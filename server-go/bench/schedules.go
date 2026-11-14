package bench

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata"
)

// Seen, pinned, reports and schedules (src/schedules.mjs, src/reports.mjs and their parts of
// src/store.mjs and src/service.mjs).

// --- store ---------------------------------------------------------------------------

func (s *Store) MarkSeen(id string) *Obj {
	s.exec("UPDATE sessions SET seen_at=? WHERE id=?", isoNow(), id)
	return s.GetSession(id)
}

func (s *Store) SetPinned(id string, on bool) *Obj {
	s.exec("UPDATE sessions SET pinned=? WHERE id=?", sqlVal(on), id)
	return s.GetSession(id)
}

func (s *Store) schedule(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	r.Set("enabled", truthy(r.Get("enabled")))
	r.Set("keep_board", truthy(r.Get("keep_board")))
	return r
}

func (s *Store) ListSchedules() []*Obj {
	rows := s.all("SELECT * FROM schedules ORDER BY created_at")
	for _, r := range rows {
		s.schedule(r)
	}
	return nonNil(rows)
}

func (s *Store) GetSchedule(id string) *Obj {
	return s.schedule(s.one("SELECT * FROM schedules WHERE id=?", id))
}

func (s *Store) AddSchedule(o *Obj) *Obj {
	id := NewID()
	enabled := 1
	if v, ok := o.Get("enabled").(bool); ok && !v {
		enabled = 0
	}
	keep := 0
	if truthy(o.Get("keep_board")) {
		keep = 1
	}
	s.exec("INSERT INTO schedules(id,title,project_id,prompt,days,time,tz,mode,model_id,provider_id,effort,enabled,created_at,keep_board) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
		id, o.Str("title"), o.Str("project_id"), o.Str("prompt"), o.Str("days"), o.Str("time"), o.Str("tz"), o.Get("mode"), o.Get("model_id"), o.Get("provider_id"), o.Get("effort"), enabled, isoNow(), keep)
	return s.GetSchedule(id)
}

func (s *Store) UpdateSchedule(id string, patch *Obj) *Obj {
	for _, k := range []string{"enabled", "keep_board"} {
		if v, ok := patch.Get(k).(bool); ok {
			patch = patch.Clone().Set(k, sqlVal(v))
		}
	}
	s.patch("schedules", id, patch)
	return s.GetSchedule(id)
}

func (s *Store) DeleteSchedule(id string) bool {
	return changes(s.exec("DELETE FROM schedules WHERE id=?", id)) > 0
}

// --- schedule times ----------------------------------------------------------------------

func zone(tz string) *time.Location {
	if l, err := time.LoadLocation(tz); err == nil {
		return l
	}
	return time.UTC
}

func validZone(tz string) bool {
	_, err := time.LoadLocation(tz)
	return err == nil && tz != ""
}

func scheduleDays(sch *Obj) []int {
	var out []int
	for _, x := range strings.Split(sch.Str("days"), ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(x)); err == nil && n >= 0 && n <= 6 {
			out = append(out, n)
		}
	}
	return out
}

func hasDay(days []int, d int) bool {
	for _, x := range days {
		if x == d {
			return true
		}
	}
	return false
}

// NextRun is when a schedule next starts a session, or nil when it is off or has no days.
func NextRun(sch *Obj, from time.Time) *time.Time {
	if !truthy(sch.Get("enabled")) {
		return nil
	}
	days := scheduleDays(sch)
	if len(days) == 0 {
		return nil
	}
	loc := zone(sch.Str("tz"))
	today := from.In(loc)
	hh, mm := 0, 0
	fmt.Sscanf(sch.Str("time"), "%d:%d", &hh, &mm)
	ranToday := false
	if lr, ok := parseISO(sch.Str("last_run_at")); ok && sch.Str("last_run_at") != "" {
		ranToday = lr.In(loc).Format("2006-01-02") == today.Format("2006-01-02")
	}
	for i := 0; i < 8; i++ {
		day := time.Date(today.Year(), today.Month(), today.Day()+i, 12, 0, 0, 0, loc)
		if !hasDay(days, int(day.Weekday())) {
			continue
		}
		at := time.Date(day.Year(), day.Month(), day.Day(), hh, mm, 0, 0, loc)
		if at.After(from) && !(i == 0 && ranToday) {
			return &at
		}
	}
	return nil
}

// Due is whether a schedule should start a session now: on one of its days, at or after its
// time, and not yet that day.
func Due(sch *Obj, at time.Time) bool {
	if !truthy(sch.Get("enabled")) {
		return false
	}
	loc := zone(sch.Str("tz"))
	now := at.In(loc)
	if !hasDay(scheduleDays(sch), int(now.Weekday())) || now.Format("15:04") < sch.Str("time") {
		return false
	}
	lr, ok := parseISO(sch.Str("last_run_at"))
	return sch.Str("last_run_at") == "" || !ok || lr.In(loc).Format("2006-01-02") != now.Format("2006-01-02")
}

