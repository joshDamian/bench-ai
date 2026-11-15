package bench

// Living results: boards.mjs. A schedule that keeps one board, updated in place each run.

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const boardKeep = 10

var (
	boardNewMD   = regexp.MustCompile(`(?m)·\s*New\s*$`)
	boardNewHTML = regexp.MustCompile(`(?i)data-status=["']new["']`)
	boardTitleRe = regexp.MustCompile(`(?i)<title>([^<]+)</title>`)
)

func BoardBase(projectPath, title string) string {
	return filepath.Join(projectPath, "boards", NoteSlug(title))
}

func FindBoard(projectPath, title string) string {
	for _, ext := range []string{".md", ".html"} {
		f := BoardBase(projectPath, title) + ext
		if _, err := os.Stat(f); err == nil {
			return f
		}
	}
	return ""
}

func boardHistoryDir(projectPath, title string) string {
	return filepath.Join(projectPath, "boards", ".history", NoteSlug(title))
}

func SnapshotBoard(projectPath, title string) {
	f := FindBoard(projectPath, title)
	if f == "" {
		return
	}
	dir := boardHistoryDir(projectPath, title)
	os.MkdirAll(dir, 0o755)
	st, err := os.Stat(f)
	if err != nil {
		return
	}
	stamp := strings.Replace(isoTime(st.ModTime())[:16], ":", "-", 1)
	if b, err := os.ReadFile(f); err == nil {
		os.WriteFile(filepath.Join(dir, stamp+filepath.Ext(f)), b, 0o644)
	}
	names := BoardHistory(projectPath, title)
	for i, n := range names {
		if i >= boardKeep {
			os.Remove(filepath.Join(dir, n.(string)))
		}
	}
}

func BoardHistory(projectPath, title string) []any {
	entries, err := os.ReadDir(boardHistoryDir(projectPath, title))
	if err != nil {
		return []any{}
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	out := []any{}
	for _, n := range names {
		out = append(out, n)
	}
	return out
}

func NewCount(full string) int {
	b, err := os.ReadFile(full)
	if err != nil {
		return 0
	}
	if strings.HasSuffix(full, ".html") {
		return len(boardNewHTML.FindAllString(string(b), -1))
	}
	return len(boardNewMD.FindAllString(string(b), -1))
}

func BoardTitle(full, fallback string) string {
	b, err := os.ReadFile(full)
	if err != nil {
		return fallback
	}
	head := string(b)
	if len(head) > 20000 {
		head = head[:20000]
	}
	if strings.HasSuffix(full, ".html") {
		if m := boardTitleRe.FindStringSubmatch(head); m != nil && strings.TrimSpace(m[1]) != "" {
			return strings.TrimSpace(m[1])
		}
		return fallback
	}
	if t := TitleOf(head, fallback); t != "" {
		return t
	}
	return fallback
}

func BoardNote(projectPath, title string) string {
	base := BoardBase(projectPath, title)
	return "Keep one board for this, not a new report: " + base + ".md (or " + base + ".html when it needs sorting or ticking off; keep using the same file every run). If it exists, update it in place: first remove the marks from the last run and drop entries marked Gone, then add what is new, update what changed, and mark them at the end of their line - \" · New\", \" · Changed: what changed\", \" · Gone\" for what is no longer there (in HTML, data-status=\"new\", \"changed\" or \"gone\" on the entry). Start the file with a \"# \" heading and a line \"Updated\" with today's date. Do not write a separate report, and give the board's path in your reply."
}

func (s *Store) SessionsOfSchedule(id string) []*Obj {
	rows := s.all("SELECT * FROM sessions WHERE schedule_id=? ORDER BY created_at DESC LIMIT 20", id)
	for _, r := range rows {
		s.session(r)
	}
	return rows
}

func (s *Service) ScheduleDetail(id string) (*Obj, error) {
	sch := s.Store.GetSchedule(id)
	if sch == nil {
		return nil, fail("No such schedule", 404)
	}
	runs := s.Store.SessionsOfSchedule(id)
	list := []any{}
	for _, r := range runs {
		list = append(list, O("id", r.Get("id"), "title", r.Get("title"), "status", r.Get("status"), "cost", r.Get("cost"), "capped", truthy(r.Get("capped")), "created_at", r.Get("created_at"), "archived", r.Get("archived_at") != nil))
	}
	var board any
	if p := s.Store.GetProject(sch.Str("project_id")); p != nil {
		if f := FindBoard(p.Str("path"), sch.Str("title")); f != "" {
			var sid any
			if len(runs) > 0 {
				sid = runs[0].Get("id")
			}
			board = O("session_id", sid, "path", f, "name", filepath.Base(f), "title", BoardTitle(f, sch.Str("title")), "new", NewCount(f), "history", BoardHistory(p.Str("path"), sch.Str("title")))
		}
	}
	return O("schedule", scheduleView(sch), "runs", list, "board", board), nil
}

// boardReports are the boards Home lists among what is ready.
func (s *Service) boardReports() []*Obj {
	out := []*Obj{}
	for _, sch := range s.Store.ListSchedules() {
		if !truthy(sch.Get("keep_board")) {
			continue
		}
		p := s.Store.GetProject(sch.Str("project_id"))
		if p == nil {
			continue
		}
		f := FindBoard(p.Str("path"), sch.Str("title"))
		if f == "" {
			continue
		}
		runs := s.Store.SessionsOfSchedule(sch.Str("id"))
		if len(runs) == 0 {
			continue
		}
		last := runs[0]
		st, err := os.Stat(f)
		if err != nil {
			continue
		}
		modified := isoTime(st.ModTime())
		seen := last.Str("seen_at") != "" && last.Str("seen_at") >= modified
		out = append(out, O("session_id", last.Get("id"), "path", f, "name", filepath.Base(f), "kind", "board", "title", BoardTitle(f, sch.Str("title")), "size", st.Size(), "modified", modified, "seen", seen, "schedule_id", sch.Get("id"), "new", NewCount(f)))
	}
	return out
}
