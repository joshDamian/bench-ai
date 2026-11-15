package bench

// Activity: what Bench did, mostly while the person was not watching. Runs come from the
// sessions themselves (who started each, what it made, what it cost); what is not a run - a
// Never block, housekeeping - is kept in the activity table. Go only: the Node server does
// not have it.
//
// The morning brief is one notification, once a morning, about the night's unattended runs.

import (
	"fmt"
	"os"
	"sort"
	"strings"
	"time"
)

const activitySchema = `
  CREATE TABLE IF NOT EXISTS activity(id INTEGER PRIMARY KEY AUTOINCREMENT,at TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,
    by TEXT,detail TEXT,session_id TEXT,undo TEXT);
  CREATE INDEX IF NOT EXISTS activity_by_at ON activity(at);
`

// AddActivity keeps something that is not a run: kind is "never" or "housekeeping".
func (s *Store) AddActivity(kind, title, by, detail, sessionID string, undo *Obj) {
	var u any
	if undo != nil {
		u = string(ToJSON(undo))
	}
	var sid any
	if sessionID != "" {
		sid = sessionID
	}
	s.exec("INSERT INTO activity(at,kind,title,by,detail,session_id,undo) VALUES(?,?,?,?,?,?,?)", isoNow(), kind, title, by, detail, sid, u)
}

// minutes is a run's length in words: "3 min", "under a minute".
func minutes(from, to string) string {
	a, ok1 := parseISO(from)
	b, ok2 := parseISO(to)
	if !ok1 || !ok2 || b.Before(a) {
		return ""
	}
	m := int(b.Sub(a).Minutes())
	switch {
	case m < 1:
		return "under a minute"
	case m < 120:
		return fmt.Sprintf("%d min", m)
	}
	return fmt.Sprintf("%d h", m/60)
}

// startedBy says who started a run: you, a schedule, or a page that changed.
func (s *Service) startedBy(ss *Obj, schedules map[string]*Obj) string {
	sch := schedules[ss.Str("schedule_id")]
	switch {
	case ss.Str("schedule_id") == "":
		return "Started by you"
	case sch == nil:
		return "Started by a schedule"
	case sch.ObjAt("watch") != nil:
		return "Started when " + pageHost(sch.ObjAt("watch").Str("url")) + " changed"
	}
	return "Started by " + sch.Str("title")
}

func pageHost(u string) string {
	u = strings.TrimPrefix(strings.TrimPrefix(u, "https://"), "http://")
	u = strings.TrimPrefix(u, "www.")
	if i := strings.IndexAny(u, "/?#"); i >= 0 {
		u = u[:i]
	}
	return u
}

// Activity is the last days' runs and other doings, newest first, and how many happened on
// their own since the person last looked (runs only: Home's line counts runs).
func (s *Service) Activity(days int) *Obj {
	if days <= 0 || days > 30 {
		days = 7
	}
	since := isoTime(time.Now().Add(-time.Duration(days) * 24 * time.Hour))
	seen, _ := s.Store.GetSetting("activity_seen_at")
	schedules := map[string]*Obj{}
	for _, sch := range s.Store.ListSchedules() {
		schedules[sch.Str("id")] = sch
	}
	made := map[string][]*Obj{}
	for _, r := range s.Reports() {
		made[r.Str("session_id")] = append(made[r.Str("session_id")], r)
	}
	items := []*Obj{}
	unseen := 0
	for _, ss := range s.Store.all("SELECT * FROM sessions WHERE created_at >= ? AND project_id IS NOT NULL ORDER BY created_at DESC LIMIT 300", since) {
		s.Store.session(ss)
		var bits []string
		if rs := made[ss.Str("id")]; len(rs) > 0 {
			names := []string{}
			for _, r := range rs {
				t := r.Str("title")
				if t == "" {
					t = r.Str("name")
				}
				names = append(names, t)
			}
			bits = append(bits, strings.Join(names, ", "))
		}
		switch {
		case truthy(ss.Get("capped")):
			bits = append(bits, "stopped at its cap")
		case ss.Str("status") == "error":
			bits = append(bits, "stopped with an error")
		case ss.Str("archived_at") != "" && ss.Str("schedule_id") != "" && schedules[ss.Str("schedule_id")] != nil && schedules[ss.Str("schedule_id")].ObjAt("watch") != nil:
			bits = append(bits, "nothing new")
		}
		if d := minutes(ss.Str("created_at"), ss.Str("updated_at")); d != "" && ss.Str("status") != "running" {
			bits = append(bits, d)
		}
		badge := any(nil)
		if ss.Str("status") == "running" {
			badge = "Working"
		} else if ss.Str("seen_at") == "" || ss.Str("seen_at") < ss.Str("updated_at") {
			if len(made[ss.Str("id")]) > 0 || ss.Str("schedule_id") != "" {
				badge = "Ready"
			}
		}
		if ss.Str("schedule_id") != "" && ss.Str("created_at") > seen {
			unseen++
		}
		items = append(items, O("at", ss.Get("created_at"), "kind", "run", "title", ss.Get("title"), "by", s.startedBy(ss, schedules),
			"detail", strings.Join(bits, " · "), "session_id", ss.Get("id"), "cost", ss.Num("cost"), "badge", badge, "undo", false))
	}
	for _, a := range s.Store.all("SELECT * FROM activity WHERE at >= ? ORDER BY at DESC LIMIT 200", since) {
		badge := any(nil)
		if a.Str("kind") == "never" {
			badge = "Never"
		}
		items = append(items, O("at", a.Get("at"), "kind", a.Get("kind"), "title", a.Get("title"), "by", a.Get("by"), "detail", a.Get("detail"),
			"session_id", a.Get("session_id"), "cost", 0.0, "badge", badge, "undo", a.Get("undo") != nil, "id", a.Get("id")))
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].Str("at") > items[j].Str("at") })
	var seenAt any
	if seen != "" {
		seenAt = seen
	}
	return O("items", items, "unseen", unseen, "seen_at", seenAt)
}

