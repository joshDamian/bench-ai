package bench

import (
	"context"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// Everything the server can do, in one place (src/service.mjs). Events: "task", "git".

const defaultProvider = "anthropic-claude-code"

var Efforts = []string{"low", "medium", "high", "xhigh", "max"}

// effortOf: (value, present, error). Absent stays absent; null or "" is null.
func effortOf(v any, present bool) (any, bool, error) {
	if !present {
		return nil, false, nil
	}
	if _, u := v.(undefined); u {
		return nil, false, nil
	}
	if v == nil || v == "" {
		return nil, true, nil
	}
	s := str(v)
	for _, e := range Efforts {
		if e == s {
			return s, true, nil
		}
	}
	return nil, true, fail(fmt.Sprintf("Unknown effort %s; use one of %s", s, strings.Join(Efforts, ", ")))
}

var slugNon = regexp.MustCompile(`[^a-z0-9]+`)
var slugTrim = regexp.MustCompile(`^-+|-+$`)

func taskSlug(text string) string {
	s := slugTrim.ReplaceAllString(slugNon.ReplaceAllString(strings.ToLower(text), "-"), "")
	if len(s) > 40 {
		s = s[:40]
	}
	s = strings.TrimRight(s, "-")
	if s == "" {
		return "task"
	}
	return s
}

func expandHome(p string) string {
	home, _ := os.UserHomeDir()
	if p == "~" {
		return home
	}
	if strings.HasPrefix(p, "~/") {
		return home + p[1:]
	}
	return p
}

var folderSep = regexp.MustCompile(`[\/\\]+`)
var folderBad = regexp.MustCompile(`[^\w.\- ]+`)
var folderLead = regexp.MustCompile(`^[.\-\s]+`)

func safeFolderName(n string) (string, error) {
	s := folderBad.ReplaceAllString(folderSep.ReplaceAllString(strings.TrimSpace(n), "-"), "-")
	s = folderLead.ReplaceAllString(s, "")
	if len(s) > 100 {
		s = s[:100]
	}
	if s == "" {
		return "", fail("That name cannot be a folder name")
	}
	return s, nil
}

func absPath(p string) string {
	a, _ := filepath.Abs(p)
	return a
}

type Host struct {
	Pair    func() (*Obj, error)
	Metrics func() *Obj
	Status  func() *Obj
}

type Service struct {
	Emitter
	Root     string
	Store    *Store
	Runtime  *Runtime
	Notifier func(payload *Obj) int
	Host     *Host
	Loop     *Loop
	Resumer  *Resumer
}

func NewService(root string) (*Service, error) {
	store, err := NewStore(root)
	if err != nil {
		return nil, err
	}
	s := &Service{Root: root, Store: store}
	s.Runtime = NewRuntime(store, func(sessionID string) *MCPServer {
		if sess := store.GetSession(sessionID); sess != nil && sess.Str("project_id") != "" {
			return BenchServer(s, sessionID)
		}
		return AskServer(s, sessionID)
	})
	s.Loop = NewLoop(s)
	s.Resumer = NewResumer(s)
	s.Runtime.On("turn-done", func(x any) {
		td := x.(TurnDone)
		if td.Session != nil && td.Session.Str("project_id") != "" {
			s.Emit("git", td.Session.Str("project_id"))
		}
	})
	s.Runtime.On("task", func(t any) { s.Emit("task", t) })
	seen := map[string]bool{}
	for _, a := range store.ListArtifacts(ArtifactQuery{}) {
		id := a.Str("session_id")
		if seen[id] {
			continue
		}
		seen[id] = true
		if ss := store.GetSession(id); ss == nil || truthy(ss.Get("archived_at")) {
			s.cleanAfter(id)
		}
	}
	return s, nil
}

// --- projects ---------------------------------------------------------------------------

func (s *Service) Project(id string) (*Obj, error) {
	p := s.Store.GetProject(id)
	if p == nil {
		return nil, fail("No such project", 404)
	}
	return p, nil
}

func (s *Service) AddProject(o *Obj) (*Obj, error) {
	dir := absPath(expandHome(str(o.Get("path"))))
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return nil, fail("No such directory: " + dir)
	}
	if !IsRepo(dir) {
		if !truthy(o.Get("init")) {
			return nil, &HTTPError{Status: 400, Msg: dir + " is not a git repository", Code: "NOT_A_REPO"}
		}
		Git(dir, []string{"init", "-q"})
	}
	top, err := TopLevel(dir)
	if err != nil {
		return nil, fail(GitError(err))
	}
	if RevParse(top, "") == "" {
		Git(top, []string{"add", "-A"})
		Git(top, []string{"-c", "user.email=bench@localhost", "-c", "user.name=Bench", "commit", "-q", "--allow-empty", "-m", "Initial commit"})
	}
	if ex := s.Store.ProjectByPath(top); ex != nil {
		return ex, nil
	}
	name := strings.TrimSpace(o.Str("name"))
	if name == "" {
		name = filepath.Base(top)
	}
	return s.Store.AddProject(name, top), nil
}

func (s *Service) ProjectsDir() string {
	set := s.Store.Setting("projects_dir")
	if set == "" {
		set = Env("PROJECTS_DIR")
	}
	if set == "" {
		home, _ := os.UserHomeDir()
		set = filepath.Join(home, "bench-projects")
	}
	return absPath(expandHome(set))
}

func (s *Service) CloneProject(o *Obj) (*Obj, error) {
	spec, err := ParseRepoSpec(str(o.Get("repo")))
	if err != nil {
		return nil, fail(err.Error())
	}
	base := s.ProjectsDir()
	if d := o.Str("dir"); d != "" {
		base = absPath(expandHome(d))
	}
	nm := o.Str("name")
	if nm == "" {
		nm = spec.Name
	}
	folder, err := safeFolderName(nm)
	if err != nil {
		return nil, err
	}
	target := filepath.Join(base, folder)
	if ents, err := os.ReadDir(target); err == nil && len(ents) > 0 {
		return nil, &HTTPError{Status: 409, Msg: target + " already exists and is not empty", Code: "EXISTS", Extra: O("path", target)}
	}
	if err := Clone(spec, target); err != nil {
		return nil, fail(err.Error())
	}
	return s.AddProject(O("path", target, "name", o.Get("name")))
}

