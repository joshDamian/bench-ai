package bench

import (
	"testing"
	"time"
)

func TestWatchReadsAPageAndWhatChanged(t *testing.T) {
	got := PageText(`<html><head><style>a{}</style><script>x()</script></head><body><h1>Jobs</h1><ul><li>Designer, Lagos</li><li>PM &amp; Ops</li></ul></body></html>`)
	if got != "Jobs\nDesigner, Lagos\nPM & Ops" {
		t.Fatalf("PageText = %q", got)
	}
	if a := AddedLines(got, got+"\nEngineer, Lagos"); a != "Engineer, Lagos\n" {
		t.Fatalf("AddedLines = %q", a)
	}
	if !IsNothingNew(" Nothing new.\n") || IsNothingNew("Nothing new. But one thing") {
		t.Fatal("IsNothingNew")
	}
	if _, err := watchFields(O("url", "jobs page")); err == nil {
		t.Fatal("a bad address passes")
	}
	w, _ := watchFields(O("url", "https://example.com/jobs", "everyHours", 2.0))
	if w.Num("every_hours") != 2 {
		t.Fatal("every_hours")
	}
	sch := O("enabled", true, "watch", w, "checked_at", isoTime(time.Now().Add(-time.Hour)))
	if CheckDue(sch, time.Now()) || !CheckDue(sch, time.Now().Add(2*time.Hour)) || Due(sch, time.Now()) || NextRun(sch, time.Now()) != nil {
		t.Fatal("a watch runs on its page, not a clock")
	}
}
