import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLimit, limitFromEvent, isLimitText } from '../src/limits.mjs';

// Each fixture is a message Claude Code sent a Bench session, and the time the transcript
// recorded it - the first six exactly as they were stored in a real Bench database.
const FIXTURES = [
  { text: "You've hit your session limit · resets 5pm (UTC)", at: '2026-10-22T16:14:22.184Z', want: '2026-10-22T17:00:00.000Z', limit: 'session limit' },
  { text: "You've hit your session limit · resets 4pm (UTC)", at: '2026-10-21T12:30:04.977Z', want: '2026-10-21T16:00:00.000Z', limit: 'session limit' },
  // Said before midnight, about a time after it: tomorrow.
  { text: "You've hit your session limit · resets 1:10am (UTC)", at: '2026-10-19T22:53:01.164Z', want: '2026-10-20T01:10:00.000Z', limit: 'session limit' },
  { text: "You've hit your session limit · resets 8:10pm (UTC)", at: '2026-10-19T17:15:51.884Z', want: '2026-10-19T20:10:00.000Z', limit: 'session limit' },
  { text: "You've hit your session limit · resets 1:50am (UTC)", at: '2026-10-17T00:38:30.804Z', want: '2026-10-17T01:50:00.000Z', limit: 'session limit' },
  { text: "You've hit your session limit · resets 12:10pm (UTC)", at: '2026-10-16T11:00:00.000Z', want: '2026-10-16T12:10:00.000Z', limit: 'session limit' },
  // The forms Claude Code's formatter makes for other zones and for more than a day away.
  { text: "You've hit your session limit · resets 2am (Europe/London)", at: '2026-07-01T22:00:00.000Z', want: '2026-07-02T01:00:00.000Z', limit: 'session limit' },
  { text: "You've hit your session limit · resets 9:30pm (America/New_York)", at: '2026-01-15T20:00:00.000Z', want: '2026-01-16T02:30:00.000Z', limit: 'session limit' },
  { text: "You've hit your weekly limit · resets Oct 25, 5pm (UTC)", at: '2026-10-22T12:00:00.000Z', want: '2026-10-25T17:00:00.000Z', limit: 'weekly limit' },
  { text: "You've hit your Opus limit · resets Oct 28, 9:30am (Asia/Tokyo) · progress saved", at: '2026-10-22T12:00:00.000Z', want: '2026-10-28T00:30:00.000Z', limit: 'Opus limit' },
  // A date in the new year, said in December, with and without its year.
  { text: "You've hit your weekly limit · resets Jan 2, 5pm (UTC)", at: '2026-12-30T12:00:00.000Z', want: '2027-01-02T17:00:00.000Z', limit: 'weekly limit' },
  { text: "You've hit your weekly limit · resets Jan 2, 2027, 5pm (UTC)", at: '2026-12-30T12:00:00.000Z', want: '2027-01-02T17:00:00.000Z', limit: 'weekly limit' },
  // Across a daylight-saving change: London is on GMT again by Nov 1.
  { text: "You've hit your weekly limit · resets Nov 1, 9am (Europe/London)", at: '2026-10-22T12:00:00.000Z', want: '2026-11-01T09:00:00.000Z', limit: 'weekly limit' },
];

for (const f of FIXTURES) {
  test(`reads "${f.text}"`, () => {
    const r = parseLimit(f.text, new Date(f.at));
    assert.ok(r, 'it is a limit message');
    assert.equal(r.limit, f.limit);
    assert.equal(r.resetsAt?.toISOString(), f.want);
  });
}

test('a reset happening as the message is read is not pushed to tomorrow', () => {
  const r = parseLimit("You've hit your session limit · resets 5pm (UTC)", new Date('2026-10-22T17:00:30Z'));
  assert.equal(r?.resetsAt?.toISOString(), '2026-10-22T17:00:00.000Z');
});

test('a limit with no time it can read is still a limit, with no reset time', () => {
  const r = parseLimit("You've hit your monthly spend limit. Raise it at claude.ai/settings", new Date());
  assert.ok(r);
  assert.equal(r.resetsAt, null);
  const odd = parseLimit("You've hit your session limit · resets 5pm (Mars/Olympus_Mons)", new Date());
  assert.ok(odd);
  assert.equal(odd.resetsAt, null);
});

test('other errors are not limits', () => {
  for (const t of ['API Error: 402 Insufficient Balance (request_id: 954fd200)', 'API Error: 429 Too Many Requests', 'echo: hello', '', "You've used 90% of your session limit"]) {
    assert.equal(parseLimit(t), null, t);
    assert.equal(isLimitText(t), false, t);
  }
});

test('a rate_limit_event that refused a request gives the exact reset time', () => {
  assert.equal(limitFromEvent({ status: 'rejected', resetsAt: 1792000000, rateLimitType: 'five_hour' })?.toISOString(), new Date(1792000000 * 1000).toISOString());
  assert.equal(limitFromEvent({ status: 'allowed_warning', resetsAt: 1792000000 }), null);
  assert.equal(limitFromEvent({ status: 'rejected' }), null);
  assert.equal(limitFromEvent(null), null);
});