func scheduleView(sch *Obj) *Obj {
	if n := NextRun(sch, time.Now()); n != nil {
		sch.Set("next_run_at", isoTime(*n))
	} else {
		sch.Set("next_run_at", nil)
	}
	return sch
}

// Scheduler checks the schedules every half minute and starts what is due.
type Scheduler struct {
	svc  *Service
	stop chan struct{}
	once sync.Once
}

func NewScheduler(svc *Service) *Scheduler {
	sc := &Scheduler{svc: svc, stop: make(chan struct{})}
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-sc.stop:
				return
			case now := <-t.C:
				sc.Tick(now)
			}
		}
	}()
	return sc
}

func (sc *Scheduler) Tick(at time.Time) {
	for _, sch := range sc.svc.Store.ListSchedules() {
		if !Due(sch, at) {
			continue
		}
		// Paused: it waits, and runs later today if unpaused. Over the day's cap: skipped for today.
		if ok, paused, reason := sc.svc.Unattended(); !ok {
			if paused {
				continue
			}
			sc.svc.Store.UpdateSchedule(sch.Str("id"), O("last_run_at", isoTime(at)))
			fmt.Fprintf(os.Stderr, "schedule %s: skipped, %s\n", sch.Str("title"), reason)
			continue
		}
		if _, err := sc.svc.RunSchedule(sch.Str("id")); err != nil {
			sc.svc.Store.UpdateSchedule(sch.Str("id"), O("last_run_at", isoTime(at)))
			fmt.Fprintf(os.Stderr, "schedule %s: %v\n", sch.Str("title"), err)
		}
	}
}

func (sc *Scheduler) Stop() { sc.once.Do(func() { close(sc.stop) }) }

// --- service -------------------------------------------------------------------------

func (s *Service) MarkSeen(id string) (*Obj, error) {
	if _, err := s.Session(id); err != nil {
		return nil, err
	}
	ss := s.Store.MarkSeen(id)
	s.Runtime.Emit("session", ss)
	return ss, nil
}

func (s *Service) Schedules() []*Obj {
	out := s.Store.ListSchedules()
	for _, x := range out {
		scheduleView(x)
	}
	return out
}

var hhmm = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)

func (s *Service) scheduleFields(o *Obj, partial bool) (*Obj, error) {
	row := O()
	need := func(k string) bool { return !partial || (o.Has(k) && !isUndef(o.Get(k))) }
	if need("title") {
		if strings.TrimSpace(str(o.Get("title"))) == "" {
			return nil, fail("A schedule needs a title")
		}
		row.Set("title", strings.TrimSpace(str(o.Get("title"))))
	}
	if need("prompt") {
		if strings.TrimSpace(str(o.Get("prompt"))) == "" {
			return nil, fail("A schedule needs a prompt")
		}
		row.Set("prompt", strings.TrimSpace(str(o.Get("prompt"))))
	}
	if need("projectId") {
		p, err := s.Project(str(o.Get("projectId")))
		if err != nil {
			return nil, err
		}
		row.Set("project_id", p.Str("id"))
	}
	if need("days") {
		var raw []string
		if a, ok := o.Get("days").([]any); ok {
			for _, x := range a {
				raw = append(raw, str(x))
			}
		} else {
			raw = strings.Split(str(o.Get("days")), ",")
		}
		seen := map[int]bool{}
		var days []string
		for _, x := range raw {
			f, err := strconv.ParseFloat(strings.TrimSpace(x), 64)
			n := int(f)
			if err != nil || float64(n) != f || n < 0 || n > 6 || seen[n] {
				continue
			}
			seen[n] = true
			days = append(days, strconv.Itoa(n))
		}
		if len(days) == 0 {
			return nil, fail("Pick at least one day")
		}
		sort.Strings(days)
		row.Set("days", strings.Join(days, ","))
	}
	if need("time") {
		if !hhmm.MatchString(str(o.Get("time"))) {
			return nil, fail("Time is HH:MM, 24-hour")
		}
		row.Set("time", str(o.Get("time")))
	}
	if need("tz") {
		tz := str(o.Get("tz"))
		if tz == "" {
			tz = "UTC"
		}
		if !validZone(tz) {
			return nil, fail("Unknown time zone " + tz)
		}
		row.Set("tz", tz)
	}
	if o.Has("mode") && !isUndef(o.Get("mode")) {
		m := o.Get("mode")
		if m != nil && !inList(Modes, str(m)) {
			return nil, fail("Unknown mode " + str(m))
		}
		row.Set("mode", m)
	}
	for _, kc := range [][2]string{{"modelId", "model_id"}, {"providerId", "provider_id"}, {"effort", "effort"}} {
		if o.Has(kc[0]) && !isUndef(o.Get(kc[0])) {
			if truthy(o.Get(kc[0])) {
				row.Set(kc[1], o.Get(kc[0]))
			} else {
				row.Set(kc[1], nil)
			}
		}
	}
	if o.Has("enabled") && !isUndef(o.Get("enabled")) {
		row.Set("enabled", truthy(o.Get("enabled")))
	}
	if o.Has("keepBoard") && !isUndef(o.Get("keepBoard")) {
		row.Set("keep_board", truthy(o.Get("keepBoard")))
	}
	return row, nil
}

