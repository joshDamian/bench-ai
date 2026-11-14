package bench

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Claude sessions, one Claude Code process per session, as src/runtime.mjs keeps them.
// Events: "session", "session-removed", "event", "delta", "approval", "approval-done",
// "suggestion", "commands", "turn-done", "limit", "task".

const idleMs = 30 * time.Minute
const resultChars = 20000
const maxFile = 60 * 1024 * 1024
const maxFiles = 60 * 1024 * 1024
const MaxUpload = 300 * 1024 * 1024
const CheckTries = 3
const commandsFresh = 10 * time.Minute
const commandsProbe = 20 * time.Second

var Modes = []string{"default", "acceptEdits", "plan", "auto", "bypassPermissions"}

func validMode(m string) bool {
	for _, x := range Modes {
		if x == m {
			return true
		}
	}
	return false
}

var unsafeName = regexp.MustCompile(`[^\w.\- ]+`)

func safeFileName(name any) string {
	n := str(name)
	if n == "" {
		n = "file"
	}
	s := unsafeName.ReplaceAllString(filepath.Base(n), "_")
	if r := []rune(s); len(r) > 120 {
		s = string(r[len(r)-120:])
	}
	if s == "" {
		return "file"
	}
	return s
}

func configDir() string {
	if v := os.Getenv("CLAUDE_CONFIG_DIR"); v != "" {
		return v
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".claude")
}

func userInstructions() string {
	b, err := os.ReadFile(filepath.Join(configDir(), "CLAUDE.md"))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

var nonAlnum = regexp.MustCompile(`[^a-zA-Z0-9]`)

func memoryDir(projectPath string) string {
	dir := filepath.Join(configDir(), "projects", nonAlnum.ReplaceAllString(projectPath, "-"), "memory")
	if exists(dir) {
		return dir
	}
	return ""
}

func personSkillsPlugin(root string) string {
	skills, _ := filepath.Abs(filepath.Join(configDir(), "skills"))
	ents, err := os.ReadDir(skills)
	if err != nil || len(ents) == 0 {
		return ""
	}
	dir := filepath.Join(root, "person-skills")
	manifest := filepath.Join(dir, ".claude-plugin", "plugin.json")
	link := filepath.Join(dir, "skills")
	if !exists(manifest) {
		if os.MkdirAll(filepath.Dir(manifest), 0o755) != nil {
			return ""
		}
		body := "{\n  \"name\": \"person\",\n  \"version\": \"1.0.0\",\n  \"description\": \"The person's own skills, from ~/.claude/skills.\"\n}\n"
		if os.WriteFile(manifest, []byte(body), 0o644) != nil {
			return ""
		}
	}
	points, _ := os.Readlink(link)
	if points != skills {
		os.RemoveAll(link)
		if os.Symlink(skills, link) != nil {
			return ""
		}
	}
	return dir
}

func AttachNote(dir string) string {
	return "# Sending files to the person\n\nYou can send the person files: Bench shows a file as an attachment card under your reply - an image or PDF preview, the start of a document, a table's first rows - which opens in a viewer and can be saved or shared. To send one, put it in this session's attachments folder, " + dir + ", or anywhere outside the project's tracked files, and write its full path in your reply. That is all it takes: there is no upload step, no tool to call, and no need to serve it over HTTP or paste its contents. Use a clear file name, since that is what the card shows. Files the project's git tracks are treated as source, not attachments, so copy one into the attachments folder to send it."
}

func scratchNote(dir string) string {
	return "# Scratch files\n\nThis session's scratch folder is " + dir + ", and TMPDIR points at it. Put anything temporary there - logs, screenshots, downloads and throwaway scripts - rather than in /tmp or in the project. Bench deletes the folder when the session is archived, so nothing in it outlives the session; anything worth keeping belongs in the project."
}

func treeOf(dir string) string {
	t, err := WorkingTree(dir)
	if err != nil {
		return ""
	}
	return t
}

func trimResult(content any) any {
	switch c := content.(type) {
	case string:
		if jsLen(c) > resultChars {
			cut := jsSlice(c, resultChars)
			return fmt.Sprintf("%s\n… (%d more characters)", cut, jsLen(c)-resultChars)
		}
		return c
	case []any:
		out := make([]any, len(c))
		for i, b := range c {
			bo, ok := b.(*Obj)
			if ok && bo.Str("type") == "text" {
				out[i] = bo.Clone().Set("text", trimResult(bo.Get("text")))
			} else if ok && bo.Str("type") == "image" {
				out[i] = O("type", "text", "text", "[image]")
			} else {
				out[i] = b
			}
		}
		return out
	}
	return content
}

// jsSlice is s.slice(0, n) counted in UTF-16 units, without splitting a rune.
func jsSlice(s string, n int) string {
	k := 0
	for i, r := range s {
		w := 1
		if r >= 0x10000 {
			w = 2
		}
		if k+w > n {
			return s[:i]
		}
		k += w
	}
	return s
}

// jsTail is s.slice(-n) in UTF-16 units.
func jsTail(s string, n int) string {
	l := jsLen(s)
	if l <= n {
		return s
	}
	skip := l - n
	k := 0
	for i, r := range s {
		if k >= skip {
			return s[i:]
		}
		if r >= 0x10000 {
			k += 2
		} else {
			k++
		}
	}
	return ""
}

var wsRun = regexp.MustCompile(`\s+`)
var lastWord = regexp.MustCompile(`\s+\S*$`)

func TitleFrom(text string) string {
	line := wsRun.ReplaceAllString(strings.Split(strings.TrimSpace(text), "\n")[0], " ")
	if jsLen(line) <= 60 {
		if line == "" {
			return "New session"
		}
		return line
	}
	return lastWord.ReplaceAllString(jsSlice(line, 60), "") + "…"
}

type live struct {
	sessionID    string
	proc         *Proc
	mu           sync.Mutex
	closed       bool
	turns        int
	lastUsed     time.Time
	commands     []any
	providerID   string
	providerKind string
	lastStderr   string
	checkTries   int
	writeUp      bool
	limit        *LimitHit
	limitAt      *time.Time
}

type heldApproval struct {
	approval    *Obj
	resolve     chan *Obj
	suggestions []any
	once        sync.Once
}

type Runtime struct {
	Emitter
	Store        *Store
	idle         time.Duration
	mcp          func(sessionID string) *MCPServer
	mu           sync.Mutex
	startMu      sync.Mutex
	live         map[string]*live
	claudePids   map[int]string
	approvals    map[string]*heldApproval
	approvalList []string
	checks       map[string]*checkRun
	commandCache map[string]commandsAt
	probes       map[string]*probe
	tmpBefore    map[string]map[string]bool
	notes        *Notes
	stop         chan struct{}
}

type checkRun struct {
	done chan struct{}
	r    *CheckResult
}

type commandsAt struct {
	at   time.Time
	list []any
}

type probe struct {
	done chan struct{}
	list []any
}

func NewRuntime(store *Store, mcp func(string) *MCPServer) *Runtime {
	r := &Runtime{Store: store, idle: idleMs, mcp: mcp, live: map[string]*live{}, claudePids: map[int]string{}, approvals: map[string]*heldApproval{},
		checks: map[string]*checkRun{}, commandCache: map[string]commandsAt{}, probes: map[string]*probe{}, tmpBefore: map[string]map[string]bool{}, stop: make(chan struct{})}
	if v, err := strconv.Atoi(Env("IDLE_MS")); err == nil && v > 0 {
		r.idle = time.Duration(v) * time.Millisecond
	}
	store.ResetLiveSessions()
	for _, t := range store.ListTasks("") {
		if t.Str("check_status") == "running" {
			store.UpdateTask(t.Str("id"), O("check_status", nil, "check_tree", nil))
		}
	}
	r.SweepScratch()
	go func() {
		tk := time.NewTicker(time.Minute)
		defer tk.Stop()
		for {
			select {
			case <-tk.C:
				r.reap()
			case <-r.stop:
				return
			}
		}
	}()
	return r
}

func (r *Runtime) update(id string, patch *Obj) *Obj {
	s := r.Store.UpdateSession(id, patch)
	r.Emit("session", s)
	return s
}

func (r *Runtime) event(id, kind string, data any) *Obj {
	e := r.Store.AddEvent(id, kind, data)
	r.Emit("event", e)
	return e
}

func (r *Runtime) session(id string) (*Obj, error) {
	s := r.Store.GetSession(id)
	if s == nil {
		return nil, fail("No such session", 404)
	}
	return s, nil
}

// Record adds a transcript entry from outside a turn.
func (r *Runtime) Record(id, kind string, data any) *Obj { return r.event(id, kind, data) }

func (r *Runtime) Notice(id, text string) *Obj { return r.event(id, "notice", O("text", text)) }

func (r *Runtime) getLive(id string) *live {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.live[id]
}

func (r *Runtime) IsLive(id string) bool { return r.getLive(id) != nil }

func (r *Runtime) Pids() map[int]string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[int]string{}
	for k, v := range r.claudePids {
		out[k] = v
	}
	return out
}

