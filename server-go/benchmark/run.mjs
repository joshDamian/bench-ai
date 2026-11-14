#!/usr/bin/env node
// Bench vs bench-go: the same load on both servers, and what it cost each.
//
// Two ways to run it:
//
//   node server-go/benchmark/run.mjs
//     Starts both servers itself, each on a fresh data folder with the fake Claude CLI
//     (no model calls, no money), seeds the same data into both, and measures startup,
//     memory, HTTP latency and throughput, session turns and WebSocket fan-out.
//
//   node server-go/benchmark/run.mjs --node-url=http://127.0.0.1:4317 --go-url=http://127.0.0.1:4318 [--node-pid=N --go-pid=N]
//     Measures two servers already running - your real one and bench-go on its copy of
//     the data - with read-only requests only (GETs), so nothing on either changes.
//     With their pids it also reports their memory and CPU.
//
// Options: --duration=<s> per load test (default 5), --out=<file> for the JSON report,
// --sessions=<n> seeded sessions (default 20), --events=<n> turns per seeded session (default 40).
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const fake = path.join(repoRoot, 'server-go', 'conformance', 'fake-claude.mjs');
const goBin = process.env.BENCH_GO_BIN || path.join(repoRoot, 'server-go', 'bin', 'bench-go');
const arg = (name, def) => process.argv.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3) ?? def;
const DURATION = Number(arg('duration', 5)) * 1000;
const SESSIONS = Number(arg('sessions', 20));
const EVENTS = Number(arg('events', 40));
const external = arg('node-url') && arg('go-url');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- process accounting ------------------------------------------------------------------

/** RSS in bytes and CPU seconds (user+system) of one process, from /proc. */
function procStat(pid) {
  try {
    const status = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
    const rss = Number(/VmRSS:\s+(\d+)/.exec(status)?.[1] || 0) * 1024;
    const hwm = Number(/VmHWM:\s+(\d+)/.exec(status)?.[1] || 0) * 1024;
    const threads = Number(/Threads:\s+(\d+)/.exec(status)?.[1] || 0);
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
    const f = stat.slice(stat.lastIndexOf(')') + 2).split(' ');
    const tick = 100;
    return { rss, hwm, threads, cpu: (Number(f[11]) + Number(f[12])) / tick };
  } catch {
    return null;
  }
}

class Sampler {
  constructor(pid) {
    this.pid = pid;
    this.peak = 0;
    this.samples = [];
    this.timer = pid ? setInterval(() => this.take(), 100) : null;
    this.start = pid ? procStat(pid) : null;
  }
  take() {
    const s = procStat(this.pid);
    if (!s) return;
    this.samples.push(s.rss);
    this.peak = Math.max(this.peak, s.rss);
  }
  stop() {
    if (this.timer) clearInterval(this.timer);
    const end = this.pid ? procStat(this.pid) : null;
    if (!end || !this.start) return null;
    const avg = this.samples.length ? this.samples.reduce((a, b) => a + b, 0) / this.samples.length : end.rss;
    return { cpuSeconds: +(end.cpu - this.start.cpu).toFixed(2), rssAvg: Math.round(avg), rssPeak: Math.max(this.peak, end.rss), threads: end.threads };
  }
}

// --- servers -------------------------------------------------------------------------------

function makeRepo(dir, files = 400) {
  fs.mkdirSync(dir, { recursive: true });
  const g = (...a) => execFileSync('git', a, { cwd: dir, stdio: 'pipe' });
  g('init', '-q', '-b', 'main');
  g('config', 'user.email', 'bench@bench');
  g('config', 'user.name', 'Bench');
  for (let i = 0; i < files; i++) {
    const d = path.join(dir, 'src', `mod${i % 20}`);
    fs.mkdirSync(d, { recursive: true });
    fs.writeFileSync(path.join(d, `file${i}.js`), `export const v${i} = ${i};\n`.repeat(20));
  }
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name: 'demo', scripts: { test: 'true' } }));
  g('add', '-A');
  g('commit', '-qm', 'init');
}

async function start(kind, root, port) {
  const env = { ...process.env, BENCH_ROOT: root, PORT: String(port), BENCH_CLAUDE_BIN: fake, BENCH_TMP_ROOTS: path.join(root, 'tmp') };
  fs.mkdirSync(path.join(root, 'tmp'), { recursive: true });
  const t0 = performance.now();
  const child = kind === 'node'
    ? spawn(process.execPath, [path.join(repoRoot, 'src', 'cli.mjs'), 'serve'], { env, stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(goBin, ['serve'], { env, stdio: ['ignore', 'ignore', 'pipe'] });
  let err = '';
  child.stderr.on('data', (d) => (err += d));
  for (let i = 0; i < 400; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/ping`);
      if (r.ok) return { child, pid: child.pid, base: `http://127.0.0.1:${port}`, startupMs: performance.now() - t0 };
    } catch {}
    await sleep(10);
  }
  throw new Error(`${kind} did not start: ${err}`);
}

const json = (base, method, url, body) =>
  fetch(base + url, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined }).then((r) => r.json());

