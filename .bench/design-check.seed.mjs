// Fills a fresh Bench with the work a typical day leaves behind, so the design gate and
// the screenshots see the real views with real data rather than an empty app.
//
//   node .bench/design-check.seed.mjs <url> <folder>
//   node .bench/design-check.seed.mjs <url> --pick     ids from a Bench already running
//
// <url> is a Bench started with BENCH_FAKE_SDK=1 on a data folder of its own; <folder> is
// where the seeded projects' repositories are made. It talks to the server over loopback,
// which needs no pairing - the browser the gate drives is loopback too, so it is this
// machine's own browser and sees every view. Never point it at a Bench you use: it adds
// projects. Prints the ids the routes are made from, as JSON, on its last line.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [url, folder] = process.argv.slice(2);
if (!url || !folder) {
  console.error('usage: node .bench/design-check.seed.mjs <url> <folder|--pick>');
  process.exit(2);
}

/** @param {string} method @param {string} p @param {unknown} [body] */
async function call(method, p, body) {
  const res = await fetch(new URL(p, url), { method, headers: body === undefined ? {} : { 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`${method} ${p}: ${res.status} ${data?.error || ''}`);
  return data;
}
const sleep = (/** @type {number} */ ms) => new Promise((r) => setTimeout(r, ms));

// A Bench that was already running is gated as it is: nothing is added to it, and the
// routes are made from the work it already has.
if (folder === '--pick') {
  const s = await call('GET', '/api/state');
  const task = s.tasks.find((/** @type {any} */ t) => t.state === 'active' && s.sessions.some((/** @type {any} */ x) => x.task_id === t.id)) || s.tasks[0];
  const project = task ? task.project_id : s.projects[0]?.id;
  const checkout = s.sessions.find((/** @type {any} */ x) => !x.task_id && x.project_id === project) || s.sessions.find((/** @type {any} */ x) => !x.task_id);
  const ids = { project, task: task?.id, taskSession: s.sessions.find((/** @type {any} */ x) => x.task_id === task?.id)?.id, session: checkout?.id };
  const missing = Object.entries(ids).filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) {
    console.error(`The running Bench has no ${missing.join(', ')} to gate. Stop it and let the gate start a seeded one.`);
    process.exit(2);
  }
  console.log(JSON.stringify(ids));
  process.exit(0);
}
/** Waits until the session's turn has ended, or a question is waiting on the person. */
async function settle(/** @type {string} */ sid) {
  for (let i = 0; i < 100; i++) {
    const { session } = await call('GET', `/api/sessions/${sid}`);
    if (session.status !== 'running') return session;
    await sleep(50);
  }
  throw new Error(`session ${sid} never settled`);
}
async function say(/** @type {string} */ sid, /** @type {string} */ text) {
  await call('POST', `/api/sessions/${sid}/messages`, { text });
  return settle(sid);
}
/** Answers every question the session has waiting with yes. */
async function allow(/** @type {string} */ sid) {
  const { approvals } = await call('GET', '/api/state');
  for (const a of approvals.filter((/** @type {any} */ x) => x.session_id === sid)) await call('POST', `/api/approvals/${a.id}`, { allow: true });
  return settle(sid);
}

/** A repository with a few files and a short history. @param {string} name @param {Record<string,string>} files */
function repo(name, files) {
  const dir = path.join(folder, name);
  fs.mkdirSync(dir, { recursive: true });
  const git = (/** @type {string[]} */ ...a) => execFileSync('git', ['-c', 'user.email=dev@example.com', '-c', 'user.name=Sam Rivera', '-c', 'init.defaultBranch=main', ...a], { cwd: dir, stdio: 'pipe' });
  git('init', '-q');
  let n = 0;
  for (const [file, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
    fs.writeFileSync(path.join(dir, file), text);
    git('add', '-A');
    git('commit', '-q', '-m', n++ === 0 ? 'Initial commit' : `Add ${file}`);
  }
  return dir;
}

const projects = [
  {
    name: 'ledger-api',
    files: {
      'README.md': '# ledger-api\n\nThe accounts service: balances, transfers and the nightly reconciliation.\n',
      'src/transfer.js': "export function transfer(from, to, amount) {\n  if (amount <= 0) throw new Error('amount must be positive');\n  from.balance -= amount;\n  to.balance += amount;\n  return { from, to };\n}\n",
      'src/reconcile.js': 'export const reconcile = (rows) => rows.filter((r) => r.delta !== 0);\n',
    },
    tasks: [
      { title: 'Reject transfers that would overdraw an account', prompt: 'Transfers can take an account below zero. Reject them with a clear error and add a test.', write: ['src/transfer.js', "export function transfer(from, to, amount) {\n  if (amount <= 0) throw new Error('amount must be positive');\n  if (from.balance < amount) throw new Error('insufficient funds');\n  from.balance -= amount;\n  to.balance += amount;\n  return { from, to };\n}\n"] },
      { title: 'Speed up the nightly reconciliation', prompt: 'The nightly reconciliation takes 40 minutes on the full ledger. Find out where the time goes.' },
      { title: 'Add idempotency keys to POST /transfers', prompt: 'A retried request creates a second transfer. Add idempotency keys.', waiting: 'src/idempotency.js' },
    ],
    checkout: ['Why does the CI cache miss on every run?'],
  },
  {
    name: 'field-notes',
    files: {
      'README.md': '# field-notes\n\nA small notes app for the phone: capture, tag, search.\n',
      'app/search.ts': 'export const search = (notes: string[], q: string) => notes.filter((n) => n.includes(q));\n',
    },
    tasks: [
      { title: 'Search should ignore accents and case', prompt: 'Searching "cafe" should find "Café". Make search accent- and case-insensitive.', write: ['app/search.ts', "const fold = (s: string) => s.normalize('NFD').replace(/\\p{M}/gu, '').toLowerCase();\nexport const search = (notes: string[], q: string) => notes.filter((n) => fold(n).includes(fold(q)));\n"] },
      { title: 'Offline drafts are lost on reload', prompt: 'A note typed offline disappears if the page reloads before it syncs.' },
    ],
    checkout: [],
  },
  {
    name: 'website',
    files: { 'README.md': '# website\n\nThe marketing site.\n', 'index.html': '<h1>Hello</h1>\n' },
    tasks: [{ title: 'Pricing page copy for the October launch', prompt: 'Rewrite the pricing page for the three new plans.' }],
    checkout: ['Check the sitemap for broken links'],
  },
  { name: 'infra', files: { 'README.md': '# infra\n\nTerraform for the VPS and the backups.\n' }, tasks: [], checkout: [] },
];

const ids = { project: '', task: '', taskSession: '', session: '', emptyProject: '' };
for (const p of projects) {
  const project = await call('POST', '/api/projects', { path: repo(p.name, p.files), name: p.name });
  if (!ids.project) ids.project = project.id;
  if (!p.tasks.length && !p.checkout.length) ids.emptyProject = project.id;
  for (const t of p.tasks) {
    const { task, session } = await call('POST', '/api/tasks', { projectId: project.id, title: t.title, prompt: t.prompt });
    await settle(session.id);
    if (t.write) {
      await say(session.id, 'I looked at how it works today. Making the change now, then I will run the tests.');
      await call('POST', `/api/sessions/${session.id}/messages`, { text: `write ${t.write[0]} ${t.write[1]}` });
      await sleep(150);
      await allow(session.id);
      await say(session.id, 'The tests pass. The change is ready for you to look at.');
    }
    if (t.waiting) await call('POST', `/api/sessions/${session.id}/messages`, { text: `write ${t.waiting} export const seen = new Map();` });
    if (!ids.task) {
      ids.task = task.id;
      ids.taskSession = session.id;
    }
  }
  for (const title of p.checkout) {
    const s = await call('POST', '/api/sessions', { projectId: project.id, title, prompt: title });
    await settle(s.id);
    await say(s.id, 'Thanks - can you also say which job is slowest?');
    if (!ids.session) ids.session = s.id;
  }
}
await sleep(300);
console.log(JSON.stringify(ids));
