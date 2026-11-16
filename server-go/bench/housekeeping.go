package bench

// Housekeeping and the archive (Go only). Bench tidies on its own, once a day, and says what it
// did in Activity: it archives conversations not opened in 30 days, removes the branches of
// merged tasks, and frees disk from the work folders of finished tasks. What it archives can
// be brought back; the archive is emptied only when the person asks.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

var houseMu sync.Mutex

type houseSettings struct {
	ArchiveIdle   bool `json:"archive_idle"`
	PruneBranches bool `json:"prune_branches"`
	FreeDisk      bool `json:"free_disk"`
	// KeepBoard is what a new schedule starts with: one board, not a report per run.
	KeepBoard bool `json:"keep_board"`
	// Freed is what the last free-disk pass removed, and when.
	Freed   float64 `json:"freed"`
	FreedAt string  `json:"freed_at"`
}

func (s *Service) houseFile() string { return filepath.Join(s.Store.Dir, "housekeeping.json") }

func (s *Service) house() houseSettings {
	h := houseSettings{ArchiveIdle: true, PruneBranches: true, FreeDisk: true, KeepBoard: true}
	if b, err := os.ReadFile(s.houseFile()); err == nil {
		json.Unmarshal(b, &h)
	}
	return h
}

func (s *Service) saveHouse(h houseSettings) {
	b, _ := json.MarshalIndent(h, "", "  ")
	os.WriteFile(s.houseFile(), b, 0o644)
}

func (s *Service) houseView(h houseSettings) *Obj {
	var freed any
	if h.FreedAt != "" {
		freed = O("bytes", h.Freed, "at", h.FreedAt)
	}
	n := len(s.Store.all("SELECT id FROM sessions WHERE archived_at IS NOT NULL")) + len(s.Store.ArchivedSchedules())
	return O("archive_idle", h.ArchiveIdle, "prune_branches", h.PruneBranches, "free_disk", h.FreeDisk, "keep_board", h.KeepBoard, "freed", freed, "archived", n)
}

func (s *Service) Housekeeping() *Obj {
	houseMu.Lock()
	defer houseMu.Unlock()
	return s.houseView(s.house())
}

func (s *Service) SetHousekeeping(o *Obj) *Obj {
	houseMu.Lock()
	defer houseMu.Unlock()
	h := s.house()
	for k, p := range map[string]*bool{"archive_idle": &h.ArchiveIdle, "prune_branches": &h.PruneBranches, "free_disk": &h.FreeDisk, "keep_board": &h.KeepBoard} {
		if o.Has(k) && !isUndef(o.Get(k)) {
			*p = truthy(o.Get(k))
		}
	}
	s.saveHouse(h)
	return s.houseView(h)
}

func bytesWord(b float64) string {
	switch {
	case b >= 1<<30:
		return fmt.Sprintf("%.1f GB", b/(1<<30))
	case b >= 1<<20:
		return fmt.Sprintf("%.0f MB", b/(1<<20))
	}
	return fmt.Sprintf("%.0f KB", b/(1<<10))
}

