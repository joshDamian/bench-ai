// @ts-check
// The transcript as it was when it was last seen, kept per session so reopening one - the
// common case on a phone, where a session is left and returned to all day - draws its
// history at once instead of an empty pane and a spinner. The fetch replaces it either way.
//
// A browser grants a site about 5 MB, and one busy session's tail runs to hundreds of KB,
// most of it thinking and tool output nobody needs for the first draw. So the cached copy
// is slimmed, kept to a budget per session, and the least recently opened sessions give
// way when storage is full. A write that cannot be made removes the old copy rather than
// leaving it to be shown, ever staler, as if it were current.
//
// Nothing here imports the UI, so the tests run it against a stand-in for localStorage.

/** @typedef {import('../src/contract.d.mts').SessionEvent} SessionEvent */
/** @typedef {Pick<Storage, 'getItem'|'setItem'|'removeItem'|'key'|'length'>} Store */

export const PREFIX = 'bench:events:';
/** When each cached session was last read or written, for evicting the least recent. */
export const INDEX_KEY = 'bench:events-index';
/** Characters of JSON per session: enough for a screenful or three of the tail. */
export const BUDGET = 50_000;
/** The longest a tool's input or result is kept; the head says what it was. */
export const CLIP = 1_000;
/** The longest any other text is kept. A person's own long message is still theirs to see. */
const CLIP_TEXT = 8_000;

export const eventsKey = (/** @type {string} */ id) => `${PREFIX}${id}`;

// Sessions archived or deleted while this page is open. A pane closing on one flushes what
// it had, and that write must not bring back what was just removed.
const dropped = new Set();

/** @returns {Store|null} */
function storage() {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null; // some privacy modes throw on the getter itself
  }
}

/** @param {unknown} s @param {number} n */
const clip = (s, n) => (typeof s === 'string' && s.length > n ? `${s.slice(0, n)}…` : s);

/** Every string in a value cut to `n`. @param {any} v @param {number} n @returns {any} */
function clipDeep(v, n) {
  if (typeof v === 'string') return clip(v, n);
  if (Array.isArray(v)) return v.map((x) => clipDeep(x, n));
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, clipDeep(x, n)]));
  return v;
}

/** A tool result's body as text, whatever shape it came in; images are named, not kept. @param {any} c */
function resultBody(c) {
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((b) => (b?.type === 'text' ? b.text : `[${b?.type}]`)).join('\n');
  return c == null ? c : JSON.stringify(c);
}

/**
 * The copy of one event that goes in the cache: thinking left out (it is many times the
 * size of the reply and folded away when drawn), tool inputs and results cut to their
 * head. Only the cache's copy; the event the pane holds is untouched.
 * @param {SessionEvent} e @returns {SessionEvent}
 */
export function slimEvent(e) {
  const d = e.data;
  if (!d || typeof d !== 'object') return e;
  if (e.kind === 'assistant') {
    const content = (d.content || [])
      .filter((/** @type {any} */ b) => b.type !== 'thinking' && b.type !== 'redacted_thinking')
      .map((/** @type {any} */ b) => (b.type === 'tool_use' ? { ...b, input: clipDeep(b.input, CLIP) } : clipDeep(b, CLIP_TEXT)));
    return { ...e, data: { ...d, content } };
  }
  if (e.kind === 'tool_result') {
    const results = (d.results || []).map((/** @type {any} */ r) => ({ ...r, content: clip(resultBody(r.content), CLIP) }));
    return { ...e, data: { ...d, results } };
  }
  // A check's output ends with what failed, so the tail is the part worth keeping.
  if (e.kind === 'check' && typeof d.output === 'string' && d.output.length > CLIP) {
    return { ...e, data: { ...d, output: `…${d.output.slice(-CLIP)}` } };
  }
  return { ...e, data: clipDeep(d, CLIP_TEXT) };
}

/**
 * The slimmed tail of a transcript that fits the budget, oldest first. Sized as the JSON
 * it will be stored as, which is what a browser counts against the quota.
 * @param {SessionEvent[]} events @param {number} [budget] @returns {SessionEvent[]}
 */