func commandList(list []any) []any {
	out := []any{}
	for _, x := range list {
		c, _ := x.(*Obj)
		out = append(out, O("name", str(c.Get("name")), "description", str(c.Get("description")), "argumentHint", str(c.Get("argumentHint"))))
	}
	return out
}

func (r *Runtime) Commands(id string) []any {
	if l := r.getLive(id); l != nil {
		l.mu.Lock()
		c := l.commands
		l.mu.Unlock()
		if c != nil {
			return c
		}
	}
	s := r.Store.GetSession(id)
	if s != nil {
		r.mu.Lock()
		c, ok := r.commandCache[s.Str("cwd")]
		r.mu.Unlock()
		if ok {
			return c.list
		}
	}
	return []any{}
}

func (r *Runtime) ListCommands(id string) ([]any, error) {
	s, err := r.session(id)
	if err != nil {
		return nil, err
	}
	if l := r.getLive(id); l != nil {
		l.mu.Lock()
		c := l.commands
		l.mu.Unlock()
		if c != nil {
			return c, nil
		}
	}
	cwd := s.Str("cwd")
	r.mu.Lock()
	if c, ok := r.commandCache[cwd]; ok && time.Since(c.at) < commandsFresh {
		r.mu.Unlock()
		return c.list, nil
	}
	pr := r.probes[cwd]
	if pr == nil {
		pr = &probe{done: make(chan struct{})}
		r.probes[cwd] = pr
		go func() {
			pr.list = r.probeCommands(s)
			r.mu.Lock()
			delete(r.probes, cwd)
			r.mu.Unlock()
			close(pr.done)
		}()
	}
	r.mu.Unlock()
	<-pr.done
	return pr.list, nil
}

func (r *Runtime) probeCommands(s *Obj) []any {
	env := append(CleanEnv(), "CLAUDE_AGENT_SDK_CLIENT_APP=bench")
	if p := r.Store.GetProvider(s.Str("provider_id")); p != nil {
		var model *Obj
		if s.Str("model_id") != "" {
			model = r.Store.GetModel(s.Str("model_id"))
		}
		if pe, err := ProviderEnv(p, model); err == nil {
			env = mergeEnv(env, pe)
		}
	}
	o := ProcOptions{Cwd: s.Str("cwd"), Env: env}
	if s.Str("project_id") != "" {
		o.SettingSources = []string{"project", "local"}
		o.Plugins = r.plugins()
	} else {
		o.SettingSources = []string{}
	}
	cached := func() []any {
		r.mu.Lock()
		defer r.mu.Unlock()
		if c, ok := r.commandCache[s.Str("cwd")]; ok {
			return c.list
		}
		return []any{}
	}
	p, err := StartProc(o)
	if err != nil {
		return cached()
	}
	defer p.Close()
	list, err := p.Ready(commandsProbe)
	if err != nil {
		return cached()
	}
	cl := commandList(list)
	r.mu.Lock()
	r.commandCache[s.Str("cwd")] = commandsAt{time.Now(), cl}
	r.mu.Unlock()
	return cl
}

func (r *Runtime) setCommands(id string, l *live, list []any) {
	cl := commandList(list)
	l.mu.Lock()
	l.commands = cl
	l.mu.Unlock()
	if s := r.Store.GetSession(id); s != nil {
		r.mu.Lock()
		r.commandCache[s.Str("cwd")] = commandsAt{time.Now(), cl}
		r.mu.Unlock()
	}
	r.Emit("commands", O("session_id", id, "commands", cl))
}

func (r *Runtime) Notes() *Notes {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.notes == nil {
		r.notes = NewNotes(r.Store.Dir)
	}
	return r.notes
}

func (r *Runtime) AttachmentsDir(id string) string { return filepath.Join(r.Store.Dir, "attachments", id) }
func (r *Runtime) ScratchDir(id string) string     { return filepath.Join(r.Store.Dir, "scratch", id) }
func (r *Runtime) DropScratch(id string)           { os.RemoveAll(r.ScratchDir(id)) }

func (r *Runtime) SweepScratch() {
	ents, err := os.ReadDir(filepath.Join(r.Store.Dir, "scratch"))
	if err != nil {
		return
	}
	for _, e := range ents {
		id := e.Name()
		s := r.Store.GetSession(id)
		if (s == nil || truthy(s.Get("archived_at"))) && !r.IsLive(id) {
			r.DropScratch(id)
		}
	}
}

type savedFile struct {
	Name, Path, Type string
	Size             int64
}