// Housekeep runs the passes that are on, once a day, and logs each that did something.
func (s *Service) Housekeep(at time.Time, force bool) {
	day := at.Format("2006-01-02")
	if last, _ := s.Store.GetSetting("housekeep_day"); last == day && !force {
		return
	}
	s.Store.SetSetting("housekeep_day", day)
	houseMu.Lock()
	h := s.house()
	houseMu.Unlock()

	if h.ArchiveIdle {
		cutoff := isoTime(at.Add(-30 * 24 * time.Hour))
		var ids []any
		for _, r := range s.Store.all(`SELECT id FROM sessions WHERE archived_at IS NULL AND (pinned IS NULL OR pinned=0) AND status IN ('idle','error')
			AND COALESCE(seen_at, updated_at) < ? AND updated_at < ?`, cutoff, cutoff) {
			if _, err := s.ArchiveSession(r.Str("id"), true); err == nil {
				ids = append(ids, r.Str("id"))
			}
		}
		if len(ids) > 0 {
			s.Store.AddActivity("housekeeping", fmt.Sprintf("Archived %d %s", len(ids), plural(len(ids), "conversation")), "Housekeeping", "Not opened in 30 days", "", O("sessions", ids))
		}
	}

	var freed float64
	branches := 0
	for _, t := range s.Store.ListTasks("") {
		state := t.Str("state")
		if state == "active" {
			continue
		}
		p := s.Store.GetProject(t.Str("project_id"))
		if p == nil {
			continue
		}
		wt := t.Str("worktree")
		if h.FreeDisk && wt != "" && exists(wt) {
			// A finished task's folder is only kept a week, in case someone looks back.
			if u, ok := parseISO(t.Str("updated_at")); !ok || at.Sub(u) > 7*24*time.Hour {
				b, _ := sizeOf(wt)
				if RemoveWorktree(p.Str("path"), wt) == nil {
					freed += b
				}
			}
		}
		if h.PruneBranches && state == "merged" && t.Str("branch") != "" && branchExists(p.Str("path"), t.Str("branch")) {
			if DeleteBranch(p.Str("path"), t.Str("branch")) == nil {
				branches++
			}
		}
	}
	if branches > 0 {
		s.Store.AddActivity("housekeeping", fmt.Sprintf("Removed %d merged %s", branches, plural(branches, "branch")), "Housekeeping", "Their work stays in main", "", nil)
	}
	if freed > 0 {
		houseMu.Lock()
		h = s.house()
		h.Freed, h.FreedAt = freed, isoTime(at)
		s.saveHouse(h)
		houseMu.Unlock()
		s.Store.AddActivity("housekeeping", "Freed "+bytesWord(freed), "Housekeeping", "From the folders of finished tasks", "", nil)
	}
}

func branchExists(repo, branch string) bool {
	_, err := Git(repo, []string{"rev-parse", "--verify", "--quiet", "refs/heads/" + branch})
	return err == nil
}

// --- the archive -----------------------------------------------------------------------

// Archive is everything put away that can come back: conversations and schedules.
func (s *Service) Archive() *Obj {
	sessions := []*Obj{}
	for _, r := range s.Store.all("SELECT id,title,project_id,archived_at,schedule_id FROM sessions WHERE archived_at IS NOT NULL ORDER BY archived_at DESC LIMIT 500") {
		sessions = append(sessions, r)
	}
	return O("sessions", sessions, "schedules", s.Store.ArchivedSchedules())
}

// Restore brings one thing back from the archive.
func (s *Service) Restore(kind, id string) (*Obj, error) {
	switch kind {
	case "session":
		return s.ArchiveSession(id, false)
	case "schedule":
		if !s.Store.RestoreSchedule(id) {
			return nil, fail("It is not in the archive", 404)
		}
		return scheduleView(s.Store.GetSchedule(id)), nil
	}
	return nil, fail("Restore a session or a schedule")
}

// EmptyArchive removes for good what is in the archive.
func (s *Service) EmptyArchive() *Obj {
	n := 0
	for _, r := range s.Store.all("SELECT id FROM sessions WHERE archived_at IS NOT NULL") {
		if _, err := s.DeleteSession(r.Str("id")); err == nil {
			n++
		}
	}
	for _, r := range s.Store.ArchivedSchedules() {
		if s.Store.PurgeSchedule(r.Str("id")) {
			os.Remove(watchFile(s.Store.Dir, r.Str("id")))
			n++
		}
	}
	return O("removed", n)
}

// UndoActivity undoes what housekeeping did, where it can: archived conversations come back.
func (s *Service) UndoActivity(id string) (*Obj, error) {
	a := s.Store.one("SELECT * FROM activity WHERE id=?", id)
	if a == nil || a.Get("undo") == nil {
		return nil, fail("There is nothing to undo", 404)
	}
	u, _ := ParseJSON(a.Str("undo")).(*Obj)
	n := 0
	if u != nil {
		for _, sid := range arr(u.Get("sessions")) {
			if _, err := s.ArchiveSession(str(sid), false); err == nil {
				n++
			}
		}
	}
	s.Store.exec("UPDATE activity SET undo=NULL, detail=COALESCE(detail,'') || ' · undone' WHERE id=?", id)
	return O("restored", n), nil
}
