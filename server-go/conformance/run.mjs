#!/usr/bin/env node
// Conformance: the same scenario, step by step, against the Node server and the Go
// server, each on a fresh data folder and its own copy of a test repository, both
// driving the fake Claude CLI. Every answer is normalised - ids, times, hashes, paths -
// and the two are compared. Exit 1 when any step differs.
//
//   node server-go/conformance/run.mjs [--keep] [--only=<step substring>]
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const fake = path.join(here, 'fake-claude.mjs');
const keep = process.argv.includes('--keep');
const only = process.argv.find((a) => a.startsWith('--only='))?.slice(7);
const goBin = process.env.BENCH_GO_BIN || path.join(repoRoot, 'server-go', 'bin', 'bench-go');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function makeRepo(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const g = (...a) => execFileSync('git', a, { cwd: dir, stdio: 'pipe', env: { ...process.env, GIT_AUTHOR_DATE: '2026-01-01T00:00:00Z', GIT_COMMITTER_DATE: '2026-01-01T00:00:00Z' } });
  g('init', '-q', '-b', 'main');
  g('config', 'user.email', 'test@bench');
  g('config', 'user.name', 'Bench Test');
  fs.writeFileSync(path.join(dir, 'README.md'), '# Demo\n\nA project for conformance.\n');
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name: 'demo', scripts: { test: 'node -e "process.exit(0)"', lint: 'true' } }, null, 2));
  fs.mkdirSync(path.join(dir, 'src'));
  fs.writeFileSync(path.join(dir, 'src', 'index.js'), 'console.log("hi");\n');
  g('add', '-A');
  g('commit', '-qm', 'init');
}

async function start(kind, root, port) {
  const env = { ...process.env, BENCH_ROOT: root, PORT: String(port), BENCH_CLAUDE_BIN: fake, BENCH_PROJECTS_DIR: path.join(root, 'projects'), BENCH_TMP_ROOTS: path.join(root, 'tmp') };
  fs.mkdirSync(path.join(root, 'tmp'), { recursive: true });
  const child = kind === 'node'
    ? spawn(process.execPath, [path.join(repoRoot, 'src', 'cli.mjs'), 'serve'], { env, stdio: ['ignore', 'pipe', 'pipe'] })
    : spawn(goBin, ['serve'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  child.stdout.on('data', (d) => (log += d));
  child.stderr.on('data', (d) => (log += d));
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/ping`);
      if (r.ok) return { child, log: () => log, base: `http://127.0.0.1:${port}` };
    } catch {}
    await sleep(100);
  }
  throw new Error(`${kind} did not start:\n${log}`);
}

// --- normalising ----------------------------------------------------------------------

function normaliser(root, repo, home) {
  const ids = new Map();
  const shas = new Map();
  const tag = (map, v, prefix) => {
    if (!map.has(v)) map.set(v, `<${prefix}${map.size + 1}>`);
    return map.get(v);
  };
  const VOLATILE = new Set(['push', 'key', 'created_at', 'updated_at', 'archived_at', 'last_seen_at', 'merged_at', 'check_at', 'started_at', 'ended_at', 'mtime', 'at', 'uptimeMs', 'startedAt', 'pid', 'ms', 'duration_ms', 'expiresIn', 'code_hash', 'qr', 'code', 'removed_at', 'size', 'loadavg', 'memFree', 'diskFree', 'until']);
  const str = (s) => {
    s = s.split(root).join('<root>').split(repo).join('<repo>').split(home).join('<home>');
    for (const m of s.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g) || []) tag(ids, m, 'id');
    // Ids are also shown cut to 8 or 6 characters: in tool text, and in a task's branch.
    for (const [full, t] of ids) s = s.split(full).join(t).split(full.slice(0, 8)).join(`${t}:8`).replace(new RegExp(`-${full.slice(0, 6)}\\b`, 'g'), `-${t}:6`);
    return s
      .replace(/\b[0-9a-f]{40}\b/g, (m) => tag(shas, m, 'sha'))
      .replace(/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?Z?( UTC)?/g, '<time>')
      .replace(/msg_[0-9a-f-]{12}/g, '<msg>')
      .replace(/toolu_[0-9a-f-]{10}/g, '<toolu>')
      .replace(/bench-index-\S+/g, '<index>')
      .replace(/\b[a-z0-9]{8}-[a-z0-9]{1,4}-(?=\S+\.\w+)/g, '<upload>-');
  };
  const walk = (v, key) => {
    if (key && VOLATILE.has(key) && v !== null && v !== undefined) return `<${key}>`;
    if (typeof v === 'string') return str(v);
    if (Array.isArray(v)) return v.map((x) => walk(x));
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [str(k), walk(x, k)]));
    return v;
  };
  return walk;
}

