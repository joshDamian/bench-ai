package bench

import (
	"context"
	"fmt"
	"math"
	"strings"
	"sync"
	"time"
)

// Loop until done (src/loop.mjs): a task's failing check fed back to its session, turn by
// turn, until it passes or a cap is reached.

const MaxAttempts = 10
const MaxMinutes = 120
const loopOutputChars = 4000

func pluralN(n int, one string, many ...string) string {
	m := one + "s"
	if len(many) > 0 {
		m = many[0]
	}
	if n == 1 {
		return fmt.Sprintf("%d %s", n, one)
	}
	return fmt.Sprintf("%d %s", n, m)
}

func loopCap(v any, max int, what string) (int, bool, error) {
	if v == nil || v == "" || isUndef(v) {
		return 0, false, nil
	}
	n := num(v)
	if n != math.Trunc(n) || n < 1 || n > float64(max) {
		return 0, false, fail(fmt.Sprintf("A loop's max %s is a whole number from 1 to %d", what, max))
	}
	return int(n), true, nil
}

func loopCaps(o *Obj, baseA, baseM int) (int, int, error) {
	a, okA, err := loopCap(o.Get("max_attempts"), 100, "attempts")
	if err != nil {
		return 0, 0, err
	}
	m, okM, err := loopCap(o.Get("max_minutes"), 24*60, "minutes")
	if err != nil {
		return 0, 0, err
	}
	if !okA {
		a = baseA
	}
	if !okM {
		m = baseM
	}
	return a, m, nil
}

// NewLoopState is a task's loop, fresh or with a running one's caps retuned.
func NewLoopState(o *Obj, check string, running *Obj) (*Obj, error) {
	if running != nil {
		a, m, err := loopCaps(o, int(running.Num("max_attempts")), int(running.Num("max_minutes")))
		if err != nil {
			return nil, err
		}
		return running.Clone().Set("max_attempts", float64(a)).Set("max_minutes", float64(m)), nil
	}
	if check == "" {
		return nil, fail("A loop needs a check to end it: this task has no check command. Set one first.")
	}
	a, m, err := loopCaps(o, MaxAttempts, MaxMinutes)
	if err != nil {
		return nil, err
	}
	return O("max_attempts", float64(a), "max_minutes", float64(m), "attempts", 0.0, "state", "looping", "reason", nil, "started_at", isoNow(), "ended_at", nil, "session_id", nil), nil
}

var loopEnding = map[string]string{"looping": "Loop", "done": "Loop done", "gave-up": "Loop gave up", "stopped": "Loop stopped", "error": "Loop stopped"}

type Loop struct {
	svc      *Service
	mu       sync.Mutex
	writeUps map[string]bool
	deciding map[string]bool
}

func NewLoop(svc *Service) *Loop {
	l := &Loop{svc: svc, writeUps: map[string]bool{}, deciding: map[string]bool{}}
	svc.Runtime.On("turn-done", func(x any) {
		td := x.(TurnDone)
		if td.Session != nil {
			go func() {
				defer func() {
					if r := recover(); r != nil {
						l.itselfBroke(td.Session, fmt.Sprint(r))
					}
				}()
				l.TurnDone(context.Background(), td.Session, td.OK)
			}()
		}
	})
	return l
}

func (l *Loop) itselfBroke(s *Obj, msg string) {
	if tid := s.Str("task_id"); tid != "" {
		if t := l.svc.Store.GetTask(tid); t != nil && t.ObjAt("loop").Str("state") == "looping" {
			l.Stop(tid, "error", "the loop itself failed", msg)
		}
	}
}

// checkStarted, in a context, is called once a check has been marked running: where
// Node's loop.start() reaches its first await and hands control back to its caller.
type checkStarted struct{}

// StartAsync starts a loop the way Node's `void loop.start(id)` does: it runs up to its
// first await - the notice, the binding, the check marked running - then waits there
// until the caller, having read the task as it stands, calls the release it returns.
func (l *Loop) StartAsync(taskID string) (release func()) {
	ready := make(chan struct{})
	resume := make(chan struct{})
	var once, rel sync.Once
	pause := func() {
		first := false
		once.Do(func() { close(ready); first = true })
		if first {
			<-resume
		}
	}
	ctx := context.WithValue(context.Background(), checkStarted{}, pause)
	go func() {
		defer once.Do(func() { close(ready) })
		l.Start(ctx, taskID)
	}()
	<-ready
	return func() { rel.Do(func() { close(resume) }) }
}