async function waitIdle(base, id, ms = 30000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const s = await json(base, 'GET', `/api/sessions/${id}`);
    if (s.session?.status === 'idle') return;
    await sleep(20);
  }
}

/** The same data in both: a project, a task, sessions with transcripts. */
async function seed(base, repo) {
  const project = await json(base, 'POST', '/api/projects', { path: repo });
  const task = await json(base, 'POST', '/api/tasks', { projectId: project.id, title: 'Seeded task', done: 'It works', check: 'true' });
  const sessions = [];
  for (let i = 0; i < SESSIONS; i++) {
    const s = await json(base, 'POST', '/api/sessions', { projectId: project.id, title: `Seeded ${i}` });
    sessions.push(s.id);
  }
  // Turns, a few sessions at a time.
  for (let i = 0; i < sessions.length; i += 5) {
    await Promise.all(sessions.slice(i, i + 5).map(async (id) => {
      for (let k = 0; k < EVENTS / 4; k++) {
        await json(base, 'POST', `/api/sessions/${id}/messages`, { text: k % 3 === 0 ? 'BIG 2000' : `turn ${k}` });
        await waitIdle(base, id);
      }
    }));
  }
  return { project: project.id, task: task.task.id, sessions };
}

// --- load -----------------------------------------------------------------------------------

function pct(sorted, p) {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

async function load(base, urls, concurrency, duration) {
  const lat = [];
  let errors = 0;
  let bytes = 0;
  const end = performance.now() + duration;
  let n = 0;
  const worker = async () => {
    while (performance.now() < end) {
      const url = urls[n++ % urls.length];
      const t = performance.now();
      try {
        const r = await fetch(base + url, { headers: { 'accept-encoding': 'gzip' } });
        const b = await r.arrayBuffer();
        bytes += b.byteLength;
        if (!r.ok) errors++;
      } catch {
        errors++;
      }
      lat.push(performance.now() - t);
    }
  };
  const t0 = performance.now();
  await Promise.all(Array.from({ length: concurrency }, worker));
  const secs = (performance.now() - t0) / 1000;
  lat.sort((a, b) => a - b);
  return { requests: lat.length, rps: +(lat.length / secs).toFixed(1), p50: +pct(lat, 50).toFixed(2), p95: +pct(lat, 95).toFixed(2), p99: +pct(lat, 99).toFixed(2), max: +(lat[lat.length - 1] || 0).toFixed(2), errors, mb: +(bytes / 1e6).toFixed(1) };
}

/** Time from sending a message to the turn's result arriving on the WebSocket. */
async function turns(base, projectId, parallel, rounds) {
  const ws = new WebSocket(base.replace('http', 'ws') + '/api/ws');
  await new Promise((r) => (ws.onopen = r));
  const ids = [];
  for (let i = 0; i < parallel; i++) ids.push((await json(base, 'POST', '/api/sessions', { projectId, title: `Turns ${i}` })).id);
  ws.send(JSON.stringify({ type: 'watch', ids }));
  const waiting = new Map();
  ws.onmessage = (m) => {
    const e = JSON.parse(m.data);
    if (e.type === 'event' && e.event.kind === 'result') waiting.get(e.event.session_id)?.();
  };
  const lat = [];
  // The first turn in each session starts its process; the rest reuse it.
  const firsts = [];
  for (let r = 0; r < rounds; r++) {
    await Promise.all(ids.map((id) => new Promise((resolve) => {
      const t = performance.now();
      waiting.set(id, () => {
        (r === 0 ? firsts : lat).push(performance.now() - t);
        waiting.delete(id);
        resolve();
      });
      json(base, 'POST', `/api/sessions/${id}/messages`, { text: `round ${r}` });
    })));
  }
  ws.close();
  lat.sort((a, b) => a - b);
  firsts.sort((a, b) => a - b);
  return { sessions: parallel, turns: lat.length + firsts.length, coldP50: +pct(firsts, 50).toFixed(1), warmP50: +pct(lat, 50).toFixed(1), warmP95: +pct(lat, 95).toFixed(1) };
}

/** Many clients watching one session while it streams: how late the last one hears each event. */
async function fanout(base, projectId, clients) {
  const s = await json(base, 'POST', '/api/sessions', { projectId, title: 'Fan-out' });
  const sockets = await Promise.all(Array.from({ length: clients }, () => new Promise((resolve) => {
    const ws = new WebSocket(base.replace('http', 'ws') + '/api/ws');
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'watch', ids: [s.id] }));
      resolve(ws);
    };
  })));
  await sleep(200);
  const got = new Array(clients).fill(0);
  let doneAt = [];
  const t0 = performance.now();
  await new Promise((resolve) => {
    let finished = 0;
    sockets.forEach((ws, i) => {
      ws.onmessage = (m) => {
        const e = JSON.parse(m.data);
        if (e.type === 'delta' || e.type === 'event') got[i]++;
        if (e.type === 'event' && e.event.kind === 'result') {
          doneAt.push(performance.now() - t0);
          if (++finished === clients) resolve();
        }
      };
    });
    json(base, 'POST', `/api/sessions/${s.id}/messages`, { text: 'BIG 200000' });
    setTimeout(resolve, 30000);
  });
  sockets.forEach((ws) => ws.close());
  doneAt.sort((a, b) => a - b);
  return { clients, messagesPerClient: Math.round(got.reduce((a, b) => a + b, 0) / clients), firstDoneMs: +(doneAt[0] || 0).toFixed(1), lastDoneMs: +(doneAt[doneAt.length - 1] || 0).toFixed(1) };
}