// --- the scenario -----------------------------------------------------------------------

async function scenario(srv, repo, root) {
  const out = [];
  const api = async (method, url, body, headers = {}) => {
    const r = await fetch(srv.base + url, { method, headers: { ...(body !== undefined ? { 'content-type': 'application/json' } : {}), ...headers }, body: body !== undefined ? JSON.stringify(body) : undefined });
    const text = await r.text();
    let json;
    try { json = JSON.parse(text); } catch { json = text; }
    return { status: r.status, body: json, headers: Object.fromEntries([...r.headers].filter(([k]) => ['content-type', 'cache-control', 'content-encoding', 'vary', 'content-security-policy', 'content-disposition', 'accept-ranges', 'content-range'].includes(k))) };
  };
  const step = async (name, fn) => {
    if (only && !name.includes(only)) return fn().catch(() => undefined);
    try {
      const v = await fn();
      out.push({ name, value: v });
      return v;
    } catch (e) {
      out.push({ name, value: { threw: String(e.message) } });
    }
  };
  const until = async (fn, ms = 8000) => {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      const v = await fn();
      if (v) return v;
      await sleep(50);
    }
    return null;
  };

  // A WebSocket that records every message, to compare the event stream.
  const events = [];
  const ws = new WebSocket(srv.base.replace('http', 'ws') + '/api/ws');
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (m) => events.push(JSON.parse(m.data));
  await until(() => events.find((e) => e.type === 'hello'));

  await step('ping', () => api('GET', '/api/ping'));
  await step('unauthorized from elsewhere', () => api('GET', '/api/state', undefined, { host: 'phone.example.ts.net' }));
  await step('state, empty', () => api('GET', '/api/state'));
  await step('providers', () => api('GET', '/api/providers'));
  await step('defaults', () => api('GET', '/api/defaults'));
  await step('set defaults', () => api('PUT', '/api/defaults', { mode: 'acceptEdits', effort: 'high' }));
  await step('set defaults: bad effort', () => api('PUT', '/api/defaults', { effort: 'huge' }));
  await step('set defaults back', () => api('PUT', '/api/defaults', { mode: 'default', effort: null }));
  await step('routing default patch', () => api('PUT', '/api/defaults', { routing: { explorer: 'sonnet' } }));
  await step('write must be JSON', () => fetch(srv.base + '/api/projects', { method: 'POST', body: 'x' }).then(async (r) => ({ status: r.status, body: await r.json() })));
  await step('not found', () => api('GET', '/api/nope'));
  await step('add project: not a dir', () => api('POST', '/api/projects', { path: path.join(root, 'nope') }));
  const project = (await step('add project', () => api('POST', '/api/projects', { path: repo }))).body;
  await step('add project again', () => api('POST', '/api/projects', { path: repo }));
  await step('projects', () => api('GET', '/api/projects'));
  await step('project git', () => api('GET', `/api/projects/${project.id}/git`));
  await step('project diff', () => api('GET', `/api/projects/${project.id}/diff`));
  await step('project tree', () => api('GET', `/api/projects/${project.id}/tree`));
  await step('project text', () => api('GET', `/api/projects/${project.id}/text?path=README.md`));
  await step('project text: outside', () => api('GET', `/api/projects/${project.id}/text?path=../x`));
  await step('project text: missing', () => api('GET', `/api/projects/${project.id}/text?path=nope.md`));
  await step('project set check', () => api('PATCH', `/api/projects/${project.id}`, { check_command: 'npm test' }));
  await step('project routing', () => api('PATCH', `/api/projects/${project.id}`, { routing: { implementer: 'opus' } }));
  await step('project routing: bad', () => api('PATCH', `/api/projects/${project.id}`, { routing: { implementer: 'two words' } }));
  await step('fs', () => api('GET', `/api/fs?path=${encodeURIComponent(path.dirname(repo))}`));
  await step('fs mkdir', () => api('POST', '/api/fs/mkdir', { parent: root, name: 'made' }));
  await step('fs mkdir: bad', () => api('POST', '/api/fs/mkdir', { parent: root, name: '.hidden' }));
  await step('create project', () => api('POST', '/api/projects/create', { name: 'Fresh One' }));
  await step('create project: exists', () => api('POST', '/api/projects/create', { name: 'Fresh One' }).then(async (r) => (fs.writeFileSync(path.join(root, 'projects', 'Fresh One', 'x'), ''), api('POST', '/api/projects/create', { name: 'Fresh One' }))));

  // Notes
  await step('notes, none', () => api('GET', '/api/notes'));
  const note = (await step('note create', () => api('POST', '/api/notes', { title: 'KYC flow: cutting spend' }))).body;
  await step('note create, project', () => api('POST', '/api/notes', { project: project.id, title: 'Plan', text: '# The plan\n\nShip it.\n' }));
  await step('notes, project', () => api('GET', `/api/notes?project=${project.id}`));
  await step('note read', () => api('GET', `/api/notes/text?name=${note.name}`));
  await step('note write', () => api('PUT', '/api/notes/text', { name: note.name, text: '# KYC\n\nNew text.\n' }));
  await step('note write: conflict', () => api('PUT', '/api/notes/text', { name: note.name, text: 'x', mtime: 1 }));
  await step('note include', () => api('PATCH', '/api/notes', { name: note.name, include: true }));
  await step('note bad name', () => api('GET', '/api/notes/text?name=../x'));
  await step('notes, global', () => api('GET', '/api/notes'));

  // A session in the checkout, on the fake Claude.
  const s1 = (await step('session create', () => api('POST', '/api/sessions', { projectId: project.id, prompt: 'hello there' }))).body;
  await until(async () => (await api('GET', `/api/sessions/${s1.id}`)).body.session.status === 'idle');
  await step('session after a turn', () => api('GET', `/api/sessions/${s1.id}`));
  await step('session events', () => api('GET', `/api/sessions/${s1.id}/events`));
  await step('session events after', () => api('GET', `/api/sessions/${s1.id}/events?after=1`));
  await step('session commands', () => api('GET', `/api/sessions/${s1.id}/commands`));
  await step('session paths', () => api('GET', `/api/sessions/${s1.id}/paths?q=read`));
  await step('session routing', () => api('GET', `/api/sessions/${s1.id}/routing`));
  await step('session files stat', () => api('POST', `/api/sessions/${s1.id}/files/stat`, { paths: ['README.md', 'src/index.js', 'nope.txt', '../etc/passwd'] }));
  await step('session file', () => api('GET', `/api/sessions/${s1.id}/files?path=README.md`));
  await step('session file: range', () => fetch(`${srv.base}/api/sessions/${s1.id}/files?path=README.md`, { headers: { range: 'bytes=0-5' } }).then(async (r) => ({ status: r.status, range: r.headers.get('content-range'), body: await r.text() })));
  await step('session file: download', () => api('GET', `/api/sessions/${s1.id}/files?path=README.md&download=1`));
  await step('session file: missing', () => api('GET', `/api/sessions/${s1.id}/files?path=nope`));
  await step('session second message', async () => {
    const r = await api('POST', `/api/sessions/${s1.id}/messages`, { text: 'BIG 3000' });
    await until(async () => (await api('GET', `/api/sessions/${s1.id}`)).body.session.status === 'idle');
    return r;
  });
  await step('session events big (gzip)', () => fetch(`${srv.base}/api/sessions/${s1.id}/events`, { headers: { 'accept-encoding': 'gzip' } }).then((r) => ({ status: r.status, encoding: r.headers.get('content-encoding'), vary: r.headers.get('vary') })));
  await step('session rename', () => api('PATCH', `/api/sessions/${s1.id}`, { title: 'Renamed' }));
  await step('session mode', () => api('PATCH', `/api/sessions/${s1.id}`, { mode: 'plan' }));
  await step('session mode: bad', () => api('PATCH', `/api/sessions/${s1.id}`, { mode: 'wild' }));
  await step('session model', () => api('PATCH', `/api/sessions/${s1.id}`, { modelId: 'anthropic:claude-sonnet-5' }));
  await step('session model: wrong provider', () => api('PATCH', `/api/sessions/${s1.id}`, { modelId: 'deepseek:deepseek-flash' }));
  await step('session limit', () => api('POST', `/api/sessions/${s1.id}/limit`, { autoResume: false }));
  await step('send nothing', () => api('POST', `/api/sessions/${s1.id}/messages`, { text: '  ' }));
  await step('upload', () => fetch(`${srv.base}/api/sessions/${s1.id}/uploads?name=report.txt&type=text/plain`, { method: 'POST', headers: { 'content-type': 'application/octet-stream' }, body: 'hello file' }).then(async (r) => ({ status: r.status, body: await r.json() })));
  await step('upload: wrong type', () => fetch(`${srv.base}/api/sessions/${s1.id}/uploads?name=x`, { method: 'POST', headers: { 'content-type': 'text/plain' }, body: 'x' }).then(async (r) => ({ status: r.status, body: await r.json() })));

  // An approval, answered.
  const s2 = (await step('session asking', () => api('POST', '/api/sessions', { projectId: project.id, prompt: 'ASK Bash' }))).body;
  const appr = await until(async () => (await api('GET', `/api/sessions/${s2.id}`)).body.approvals[0]);
  await step('approval waiting', () => api('GET', `/api/sessions/${s2.id}`));
  await step('approval answer', () => api('POST', `/api/approvals/${appr?.id}`, { allow: true }));
  await step('approval answer again', () => api('POST', `/api/approvals/${appr?.id}`, { allow: true }));
  await until(async () => (await api('GET', `/api/sessions/${s2.id}`)).body.session.status === 'idle');
  await step('after approval', () => api('GET', `/api/sessions/${s2.id}/events`));

  // A task: worktree, a bench tool, the check, the merge.
  const t = (await step('task create', () => api('POST', '/api/tasks', { projectId: project.id, title: 'Add a feature', done: 'It works', prompt: 'MCP task_status' }))).body;
  const tid = t?.task?.id;
  await until(async () => (await api('GET', `/api/sessions/${t.session.id}`)).body.session.status === 'idle');
  await step('task tool reply', () => api('GET', `/api/sessions/${t.session.id}/events`));
  await step('task', () => api('GET', `/api/tasks/${tid}`));
  await step('tasks', () => api('GET', '/api/tasks'));
  await step('task diff, empty', () => api('GET', `/api/tasks/${tid}/diff`));
  fs.writeFileSync(path.join(t.task.worktree, 'feature.js'), 'export const f = 1;\n');
  await step('task git, dirty', () => api('GET', `/api/tasks/${tid}/git`));
  await step('task diff', () => api('GET', `/api/tasks/${tid}/diff`));
  await step('task check suggestions', () => api('GET', `/api/tasks/${tid}/check-suggestions`));
  await step('task check', () => api('POST', `/api/tasks/${tid}/check`, {}));
  await step('task edit', () => api('PATCH', `/api/tasks/${tid}`, { title: 'Add the feature', check: 'node -e "process.exit(3)"' }));
  await step('task check fails', () => api('POST', `/api/tasks/${tid}/check`, {}));
  await step('task loop', () => api('PATCH', `/api/tasks/${tid}`, { loop: { max_attempts: 2 } }));
  await step('task loop off', () => api('PATCH', `/api/tasks/${tid}`, { loop: null }));
  await step('task log', () => api('GET', `/api/tasks/${tid}/log`));
  await step('task commit', () => api('POST', `/api/tasks/${tid}/commit`, { message: 'Add feature.js' }));
  await step('task commit: nothing', () => api('POST', `/api/tasks/${tid}/commit`, {}));
  await step('task update from base', () => api('POST', `/api/tasks/${tid}/update`, {}));
  await step('task merge', () => api('POST', `/api/tasks/${tid}/merge`, { cleanup: true }));
  await step('task after merge', () => api('GET', `/api/tasks/${tid}`));
  await step('task diff after merge', () => api('GET', `/api/tasks/${tid}/diff`));
  await step('task merge again', () => api('POST', `/api/tasks/${tid}/merge`, {}));
  const t2 = (await step('task 2', () => api('POST', '/api/tasks', { projectId: project.id, title: 'Throwaway' }))).body;
  await step('task close', () => api('POST', `/api/tasks/${t2.task.id}/close`, { deleteBranch: false }));
  await step('task reopen', () => api('POST', `/api/tasks/${t2.task.id}/reopen`, {}));
  await step('task 404', () => api('GET', '/api/tasks/nope'));

  // Archive, delete.
  await step('archive', () => api('POST', `/api/sessions/${s2.id}/archive`, {}));
  await step('sessions archived', () => api('GET', `/api/sessions?archived=1`));
  await step('unarchive', () => api('POST', `/api/sessions/${s2.id}/archive`, { archived: false }));
  await step('delete', () => api('DELETE', `/api/sessions/${s2.id}`));
  await step('session 404', () => api('GET', `/api/sessions/${s2.id}`));

  // Ask Bench: no project.
  const ask = (await step('ask session', () => api('POST', '/api/sessions', { prompt: 'MCP overview' }))).body;
  await until(async () => (await api('GET', `/api/sessions/${ask.id}`)).body.session.status === 'idle');
  await step('ask reply', () => api('GET', `/api/sessions/${ask.id}/events`));

  // The rest.
  await step('usage', async () => { const r = await api('GET', '/api/usage'); return { status: r.status, rows: r.body.map((x) => JSON.stringify({ ...x, task_id: !!x.task_id, project_id: !!x.project_id })).sort() }; });
  await step('usage by model', () => api('GET', '/api/usage/models'));
  await step('devices', () => api('GET', '/api/devices'));
  await step('push key', async () => ({ status: (await api('GET', '/api/push/key')).status }));
  await step('push subscribe', () => api('POST', '/api/push/subscribe', { endpoint: 'https://push.example/1', keys: { p256dh: 'x', auth: 'y' } }));
  await step('push choices', () => api('POST', '/api/push/choices', { endpoint: 'https://push.example/1', choices: { finished: false } }));
  await step('push unsubscribe', () => api('POST', '/api/push/unsubscribe', { endpoint: 'https://push.example/1' }));
  await step('pairing', async () => { const r = await api('POST', '/api/pairing', {}); return { status: r.status, keys: Object.keys(r.body) }; });
  await step('pair: bad code', () => api('POST', '/api/pair', { code: 'nope' }));
  await step('server status keys', async () => ({ keys: Object.keys((await api('GET', '/api/server/status')).body).filter((k) => k !== 'server') }));
  await step('metrics keys', async () => { const m = (await api('GET', '/api/metrics')).body; return { keys: Object.keys(m), host: Object.keys(m.host), bench: Object.keys(m.bench).sort() }; });
  await step('update status', async () => { const u = (await api('GET', '/api/server/update')).body; return { keys: Object.keys(u), canUpdate: u.canUpdate }; });
  await step('restart: unsupervised', () => api('POST', '/api/server/restart', {}));
  await step('static index', async () => { const r = await fetch(srv.base + '/'); return { status: r.status, type: r.headers.get('content-type') }; });
  await step('static client route', async () => { const r = await fetch(srv.base + '/p/x/s/y'); return { status: r.status, type: r.headers.get('content-type') }; });
  await step('static missing', async () => ({ status: (await fetch(srv.base + '/nope.js')).status }));
  await step('vendor', async () => { const r = await fetch(srv.base + '/vendor/preact/package.json'); return { status: r.status, cache: r.headers.get('cache-control') }; });

  // The event stream, by type and order, deltas merged.
  ws.close();
  const kinds = [];
  for (const e of events) {
    const k = e.type === 'event' ? `event:${e.event.kind}` : e.type;
    if (k === 'delta' && kinds[kinds.length - 1] === 'delta') continue;
    kinds.push(k);
  }
  out.push({ name: 'event stream (types)', value: [...new Set(kinds)].sort() });
  return out;
}