func (s *Service) CreateProject(o *Obj) (*Obj, error) {
	name := strings.TrimSpace(o.Str("name"))
	if name == "" {
		return nil, fail("Name the project")
	}
	base := s.ProjectsDir()
	if d := o.Str("dir"); d != "" {
		base = absPath(expandHome(d))
	}
	folder, err := safeFolderName(name)
	if err != nil {
		return nil, err
	}
	target := filepath.Join(base, folder)
	if ents, err := os.ReadDir(target); err == nil && len(ents) > 0 {
		return nil, &HTTPError{Status: 409, Msg: target + " already exists and is not empty", Code: "EXISTS", Extra: O("path", target)}
	}
	os.MkdirAll(target, 0o755)
	return s.AddProject(O("path", target, "name", name, "init", true))
}

func (s *Service) GithubRepos() *Obj {
	if !GhReady() {
		return O("ready", false, "repos", []any{})
	}
	return O("ready", true, "repos", GithubRepos())
}

func (s *Service) RemoveProject(id string) {
	for _, ss := range s.Store.ListSessions(SessionQuery{ProjectID: id, Archived: true}) {
		s.Runtime.Close(ss.Str("id"))
		s.Runtime.DropScratch(ss.Str("id"))
	}
	s.Store.DeleteProject(id)
}

func (s *Service) Git(id string) (*Obj, error) {
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	return GitState(p.Str("path"))
}

func (s *Service) remote(id string, op func(dir string) error) (*Obj, error) {
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	if err := op(p.Str("path")); err != nil {
		return nil, fail(err.Error())
	}
	s.Emit("git", id)
	return s.Git(id)
}

func (s *Service) Fetch(id string) (*Obj, error) { return s.remote(id, FetchAll) }
func (s *Service) Pull(id string) (*Obj, error)  { return s.remote(id, Pull) }
func (s *Service) Push(id string) (*Obj, error) {
	return s.remote(id, func(dir string) error {
		b := CurrentBranch(dir)
		if b == "" {
			return fmt.Errorf("The checkout is not on a branch")
		}
		return Push(dir, b)
	})
}

func (s *Service) Checkout(id string, o *Obj) (*Obj, error) {
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	for _, ss := range s.Store.ListSessions(SessionQuery{ProjectID: id}) {
		if !truthy(ss.Get("task_id")) && ss.Str("status") != "idle" && ss.Str("status") != "error" {
			return nil, fail(fmt.Sprintf(`"%s" is working in this checkout; stop it before switching branches`, ss.Str("title")), 409)
		}
	}
	r, err := GitCheckout(p.Str("path"), o.Str("branch"), o.Bool("create"), o.Str("from"), o.Bool("stash"))
	if err != nil {
		if ge, ok := err.(*GitErr); ok && ge.Code == "DIRTY" {
			return nil, &HTTPError{Status: 409, Msg: ge.Message, Code: "DIRTY", Dirty: ge.Dirty}
		}
		return nil, fail(err.Error())
	}
	s.Emit("git", id)
	return r, nil
}

func (s *Service) Commit(id string, message string) (*Obj, error) {
	if strings.TrimSpace(message) == "" {
		return nil, fail("A commit needs a message")
	}
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	sha, err := CommitAll(p.Str("path"), strings.TrimSpace(message))
	if err != nil {
		return nil, fail(GitError(err))
	}
	if sha == "" {
		return nil, fail("Nothing to commit")
	}
	s.Emit("git", id)
	return O("commit", sha), nil
}

func (s *Service) CheckoutDiff(id string) (*Obj, error) {
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	dir := p.Str("path")
	head := RevParse(dir, "")
	if head == "" {
		return O("diff", ""), nil
	}
	d, err := WorkingDiff(dir, head)
	if err != nil {
		return nil, fail(GitError(err))
	}
	return O("diff", d), nil
}

// --- tasks ---------------------------------------------------------------------------

func (s *Service) Task(id string) (*Obj, error) {
	t := s.Store.GetTask(id)
	if t == nil {
		return nil, fail("No such task", 404)
	}
	return t, nil
}

func (s *Service) Tasks(projectID string) []*Obj { return nonNil(s.Store.ListTasks(projectID)) }

func (s *Service) CreateTask(projectID string, o *Obj) (*Obj, error) {
	p, err := s.Project(projectID)
	if err != nil {
		return nil, err
	}
	prompt := strings.TrimSpace(o.Str("prompt"))
	title := strings.TrimSpace(o.Str("title"))
	if title == "" && prompt != "" {
		title = jsSlice(strings.Split(prompt, "\n")[0], 80)
	}
	if title == "" {
		return nil, fail("A task needs a title or a prompt")
	}
	repo := p.Str("path")
	base := o.Str("baseBranch")
	if base == "" {
		base = CurrentBranch(repo)
	}
	if base == "" {
		return nil, fail("The checkout is detached; pick a base branch")
	}
	if err := EnsureLocalBranch(repo, base); err != nil {
		return nil, fail(err.Error())
	}
	baseCommit := RevParse(repo, "refs/heads/"+base)
	id := NewID()
	branch := fmt.Sprintf("bench/%s-%s", taskSlug(title), id[:6])
	worktree := filepath.Join(WorktreeRoot(repo), id)
	if err := CreateWorktree(repo, worktree, branch, baseCommit); err != nil {
		return nil, fail(GitError(err))
	}
	var done any
	if d := strings.TrimSpace(o.Str("done")); d != "" {
		done = d
	}
	var check any
	if o.Has("check") {
		if c := strings.TrimSpace(str(o.Get("check"))); c != "" && o.Get("check") != nil {
			check = c
		}
	} else if c := strings.TrimSpace(p.Str("check_command")); c != "" {
		check = c
	}
	var loop any
	if truthy(o.Get("loop")) {
		cs, _ := check.(string)
		l, err := NewLoopState(o.ObjAt("loop"), cs, nil)
		if err != nil {
			return nil, err
		}
		loop = l
	}
	var desc any
	if prompt != "" {
		desc = prompt
	}
	var createdBy any
	if cb := o.Str("createdBy"); cb != "" {
		createdBy = cb
	}
	task := s.Store.AddTask(O("id", id, "project_id", p.Str("id"), "title", title, "description", desc, "base_branch", base, "base_commit", baseCommit, "branch", branch, "worktree", worktree, "done", done, "check_command", check, "created_by_session", createdBy, "loop", loop))
	s.Emit("task", task)
	s.Emit("git", p.Str("id"))
	so := O("projectId", p.Str("id"), "taskId", id, "title", title, "startedBy", createdBy)
	for _, k := range []string{"providerId", "modelId", "mode", "effort"} {
		if o.Has(k) {
			so.Set(k, o.Get(k))
		}
	}
	sess, err := s.CreateSession(so)
	if err != nil {
		return nil, err
	}
	if prompt != "" {
		if _, err := s.Send(sess.Str("id"), prompt, SendOpts{}); err != nil {
			return nil, err
		}
	}
	release := func() {}
	if loop != nil {
		release = s.Loop.StartAsync(id)
	}
	defer release()
	t, _ := s.Task(id)
	return O("task", t, "session", s.Store.GetSession(sess.Str("id"))), nil
}

