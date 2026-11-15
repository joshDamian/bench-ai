package bench

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMeetingSpeechRules(t *testing.T) {
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	m := svc.Meeting()
	say := toolPrefix + "meeting_say"
	small, answer := O("text", "Hi all", "kind", "small"), O("text", "Yes, Friday works", "kind", "answer")
	if m.Rule(say, small) != "ask" || m.Rule(say, answer) != "ask" {
		t.Fatal("ask: every line waits")
	}
	m.SetSpeech("some")
	if m.Rule(say, small) != "allow" || m.Rule(say, answer) != "ask" {
		t.Fatal("some: small talk only")
	}
	m.SetSpeech("allow")
	if m.Rule(say, answer) != "allow" {
		t.Fatal("allow: speaks freely")
	}
	if m.Rule("Bash", O()) != "" || m.Rule(toolPrefix+"meeting_start", O()) != "allow" {
		t.Fatal("other tools")
	}
	if _, err := m.SetSpeech("loud"); err == nil {
		t.Fatal("unknown speech")
	}
}

func TestAboutFirstName(t *testing.T) {
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	a := svc.Runtime.About()
	if a.FirstName() != "" {
		t.Fatal("no About me, no name")
	}
	os.MkdirAll(filepath.Dir(a.File()), 0o755)
	os.WriteFile(a.File(), []byte("# About me\n\n## Who you are\n\nJoshua Avoaja, CTO at UseAzza. Lagos.\n"), 0o644)
	if n := a.FirstName(); n != "Joshua" {
		t.Fatalf("FirstName = %q", n)
	}
	if l := svc.Meeting().HoldingLine(); l != "One moment, let me confirm that with Joshua." {
		t.Fatal(l)
	}
}
