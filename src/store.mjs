// @ts-check
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { BUILTIN_PROVIDERS } from './providers.mjs';

/** @typedef {import('./contract.mjs').Project} Project */
/** @typedef {import('./contract.mjs').Provider} Provider */
/** @typedef {import('./contract.mjs').Model} Model */
/** @typedef {import('./contract.mjs').Task} Task */
/** @typedef {import('./contract.mjs').Session} Session */
/** @typedef {import('./contract.mjs').SessionEvent} SessionEvent */
/** @typedef {import('./contract.mjs').TaskLogEntry} TaskLogEntry */

// The tables the pipeline harness wrote. A v1 database keeps them, renamed with a
// `legacy_` prefix, so nothing anybody recorded is lost by the upgrade and nothing in
// the new schema can mistake one for its own.
const LEGACY_TABLES = [
  'tasks', 'sessions', 'runs', 'events', 'automations', 'run_leases', 'provider_health', 'jobs',
  'chat_sessions', 'chat_messages', 'chat_runs', 'decisions', 'session_runs', 'permission_requests',
];

const SCHEMA = `
  CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,path TEXT UNIQUE NOT NULL,created_at TEXT NOT NULL,language TEXT,framework TEXT,commands TEXT NOT NULL DEFAULT '{}');
  CREATE TABLE IF NOT EXISTS providers(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,enabled INTEGER NOT NULL,config TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS models(id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,name TEXT NOT NULL,capabilities TEXT NOT NULL DEFAULT '[]',speed REAL,cost REAL,quality REAL,context_length INTEGER,input_cost_per_mtok REAL,output_cost_per_mtok REAL,cache_read_cost_per_mtok REAL,cache_write_cost_per_mtok REAL,pricing_source TEXT,pricing_updated_at TEXT,billing_mode TEXT,enabled INTEGER DEFAULT 1,provider_model_id TEXT,invocation_model_id TEXT,display_name TEXT);
  CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS push_subscriptions(endpoint TEXT PRIMARY KEY,p256dh TEXT NOT NULL,auth TEXT NOT NULL,created_at TEXT NOT NULL);

  -- A task: a branch in its own worktree, cut from base_branch at base_commit. It is
  -- 'active' until a person merges it ('merged') or throws it away ('closed').
  CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT,
    base_branch TEXT NOT NULL,base_commit TEXT NOT NULL,branch TEXT NOT NULL,worktree TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'active',merge_commit TEXT,merged_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);

  -- A Claude session. task_id is null for one in the project's own checkout; project_id
  -- is null too for an Ask Bench conversation, which belongs to no project.
  CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,project_id TEXT,task_id TEXT,title TEXT NOT NULL,cwd TEXT NOT NULL,
    provider_id TEXT NOT NULL,model_id TEXT,permission_mode TEXT NOT NULL DEFAULT 'default',effort TEXT,
    claude_session_id TEXT,status TEXT NOT NULL DEFAULT 'idle',error TEXT,
    cost REAL NOT NULL DEFAULT 0,input_tokens INTEGER NOT NULL DEFAULT 0,output_tokens INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,updated_at TEXT NOT NULL,archived_at TEXT);

  -- The transcript. One row per message worth keeping; streaming deltas are not kept.
  CREATE TABLE IF NOT EXISTS session_events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT NOT NULL,kind TEXT NOT NULL,data TEXT NOT NULL,created_at TEXT NOT NULL);
  CREATE INDEX IF NOT EXISTS session_events_by_session ON session_events(session_id,id);

  -- A paired device. The token itself is never stored, only its hash.
  CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,created_at TEXT NOT NULL,last_seen_at TEXT,revoked_at TEXT);
  -- A one-time pairing code, also stored as a hash.
  CREATE TABLE IF NOT EXISTS pairings(code_hash TEXT PRIMARY KEY,expires_at TEXT NOT NULL,used_at TEXT);

  -- A task's decision log: what a session decided, and what it measured or saw that
  -- made it decide. Written by the bench tool \`log\`, read on the task's Log tab.
  CREATE TABLE IF NOT EXISTS task_log(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,session_id TEXT,decision TEXT NOT NULL,evidence TEXT NOT NULL,created_at TEXT NOT NULL);
  CREATE INDEX IF NOT EXISTS task_log_by_task ON task_log(task_id,id);
`;