func (s *Service) TaskGit(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	p, err := s.Project(t.Str("project_id"))
	if err != nil {
		return nil, err
	}
	repo := p.Str("path")
	gone := !exists(t.Str("worktree"))
	tip := RevParse(repo, "refs/heads/"+t.Str("branch"))
	base := RevParse(repo, "refs/heads/"+t.Str("base_branch"))
	st := GitStatus{Dirty: []string{}, Conflicted: []string{}}
	if !gone {
		if x, err := Status(t.Str("worktree")); err == nil {
			st = x
		} else {
			return nil, fail(GitError(err))
		}
	}
	ahead, behind := 0, 0
	if tip != "" && base != "" {
		ahead, behind = Compare(repo, t.Str("branch"), t.Str("base_branch"))
	}
	from := ""
	if tip != "" && base != "" {
		from = MergeBase(repo, tip, base)
	}
	stat := O("files", 0.0, "added", 0.0, "removed", 0.0)
	if !gone && from != "" {
		if x, err := WorkingStat(t.Str("worktree"), from); err == nil {
			stat = x
		}
	}
	merged := t.Str("state") == "merged" || (tip != "" && base != "" && tip != t.Str("base_commit") && ahead == 0 && len(st.Dirty) == 0)
	can := O("ok", true, "reason", nil, "conflicts", []any{})
	switch {
	case t.Str("state") != "active":
		can = O("ok", false, "reason", "The task is "+t.Str("state"), "conflicts", []any{})
	case base == "":
		can = O("ok", false, "reason", t.Str("base_branch")+" no longer exists", "conflicts", []any{})
	case len(st.Conflicted) > 0:
		can = O("ok", false, "reason", "The worktree has unresolved conflicts", "conflicts", strs(st.Conflicted))
	case ahead == 0 && len(st.Dirty) == 0:
		can = O("ok", false, "reason", "Nothing to merge yet", "conflicts", []any{})
	case tip != "" && ahead > 0:
		if m := MergeTree(repo, base, tip); !m.Clean {
			can = O("ok", false, "reason", "Conflicts with "+t.Str("base_branch"), "conflicts", strs(m.Conflicts))
		}
	}
	if merged {
		if from, to, ok := s.landedRange(t, repo, tip); ok {
			stat = CommittedStat(repo, from, to)
		}
	}
	out := O("ahead", float64(ahead), "behind", float64(behind), "dirty", strs(st.Dirty), "conflicted", strs(st.Conflicted))
	for _, k := range stat.Keys() {
		out.Set(k, stat.Get(k))
	}
	return out.Set("merged", merged).Set("canMerge", can), nil
}

func (s *Service) landedRange(t *Obj, repo, tip string) (string, string, bool) {
	if mc := t.Str("merge_commit"); mc != "" && RevParse(repo, mc+"^1") != "" {
		return mc + "^1", mc, true
	}
	if tip != "" && t.Str("base_commit") != "" && tip != t.Str("base_commit") {
		return t.Str("base_commit"), tip, true
	}
	return "", "", false
}

func (s *Service) TaskDiff(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	p, err := s.Project(t.Str("project_id"))
	if err != nil {
		return nil, err
	}
	repo := p.Str("path")
	if t.Str("state") == "merged" {
		if from, to, ok := s.landedRange(t, repo, RevParse(repo, "refs/heads/"+t.Str("branch"))); ok {
			return O("diff", CommittedDiff(repo, from, to)), nil
		}
		return O("diff", ""), nil
	}
	if !exists(t.Str("worktree")) {
		return O("diff", ""), nil
	}
	base := RevParse(repo, "refs/heads/"+t.Str("base_branch"))
	from := ""
	if base != "" {
		from = MergeBase(t.Str("worktree"), "HEAD", base)
	}
	if from == "" {
		from = t.Str("base_commit")
	}
	d, err := WorkingDiff(t.Str("worktree"), from)
	if err != nil {
		return nil, fail(GitError(err))
	}
	return O("diff", d), nil
}

func (s *Service) liveTaskWork(id, except string) *Obj {
	for _, ss := range s.Store.ListSessions(SessionQuery{TaskID: id}) {
		if ss.Str("id") != except && (ss.Str("status") == "running" || ss.Str("status") == "waiting") {
			return ss
		}
	}
	return nil
}

func (s *Service) CommitTask(id, message string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	msg := strings.TrimSpace(message)
	if msg == "" {
		msg = t.Str("title")
	}
	sha, err := CommitAll(t.Str("worktree"), msg)
	if err != nil {
		return nil, fail(GitError(err))
	}
	if sha == "" {
		return nil, fail("Nothing to commit")
	}
	s.Emit("git", t.Str("project_id"))
	return O("commit", sha), nil
}

func (s *Service) UpdateFromBase(id, except string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if s.liveTaskWork(id, except) != nil {
		return nil, fail("A session is working in this task; wait for it or stop it", 409)
	}
	st, err := Status(t.Str("worktree"))
	if err != nil {
		return nil, fail(GitError(err))
	}
	if len(st.Dirty) > 0 {
		return nil, fail("Commit the task's changes first, so the merge cannot mix with them", 409)
	}
	r, err := MergeInto(t.Str("worktree"), t.Str("base_branch"))
	if err != nil {
		return nil, fail(err.Error())
	}
	s.Emit("git", t.Str("project_id"))
	return r, nil
}

func (s *Service) AbortTaskMerge(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if err := AbortMerge(t.Str("worktree")); err != nil {
		return nil, fail(GitError(err))
	}
	s.Emit("git", t.Str("project_id"))
	return O("ok", true), nil
}

