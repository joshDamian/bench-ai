// @ts-check
// The Claude login's usage limit: telling a turn that ended on it from one that failed,
// and when it lifts.
//
// Claude Code ends such a turn with a synthetic assistant message - `error: 'rate_limit'`,
// model `<synthetic>` - whose text is built from the limit's name and its reset time:
//
//   You've hit your session limit · resets 5pm (UTC)
//   You've hit your session limit · resets 1:10am (Europe/London)
//   You've hit your weekly limit · resets Oct 25, 5pm (UTC)
//   You've hit your Opus limit · resets Jan 2, 2027, 9:30am (America/New_York)
//
// The time is wall-clock time in the zone named in brackets (an IANA name, or UTC). Within a
// day it has no date, so "1:10am" said at 10:53pm is tomorrow. Beyond a day it carries a
// month and day, and a year when that differs from this one. The same turn usually carries a
// `rate_limit_event` whose `resetsAt` is exact (seconds since the epoch); that is preferred,
// and the text is what is read when it is missing.

/** How Claude Code begins a message that means a usage limit was reached (USAGE_LIMIT_ERROR_PREFIXES). */
const LIMIT_PREFIXES = ["You've hit your", "You've reached your", "You're out of usage credits", "You're out of extra usage"];

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

const RESETS = /resets (?:at )?(?:([A-Za-z]{3})[a-z]* (\d{1,2}),? (?:(\d{4}),? )?(?:at )?)?(\d{1,2})(?::(\d{2}))?\s?([ap]m)(?: \(([^)]+)\))?/i;

/** @param {string} text */
export const isLimitText = (text) => LIMIT_PREFIXES.some((p) => String(text || '').trim().startsWith(p));

/**
 * The offset of `zone` from UTC at `at`, in minutes. Null for a zone this runtime does not know.
 * @param {string|undefined} zone @param {number} at
 */
function offsetMinutes(zone, at) {
  if (!zone) return -new Date(at).getTimezoneOffset();
  let parts;
  try {
    parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(new Date(at));
  } catch {
    return null;
  }
  const n = (/** @type {string} */ t) => Number(parts.find((p) => p.type === t)?.value);
  const wall = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour') % 24, n('minute'), n('second'));
  return Math.round((wall - Math.floor(at / 1000) * 1000) / 60000);
}

/**
 * The instant a wall-clock time in `zone` names. Two passes, so a time near a daylight-saving
 * change lands on the offset in force at that time rather than at the guess.
 * @param {number} y @param {number} mo month, 0-based @param {number} d @param {number} h @param {number} mi @param {string|undefined} zone
 */
function zonedTime(y, mo, d, h, mi, zone) {
  const wall = Date.UTC(y, mo, d, h, mi);
  let at = wall;
  for (let i = 0; i < 2; i++) {
    const off = offsetMinutes(zone, at);
    if (off === null) return null;
    at = wall - off * 60000;
  }
  return at;
}

/** Today's date in `zone`, at `at`. @param {string|undefined} zone @param {number} at */
function dateIn(zone, at) {
  const off = offsetMinutes(zone, at) ?? 0;
  const d = new Date(at + off * 60000);
  return { y: d.getUTCFullYear(), mo: d.getUTCMonth(), d: d.getUTCDate() };
}

/**
 * Reads a usage-limit message. Null when the text is not one. `resetsAt` is null when the
 * message names no time this can read - a monthly cap, or a time in a zone it does not know.
 * @param {string} text what Claude Code said
 * @param {Date} [now] when it said it: a time with no date is the next one after this
 * @returns {{message:string, limit:string|null, resetsAt:Date|null}|null}
 */
export function parseLimit(text, now = new Date()) {
  const message = String(text || '').trim();
  if (!isLimitText(message)) return null;
  const limit = /^You've (?:hit|reached) your (.+?)(?: ·|\.|$)/.exec(message)?.[1] || null;
  const m = RESETS.exec(message);
  if (!m) return { message, limit, resetsAt: null };
  const [, mon, day, year, hour, minute, ampm, zoneText] = m;
  const zone = !zoneText || /^utc$/i.test(zoneText) ? (zoneText ? 'UTC' : undefined) : zoneText;
  const h = (Number(hour) % 12) + (ampm.toLowerCase() === 'pm' ? 12 : 0);
  const mi = Number(minute || 0);
  const t = now.getTime();
  const today = dateIn(zone, t);
  let at;
  if (mon) {
    const mo = MONTHS.indexOf(mon.toLowerCase());
    if (mo < 0) return { message, limit, resetsAt: null };
    at = zonedTime(year ? Number(year) : today.y, mo, Number(day), h, mi, zone);
    // "Jan 2" said in December is next year's.
    if (at !== null && !year && at < t - 86400000) at = zonedTime(today.y + 1, mo, Number(day), h, mi, zone);
  } else {
    at = zonedTime(today.y, today.mo, today.d, h, mi, zone);
    // A time with no date that has passed today is tomorrow's. A minute's grace for a reset
    // that is happening as the message is read.
    if (at !== null && at < t - 60000) at = zonedTime(today.y, today.mo, today.d + 1, h, mi, zone);
  }
  return { message, limit, resetsAt: at === null ? null : new Date(at) };
}

/**
 * The reset time in a `rate_limit_event`, when it says the limit refused a request.
 * @param {any} info the event's rate_limit_info
 * @returns {Date|null}
 */
export function limitFromEvent(info) {
  if (!info || info.status !== 'rejected') return null;
  const s = info.resetsAt ?? info.overageResetsAt;
  return typeof s === 'number' && s > 0 ? new Date(s * 1000) : null;
}