export const hash = (/** @type {string} */ s) => crypto.createHash('sha256').update(s).digest('hex');
const now = () => new Date().toISOString();
const PROJECT = "SELECT id,name,path,created_at,json_extract(commands,'$.check') AS check_command,routing FROM projects";

/**
 * A transcript listing carries a stub in place of each thinking block's text: how long
 * the reasoning is, not the reasoning. It is by far the largest part of a session - more
 * than half of a long transcript's bytes - and it is drawn folded away, so it is asked
 * for one event at a time, when the box in front of it is opened. The signature goes with
 * it: nothing reads one back, and it is stored where the text is.
 * @param {any} data an event's data, as stored
 */
function stubThinking(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.content)) return data;
  let stubbed = false;
  const content = data.content.map((/** @type {any} */ b) => {
    if (!b || b.type !== 'thinking' || typeof b.thinking !== 'string') return b;
    stubbed = true;
    return { type: 'thinking', length: b.thinking.length, truncated: true };
  });
  return stubbed ? { ...data, content } : data;
}

export class Store {
  /** @param {string} root the directory the database lives under, in `.bench/` */
  constructor(root = process.cwd()) {
    this.root = root;
    this.dir = path.join(root, '.bench');
    this.file = path.join(this.dir, 'bench.db');
    Store.adoptOldName(root);
    fs.mkdirSync(this.dir, { recursive: true });
    this.db = new DatabaseSync(this.file);
    // The server, the CLI and the tests may hold the file at once.
    this.db.exec('PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL;');
    this.#migrate();
    this.db.exec(SCHEMA);
    // Columns a v1 `models` table may predate.
    for (const col of ['provider_model_id', 'invocation_model_id', 'display_name']) {
      if (!this.db.prepare('PRAGMA table_info(models)').all().some((c) => c.name === col)) this.db.exec(`ALTER TABLE models ADD COLUMN ${col} TEXT`);
    }
    // A push subscription that predates per-device choices: what this device wants to be
    // woken for, as JSON. Null means everything, which is what every device got before.
    if (!this.db.prepare('PRAGMA table_info(push_subscriptions)').all().some((c) => c.name === 'choices')) this.db.exec('ALTER TABLE push_subscriptions ADD COLUMN choices TEXT');
    // A task's bar for done: what it means in words, the command that checks it, and the
    // last time Bench ran that command - its result and the working tree it ran against.
    // created_by_session: the session that made the task with the bench tool, if one did.
    // A task made that way may not make another, so a chain of tasks cannot grow.
    // loop: the task's unattended loop, as JSON - its caps, how far it got, and how it
    // ended. Null when the task is not looping, which is what it is by default.
    for (const col of ['done', 'check_command', 'check_status', 'check_tree', 'check_output', 'check_at', 'created_by_session', 'loop']) {
      if (!this.db.prepare('PRAGMA table_info(tasks)').all().some((c) => c.name === col)) this.db.exec(`ALTER TABLE tasks ADD COLUMN ${col} TEXT`);
    }
    // started_by_session: the session that started this one with a bench tool, if one did;
    // such a session may not start others. purpose: 'review' for a session request_review
    // started, which reads and reports rather than works, so the task's check does not hold it.
    // pause: the Claude login's usage limit stopped this session, as JSON - when it lifts and
    // what Claude Code said. Null when it is not paused. auto_resume: whether Bench sends it
    // back to work when the limit lifts; null follows the person's default. model_usage: what
    // each model the session used (its own and its subagents') cost, as JSON.
    for (const col of ['started_by_session', 'purpose', 'pause', 'model_usage']) {
      if (!this.db.prepare('PRAGMA table_info(sessions)').all().some((c) => c.name === col)) this.db.exec(`ALTER TABLE sessions ADD COLUMN ${col} TEXT`);
    }
    if (!this.db.prepare('PRAGMA table_info(sessions)').all().some((c) => c.name === 'auto_resume')) this.db.exec('ALTER TABLE sessions ADD COLUMN auto_resume INTEGER');
    // routing: a project's or a task's own subagent models, as JSON, over the person's default.
    if (!this.db.prepare('PRAGMA table_info(projects)').all().some((c) => c.name === 'routing')) this.db.exec('ALTER TABLE projects ADD COLUMN routing TEXT');
    if (!this.db.prepare('PRAGMA table_info(tasks)').all().some((c) => c.name === 'routing')) this.db.exec('ALTER TABLE tasks ADD COLUMN routing TEXT');
    this.#projectlessSessions();
    // A log entry is a decision with its evidence, or a review: who reviewed and what it found.
    if (!this.db.prepare('PRAGMA table_info(task_log)').all().some((c) => c.name === 'kind')) this.db.exec("ALTER TABLE task_log ADD COLUMN kind TEXT NOT NULL DEFAULT 'decision'");
    this.#seedProviders();
  }

