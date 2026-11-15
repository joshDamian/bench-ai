package bench

import (
	"context"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestZZAudioLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	if err := AudioSetup(); err != nil {
		t.Fatal(err)
	}
	out, _ := pactl("list", "short", "sources")
	t.Log(out)
	// Record the microphone Chromium would hear while Bench speaks.
	rec := exec.Command("parec", "--device="+sourceMic, "--format=s16le", "--rate=16000", "--channels=1")
	pipe, _ := rec.StdoutPipe()
	rec.Start()
	got := make(chan float64, 1)
	go func() {
		buf := make([]byte, 640)
		best := 0.0
		deadline := time.Now().Add(8 * time.Second)
		for time.Now().Before(deadline) {
			if _, err := ioReadFull(pipe, buf); err != nil {
				break
			}
			if r := rms(buf); r > best {
				best = r
			}
		}
		got <- best
	}()
	time.Sleep(500 * time.Millisecond)
	svc, _ := NewService(t.TempDir())
	defer svc.Scheduler.Stop()
	if err := svc.Meeting().Speak("Hello, this is a test of the meeting voice."); err != nil {
		t.Fatal(err)
	}
	peak := <-got
	rec.Process.Kill()
	t.Logf("peak RMS on the microphone: %.0f", peak)
	if peak < 350 {
		t.Fatal("the microphone heard nothing")
	}
}

func TestZZListenLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	if err := AudioSetup(); err != nil {
		t.Fatal(err)
	}
	clips := make(chan int, 10)
	transcribeFn = func(pcm []byte) string { clips <- len(pcm); return "" }
	defer func() { transcribeFn = transcribe }()
	svc, _ := NewService(t.TempDir())
	defer svc.Scheduler.Stop()
	m := svc.Meeting()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go m.listen(ctx, "s1")
	time.Sleep(500 * time.Millisecond)
	// Two sentences into the call's speaker, with a pause between: two utterances.
	for _, line := range []string{"Can you hear me?", "What is Joshua working on today?"} {
		f := t.TempDir() + "/x.wav"
		exec.Command("espeak-ng", "-w", f, line).Run()
		exec.Command("paplay", "--device="+sinkOut, f).Run()
		time.Sleep(1500 * time.Millisecond)
	}
	n := 0
	timeout := time.After(3 * time.Second)
	for n < 2 {
		select {
		case b := <-clips:
			n++
			t.Logf("utterance %d: %.1fs", n, float64(b)/32000)
		case <-timeout:
			t.Fatalf("heard %d utterances, want 2", n)
		}
	}
}