func (l *Loop) Start(ctx context.Context, taskID string) {
	defer func() {
		if r := recover(); r != nil {
			l.Stop(taskID, "error", "the loop itself failed", fmt.Sprint(r))
		}
	}()
	st := l.svc.Store
	t := st.GetTask(taskID)
	if t == nil {
		return
	}
	loop := t.ObjAt("loop")
	if loop == nil || loop.Str("state") != "looping" {
		return
	}
	var s *Obj
	if sid := loop.Str("session_id"); sid != "" {
		if b := st.GetSession(sid); b != nil && !truthy(b.Get("archived_at")) {
			s = b
		}
	}
	if s == nil {
		s = l.newest(taskID)
	}
	if s == nil {
		l.Stop(taskID, "stopped", "there is no session in the task to feed", "")
		return
	}
	l.mark(s, fmt.Sprintf("Looping until `%s` passes: up to %s or %s. Only a person can start or stop a loop.", t.Str("check_command"), pluralN(int(loop.Num("max_attempts")), "attempt"), pluralN(int(loop.Num("max_minutes")), "minute")))
	if s.Str("status") == "running" || s.Str("status") == "waiting" || l.svc.Runtime.Busy(s.Str("id")) {
		return
	}
	cur := st.GetSession(s.Str("id"))
	if cur == nil {
		cur = s
	}
	l.TurnDone(ctx, cur, true)
}

func (l *Loop) Off(taskID, why string) *Obj {
	if why == "" {
		why = "the person turned it off"
	}
	// A decision in flight for this task's loop finishes first, as it would have in
	// Node, where nothing else runs between a loop's awaits.
	if t := l.svc.Store.GetTask(taskID); t != nil {
		if sid := t.ObjAt("loop").Str("session_id"); sid != "" {
			for end := time.Now().Add(5 * time.Second); time.Now().Before(end); time.Sleep(5 * time.Millisecond) {
				l.mu.Lock()
				busy := l.deciding[sid]
				l.mu.Unlock()
				if !busy {
					break
				}
			}
		}
	}
	stopped := l.Stop(taskID, "stopped", why, "")
	if sid := stopped.ObjAt("loop").Str("session_id"); sid != "" {
		l.mu.Lock()
		delete(l.writeUps, sid)
		l.mu.Unlock()
	}
	return stopped
}

func (l *Loop) SessionGone(s *Obj, why string) {
	if tid := s.Str("task_id"); tid != "" {
		t := l.svc.Store.GetTask(tid)
		loop := t.ObjAt("loop")
		feeding := false
		if loop != nil {
			if sid := loop.Str("session_id"); sid != "" {
				feeding = sid == s.Str("id")
			} else if n := l.newest(tid); n != nil {
				feeding = n.Str("id") == s.Str("id")
			}
		}
		if t != nil && loop.Str("state") == "looping" && feeding {
			if why == "" {
				why = "its session was archived"
			}
			l.Stop(tid, "stopped", why, "")
		}
	}
	l.mu.Lock()
	delete(l.writeUps, s.Str("id"))
	l.mu.Unlock()
}

func (l *Loop) Stop(taskID, state, reason, evidence string) *Obj {
	st := l.svc.Store
	t := st.GetTask(taskID)
	if t == nil {
		return nil
	}
	loop := t.ObjAt("loop")
	if loop == nil || loop.Str("state") != "looping" {
		return t
	}
	var r any
	if reason != "" {
		r = reason
	}
	ended := l.write(t, loop, O("state", state, "reason", r, "ended_at", isoNow()))
	if el := ended.ObjAt("loop"); el.Str("state") != state || el.Str("started_at") != loop.Str("started_at") {
		return ended
	}
	var s *Obj
	if sid := loop.Str("session_id"); sid != "" {
		s = st.GetSession(sid)
	}
	text := loopEnding[state] + ": the check passed."
	if reason != "" {
		text = loopEnding[state] + ": " + reason + "."
	}
	if evidence == "" {
		last := t.Str("check_status")
		if last == "running" {
			last = "was running"
		} else if last == "" {
			last = "was not run"
		}
		evidence = fmt.Sprintf("%s sent; the check last %s", pluralN(int(loop.Num("attempts")), "attempt"), last)
	}
	var sid any
	if s != nil {
		sid = s.Str("id")
	}
	st.AddLog(O("task_id", taskID, "session_id", sid, "kind", "loop", "decision", strings.TrimSuffix(text, "."), "evidence", evidence))
	if s != nil && !truthy(s.Get("archived_at")) {
		l.svc.Runtime.Record(s.Str("id"), "loop", O("text", text))
	}
	return ended
}