func (r *Runtime) saveFiles(id string, files []any) ([]savedFile, error) {
	dir := r.AttachmentsDir(id)
	os.MkdirAll(dir, 0o755)
	var total int64
	var out []savedFile
	for _, x := range files {
		f, _ := x.(*Obj)
		b, _ := base64.StdEncoding.DecodeString(f.Str("data"))
		if len(b) > maxFile {
			return nil, failf(413, "%s is over %d MB", f.Str("name"), maxFile/1024/1024)
		}
		total += int64(len(b))
		if total > maxFiles {
			return nil, failf(413, "Attachments over %d MB in one message", maxFiles/1024/1024)
		}
		safe := safeFileName(f.Get("name"))
		file := filepath.Join(dir, strconv.FormatInt(time.Now().UnixMilli(), 36)+"-"+safe)
		os.WriteFile(file, b, 0o644)
		out = append(out, savedFile{Name: safe, Path: file, Size: int64(len(b)), Type: f.Str("media_type")})
	}
	return out, nil
}

// ReceiveFile streams one uploaded file into the session's attachments folder.
func (r *Runtime) ReceiveFile(id, name, typ string, body io.Reader) (*Obj, error) {
	s, err := r.session(id)
	if err != nil {
		return nil, err
	}
	if truthy(s.Get("archived_at")) {
		return nil, fail("This session is archived", 409)
	}
	dir := r.AttachmentsDir(id)
	os.MkdirAll(dir, 0o755)
	safe := safeFileName(name)
	rnd := strconv.FormatFloat(rand.Float64(), 'f', -1, 64)
	r36 := strconv.FormatInt(int64(rand.Float64()*1e12), 36)
	_ = rnd
	if len(r36) > 4 {
		r36 = r36[:4]
	}
	file := strconv.FormatInt(time.Now().UnixMilli(), 36) + "-" + r36 + "-" + safe
	full := filepath.Join(dir, file)
	fh, err := os.Create(full)
	if err != nil {
		return nil, err
	}
	n, err := io.Copy(fh, io.LimitReader(body, MaxUpload+1))
	fh.Close()
	if err != nil {
		os.Remove(full)
		return nil, err
	}
	if n > MaxUpload {
		os.Remove(full)
		return nil, failf(413, "%s is over %d MB", safe, MaxUpload/1024/1024)
	}
	if n == 0 {
		os.Remove(full)
		return nil, fail(safe+" is empty", 400)
	}
	if len(typ) > 120 {
		typ = typ[:120]
	}
	return O("file", file, "name", safe, "size", float64(n), "type", typ), nil
}

var uploadPrefix = regexp.MustCompile(`^[a-z0-9]+-[a-z0-9]{1,4}-`)

func (r *Runtime) uploaded(id string, uploads []any) ([]savedFile, error) {
	dir := r.AttachmentsDir(id)
	var out []savedFile
	for _, x := range uploads {
		u, _ := x.(*Obj)
		file := u.Str("file")
		full := filepath.Join(dir, file)
		if file == "" || filepath.Base(file) != file || !exists(full) {
			return nil, fail("An attached file is missing; attach it again", 400)
		}
		st, _ := os.Stat(full)
		out = append(out, savedFile{Name: uploadPrefix.ReplaceAllString(file, ""), Path: full, Size: st.Size(), Type: u.Str("type")})
	}
	return out, nil
}

type SendOpts struct {
	Images  []any
	Files   []any
	Uploads []any
	WriteUp bool
}

var archiveExt = regexp.MustCompile(`(?i)\.(apk|aab|apks|xapk|ipa|jar|aar|zip)$`)
var newTitle = regexp.MustCompile(`^New (session|task|conversation)`)

// Send is one message from the person.
func (r *Runtime) Send(id, text string, o SendOpts) error {
	s, err := r.session(id)
	if err != nil {
		return err
	}
	if truthy(s.Get("archived_at")) {
		return fail("This session is archived")
	}
	var saved []savedFile
	if len(o.Uploads) > 0 {
		u, err := r.uploaded(id, o.Uploads)
		if err != nil {
			return err
		}
		saved = append(saved, u...)
	}
	if len(o.Files) > 0 {
		f, err := r.saveFiles(id, o.Files)
		if err != nil {
			return err
		}
		saved = append(saved, f...)
	}
	if strings.TrimSpace(text) == "" && len(o.Images) == 0 && len(saved) == 0 {
		return fail("Nothing to send")
	}
	note := ""
	if len(saved) > 0 {
		var lines []string
		for _, f := range saved {
			lines = append(lines, "- "+f.Path)
		}
		one, it := "s", "them"
		if len(saved) == 1 {
			one, it = "", "it"
		}
		note = fmt.Sprintf("\n\nAttached file%s (read %s as needed):\n%s", one, it, strings.Join(lines, "\n"))
	}
	hint := ""
	for _, f := range saved {
		if archiveExt.MatchString(f.Name) {
			hint = "\n(An .apk, .aab, .ipa, .jar or .zip is a zip archive: inspect it with Bash - unzip -l, unzip -p, aapt or apkanalyzer where installed - rather than Read.)"
			break
		}
	}
	notesOf := ""
	if pid := s.Str("project_id"); pid != "" {
		if p := r.Store.GetProject(pid); p != nil {
			notesOf = p.Str("path")
		}
	}
	notes := r.Notes()
	body := strings.TrimSpace(text + note + hint + MentionedFiles(s.Str("cwd"), text, func(name string) string { return notes.Resolve(notesOf, name) }))
	// One process per session, however many messages arrive at once.
	r.startMu.Lock()
	l := r.getLive(id)
	if l == nil {
		if l, err = r.start(s); err != nil {
			r.startMu.Unlock()
			return err
		}
	}
	r.startMu.Unlock()
	l.mu.Lock()
	l.checkTries = 0
	l.writeUp = o.WriteUp
	l.mu.Unlock()
	var content any = body
	if len(o.Images) > 0 {
		blocks := []any{}
		for _, x := range o.Images {
			im, _ := x.(*Obj)
			blocks = append(blocks, O("type", "image", "source", O("type", "base64", "media_type", im.Get("media_type"), "data", im.Get("data"))))
		}
		if body != "" {
			blocks = append(blocks, O("type", "text", "text", body))
		}
		content = blocks
	}
	l.mu.Lock()
	l.turns++
	l.lastUsed = time.Now()
	l.mu.Unlock()
	l.proc.Send(O("type", "user", "message", O("role", "user", "content", content), "parent_tool_use_id", nil))
	files := []any{}
	for _, f := range saved {
		files = append(files, O("name", f.Name, "size", float64(f.Size), "type", f.Type))
	}
	r.event(id, "user", O("text", text, "images", float64(len(o.Images)), "files", files))
	first := !truthy(s.Get("claude_session_id")) && len(r.Store.ListEvents(id, EventQuery{Limit: 2})) == 1
	title := ""
	if strings.TrimSpace(text) != "" {
		title = TitleFrom(text)
	} else if len(saved) > 0 {
		var names []string
		for _, f := range saved {
			names = append(names, f.Name)
		}
		title = TitleFrom(strings.Join(names, ", "))
	}
	status := "running"
	if r.pendingFor(id) {
		status = "waiting"
	}
	patch := O("status", status, "error", nil)
	if s.Get("pause") != nil {
		patch.Set("pause", nil)
	}
	if first && title != "" && newTitle.MatchString(s.Str("title")) {
		patch.Set("title", title)
	}
	r.update(id, patch)
	return nil
}

