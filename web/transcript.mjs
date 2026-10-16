// Folding a fetched transcript into the one already on screen. The fetch is a snapshot
// the server took before it answered, and a long session's answer takes a while to
// arrive; events pushed over the socket in that time are newer than the snapshot and
// must survive it, or a reply vanishes from the page until the next reload.

// The kinds that end what the streaming text was building.
const FINISHING = new Set(['assistant', 'result', 'error']);

/**
 * The snapshot is the truth for every id it covers: an id in it is taken from it, and a
 * held event inside its range that it lacks is gone. Held events newer than its last id
 * arrived while it was in flight and are kept; held events older than its first id that
 * it lacks are dropped too, so the cached tail drawn on open gives way to the server's.
 * Ordered by id.
 * @param {{id:number}[]} held
 * @param {{id:number}[]} fetched
 */
export function mergeEvents(held, fetched) {
  if (!fetched.length) return held.slice();
  const last = fetched[fetched.length - 1].id;
  const newer = held.filter((e) => e.id > last);
  return [...fetched, ...newer].sort((a, b) => a.id - b.id);
}

/**
 * Whether the snapshot holds the end of the reply now streaming: a finished assistant,
 * result or error newer than anything already held. Anything older was on screen when
 * the stream began, so the stream is not its text.
 * @param {{id:number}[]} held
 * @param {{id:number, kind:string}[]} fetched
 */
export function finishesStream(held, fetched) {
  const newest = held.reduce((n, e) => Math.max(n, e.id), -Infinity);
  return fetched.some((e) => e.id > newest && FINISHING.has(e.kind));
}
