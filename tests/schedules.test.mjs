import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextRun, due } from '../src/schedules.mjs';
import { kindOf } from '../src/reports.mjs';

const monday8 = { enabled: true, days: '1', time: '08:00', tz: 'Africa/Lagos', last_run_at: null };

test('a schedule runs next at its time in its own zone', () => {
  assert.equal(nextRun(monday8, new Date('2026-11-14T19:00:00Z'))?.toISOString(), '2026-11-16T07:00:00.000Z');
  assert.equal(nextRun({ ...monday8, tz: 'America/New_York' }, new Date('2026-11-14T19:00:00Z'))?.toISOString(), '2026-11-16T13:00:00.000Z');
  assert.equal(nextRun({ ...monday8, enabled: false }), null);
});

test('a schedule is due from its time onwards, once a day', () => {
  assert.equal(due(monday8, new Date('2026-11-16T06:55:00Z')), false);
  assert.equal(due(monday8, new Date('2026-11-16T07:05:00Z')), true);
  assert.equal(due({ ...monday8, last_run_at: '2026-11-16T07:01:00Z' }, new Date('2026-11-16T09:00:00Z')), false);
  assert.equal(due(monday8, new Date('2026-11-17T07:05:00Z')), false, 'not on a day it does not list');
});

test('reports are told apart by their file names', () => {
  assert.equal(kindOf('jobs.drafts.json'), 'drafts');
  assert.equal(kindOf('board.html'), 'page');
  assert.equal(kindOf('hackathons.md'), 'report');
  assert.equal(kindOf('data.csv'), 'file');
});
