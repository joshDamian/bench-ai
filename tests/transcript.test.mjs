import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeEvents, finishesStream } from '../web/transcript.mjs';

const ev = (id, kind = 'notice', text = '') => ({ id, kind, data: { text } });
const ids = (list) => list.map((e) => e.id);

test('a read that lands after live events keeps them', () => {
  // The server's snapshot ends at 5; reply 6 and its result 7 came over the socket while
  // the answer was on its way.
  const held = [ev(4), ev(5), ev(6, 'assistant'), ev(7, 'result')];
  const fetched = [ev(3), ev(4), ev(5)];
  assert.deepEqual(ids(mergeEvents(held, fetched)), [3, 4, 5, 6, 7]);
});

test('the snapshot is the truth for the ids it holds', () => {
  const held = [ev(1, 'notice', 'stale'), ev(2), ev(9)];
  const fetched = [ev(1, 'notice', 'fresh'), ev(3), ev(4)];
  const merged = mergeEvents(held, fetched);
  assert.deepEqual(ids(merged), [1, 3, 4, 9], 'held 2 is inside the snapshot but not in it, so it is gone; 9 is newer and stays');
  assert.equal(merged[0].data.text, 'fresh');
});

test('the cached tail drawn on open gives way to the server', () => {
  // An old cache: events the server no longer sends (cleared, or before its window)
  // and some it does.
  const cached = [ev(10), ev(11), ev(40), ev(41)];
  const fetched = [ev(40), ev(41), ev(42, 'assistant'), ev(43, 'result')];
  assert.deepEqual(ids(mergeEvents(cached, fetched)), [40, 41, 42, 43]);
});

test('live events are kept in id order, whichever way they arrived', () => {
  const held = [ev(8), ev(6)];
  const fetched = [ev(2), ev(5)];
  assert.deepEqual(ids(mergeEvents(held, fetched)), [2, 5, 6, 8]);
});

test('an empty snapshot leaves the list alone, and does not share it', () => {
  const held = [ev(1), ev(2)];
  const merged = mergeEvents(held, []);
  assert.deepEqual(ids(merged), [1, 2]);
  assert.notEqual(merged, held);
  assert.deepEqual(ids(mergeEvents([], [ev(1), ev(2)])), [1, 2]);
});

test('a read ends the stream only when it holds a newer finished reply', () => {
  const held = [ev(1, 'user'), ev(2, 'assistant'), ev(3, 'tool_result')];
  // The reply to 3 is still streaming: the snapshot has nothing past what is held.
  assert.equal(finishesStream(held, [ev(1, 'user'), ev(2, 'assistant'), ev(3, 'tool_result')]), false);
  // The line dropped while the reply finished: the snapshot has it and the stream is done.
  assert.equal(finishesStream(held, [ev(2, 'assistant'), ev(3, 'tool_result'), ev(4, 'assistant')]), true);
  assert.equal(finishesStream(held, [ev(3, 'tool_result'), ev(4, 'result')]), true);
  assert.equal(finishesStream(held, [ev(3, 'tool_result'), ev(4, 'error')]), true);
  // Something new, but not the end of a reply.
  assert.equal(finishesStream(held, [ev(3, 'tool_result'), ev(4, 'notice')]), false);
  // An older snapshot than the list is never the end of what streams now.
  assert.equal(finishesStream([...held, ev(5, 'assistant'), ev(6, 'tool_result')], [ev(3, 'tool_result'), ev(4, 'assistant')]), false);
});

test('a read of what is after the held events adds to them and takes nothing away', () => {
  // The pane holds the tail; the read asked for `after=5` and came back with the three
  // after it. Nothing behind it gives way: there is no window here to be the truth for.
  const held = [ev(3), ev(4), ev(5)];
  const fetched = [ev(6, 'assistant'), ev(7, 'tool_result'), ev(8)];
  assert.deepEqual(ids(mergeEvents(held, fetched, { suffix: true })), [3, 4, 5, 6, 7, 8]);
  // Without the suffix the same fetch would read as a window starting at 6 and drop the
  // whole held transcript, which is why the read says which it is.
  assert.deepEqual(ids(mergeEvents(held, fetched)), [6, 7, 8]);
});

test('a read of what is after the held events adds each id once', () => {
  // The socket brought 6 while the read was on its way and the read brought it again: the
  // same event, so the one already on screen stands, and 7 comes in behind it.
  const held = [ev(5), ev(6, 'notice', 'from the socket')];
  const merged = mergeEvents(held, [ev(6, 'notice', 'from the read'), ev(7)], { suffix: true });
  assert.deepEqual(ids(merged), [5, 6, 7]);
  assert.equal(merged[1].data.text, 'from the socket');
});

test('a read of what is after the held events leaves the ids it does not cover alone', () => {
  const held = [ev(4, 'notice', 'stale'), ev(5)];
  const merged = mergeEvents(held, [ev(6), ev(7)], { suffix: true });
  assert.deepEqual(ids(merged), [4, 5, 6, 7]);
  assert.equal(merged[0].data.text, 'stale', 'nothing behind the read gives way to it');
});
