// The web app's cached transcripts: what is cut from the cached copy, how the tail is fitted
// to a budget, and how a full storage gives way. Run against a stand-in for localStorage
// with a quota, counted in characters as browsers count it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  slimEvent,
  trimTranscript,
  saveTranscript,
  readTranscript,
  forgetTranscript,
  dropTranscript,
  undropTranscript,
  lruOrder,
  eventsKey,
  INDEX_KEY,
  CLIP,
} from '../web/transcript-cache.mjs';

/** localStorage with a quota: a write past it throws, as a browser's does. */
function fakeStore(quota = Infinity) {
  const m = new Map();
  const used = () => [...m].reduce((n, [k, v]) => n + k.length + v.length, 0);
  return {
    get length() {
      return m.size;
    },
    key: (i) => [...m.keys()][i] ?? null,
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem(k, v) {
      const before = m.get(k);
      m.set(k, String(v));
      if (used() > quota) {
        if (before === undefined) m.delete(k);
        else m.set(k, before);
        throw Object.assign(new Error('quota'), { name: 'QuotaExceededError' });
      }
    },
    removeItem: (k) => void m.delete(k),
    map: m,
  };
}

let next = 1;
const ev = (kind, data) => ({ id: next++, session_id: 's', kind, data, created_at: '2026-10-16T00:00:00Z' });
const big = (n, c = 'x') => c.repeat(n);

test('the cached copy leaves out thinking and cuts tool input and output', () => {
  const a = ev('assistant', {
    content: [
      { type: 'thinking', thinking: big(50_000) },
      { type: 'text', text: 'The reply.' },
      { type: 'tool_use', id: 't1', name: 'Write', input: { file_path: '/a', content: big(20_000) } },
    ],
  });
  const slim = slimEvent(a);
  assert.deepEqual(slim.data.content.map((b) => b.type), ['text', 'tool_use']);
  assert.equal(slim.data.content[0].text, 'The reply.');
  assert.equal(slim.data.content[1].input.file_path, '/a');
  assert.equal(slim.data.content[1].input.content.length, CLIP + 1);
  // The pane's own copy is untouched.
  assert.equal(a.data.content.length, 3);
  assert.equal(a.data.content[0].thinking.length, 50_000);

  const r = ev('tool_result', {
    results: [
      { tool_use_id: 't1', is_error: false, content: big(30_000) },
      { tool_use_id: 't2', content: [{ type: 'text', text: 'ok' }, { type: 'image', source: { data: big(90_000) } }] },
    ],
  });
  const rs = slimEvent(r).data.results;
  assert.equal(rs[0].tool_use_id, 't1');
  assert.equal(rs[0].content.length, CLIP + 1);
  assert.equal(rs[1].content, 'ok\n[image]');

  const c = ev('check', { ok: false, command: 'npm test', output: `${big(5_000)}FAILED: thing` });
  const out = slimEvent(c).data.output;
  assert.ok(out.endsWith('FAILED: thing'));
  assert.equal(out.length, CLIP + 1);

  const u = ev('user', { text: 'hello' });
  assert.deepEqual(slimEvent(u), u);
});

test('the tail is fitted to the budget by size, newest kept, order kept', () => {
  const events = Array.from({ length: 200 }, (_, i) => ev('user', { text: `${i}:${big(400)}` }));
  const kept = trimTranscript(events, 10_000);
  assert.ok(JSON.stringify(kept).length <= 10_000);
  assert.ok(kept.length > 10 && kept.length < 200);
  assert.deepEqual(kept.map((e) => e.id), events.slice(-kept.length).map((e) => e.id));
  // Slimming is what lets heavy turns fit: one assistant event with 90 KB of thinking.
  const heavy = [ev('assistant', { content: [{ type: 'thinking', thinking: big(90_000) }, { type: 'text', text: 'hi' }] })];
  assert.equal(trimTranscript(heavy, 10_000).length, 1);
  assert.deepEqual(trimTranscript([], 10_000), []);
});