  /**
   * Before the rename the data lived in `.ai-code/ai-code.db`. It is moved, with its
   * WAL and shared-memory files and the attachments beside it, the first time a store
   * opens a root that has the old directory and not the new one.
   * @param {string} root
   */
  static adoptOldName(root) {
    const old = path.join(root, '.ai-code');
    const dir = path.join(root, '.bench');
    if (fs.existsSync(dir) || !fs.existsSync(path.join(old, 'ai-code.db'))) return;
    fs.renameSync(old, dir);
    for (const suffix of ['', '-wal', '-shm']) {
      const from = path.join(dir, `ai-code.db${suffix}`);
      if (fs.existsSync(from)) fs.renameSync(from, path.join(dir, `bench.db${suffix}`));
    }
  }

  // A v1 database has a `tasks` table without `base_branch`. Before touching it, the
  // whole file is copied with VACUUM INTO, which is a consistent snapshot even while
  // another process has it open; then the pipeline's tables are renamed out of the way.
  #migrate() {
    const cols = this.db.prepare('PRAGMA table_info(tasks)').all().map((c) => c.name);
    if (!cols.length || cols.includes('base_branch')) return;
    const backup = path.join(this.dir, `bench.db.pre-v2-${Date.now()}`);
    this.db.exec(`VACUUM INTO '${backup.replaceAll("'", "''")}'`);
    const have = new Set(this.db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map((r) => r.name));
    this.db.exec('BEGIN');
    try {
      for (const t of LEGACY_TABLES) {
        if (!have.has(t) || have.has(`legacy_${t}`)) continue;
        this.db.exec(`ALTER TABLE ${t} RENAME TO legacy_${t}`);
      }
      // Indexes keep their names across a rename, and a v2 index of the same name
      // would then refuse to be created.
      for (const { name } of this.db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL").all()) {
        if (String(name).startsWith('legacy_')) continue;
        this.db.exec(`DROP INDEX IF EXISTS "${name}"`);
      }
      this.db.exec('COMMIT');
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
    this.db.exec('CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)');
    this.setSetting('legacy_import', 'pending');
    this.setSetting('legacy_backup', backup);
  }

  // Before Ask Bench every session had a project, and the column said so. SQLite cannot
  // drop a NOT NULL in place, so the table is rebuilt once, from its own definition -
  // columns added since included - with only that constraint gone.
  #projectlessSessions() {
    const col = this.db.prepare('PRAGMA table_info(sessions)').all().find((c) => c.name === 'project_id');
    if (!col?.notnull) return;
    const { sql } = /** @type {{sql:string}} */ (this.db.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='sessions'").get());
    const rebuilt = sql.replace(/^CREATE TABLE "?sessions"?/i, 'CREATE TABLE sessions_rebuilt').replace(/project_id TEXT NOT NULL/i, 'project_id TEXT');
    this.db.exec('BEGIN');
    try {
      this.db.exec(rebuilt);
      this.db.exec('INSERT INTO sessions_rebuilt SELECT * FROM sessions; DROP TABLE sessions; ALTER TABLE sessions_rebuilt RENAME TO sessions;');
      this.db.exec('COMMIT');
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  // Each built-in provider exists. One that is already there keeps its row - the
  // person may have edited it - and gains any catalog model it is missing.
  #seedProviders() {
    for (const p of BUILTIN_PROVIDERS) {
      if (!this.getProvider(p.id)) this.addProvider(p);
      const have = new Set(this.listModels(p.id).map((m) => m.id));
      for (const m of p.models) if (!have.has(m.id)) this.addModel({ ...m, providerId: p.id });
    }
  }

  id() {
    return crypto.randomUUID();
  }

  /** @param {string} sql @param {...any} args */
  #one(sql, ...args) {
    return /** @type {any} */ (this.db.prepare(sql).get(...args));
  }

