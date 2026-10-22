// @ts-check
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// What a session leaves in the system's temporary folder. A session's TMPDIR is its own
// scratch folder, which goes when the session is archived; this is for everything that
// lands in /tmp anyway - a path typed out by the model, a tool that ignores TMPDIR, a
// download, a browser profile. Bench looks at /tmp's top level either side of each tool
// call and records what appeared against the session that ran it. Nothing here asks the
// model to remember anything.
//
// The record is of candidates, not of proof: two sessions running tools at once may each
// be credited with what either made. So nothing is removed while any process has it open,
// and only top-level entries of a temporary folder are ever removed.

/** The temporary folders watched. BENCH_TMP_ROOTS (colon-separated) replaces them, for tests. */
export function tmpRoots() {
  const set = process.env.BENCH_TMP_ROOTS;
  const roots = set ? set.split(':').filter(Boolean) : ['/tmp', os.tmpdir()];
  return [...new Set(roots.map((r) => path.resolve(r)))].filter((r) => {
    try {
      return fs.statSync(r).isDirectory();
    } catch {
      return false;
    }
  });
}

// Shared by everything on the machine, or the system's own: never a session's to clean.
const SHARED = [/^\./, /^claude-\d+$/, /^systemd-private-/, /^snap-private-tmp$/, /^tmux-\d+$/, /^ssh-/, /^node-compile-cache$/, /^v8-compile-cache/, /^hsperfdata_/, /^pulse-/, /^dbus-/];

/** Whether a top-level name is one a session can own. @param {string} name */
export const ownable = (name) => !SHARED.some((re) => re.test(name));

/** The top-level entries of the temporary folders, as full paths. @returns {Set<string>} */
export function snapshot() {
  const out = new Set();
  for (const root of tmpRoots()) {
    /** @type {string[]} */
    let names = [];
    try {
      names = fs.readdirSync(root);
    } catch {
      continue;
    }
    for (const n of names) if (ownable(n)) out.add(path.join(root, n));
  }
  return out;
}

/** What is in `after` and not in `before`. @param {Set<string>} before @param {Set<string>} after */
export function appeared(before, after) {
  return [...after].filter((p) => !before.has(p));
}

/** Whether `p` is a top-level entry of a watched temporary folder - the only thing removed. @param {string} p */
export function removable(p) {
  const r = path.resolve(p);
  return tmpRoots().includes(path.dirname(r)) && ownable(path.basename(r));
}

/**
 * The processes holding anything under each path open - a file descriptor or a working
 * directory inside it. Null when this system cannot say (no /proc), which is read as
 * "maybe": nothing is removed on a guess.
 * @param {string[]} paths @returns {Map<string, number[]>|null}
 */
export function holders(paths) {
  if (!fs.existsSync('/proc/self/fd')) return null;
  /** @type {Map<string, number[]>} */
  const out = new Map(paths.map((p) => [p, []]));
  const under = (/** @type {string} */ target) => paths.filter((p) => target === p || target.startsWith(`${p}/`));
  /** @type {string[]} */
  let pids = [];
  try {
    pids = fs.readdirSync('/proc').filter((n) => /^\d+$/.test(n));
  } catch {
    return null;
  }
  for (const pid of pids) {
    const seen = new Set();
    /** @param {string} link */
    const look = (link) => {
      let target;
      try {
        target = fs.readlinkSync(link);
      } catch {
        return;
      }
      for (const p of under(target.replace(/ \(deleted\)$/, ''))) seen.add(p);
    };
    look(`/proc/${pid}/cwd`);
    /** @type {string[]} */
    let fds = [];
    try {
      fds = fs.readdirSync(`/proc/${pid}/fd`);
    } catch {
      /* another user's process, or gone */
    }
    for (const fd of fds) look(`/proc/${pid}/fd/${fd}`);
    for (const p of seen) out.get(p)?.push(Number(pid));
  }
  return out;
}

/**
 * The bytes under a path, stopping once it has counted `cap` entries, so one huge folder
 * cannot stall a listing. `partial` says it stopped early.
 * @param {string} p @param {number} [cap]
 */
export function sizeOf(p, cap = 20000) {
  let bytes = 0;
  let n = 0;
  const stack = [p];
  while (stack.length) {
    const cur = /** @type {string} */ (stack.pop());
    let st;
    try {
      st = fs.lstatSync(cur);
    } catch {
      continue;
    }
    bytes += st.size;
    if (++n >= cap) return { bytes, partial: true };
    if (st.isDirectory()) {
      try {
        for (const name of fs.readdirSync(cur)) stack.push(path.join(cur, name));
      } catch {
        /* unreadable: counted as its own size */
      }
    }
  }
  return { bytes, partial: false };
}