func (l *Loop) TurnDone(ctx context.Context, s *Obj, ok bool) {
	id := s.Str("id")
	l.mu.Lock()
	if l.deciding[id] {
		l.mu.Unlock()
		return
	}
	l.deciding[id] = true
	wu := l.writeUps[id]
	l.mu.Unlock()
	defer func() {
		l.mu.Lock()
		delete(l.deciding, id)
		l.mu.Unlock()
	}()
	if wu {
		l.writeUpDone(s)
		return
	}
	t := l.looping(s)
	if t == nil || t.ObjAt("loop") == nil {
		return
	}
	if s.Get("pause") != nil {
		return
	}
	loop := t.ObjAt("loop")
	if s.Str("status") == "error" || !ok {
		why := s.Str("error")
		if why == "" {
			if s.Str("status") == "error" {
				why = "it could not go on"
			} else {
				why = "its turn ended in an error"
			}
		}
		l.Stop(t.Str("id"), "error", "its session errored", fmt.Sprintf("%s\n\n%s were sent.", why, pluralN(int(loop.Num("attempts")), "attempt")))
		return
	}
	if s.Str("status") == "waiting" || len(l.svc.Runtime.Pending(id)) > 0 {
		return
	}
	t = l.bind(t, s)
	r := l.svc.Runtime.Check(ctx, t.Str("id"), "", false)
	cur := l.current(t, t.ObjAt("loop"))
	if cur == nil {
		return
	}
	if r == nil {
		l.Stop(t.Str("id"), "stopped", "the check could not run", "The worktree is gone, or the task no longer has a check.")
		return
	}
	if r.OK {
		l.Stop(t.Str("id"), "done", "", fmt.Sprintf("`%s` passed after %s", t.Str("check_command"), pluralN(int(cur.Num("attempts")), "attempt")))
		return
	}
	if reached := l.capReached(cur); reached != "" {
		l.giveUp(t, s, reached, cur, r)
		return
	}
	l.back(t, s, cur, r)
}

func (l *Loop) looping(s *Obj) *Obj {
	if s.Str("task_id") == "" || truthy(s.Get("archived_at")) || s.Str("purpose") == "review" {
		return nil
	}
	t := l.svc.Store.GetTask(s.Str("task_id"))
	loop := t.ObjAt("loop")
	if t == nil || loop == nil || loop.Str("state") != "looping" {
		return nil
	}
	if t.Str("state") != "active" {
		l.Stop(t.Str("id"), "stopped", "the task was "+t.Str("state"), "")
		return nil
	}
	if sid := loop.Str("session_id"); sid != "" && sid != s.Str("id") {
		return nil
	}
	return t
}

func (l *Loop) bind(t, s *Obj) *Obj {
	loop := t.ObjAt("loop")
	if loop.Str("session_id") == s.Str("id") {
		return t
	}
	return l.write(t, loop, O("session_id", s.Str("id")))
}

func (l *Loop) capReached(loop *Obj) string {
	if loop.Num("attempts") >= loop.Num("max_attempts") {
		return "the check still fails after " + pluralN(int(loop.Num("max_attempts")), "attempt")
	}
	if started, ok := parseISO(loop.Str("started_at")); ok && time.Since(started).Minutes() >= loop.Num("max_minutes") {
		return "the check still fails after " + pluralN(int(loop.Num("max_minutes")), "minute")
	}
	return ""
}

func checkHow(r *CheckResult) string {
	if r.TimedOut {
		return "timed out"
	}
	c := "?"
	if r.Code != nil {
		c = str(r.Code)
	}
	return fmt.Sprintf("failed (exit %s)", c)
}