// --- run both, compare --------------------------------------------------------------------

const base = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-conformance-'));
const results = {};
for (const [kind, port] of [['node', 5521], ['go', 5522]]) {
  const root = path.join(base, kind, 'data');
  const repo = path.join(base, kind, 'repo');
  fs.mkdirSync(root, { recursive: true });
  makeRepo(repo);
  const srv = await start(kind, root, port);
  try {
    const steps = await scenario(srv, repo, root);
    const norm = normaliser(root, repo, path.join(base, kind));
    results[kind] = steps.map((s) => ({ name: s.name, value: norm(s.value) }));
  } finally {
    srv.child.kill('SIGTERM');
    if (process.env.SHOW_LOG) console.log(`--- ${kind} log\n${srv.log()}`);
  }
}

let same = 0;
const diffs = [];
for (let i = 0; i < results.node.length; i++) {
  const a = results.node[i];
  const b = results.go.find((x) => x.name === a.name);
  const ja = JSON.stringify(a.value, null, 1);
  const jb = JSON.stringify(b?.value, null, 1);
  if (ja === jb) same++;
  else diffs.push({ name: a.name, node: ja, go: jb });
}
for (const d of diffs) {
  console.log(`\n✗ ${d.name}`);
  const la = d.node.split('\n');
  const lb = (d.go || 'undefined').split('\n');
  let shown = 0;
  for (let i = 0; i < Math.max(la.length, lb.length) && shown < 12; i++) {
    if (la[i] !== lb[i]) {
      console.log(`  node: ${(la[i] ?? '').trim().slice(0, 160)}\n  go:   ${(lb[i] ?? '').trim().slice(0, 160)}`);
      shown++;
    }
  }
}
console.log(`\n${same} of ${results.node.length} steps match${diffs.length ? `; ${diffs.length} differ` : ''}.`);
fs.writeFileSync(path.join(base, 'results.json'), JSON.stringify(results, null, 1));
console.log(`Full results: ${path.join(base, 'results.json')}`);
if (!keep && !diffs.length) fs.rmSync(base, { recursive: true, force: true });
process.exit(diffs.length ? 1 : 0);