func (s *Service) MergeTask(id string, o *Obj) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if t.Str("state") != "active" {
		return nil, fail("The task is already "+t.Str("state"), 409)
	}
	if s.liveTaskWork(id, "") != nil {
		return nil, fail("A session is working in this task; wait for it or stop it", 409)
	}
	p, err := s.Project(t.Str("project_id"))
	if err != nil {
		return nil, err
	}
	repo := p.Str("path")
	if exists(t.Str("worktree")) {
		if st, err := Status(t.Str("worktree")); err == nil && len(st.Conflicted) > 0 {
			return nil, fail("Resolve the conflicts in the task first", 409)
		}
		CommitAll(t.Str("worktree"), t.Str("title"))
	}
	msg := strings.TrimSpace(o.Str("message"))
	strategy := o.Str("strategy")
	if msg == "" {
		if strategy == "squash" {
			msg = t.Str("title")
		} else {
			msg = "Merge task: " + t.Str("title")
		}
	}
	if strategy == "" {
		strategy = "merge"
	}
	landed, err := Land(repo, t.Str("branch"), t.Str("base_branch"), msg, strategy)
	if err != nil {
		ge, _ := err.(*GitErr)
		status := 400
		he := &HTTPError{Msg: err.Error()}
		if ge != nil {
			he.Code = ge.Code
			he.Conflicts = ge.Conflicts
			if ge.Code == "CONFLICT" {
				status = 409
			}
		}
		he.Status = status
		return nil, he
	}
	s.Loop.Stop(id, "stopped", "the task was merged", "")
	updated := s.Store.UpdateTask(id, O("state", "merged", "merge_commit", landed.Get("commit"), "merged_at", isoNow()))
	if o.Bool("cleanup") {
		s.cleanup(updated, true)
	}
	s.Emit("task", updated)
	s.Emit("git", t.Str("project_id"))
	now, _ := s.Task(id)
	return O("task", now).Merge(landed), nil
}

func (s *Service) cleanup(t *Obj, deleteBranch bool) {
	p := s.Store.GetProject(t.Str("project_id"))
	if p == nil {
		return
	}
	repo := p.Str("path")
	for _, ss := range s.Store.ListSessions(SessionQuery{TaskID: t.Str("id")}) {
		s.Runtime.Close(ss.Str("id"))
		s.Runtime.DropScratch(ss.Str("id"))
		archived := s.Store.UpdateSession(ss.Str("id"), O("archived_at", isoNow()))
		s.cleanAfter(ss.Str("id"))
		s.Runtime.Emit("session", archived)
	}
	RemoveWorktree(repo, t.Str("worktree"))
	if deleteBranch {
		DeleteBranch(repo, t.Str("branch"))
	}
}

func (s *Service) CloseTask(id string, o *Obj) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if t.Str("state") != "active" {
		return nil, fail("The task is already "+t.Str("state"), 409)
	}
	s.Loop.Stop(id, "stopped", "the task was closed", "")
	s.cleanup(t, o.Bool("deleteBranch"))
	updated := s.Store.UpdateTask(id, O("state", "closed"))
	s.Emit("task", updated)
	s.Emit("git", t.Str("project_id"))
	return updated, nil
}

func (s *Service) ReopenTask(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if t.Str("state") != "closed" {
		return nil, fail("Only a closed task can be reopened", 409)
	}
	p, err := s.Project(t.Str("project_id"))
	if err != nil {
		return nil, err
	}
	repo := p.Str("path")
	if RevParse(repo, "refs/heads/"+t.Str("branch")) == "" {
		return nil, fail("The branch "+t.Str("branch")+" was deleted", 409)
	}
	if !exists(t.Str("worktree")) {
		if _, err := Git(repo, []string{"worktree", "add", "-q", t.Str("worktree"), t.Str("branch")}); err != nil {
			return nil, fail(GitError(err))
		}
	}
	for _, ss := range s.Store.ListSessions(SessionQuery{TaskID: id, Archived: true}) {
		s.Runtime.Emit("session", s.Store.UpdateSession(ss.Str("id"), O("archived_at", nil)))
	}
	updated := s.Store.UpdateTask(id, O("state", "active"))
	s.Emit("task", updated)
	return updated, nil
}

func (s *Service) PushTask(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if exists(t.Str("worktree")) {
		CommitAll(t.Str("worktree"), t.Str("title"))
	}
	p, err := s.Project(t.Str("project_id"))
	if err != nil {
		return nil, err
	}
	if err := Push(p.Str("path"), t.Str("branch")); err != nil {
		return nil, fail(err.Error())
	}
	s.Emit("git", t.Str("project_id"))
	return O("ok", true), nil
}

func (s *Service) PullRequest(id string, o *Obj) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if !GhReady() {
		return nil, fail("The GitHub CLI is not logged in on this machine; run `gh auth login`")
	}
	if _, err := s.PushTask(id); err != nil {
		return nil, err
	}
	p, _ := s.Project(t.Str("project_id"))
	title := strings.TrimSpace(o.Str("title"))
	if title == "" {
		title = t.Str("title")
	}
	body := o.Str("body")
	if !o.Has("body") || o.Get("body") == nil {
		body = t.Str("description")
	}
	url, err := CreatePullRequest(p.Str("path"), t.Str("branch"), t.Str("base_branch"), title, body)
	if err != nil {
		return nil, fail(err.Error())
	}
	return O("url", url), nil
}

func (s *Service) EditTask(id string, patch *Obj) (*Obj, error) {
	t0, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	change := O()
	if patch.Has("routing") {
		if patch.Get("routing") == nil {
			change.Set("routing", nil)
		} else {
			rp, err := RoutingPatch(patch.Get("routing"), true)
			if err != nil {
				return nil, err
			}
			merged := O()
			if r := t0.ObjAt("routing"); r != nil {
				merged = r.Clone()
			}
			merged = merged.Merge(rp)
			any := false
			for _, k := range merged.Keys() {
				if merged.Get(k) != nil {
					any = true
				}
			}
			if any {
				change.Set("routing", merged)
			} else {
				change.Set("routing", nil)
			}
		}
	}
	if patch.Has("title") {
		if strings.TrimSpace(patch.Str("title")) == "" {
			return nil, fail("A title cannot be empty")
		}
		change.Set("title", strings.TrimSpace(patch.Str("title")))
	}
	bar := patch.Has("done") || patch.Has("check")
	if patch.Has("done") {
		change.Set("done", nilIfEmpty(strings.TrimSpace(patch.Str("done"))))
	}
	if patch.Has("check") {
		change.Set("check_command", nilIfEmpty(strings.TrimSpace(patch.Str("check"))))
	}
	if bar {
		change.Set("check_status", nil).Set("check_tree", nil).Set("check_output", nil).Set("check_at", nil)
	}
	if patch.Has("loop") && patch.Get("loop") != nil {
		check := t0.Str("check_command")
		if bar {
			check, _ = change.Get("check_command").(string)
		}
		var running *Obj
		if l := t0.ObjAt("loop"); l != nil && l.Str("state") == "looping" {
			running = l
		}
		l, err := NewLoopState(patch.ObjAt("loop"), check, running)
		if err != nil {
			return nil, err
		}
		change.Set("loop", l)
	}
	if patch.Has("loop") && patch.Get("loop") == nil {
		s.Loop.Off(id, "")
		change.Set("loop", nil)
	}
	s.Store.UpdateTask(id, change)
	if bar {
		for _, ss := range s.Store.ListSessions(SessionQuery{TaskID: id}) {
			if !s.Runtime.Busy(ss.Str("id")) {
				s.Runtime.Close(ss.Str("id"))
			}
		}
	}
	release := func() {}
	if truthy(change.Get("loop")) {
		release = s.Loop.StartAsync(id)
	}
	defer release()
	now, _ := s.Task(id)
	s.Emit("task", now)
	return now, nil
}