func (s *Service) AddSchedule(o *Obj) (*Obj, error) {
	row, err := s.scheduleFields(o, false)
	if err != nil {
		return nil, err
	}
	return scheduleView(s.Store.AddSchedule(row)), nil
}

func (s *Service) UpdateSchedule(id string, o *Obj) (*Obj, error) {
	if s.Store.GetSchedule(id) == nil {
		return nil, fail("No such schedule", 404)
	}
	row, err := s.scheduleFields(o, true)
	if err != nil {
		return nil, err
	}
	return scheduleView(s.Store.UpdateSchedule(id, row)), nil
}

func (s *Service) DeleteSchedule(id string) (*Obj, error) {
	if !s.Store.DeleteSchedule(id) {
		return nil, fail("No such schedule", 404)
	}
	return O("ok", true), nil
}

func (s *Service) RunSchedule(id string) (*Obj, error) {
	sch := s.Store.GetSchedule(id)
	if sch == nil {
		return nil, fail("No such schedule", 404)
	}
	o := O("projectId", sch.Str("project_id"), "title", sch.Str("title"))
	for _, kc := range [][2]string{{"mode", "mode"}, {"provider_id", "providerId"}, {"model_id", "modelId"}, {"effort", "effort"}} {
		if v := sch.Str(kc[0]); v != "" {
			o.Set(kc[1], v)
		}
	}
	ss, err := s.CreateSession(o)
	if err != nil {
		return nil, err
	}
	s.Runtime.Emit("session", s.Store.UpdateSession(ss.Str("id"), O("schedule_id", id, "budget", s.Spending().Settings().Num("run_cap"))))
	s.Store.UpdateSchedule(id, O("last_run_at", isoNow()))
	// A schedule that keeps one board: the version before this run is kept, and the run is told where the board is.
	prompt := sch.Str("prompt")
	if p := s.Store.GetProject(sch.Str("project_id")); p != nil && truthy(sch.Get("keep_board")) {
		SnapshotBoard(p.Str("path"), sch.Str("title"))
		prompt += "\n\n" + BoardNote(p.Str("path"), sch.Str("title"))
	}
	if _, err := s.Send(ss.Str("id"), prompt, SendOpts{}); err != nil {
		return nil, err
	}
	return s.Session(ss.Str("id"))
}

// Research is the Research project: a folder in the projects folder, made on first use.
func (s *Service) Research() (*Obj, error) {
	dir := filepath.Join(s.ProjectsDir(), "research")
	os.MkdirAll(dir, 0o755)
	return s.AddProject(O("path", dir, "name", "Research", "init", true))
}

// --- reports -----------------------------------------------------------------------

func ReportsDir(attachments string) string { return filepath.Join(attachments, "reports") }

func ReportsNote(dir string) string {
	return "# Reports for the person\n\nWhen the person asked for something to read or use - research, a shortlist, a comparison, a plan, emails to send - put it in this session's reports folder, " + dir + " (create it if it is not there), and give its full path in your reply, so the person can open it from there. Bench lists it on the person's phone and Mac, where they read it later, often away from you. Pick the format by what they will do with it:\n\n" +
		"- To read: Markdown (.md). Lead with the answer, then the detail; links inline.\n" +
		"- To use: one self-contained HTML page (.html) when it needs sorting, filtering, checkboxes or a chart. Inline its CSS and script; it gets no network. Use the CSS variables --bg, --surface, --text, --muted, --accent, --line for colours, so it matches the person's light or dark theme, and keep state in localStorage. For choices use buttons or chips, not <select>: a select opens the phone's own unstyled picker.\n" +
		"- Emails to send: a drafts file (name.drafts.json): {\"title\": \"...\", \"drafts\": [{\"to\": \"address\", \"subject\": \"...\", \"body\": \"plain text\", \"why\": \"one line on why this person\"}]}. Bench never sends them; the person opens each in their own email app. Never send email yourself.\n\n" +
		"One file per output, with a clear name - it is what the person sees. Mixing is fine: a job hunt can be an HTML board and a drafts file."
}