// SeenActivity marks Activity looked at: Home's "while you were away" line goes.
func (s *Service) SeenActivity() *Obj {
	s.Store.SetSetting("activity_seen_at", isoNow())
	return O("ok", true)
}

// --- the morning brief ------------------------------------------------------------------

// briefZone is the person's time zone, as their schedules give it.
func (s *Service) briefZone() *time.Location {
	count := map[string]int{}
	best := ""
	for _, sch := range s.Store.ListSchedules() {
		count[sch.Str("tz")]++
		if count[sch.Str("tz")] > count[best] {
			best = sch.Str("tz")
		}
	}
	if best == "" {
		if tz := os.Getenv("TZ"); tz != "" {
			best = tz
		}
	}
	return zone(best)
}

// MorningBrief sends one notification after 07:30, once a day, when runs happened on their own
// since the last one. It returns what it sent, or nil.
func (s *Service) MorningBrief(at time.Time) *Obj {
	if v, _ := s.Store.GetSetting("morning_brief"); v == "0" || s.Notifier == nil {
		return nil
	}
	loc := s.briefZone()
	local := at.In(loc)
	if local.Format("15:04") < "07:30" || local.Hour() >= 12 {
		return nil
	}
	today := local.Format("2006-01-02")
	last, _ := s.Store.GetSetting("brief_day")
	if last == today {
		return nil
	}
	s.Store.SetSetting("brief_day", today)
	since := isoTime(at.Add(-18 * time.Hour))
	if prev, ok := s.Store.GetSetting("brief_at"); ok && prev > since {
		since = prev
	}
	s.Store.SetSetting("brief_at", isoTime(at))
	runs := s.Store.all("SELECT * FROM sessions WHERE schedule_id IS NOT NULL AND created_at >= ? ORDER BY created_at", since)
	nevers := s.Store.all("SELECT * FROM activity WHERE kind='never' AND at >= ? ORDER BY at", since)
	if len(runs) == 0 && len(nevers) == 0 {
		return nil
	}
	ready := 0
	var lines []string
	for _, r := range runs {
		s.Store.session(r)
		if r.Str("archived_at") != "" {
			continue
		}
		if r.Str("seen_at") == "" || r.Str("seen_at") < r.Str("updated_at") {
			ready++
		}
		switch {
		case truthy(r.Get("capped")):
			lines = append(lines, r.Str("title")+" stopped at its cap")
		case r.Str("status") == "error":
			lines = append(lines, r.Str("title")+" stopped with an error")
		default:
			lines = append(lines, r.Str("title")+" is ready")
		}
	}
	for _, n := range nevers {
		lines = append(lines, n.Str("title"))
	}
	title := fmt.Sprintf("Overnight: %d %s", len(runs), plural(len(runs), "run"))
	if ready > 0 {
		title += fmt.Sprintf(", %d ready", ready)
	}
	body := strings.Join(lines, ". ")
	if len(lines) > 0 {
		body += "."
	}
	if len([]rune(body)) > 240 {
		body = string([]rune(body)[:237]) + "…"
	}
	p := O("title", title, "body", body, "url", "/#/activity", "tag", "morning-brief")
	s.Notifier(p)
	return p
}

// BriefSettings: whether the morning brief is on.
func (s *Service) BriefSettings() *Obj {
	v, _ := s.Store.GetSetting("morning_brief")
	return O("morning_brief", v != "0")
}

func (s *Service) SetBrief(o *Obj) *Obj {
	if o.Has("morning_brief") {
		v := "1"
		if !truthy(o.Get("morning_brief")) {
			v = "0"
		}
		s.Store.SetSetting("morning_brief", v)
	}
	return s.BriefSettings()
}