func (r *Runtime) plugins() []string {
	if person := personSkillsPlugin(r.Store.Dir); person != "" {
		return []string{Pack(), person}
	}
	return []string{Pack()}
}

// Routing is the subagent routing a session runs with.
func (r *Runtime) Routing(s *Obj) *Obj {
	base, _ := ParseJSON(r.Store.Setting("routing")).(*Obj)
	var project, task *Obj
	if pid := s.Str("project_id"); pid != "" {
		if p := r.Store.GetProject(pid); p != nil {
			project = p.ObjAt("routing")
		}
	}
	if tid := s.Str("task_id"); tid != "" {
		if t := r.Store.GetTask(tid); t != nil {
			task = t.ObjAt("routing")
		}
	}
	return ResolveRouting(base, project, task)
}

func (r *Runtime) start(s *Obj) (*live, error) {
	provider := r.Store.GetProvider(s.Str("provider_id"))
	if provider == nil {
		return nil, fail(fmt.Sprintf("Provider %s is gone; pick another", s.Str("provider_id")))
	}
	var model *Obj
	if s.Str("model_id") != "" {
		model = r.Store.GetModel(s.Str("model_id"))
	}
	var project *Obj
	if pid := s.Str("project_id"); pid != "" {
		project = r.Store.GetProject(pid)
	}
	ask := s.Str("project_id") == ""
	scratch := r.ScratchDir(s.Str("id"))
	os.MkdirAll(scratch, 0o755)
	if ask {
		os.MkdirAll(s.Str("cwd"), 0o755)
	}
	var routed *Obj
	if !ask {
		if rt := r.Routing(s); truthy(rt.Get("enabled")) {
			routed = rt
		}
	}
	env := CleanEnv()
	if routed != nil && truthy(routed.Get("all")) && provider.Str("kind") == "claude-code" {
		env = mergeEnv(env, []string{"CLAUDE_CODE_SUBAGENT_MODEL=" + routed.Str("all")})
	}
	pe, err := ProviderEnv(provider, model)
	if err != nil {
		return nil, err
	}
	env = mergeEnv(env, pe)
	env = mergeEnv(env, []string{"TMPDIR=" + scratch, "TMP=" + scratch, "TEMP=" + scratch, "CLAUDE_AGENT_SDK_CLIENT_APP=bench"})
	id := s.Str("id")
	attach := AttachNote(r.AttachmentsDir(id))
	var parts []string
	add := func(x string) {
		if x != "" {
			parts = append(parts, x)
		}
	}
	if ask {
		add(userInstructions())
		add(AskBrief)
		add(attach)
	} else {
		pp := ""
		if project != nil {
			pp = project.Str("path")
		}
		add(userInstructions())
		add(scratchNote(scratch))
		add(attach)
		add(r.Notes().Brief(pp))
		if routed != nil {
			add(RoutingNote)
		}
		add(r.taskBrief(s))
	}
	instructions := strings.Join(parts, "\n\n")
	memory := ""
	if project != nil {
		a, _ := filepath.Abs(project.Str("path"))
		b, _ := filepath.Abs(s.Str("cwd"))
		if a != b {
			memory = memoryDir(project.Str("path"))
		}
	}
	os.MkdirAll(r.AttachmentsDir(id), 0o755)
	l := &live{sessionID: id, lastUsed: time.Now(), providerID: provider.Str("id"), providerKind: provider.Str("kind")}
	var priced *Obj
	if provider.Str("kind") != "claude-code" {
		priced = model
	}
	o := ProcOptions{
		Cwd:               s.Str("cwd"),
		Env:               env,
		PermissionMode:    s.Str("permission_mode"),
		SkipPermissions:   true,
		PartialMessages:   true,
		PromptSuggestions: true,
		Effort:            s.Str("effort"),
		Resume:            s.Str("claude_session_id"),
		AppendSystemPrompt: instructions,
		OnStderr: func(line string) {
			if regexp.MustCompile(`(?i)error`).MatchString(line) {
				l.mu.Lock()
				if len(line) > 2000 {
					line = line[:2000]
				}
				l.lastStderr = line
				l.mu.Unlock()
			}
		},
	}
	if ask {
		o.AddDirs = []string{r.AttachmentsDir(id)}
		o.SettingSources = []string{}
		o.Tools = AskTools
	} else {
		o.AddDirs = []string{r.AttachmentsDir(id), scratch, r.Notes().Global}
		if project != nil {
			o.AddDirs = append(o.AddDirs, r.Notes().Dir(project.Str("path")))
		}
		o.SettingSources = []string{"project", "local"}
		o.Plugins = r.plugins()
	}
	if routed != nil {
		o.Agents = Subagents(routed)
	}
	if model != nil {
		o.Model = InvocationID(model)
	}
	if memory != "" {
		o.Settings = O("autoMemoryDirectory", memory)
	}
	o.CanUseTool = func(ctx context.Context, req *Obj) *Obj { return r.ask(ctx, id, req) }
	if r.mcp != nil {
		o.MCP = r.mcp(id)
	}
	if s.Str("task_id") != "" && s.Str("purpose") != "review" {
		o.Hooks = append(o.Hooks, HookSpec{Event: "Stop", Timeout: int(CheckTimeout.Seconds()) + 60, Fn: func(ctx context.Context, in *Obj) *Obj { return r.onStop(ctx, id, in) }})
	}
	o.Hooks = append(o.Hooks,
		HookSpec{Event: "PreToolUse", Fn: func(ctx context.Context, in *Obj) *Obj { return r.beforeTool(in) }},
		HookSpec{Event: "PostToolUse", Fn: func(ctx context.Context, in *Obj) *Obj { return r.afterTool(id, in) }},
		HookSpec{Event: "PostToolUseFailure", Fn: func(ctx context.Context, in *Obj) *Obj { return r.afterTool(id, in) }},
	)
	o.OnMessage = func(m *Obj) {
		l.mu.Lock()
		l.lastUsed = time.Now()
		l.mu.Unlock()
		r.handle(l, id, m, priced)
	}
	proc, err := StartProc(o)
	if err != nil {
		return nil, err
	}
	l.proc = proc
	r.mu.Lock()
	r.live[id] = l
	r.claudePids[proc.Pid] = id
	r.mu.Unlock()
	go r.pump(l, s)
	return l, nil
}