func reportKind(file string) string {
	l := strings.ToLower(file)
	switch {
	case strings.HasSuffix(file, ".drafts.json"):
		return "drafts"
	case strings.HasSuffix(l, ".html") || strings.HasSuffix(l, ".htm"):
		return "page"
	case strings.HasSuffix(l, ".md") || strings.HasSuffix(l, ".markdown"):
		return "report"
	}
	return "file"
}

var (
	mdHeading = regexp.MustCompile(`(?m)^#\s+(.+)$`)
	htmlTitle = regexp.MustCompile(`(?i)<title>([^<]+)</title>`)
	lastExt   = regexp.MustCompile(`\.drafts\.json$|\.[^.]+$`)
)

func reportTitle(full, kind string) string {
	name := lastExt.ReplaceAllString(filepath.Base(full), "")
	b, err := os.ReadFile(full)
	if err != nil {
		return name
	}
	head := string(b)
	if len(head) > 20000 {
		head = head[:20000]
	}
	switch kind {
	case "report":
		if m := mdHeading.FindStringSubmatch(head); m != nil && strings.TrimSpace(m[1]) != "" {
			return strings.TrimSpace(m[1])
		}
	case "page":
		if m := htmlTitle.FindStringSubmatch(head); m != nil && strings.TrimSpace(m[1]) != "" {
			return strings.TrimSpace(m[1])
		}
	case "drafts":
		if d, ok := ParseJSON(string(b)).(*Obj); ok && truthy(d.Get("title")) {
			return str(d.Get("title"))
		}
	}
	return name
}

func draftList(full string) []any {
	b, err := os.ReadFile(full)
	if err != nil {
		return nil
	}
	if d, ok := ParseJSON(string(b)).(*Obj); ok {
		if a, ok := d.Get("drafts").([]any); ok {
			return a
		}
	}
	return nil
}

func (s *Service) Reports() []*Obj {
	root := filepath.Join(s.Store.Dir, "attachments")
	out := []*Obj{}
	ids, _ := os.ReadDir(root)
	for _, e := range ids {
		ss := s.Store.GetSession(e.Name())
		if ss == nil {
			continue
		}
		dir := ReportsDir(filepath.Join(root, e.Name()))
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, f := range files {
			full := filepath.Join(dir, f.Name())
			st, err := os.Stat(full)
			if err != nil || !st.Mode().IsRegular() {
				continue
			}
			kind := reportKind(f.Name())
			modified := isoTime(st.ModTime())
			seen := ss.Str("seen_at") != "" && ss.Str("seen_at") >= modified
			var schedID any
			if v := ss.Str("schedule_id"); v != "" {
				schedID = v
			}
			item := O("session_id", e.Name(), "path", full, "name", f.Name(), "kind", kind, "title", reportTitle(full, kind), "size", st.Size(), "modified", modified, "seen", seen, "schedule_id", schedID)
			if kind == "drafts" {
				list := draftList(full)
				unsent := 0
				for _, x := range list {
					if d, ok := x.(*Obj); !ok || !truthy(d.Get("sent_at")) {
						unsent++
					}
				}
				item.Set("drafts", len(list))
				item.Set("unsent", unsent)
			}
			out = append(out, item)
		}
	}
	out = append(out, s.boardReports()...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Str("modified") > out[j].Str("modified") })
	return out
}

func (s *Service) MarkDraft(sessionID, file string, index int, sent bool) (*Obj, error) {
	if _, err := s.Session(sessionID); err != nil {
		return nil, err
	}
	dir := ReportsDir(s.Runtime.AttachmentsDir(sessionID))
	full := filepath.Join(dir, filepath.Base(file))
	st, err := os.Stat(full)
	if err != nil || reportKind(full) != "drafts" {
		return nil, fail("No such drafts file", 404)
	}
	b, _ := os.ReadFile(full)
	d, ok := ParseJSON(string(b)).(*Obj)
	if !ok {
		return nil, fail("No such draft", 404)
	}
	list, _ := d.Get("drafts").([]any)
	if index < 0 || index >= len(list) {
		return nil, fail("No such draft", 404)
	}
	item, ok := list[index].(*Obj)
	if !ok {
		return nil, fail("No such draft", 404)
	}
	if sent {
		item.Set("sent_at", isoNow())
	} else {
		item.Delete("sent_at")
	}
	os.WriteFile(full, ToJSON(d), 0o644)
	os.Chtimes(full, st.ModTime(), st.ModTime())
	var at any
	if sent {
		at = item.Get("sent_at")
	}
	return O("ok", true, "sent_at", at), nil
}

func inList(list []string, x string) bool {
	for _, v := range list {
		if v == x {
			return true
		}
	}
	return false
}
