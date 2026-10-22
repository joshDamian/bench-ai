import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import { Service } from '../src/service.mjs';
import { Store } from '../src/store.mjs';
import { askServer, ASK_READ_ONLY, ASK_TOOLS } from '../src/ask.mjs';
import { benchServer, sessionPath, TOOL_TIMEOUT_MS } from '../src/tools.mjs';
import { fakeQuery, calls } from './fake-sdk.mjs';
import { tmp } from './tmp.mjs';

// Ask Bench: a session with no project, whose tools reach across every project.

const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();

function repo(name = 'repo') {
  const dir = tmp(name);
  git(dir, 'init', '-q', '-b', 'main');
  git(dir, 'config', 'user.email', 't@t');
  git(dir, 'config', 'user.name', 'T');
  fs.writeFileSync(path.join(dir, 'a.txt'), 'one\n');
  git(dir, 'add', '-A');
  git(dir, 'commit', '-qm', 'first');
  return dir;
}

function setup() {
  const root = tmp('root');
  process.env.BENCH_WORKTREE_ROOT = path.join(root, 'worktrees');
  const svc = new Service(root, { query: fakeQuery });
  const api = svc.addProject({ path: repo('api'), name: 'api' });
  const web = svc.addProject({ path: repo('web'), name: 'web' });
  return { svc, api, web, root };
}