  /** @param {string} sql @param {...any} args */
  #all(sql, ...args) {
    return /** @type {any[]} */ (this.db.prepare(sql).all(...args));
  }

  /** Writes the named columns of a row. @param {string} table @param {string} id @param {Record<string, any>} patch */
  #patch(table, id, patch) {
    const keys = Object.keys(patch);
    if (!keys.length) return;
    this.db.prepare(`UPDATE ${table} SET ${keys.map((k) => `${k}=?`).join(',')} WHERE id=?`).run(...keys.map((k) => patch[k]), id);
  }

  // -- settings ---------------------------------------------------------------

  /** @param {string} key @returns {string|null} */
  getSetting(key) {
    return this.#one('SELECT value FROM settings WHERE key=?', key)?.value ?? null;
  }

  /** @param {string} key @param {string} value */
  setSetting(key, value) {
    this.db.prepare('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(key, String(value));
    return value;
  }

  // -- projects ---------------------------------------------------------------

  /** A project row, with its `routing` JSON read back. @param {any} r @returns {Project|undefined} */
  #project(r) {
    return r && { ...r, routing: r.routing ? JSON.parse(r.routing) : null };
  }

  /** @returns {Project[]} */
  listProjects() {
    return this.#all(`${PROJECT} ORDER BY name COLLATE NOCASE`).map((r) => /** @type {Project} */ (this.#project(r)));
  }

  /** @param {string} id @returns {Project|undefined} */
  getProject(id) {
    return this.#project(this.#one(`${PROJECT} WHERE id=?`, id));
  }

  /** @param {string} p @returns {Project|undefined} */
  projectByPath(p) {
    return this.#project(this.#one(`${PROJECT} WHERE path=?`, p));
  }

  /** @param {{name:string, path:string}} p @returns {Project} */
  addProject({ name, path: dir }) {
    const id = this.id();
    this.db.prepare("INSERT INTO projects(id,name,path,created_at,commands) VALUES(?,?,?,?,'{}')").run(id, name, dir, now());
    return /** @type {Project} */ (this.getProject(id));
  }

  /**
   * `check_command` is the default check a new task in this project starts with; an empty
   * one clears it. `routing` is the project's own subagent models; null follows the default.
   * @param {string} id @param {{name?:string, check_command?:string|null, routing?:object|null}} patch
   */
  updateProject(id, patch) {
    this.#patch('projects', id, { ...(patch.name ? { name: patch.name } : {}), ...(patch.routing !== undefined ? { routing: patch.routing ? JSON.stringify(patch.routing) : null } : {}) });
    if (patch.check_command !== undefined) {
      const check = String(patch.check_command || '').trim();
      if (check) this.db.prepare("UPDATE projects SET commands=json_set(commands,'$.check',?) WHERE id=?").run(check, id);
      else this.db.prepare("UPDATE projects SET commands=json_remove(commands,'$.check') WHERE id=?").run(id);
    }
    return this.getProject(id);
  }

  /** @param {string} id */
  deleteProject(id) {
    this.db.prepare('DELETE FROM projects WHERE id=?').run(id);
  }

  // -- providers and models ----------------------------------------------------

  /** @param {{id:string,name:string,kind:string,enabled:boolean,config?:object}} p */
  addProvider(p) {
    this.db.prepare('INSERT OR REPLACE INTO providers(id,name,kind,enabled,config) VALUES(?,?,?,?,?)').run(p.id, p.name, p.kind, p.enabled ? 1 : 0, JSON.stringify(p.config || {}));
    return this.getProvider(p.id);
  }

  /** @param {any} r @returns {Provider} */
  #provider(r) {
    return { ...r, enabled: !!r.enabled, config: JSON.parse(r.config || '{}') };
  }

  /** @param {string} id @returns {Provider|undefined} */
  getProvider(id) {
    const r = this.#one('SELECT * FROM providers WHERE id=?', id);
    return r && this.#provider(r);
  }

  /** The test-only mock provider of v1 is never listed. @returns {Provider[]} */
  listProviders() {
    return this.#all("SELECT * FROM providers WHERE kind!='mock' ORDER BY name").map((r) => this.#provider(r));
  }

  /** @param {string} id @param {{name?:string, enabled?:boolean, config?:object}} patch */
  updateProvider(id, patch) {
    const p = this.getProvider(id);
    if (!p) throw new Error('Provider not found');
    const n = { ...p, ...patch, config: { ...p.config, ...(patch.config || {}) } };
    this.db.prepare('UPDATE providers SET name=?,enabled=?,config=? WHERE id=?').run(n.name, n.enabled ? 1 : 0, JSON.stringify(n.config), id);
    return this.getProvider(id);
  }

  /** @param {any} m a catalog entry, camelCase */
  addModel(m) {
    this.db
      .prepare(
        `INSERT OR REPLACE INTO models(id,provider_id,name,capabilities,display_name,provider_model_id,invocation_model_id,context_length,
           input_cost_per_mtok,output_cost_per_mtok,cache_read_cost_per_mtok,cache_write_cost_per_mtok,pricing_source,pricing_updated_at,billing_mode,enabled)
         VALUES(?,?,?,'[]',?,?,?,?,?,?,?,?,?,?,?,?)`
      )
      .run(
        m.id, m.providerId, m.name, m.displayName ?? m.name, m.providerModelId ?? m.name, m.invocationModelId ?? m.name, m.contextLength ?? null,
        m.inputCostPerMTok ?? null, m.outputCostPerMTok ?? null, m.cacheReadCostPerMTok ?? null, m.cacheWriteCostPerMTok ?? null,
        m.pricingSource ?? null, m.pricingUpdatedAt ?? null, m.billingMode ?? null, m.enabled === false ? 0 : 1
      );
  }

  /** @param {any} r @returns {Model} */
  #model(r) {
    return { ...r, enabled: !!r.enabled };
  }

  /** @param {string} id @returns {Model|undefined} */
  getModel(id) {
    const r = this.#one('SELECT * FROM models WHERE id=?', id);
    return r && this.#model(r);
  }

  /** @param {string} [providerId] @returns {Model[]} */
  listModels(providerId) {
    const rows = providerId ? this.#all('SELECT * FROM models WHERE provider_id=? ORDER BY name', providerId) : this.#all('SELECT * FROM models ORDER BY provider_id,name');
    return rows.map((r) => this.#model(r));
  }

  /** @param {string} id @param {{enabled?:boolean}} patch */
  updateModel(id, patch) {
    if (patch.enabled !== undefined) this.db.prepare('UPDATE models SET enabled=? WHERE id=?').run(patch.enabled ? 1 : 0, id);
    return this.getModel(id);
  }

  // -- tasks ------------------------------------------------------------------

  /** A task row, with its `loop` JSON read back into the shape the wire carries. @param {any} r @returns {Task|undefined} */
  #task(r) {
    if (!r) return r;
    return { ...r, loop: r.loop ? JSON.parse(r.loop) : null, routing: r.routing ? JSON.parse(r.routing) : null };
  }

  /** @param {Omit<Task,'state'|'merge_commit'|'merged_at'|'created_at'|'updated_at'|'done'|'check_command'|'check_status'|'check_tree'|'check_output'|'check_at'|'created_by_session'|'loop'|'routing'> & {created_at?:string, done?:string|null, check_command?:string|null, created_by_session?:string|null, loop?:Task['loop']}} t @returns {Task} */
  addTask(t) {
    const at = t.created_at || now();
    this.db
      .prepare('INSERT INTO tasks(id,project_id,title,description,base_branch,base_commit,branch,worktree,state,done,check_command,created_by_session,loop,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)')
      .run(t.id, t.project_id, t.title, t.description ?? null, t.base_branch, t.base_commit, t.branch, t.worktree, 'active', t.done ?? null, t.check_command ?? null, t.created_by_session ?? null, t.loop ? JSON.stringify(t.loop) : null, at, now());
    return /** @type {Task} */ (this.getTask(t.id));
  }

  /** @param {string} id @returns {Task|undefined} */
  getTask(id) {
    return this.#task(this.#one('SELECT * FROM tasks WHERE id=?', id));
  }

  /** @param {string} [projectId] @returns {Task[]} */
  listTasks(projectId) {
    const rows = projectId ? this.#all('SELECT * FROM tasks WHERE project_id=? ORDER BY updated_at DESC', projectId) : this.#all('SELECT * FROM tasks ORDER BY updated_at DESC');
    return /** @type {Task[]} */ (rows.map((r) => this.#task(r)));
  }

  /** @param {string} id @param {Partial<Task>} patch */
  updateTask(id, patch) {
    const { loop, routing, ...rest } = patch;
    this.#patch('tasks', id, { ...rest, ...(loop === undefined ? {} : { loop: loop === null ? null : JSON.stringify(loop) }), ...(routing === undefined ? {} : { routing: routing ? JSON.stringify(routing) : null }), updated_at: now() });
    return /** @type {Task} */ (this.getTask(id));
  }

  /**
   * For a review, `decision` names the reviewer and `evidence` holds its findings. For a
   * loop, it is what the loop did and what the check said when it did it.
   * @param {{task_id:string, session_id:string|null, decision:string, evidence:string, kind?:TaskLogEntry['kind']}} e @returns {TaskLogEntry}
   */
  addLog(e) {
    const at = now();
    const kind = e.kind || 'decision';
    const r = this.db.prepare('INSERT INTO task_log(task_id,session_id,decision,evidence,kind,created_at) VALUES(?,?,?,?,?,?)').run(e.task_id, e.session_id, e.decision, e.evidence, kind, at);
    return { id: Number(r.lastInsertRowid), ...e, kind, created_at: at };
  }

  /** A task's log, newest first. @param {string} taskId @param {number} [limit] @returns {TaskLogEntry[]} */
  listLog(taskId, limit = 500) {
    return this.#all('SELECT * FROM task_log WHERE task_id=? ORDER BY id DESC LIMIT ?', taskId, limit);
  }

  // -- sessions ---------------------------------------------------------------

  /** @param {Pick<Session,'project_id'|'task_id'|'title'|'cwd'|'provider_id'|'model_id'|'permission_mode'> & {effort?:string|null, id?:string, started_by_session?:string|null, purpose?:string|null}} s @returns {Session} */
  addSession(s) {
    const id = s.id || this.id();
    const at = now();
    this.db
      .prepare('INSERT INTO sessions(id,project_id,task_id,title,cwd,provider_id,model_id,permission_mode,effort,started_by_session,purpose,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)')
      .run(id, s.project_id, s.task_id ?? null, s.title, s.cwd, s.provider_id, s.model_id ?? null, s.permission_mode, s.effort ?? null, s.started_by_session ?? null, s.purpose ?? null, at, at);
    return /** @type {Session} */ (this.getSession(id));
  }

  /** A session row, with its JSON columns read back. @param {any} r @returns {Session|undefined} */
  #session(r) {
    if (!r) return r;
    return { ...r, pause: r.pause ? JSON.parse(r.pause) : null, model_usage: r.model_usage ? JSON.parse(r.model_usage) : null, auto_resume: r.auto_resume == null ? null : !!r.auto_resume };
  }

  /** @param {string} id @returns {Session|undefined} */
  getSession(id) {
    return this.#session(this.#one('SELECT * FROM sessions WHERE id=?', id));
  }

  /** `ask` keeps only Ask Bench conversations. @param {{projectId?:string, taskId?:string, archived?:boolean, ask?:boolean}} [q] @returns {Session[]} */
  listSessions(q = {}) {
    const where = [];
    const args = [];
    if (q.projectId) where.push('project_id=?') && args.push(q.projectId);
    if (q.ask) where.push('project_id IS NULL');
    if (q.taskId) where.push('task_id=?') && args.push(q.taskId);
    if (!q.archived) where.push('archived_at IS NULL');
    return this.#all(`SELECT * FROM sessions ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY updated_at DESC`, ...args).map((r) => /** @type {Session} */ (this.#session(r)));
  }

  /** @param {string} id @param {Partial<Session>} patch */
  updateSession(id, patch) {
    /** @type {Record<string, any>} */
    const row = { ...patch, updated_at: now() };
    for (const k of /** @type {const} */ (['pause', 'model_usage'])) if (row[k] !== undefined) row[k] = row[k] === null ? null : JSON.stringify(row[k]);
    if (row.auto_resume !== undefined) row.auto_resume = row.auto_resume === null ? null : row.auto_resume ? 1 : 0;
    this.#patch('sessions', id, row);
    return /** @type {Session} */ (this.getSession(id));
  }

  /** Sessions the usage limit paused, archived or not, for the resume scheduler. @returns {Session[]} */
  pausedSessions() {
    return this.#all('SELECT * FROM sessions WHERE pause IS NOT NULL ORDER BY json_extract(pause,\'$.until\')').map((r) => /** @type {Session} */ (this.#session(r)));
  }

  /** A session left 'running' or 'waiting' by a process that is gone is idle: nothing is driving it. */
  resetLiveSessions() {
    this.db.prepare("UPDATE sessions SET status='idle' WHERE status IN ('running','waiting')").run();
  }

  /** @param {string} sessionId @param {string} kind @param {unknown} data @returns {SessionEvent} */
  addEvent(sessionId, kind, data) {
    const at = now();
    const r = this.db.prepare('INSERT INTO session_events(session_id,kind,data,created_at) VALUES(?,?,?,?)').run(sessionId, kind, JSON.stringify(data), at);
    return { id: Number(r.lastInsertRowid), session_id: sessionId, kind, data, created_at: at };
  }

  /**
   * The newest `limit` events, oldest first; with `before`, the window ending just before that id;
   * with `after`, the first `limit` events after that id, so a reader holding the tail asks for
   * only what is new. A full `after` page means there may be more beyond it. Thinking blocks come
   * back as stubs - see `stubThinking` - so what this hands a reader is not what is kept;
   * `eventById` is where the text behind a stub lives. Every door into a listing stubs them, the
   * incremental one included, or a pane that read its tail this way would hold the text anyway.
   * @param {string} sessionId @param {{before?:number, after?:number, limit?:number}} [o] @returns {SessionEvent[]}
   */
  listEvents(sessionId, o = {}) {
    const limit = Math.min(o.limit || 400, 2000);
    if (o.after) {
      return this.#all('SELECT * FROM session_events WHERE session_id=? AND id>? ORDER BY id LIMIT ?', sessionId, o.after, limit).map((r) => ({ ...r, data: stubThinking(JSON.parse(r.data)) }));
    }
    const rows = o.before
      ? this.#all('SELECT * FROM session_events WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?', sessionId, o.before, limit)
      : this.#all('SELECT * FROM session_events WHERE session_id=? ORDER BY id DESC LIMIT ?', sessionId, limit);
    return rows.reverse().map((r) => ({ ...r, data: stubThinking(JSON.parse(r.data)) }));
  }

  /** One event as it was stored, thinking text and all: what the stub in a listing stands in for.
   * @param {string} sessionId @param {number} id @returns {SessionEvent|undefined} */
  eventById(sessionId, id) {
    const r = this.#one('SELECT * FROM session_events WHERE session_id=? AND id=?', sessionId, id);
    return r && { ...r, data: JSON.parse(r.data) };
  }

  /** @param {string} sessionId */
  deleteSessionData(sessionId) {
    this.db.prepare('DELETE FROM session_events WHERE session_id=?').run(sessionId);
    this.db.prepare('DELETE FROM sessions WHERE id=?').run(sessionId);
  }

  // -- usage ------------------------------------------------------------------

  /** Spend by project, and by task within it. */
  usage() {
    return this.#all(
      `SELECT s.project_id, s.task_id, s.provider_id, SUM(s.cost) cost, SUM(s.input_tokens) input_tokens, SUM(s.output_tokens) output_tokens, COUNT(*) sessions
       FROM sessions s GROUP BY s.project_id, s.task_id, s.provider_id`
    );
  }

  /**
   * Spend by model, summed over sessions: each session's per-model figures, subagents'
   * models included. Sessions from before Bench kept these are left out, so the sum can be
   * less than the totals above. @param {{projectId?:string, taskId?:string}} [q]
   * @returns {{model:string, cost:number, input_tokens:number, output_tokens:number, sessions:number}[]}
   */
  usageByModel(q = {}) {
    const where = ['model_usage IS NOT NULL'];
    const args = [];
    if (q.projectId) where.push('project_id=?') && args.push(q.projectId);
    if (q.taskId) where.push('task_id=?') && args.push(q.taskId);
    /** @type {Map<string, {model:string, cost:number, input_tokens:number, output_tokens:number, sessions:number}>} */
    const by = new Map();
    for (const r of this.#all(`SELECT model_usage FROM sessions WHERE ${where.join(' AND ')}`, ...args)) {
      for (const [model, u] of Object.entries(JSON.parse(r.model_usage) || {})) {
        const t = by.get(model) || { model, cost: 0, input_tokens: 0, output_tokens: 0, sessions: 0 };
        t.cost += u.cost || 0;
        t.input_tokens += u.input_tokens || 0;
        t.output_tokens += u.output_tokens || 0;
        t.sessions++;
        by.set(model, t);
      }
    }
    return [...by.values()].sort((a, b) => b.cost - a.cost);
  }

  // -- the v1 import ------------------------------------------------------------

  /** Pipeline tasks whose worktree may still hold work. */
  legacyTasks() {
    const has = this.#one("SELECT 1 x FROM sqlite_master WHERE type='table' AND name='legacy_tasks'");
    if (!has) return [];
    return this.#all("SELECT id,project_id,title,description,worktree,branch,base_commit,plan_base,created_at FROM legacy_tasks WHERE worktree IS NOT NULL AND state NOT IN ('CANCELLED')");
  }

  // -- devices and pairing ------------------------------------------------------

  /** @param {string} code @param {number} ttlMs */
  addPairing(code, ttlMs) {
    this.db.prepare('INSERT INTO pairings(code_hash,expires_at) VALUES(?,?)').run(hash(code), new Date(Date.now() + ttlMs).toISOString());
  }

  /** Marks a code used and reports whether it was valid. A code works once. @param {string} code */
  usePairing(code) {
    const r = this.db.prepare('UPDATE pairings SET used_at=? WHERE code_hash=? AND used_at IS NULL AND expires_at>?').run(now(), hash(code), now());
    this.db.prepare('DELETE FROM pairings WHERE expires_at<?').run(new Date(Date.now() - 86400000).toISOString());
    return r.changes === 1;
  }

  /** @param {string} name @param {string} token */
  addDevice(name, token) {
    const id = this.id();
    this.db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(id, name, hash(token), now());
    return this.#one('SELECT id,name,created_at,last_seen_at FROM devices WHERE id=?', id);
  }

  /** The live device a token belongs to, touching its last-seen time. @param {string} token */
  deviceByToken(token) {
    const d = this.#one('SELECT id,name,last_seen_at FROM devices WHERE token_hash=? AND revoked_at IS NULL', hash(token));
    if (d && (!d.last_seen_at || Date.now() - Date.parse(d.last_seen_at) > 60000)) this.db.prepare('UPDATE devices SET last_seen_at=? WHERE id=?').run(now(), d.id);
    return d || null;
  }

  listDevices() {
    return this.#all('SELECT id,name,created_at,last_seen_at FROM devices WHERE revoked_at IS NULL ORDER BY created_at');
  }

  /** @param {string} id */
  revokeDevice(id) {
    return this.db.prepare('UPDATE devices SET revoked_at=? WHERE id=? AND revoked_at IS NULL').run(now(), id).changes === 1;
  }

  // -- push -----------------------------------------------------------------

  /** @param {{endpoint:string, keys:{p256dh:string, auth:string}, choices?:unknown}} sub */
  addPushSubscription(sub) {
    this.db
      .prepare('INSERT INTO push_subscriptions(endpoint,p256dh,auth,created_at,choices) VALUES(?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET p256dh=excluded.p256dh,auth=excluded.auth')
      .run(sub.endpoint, sub.keys.p256dh, sub.keys.auth, now(), sub.choices == null ? null : JSON.stringify(sub.choices));
  }

  /** What one device asked to be woken for. @param {string} endpoint @param {unknown} choices */
  setPushChoices(endpoint, choices) {
    return this.db.prepare('UPDATE push_subscriptions SET choices=? WHERE endpoint=?').run(choices == null ? null : JSON.stringify(choices), endpoint).changes;
  }

  /** @param {string} endpoint */
  deletePushSubscription(endpoint) {
    return this.db.prepare('DELETE FROM push_subscriptions WHERE endpoint=?').run(endpoint).changes;
  }

  listPushSubscriptions() {
    return this.#all('SELECT * FROM push_subscriptions ORDER BY created_at');
  }
}