func (s *Service) CheckTask(id string) (*Obj, error) {
	t, err := s.Task(id)
	if err != nil {
		return nil, err
	}
	if !truthy(t.Get("check_command")) {
		return nil, fail("This task has no check; add one first")
	}
	if t.Str("state") != "active" {
		return nil, fail("The task is "+t.Str("state"), 409)
	}
	s.Runtime.Check(context.Background(), id, "", true)
	return s.Task(id)
}

func (s *Service) TaskLog(id string) ([]*Obj, error) {
	if _, err := s.Task(id); err != nil {
		return nil, err
	}
	return s.Store.ListLog(id, 500), nil
}

func (s *Service) LogDecision(sessionID string, decision, evidence string) (*Obj, error) {
	ss, err := s.Session(sessionID)
	if err != nil {
		return nil, err
	}
	if ss.Str("task_id") == "" {
		return nil, fail("This session is not in a task, so there is no decision log to write to")
	}
	decision, evidence = strings.TrimSpace(decision), strings.TrimSpace(evidence)
	if decision == "" || evidence == "" {
		return nil, fail("A log entry needs both a decision and its evidence")
	}
	entry := s.Store.AddLog(O("task_id", ss.Str("task_id"), "session_id", ss.Str("id"), "decision", decision, "evidence", evidence))
	s.Runtime.Record(ss.Str("id"), "log", O("decision", decision, "evidence", evidence, "entry", entry.Get("id")))
	s.Emit("task", s.Store.UpdateTask(ss.Str("task_id"), O()))
	return entry, nil
}

func (s *Service) RecordReview(taskID, reviewer, by, findings, askedBy string, design bool) *Obj {
	kind := "Review"
	if design {
		kind = "Design review"
	}
	ev := strings.TrimSpace(findings)
	if ev == "" {
		ev = "(no reply)"
	}
	entry := s.Store.AddLog(O("task_id", taskID, "session_id", reviewer, "kind", "review", "decision", kind+" by "+by, "evidence", ev))
	if askedBy != "" {
		s.Runtime.Record(askedBy, "log", O("decision", entry.Get("decision"), "evidence", entry.Get("evidence"), "entry", entry.Get("id"), "kind", "review"))
	}
	s.Emit("task", s.Store.UpdateTask(taskID, O()))
	return entry
}

// --- sessions ---------------------------------------------------------------------------

func (s *Service) Session(id string) (*Obj, error) {
	ss := s.Store.GetSession(id)
	if ss == nil {
		return nil, fail("No such session", 404)
	}
	return ss, nil
}

func (s *Service) Sessions(q SessionQuery) []*Obj { return s.Store.ListSessions(q) }

func (s *Service) Defaults() *Obj {
	provider := s.Store.Setting("default_provider")
	if provider == "" {
		provider = defaultProvider
	}
	mode := s.Store.Setting("default_mode")
	if mode == "" {
		mode = "default"
	}
	return O("projectsDir", s.ProjectsDir(), "providerId", provider, "modelId", nilIfEmpty(s.Store.Setting("default_model")), "mode", mode,
		"effort", nilIfEmpty(s.Store.Setting("default_effort")), "routing", s.RoutingDefault(), "limits", s.Resumer.Settings())
}

func (s *Service) RoutingDefault() *Obj {
	stored, ok := ParseJSON(firstNonEmpty(s.Store.Setting("routing"), "{}")).(*Obj)
	if !ok {
		return DefaultRouting()
	}
	return DefaultRouting().Merge(stored)
}

func firstNonEmpty(a ...string) string {
	for _, x := range a {
		if x != "" {
			return x
		}
	}
	return ""
}

func (s *Service) SetDefaults(o *Obj) (*Obj, error) {
	if o.Has("routing") {
		rp, err := RoutingPatch(o.Get("routing"), false)
		if err != nil {
			return nil, err
		}
		s.Store.SetSetting("routing", string(ToJSON(s.RoutingDefault().Merge(rp))))
	}
	if l := o.ObjAt("limits"); l != nil {
		if l.Has("autoResume") {
			v := "0"
			if l.Bool("autoResume") {
				v = "1"
			}
			s.Store.SetSetting("auto_resume", v)
		}
		if l.Has("fallback") {
			fb, err := s.fallback(l.Get("fallback"))
			if err != nil {
				return nil, err
			}
			s.Store.SetSetting("limit_fallback", string(ToJSON(fb)))
		}
		s.Resumer.Changed()
	}
	if o.Has("projectsDir") {
		v := ""
		if d := o.Str("projectsDir"); d != "" {
			v = absPath(expandHome(d))
		}
		s.Store.SetSetting("projects_dir", v)
	}
	effort, present, err := effortOf(o.Get("effort"), o.Has("effort"))
	if err != nil {
		return nil, err
	}
	if present {
		e, _ := effort.(string)
		s.Store.SetSetting("default_effort", e)
	}
	if p := o.Str("providerId"); p != "" {
		s.Store.SetSetting("default_provider", p)
	}
	if o.Has("modelId") {
		s.Store.SetSetting("default_model", o.Str("modelId"))
	}
	if m := o.Str("mode"); m != "" && validMode(m) {
		s.Store.SetSetting("default_mode", m)
	}
	return s.Defaults(), nil
}

