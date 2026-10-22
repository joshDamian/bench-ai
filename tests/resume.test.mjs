import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Service } from '../src/service.mjs';
import { RESUME_TEXT, RESUME_BUFFER_MS, MAX_TRIES } from '../src/resume.mjs';
import { fakeQuery, fakeLimit, calls } from './fake-sdk.mjs';
import { tmp } from './tmp.mjs';

// The usage limit and what follows it: a session paused until the limit lifts, resumed by the
// scheduler at that time, staggered, backed off when the limit is still there, and found again
// after a restart. And subagent routing: the tiers each session is offered, and their models.
//
// The scheduler's clock is replaced, so a test moves time on and calls tick() rather than
// waiting hours; its real timer still runs, but every decision reads the test's clock.

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

let clock = Date.parse('2026-10-22T16:00:00Z');

/** @param {string} [root] */
function setup(root = tmp('resume')) {
  process.env.BENCH_WORKTREE_ROOT = path.join(root, 'worktrees');
  process.env.CLAUDE_CONFIG_DIR = path.join(root, 'claude');
  const svc = new Service(root, { query: fakeQuery });
  svc.resumer.now = () => clock;
  const project = svc.store.listProjects()[0] || svc.addProject({ path: repo() });
  return { svc, project, root };
}

async function until(fn, ms = 5000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (fn()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('timed out');
}

const sent = (svc, id) => svc.events(id).filter((e) => e.kind === 'user').map((e) => e.data.text);
const idle = (svc, id) => () => svc.session(id).status === 'idle';

/** A session that has just hit the limit, resetting an hour from the test clock. */
async function limited(svc, project, o = {}) {
  fakeLimit.resetsAt = Math.floor((clock + 3600 * 1000) / 1000);
  const s = svc.createSession({ projectId: project.id, taskId: o.taskId, prompt: 'limit' });
  await until(() => svc.session(s.id).pause && idle(svc, s.id)());
  return svc.session(s.id);
}

test('a turn the usage limit ends pauses the session until it resets, and the scheduler resumes it then', async () => {
  const { svc, project } = setup();
  const s = await limited(svc, project);
  assert.equal(s.status, 'idle');
  assert.equal(s.pause.until, new Date(clock + 3600 * 1000).toISOString(), "the rate_limit_event's exact time");
  assert.match(s.pause.message, /session limit/);
  assert.ok(svc.events(s.id).some((e) => e.kind === 'notice' && /Paused/.test(e.data.text)));

  // Before the reset and its buffer, nothing is sent.
  clock += 3600 * 1000;
  svc.resumer.tick();
  assert.deepEqual(sent(svc, s.id), ['limit']);

  clock += RESUME_BUFFER_MS;
  svc.resumer.tick();
  assert.deepEqual(sent(svc, s.id), ['limit', RESUME_TEXT]);
  await until(idle(svc, s.id));
  assert.equal(svc.session(s.id).pause, null);
  assert.equal(svc.resumer.next(), null, 'nothing left to resume');
  svc.shutdown();
});

test('several sessions due at once are resumed a stagger apart, soonest first', async () => {
  const { svc, project } = setup();
  const a = await limited(svc, project);
  clock += 1000;
  const b = await limited(svc, project);
  clock += 2 * 3600 * 1000;
  svc.resumer.tick();
  assert.deepEqual(sent(svc, a.id).slice(1), [RESUME_TEXT]);
  assert.deepEqual(sent(svc, b.id).slice(1), [], 'the second waits for the stagger');
  svc.resumer.tick();
  assert.deepEqual(sent(svc, b.id).slice(1), []);
  clock += svc.resumer.stagger;
  svc.resumer.tick();
  assert.deepEqual(sent(svc, b.id).slice(1), [RESUME_TEXT]);
  svc.shutdown();
});

test('a resume that hits the limit again goes back to paused, and Bench gives up after a few', async () => {
  const { svc, project } = setup();
  const s = await limited(svc, project);
  fakeLimit.on = true;
  try {
    for (let i = 1; i <= MAX_TRIES + 1; i++) {
      // Still refused with the same, now past, reset time: no time to trust, so it backs off.
      clock = Date.parse(svc.session(s.id).pause.until || svc.session(s.id).pause.at) + 6 * 3600 * 1000;
      svc.resumer.tick();
      await until(() => sent(svc, s.id).length === i + 1);
      await until(() => svc.session(s.id).pause?.tries === i && idle(svc, s.id)());
    }
    assert.equal(svc.session(s.id).pause.tries, MAX_TRIES + 1);
    assert.equal(svc.resumer.next(), null, 'it stops trying');
    assert.ok(svc.events(s.id).some((e) => e.kind === 'notice' && /stopped resuming/.test(e.data.text)));
  } finally {
    fakeLimit.on = false;
  }
  // A message from the person takes it off pause.
  svc.send(s.id, 'hello');
  await until(idle(svc, s.id));
  assert.equal(svc.session(s.id).pause, null);
  svc.shutdown();
});

test('a paused session and its resume survive a restart', async () => {
  const first = setup();
  const s = await limited(first.svc, first.project);
  first.svc.shutdown();
  const { svc } = setup(first.root);
  assert.ok(svc.session(s.id).pause, 'still paused after the restart');
  assert.equal(svc.resumer.next(), Date.parse(svc.session(s.id).pause.until) + RESUME_BUFFER_MS);
  clock += 2 * 3600 * 1000;
  svc.resumer.tick();
  assert.deepEqual(sent(svc, s.id), ['limit', RESUME_TEXT]);
  await until(idle(svc, s.id));
  svc.shutdown();
});

test('auto-resume can be turned off for one session, or for all', async () => {
  const { svc, project } = setup();
  const s = await limited(svc, project);
  svc.sessionLimit(s.id, { autoResume: false });
  assert.equal(svc.resumer.next(), null);
  svc.sessionLimit(s.id, { autoResume: null });
  assert.ok(svc.resumer.next());
  svc.setDefaults({ limits: { autoResume: false } });
  assert.equal(svc.defaults().limits.autoResume, false);
  assert.equal(svc.resumer.next(), null);
  svc.sessionLimit(s.id, { autoResume: true });
  assert.ok(svc.resumer.next(), "a session's own switch outranks the default");
  // Resume now, whatever the clock says.
  svc.sessionLimit(s.id, { resumeNow: true });
  assert.deepEqual(sent(svc, s.id), ['limit', RESUME_TEXT]);
  await until(idle(svc, s.id));
  svc.shutdown();
});

test('a loop waits through a pause instead of ending in an error', async () => {
  const { svc, project } = setup();
  const { task } = svc.createTask(project.id, { title: 'Loop me', check: 'false' });
  const s = await limited(svc, project, { taskId: task.id });
  svc.editTask(task.id, { loop: { max_attempts: 3 } });
  await new Promise((r) => setTimeout(r, 100));
  const loop = svc.task(task.id).loop;
  assert.equal(loop.state, 'looping');
  assert.equal(loop.attempts, 0, 'nothing is sent to a paused session');
  assert.deepEqual(sent(svc, s.id), ['limit']);
  svc.editTask(task.id, { loop: null });
  svc.shutdown();
});

test('with a fallback provider, the work is handed to a new session there instead of waiting', async () => {
  const { svc, project } = setup();
  process.env.DEEPSEEK_API_KEY = 'test-key';
  try {
    svc.updateProvider('deepseek-claude-code', { enabled: true });
    assert.throws(() => svc.setDefaults({ limits: { fallback: { providerId: 'anthropic-claude-code' } } }), /another provider/);
    svc.setDefaults({ limits: { fallback: { providerId: 'deepseek-claude-code', modelId: 'deepseek:deepseek-flash' } } });
    const { task } = svc.createTask(project.id, { title: 'Ship the parser', done: 'tests pass' });
    const s = await limited(svc, project, { taskId: task.id });
    const next = svc.session(s.pause.handed_to);
    assert.equal(next.provider_id, 'deepseek-claude-code');
    assert.equal(next.task_id, task.id);
    const prompt = sent(svc, next.id)[0];
    assert.match(prompt, /usage limit/);
    assert.match(prompt, /Ship the parser/);
    assert.match(prompt, /Done means: tests pass/);
    assert.equal(svc.resumer.next(), null, 'the paused one is not resumed as well');
    await until(idle(svc, next.id));
  } finally {
    delete process.env.DEEPSEEK_API_KEY;
    svc.shutdown();
  }
});

test('project sessions are offered the three tiers, with models from the default, the project and the task', async () => {
  const { svc, project } = setup();
  const optionsOf = async (s) => {
    svc.send(s.id, 'hi');
    await until(idle(svc, s.id));
    const o = calls.findLast((c) => c.options.cwd === svc.session(s.id).cwd).options;
    svc.runtime.close(s.id);
    return o;
  };
  const plain = svc.createSession({ projectId: project.id });
  let o = await optionsOf(plain);
  assert.deepEqual(Object.keys(o.agents), ['explorer', 'implementer', 'architect']);
  assert.equal(o.agents.explorer.model, 'haiku');
  assert.equal(o.agents.implementer.model, 'sonnet');
  assert.equal(o.agents.architect.model, 'inherit');
  assert.deepEqual(o.agents.explorer.disallowedTools, ['Edit', 'Write', 'NotebookEdit']);
  assert.match(o.systemPrompt.append, /bench:routing/);
  assert.equal(o.env.CLAUDE_CODE_SUBAGENT_MODEL, undefined);

  svc.setProjectRouting(project.id, { implementer: 'opus' });
  const { task, session } = svc.createTask(project.id, { title: 'Routed' });
  svc.editTask(task.id, { routing: { explorer: 'claude-haiku-4-5-20251001' } });
  o = await optionsOf(session);
  assert.equal(o.agents.explorer.model, 'claude-haiku-4-5-20251001');
  assert.equal(o.agents.implementer.model, 'opus');
  const r = svc.sessionRouting(session.id);
  assert.deepEqual(r.from, { enabled: 'default', explorer: 'task', implementer: 'project', architect: 'default', all: 'default' });

  // "All subagents" goes to Claude Code itself.
  svc.setDefaults({ routing: { all: 'sonnet' } });
  o = await optionsOf(plain);
  assert.equal(o.env.CLAUDE_CODE_SUBAGENT_MODEL, 'sonnet');

  // Off for this task: no tiers and no note.
  svc.editTask(task.id, { routing: { enabled: false } });
  o = await optionsOf(session);
  assert.equal(o.agents, undefined);
  assert.doesNotMatch(o.systemPrompt.append, /bench:routing/);
  assert.equal(o.env.CLAUDE_CODE_SUBAGENT_MODEL, undefined);

  // Clearing an override hands it back to the level above.
  svc.editTask(task.id, { routing: null });
  assert.equal(svc.task(task.id).routing, null);
  assert.throws(() => svc.setDefaults({ routing: { explorer: 'two words' } }), /name a model/);
  svc.shutdown();
});

test("spend is kept per model, a subagent's model included", async () => {
  const { svc, project } = setup();
  const { task, session } = svc.createTask(project.id, { title: 'Spend', prompt: 'delegate' });
  await until(() => svc.session(session.id).model_usage?.['claude-haiku-4-5-20251001']);
  const u = svc.session(session.id).model_usage;
  assert.deepEqual(u['claude-haiku-4-5-20251001'], { cost: 0.002, input_tokens: 300, output_tokens: 50 });
  assert.ok(u.m.cost > 0);
  const byModel = svc.usageByModel({ taskId: task.id });
  assert.deepEqual(byModel.map((r) => r.model).sort(), ['claude-haiku-4-5-20251001', 'm']);
  assert.equal(svc.usageByModel({ projectId: project.id }).find((r) => r.model === 'claude-haiku-4-5-20251001').cost, 0.002);
  svc.shutdown();
});