func (r *Runtime) pump(l *live, s *Obj) {
	id := s.Str("id")
	err := l.proc.Wait()
	r.mu.Lock()
	delete(r.claudePids, l.proc.Pid)
	r.mu.Unlock()
	l.mu.Lock()
	closed := l.closed
	stderr := l.lastStderr
	l.closed = true
	l.mu.Unlock()
	if !closed {
		if err != nil {
			message := err.Error()
			if stderr != "" && !regexp.MustCompile(`(?i)aborted`).MatchString(message) {
				message = message + "\n" + stderr
			}
			r.event(id, "error", O("message", message))
			if len(message) > 2000 {
				message = message[:2000]
			}
			r.update(id, O("status", "error", "error", message))
		} else {
			r.update(id, O("status", "idle"))
		}
	}
	r.mu.Lock()
	if r.live[id] == l {
		delete(r.live, id)
	}
	r.mu.Unlock()
	r.dropApprovals(id, "The session ended.")
}

func (r *Runtime) handle(l *live, id string, m *Obj, priced *Obj) {
	switch m.Str("type") {
	case "stream_event":
		ev := m.ObjAt("event")
		if truthy(m.Get("parent_tool_use_id")) || ev.Str("type") != "content_block_delta" {
			return
		}
		d := ev.ObjAt("delta")
		if d.Str("type") == "text_delta" {
			r.Emit("delta", O("session_id", id, "text", d.Str("text")))
		} else if d.Str("type") == "thinking_delta" && d.Str("thinking") != "" {
			r.Emit("delta", O("session_id", id, "text", d.Str("thinking"), "thinking", true))
		}
	case "system":
		switch m.Str("subtype") {
		case "init":
			go func() {
				if list, err := l.proc.Ready(30 * time.Second); err == nil {
					l.mu.Lock()
					closed := l.closed
					l.mu.Unlock()
					if !closed {
						r.setCommands(id, l, list)
					}
				}
			}()
			if s := r.Store.GetSession(id); s != nil && s.Str("claude_session_id") != m.Str("session_id") {
				r.update(id, O("claude_session_id", m.Get("session_id")))
			}
			r.event(id, "init", O("model", or(m.Get("model"), Undefined), "permissionMode", or(m.Get("permissionMode"), Undefined), "cwd", or(m.Get("cwd"), Undefined), "version", or(m.Get("claude_code_version"), Undefined)))
			for _, x := range arr(m.Get("plugin_errors")) {
				e, _ := x.(*Obj)
				r.event(id, "notice", O("text", fmt.Sprintf("Plugin %s did not load fully: %s", str(e.Get("plugin")), str(e.Get("message")))))
			}
		case "commands_changed":
			r.setCommands(id, l, arr(m.Get("commands")))
		case "compact_boundary":
			r.event(id, "notice", O("text", "Context compacted"))
		case "api_retry":
			a := "?"
			if m.Get("attempt") != nil {
				a = str(m.Get("attempt"))
			}
			r.event(id, "notice", O("text", fmt.Sprintf("API error, retrying (attempt %s)", a)))
		}
	case "rate_limit_event":
		if at := LimitFromEvent(m.ObjAt("rate_limit_info")); at != nil {
			l.mu.Lock()
			l.limitAt = at
			l.mu.Unlock()
		}
	case "assistant":
		msg := m.ObjAt("message")
		blocks := arr(msg.Get("content"))
		if m.Str("error") == "rate_limit" && !truthy(m.Get("parent_tool_use_id")) && l.providerKind == "claude-code" {
			var texts []string
			for _, b := range blocks {
				if bo, _ := b.(*Obj); bo.Str("type") == "text" {
					texts = append(texts, bo.Str("text"))
				}
			}
			if hit := ParseLimit(strings.Join(texts, "\n"), time.Now()); hit != nil {
				l.mu.Lock()
				l.limit = hit
				l.mu.Unlock()
			}
		}
		content := []any{}
		for _, b := range blocks {
			bo, _ := b.(*Obj)
			if bo.Str("type") == "thinking" && !truthy(bo.Get("thinking")) {
				continue
			}
			if bo.Str("type") == "redacted_thinking" {
				continue
			}
			content = append(content, b)
		}
		if len(content) == 0 && !truthy(m.Get("error")) {
			return
		}
		var errv any
		if truthy(m.Get("error")) {
			errv = m.Get("error")
		}
		r.event(id, "assistant", O("id", or(msg.Get("id"), Undefined), "content", content, "parent", or(m.Get("parent_tool_use_id"), nil), "error", errv, "model", or(msg.Get("model"), Undefined)))
	case "user":
		if truthy(m.Get("isReplay")) {
			return
		}
		var results []any
		for _, b := range arr(m.ObjAt("message").Get("content")) {
			bo, _ := b.(*Obj)
			if bo.Str("type") == "tool_result" {
				results = append(results, O("tool_use_id", bo.Get("tool_use_id"), "is_error", truthy(bo.Get("is_error")), "content", trimResult(bo.Get("content"))))
			}
		}
		if len(results) == 0 {
			return
		}
		r.event(id, "tool_result", O("parent", or(m.Get("parent_tool_use_id"), nil), "results", results))
	case "result":
		r.onResult(l, id, m, priced)
	case "prompt_suggestion":
		t := m.Str("suggestion")
		if t == "" {
			t = m.Str("text")
		}
		if t != "" {
			r.Emit("suggestion", O("session_id", id, "text", t))
		}
	}
}