func (l *Loop) back(t, s, loop *Obj, r *CheckResult) {
	n := int(loop.Num("attempts")) + 1
	l.write(t, loop, O("attempts", float64(n)))
	how := checkHow(r)
	tail := loopTail(r.Output)
	text := fmt.Sprintf("The check still fails: `%s` %s. The end of its output:\n\n%s\n\nKeep going. (Loop attempt %d of %d.)", t.Str("check_command"), how, tail, n, int(loop.Num("max_attempts")))
	if _, err := l.svc.Send(s.Str("id"), text, SendOpts{}); err != nil {
		l.Stop(t.Str("id"), "stopped", "it could not be sent back to work: "+err.Error(), "")
		return
	}
	l.record(t, s, fmt.Sprintf("Loop attempt %d of %d: the check %s. Sent back to work.", n, int(loop.Num("max_attempts")), how), fmt.Sprintf("`%s` %s:\n\n%s", t.Str("check_command"), how, tail))
}

func (l *Loop) giveUp(t, s *Obj, why string, loop *Obj, r *CheckResult) {
	how := checkHow(r)
	l.Stop(t.Str("id"), "gave-up", why, fmt.Sprintf("`%s` %s at attempt %d of %d:\n\n%s", t.Str("check_command"), how, int(loop.Num("attempts")), int(loop.Num("max_attempts")), loopTail(r.Output)))
	sid := s.Str("id")
	l.mu.Lock()
	l.writeUps[sid] = true
	l.mu.Unlock()
	l.svc.Runtime.SetMode(sid, "plan")
	still := l.svc.Store.GetTask(t.Str("id")).ObjAt("loop")
	if still == nil || still.Str("started_at") != loop.Str("started_at") || still.Str("state") != "gave-up" {
		l.mu.Lock()
		delete(l.writeUps, sid)
		l.mu.Unlock()
		return
	}
	if _, err := l.svc.Send(sid, l.brief(t, why), SendOpts{WriteUp: true}); err != nil {
		l.mu.Lock()
		delete(l.writeUps, sid)
		l.mu.Unlock()
		l.mark(s, "The loop ended, but the write-up could not be asked for: "+err.Error())
		return
	}
	l.mark(s, "The loop is over. One more turn, read-only, to write up why it is stuck.")
}

func (l *Loop) writeUpDone(s *Obj) {
	l.mu.Lock()
	delete(l.writeUps, s.Str("id"))
	l.mu.Unlock()
	var t *Obj
	if tid := s.Str("task_id"); tid != "" {
		t = l.svc.Store.GetTask(tid)
	}
	reply := lastReplyText(l.svc.Store, s.Str("id"))
	l.svc.Runtime.Record(s.Str("id"), "loop", O("text", "Write-up recorded in the task log."))
	if t == nil {
		return
	}
	reason := t.ObjAt("loop").Str("reason")
	if reason == "" {
		reason = "the check never passed"
	}
	if reply == "" {
		reply = "(the session wrote nothing up)"
	}
	l.svc.Store.AddLog(O("task_id", t.Str("id"), "session_id", s.Str("id"), "kind", "loop", "decision", fmt.Sprintf("Why it is stuck, in its own words (loop ended: %s)", reason), "evidence", reply))
	l.svc.Runtime.Emit("task", l.svc.Store.UpdateTask(t.Str("id"), O()))
}

func (l *Loop) record(t, s *Obj, text, evidence string) {
	l.mark(s, text)
	l.svc.Store.AddLog(O("task_id", t.Str("id"), "session_id", s.Str("id"), "kind", "loop", "decision", text, "evidence", evidence))
	l.svc.Runtime.Emit("task", l.svc.Store.UpdateTask(t.Str("id"), O()))
}

func (l *Loop) mark(s *Obj, text string) { l.svc.Runtime.Record(s.Str("id"), "loop", O("text", text)) }

func (l *Loop) current(t, loop *Obj) *Obj {
	now := l.svc.Store.GetTask(t.Str("id")).ObjAt("loop")
	if now != nil && now.Str("started_at") == loop.Str("started_at") && now.Str("state") == "looping" {
		return now
	}
	return nil
}

func (l *Loop) write(t, loop, patch *Obj) *Obj {
	now := l.current(t, loop)
	if now == nil {
		return l.svc.Store.GetTask(t.Str("id"))
	}
	updated := l.svc.Store.UpdateTask(t.Str("id"), O("loop", now.Merge(patch)))
	l.svc.Runtime.Emit("task", updated)
	return updated
}

