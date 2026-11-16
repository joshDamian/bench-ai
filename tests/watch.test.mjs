import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { Service } from '../src/service.mjs';
import { pageText, addedLines, isNothingNew, watchFields } from '../src/watch.mjs';
import { nextRun, due, checkDue } from '../src/schedules.mjs';
import { fakeQuery } from './fake-sdk.mjs';
import { tmp } from './tmp.mjs';

async function until(fn, ms = 3000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await fn()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error('timed out');
}

test('a page is read as its text, and a change as the lines it added', () => {
  const t = pageText('<html><head><style>a{}</style><script>x()</script></head><body><h1>Jobs</h1><ul><li>Designer, Lagos</li><li>PM &amp; Ops</li></ul></body></html>');
  assert.equal(t, 'Jobs\nDesigner, Lagos\nPM & Ops');
  assert.equal(addedLines(t, t + '\nEngineer, Lagos'), 'Engineer, Lagos\n');
  assert.ok(isNothingNew(' Nothing new.\n'));
  assert.ok(!isNothingNew('Nothing new. But one thing'));
  assert.throws(() => watchFields({ url: 'jobs page' }), /web address/);
  assert.deepEqual(watchFields({ url: 'https://example.com/jobs', everyHours: 2 }), { url: 'https://example.com/jobs', every_hours: 2 });
});

test('a watch runs on its page, not on a clock', () => {
  const w = { enabled: true, days: '', time: '00:00', tz: 'UTC', last_run_at: null, watch: { url: 'https://x.test', every_hours: 6 }, checked_at: null };
  assert.equal(nextRun(w), null);
  assert.equal(due(w), false);
  assert.equal(checkDue(w), true);
  assert.equal(checkDue({ ...w, checked_at: new Date(Date.now() - 3600_000).toISOString() }), false);
  assert.equal(checkDue({ ...w, checked_at: new Date(Date.now() - 7 * 3600_000).toISOString() }), true);
});

test('a change starts a run; a run with nothing that matters is put away quietly', async () => {
  let body = '<ul><li>Designer, Lagos</li></ul>';
  const server = http.createServer((_, res) => res.end(body));
  await new Promise((r) => server.listen(0, '127.0.0.1', () => r(null)));
  const url = `http://127.0.0.1:${/** @type {any} */ (server.address()).port}/jobs`;
  const root = tmp('root');
  process.env.CLAUDE_CONFIG_DIR = path.join(root, 'claude');
  const svc = new Service(root, { query: fakeQuery });
  try {
    const dir = tmp('repo');
    execFileSync('git', ['init', '-q', dir]);
    const project = svc.addProject({ path: dir });
    assert.throws(() => svc.addSchedule({ title: 'Jobs', projectId: project.id, prompt: 'x', watch: { url: 'nope' } }), /web address/);
    // The fake fetches with a plain http address, which the field check takes.
    const sch = svc.addSchedule({ title: 'Lagos roles', projectId: project.id, prompt: 'Tell me when a role in Lagos is posted', watch: { url, everyHours: 1 } });
    assert.equal(sch.days, '');
    assert.equal(sch.next_run_at, null);
    assert.deepEqual(sch.watch, { url, every_hours: 1 });
    assert.equal('watch_hash' in sch, false);

    // The first look only keeps the page.
    await svc.scheduler.look(svc.store.getSchedule(sch.id));
    assert.equal(svc.store.sessionsOfSchedule(sch.id).length, 0);
    assert.ok(svc.store.getSchedule(sch.id).checked_at);
    // No change, no run.
    await svc.scheduler.look(svc.store.getSchedule(sch.id));
    assert.equal(svc.store.sessionsOfSchedule(sch.id).length, 0);

    body = '<ul><li>Designer, Lagos</li><li>Engineer, Lagos</li></ul>';
    await svc.scheduler.look(svc.store.getSchedule(sch.id));
    const [run] = svc.store.sessionsOfSchedule(sch.id);
    assert.ok(run);
    await until(() => svc.store.getSession(run.id).status === 'idle');
    const sent = svc.events(run.id).find((e) => e.kind === 'user')?.data;
    assert.match(JSON.stringify(sent), /Engineer, Lagos/);
    assert.equal(svc.store.getSession(run.id).archived_at, null, 'a reply that says something stays');

    // A run that finds nothing goes to the archive, read.
    svc.updateSchedule(sch.id, { prompt: 'Tell me [quiet]' });
    body = '<ul><li>Designer, Lagos</li><li>Intern, Abuja</li></ul>';
    await svc.scheduler.look(svc.store.getSchedule(sch.id));
    const quiet = svc.store.sessionsOfSchedule(sch.id).find((s) => s.id !== run.id);
    assert.ok(quiet);
    await until(() => !!svc.store.getSession(quiet.id).archived_at);
    const q = svc.store.getSession(quiet.id);
    assert.ok(q.seen_at >= q.updated_at);

    // A new page starts again from a first look.
    const moved = svc.updateSchedule(sch.id, { watch: { url: url + '?b', everyHours: 1 } });
    assert.equal(moved.checked_at, null);
    // Unwatched, it needs a time again.
    assert.throws(() => svc.updateSchedule(sch.id, { watch: null, days: [1] , time: 'soon' }), /HH:MM/);
    svc.deleteSchedule(sch.id);
    assert.equal(fs.existsSync(path.join(root, '.bench', 'watches', `${sch.id}.txt`)), false);
  } finally {
    server.close();
    await svc.close?.();
  }
});