func (r *Runtime) onResult(l *live, id string, m *Obj, priced *Obj) {
	l.mu.Lock()
	if q, ok := m.Get("queued_turn_count").(float64); ok {
		l.turns = int(q)
	} else {
		l.turns = int(math.Max(0, float64(l.turns-1)))
	}
	turns := l.turns
	l.mu.Unlock()
	mu := m.ObjAt("modelUsage")
	var input, output float64
	var hosted float64
	for _, k := range mu.Keys() {
		u, _ := mu.Get(k).(*Obj)
		input += u.Num("inputTokens") + u.Num("cacheReadInputTokens") + u.Num("cacheCreationInputTokens")
		output += u.Num("outputTokens")
		if priced != nil {
			if c, ok := PriceUsage(priced, u.Num("inputTokens"), u.Num("outputTokens"), u.Num("cacheReadInputTokens"), u.Num("cacheCreationInputTokens")); ok {
				hosted += c
			}
		}
	}
	var total float64
	if priced != nil {
		total = hosted
	} else {
		total = m.Num("total_cost_usd")
	}
	s := r.Store.GetSession(id)
	if s == nil {
		return
	}
	byModel := O()
	if prev := s.ObjAt("model_usage"); prev != nil {
		byModel = prev.Clone()
	}
	for _, name := range mu.Keys() {
		u, _ := mu.Get(name).(*Obj)
		var cost float64
		if priced != nil {
			cost, _ = PriceUsage(priced, u.Num("inputTokens"), u.Num("outputTokens"), u.Num("cacheReadInputTokens"), u.Num("cacheCreationInputTokens"))
		} else {
			cost = u.Num("costUSD")
		}
		was, _ := byModel.Get(name).(*Obj)
		if was == nil {
			was = O("cost", 0.0, "input_tokens", 0.0, "output_tokens", 0.0)
		}
		byModel.Set(name, O(
			"cost", math.Max(was.Num("cost"), cost),
			"input_tokens", math.Max(was.Num("input_tokens"), u.Num("inputTokens")+u.Num("cacheReadInputTokens")+u.Num("cacheCreationInputTokens")),
			"output_tokens", math.Max(was.Num("output_tokens"), u.Num("outputTokens")),
		))
	}
	var text any = Undefined
	if m.Str("subtype") != "success" {
		var errs []string
		for _, e := range arr(m.Get("errors")) {
			errs = append(errs, str(e))
		}
		if j := strings.Join(errs, "\n"); j != "" {
			text = j
		} else if truthy(m.Get("result")) {
			text = m.Get("result")
		} else {
			text = nil
		}
	}
	r.event(id, "result", O("subtype", m.Get("subtype"), "is_error", truthy(m.Get("is_error")), "duration_ms", or(m.Get("duration_ms"), Undefined), "turns", or(m.Get("num_turns"), Undefined), "cost", total, "text", text))
	status := "idle"
	if turns > 0 {
		status = "running"
	} else if r.pendingFor(id) {
		status = "waiting"
	}
	patch := O("status", status, "cost", math.Max(s.Num("cost"), total), "input_tokens", math.Max(s.Num("input_tokens"), input), "output_tokens", math.Max(s.Num("output_tokens"), output))
	if byModel.Len() > 0 {
		patch.Set("model_usage", byModel)
	}
	if truthy(m.Get("is_error")) && m.Str("subtype") != "error_during_execution" {
		var errs []string
		for _, e := range arr(m.Get("errors")) {
			errs = append(errs, str(e))
		}
		j := strings.Join(errs, "\n")
		if len(j) > 2000 {
			j = j[:2000]
		}
		if j == "" {
			patch.Set("error", nil)
		} else {
			patch.Set("error", j)
		}
	}
	r.update(id, patch)
	l.mu.Lock()
	l.writeUp = false
	limit := l.limit
	limitAt := l.limitAt
	l.limit = nil
	l.limitAt = nil
	l.mu.Unlock()
	if limit != nil {
		until := limitAt
		if until == nil {
			until = limit.ResetsAt
		}
		r.Emit("limit", LimitEvent{SessionID: id, Message: limit.Message, Until: until})
	}
	if turns == 0 {
		r.Emit("turn-done", TurnDone{Session: r.Store.GetSession(id), OK: !truthy(m.Get("is_error")), Limited: limit != nil})
	}
}

type LimitEvent struct {
	SessionID string
	Message   string
	Until     *time.Time
}

type TurnDone struct {
	Session *Obj
	OK      bool
	Limited bool
}

// ask is the CLI's question about one tool call, held until a person answers.
func (r *Runtime) ask(ctx context.Context, id string, req *Obj) *Obj {
	tool := req.Str("tool_name")
	input := req.ObjAt("input")
	if input == nil {
		input = O()
	}
	session := r.Store.GetSession(id)
	isAsk := session != nil && session.Str("project_id") == ""
	var bench *Approval
	if isAsk {
		bench = AskApproval(tool, input)
	} else {
		bench = BenchApproval(tool, input, session, r.Store.GetTask)
	}
	if bench != nil && bench.Allow {
		return O("behavior", "allow", "updatedInput", input)
	}
	var title any
	if bench != nil && bench.Title != "" {
		title = bench.Title
	} else if truthy(req.Get("title")) {
		title = req.Get("title")
	}
	var reason any
	if truthy(req.Get("decision_reason")) {
		reason = req.Get("decision_reason")
	} else if truthy(req.Get("blocked_path")) {
		reason = req.Get("blocked_path")
	}
	approval := O("id", NewID(), "session_id", id, "tool", tool, "input", input, "title", title, "reason", reason, "created_at", isoNow())
	h := &heldApproval{approval: approval, resolve: make(chan *Obj, 1)}
	if !isAsk {
		h.suggestions = arr(req.Get("permission_suggestions"))
	}
	r.mu.Lock()
	r.approvals[approval.Str("id")] = h
	r.approvalList = append(r.approvalList, approval.Str("id"))
	r.mu.Unlock()
	r.update(id, O("status", "waiting"))
	r.Emit("approval", approval)
	select {
	case res := <-h.resolve:
		return res
	case <-ctx.Done():
		r.finish(approval.Str("id"), O("behavior", "deny", "message", "Cancelled"))
		return <-h.resolve
	}
}

func (r *Runtime) finish(approvalID string, result *Obj) bool {
	r.mu.Lock()
	h := r.approvals[approvalID]
	if h == nil {
		r.mu.Unlock()
		return false
	}
	delete(r.approvals, approvalID)
	for i, x := range r.approvalList {
		if x == approvalID {
			r.approvalList = append(r.approvalList[:i:i], r.approvalList[i+1:]...)
			break
		}
	}
	r.mu.Unlock()
	h.resolve <- result
	sid := h.approval.Str("session_id")
	r.Emit("approval-done", O("id", approvalID, "session_id", sid))
	if s := r.Store.GetSession(sid); s != nil && s.Str("status") == "waiting" && !r.pendingFor(sid) {
		status := "idle"
		if l := r.getLive(sid); l != nil {
			l.mu.Lock()
			if l.turns > 0 {
				status = "running"
			}
			l.mu.Unlock()
		}
		r.update(sid, O("status", status))
	}
	return true
}

func (r *Runtime) pendingFor(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, h := range r.approvals {
		if h.approval.Str("session_id") == id {
			return true
		}
	}
	return false
}

func (r *Runtime) dropApprovals(id, message string) {
	r.mu.Lock()
	var ids []string
	for _, aid := range r.approvalList {
		if h := r.approvals[aid]; h != nil && h.approval.Str("session_id") == id {
			ids = append(ids, aid)
		}
	}
	r.mu.Unlock()
	for _, aid := range ids {
		r.finish(aid, O("behavior", "deny", "message", message))
	}
}

// Pending: approvals waiting, oldest first, for one session or all.
func (r *Runtime) Pending(sessionID string) []any {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := []any{}
	for _, aid := range r.approvalList {
		h := r.approvals[aid]
		if h != nil && (sessionID == "" || h.approval.Str("session_id") == sessionID) {
			out = append(out, h.approval)
		}
	}
	return out
}