func (l *Loop) newest(taskID string) *Obj {
	for _, s := range l.svc.Store.ListSessions(SessionQuery{TaskID: taskID}) {
		if s.Str("purpose") != "review" && !truthy(s.Get("archived_at")) {
			return s
		}
	}
	return nil
}

func loopTail(output string) string {
	text := strings.TrimRight(output, " \t\r\n")
	if text == "" {
		text = "(no output)"
	}
	if jsLen(text) > loopOutputChars {
		return "… (earlier output cut)\n" + jsTail(text, loopOutputChars)
	}
	return text
}

func (l *Loop) brief(t *Obj, why string) string {
	parts := []string{
		fmt.Sprintf("The loop on this task has ended: %s. The work is not finished, and nothing more will be sent.", why),
		fmt.Sprintf("The check is `%s`, run in this worktree.", t.Str("check_command")),
	}
	if truthy(t.Get("done")) {
		parts = append(parts, "Done means: "+t.Str("done"))
	}
	parts = append(parts,
		"You are in Plan mode, and this is the last turn: change nothing, and do not call ExitPlanMode. Write up where you are stuck, so the person can pick it up when they read it - what you tried and what each attempt showed, what the failure is now, and what you need to finish (a decision, a key, a file, something outside this task).",
		"Reply with that write-up as plain text.")
	return strings.Join(parts, "\n\n")
}

// lastReplyText: the last thing a session said on its main thread.
func lastReplyText(st *Store, id string) string {
	t, _ := lastReply(st, id)
	return t
}

func lastReply(st *Store, id string) (string, string) {
	events := st.ListEvents(id, EventQuery{Limit: 200})
	for i := len(events) - 1; i >= 0; i-- {
		e := events[i]
		data, _ := e.Get("data").(*Obj)
		if e.Str("kind") != "assistant" || truthy(data.Get("parent")) {
			continue
		}
		var texts []string
		for _, b := range arr(data.Get("content")) {
			if bo, _ := b.(*Obj); bo.Str("type") == "text" {
				texts = append(texts, bo.Str("text"))
			}
		}
		if text := strings.TrimSpace(strings.Join(texts, "\n")); text != "" {
			return text, e.Str("created_at")
		}
	}
	return "", ""
}

// --- after the usage limit (src/resume.mjs) -----------------------------------------------

const ResumeText = "Continue where you left off."
const resumeBuffer = 90 * time.Second
const stagger = 30 * time.Second
const retryAfter = 30 * time.Minute
const maxResumeTries = 3
const maxTimer = time.Hour

func dueAt(p *Obj) (time.Time, bool) {
	if truthy(p.Get("handed_to")) || p.Num("tries") > maxResumeTries {
		return time.Time{}, false
	}
	if u := p.Str("until"); u != "" {
		t, _ := parseISO(u)
		return t.Add(resumeBuffer), true
	}
	at, _ := parseISO(p.Str("at"))
	return at.Add(retryAfter * time.Duration(math.Max(1, p.Num("tries")))), true
}

type Resumer struct {
	svc      *Service
	mu       sync.Mutex
	timer    *time.Timer
	lastSent time.Time
	sent     map[string]int
}

func NewResumer(svc *Service) *Resumer {
	r := &Resumer{svc: svc, sent: map[string]int{}}
	svc.Runtime.On("limit", func(x any) { r.limitHit(x.(LimitEvent)) })
	svc.Runtime.On("turn-done", func(x any) {
		td := x.(TurnDone)
		if td.Session != nil && !td.Limited {
			r.mu.Lock()
			delete(r.sent, td.Session.Str("id"))
			r.mu.Unlock()
		}
	})
	r.schedule()
	return r
}

func (r *Resumer) Settings() *Obj {
	fb := ParseJSON(firstNonEmpty(r.svc.Store.Setting("limit_fallback"), "null"))
	return O("autoResume", r.svc.Store.Setting("auto_resume") != "0", "fallback", fb)
}

func (r *Resumer) autoResume(s *Obj) bool {
	if v := s.Get("auto_resume"); v != nil {
		return truthy(v)
	}
	return r.Settings().Bool("autoResume")
}