export function trimTranscript(events, budget = BUDGET) {
  const out = [];
  let size = 2; // the brackets
  for (let i = events.length - 1; i >= 0; i--) {
    const e = slimEvent(events[i]);
    const n = JSON.stringify(e).length + 1;
    if (size + n > budget) break;
    size += n;
    out.push(e);
  }
  return out.reverse();
}

/** @param {Store} store @returns {Record<string, number>} */
function readIndex(store) {
  try {
    const v = JSON.parse(store.getItem(INDEX_KEY) || '{}');
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  } catch {
    return {};
  }
}

/** @param {Store} store @param {Record<string, number>} index */
function writeIndex(store, index) {
  try {
    store.setItem(INDEX_KEY, JSON.stringify(index));
  } catch {
    /* an index that cannot be kept only makes eviction guess */
  }
}

/** @param {Store} store @param {string} id @param {number|null} at */
function touch(store, id, at) {
  const index = readIndex(store);
  if (at == null) delete index[id];
  else index[id] = at;
  writeIndex(store, index);
}

/**
 * The cached sessions, least recently used first. A cached transcript the index does not
 * know (written before there was one) counts as the oldest.
 * @param {Store} store @returns {string[]}
 */
export function lruOrder(store) {
  const index = readIndex(store);
  const ids = [];
  for (let i = 0; i < store.length; i++) {
    const k = store.key(i);
    if (k?.startsWith(PREFIX)) ids.push(k.slice(PREFIX.length));
  }
  return ids.sort((a, b) => (index[a] ?? 0) - (index[b] ?? 0));
}

/**
 * Keeps a session's transcript. When storage is full the session's old copy goes first,
 * then the least recently used others until there is room for this one, and the write is
 * tried once more. Returns whether it was kept.
 * @param {string} id @param {SessionEvent[]} events
 * @param {{store?: Store|null, budget?: number, now?: number}} [o]
 */
export function saveTranscript(id, events, { store = storage(), budget = BUDGET, now = Date.now() } = {}) {
  if (!store || dropped.has(id)) return false;
  const key = eventsKey(id);
  const text = JSON.stringify(trimTranscript(events, budget));
  try {
    store.setItem(key, text);
    touch(store, id, now);
    return true;
  } catch {
    // Full, most likely. Whatever happens next, the stale copy must not stay.
  }
  forgetTranscript(id, { store });
  let freed = 0;
  for (const other of lruOrder(store)) {
    if (freed >= text.length) break;
    if (other === id) continue;
    freed += (store.getItem(eventsKey(other)) || '').length;
    forgetTranscript(other, { store });
  }
  try {
    store.setItem(key, text);
    touch(store, id, now);
    return true;
  } catch {
    return false;
  }
}

/**
 * The cached transcript, or [] when there is none; reading it counts as using it.
 * @param {string} id @param {{store?: Store|null, now?: number}} [o] @returns {SessionEvent[]}
 */
export function readTranscript(id, { store = storage(), now = Date.now() } = {}) {
  if (!store) return [];
  try {
    const v = JSON.parse(store.getItem(eventsKey(id)) || 'null');
    if (!Array.isArray(v)) return [];
    touch(store, id, now);
    return v;
  } catch {
    return [];
  }
}

/** Drops a session's cached transcript: archived, deleted, or past keeping. @param {string} id @param {{store?: Store|null}} [o] */
export function forgetTranscript(id, { store = storage() } = {}) {
  if (!store) return;
  try {
    store.removeItem(eventsKey(id));
  } catch {
    /* nothing to free */
  }
  touch(store, id, null);
}

/** A session archived or deleted: its copy removed, and not written again while it stays so. @param {string} id @param {{store?: Store|null}} [o] */
export function dropTranscript(id, o) {
  dropped.add(id);
  forgetTranscript(id, o);
}

/** A session unarchived: it may be cached again. @param {string} id */
export function undropTranscript(id) {
  dropped.delete(id);
}
