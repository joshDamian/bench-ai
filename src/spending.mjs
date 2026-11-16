// @ts-check
// Spending: caps for what runs while the person is not watching - the sessions a schedule
// or a watcher starts. A daily cap stops new unattended runs for the day; a cap per run
// stops one run (Claude Code's own budget for a query) and asks the person whether to go
// on. One switch pauses every unattended run. Runs the person starts are not capped.
import fs from 'node:fs';
import path from 'node:path';

const DEFAULTS = { daily_cap: 5, run_cap: 2, paused: false };

export class Spending {
  /** @param {string} dataDir */
  constructor(dataDir) {
    this.file = path.join(dataDir, 'spending.json');
  }

  /** @returns {{daily_cap:number, run_cap:number, paused:boolean}} */
  settings() {
    try {
      return { ...DEFAULTS, ...JSON.parse(fs.readFileSync(this.file, 'utf8')) };
    } catch {
      return { ...DEFAULTS };
    }
  }

  /** @param {{daily_cap?:unknown, run_cap?:unknown, paused?:unknown}} o */
  set(o) {
    const next = this.settings();
    for (const k of /** @type {const} */ (['daily_cap', 'run_cap'])) {
      if (o[k] === undefined) continue;
      const n = Number(o[k]);
      if (!Number.isFinite(n) || n <= 0 || n > 1000) throw Object.assign(new Error('A cap is between $0 and $1,000'), { status: 400 });
      next[k] = Math.round(n * 100) / 100;
    }
    if (o.paused !== undefined) next.paused = !!o.paused;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify(next));
    return next;
  }
}

/** The start of today on the server's clock, as the store writes times. @param {Date} [at] */
export function startOfDay(at = new Date()) {
  const d = new Date(at);
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}