async function until(fn, ms = 3000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (fn()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('timed out');
}

async function call(svc, sessionId, name, args = {}) {
  const t = askServer(svc, sessionId).tools.find((x) => x.name === name);
  assert.ok(t, `there is a ${name} tool`);
  const r = await t.handler(args, {});
  return { text: r.content.map((b) => b.text).join('\n'), error: !!r.isError };
}

test('a conversation has no project, works in its own folder, and always asks', async () => {
  const { svc, root } = setup();
  svc.setDefaults({ mode: 'bypassPermissions' });
  const s = svc.createSession({ title: '' });
  assert.equal(s.project_id, null);
  assert.equal(s.task_id, null);
  assert.equal(s.title, 'New conversation');
  assert.equal(s.cwd, path.join(root, '.bench', 'ask'));
  assert.ok(fs.existsSync(s.cwd));
  assert.equal(s.permission_mode, 'default', "the person's default mode does not reach it");
  await assert.rejects(svc.updateSession(s.id, { mode: 'bypassPermissions' }), (e) => e.status === 409);
  assert.equal(svc.session(s.id).permission_mode, 'default');
  assert.throws(() => svc.createSession({ taskId: 'x' }), /needs its project/);
  assert.deepEqual(svc.sessions({ ask: true }).map((x) => x.id), [s.id]);
  assert.equal(sessionPath(s), `/#/ask/${s.id}`);
  svc.shutdown();
});

test("a conversation's query gets Bench's tools, no shell, no project settings, and its brief", async () => {
  const { svc } = setup();
  const s = svc.createSession({});
  svc.send(s.id, 'what is running?');
  await until(() => svc.events(s.id).some((e) => e.kind === 'result'));
  const o = calls.at(-1).options;
  assert.deepEqual(o.tools, ASK_TOOLS);
  assert.ok(!o.tools.includes('Bash') && !o.tools.includes('Edit') && !o.tools.includes('Write'));
  assert.deepEqual(o.settingSources, []);
  assert.deepEqual(o.plugins, []);
  assert.equal(o.permissionMode, 'default');
  assert.equal(o.additionalDirectories.length, 1, 'its attachments only, no scratch folder');
  assert.match(o.systemPrompt.append, /# Ask Bench/);
  assert.equal(o.hooks.Stop, undefined, 'no task, so no check holds its stop');
  const names = o.mcpServers.bench.instance ? askServer(svc, s.id).tools.map((t) => t.name) : [];
  assert.ok(names.includes('overview'));
  assert.equal(svc.session(s.id).title, 'what is running?', 'the first message titles it');
  svc.shutdown();
});

test('the tool set reads freely, asks for the rest, and has no way to land or delete', () => {
  const { svc } = setup();
  const s = svc.createSession({});
  const server = askServer(svc, s.id);
  assert.equal(server.name, 'bench');
  assert.equal(server.timeout, TOOL_TIMEOUT_MS);
  const names = server.tools.map((t) => t.name);
  for (const t of server.tools) assert.equal(t.annotations?.readOnlyHint, ASK_READ_ONLY.has(t.name), `${t.name}'s readOnlyHint`);
  for (const n of names) assert.doesNotMatch(n, /merge|close|delete|remove|revoke|archive|approve|answer|restart|update_bench/, `${n} is not a tool`);
  svc.shutdown();
});

test('read-only tools run without asking; the rest reach the card in words, with no "always allow"', async () => {
  const { svc } = setup();
  const s = svc.createSession({});
  svc.send(s.id, 'hi');
  await until(() => svc.events(s.id).some((e) => e.kind === 'result'));
  const { canUseTool } = calls.at(-1).options;
  const opts = () => ({ signal: new AbortController().signal, suggestions: [{ type: 'addRules', rules: [{ toolName: 'x' }], behavior: 'allow', destination: 'localSettings' }] });
  for (const name of ASK_READ_ONLY) assert.equal((await canUseTool(`mcp__bench__${name}`, {}, opts())).behavior, 'allow', name);
  assert.equal(svc.runtime.pending(s.id).length, 0);

  const ask = async (tool, input) => {
    const p = canUseTool(tool, input, opts());
    await until(() => svc.runtime.pending(s.id).length === 1);
    const [a] = svc.runtime.pending(s.id);
    svc.runtime.answer(a.id, { allow: true, always: true });
    const r = await p;
    assert.equal(r.behavior, 'allow');
    assert.equal(r.updatedPermissions, undefined, '"always" grants nothing here');
    return a;
  };
  assert.equal((await ask('mcp__bench__create_task', { project: 'api', title: 'Bump the SDK', prompt: 'x' })).title, 'Bench: create task in api — Bump the SDK');
  assert.equal((await ask('mcp__bench__set_defaults', { mode: 'plan' })).title, 'Bench: change the defaults — mode plan');
  assert.equal((await ask('mcp__bench__pairing_link', {})).title, 'Bench: make a one-time link that pairs a new device');
  await ask('Read', { file_path: '/etc/hosts' });
  svc.shutdown();
});

test('overview, list_projects, list_tasks and get_task see every project', async () => {
  const { svc, api, web } = setup();
  const me = svc.createSession({});
  svc.createTask(api.id, { title: 'Parser' });
  const { task } = svc.createTask(web.id, { title: 'Header' });
  const over = await call(svc, me.id, 'overview');
  assert.match(over.text, /- api \(\w{8}\): 1 active task, 1 open session/);
  assert.match(over.text, /- web/);
  assert.match(over.text, /Defaults for new sessions/);
  assert.match((await call(svc, me.id, 'list_projects')).text, /2 projects/);
  const all = await call(svc, me.id, 'list_tasks');
  assert.match(all.text, /Parser/);
  assert.match(all.text, /Header/);
  const one = await call(svc, me.id, 'list_tasks', { project: 'WEB' });
  assert.doesNotMatch(one.text, /Parser/);
  assert.match((await call(svc, me.id, 'get_task', { id: task.id.slice(0, 6) })).text, /Project: web\nTask: Header/);
  const miss = await call(svc, me.id, 'list_tasks', { project: 'ap' });
  assert.ok(miss.error);
  assert.match(miss.text, /Did you mean api\?/);
  svc.shutdown();
});

test('create_task and start_session work in any project, and what they start cannot start more', async () => {
  const { svc, api, web } = setup();
  const me = svc.createSession({});
  const made = await call(svc, me.id, 'create_task', { project: 'api', title: 'Bump', prompt: 'Bump the SDK' });
  assert.ok(!made.error, made.text);
  const t = svc.tasks(api.id)[0];
  assert.equal(t.created_by_session, me.id);
  const started = await call(svc, me.id, 'start_session', { project: web.id.slice(0, 6), prompt: 'look around' });
  assert.ok(!started.error, started.text);
  const s = svc.sessions({ projectId: web.id })[0];
  assert.equal(s.started_by_session, me.id);
  assert.equal(s.task_id, null);
  assert.match(started.text, /web's own checkout/);
  const list = await call(svc, me.id, 'list_sessions');
  assert.match(list.text, /\(this conversation\)/);
  assert.match(list.text, /Ask Bench/);
  const again = await benchServer(svc, s.id).tools.find((x) => x.name === 'start_session').handler({ prompt: 'more' }, {});
  assert.ok(again.isError);
  assert.match(again.content[0].text, /one level only/);
  svc.shutdown();
});

test('set_defaults changes what new sessions start with', async () => {
  const { svc } = setup();
  const me = svc.createSession({});
  const r = await call(svc, me.id, 'set_defaults', { mode: 'acceptEdits', effort: 'high' });
  assert.ok(!r.error, r.text);
  assert.equal(svc.defaults().mode, 'acceptEdits');
  assert.equal(svc.defaults().effort, 'high');
  assert.ok((await call(svc, me.id, 'set_defaults', {})).error);
  svc.shutdown();
});

test('machine and pairing_link need the running server, and say so without it', async () => {
  const { svc } = setup();
  const me = svc.createSession({});
  assert.match((await call(svc, me.id, 'machine')).text, /Only the running Bench server/);
  svc.host = { pair: async () => ({ urls: ['https://box.ts.net/#/pair/abc'], expiresIn: 600 }), metrics: async () => { throw new Error('not here'); }, status: () => ({}) };
  const link = await call(svc, me.id, 'pairing_link');
  assert.match(link.text, /within 10 minutes/);
  assert.match(link.text, /box\.ts\.net/);
  svc.shutdown();
});

test('a database from before Ask Bench lets a session have no project, and keeps every row', () => {
  const root = tmp('old');
  fs.mkdirSync(path.join(root, '.bench'), { recursive: true });
  const db = new DatabaseSync(path.join(root, '.bench', 'bench.db'));
  db.exec(`CREATE TABLE tasks(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT,base_branch TEXT NOT NULL,base_commit TEXT NOT NULL,branch TEXT NOT NULL,worktree TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'active',merge_commit TEXT,merged_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
    CREATE TABLE sessions(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,task_id TEXT,title TEXT NOT NULL,cwd TEXT NOT NULL,provider_id TEXT NOT NULL,model_id TEXT,permission_mode TEXT NOT NULL DEFAULT 'default',effort TEXT,claude_session_id TEXT,status TEXT NOT NULL DEFAULT 'idle',error TEXT,cost REAL NOT NULL DEFAULT 0,input_tokens INTEGER NOT NULL DEFAULT 0,output_tokens INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,archived_at TEXT);
    ALTER TABLE sessions ADD COLUMN started_by_session TEXT;
    INSERT INTO sessions(id,project_id,title,cwd,provider_id,cost,created_at,updated_at,started_by_session) VALUES('s1','p1','Old','/x','claude',1.5,'t','t','s0');`);
  db.close();
  const store = new Store(root);
  const col = store.db.prepare('PRAGMA table_info(sessions)').all().find((c) => c.name === 'project_id');
  assert.equal(col.notnull, 0);
  const old = store.getSession('s1');
  assert.equal(old.project_id, 'p1');
  assert.equal(old.cost, 1.5);
  assert.equal(old.started_by_session, 's0');
  const s = store.addSession({ project_id: null, task_id: null, title: 'New', cwd: '/y', provider_id: 'claude', model_id: null, permission_mode: 'default' });
  assert.equal(store.getSession(s.id).project_id, null);
  store.db.close();
  // Opening it again changes nothing.
  const again = new Store(root);
  assert.equal(again.listSessions().length, 2);
  again.db.close();
});
