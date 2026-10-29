#!/usr/bin/env node
// Records what the server really answers - /api/state, a session read, and the socket's
// frames - from a Service driven by the test suite's fake Agent SDK, so the native
// clients' tests decode the server's own output rather than shapes typed by hand.
//
//   node apps/fixtures/generate.mjs           write apps/fixtures/*.json
//   node apps/fixtures/generate.mjs --check   exit 1 if they are stale (CI)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Service } from '../../src/service.mjs';
import { fakeQuery } from '../../tests/fake-sdk.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-fixtures-'));
process.env.CLAUDE_CONFIG_DIR = path.join(root, 'claude');
const repo = path.join(root, 'repo');
fs.mkdirSync(repo);
const git = (...a) => execFileSync('git', a, { cwd: repo, stdio: 'pipe' });
git('init', '-q', '-b', 'main');
fs.writeFileSync(path.join(repo, 'f.txt'), 'hi\n');
git('add', '.');
git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'init');

const svc = new Service(root, { query: fakeQuery });
const frames = [];
for (const kind of ['session', 'task', 'event', 'delta', 'approval', 'approval-done', 'suggestion', 'commands']) {
  svc.runtime.on(kind, (d) => frames.push({ kind, d }));
}
const until = async (fn, ms = 5000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (fn()) return; await new Promise((r) => setTimeout(r, 10)); }
  throw new Error('timed out');
};

const project = svc.addProject({ path: repo, name: 'demo' });
const s = svc.createSession({ projectId: project.id, title: 'Checkout session' });
svc.send(s.id, 'hello there');
await until(() => svc.session(s.id).status === 'idle' && svc.events(s.id).some((e) => e.kind === 'result'));
svc.send(s.id, 'suggest run the tests');
await until(() => svc.events(s.id).filter((e) => e.kind === 'result').length === 2);
svc.send(s.id, 'write notes.md some notes');
await until(() => svc.runtime.pending(s.id).length === 1);
const { task, session: ts } = svc.createTask(project.id, { title: 'Fix the thing', prompt: 'ask' });
await until(() => svc.runtime.pending(ts.id).length === 1);

const state = {
  version: '0.0.0',
  projects: svc.store.listProjects(),
  tasks: svc.tasks(),
  sessions: svc.sessions(),
  approvals: svc.runtime.pending(),
  providers: svc.providers(),
  defaults: svc.defaults(),
  me: { local: false, device: { id: 'd1', name: 'Pixel', last_seen_at: null } },
  push: 'BEjj',
};
const detail = { session: svc.session(s.id), events: svc.events(s.id), approvals: svc.runtime.pending(s.id), live: true, commands: svc.runtime.commands(s.id) };
const socket = frames.map(({ kind, d }) => {
  if (kind === 'event') return { type: 'event', event: d };
  if (kind === 'approval') return { type: 'approval', approval: d };
  if (kind === 'session') return { type: 'session', session: d };
  if (kind === 'task') return { type: 'task', task: d };
  return { type: kind, ...d };
});
svc.runtime.closeAll?.();

// Stable across runs: ids, times, paths and costs are the things that change.
const ids = new Map();
const stable = (v) => JSON.parse(JSON.stringify(v), (k, x) => {
  if (typeof x !== 'string') return x;
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-/.test(x) || (/^[0-9a-f]{32,}$/.test(x))) {
    if (!ids.has(x)) ids.set(x, `id-${ids.size + 1}`);
    return ids.get(x);
  }
  if (/^\d{4}-\d\d-\d\dT/.test(x)) return '2026-10-29T12:00:00.000Z';
  return x.split(root).join('/root');
});
const out = { 'state.json': stable(state), 'session.json': stable(detail), 'socket.json': stable(socket) };
const check = process.argv.includes('--check');
let stale = 0;
for (const [name, v] of Object.entries(out)) {
  const file = path.join(here, name);
  const text = JSON.stringify(v, null, 1) + '\n';
  if (check) { if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== text) { console.error(`${name} is stale`); stale++; } }
  else fs.writeFileSync(file, text);
}
fs.rmSync(root, { recursive: true, force: true });
process.exit(stale ? 1 : 0);