test('saved and read back; reading counts as use', () => {
  const store = fakeStore();
  const events = [ev('user', { text: 'a' }), ev('assistant', { content: [{ type: 'text', text: 'b' }] })];
  assert.equal(saveTranscript('one', events, { store, now: 1 }), true);
  assert.deepEqual(readTranscript('one', { store, now: 5 }), events);
  assert.deepEqual(JSON.parse(store.getItem(INDEX_KEY)), { one: 5 });
  assert.deepEqual(readTranscript('missing', { store }), []);
  store.setItem(eventsKey('bad'), '{not json');
  assert.deepEqual(readTranscript('bad', { store }), []);
});

test('least recently used first, with unindexed entries oldest', () => {
  const store = fakeStore();
  saveTranscript('a', [ev('user', { text: 'a' })], { store, now: 30 });
  saveTranscript('b', [ev('user', { text: 'b' })], { store, now: 10 });
  saveTranscript('c', [ev('user', { text: 'c' })], { store, now: 20 });
  store.setItem(eventsKey('legacy'), '[]');
  store.setItem('bench:other', '1');
  assert.deepEqual(lruOrder(store), ['legacy', 'b', 'c', 'a']);
  readTranscript('b', { store, now: 40 });
  assert.deepEqual(lruOrder(store), ['legacy', 'c', 'a', 'b']);
});

test('a full storage evicts the least recently used and retries, keeping the rest', () => {
  const store = fakeStore(8_000);
  const turn = (t) => [ev('user', { text: `${t}${big(2_000)}` })];
  saveTranscript('old', turn('o'), { store, now: 1 });
  saveTranscript('mid', turn('m'), { store, now: 2 });
  saveTranscript('new', turn('n'), { store, now: 3 });
  assert.equal(saveTranscript('next', turn('x'), { store, now: 4 }), true);
  assert.equal(store.getItem(eventsKey('old')), null);
  assert.ok(store.getItem(eventsKey('mid')));
  assert.ok(store.getItem(eventsKey('new')));
  assert.equal(readTranscript('next', { store }).length, 1);
  assert.equal(JSON.parse(store.getItem(INDEX_KEY)).old, undefined);
});

test('a write that cannot be made removes the stale copy rather than keeping it', () => {
  const store = fakeStore(3_000);
  saveTranscript('s', [ev('user', { text: 'stale' })], { store, now: 1 });
  // Bigger than the whole quota: nothing can make room for it.
  assert.equal(saveTranscript('s', [ev('user', { text: big(5_000) })], { store, now: 2, budget: 50_000 }), false);
  assert.equal(store.getItem(eventsKey('s')), null);
  assert.deepEqual(readTranscript('s', { store }), []);
});

test('an archived or deleted session is removed and not written back until unarchived', () => {
  const store = fakeStore();
  const events = [ev('user', { text: 'a' })];
  saveTranscript('gone', events, { store, now: 1 });
  saveTranscript('kept', events, { store, now: 1 });
  dropTranscript('gone', { store });
  assert.equal(store.getItem(eventsKey('gone')), null);
  assert.deepEqual(Object.keys(JSON.parse(store.getItem(INDEX_KEY))), ['kept']);
  // The pane closing on the way out flushes what it had.
  assert.equal(saveTranscript('gone', events, { store }), false);
  assert.equal(store.getItem(eventsKey('gone')), null);
  undropTranscript('gone');
  assert.equal(saveTranscript('gone', events, { store }), true);
  forgetTranscript('gone', { store });
  assert.equal(store.getItem(eventsKey('gone')), null);
});

test('no storage at all is not an error', () => {
  assert.equal(saveTranscript('x', [], { store: null }), false);
  assert.deepEqual(readTranscript('x', { store: null }), []);
  forgetTranscript('x', { store: null });
});