// --- the run --------------------------------------------------------------------------------

async function measure(kind, target) {
  const r = { server: kind };
  const { base, pid } = target;
  if (target.startupMs) r.startupMs = +target.startupMs.toFixed(0);
  if (pid) {
    await sleep(500);
    r.idle = procStat(pid);
  }
  const ids = target.ids;
  const read = {
    'GET /api/state': ['/api/state'],
    'GET /api/sessions/:id (transcript)': ids.sessions.map((id) => `/api/sessions/${id}`),
    'GET /api/tasks/:id (git)': [`/api/tasks/${ids.task}`],
    'GET /api/projects/:id/tree': [`/api/projects/${ids.project}/tree`],
    'GET /api/sessions/:id/events?after': ids.sessions.map((id) => `/api/sessions/${id}/events?after=1&limit=50`),
  };
  r.http = {};
  for (const [name, urls] of Object.entries(read)) {
    for (const c of [1, 16]) {
      const sampler = new Sampler(pid);
      const res = await load(base, urls, c, DURATION);
      r.http[`${name} c=${c}`] = { ...res, ...(pid ? { proc: sampler.stop() } : {}) };
      if (!pid) sampler.stop();
    }
  }
  if (!external) {
    let sampler = new Sampler(pid);
    r.turns1 = { ...(await turns(base, ids.project, 1, 20)), proc: sampler.stop() };
    sampler = new Sampler(pid);
    r.turns10 = { ...(await turns(base, ids.project, 10, 5)), proc: sampler.stop() };
    sampler = new Sampler(pid);
    r.fanout = { ...(await fanout(base, ids.project, 50)), proc: sampler.stop() };
  }
  if (pid) r.after = procStat(pid);
  return r;
}

async function externalIds(base) {
  const st = await json(base, 'GET', '/api/state');
  const sessions = st.sessions.slice(0, SESSIONS).map((s) => s.id);
  const task = st.tasks.find((t) => t.state === 'active') || st.tasks[0];
  const project = st.projects[0];
  if (!project || !sessions.length) throw new Error(`${base} has no projects or sessions to read`);
  return { project: project.id, task: task?.id, sessions };
}

const results = [];
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-benchmark-'));
if (external) {
  for (const [kind, url, pid] of [['node', arg('node-url'), arg('node-pid')], ['go', arg('go-url'), arg('go-pid')]]) {
    const ids = await externalIds(url);
    console.log(`Measuring ${kind} at ${url}…`);
    results.push(await measure(kind, { base: url, pid: pid ? Number(pid) : null, ids }));
  }
} else {
  const repo = path.join(tmp, 'repo');
  makeRepo(repo);
  for (const [kind, port] of [['node', 5531], ['go', 5532]]) {
    const root = path.join(tmp, kind);
    fs.mkdirSync(root, { recursive: true });
    const target = await start(kind, root, port);
    try {
      console.log(`Seeding ${kind}: ${SESSIONS} sessions × ${EVENTS / 4} turns…`);
      target.ids = await seed(target.base, repo);
      console.log(`Measuring ${kind}…`);
      results.push(await measure(kind, target));
    } finally {
      target.child.kill('SIGTERM');
      await sleep(500);
    }
  }
}

// --- the report --------------------------------------------------------------------------------

