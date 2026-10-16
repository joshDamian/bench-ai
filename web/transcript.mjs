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
 *
 * `suffix` says the snapshot is a read of what came after the held events - `?after=`, for
 * a pane holding the tail - rather than a window with a start of its own. Nothing held
 * gives way to it then: it only has ids the pane is missing (the socket may have brought
 * some of them already), and everything held stays where it is.
 * @param {{id:number}[]} held
 * @param {{id:number}[]} fetched
 * @param {{suffix?:boolean}} [o]
 */
export function mergeEvents(held, fetched, o = {}) {
  if (!fetched.length) return held.slice();
  if (o.suffix) {
    const have = new Set(held.map((e) => e.id));
    const add = fetched.filter((e) => !have.has(e.id));
    return add.length ? [...held, ...add].sort((a, b) => a.id - b.id) : held.slice();
  }
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