func (s *Service) fallback(v any) (any, error) {
	fb, _ := v.(*Obj)
	if fb == nil || !truthy(fb.Get("providerId")) {
		return nil, nil
	}
	p := s.Store.GetProvider(str(fb.Get("providerId")))
	if p == nil {
		return nil, fail("No such provider")
	}
	if p.Str("kind") == "claude-code" {
		return nil, fail("The fallback has to be another provider: the Claude login is the one that is out")
	}
	var modelID any
	if m := fb.Str("modelId"); m != "" {
		modelID = m
		if mm := s.Store.GetModel(m); mm == nil || mm.Str("provider_id") != p.Str("id") {
			return nil, fail("That model is not on that provider")
		}
	}
	return O("providerId", p.Str("id"), "modelId", modelID), nil
}

func (s *Service) SetProjectRouting(id string, routing any) (*Obj, error) {
	p, err := s.Project(id)
	if err != nil {
		return nil, err
	}
	var merged any
	if routing != nil {
		rp, err := RoutingPatch(routing, true)
		if err != nil {
			return nil, err
		}
		m := O()
		if r := p.ObjAt("routing"); r != nil {
			m = r.Clone()
		}
		m = m.Merge(rp)
		for _, k := range m.Keys() {
			if m.Get(k) != nil {
				merged = m
				break
			}
		}
	}
	return s.Store.UpdateProject(id, O("routing", merged)), nil
}

func (s *Service) SessionRouting(id string) (*Obj, error) {
	ss, err := s.Session(id)
	if err != nil {
		return nil, err
	}
	return s.Runtime.Routing(ss), nil
}

func (s *Service) SessionLimit(id string, o *Obj) (*Obj, error) {
	if _, err := s.Session(id); err != nil {
		return nil, err
	}
	if o.Has("autoResume") {
		s.Runtime.Emit("session", s.Store.UpdateSession(id, O("auto_resume", o.Get("autoResume"))))
	}
	if o.Bool("resumeNow") {
		s.Resumer.Resume(id)
	}
	s.Resumer.Changed()
	return s.Session(id)
}

// CreateSession: o carries projectId, taskId, title, providerId, modelId, mode, effort,
// prompt, images, startedBy, purpose, as src/service.mjs takes them.
func (s *Service) CreateSession(o *Obj) (*Obj, error) {
	var p, t *Obj
	var err error
	if pid := o.Str("projectId"); pid != "" {
		if p, err = s.Project(pid); err != nil {
			return nil, err
		}
	}
	if p == nil && o.Str("taskId") != "" {
		return nil, fail("A task session needs its project")
	}
	if tid := o.Str("taskId"); tid != "" {
		if t, err = s.Task(tid); err != nil {
			return nil, err
		}
		if t.Str("project_id") != p.Str("id") {
			return nil, fail("That task belongs to another project")
		}
		if t.Str("state") != "active" {
			return nil, fail("The task is "+t.Str("state"), 409)
		}
	}
	d := s.Defaults()
	providerID := o.Str("providerId")
	if providerID == "" {
		providerID = d.Str("providerId")
	}
	provider := s.Store.GetProvider(providerID)
	if provider == nil {
		return nil, fail("No provider " + providerID)
	}
	if ready, reason := ProviderReady(provider); !ready {
		return nil, fail(fmt.Sprintf("%s: %s", provider.Str("name"), str(reason)))
	}
	var modelID any
	if o.Has("modelId") && !isUndef(o.Get("modelId")) {
		modelID = o.Get("modelId")
	} else if providerID == d.Str("providerId") {
		modelID = d.Get("modelId")
	}
	if ms, _ := modelID.(string); ms != "" {
		if m := s.Store.GetModel(ms); m == nil || m.Str("provider_id") != providerID {
			return nil, fail("That model is not on that provider")
		}
	} else {
		modelID = nil
	}
	mode := "default"
	if p != nil {
		mode = o.Str("mode")
		if mode == "" {
			mode = d.Str("mode")
		}
	}
	if !validMode(mode) {
		return nil, fail("Unknown mode " + mode)
	}
	if p == nil {
		os.MkdirAll(s.AskDir(), 0o755)
	}
	var effort any
	if o.Has("effort") && !isUndef(o.Get("effort")) {
		e, _, err := effortOf(o.Get("effort"), true)
		if err != nil {
			return nil, err
		}
		effort = e
	} else {
		effort = d.Get("effort")
	}
	title := strings.TrimSpace(o.Str("title"))
	if title == "" {
		title = "New conversation"
		if t != nil {
			title = "New task session"
		} else if p != nil {
			title = "New session"
		}
	}
	cwd := s.AskDir()
	var pid, tid any
	if t != nil {
		cwd = t.Str("worktree")
		tid = t.Str("id")
	} else if p != nil {
		cwd = p.Str("path")
	}
	if p != nil {
		pid = p.Str("id")
	}
	ss := s.Store.AddSession(O("project_id", pid, "task_id", tid, "title", title, "cwd", cwd, "provider_id", providerID, "model_id", modelID, "permission_mode", mode,
		"effort", nilIfEmpty(str(effort)), "started_by_session", nilIfEmpty(o.Str("startedBy")), "purpose", nilIfEmpty(o.Str("purpose"))))
	s.Runtime.Emit("session", ss)
	if strings.TrimSpace(o.Str("prompt")) != "" {
		so := SendOpts{}
		if imgs := arr(o.Get("images")); len(imgs) > 0 {
			so.Images = imgs
		}
		if _, err := s.Send(ss.Str("id"), o.Str("prompt"), so); err != nil {
			return nil, err
		}
	}
	return s.Session(ss.Str("id"))
}

func isUndef(v any) bool {
	_, u := v.(undefined)
	return u
}

func (s *Service) AskDir() string { return filepath.Join(s.Store.Dir, "ask") }

func (s *Service) Send(id, text string, o SendOpts) (*Obj, error) {
	ss, err := s.Session(id)
	if err != nil {
		return nil, err
	}
	if tid := ss.Str("task_id"); tid != "" {
		if t, _ := s.Task(tid); t != nil && t.Str("state") != "active" {
			return nil, fail("The task is no longer active", 409)
		}
	}
	if !exists(ss.Str("cwd")) {
		return nil, fail("The session's directory is gone: "+ss.Str("cwd"), 409)
	}
	if err := s.Runtime.Send(id, text, o); err != nil {
		return nil, err
	}
	return s.Session(id)
}

