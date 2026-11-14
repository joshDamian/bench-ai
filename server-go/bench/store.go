package bench

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// The store: the same SQLite file, schema and row shapes as src/store.mjs. Where Node
// spreads a row into an object, this returns an *Obj with the columns in table order.

var legacyTables = []string{"tasks", "sessions", "runs", "events", "automations", "run_leases", "provider_health", "jobs",
	"chat_sessions", "chat_messages", "chat_runs", "decisions", "session_runs", "permission_requests"}

const schema = `
  CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT UNIQUE NOT NULL,created_at TEXT NOT NULL,language TEXT,framework TEXT,commands TEXT NOT NULL DEFAULT '{}');
  CREATE TABLE IF NOT EXISTS providers(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,enabled INTEGER NOT NULL,config TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS models(id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,name TEXT NOT NULL,capabilities TEXT NOT NULL DEFAULT '[]',speed REAL,cost REAL,quality REAL,context_length INTEGER,input_cost_per_mtok REAL,output_cost_per_mtok REAL,cache_read_cost_per_mtok REAL,cache_write_cost_per_mtok REAL,pricing_source TEXT,pricing_updated_at TEXT,billing_mode TEXT,enabled INTEGER DEFAULT 1,provider_model_id TEXT,invocation_model_id TEXT,display_name TEXT);
  CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS push_subscriptions(endpoint TEXT PRIMARY KEY,p256dh TEXT NOT NULL,auth TEXT NOT NULL,created_at TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT,
    base_branch TEXT NOT NULL,base_commit TEXT NOT NULL,branch TEXT NOT NULL,worktree TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'active',merge_commit TEXT,merged_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,project_id TEXT,task_id TEXT,title TEXT NOT NULL,cwd TEXT NOT NULL,
    provider_id TEXT NOT NULL,model_id TEXT,permission_mode TEXT NOT NULL DEFAULT 'default',effort TEXT,
    claude_session_id TEXT,status TEXT NOT NULL DEFAULT 'idle',error TEXT,
    cost REAL NOT NULL DEFAULT 0,input_tokens INTEGER NOT NULL DEFAULT 0,output_tokens INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,updated_at TEXT NOT NULL,archived_at TEXT);
  CREATE TABLE IF NOT EXISTS session_events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,kind TEXT NOT NULL,data TEXT NOT NULL,created_at TEXT NOT NULL);
  CREATE INDEX IF NOT EXISTS session_events_by_session ON session_events(session_id,id);
  CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,created_at TEXT NOT NULL,last_seen_at TEXT,revoked_at TEXT);
  CREATE TABLE IF NOT EXISTS pairings(code_hash TEXT PRIMARY KEY,expires_at TEXT NOT NULL,used_at TEXT);
  CREATE TABLE IF NOT EXISTS task_log(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,session_id TEXT,decision TEXT NOT NULL,evidence TEXT NOT NULL,created_at TEXT NOT NULL);
  CREATE INDEX IF NOT EXISTS task_log_by_task ON task_log(task_id,id);
  CREATE TABLE IF NOT EXISTS artifacts(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,task_id TEXT,project_id TEXT,path TEXT NOT NULL,tool TEXT,
    created_at TEXT NOT NULL,removed_at TEXT,removed_by TEXT,UNIQUE(session_id,path));
  CREATE INDEX IF NOT EXISTS artifacts_by_path ON artifacts(path);
`

//go:embed providers.json
var providersJSON []byte

// BuiltinProviders is the catalog src/providers.mjs ships, exported from it as JSON.
var BuiltinProviders = func() []any { return ParseJSON(string(providersJSON)).([]any) }()