// Answer is a person's answer to an approval.
func (r *Runtime) Answer(approvalID string, a *Obj) (bool, error) {
	r.mu.Lock()
	h := r.approvals[approvalID]
	r.mu.Unlock()
	if h == nil {
		return false, fail("That request is no longer waiting", 404)
	}
	if !truthy(a.Get("allow")) {
		msg := strings.TrimSpace(a.Str("message"))
		if msg == "" {
			msg = "The user declined this."
		}
		return r.finish(approvalID, O("behavior", "deny", "message", msg)), nil
	}
	var updates []any
	input := h.approval.ObjAt("input")
	if h.approval.Str("tool") == "AskUserQuestion" {
		ans := a.Get("answers")
		if ans == nil {
			ans = O()
		}
		input = input.Clone().Set("answers", ans)
	}
	if h.approval.Str("tool") == "ExitPlanMode" {
		mode := a.Str("mode")
		if mode == "" || mode == "plan" {
			mode = "default"
		}
		updates = append(updates, O("type", "setMode", "mode", mode, "destination", "session"))
		r.update(h.approval.Str("session_id"), O("permission_mode", mode))
	}
	if truthy(a.Get("always")) {
		for _, u := range h.suggestions {
			if uo, ok := u.(*Obj); ok {
				updates = append(updates, uo.Clone().Set("destination", "session"))
			}
		}
	}
	res := O("behavior", "allow", "updatedInput", input)
	if len(updates) > 0 {
		res.Set("updatedPermissions", updates)
	}
	return r.finish(approvalID, res), nil
}

func (r *Runtime) Interrupt(id string) bool {
	l := r.getLive(id)
	if l == nil {
		return false
	}
	r.dropApprovals(id, "The user interrupted.")
	l.proc.Interrupt()
	return true
}

func (r *Runtime) SetMode(id, mode string) (*Obj, error) {
	if !validMode(mode) {
		return nil, fail("Unknown mode " + mode)
	}
	s, err := r.session(id)
	if err != nil {
		return nil, err
	}
	if s.Str("project_id") == "" && mode != "default" {
		return nil, fail("Ask Bench always asks before it acts; its mode cannot change")
	}
	if l := r.getLive(id); l != nil {
		if err := l.proc.SetPermissionMode(mode); err != nil {
			return nil, err
		}
	}
	return r.update(id, O("permission_mode", mode)), nil
}

// SetModel: providerID "" keeps it; modelID nil-able via the has flag; effort likewise.
func (r *Runtime) SetModel(id string, providerID string, modelID any, hasModel bool, effort any, hasEffort bool) (*Obj, error) {
	s, err := r.session(id)
	if err != nil {
		return nil, err
	}
	if providerID == "" {
		providerID = s.Str("provider_id")
	}
	provider := r.Store.GetProvider(providerID)
	if provider == nil {
		return nil, fail("No such provider")
	}
	var mid any
	if !hasModel {
		if providerID == s.Str("provider_id") {
			mid = s.Get("model_id")
		}
	} else {
		mid = modelID
	}
	var model *Obj
	if ms, _ := mid.(string); ms != "" {
		model = r.Store.GetModel(ms)
		if model == nil || model.Str("provider_id") != providerID {
			return nil, fail("That model is not on that provider")
		}
	}
	l := r.getLive(id)
	effortChanged := hasEffort && effort != s.Get("effort")
	if l != nil && (providerID != l.providerID || effortChanged) {
		l.mu.Lock()
		turns := l.turns
		l.mu.Unlock()
		if turns > 0 {
			return nil, fail("Wait for the turn to finish, or stop it, before changing provider or effort")
		}
		r.Close(id)
	} else if l != nil && model != nil {
		if err := l.proc.SetModel(InvocationID(model)); err != nil {
			return nil, err
		}
	}
	patch := O("provider_id", providerID, "model_id", mid)
	if ms, _ := mid.(string); ms == "" {
		patch.Set("model_id", nil)
	}
	if hasEffort {
		patch.Set("effort", effort)
	}
	return r.update(id, patch), nil
}

func (r *Runtime) beforeTool(in *Obj) *Obj {
	if id := in.Str("tool_use_id"); id != "" {
		snap := tmpSnapshot()
		r.mu.Lock()
		r.tmpBefore[id] = snap
		r.mu.Unlock()
	}
	return O()
}

func (r *Runtime) afterTool(sid string, in *Obj) *Obj {
	tid := in.Str("tool_use_id")
	r.mu.Lock()
	before, ok := r.tmpBefore[tid]
	delete(r.tmpBefore, tid)
	r.mu.Unlock()
	if !ok || tid == "" {
		return O()
	}
	s := r.Store.GetSession(sid)
	if s == nil {
		return O()
	}
	for _, p := range appeared(before, tmpSnapshot()) {
		var tool any
		if in.Str("tool_name") != "" {
			tool = in.Str("tool_name")
		}
		r.Store.AddArtifact(O("session_id", sid, "task_id", s.Get("task_id"), "project_id", s.Get("project_id"), "path", p, "tool", tool))
	}
	return O()
}

func (r *Runtime) taskBrief(s *Obj) string {
	if s.Str("task_id") == "" || s.Str("purpose") == "review" {
		return ""
	}
	t := r.Store.GetTask(s.Str("task_id"))
	if t == nil || (!truthy(t.Get("done")) && !truthy(t.Get("check_command"))) {
		return ""
	}
	parts := []string{"# This task's bar for done"}
	if truthy(t.Get("done")) {
		parts = append(parts, "Done means: "+t.Str("done"))
	}
	if truthy(t.Get("check_command")) {
		parts = append(parts, fmt.Sprintf("When you stop, Bench runs `%s` in this worktree. If it fails you are handed its output and sent back to work, so run it yourself before you stop.", t.Str("check_command")))
	}
	return strings.Join(parts, "\n\n")
}

func (r *Runtime) onStop(ctx context.Context, id string, in *Obj) *Obj {
	s := r.Store.GetSession(id)
	l := r.getLive(id)
	if s == nil || s.Str("task_id") == "" || l == nil || len(arr(in.Get("background_tasks"))) > 0 {
		return O()
	}
	l.mu.Lock()
	skip := l.writeUp || l.limit != nil
	l.mu.Unlock()
	if skip {
		return O()
	}
	res := r.Check(ctx, s.Str("task_id"), id, false)
	if res == nil || res.OK || !res.Fresh {
		return O()
	}
	t := r.Store.GetTask(s.Str("task_id"))
	l.mu.Lock()
	l.checkTries++
	tries := l.checkTries
	l.mu.Unlock()
	if tries > CheckTries {
		r.event(id, "notice", O("text", fmt.Sprintf("The check still fails after %d tries. Stopping here so you can look.", CheckTries)))
		return O()
	}
	how := "timed out"
	if !res.TimedOut {
		how = fmt.Sprintf("failed (exit %s)", str(res.Code))
		if res.Code == nil {
			how = "failed (exit null)"
		}
	}
	out := res.Output
	if out == "" {
		out = "(no output)"
	}
	reason := fmt.Sprintf("Bench ran this task's check, `%s`, and it %s. The end of its output:\n\n%s\n\n", t.Str("check_command"), how, out)
	if truthy(t.Get("done")) {
		reason += "Done means: " + t.Str("done") + "\n\n"
	}
	reason += fmt.Sprintf("Fix what it shows, run the check yourself, and stop once it passes. If it cannot pass for a reason outside this task, say why and stop. (Try %d of %d.)", tries, CheckTries)
	return O("decision", "block", "reason", reason)
}

