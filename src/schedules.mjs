// @ts-check
// Schedules: sessions that start on their own, on chosen weekdays at a time of day in the
// person's time zone ("every Monday at 8:00, look for new hackathons"). A schedule runs at
// most once a day; one missed while the server was down runs when it comes back, the same
// day only, so a weekend away does not set off a week of runs at once.

/** @typedef {{id:string, title:string, project_id:string, prompt:string, days:string, time:string, tz:string, mode:string|null, model_id:string|null, provider_id:string|null, effort:string|null, enabled:boolean, last_run_at:string|null, created_at:string}} Schedule */

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** A moment's wall-clock parts in a time zone. @param {Date} at @param {string} tz */
export function localParts(at, tz) {
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short', hourCycle: 'h23' });
  /** @type {Record<string, string>} */
  const p = {};
  for (const x of f.formatToParts(at)) p[x.type] = x.value;
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}`, weekday: WEEKDAYS.indexOf(p.weekday), y: +p.year, m: +p.month, d: +p.day };
}

/** Whether a time zone name is one this machine knows. @param {string} tz */
export function validZone(tz) {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** The UTC instant of a wall-clock time in a zone. @param {number} y @param {number} m @param {number} d @param {string} hhmm @param {string} tz */
function instant(y, m, d, hhmm, tz) {
  const [hh, mm] = hhmm.split(':').map(Number);
  const guess = Date.UTC(y, m - 1, d, hh, mm);
  // The zone's offset at that moment: the guess read back in the zone, minus the guess.
  const back = localParts(new Date(guess), tz);
  const [bh, bm] = back.time.split(':').map(Number);
  const offset = Date.UTC(back.y, back.m - 1, back.d, bh, bm) - guess;
  return new Date(guess - offset);
}

/** @param {Schedule} s */
const daysOf = (s) => s.days.split(',').map((x) => Number(x.trim())).filter((n) => n >= 0 && n <= 6);

/** When a schedule next runs, or null when it is off or has no days. @param {Schedule} s @param {Date} [from] */
export function nextRun(s, from = new Date()) {
  if (!s.enabled) return null;
  const days = daysOf(s);
  if (!days.length) return null;
  const today = localParts(from, s.tz);
  for (let i = 0; i < 8; i++) {
    const day = new Date(Date.UTC(today.y, today.m - 1, today.d + i, 12));
    const wd = day.getUTCDay();
    if (!days.includes(wd)) continue;
    const at = instant(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate(), s.time, s.tz);
    const ranToday = i === 0 && s.last_run_at && localParts(new Date(s.last_run_at), s.tz).date === today.date;
    if (at > from && !ranToday) return at;
  }
  return null;
}

/** Whether a schedule should start a session now. @param {Schedule} s @param {Date} [at] */
export function due(s, at = new Date()) {
  if (!s.enabled) return false;
  const now = localParts(at, s.tz);
  if (!daysOf(s).includes(now.weekday) || now.time < s.time) return false;
  return !s.last_run_at || localParts(new Date(s.last_run_at), s.tz).date !== now.date;
}

/** A schedule as the apps read it: the row and when it runs next. @param {Schedule} s */
export const scheduleView = (s) => ({ ...s, next_run_at: nextRun(s)?.toISOString() ?? null });

/** Checks the schedules every half minute and starts what is due. */
export class Scheduler {
  /** @param {import('./service.mjs').Service} svc */
  constructor(svc) {
    this.svc = svc;
    /** @type {NodeJS.Timeout|null} */
    this.timer = null;
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), 30_000);
    this.timer.unref();
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  tick(at = new Date()) {
    for (const s of this.svc.store.listSchedules()) {
      if (!due(s, at)) continue;
      // Paused: it waits, and runs later today if unpaused. Over the day's cap: skipped for today.
      const may = this.svc.unattended();
      if (!may.ok) {
        if (may.paused) continue;
        this.svc.store.updateSchedule(s.id, { last_run_at: at.toISOString() });
        console.error(`schedule ${s.title}: skipped, ${may.reason}`);
        continue;
      }
      try {
        this.svc.runSchedule(s.id);
      } catch (e) {
        // A schedule that cannot start (its project gone, its provider not set up) is marked
        // run for today, so it does not retry every half minute; the log says why.
        this.svc.store.updateSchedule(s.id, { last_run_at: at.toISOString() });
        console.error(`schedule ${s.title}: ${/** @type {Error} */ (e).message}`);
      }
    }
  }
}