func (s *Service) UpdateSession(id string, patch *Obj) (*Obj, error) {
	if _, err := s.Session(id); err != nil {
		return nil, err
	}
	if patch.Has("title") {
		if strings.TrimSpace(patch.Str("title")) == "" {
			return nil, fail("A title cannot be empty")
		}
		s.Runtime.Emit("session", s.Store.UpdateSession(id, O("title", strings.TrimSpace(patch.Str("title")))))
	}
	if m := patch.Str("mode"); m != "" {
		if _, err := s.Runtime.SetMode(id, m); err != nil {
			return nil, fail(err.Error(), 409)
		}
	}
	effort, hasEffort, err := effortOf(patch.Get("effort"), patch.Has("effort"))
	if err != nil {
		return nil, err
	}
	if patch.Has("providerId") || patch.Has("modelId") || hasEffort {
		cur, _ := s.Session(id)
		providerID := patch.Str("providerId")
		if providerID == "" {
			providerID = cur.Str("provider_id")
		}
		provider := s.Store.GetProvider(providerID)
		if provider == nil {
			return nil, fail("No such provider")
		}
		if ready, reason := ProviderReady(provider); !ready {
			return nil, fail(fmt.Sprintf("%s: %s", provider.Str("name"), str(reason)))
		}
		if _, err := s.Runtime.SetModel(id, providerID, patch.Get("modelId"), patch.Has("modelId"), effort, hasEffort); err != nil {
			return nil, fail(err.Error(), 409)
		}
	}
	return s.Session(id)
}

func (s *Service) Interrupt(id string) (bool, error) {
	if _, err := s.Session(id); err != nil {
		return false, err
	}
	return s.Runtime.Interrupt(id), nil
}

// --- artifacts ----------------------------------------------------------------------------

func (s *Service) Artifacts(q ArtifactQuery) []*Obj {
	rows := s.Store.ListArtifacts(q)
	var live []string
	for _, a := range rows {
		if !truthy(a.Get("removed_at")) {
			live = append(live, a.Str("path"))
		}
	}
	open := holders(live)
	out := []*Obj{}
	for _, a := range rows {
		ex := !truthy(a.Get("removed_at")) && exists(a.Str("path"))
		o := a.Clone().Set("exists", ex)
		if ex {
			b, partial := sizeOf(a.Str("path"))
			o.Set("bytes", b).Set("partial", partial)
		} else {
			o.Set("bytes", 0.0).Set("partial", false)
		}
		if open == nil {
			o.Set("heldBy", nil)
		} else {
			h := open[a.Str("path")]
			if h == nil {
				h = []any{}
			}
			o.Set("heldBy", h)
		}
		out = append(out, o)
	}
	return out
}

type CleanResult struct {
	Removed []*Obj
	Kept    []*Obj
	Gone    []string
}

func (s *Service) CleanArtifacts(q ArtifactQuery, paths []string, by string) CleanResult {
	var want map[string]bool
	if len(paths) > 0 {
		want = map[string]bool{}
		for _, p := range paths {
			want[absPath(p)] = true
		}
	}
	byPath := map[string][]*Obj{}
	var order []string
	for _, a := range s.Store.ListArtifacts(q) {
		if want != nil && !want[a.Str("path")] {
			continue
		}
		if _, ok := byPath[a.Str("path")]; !ok {
			order = append(order, a.Str("path"))
		}
		byPath[a.Str("path")] = append(byPath[a.Str("path")], a)
	}
	open := holders(order)
	r := CleanResult{Removed: []*Obj{}, Kept: []*Obj{}, Gone: []string{}}
	for _, p := range order {
		list := byPath[p]
		mark := func(why string) {
			for _, a := range list {
				s.Store.MarkArtifactRemoved(a.Num("id"), why)
			}
		}
		if !exists(p) {
			r.Gone = append(r.Gone, p)
			mark("gone")
			continue
		}
		if !removable(p) {
			r.Kept = append(r.Kept, O("path", p, "why", "not a top-level entry of a temporary folder"))
			continue
		}
		if open == nil {
			r.Kept = append(r.Kept, O("path", p, "why", "this system cannot tell whether a process has it open"))
			continue
		}
		if pids := open[p]; len(pids) > 0 {
			if len(pids) > 5 {
				pids = pids[:5]
			}
			var ps []string
			for _, x := range pids {
				ps = append(ps, str(x))
			}
			r.Kept = append(r.Kept, O("path", p, "why", "open in process "+strings.Join(ps, ", ")))
			continue
		}
		b, _ := sizeOf(p)
		if err := os.RemoveAll(p); err != nil {
			r.Kept = append(r.Kept, O("path", p, "why", err.Error()))
			continue
		}
		r.Removed = append(r.Removed, O("path", p, "bytes", b))
		mark(by)
	}
	return r
}

func mb(b float64) string {
	if b >= 1024*1024 {
		return fmt.Sprintf("%.1f MB", b/1024/1024)
	}
	return fmt.Sprintf("%d KB", int(math.Max(1, math.Round(b/1024))))
}

func (s *Service) cleanAfter(sessionID string) {
	r := s.CleanArtifacts(ArtifactQuery{SessionID: sessionID}, nil, "archive")
	if len(r.Removed) == 0 && len(r.Kept) == 0 {
		return
	}
	if s.Store.GetSession(sessionID) == nil {
		return
	}
	var bytes float64
	var paths []string
	for _, x := range r.Removed {
		bytes += x.Num("bytes")
		paths = append(paths, x.Str("path"))
	}
	var lines []string
	if len(r.Removed) > 0 {
		lines = append(lines, fmt.Sprintf("Removed %d temporary %s this session left in /tmp (%s): %s", len(r.Removed), plural(len(r.Removed), "item"), mb(bytes), strings.Join(paths, ", ")))
	}
	if len(r.Kept) > 0 {
		var ks []string
		for _, k := range r.Kept {
			ks = append(ks, fmt.Sprintf("%s (%s)", k.Str("path"), k.Str("why")))
		}
		lines = append(lines, "Kept "+strings.Join(ks, ", ")+"; Bench tries again when it restarts.")
	}
	s.Runtime.Notice(sessionID, strings.Join(lines, "\n"))
}