const [n, g] = results;
const mb = (b) => (b == null ? '—' : `${(b / 1024 / 1024).toFixed(1)} MB`);
const ratio = (a, b, higherBetter) => {
  if (!a || !b) return '';
  const x = higherBetter ? b / a : a / b;
  return x >= 1 ? `Go ${x.toFixed(1)}× better` : `Node ${(1 / x).toFixed(1)}× better`;
};
const lines = ['# Bench: Node vs Go', '', `${new Date().toISOString()} · ${os.hostname()} · ${os.cpus().length} CPUs · ${external ? 'existing servers, read-only' : `fresh servers, fake Claude, ${SESSIONS} sessions × ${EVENTS / 4} turns`} · ${DURATION / 1000}s per load test`, ''];
if (!external) lines.push('| | Node | Go | |', '|---|---|---|---|', `| Startup to first answer | ${n.startupMs} ms | ${g.startupMs} ms | ${ratio(n.startupMs, g.startupMs)} |`);
else lines.push('| | Node | Go | |', '|---|---|---|---|');
if (n.idle && g.idle) lines.push(`| Memory at rest (RSS) | ${mb(n.idle.rss)} | ${mb(g.idle.rss)} | ${ratio(n.idle.rss, g.idle.rss)} |`);
if (n.after && g.after) lines.push(`| Memory after the run (RSS) | ${mb(n.after.rss)} | ${mb(g.after.rss)} | ${ratio(n.after.rss, g.after.rss)} |`, `| Peak memory (high-water) | ${mb(n.after.hwm)} | ${mb(g.after.hwm)} | ${ratio(n.after.hwm, g.after.hwm)} |`);
lines.push('', '## HTTP', '', '| Request | Node req/s | Go req/s | Node p50 / p99 ms | Go p50 / p99 ms | Node CPU s | Go CPU s | |', '|---|---|---|---|---|---|---|---|');
for (const k of Object.keys(n.http)) {
  const a = n.http[k];
  const b = g.http[k];
  lines.push(`| ${k} | ${a.rps} | ${b.rps} | ${a.p50} / ${a.p99} | ${b.p50} / ${b.p99} | ${a.proc?.cpuSeconds ?? '—'} | ${b.proc?.cpuSeconds ?? '—'} | ${ratio(a.rps, b.rps, true)}${a.errors || b.errors ? ` · errors ${a.errors}/${b.errors}` : ''} |`);
}
if (!external) {
  lines.push('', '## Sessions (fake Claude: measures the server, not the model)', '', '| | Node | Go | |', '|---|---|---|---|');
  for (const [label, k] of [['1 session, 20 turns', 'turns1'], ['10 sessions at once, 5 turns each', 'turns10']]) {
    lines.push(`| ${label}: first turn (process start) p50 | ${n[k].coldP50} ms | ${g[k].coldP50} ms | ${ratio(n[k].coldP50, g[k].coldP50)} |`);
    lines.push(`| ${label}: later turns p50 / p95 | ${n[k].warmP50} / ${n[k].warmP95} ms | ${g[k].warmP50} / ${g[k].warmP95} ms | ${ratio(n[k].warmP50, g[k].warmP50)} |`);
    lines.push(`| ${label}: server CPU | ${n[k].proc?.cpuSeconds} s | ${g[k].proc?.cpuSeconds} s | ${ratio(n[k].proc?.cpuSeconds, g[k].proc?.cpuSeconds)} |`);
  }
  lines.push(`| 50 clients watching a 200 KB reply: last client done | ${n.fanout.lastDoneMs} ms | ${g.fanout.lastDoneMs} ms | ${ratio(n.fanout.lastDoneMs, g.fanout.lastDoneMs)} |`);
  lines.push(`| … server CPU | ${n.fanout.proc?.cpuSeconds} s | ${g.fanout.proc?.cpuSeconds} s | ${ratio(n.fanout.proc?.cpuSeconds, g.fanout.proc?.cpuSeconds)} |`);
  lines.push(`| … peak RSS during it | ${mb(n.fanout.proc?.rssPeak)} | ${mb(g.fanout.proc?.rssPeak)} | ${ratio(n.fanout.proc?.rssPeak, g.fanout.proc?.rssPeak)} |`);
}
lines.push('', 'Memory and CPU are the server process alone: the Claude Code processes it starts are the same for both and are left out.');
const report = lines.join('\n');
console.log(`\n${report}`);
const out = arg('out', path.join(tmp, 'report.json'));
fs.writeFileSync(out, JSON.stringify({ results, report }, null, 1));
fs.writeFileSync(out.replace(/\.json$/, '.md'), `${report}\n`);
console.log(`\nReport: ${out.replace(/\.json$/, '.md')}`);