func (r *Resumer) limitHit(e LimitEvent) {
	s := r.svc.Store.GetSession(e.SessionID)
	if s == nil {
		return
	}
	now := time.Now()
	r.mu.Lock()
	tries := r.sent[s.Str("id")]
	r.mu.Unlock()
	var until any
	if e.Until != nil && e.Until.After(now.Add(-resumeBuffer)) {
		until = isoTime(*e.Until)
	}
	if u, ok := until.(string); ok && tries > 0 {
		t, _ := parseISO(u)
		if !t.Add(resumeBuffer).After(now) {
			until = nil
		}
	}
	pause := O("until", until, "message", e.Message, "at", isoTime(now), "tries", float64(tries), "handed_to", nil)
	r.set(s.Str("id"), pause)
	if tries > maxResumeTries {
		r.svc.Runtime.Record(s.Str("id"), "notice", O("text", fmt.Sprintf("The usage limit is still there after %d tries; Bench has stopped resuming this session. Send a message to carry on.", maxResumeTries)))
	} else if r.handOff(s, pause) == "" {
		due, ok := dueAt(pause)
		text := fmt.Sprintf("Paused: %s.", e.Message)
		if r.autoResume(s) && ok {
			text += fmt.Sprintf(` Bench will send "%s" at %s.`, ResumeText, strings.Replace(due.UTC().Format("Mon, 02 Jan 2006 15:04:05 GMT"), ":00 GMT", " UTC", 1))
		} else {
			text += " Auto-resume is off for this session."
		}
		r.svc.Runtime.Record(s.Str("id"), "notice", O("text", text))
	}
	r.schedule()
}

func (r *Resumer) set(id string, pause any) {
	r.svc.Runtime.Emit("session", r.svc.Store.UpdateSession(id, O("pause", pause)))
}

func (r *Resumer) handOff(s, pause *Obj) string {
	fb := r.Settings().ObjAt("fallback")
	if fb == nil || s.Str("project_id") == "" || s.Str("purpose") == "review" || fb.Str("providerId") == s.Str("provider_id") {
		return ""
	}
	if b, ok := s.Get("auto_resume").(bool); ok && !b {
		return ""
	}
	provider := r.svc.Store.GetProvider(fb.Str("providerId"))
	if provider == nil {
		r.svc.Runtime.Record(s.Str("id"), "notice", O("text", "Could not hand this work to the fallback provider: it is gone. Waiting for the limit instead."))
		return ""
	}
	if ready, reason := ProviderReady(provider); !ready {
		r.svc.Runtime.Record(s.Str("id"), "notice", O("text", fmt.Sprintf("Could not hand this work to the fallback provider: %s. Waiting for the limit instead.", str(reason))))
		return ""
	}
	title := jsSlice(s.Str("title")+" (continued)", 120)
	next, err := r.svc.CreateSession(O("projectId", s.Str("project_id"), "taskId", s.Get("task_id"), "title", title, "providerId", provider.Str("id"), "modelId", fb.Get("modelId"),
		"mode", s.Str("permission_mode"), "startedBy", s.Get("started_by_session"), "prompt", r.Handoff(s, pause)))
	if err != nil {
		r.svc.Runtime.Record(s.Str("id"), "notice", O("text", fmt.Sprintf("Could not hand this work to %s: %s. Waiting for the limit instead.", provider.Str("name"), err.Error())))
		return ""
	}
	r.set(s.Str("id"), pause.Clone().Set("handed_to", next.Str("id")))
	r.svc.Runtime.Record(s.Str("id"), "notice", O("text", fmt.Sprintf(`Paused: %s. The work was handed to a new session on %s: "%s".`, pause.Str("message"), provider.Str("name"), next.Str("title"))))
	return next.Str("id")
}