func (s *Service) ArchiveSession(id string, archived bool) (*Obj, error) {
	ss, err := s.Session(id)
	if err != nil {
		return nil, err
	}
	if archived {
		s.Loop.SessionGone(ss, "")
		s.Runtime.Close(id)
		s.Runtime.DropScratch(id)
	} else if tid := ss.Str("task_id"); tid != "" {
		if t, _ := s.Task(tid); t != nil && t.Str("state") != "active" {
			return nil, fail("Reopen the task first", 409)
		}
	}
	var at any
	if archived {
		at = isoNow()
	}
	updated := s.Store.UpdateSession(id, O("archived_at", at))
	if archived {
		s.cleanAfter(id)
	}
	s.Runtime.Emit("session", updated)
	return updated, nil
}

func (s *Service) DeleteSession(id string) (*Obj, error) {
	ss, err := s.Session(id)
	if err != nil {
		return nil, err
	}
	s.Loop.SessionGone(ss, "its session was deleted")
	s.Runtime.Close(id)
	os.RemoveAll(s.Runtime.AttachmentsDir(id))
	s.Runtime.DropScratch(id)
	s.cleanAfter(id)
	s.Store.DeleteSessionData(id)
	s.Runtime.Emit("session-removed", id)
	return O("ok", true), nil
}

func (s *Service) Events(id string, q EventQuery) ([]*Obj, error) {
	if _, err := s.Session(id); err != nil {
		return nil, err
	}
	return s.Store.ListEventsWire(id, q), nil
}

func (s *Service) EventThinking(id string, eventID int) (*Obj, error) {
	if _, err := s.Session(id); err != nil {
		return nil, err
	}
	e := s.Store.EventByID(id, eventID)
	if e == nil {
		return nil, fail("No such event", 404)
	}
	blocks := []any{}
	data, _ := e.Get("data").(*Obj)
	for i, b := range arr(data.Get("content")) {
		bo, _ := b.(*Obj)
		if bo.Str("type") == "thinking" {
			if t, ok := bo.Get("thinking").(string); ok {
				blocks = append(blocks, O("index", float64(i), "thinking", t, "signature", or(bo.Get("signature"), nil)))
			}
		}
	}
	return O("id", e.Get("id"), "blocks", blocks), nil
}

// --- providers ------------------------------------------------------------------------

func (s *Service) Providers() []*Obj {
	out := []*Obj{}
	for _, p := range s.Store.ListProviders() {
		ready, reason := ProviderReady(p)
		out = append(out, p.Clone().Set("ready", ready).Set("reason", reason).Set("models", s.Store.ListModels(p.Str("id"))))
	}
	return out
}

func (s *Service) TestProvider(providerID string, modelID string) (*Obj, error) {
	provider := s.Store.GetProvider(providerID)
	if provider == nil {
		return nil, fail("No such provider", 404)
	}
	if ready, reason := ProviderReady(provider); !ready {
		return O("ok", false, "error", reason), nil
	}
	if modelID == "" {
		for _, m := range s.Store.ListModels(providerID) {
			if m.Bool("enabled") {
				modelID = m.Str("id")
				break
			}
		}
	}
	var m *Obj
	if modelID != "" {
		m = s.Store.GetModel(modelID)
	}
	started := time.Now()
	ms := func() float64 { return float64(time.Since(started).Milliseconds()) }
	pe, err := ProviderEnv(provider, m)
	if err != nil {
		return O("ok", false, "error", err.Error(), "ms", ms()), nil
	}
	resCh := make(chan *Obj, 1)
	o := ProcOptions{Cwd: s.Store.Dir, Env: mergeEnv(CleanEnv(), pe), SettingSources: []string{}, MaxTurns: 1, PermissionMode: "dontAsk",
		OnMessage: func(msg *Obj) {
			if msg.Str("type") == "result" {
				select {
				case resCh <- msg:
				default:
				}
			}
		}}
	if m != nil {
		o.Model = InvocationID(m)
	}
	proc, err := StartProc(o)
	if err != nil {
		return O("ok", false, "error", err.Error(), "ms", ms()), nil
	}
	defer proc.Close()
	proc.Send(O("type", "user", "message", O("role", "user", "content", "Reply with exactly OK."), "parent_tool_use_id", nil, "session_id", ""))
	select {
	case r := <-resCh:
		if truthy(r.Get("is_error")) {
			var errs []string
			for _, e := range arr(r.Get("errors")) {
				errs = append(errs, str(e))
			}
			msg := strings.Join(errs, "\n")
			if msg == "" {
				msg = r.Str("result")
			}
			if msg == "" {
				msg = "Failed"
			}
			return O("ok", false, "error", msg, "ms", ms()), nil
		}
		text := r.Str("result")
		return O("ok", true, "response", jsSlice(text, 200), "ms", ms()), nil
	case <-proc.Done():
		msg := "Claude Code process exited"
		if err := proc.Wait(); err != nil {
			msg = err.Error()
		}
		return O("ok", false, "error", msg, "ms", ms()), nil
	case <-time.After(3 * time.Minute):
		return O("ok", false, "error", "timed out", "ms", ms()), nil
	}
}

func (s *Service) Doctor() *Obj {
	run := func(cmd string, args ...string) string {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		out, err := exec.CommandContext(ctx, cmd, args...).Output()
		if err != nil {
			return ""
		}
		return strings.Split(strings.TrimSpace(string(out)), "\n")[0]
	}
	checks := []any{}
	for _, c := range [][]string{{"git", "git", "--version"}, {"node", "node", "--version"}, {"claude", ClaudeBin(), "--version"}, {"gh", "gh", "--version"}, {"tailscale", "tailscale", "version"}} {
		v := run(c[1], c[2:]...)
		detail := v
		if v == "" {
			detail = "not found"
		}
		checks = append(checks, O("name", c[0], "ok", v != "", "detail", detail, "optional", c[0] == "gh" || c[0] == "tailscale" || c[0] == "claude"))
	}
	checks = append(checks, O("name", "gh auth", "ok", GhReady(), "detail", "needed for pull requests", "optional", true))
	checks = append(checks, O("name", "terminal (node-pty)", "ok", true, "detail", "ready", "optional", true))
	for _, p := range s.Store.ListProviders() {
		ready, reason := ProviderReady(p)
		d := "ready"
		if reason != nil {
			d = str(reason)
		}
		checks = append(checks, O("name", "provider: "+p.Str("name"), "ok", ready, "detail", d, "optional", !p.Bool("enabled")))
	}
	return O("checks", checks)
}

func (s *Service) Shutdown() {
	s.Resumer.Shutdown()
	s.Runtime.Shutdown()
}
