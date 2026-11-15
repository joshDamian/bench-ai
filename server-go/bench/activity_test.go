package bench

import (
	"testing"
	"time"
)

func TestActivityAndMorningBrief(t *testing.T) {
	t.Setenv("TZ", "Africa/Lagos")
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	p, err := svc.AddProject(O("path", t.TempDir(), "name", "Research", "init", true))
	if err != nil {
		t.Fatal(err)
	}
	sch, err := svc.AddSchedule(O("title", "Hackathon scan", "projectId", p.Str("id"), "prompt", "look", "days", "6", "time", "06:00", "tz", "Africa/Lagos"))
	if err != nil {
		t.Fatal(err)
	}
	ss, err := svc.CreateSession(O("projectId", p.Str("id"), "title", "Hackathon scan"))
	if err != nil {
		t.Fatal(err)
	}
	svc.Store.UpdateSession(ss.Str("id"), O("schedule_id", sch.Str("id"), "cost", 0.4))
	svc.Store.AddActivity("never", "Stopped before it could send email", "In Job hunt", "On your Never list", ss.Str("id"), nil)

	a := svc.Activity(7)
	items := a.Get("items").([]*Obj)
	if len(items) != 2 || a.Num("unseen") != 1 {
		t.Fatalf("activity = %s", ToJSON(a))
	}
	if items[1].Str("by") != "Started by Hackathon scan" && items[0].Str("by") != "Started by Hackathon scan" {
		t.Fatalf("who started it: %s", ToJSON(items))
	}
	svc.SeenActivity()
	if svc.Activity(7).Num("unseen") != 0 {
		t.Fatal("looked at, still unseen")
	}

	var sent []*Obj
	svc.Notifier = func(p *Obj) int { sent = append(sent, p); return 1 }
	lagos, _ := time.LoadLocation("Africa/Lagos")
	if svc.MorningBrief(time.Date(2026, 11, 15, 6, 0, 0, 0, lagos).Add(24*time.Hour)) != nil {
		t.Fatal("a brief before 07:30")
	}
	now := time.Now().In(lagos)
	morning := time.Date(now.Year(), now.Month(), now.Day(), 8, 0, 0, 0, lagos)
	if morning.Before(now) {
		// The runs above were made just now; the brief covers the 18 hours before it.
		morning = morning.Add(24 * time.Hour)
	}
	if !morning.Add(-18 * time.Hour).Before(now) {
		t.Skip("too far from the morning to test the brief window here")
	}
	b := svc.MorningBrief(morning)
	if b == nil || len(sent) != 1 || b.Str("title") != "Overnight: 1 run, 1 ready" {
		t.Fatalf("brief = %s", ToJSON(b))
	}
	if svc.MorningBrief(morning.Add(time.Hour)) != nil {
		t.Fatal("two briefs in one morning")
	}
}
