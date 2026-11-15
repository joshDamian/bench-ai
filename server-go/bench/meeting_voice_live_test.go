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
	time.Sleep(3 * time.Second) // the listener loads its model
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
