package bench

import (
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// With the helper installed (BENCH_GO_HOME): Kokoro speaks, the call is heard, and talking
// over the session stops it.
func TestMeetingVoiceLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	m := svc.Meeting()
	if r := m.Ready(); r.Str("voice") != "kokoro" || !truthy(r.Get("listen")) {
		t.Fatalf("ready: %s", ToJSON(r))
	}
	start := time.Now()
	if err := m.Speak("Hi Josh, this is the new voice."); err != nil {
		t.Fatal(err)
	}
	t.Logf("first line said in %.1fs (includes loading the voice)", time.Since(start).Seconds())
	start = time.Now()
	m.Speak("And a second line, with the voice already loaded.")
	t.Logf("second line said in %.1fs", time.Since(start).Seconds())

	// Listening: someone in the call says something.
	if err := m.Start("s1"); err != nil {
		t.Fatal(err)
	}
	waitHearing(t, m)
	h, _ := m.voiceHelper()
	f, err := h.say("Can you send me the shortlist after the call?", 16, 1.0)
	if err != nil {
		t.Fatal(err)
	}
	exec.Command("paplay", "--device="+sinkOut, f).Run()
	for i := 0; i < 50 && m.View().Num("heard") == 0; i++ {
		time.Sleep(100 * time.Millisecond)
	}
	if m.View().Num("heard") == 0 {
		t.Fatalf("heard nothing: %v", m.View().Get("error"))
	}
	t.Logf("heard it; delivering to the session gave: %v", m.View().Get("error"))

	// Talking over it: a long reply, cut off when someone speaks.
	done := m.say("This is a long answer. It goes on for a while. There is a lot to say about it. And more still after that. Nearly done now. One last thing.")
	time.Sleep(2500 * time.Millisecond)
	f2, _ := h.say("Wait, hold on a second, I have a question.", 16, 1.0)
	cut := time.Now()
	go exec.Command("paplay", "--device="+sinkOut, f2).Run()
	select {
	case <-done:
		t.Logf("stopped %.1fs after they started talking", time.Since(cut).Seconds())
	case <-time.After(12 * time.Second):
		t.Fatal("kept talking")
	}
	m.mu.Lock()
	said := m.interrupted
	m.mu.Unlock()
	if !strings.Contains(said, ".") {
		t.Logf("last thing said before the cut: %q", said)
	}
	m.Stop()
}

// A line given while someone is talking waits for them to finish.
func TestMeetingHoldsLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	m := svc.Meeting()
	if err := m.Start("s1"); err != nil {
		t.Fatal(err)
	}
	defer m.Stop()
	waitHearing(t, m)
	h, _ := m.voiceHelper()
	long, _ := h.say("So what I was thinking is that we could look at the three roles again, and maybe compare them on pay and on the team.", 16, 1.0)
	theirs := make(chan time.Time, 1)
	go func() { exec.Command("paplay", "--device="+sinkOut, long).Run(); theirs <- time.Now() }()
	time.Sleep(1500 * time.Millisecond) // they are mid-sentence
	if !m.talking() {
		t.Fatal("their voice was not noticed")
	}
	start := time.Now()
	m.say("Sure.")
	var began time.Time
	for i := 0; i < 800; i++ {
		m.mu.Lock()
		p := m.playingText
		m.mu.Unlock()
		if p != "" {
			began = time.Now()
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	ended := <-theirs
	_ = start
	if began.IsZero() {
		t.Fatal("never spoke")
	}
	t.Logf("asked to speak 1.5s into their sentence; spoke %.1fs after they finished", began.Sub(ended).Seconds())
	if began.Before(ended) {
		t.Fatal("spoke over them")
	}
}

func waitHearing(t *testing.T, m *Meeting) {
	for i := 0; i < 600 && !truthy(m.View().Get("hearing")); i++ {
		time.Sleep(100 * time.Millisecond)
	}
	if !truthy(m.View().Get("hearing")) {
		t.Fatal("the listener never got ready")
	}
}

// Heard twice: the live pass for the turn, the accurate pass for the words.
func TestMeetingAccurateLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	m := svc.Meeting()
	if err := m.Start("s1"); err != nil {
		t.Fatal(err)
	}
	defer m.Stop()
	waitHearing(t, m)
	h, _ := m.voiceHelper()
	for _, line := range []string{"Hey, what did you find in the research on backend roles in Lagos?", "How do I access the deck after the call?"} {
		f, _ := h.say(line, 21, 1.0)
		before := m.View().Num("heard")
		exec.Command("paplay", "--device="+sinkOut, f).Run()
		ended := time.Now()
		for i := 0; i < 100 && m.View().Num("heard") == before; i++ {
			time.Sleep(50 * time.Millisecond)
		}
		t.Logf("%.1fs after they stopped: %q", time.Since(ended).Seconds(), m.View().Str("last_heard"))
	}
}