func Hash(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// isoNow is new Date().toISOString().
func isoNow() string { return isoTime(time.Now()) }

func isoTime(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }

func parseISO(s string) (time.Time, bool) {
	for _, f := range []string{"2006-01-02T15:04:05.000Z", time.RFC3339Nano, time.RFC3339} {
		if t, err := time.Parse(f, s); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

func nowMs() float64 { return float64(time.Now().UnixMilli()) }

const projectSelect = "SELECT id,name,path,created_at,json_extract(commands,'$.check') AS check_command,routing FROM projects"

// stubThinking puts a stub in place of each thinking block's text, as a listing does.
func stubThinking(data any) any {
	o, ok := data.(*Obj)
	if !ok {
		return data
	}
	content, ok := o.Get("content").([]any)
	if !ok {
		return data
	}
	stubbed := false
	out := make([]any, len(content))
	for i, b := range content {
		bo, ok := b.(*Obj)
		if ok && bo.Str("type") == "thinking" {
			if t, ok := bo.Get("thinking").(string); ok {
				stubbed = true
				out[i] = O("type", "thinking", "length", float64(jsLen(t)), "truncated", true)
				continue
			}
		}
		out[i] = b
	}
	if !stubbed {
		return data
	}
	return o.Clone().Set("content", out)
}

// jsLen is a string's .length: UTF-16 code units.
func jsLen(s string) int {
	n := 0
	for _, r := range s {
		if r >= 0x10000 {
			n += 2
		} else {
			n++
		}
	}
	return n
}

type Store struct {
	Root  string
	Dir   string
	File  string
	DB    *sql.DB
	stmts sync.Map // SQL text -> *sql.Stmt, prepared once per connection by database/sql
}

// stmt is the prepared statement for q, made the first time q is used.
func (s *Store) stmt(q string) (*sql.Stmt, error) {
	if st, ok := s.stmts.Load(q); ok {
		return st.(*sql.Stmt), nil
	}
	st, err := s.DB.Prepare(q)
	if err != nil {
		return nil, err
	}
	if prev, loaded := s.stmts.LoadOrStore(q, st); loaded {
		st.Close()
		return prev.(*sql.Stmt), nil
	}
	return st, nil
}

func NewStore(root string) (*Store, error) {
	s := &Store{Root: root, Dir: filepath.Join(root, ".bench")}
	s.File = filepath.Join(s.Dir, "bench.db")
	adoptOldName(root)
	if err := os.MkdirAll(s.Dir, 0o755); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite3", "file:"+s.File+"?_busy_timeout=5000&_journal_mode=WAL&_txlock=immediate")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(8)
	db.SetConnMaxIdleTime(5 * time.Minute)
	s.DB = db
	if err := s.migrate(); err != nil {
		return nil, err
	}
	if _, err := db.Exec(schema); err != nil {
		return nil, err
	}
	for _, col := range []string{"provider_model_id", "invocation_model_id", "display_name"} {
		s.addColumn("models", col, "TEXT")
	}
	s.addColumn("push_subscriptions", "choices", "TEXT")
	for _, col := range []string{"done", "check_command", "check_status", "check_tree", "check_output", "check_at", "created_by_session", "loop"} {
		s.addColumn("tasks", col, "TEXT")
	}
	for _, col := range []string{"started_by_session", "purpose", "pause", "model_usage"} {
		s.addColumn("sessions", col, "TEXT")
	}
	s.addColumn("sessions", "auto_resume", "INTEGER")
	s.addColumn("projects", "routing", "TEXT")
	s.addColumn("tasks", "routing", "TEXT")
	if err := s.projectlessSessions(); err != nil {
		return nil, err
	}
	s.addColumn("task_log", "kind", "TEXT NOT NULL DEFAULT 'decision'")
	s.seedProviders()
	return s, nil
}

func (s *Store) Close() { s.DB.Close() }

func (s *Store) columns(table string) map[string]bool {
	out := map[string]bool{}
	for _, r := range s.all("PRAGMA table_info(" + table + ")") {
		out[r.Str("name")] = true
	}
	return out
}

func (s *Store) addColumn(table, col, typ string) {
	if !s.columns(table)[col] {
		s.DB.Exec(fmt.Sprintf("ALTER TABLE %s ADD COLUMN %s %s", table, col, typ))
	}
}

func adoptOldName(root string) {
	old := filepath.Join(root, ".ai-code")
	dir := filepath.Join(root, ".bench")
	if exists(dir) || !exists(filepath.Join(old, "ai-code.db")) {
		return
	}
	os.Rename(old, dir)
	for _, suffix := range []string{"", "-wal", "-shm"} {
		from := filepath.Join(dir, "ai-code.db"+suffix)
		if exists(from) {
			os.Rename(from, filepath.Join(dir, "bench.db"+suffix))
		}
	}
}

func (s *Store) migrate() error {
	cols := s.columns("tasks")
	if len(cols) == 0 || cols["base_branch"] {
		return nil
	}
	backup := filepath.Join(s.Dir, fmt.Sprintf("bench.db.pre-v2-%d", time.Now().UnixMilli()))
	if _, err := s.DB.Exec("VACUUM INTO '" + strings.ReplaceAll(backup, "'", "''") + "'"); err != nil {
		return err
	}
	have := map[string]bool{}
	for _, r := range s.all("SELECT name FROM sqlite_master WHERE type='table'") {
		have[r.Str("name")] = true
	}
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	for _, t := range legacyTables {
		if !have[t] || have["legacy_"+t] {
			continue
		}
		if _, err := tx.Exec(fmt.Sprintf("ALTER TABLE %s RENAME TO legacy_%s", t, t)); err != nil {
			tx.Rollback()
			return err
		}
	}
	rows, err := tx.Query("SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL")
	if err != nil {
		tx.Rollback()
		return err
	}
	var names []string
	for rows.Next() {
		var n string
		rows.Scan(&n)
		names = append(names, n)
	}
	rows.Close()
	for _, n := range names {
		if strings.HasPrefix(n, "legacy_") {
			continue
		}
		tx.Exec(`DROP INDEX IF EXISTS "` + n + `"`)
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	s.DB.Exec("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
	s.SetSetting("legacy_import", "pending")
	s.SetSetting("legacy_backup", backup)
	return nil
}

var sessionsHead = regexp.MustCompile(`(?i)^CREATE TABLE "?sessions"?`)
var projectNotNull = regexp.MustCompile(`(?i)project_id TEXT NOT NULL`)

func (s *Store) projectlessSessions() error {
	var notnull bool
	for _, c := range s.all("PRAGMA table_info(sessions)") {
		if c.Str("name") == "project_id" {
			notnull = c.Num("notnull") != 0
		}
	}
	if !notnull {
		return nil
	}
	r := s.one("SELECT sql FROM sqlite_master WHERE type='table' AND name='sessions'")
	rebuilt := projectNotNull.ReplaceAllString(sessionsHead.ReplaceAllString(r.Str("sql"), "CREATE TABLE sessions_rebuilt"), "project_id TEXT")
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	for _, q := range []string{rebuilt, "INSERT INTO sessions_rebuilt SELECT * FROM sessions", "DROP TABLE sessions", "ALTER TABLE sessions_rebuilt RENAME TO sessions"} {
		if _, err := tx.Exec(q); err != nil {
			tx.Rollback()
			return err
		}
	}
	return tx.Commit()
}

func (s *Store) seedProviders() {
	for _, pv := range BuiltinProviders {
		p := pv.(*Obj)
		if s.GetProvider(p.Str("id")) == nil {
			s.AddProvider(p)
		}
		have := map[string]bool{}
		for _, m := range s.ListModels(p.Str("id")) {
			have[m.Str("id")] = true
		}
		for _, mv := range arr(p.Get("models")) {
			m := mv.(*Obj)
			if !have[m.Str("id")] {
				s.AddModel(m.Clone().Set("providerId", p.Str("id")))
			}
		}
	}
}

// NewID is crypto.randomUUID().
func NewID() string {
	var b [16]byte
	rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b[:])
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// --- queries ----------------------------------------------------------------------

func scanRows(rows *sql.Rows) []*Obj {
	defer rows.Close()
	cols, _ := rows.Columns()
	var out []*Obj
	for rows.Next() {
		vals := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			continue
		}
		o := &Obj{keys: make([]string, 0, len(cols)), vals: make(map[string]any, len(cols))}
		for i, c := range cols {
			switch v := vals[i].(type) {
			case []byte:
				o.Set(c, string(v))
			case int64:
				o.Set(c, float64(v))
			case time.Time:
				o.Set(c, isoTime(v))
			default:
				o.Set(c, v)
			}
		}
		out = append(out, o)
	}
	return out
}

func (s *Store) all(q string, args ...any) []*Obj {
	if strings.HasPrefix(q, "PRAGMA") || strings.HasPrefix(q, "SELECT name FROM sqlite_master") {
		rows, err := s.DB.Query(q, args...)
		if err != nil {
			return nil
		}
		return scanRows(rows)
	}
	st, err := s.stmt(q)
	if err != nil {
		return nil
	}
	rows, err := st.Query(args...)
	if err != nil {
		return nil
	}
	return scanRows(rows)
}

func (s *Store) query(q string, args ...any) (*sql.Rows, error) {
	st, err := s.stmt(q)
	if err != nil {
		return nil, err
	}
	return st.Query(args...)
}

func (s *Store) one(q string, args ...any) *Obj {
	r := s.all(q, args...)
	if len(r) == 0 {
		return nil
	}
	return r[0]
}

func (s *Store) exec(q string, args ...any) sql.Result {
	var r sql.Result
	st, err := s.stmt(q)
	if err == nil {
		r, err = st.Exec(args...)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "store:", err, q)
	}
	return r
}

func changes(r sql.Result) int64 {
	if r == nil {
		return 0
	}
	n, _ := r.RowsAffected()
	return n
}

// patch writes the named columns of a row, in the order given.
func (s *Store) patch(table, id string, p *Obj) {
	if p.Len() == 0 {
		return
	}
	var sets []string
	var args []any
	for _, k := range p.Keys() {
		sets = append(sets, k+"=?")
		args = append(args, sqlVal(p.Get(k)))
	}
	args = append(args, id)
	s.exec(fmt.Sprintf("UPDATE %s SET %s WHERE id=?", table, strings.Join(sets, ",")), args...)
}

// sqlVal is a value as node:sqlite would bind it.
func sqlVal(v any) any {
	switch x := v.(type) {
	case bool:
		if x {
			return 1
		}
		return 0
	case *Obj, []any:
		return string(ToJSON(x))
	case undefined:
		return nil
	}
	return v
}

func jsonCol(v any) any {
	s, ok := v.(string)
	if !ok || s == "" {
		return nil
	}
	return ParseJSON(s)
}

// --- settings -----------------------------------------------------------------------

func (s *Store) GetSetting(key string) (string, bool) {
	r := s.one("SELECT value FROM settings WHERE key=?", key)
	if r == nil {
		return "", false
	}
	return r.Str("value"), true
}

func (s *Store) Setting(key string) string {
	v, _ := s.GetSetting(key)
	return v
}

func (s *Store) SetSetting(key, value string) string {
	s.exec("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
	return value
}

// --- projects ------------------------------------------------------------------------

func (s *Store) project(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	return r.Set("routing", jsonCol(r.Get("routing")))
}

func (s *Store) ListProjects() []*Obj {
	rows := s.all(projectSelect + " ORDER BY name COLLATE NOCASE")
	for _, r := range rows {
		s.project(r)
	}
	return rows
}

func (s *Store) GetProject(id string) *Obj {
	return s.project(s.one(projectSelect+" WHERE id=?", id))
}

func (s *Store) ProjectByPath(p string) *Obj {
	return s.project(s.one(projectSelect+" WHERE path=?", p))
}

func (s *Store) AddProject(name, dir string) *Obj {
	id := NewID()
	s.exec("INSERT INTO projects(id,name,path,created_at,commands) VALUES(?,?,?,?,'{}')", id, name, dir, isoNow())
	return s.GetProject(id)
}

// UpdateProject: name, check_command ("" clears it) and routing (nil follows the default).
func (s *Store) UpdateProject(id string, p *Obj) *Obj {
	set := O()
	if n := p.Str("name"); n != "" {
		set.Set("name", n)
	}
	if p.Has("routing") {
		if _, u := p.Get("routing").(undefined); !u {
			if truthy(p.Get("routing")) {
				set.Set("routing", string(ToJSON(p.Get("routing"))))
			} else {
				set.Set("routing", nil)
			}
		}
	}
	s.patch("projects", id, set)
	if p.Has("check_command") {
		if _, u := p.Get("check_command").(undefined); !u {
			check := strings.TrimSpace(str(p.Get("check_command")))
			if !truthy(p.Get("check_command")) {
				check = ""
			}
			if check != "" {
				s.exec("UPDATE projects SET commands=json_set(commands,'$.check',?) WHERE id=?", check, id)
			} else {
				s.exec("UPDATE projects SET commands=json_remove(commands,'$.check') WHERE id=?", id)
			}
		}
	}
	return s.GetProject(id)
}

func (s *Store) DeleteProject(id string) { s.exec("DELETE FROM projects WHERE id=?", id) }

// --- providers and models -------------------------------------------------------------

func (s *Store) AddProvider(p *Obj) *Obj {
	cfg := p.Get("config")
	if cfg == nil {
		cfg = O()
	}
	s.exec("INSERT OR REPLACE INTO providers(id,name,kind,enabled,config) VALUES(?,?,?,?,?)", p.Str("id"), p.Str("name"), p.Str("kind"), sqlVal(p.Bool("enabled")), string(ToJSON(cfg)))
	return s.GetProvider(p.Str("id"))
}

func (s *Store) provider(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	r.Set("enabled", truthy(r.Get("enabled")))
	cfg := jsonCol(r.Get("config"))
	if cfg == nil {
		cfg = O()
	}
	return r.Set("config", cfg)
}

func (s *Store) GetProvider(id string) *Obj {
	return s.provider(s.one("SELECT * FROM providers WHERE id=?", id))
}

func (s *Store) ListProviders() []*Obj {
	rows := s.all("SELECT * FROM providers WHERE kind!='mock' ORDER BY name")
	for _, r := range rows {
		s.provider(r)
	}
	return rows
}

func (s *Store) UpdateProvider(id string, patch *Obj) (*Obj, error) {
	p := s.GetProvider(id)
	if p == nil {
		return nil, fail("Provider not found")
	}
	n := p.Merge(patch)
	cfg := p.ObjAt("config")
	if cfg == nil {
		cfg = O()
	}
	if pc := patch.ObjAt("config"); pc != nil {
		cfg = cfg.Merge(pc)
	}
	s.exec("UPDATE providers SET name=?,enabled=?,config=? WHERE id=?", str(n.Get("name")), sqlVal(truthy(n.Get("enabled"))), string(ToJSON(cfg)), id)
	return s.GetProvider(id), nil
}

func or(v any, d any) any {
	if v == nil {
		return d
	}
	if _, u := v.(undefined); u {
		return d
	}
	return v
}

func (s *Store) AddModel(m *Obj) {
	enabled := 1
	if b, ok := m.Get("enabled").(bool); ok && !b {
		enabled = 0
	}
	s.exec(`INSERT OR REPLACE INTO models(id,provider_id,name,capabilities,display_name,provider_model_id,invocation_model_id,context_length,
           input_cost_per_mtok,output_cost_per_mtok,cache_read_cost_per_mtok,cache_write_cost_per_mtok,pricing_source,pricing_updated_at,billing_mode,enabled)
         VALUES(?,?,?,'[]',?,?,?,?,?,?,?,?,?,?,?,?)`,
		m.Str("id"), m.Str("providerId"), m.Str("name"), or(m.Get("displayName"), m.Get("name")), or(m.Get("providerModelId"), m.Get("name")), or(m.Get("invocationModelId"), m.Get("name")), m.Get("contextLength"),
		m.Get("inputCostPerMTok"), m.Get("outputCostPerMTok"), m.Get("cacheReadCostPerMTok"), m.Get("cacheWriteCostPerMTok"),
		m.Get("pricingSource"), m.Get("pricingUpdatedAt"), m.Get("billingMode"), enabled)
}

func (s *Store) model(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	return r.Set("enabled", truthy(r.Get("enabled")))
}

func (s *Store) GetModel(id string) *Obj { return s.model(s.one("SELECT * FROM models WHERE id=?", id)) }

func (s *Store) ListModels(providerID string) []*Obj {
	var rows []*Obj
	if providerID != "" {
		rows = s.all("SELECT * FROM models WHERE provider_id=? ORDER BY name", providerID)
	} else {
		rows = s.all("SELECT * FROM models ORDER BY provider_id,name")
	}
	for _, r := range rows {
		s.model(r)
	}
	return rows
}

func (s *Store) UpdateModel(id string, patch *Obj) *Obj {
	if b, ok := patch.Get("enabled").(bool); ok || patch.Has("enabled") {
		_ = b
		if _, u := patch.Get("enabled").(undefined); !u {
			s.exec("UPDATE models SET enabled=? WHERE id=?", sqlVal(truthy(patch.Get("enabled"))), id)
		}
	}
	return s.GetModel(id)
}

// --- tasks -----------------------------------------------------------------------------

func (s *Store) task(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	r.Set("loop", jsonCol(r.Get("loop")))
	return r.Set("routing", jsonCol(r.Get("routing")))
}

func (s *Store) AddTask(t *Obj) *Obj {
	at := t.Str("created_at")
	if at == "" {
		at = isoNow()
	}
	var loop any
	if truthy(t.Get("loop")) {
		loop = string(ToJSON(t.Get("loop")))
	}
	s.exec("INSERT INTO tasks(id,project_id,title,description,base_branch,base_commit,branch,worktree,state,done,check_command,created_by_session,loop,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
		t.Str("id"), t.Str("project_id"), t.Str("title"), t.Get("description"), t.Str("base_branch"), t.Str("base_commit"), t.Str("branch"), t.Str("worktree"), "active", t.Get("done"), t.Get("check_command"), t.Get("created_by_session"), loop, at, isoNow())
	return s.GetTask(t.Str("id"))
}

func (s *Store) GetTask(id string) *Obj { return s.task(s.one("SELECT * FROM tasks WHERE id=?", id)) }

func (s *Store) ListTasks(projectID string) []*Obj {
	var rows []*Obj
	if projectID != "" {
		rows = s.all("SELECT * FROM tasks WHERE project_id=? ORDER BY updated_at DESC", projectID)
	} else {
		rows = s.all("SELECT * FROM tasks ORDER BY updated_at DESC")
	}
	for _, r := range rows {
		s.task(r)
	}
	return rows
}

// UpdateTask writes a patch; `loop` and `routing` are JSON, nil clears them.
func (s *Store) UpdateTask(id string, patch *Obj) *Obj {
	row := O()
	for _, k := range patch.Keys() {
		v := patch.Get(k)
		if _, u := v.(undefined); u {
			continue
		}
		switch k {
		case "loop":
			if v == nil {
				row.Set(k, nil)
			} else {
				row.Set(k, string(ToJSON(v)))
			}
		case "routing":
			if truthy(v) {
				row.Set(k, string(ToJSON(v)))
			} else {
				row.Set(k, nil)
			}
		default:
			row.Set(k, v)
		}
	}
	row.Set("updated_at", isoNow())
	s.patch("tasks", id, row)
	return s.GetTask(id)
}

func (s *Store) AddLog(e *Obj) *Obj {
	at := isoNow()
	kind := e.Str("kind")
	if kind == "" {
		kind = "decision"
	}
	r := s.exec("INSERT INTO task_log(task_id,session_id,decision,evidence,kind,created_at) VALUES(?,?,?,?,?,?)", e.Str("task_id"), e.Get("session_id"), e.Str("decision"), e.Str("evidence"), kind, at)
	var id int64
	if r != nil {
		id, _ = r.LastInsertId()
	}
	out := O("id", float64(id))
	for _, k := range e.Keys() {
		out.Set(k, e.Get(k))
	}
	return out.Set("kind", kind).Set("created_at", at)
}

func (s *Store) ListLog(taskID string, limit int) []*Obj {
	if limit <= 0 {
		limit = 500
	}
	return nonNil(s.all("SELECT * FROM task_log WHERE task_id=? ORDER BY id DESC LIMIT ?", taskID, limit))
}

// --- sessions -------------------------------------------------------------------------

func (s *Store) AddSession(x *Obj) *Obj {
	id := x.Str("id")
	if id == "" {
		id = NewID()
	}
	at := isoNow()
	s.exec("INSERT INTO sessions(id,project_id,task_id,title,cwd,provider_id,model_id,permission_mode,effort,started_by_session,purpose,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
		id, x.Get("project_id"), x.Get("task_id"), x.Str("title"), x.Str("cwd"), x.Str("provider_id"), x.Get("model_id"), x.Str("permission_mode"), x.Get("effort"), x.Get("started_by_session"), x.Get("purpose"), at, at)
	return s.GetSession(id)
}

func (s *Store) session(r *Obj) *Obj {
	if r == nil {
		return nil
	}
	r.Set("pause", jsonCol(r.Get("pause")))
	r.Set("model_usage", jsonCol(r.Get("model_usage")))
	if r.Get("auto_resume") == nil {
		r.Set("auto_resume", nil)
	} else {
		r.Set("auto_resume", truthy(r.Get("auto_resume")))
	}
	return r
}

func (s *Store) GetSession(id string) *Obj { return s.session(s.one("SELECT * FROM sessions WHERE id=?", id)) }

type SessionQuery struct {
	ProjectID string
	TaskID    string
	Archived  bool
	Ask       bool
}

func (s *Store) ListSessions(q SessionQuery) []*Obj {
	var where []string
	var args []any
	if q.ProjectID != "" {
		where = append(where, "project_id=?")
		args = append(args, q.ProjectID)
	}
	if q.Ask {
		where = append(where, "project_id IS NULL")
	}
	if q.TaskID != "" {
		where = append(where, "task_id=?")
		args = append(args, q.TaskID)
	}
	if !q.Archived {
		where = append(where, "archived_at IS NULL")
	}
	w := ""
	if len(where) > 0 {
		w = "WHERE " + strings.Join(where, " AND ")
	}
	rows := s.all("SELECT * FROM sessions "+w+" ORDER BY updated_at DESC", args...)
	for _, r := range rows {
		s.session(r)
	}
	return nonNil(rows)
}

func (s *Store) UpdateSession(id string, patch *Obj) *Obj {
	row := O()
	for _, k := range patch.Keys() {
		v := patch.Get(k)
		if _, u := v.(undefined); u {
			continue
		}
		switch k {
		case "pause", "model_usage":
			if v == nil {
				row.Set(k, nil)
			} else {
				row.Set(k, string(ToJSON(v)))
			}
		case "auto_resume":
			if v == nil {
				row.Set(k, nil)
			} else {
				row.Set(k, sqlVal(truthy(v)))
			}
		default:
			row.Set(k, v)
		}
	}
	row.Set("updated_at", isoNow())
	s.patch("sessions", id, row)
	return s.GetSession(id)
}

func (s *Store) PausedSessions() []*Obj {
	rows := s.all("SELECT * FROM sessions WHERE pause IS NOT NULL ORDER BY json_extract(pause,'$.until')")
	for _, r := range rows {
		s.session(r)
	}
	return rows
}

func (s *Store) ResetLiveSessions() {
	s.exec("UPDATE sessions SET status='idle' WHERE status IN ('running','waiting')")
}

func (s *Store) AddEvent(sessionID, kind string, data any) *Obj {
	at := isoNow()
	r := s.exec("INSERT INTO session_events(session_id,kind,data,created_at) VALUES(?,?,?,?)", sessionID, kind, string(ToJSON(data)), at)
	var id int64
	if r != nil {
		id, _ = r.LastInsertId()
	}
	return O("id", float64(id), "session_id", sessionID, "kind", kind, "data", data, "created_at", at)
}

type EventQuery struct {
	Before, After, Limit int
}

func (s *Store) ListEvents(sessionID string, q EventQuery) []*Obj {
	limit := q.Limit
	if limit <= 0 {
		limit = 400
	}
	if limit > 2000 {
		limit = 2000
	}
	var rows []*Obj
	if q.After > 0 {
		rows = s.all("SELECT * FROM session_events WHERE session_id=? AND id>? ORDER BY id LIMIT ?", sessionID, q.After, limit)
	} else {
		if q.Before > 0 {
			rows = s.all("SELECT * FROM session_events WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?", sessionID, q.Before, limit)
		} else {
			rows = s.all("SELECT * FROM session_events WHERE session_id=? ORDER BY id DESC LIMIT ?", sessionID, limit)
		}
		for i, j := 0, len(rows)-1; i < j; i, j = i+1, j-1 {
			rows[i], rows[j] = rows[j], rows[i]
		}
	}
	for _, r := range rows {
		r.Set("data", stubThinking(ParseJSON(r.Str("data"))))
	}
	return nonNil(rows)
}

// ListEventsWire is ListEvents for an answer on the wire: an event with no thinking block
// is passed through as the JSON it was stored as, without being parsed and written again.
func (s *Store) ListEventsWire(sessionID string, q EventQuery) []*Obj {
	limit := q.Limit
	if limit <= 0 {
		limit = 400
	}
	if limit > 2000 {
		limit = 2000
	}
	var rows *sql.Rows
	var err error
	reverse := false
	if q.After > 0 {
		rows, err = s.query("SELECT id,session_id,kind,data,created_at FROM session_events WHERE session_id=? AND id>? ORDER BY id LIMIT ?", sessionID, q.After, limit)
	} else if q.Before > 0 {
		rows, err = s.query("SELECT id,session_id,kind,data,created_at FROM session_events WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?", sessionID, q.Before, limit)
		reverse = true
	} else {
		rows, err = s.query("SELECT id,session_id,kind,data,created_at FROM session_events WHERE session_id=? ORDER BY id DESC LIMIT ?", sessionID, limit)
		reverse = true
	}
	if err != nil {
		return []*Obj{}
	}
	defer rows.Close()
	out := []*Obj{}
	for rows.Next() {
		var id int64
		var sid, kind, data, at string
		if rows.Scan(&id, &sid, &kind, &data, &at) != nil {
			continue
		}
		var d any = json.RawMessage(data)
		if strings.Contains(data, `"thinking"`) {
			d = stubThinking(ParseJSON(data))
		}
		out = append(out, &Obj{keys: []string{"id", "session_id", "kind", "data", "created_at"}, vals: map[string]any{"id": float64(id), "session_id": sid, "kind": kind, "data": d, "created_at": at}})
	}
	if reverse {
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	}
	return out
}

func (s *Store) EventByID(sessionID string, id int) *Obj {
	r := s.one("SELECT * FROM session_events WHERE session_id=? AND id=?", sessionID, id)
	if r == nil {
		return nil
	}
	return r.Set("data", ParseJSON(r.Str("data")))
}

func (s *Store) DeleteSessionData(sessionID string) {
	s.exec("DELETE FROM session_events WHERE session_id=?", sessionID)
	s.exec("DELETE FROM sessions WHERE id=?", sessionID)
}

// --- artifacts ---------------------------------------------------------------------------

func (s *Store) AddArtifact(a *Obj) {
	if s.one("SELECT id FROM artifacts WHERE session_id=? AND path=? AND removed_at IS NULL", a.Str("session_id"), a.Str("path")) != nil {
		return
	}
	s.exec("DELETE FROM artifacts WHERE session_id=? AND path=? AND removed_at IS NOT NULL", a.Str("session_id"), a.Str("path"))
	s.exec("INSERT INTO artifacts(session_id,task_id,project_id,path,tool,created_at) VALUES(?,?,?,?,?,?)", a.Str("session_id"), a.Get("task_id"), a.Get("project_id"), a.Str("path"), a.Get("tool"), isoNow())
}

type ArtifactQuery struct {
	SessionID, TaskID, ProjectID string
	Removed, Ask                 bool
}

func (s *Store) ListArtifacts(q ArtifactQuery) []*Obj {
	var where []string
	var args []any
	if q.SessionID != "" {
		where = append(where, "session_id=?")
		args = append(args, q.SessionID)
	}
	if q.TaskID != "" {
		where = append(where, "task_id=?")
		args = append(args, q.TaskID)
	}
	if q.ProjectID != "" {
		where = append(where, "project_id=?")
		args = append(args, q.ProjectID)
	}
	if q.Ask {
		where = append(where, "project_id IS NULL")
	}
	if !q.Removed {
		where = append(where, "removed_at IS NULL")
	}
	w := ""
	if len(where) > 0 {
		w = "WHERE " + strings.Join(where, " AND ")
	}
	return nonNil(s.all("SELECT * FROM artifacts "+w+" ORDER BY id", args...))
}

func (s *Store) MarkArtifactRemoved(id float64, by string) {
	s.exec("UPDATE artifacts SET removed_at=?, removed_by=? WHERE id=?", isoNow(), by, id)
}

// --- usage ------------------------------------------------------------------------------

func (s *Store) Usage() []*Obj {
	return nonNil(s.all(`SELECT s.project_id, s.task_id, s.provider_id, SUM(s.cost) cost, SUM(s.input_tokens) input_tokens, SUM(s.output_tokens) output_tokens, COUNT(*) sessions
       FROM sessions s GROUP BY s.project_id, s.task_id, s.provider_id`))
}

func (s *Store) UsageByModel(projectID, taskID string) []*Obj {
	where := []string{"model_usage IS NOT NULL"}
	var args []any
	if projectID != "" {
		where = append(where, "project_id=?")
		args = append(args, projectID)
	}
	if taskID != "" {
		where = append(where, "task_id=?")
		args = append(args, taskID)
	}
	by := map[string]*Obj{}
	var order []string
	for _, r := range s.all("SELECT model_usage FROM sessions WHERE "+strings.Join(where, " AND "), args...) {
		mu, _ := ParseJSON(r.Str("model_usage")).(*Obj)
		for _, model := range mu.Keys() {
			u, _ := mu.Get(model).(*Obj)
			t := by[model]
			if t == nil {
				t = O("model", model, "cost", 0.0, "input_tokens", 0.0, "output_tokens", 0.0, "sessions", 0.0)
				by[model] = t
				order = append(order, model)
			}
			t.Set("cost", t.Num("cost")+u.Num("cost"))
			t.Set("input_tokens", t.Num("input_tokens")+u.Num("input_tokens"))
			t.Set("output_tokens", t.Num("output_tokens")+u.Num("output_tokens"))
			t.Set("sessions", t.Num("sessions")+1)
		}
	}
	out := make([]*Obj, 0, len(order))
	for _, k := range order {
		out = append(out, by[k])
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Num("cost") > out[j].Num("cost") })
	return out
}

// --- devices and pairing ---------------------------------------------------------------

func (s *Store) AddPairing(code string, ttl time.Duration) {
	s.exec("INSERT INTO pairings(code_hash,expires_at) VALUES(?,?)", Hash(code), isoTime(time.Now().Add(ttl)))
}

func (s *Store) UsePairing(code string) bool {
	r := s.exec("UPDATE pairings SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>?", isoNow(), Hash(code), isoNow())
	s.exec("DELETE FROM pairings WHERE expires_at<?", isoTime(time.Now().Add(-24*time.Hour)))
	return changes(r) == 1
}

func (s *Store) AddDevice(name, token string) *Obj {
	id := NewID()
	s.exec("INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)", id, name, Hash(token), isoNow())
	return s.one("SELECT id,name,created_at,last_seen_at FROM devices WHERE id=?", id)
}

func (s *Store) DeviceByToken(token string) *Obj {
	d := s.one("SELECT id,name,last_seen_at FROM devices WHERE token_hash=? AND revoked_at IS NULL", Hash(token))
	if d == nil {
		return nil
	}
	last, ok := parseISO(d.Str("last_seen_at"))
	if !ok || time.Since(last) > time.Minute {
		s.exec("UPDATE devices SET last_seen_at=? WHERE id=?", isoNow(), d.Str("id"))
	}
	return d
}

func (s *Store) ListDevices() []*Obj {
	return nonNil(s.all("SELECT id,name,created_at,last_seen_at FROM devices WHERE revoked_at IS NULL ORDER BY created_at"))
}

func (s *Store) RevokeDevice(id string) bool {
	return changes(s.exec("UPDATE devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL", isoNow(), id)) == 1
}

// --- push ---------------------------------------------------------------------------

func (s *Store) AddPushSubscription(sub *Obj) {
	keys := sub.ObjAt("keys")
	var choices any
	if sub.Get("choices") != nil {
		choices = string(ToJSON(sub.Get("choices")))
	}
	s.exec("INSERT INTO push_subscriptions(endpoint,p256dh,auth,created_at,choices) VALUES(?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET p256dh=excluded.p256dh,auth=excluded.auth",
		sub.Str("endpoint"), keys.Str("p256dh"), keys.Str("auth"), isoNow(), choices)
}

func (s *Store) SetPushChoices(endpoint string, choices any) int64 {
	var c any
	if choices != nil {
		c = string(ToJSON(choices))
	}
	return changes(s.exec("UPDATE push_subscriptions SET choices=? WHERE endpoint=?", c, endpoint))
}

func (s *Store) DeletePushSubscription(endpoint string) int64 {
	return changes(s.exec("DELETE FROM push_subscriptions WHERE endpoint=?", endpoint))
}

func (s *Store) ListPushSubscriptions() []*Obj {
	return nonNil(s.all("SELECT * FROM push_subscriptions ORDER BY created_at"))
}

// --- helpers ------------------------------------------------------------------------

func nonNil(r []*Obj) []*Obj {
	if r == nil {
		return []*Obj{}
	}
	return r
}

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
