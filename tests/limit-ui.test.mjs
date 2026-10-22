import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatUntil, sessionStatus, pauseState, spendRows, inheritRouting, DEFAULT_ROUTING, PAUSE_RETRY_MS, PAUSE_MAX_TRIES } from '../web/format.mjs';
import { dueAt, RETRY_MS, MAX_TRIES } from '../src/resume.mjs';
import { resolveRouting, DEFAULT_ROUTING as SERVER_ROUTING } from '../src/routing.mjs';

// What the web app says about a paused session, its spend by model, and the routing a
// project or task dialog names as "Default". The browser is not served src/, so these
// mirror the server's own rules; the tests are what keep the two from drifting.

const at = (h, m = 0, day = 22) => new Date(2026, 9, day, h, m).getTime();

test('a reset time reads as the clock today, "Tomorrow" tomorrow, and the date further out', () => {
  const now = at(16);
  assert.equal(formatUntil(new Date(at(17)).toISOString(), now), '17:00');
  assert.equal(formatUntil(new Date(at(23, 59)).toISOString(), now), '23:59');
  assert.equal(formatUntil(new Date(at(9, 5, 23)).toISOString(), now), 'Tomorrow 09:05');
  assert.equal(formatUntil(new Date(at(9, 0, 27)).toISOString(), now), '27 Oct 09:00');
  assert.equal(formatUntil('not a time', now), '—');
});

test('an idle session with a pause is Paused; anything else keeps its own status', () => {
  const pause = { until: null, message: 'limit', at: new Date().toISOString(), tries: 0, handed_to: null };
  assert.equal(sessionStatus({ status: 'idle', pause }), 'paused');
  assert.equal(sessionStatus({ status: 'idle', pause: null }), 'idle');
  // A message sent mid-pause starts a turn before the row's pause is cleared.
  assert.equal(sessionStatus({ status: 'running', pause }), 'running');
  assert.equal(sessionStatus({ status: 'error', pause: null }), 'error');
});

test("the pause bar's timing is the scheduler's own", () => {
  assert.equal(PAUSE_RETRY_MS, RETRY_MS);
  assert.equal(PAUSE_MAX_TRIES, MAX_TRIES);
  const t = '2026-10-22T16:00:00.000Z';
  const until = '2026-10-22T17:00:00.000Z';

  assert.deepEqual(pauseState({ until, message: '', at: t, tries: 0, handed_to: null }, true), { kind: 'until', until, retryAt: null });
  assert.equal(pauseState({ until, message: '', at: t, tries: 0, handed_to: null }, false).kind, 'off');

  // No time Bench could read: it says when it will try, which is when the scheduler sends.
  for (const tries of [0, 1, 2]) {
    const p = { until: null, message: '', at: t, tries, handed_to: null };
    const st = pauseState(p, true);
    assert.equal(st.kind, 'retry');
    assert.equal(Date.parse(st.retryAt), dueAt(p));
  }

  // Handed over, or given up on: the scheduler sends nothing, and the bar says why.
  const handed = { until, message: '', at: t, tries: 0, handed_to: 'abc' };
  assert.equal(dueAt(handed), null);
  assert.equal(pauseState(handed, true).kind, 'handed');
  const stopped = { until: null, message: '', at: t, tries: MAX_TRIES + 1, handed_to: null };
  assert.equal(dueAt(stopped), null);
  assert.equal(pauseState(stopped, true).kind, 'stopped');
});

test('spend by model is most expensive first, and empty when nothing was reported', () => {
  assert.deepEqual(spendRows(null), []);
  const rows = spendRows({
    'claude-haiku-4-5-20251001': { cost: 0.002, input_tokens: 200, output_tokens: 50 },
    'claude-opus-5-5': { cost: 1.25, input_tokens: 9000, output_tokens: 1200 },
  });
  assert.deepEqual(rows.map((r) => r.model), ['claude-opus-5-5', 'claude-haiku-4-5-20251001']);
  assert.equal(rows[1].input_tokens, 200);
});

test("a dialog's Default names what the server would resolve at the level above", () => {
  assert.deepEqual(DEFAULT_ROUTING, SERVER_ROUTING);
  const base = { ...SERVER_ROUTING, explorer: 'sonnet' };
  const project = { explorer: null, implementer: 'opus', architect: null, enabled: null, all: null };
  const task = { all: 'haiku' };
  const strip = ({ from, ...r }) => r;
  assert.deepEqual(inheritRouting(base), strip(resolveRouting(base)));
  assert.deepEqual(inheritRouting(base, project), strip(resolveRouting(base, project)));
  assert.deepEqual(inheritRouting(base, project, task), strip(resolveRouting(base, project, task)));
  assert.deepEqual(inheritRouting(null), SERVER_ROUTING);
});
