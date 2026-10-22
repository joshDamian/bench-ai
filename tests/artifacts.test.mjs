import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Service } from '../src/service.mjs';
import { benchServer } from '../src/tools.mjs';
import { askServer, ASK_READ_ONLY } from '../src/ask.mjs';
import { snapshot, removable, holders } from '../src/artifacts.mjs';
import { fakeQuery, calls } from './fake-sdk.mjs';
import { tmp } from './tmp.mjs';

// What sessions leave in /tmp: recorded as their tools run, listed and cleaned by tool,
// and cleaned when a session is archived - never anything a process has open. The
// watched folder is a test's own, through BENCH_TMP_ROOTS.

const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();

function repo() {
  const dir = tmp('repo');
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
  const watched = tmp('watched');
  process.env.BENCH_TMP_ROOTS = watched;
  process.env.BENCH_WORKTREE_ROOT = path.join(root, 'worktrees');
  const svc = new Service(root, { query: fakeQuery });
  const project = svc.addProject({ path: repo(), name: 'api' });
  return { svc, project, root, watched };
}

async function until(fn, ms = 3000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (fn()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('timed out');
}

/** Starts the session so its query options - and their hooks - exist. */
async function started(svc, s) {
  svc.send(s.id, 'hi');
  await until(() => svc.events(s.id).some((e) => e.kind === 'result'));
  return calls.at(-1).options.hooks;
}

/** One tool call through the session's hooks, making `make` while it runs. */
async function toolRun(hooks, tool, make) {
  const id = `toolu_${Math.random().toString(36).slice(2)}`;
  await hooks.PreToolUse[0].hooks[0]({ hook_event_name: 'PreToolUse', tool_name: tool, tool_input: {}, tool_use_id: id });
  make();
  await hooks.PostToolUse[0].hooks[0]({ hook_event_name: 'PostToolUse', tool_name: tool, tool_input: {}, tool_response: {}, tool_use_id: id });
}

async function call(server, name, args = {}) {
  const t = server.tools.find((x) => x.name === name);
  assert.ok(t, `there is a ${name} tool`);
  const r = await t.handler(args, {});
  return { text: r.content.map((b) => b.text).join('\n'), error: !!r.isError };
}

test('what appears in /tmp during a tool call is recorded against the session, shared names aside', async () => {
  const { svc, project, watched } = setup();
  fs.writeFileSync(path.join(watched, 'before.txt'), 'x');
  const { session } = svc.createTask(project.id, { title: 'T' });
  const hooks = await started(svc, session);
  await toolRun(hooks, 'Bash', () => {
    fs.mkdirSync(path.join(watched, 'unpacked'));
    fs.writeFileSync(path.join(watched, 'unpacked', 'a.bin'), 'abc');
    fs.writeFileSync(path.join(watched, 'dump.xml'), '<x/>');
    fs.mkdirSync(path.join(watched, '.X11-unix'));
    fs.mkdirSync(path.join(watched, 'claude-1000'));
  });
  await toolRun(hooks, 'Read', () => {});
  const rows = svc.store.listArtifacts({ sessionId: session.id });
  assert.deepEqual(rows.map((a) => path.basename(a.path)).sort(), ['dump.xml', 'unpacked']);
  assert.ok(rows.every((a) => a.tool === 'Bash' && a.task_id === session.task_id && a.project_id === project.id));
  assert.ok(!snapshot().has(path.join(watched, '.X11-unix')));
  svc.shutdown();
});

test('list_artifacts and clean_up: unopened items go, open ones stay and say why, and the record keeps who asked', async () => {
  const { svc, project, watched } = setup();
  const s = svc.createSession({ projectId: project.id });
  const hooks = await started(svc, s);
  const held = path.join(watched, 'server-root');
  await toolRun(hooks, 'Bash', () => {
    fs.writeFileSync(path.join(watched, 'download.zip'), Buffer.alloc(2048));
    fs.mkdirSync(held);
    fs.writeFileSync(path.join(held, 'log'), 'x');
  });
  const fd = fs.openSync(path.join(held, 'log'), 'r');
  try {
    const bench = benchServer(svc, s.id);
    const list = await call(bench, 'list_artifacts');
    assert.match(list.text, /2 items this session left in \/tmp/);
    assert.match(list.text, /download\.zip — 2 KB, not open; made by session \w{8} during Bash/);
    assert.match(list.text, new RegExp(`server-root — .*open in process ${process.pid}`));
    const cleaned = await call(bench, 'clean_up');
    assert.match(cleaned.text, /Removed 1 item/);
    assert.match(cleaned.text, /Kept:\n- .*server-root — open in process/);
    assert.ok(!fs.existsSync(path.join(watched, 'download.zip')));
    assert.ok(fs.existsSync(held));
    const after = await call(bench, 'list_artifacts', { removed: true });
    assert.match(after.text, /download\.zip — removed .* \(asked by session/);
  } finally {
    fs.closeSync(fd);
  }
  svc.shutdown();
});

test('archiving a session removes what nothing has open, logs it in the transcript, and a restart retries the rest', async () => {
  const { svc, project, root, watched } = setup();
  const s = svc.createSession({ projectId: project.id });
  const hooks = await started(svc, s);
  await toolRun(hooks, 'Bash', () => {
    fs.writeFileSync(path.join(watched, 'shot.png'), 'png');
    fs.writeFileSync(path.join(watched, 'busy.log'), 'x');
  });
  const fd = fs.openSync(path.join(watched, 'busy.log'), 'r');
  svc.archiveSession(s.id);
  assert.ok(!fs.existsSync(path.join(watched, 'shot.png')));
  assert.ok(fs.existsSync(path.join(watched, 'busy.log')));
  const note = svc.store.listEvents(s.id).filter((e) => e.kind === 'notice').at(-1);
  assert.match(note.data.text, /Removed 1 temporary item this session left in \/tmp .*shot\.png/);
  assert.match(note.data.text, /Kept .*busy\.log \(open in process \d+\); Bench tries again when it restarts/);
  const row = svc.store.listArtifacts({ sessionId: s.id, removed: true }).find((a) => a.path.endsWith('shot.png'));
  assert.equal(row.removed_by, 'archive');
  fs.closeSync(fd);
  svc.shutdown();
  const again = new Service(root, { query: fakeQuery });
  assert.ok(!fs.existsSync(path.join(watched, 'busy.log')), 'the restart cleans what is no longer open');
  again.shutdown();
});

test('closing a task cleans after its sessions, as archiving one does', async () => {
  const { svc, project, watched } = setup();
  const { task, session } = svc.createTask(project.id, { title: 'T' });
  const hooks = await started(svc, session);
  await toolRun(hooks, 'Bash', () => fs.writeFileSync(path.join(watched, 'build.tar'), 'x'));
  svc.closeTask(task.id);
  assert.ok(!fs.existsSync(path.join(watched, 'build.tar')));
  svc.shutdown();
});

test('only top-level entries of a watched folder are ever removed, and what is gone is marked so', async () => {
  const { svc, project, watched } = setup();
  const s = svc.createSession({ projectId: project.id });
  const elsewhere = tmp('elsewhere');
  const outside = path.join(elsewhere, 'keep.txt');
  fs.writeFileSync(outside, 'x');
  svc.store.addArtifact({ session_id: s.id, task_id: null, project_id: project.id, path: outside });
  svc.store.addArtifact({ session_id: s.id, task_id: null, project_id: project.id, path: path.join(watched, 'vanished') });
  assert.equal(removable(outside), false);
  const r = svc.cleanArtifacts({ sessionId: s.id }, 'person');
  assert.ok(fs.existsSync(outside));
  assert.deepEqual(r.kept.map((k) => k.why), ['not a top-level entry of a temporary folder']);
  assert.deepEqual(r.gone, [path.join(watched, 'vanished')]);
  assert.equal(holders([outside]).get(outside).length, 0);
  svc.shutdown();
});

test('Ask Bench lists and cleans across sessions, and asks before cleaning', async () => {
  const { svc, project, watched } = setup();
  const s = svc.createSession({ projectId: project.id });
  const hooks = await started(svc, s);
  await toolRun(hooks, 'Bash', () => fs.writeFileSync(path.join(watched, 'cmdtools.zip'), 'x'));
  const me = svc.createSession({});
  const ask = askServer(svc, me.id);
  assert.ok(ASK_READ_ONLY.has('list_artifacts'));
  assert.ok(!ASK_READ_ONLY.has('clean_up'));
  assert.match((await call(ask, 'list_artifacts', { project: 'api' })).text, /1 item api's sessions left in \/tmp/);
  assert.match((await call(ask, 'list_artifacts')).text, /cmdtools\.zip/);
  const bare = await call(ask, 'clean_up');
  assert.ok(bare.error);
  assert.match(bare.text, /Name a session, task or project, or pass all: true/);
  assert.match((await call(ask, 'clean_up', { session: s.id.slice(0, 6) })).text, /Removed 1 item/);
  assert.ok(!fs.existsSync(path.join(watched, 'cmdtools.zip')));
  // The approval card names it in words.
  svc.send(me.id, 'hi');
  await until(() => svc.events(me.id).some((e) => e.kind === 'result'));
  const p = calls.at(-1).options.canUseTool('mcp__bench__clean_up', { project: 'api' }, { signal: new AbortController().signal, suggestions: [] });
  await until(() => svc.runtime.pending(me.id).length === 1);
  assert.equal(svc.runtime.pending(me.id)[0].title, 'Bench: remove what api left in /tmp');
  svc.runtime.answer(svc.runtime.pending(me.id)[0].id, { allow: false });
  assert.equal((await p).behavior, 'deny');
  svc.shutdown();
});