// Check runs a task's check and records it; nil when there is nothing to run.
func (r *Runtime) Check(ctx context.Context, taskID, sessionID string, force bool) *CheckResult {
	if ctx == nil {
		ctx = context.Background()
	}
	t := r.Store.GetTask(taskID)
	if t == nil || t.Str("state") != "active" || !truthy(t.Get("check_command")) || !exists(t.Str("worktree")) {
		return nil
	}
	tree := treeOf(t.Str("worktree"))
	if !force && tree != "" && tree == t.Str("check_tree") && (t.Str("check_status") == "passed" || t.Str("check_status") == "failed") {
		if f, ok := ctx.Value(checkStarted{}).(func()); ok {
			f()
		}
		return &CheckResult{OK: t.Str("check_status") == "passed", Output: t.Str("check_output"), Fresh: false}
	}
	if ctx == nil {
		ctx = context.Background()
	}
	started := func() {
		if f, ok := ctx.Value(checkStarted{}).(func()); ok {
			f()
		}
	}
	r.mu.Lock()
	if going := r.checks[taskID]; going != nil {
		r.mu.Unlock()
		started()
		<-going.done
		return going.r
	}
	run := &checkRun{done: make(chan struct{})}
	r.checks[taskID] = run
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.checks, taskID)
		r.mu.Unlock()
		close(run.done)
	}()
	r.taskUpdate(taskID, O("check_status", "running"))
	started()
	res := RunCheck(ctx, t.Str("check_command"), t.Str("worktree"), CleanEnv(), 0)
	if ctx.Err() != nil {
		r.taskUpdate(taskID, O("check_status", nil, "check_tree", nil))
		return nil
	}
	status := "failed"
	if res.OK {
		status = "passed"
	}
	r.taskUpdate(taskID, O("check_status", status, "check_tree", nilIfEmpty(treeOf(t.Str("worktree"))), "check_output", res.Output, "check_at", isoNow()))
	if sessionID != "" {
		r.event(sessionID, "check", O("command", t.Str("check_command"), "ok", res.OK, "code", res.Code, "output", res.Output, "ms", res.Ms, "timedOut", res.TimedOut))
	}
	res.Fresh = true
	run.r = &res
	return &res
}

func nilIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (r *Runtime) taskUpdate(id string, patch *Obj) {
	r.Emit("task", r.Store.UpdateTask(id, patch))
}

// Busy: a turn in flight, or an approval waiting.
func (r *Runtime) Busy(id string) bool {
	l := r.getLive(id)
	if l == nil {
		return false
	}
	l.mu.Lock()
	t := l.turns
	l.mu.Unlock()
	return t > 0 || r.pendingFor(id)
}

// Close ends a session's process; the next message resumes it.
func (r *Runtime) Close(id string) {
	r.mu.Lock()
	l := r.live[id]
	if l == nil {
		r.mu.Unlock()
		return
	}
	delete(r.live, id)
	r.mu.Unlock()
	l.mu.Lock()
	l.closed = true
	l.mu.Unlock()
	r.dropApprovals(id, "The session was closed.")
	if l.proc != nil {
		l.proc.Close()
	}
	if s := r.Store.GetSession(id); s != nil && s.Str("status") != "error" {
		r.update(id, O("status", "idle"))
	}
}

func (r *Runtime) reap() {
	cutoff := time.Now().Add(-r.idle)
	r.mu.Lock()
	var ids []string
	for id, l := range r.live {
		l.mu.Lock()
		if l.turns == 0 && l.lastUsed.Before(cutoff) {
			ids = append(ids, id)
		}
		l.mu.Unlock()
	}
	r.mu.Unlock()
	for _, id := range ids {
		if !r.pendingFor(id) {
			r.Close(id)
		}
	}
	r.SweepScratch()
}

func (r *Runtime) Shutdown() {
	select {
	case <-r.stop:
	default:
		close(r.stop)
	}
	r.mu.Lock()
	var ids []string
	for id := range r.live {
		ids = append(ids, id)
	}
	r.mu.Unlock()
	for _, id := range ids {
		r.Close(id)
	}
}

var mentionRe = regexp.MustCompile(`(?:^|\s)@([\w./~+-][^\s]*)`)
var mentionTrail = regexp.MustCompile(`[.,;:!?)]+$`)

// MentionedFiles inlines the files a message names as `@path`.
func MentionedFiles(cwd, text string, note func(string) string) string {
	root, _ := filepath.Abs(cwd)
	seen := map[string]bool{}
	total := int64(0)
	out := ""
	var big []string
	for _, m := range mentionRe.FindAllStringSubmatch(text, -1) {
		rel := mentionTrail.ReplaceAllString(m[1], "")
		noted := ""
		if strings.HasPrefix(rel, "note:") {
			if note != nil {
				noted = note(rel[5:])
			}
			if noted == "" {
				continue
			}
		}
		full := noted
		if full == "" {
			full = filepath.Join(root, rel)
			if filepath.IsAbs(rel) {
				full = filepath.Clean(rel)
			}
		}
		if seen[full] || (noted == "" && full != root && !strings.HasPrefix(full, root+string(filepath.Separator))) {
			continue
		}
		seen[full] = true
		st, err := os.Stat(full)
		if err != nil || !st.Mode().IsRegular() {
			continue
		}
		name := rel
		if noted != "" {
			name = noted
		}
		if st.Size() > 100*1024 || total+st.Size() > 300*1024 {
			big = append(big, fmt.Sprintf("%s (%d KB)", name, int(math.Round(float64(st.Size())/1024))))
			continue
		}
		data, err := os.ReadFile(full)
		if err != nil || strings.IndexByte(string(data), 0) >= 0 {
			continue
		}
		t := string(data)
		lines := strings.Count(t, "\n") + 1
		if lines > 1500 {
			big = append(big, fmt.Sprintf("%s (%d lines)", name, lines))
			continue
		}
		total += st.Size()
		nl := "\n"
		if strings.HasSuffix(t, "\n") {
			nl = ""
		}
		out += fmt.Sprintf("\n\n<file path=\"%s\">\n%s%s</file>", name, t, nl)
	}
	if len(big) > 0 {
		var lines []string
		for _, b := range big {
			lines = append(lines, "- "+b)
		}
		out += "\n\nToo large to attach, so read only the parts you need (grep, or Read with an offset):\n" + strings.Join(lines, "\n")
	}
	return out
}