func (r *Resumer) Handoff(s, pause *Obj) string {
	var task *Obj
	if tid := s.Str("task_id"); tid != "" {
		task = r.svc.Store.GetTask(tid)
	}
	events := r.svc.Store.ListEvents(s.Str("id"), EventQuery{Limit: 400})
	first := ""
	for _, e := range events {
		if e.Str("kind") == "user" {
			first = e.ObjAt("data").Str("text")
			break
		}
	}
	var said []string
	for _, e := range events {
		d := e.ObjAt("data")
		if e.Str("kind") != "assistant" || truthy(d.Get("parent")) {
			continue
		}
		for _, b := range arr(d.Get("content")) {
			if bo, _ := b.(*Obj); bo.Str("type") == "text" {
				t := str(bo.Get("text"))
				if !strings.HasPrefix(t, "You've hit your") && !strings.HasPrefix(t, "You've reached your") {
					said = append(said, t)
				}
			}
		}
	}
	var recent []string
	room := 6000
	for i := len(said) - 1; i >= 0 && room > 0; i-- {
		t := said[i]
		if jsLen(t) > 2000 {
			t = jsSlice(t, 2000) + "…"
		}
		recent = append([]string{t}, recent...)
		room -= jsLen(t)
	}
	parts := []string{fmt.Sprintf(`You are taking over work from another session ("%s"), which stopped because the Claude login reached its usage limit (%s). It cannot continue until the limit resets, so carry the work on from where it got to.`, s.Str("title"), pause.Str("message"))}
	if task != nil {
		tl := "The task: " + task.Str("title")
		if truthy(task.Get("done")) {
			tl += "\nDone means: " + task.Str("done")
		}
		parts = append(parts, tl)
	}
	if first != "" {
		parts = append(parts, "What the person first asked it:\n<<<\n"+jsSlice(first, 4000)+"\n>>>")
	}
	if len(recent) > 0 {
		parts = append(parts, "What it last said, oldest first:\n<<<\n"+strings.Join(recent, "\n\n---\n\n")+"\n>>>")
	}
	parts = append(parts, "Its changes, if it made any, are in the working tree: read `git status` and `git diff` before you start, then continue the work rather than redoing it.")
	return strings.Join(parts, "\n\n")
}

type queued struct {
	s   *Obj
	due time.Time
}

func (r *Resumer) queue() []queued {
	var out []queued
	for _, s := range r.svc.Store.PausedSessions() {
		p := s.ObjAt("pause")
		if truthy(s.Get("archived_at")) || p == nil || !r.autoResume(s) {
			continue
		}
		if due, ok := dueAt(p); ok {
			out = append(out, queued{s, due})
		}
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].due.Before(out[j-1].due); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

func (r *Resumer) next() (time.Time, bool) {
	q := r.queue()
	if len(q) == 0 {
		return time.Time{}, false
	}
	r.mu.Lock()
	ls := r.lastSent
	r.mu.Unlock()
	at := q[0].due
	if ls.Add(stagger).After(at) {
		at = ls.Add(stagger)
	}
	return at, true
}

func (r *Resumer) schedule() {
	r.mu.Lock()
	if r.timer != nil {
		r.timer.Stop()
		r.timer = nil
	}
	r.mu.Unlock()
	at, ok := r.next()
	if !ok {
		return
	}
	d := time.Until(at)
	if d < 0 {
		d = 0
	}
	if d > maxTimer {
		d = maxTimer
	}
	r.mu.Lock()
	r.timer = time.AfterFunc(d, r.tick)
	r.mu.Unlock()
}

func (r *Resumer) tick() {
	if at, ok := r.next(); ok && !at.After(time.Now()) {
		if q := r.queue(); len(q) > 0 {
			r.Resume(q[0].s.Str("id"))
		}
	}
	r.schedule()
}

func (r *Resumer) Resume(id string) bool {
	s := r.svc.Store.GetSession(id)
	if s == nil || s.Get("pause") == nil {
		return false
	}
	if r.svc.Runtime.Busy(id) {
		r.set(id, nil)
		return false
	}
	r.mu.Lock()
	r.lastSent = time.Now()
	r.sent[id]++
	r.mu.Unlock()
	r.svc.Runtime.Record(id, "notice", O("text", "The usage limit has reset; Bench is resuming this session."))
	if _, err := r.svc.Send(id, ResumeText, SendOpts{}); err != nil {
		r.set(id, nil)
		r.svc.Runtime.Record(id, "notice", O("text", "Could not resume: "+err.Error()))
		return false
	}
	return true
}

func (r *Resumer) Changed() { r.schedule() }

func (r *Resumer) Shutdown() {
	r.mu.Lock()
	if r.timer != nil {
		r.timer.Stop()
		r.timer = nil
	}
	r.mu.Unlock()
}
