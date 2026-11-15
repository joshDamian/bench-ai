package bench

import (
	"testing"
	"time"
)

func TestHousekeepingArchivesAndUndoes(t *testing.T) {
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	p, err := svc.AddProject(O("path", t.TempDir(), "name", "Research", "init", true))
	if err != nil {
		t.Fatal(err)
	}
	old, _ := svc.CreateSession(O("projectId", p.Str("id"), "title", "Old"))
	pinned, _ := svc.CreateSession(O("projectId", p.Str("id"), "title", "Pinned"))
	fresh, _ := svc.CreateSession(O("projectId", p.Str("id"), "title", "Fresh"))
	long := isoTime(time.Now().Add(-40 * 24 * time.Hour))
	for _, id := range []string{old.Str("id"), pinned.Str("id")} {
		svc.Store.exec("UPDATE sessions SET updated_at=?, seen_at=? WHERE id=?", long, long, id)
	}
	svc.Store.SetPinned(pinned.Str("id"), true)

	svc.Housekeep(time.Now(), true)
	if svc.Store.GetSession(old.Str("id")).Str("archived_at") == "" {
		t.Fatal("an old conversation stayed")
	}
	if svc.Store.GetSession(pinned.Str("id")).Str("archived_at") != "" || svc.Store.GetSession(fresh.Str("id")).Str("archived_at") != "" {
		t.Fatal("a pinned or recent one went")
	}
	if n := svc.Housekeeping().Num("archived"); n != 1 {
		t.Fatalf("archive holds %v", n)
	}
	var undo *Obj
	for _, it := range svc.Activity(7).Get("items").([]*Obj) {
		if it.Str("kind") == "housekeeping" {
			undo = it
		}
	}
	if undo == nil || !truthy(undo.Get("undo")) {
		t.Fatal("housekeeping did not say what it did")
	}
	if _, err := svc.UndoActivity(str(undo.Get("id"))); err != nil {
		t.Fatal(err)
	}
	if svc.Store.GetSession(old.Str("id")).Str("archived_at") != "" {
		t.Fatal("undo did not bring it back")
	}

	// A deleted schedule waits in the archive, off, and comes back.
	sch, _ := svc.AddSchedule(O("title", "Scan", "projectId", p.Str("id"), "prompt", "look", "days", "1", "time", "08:00", "tz", "UTC"))
	if _, err := svc.DeleteSchedule(sch.Str("id"), false); err != nil {
		t.Fatal(err)
	}
	if len(svc.Store.ListSchedules()) != 0 || len(svc.Archive().Get("schedules").([]*Obj)) != 1 {
		t.Fatal("deleted schedule not in the archive")
	}
	back, err := svc.Restore("schedule", sch.Str("id"))
	if err != nil || truthy(back.Get("enabled")) {
		t.Fatal("restored schedule should come back off")
	}
	svc.DeleteSchedule(sch.Str("id"), false)
	if svc.EmptyArchive().Num("removed") != 1 {
		t.Fatal("empty archive")
	}
}
