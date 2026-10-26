// @ts-check
// The directions chosen across every project on this machine, so the next one does not
// converge on the last. One JSON line per choice, under Bench's data folder.
//
//   node directions.mjs list [--last 12]          what was chosen recently, newest first
//   node directions.mjs add --project <name> --direction "<one line>" [--palette ..] [--type ..] [--motion ..] [--signature ..]
//
// The folder is $BENCH_DATA_DIR, else $XDG_DATA_HOME/bench, else ~/.local/share/bench.

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const FIELDS = ['project', 'direction', 'palette', 'type', 'motion', 'signature'];

export function historyFile() {
  const dir = process.env.BENCH_DATA_DIR || path.join(process.env.XDG_DATA_HOME || path.join(os.homedir(), '.local', 'share'), 'bench');
  return path.join(dir, 'design-directions.jsonl');
}

/** @param {number} last */
export function list(last = 12) {
  let text = '';
  try {
    text = fs.readFileSync(historyFile(), 'utf8');
  } catch {
    return [];
  }
  /** @type {any[]} */
  const rows = [];
  for (const line of text.split('\n')) {
    try {
      if (line.trim()) rows.push(JSON.parse(line));
    } catch {
      /* a torn line is skipped, not fatal */
    }
  }
  return rows.reverse().slice(0, last);
}

/** @param {Record<string, string>} entry */
export function add(entry) {
  const row = { date: new Date().toISOString().slice(0, 10), ...Object.fromEntries(FIELDS.filter((f) => entry[f]).map((f) => [f, String(entry[f])])) };
  fs.mkdirSync(path.dirname(historyFile()), { recursive: true });
  fs.appendFileSync(historyFile(), `${JSON.stringify(row)}\n`);
  return row;
}

function cli() {
  const [cmd, ...rest] = process.argv.slice(2);
  /** @type {Record<string, string>} */
  const o = {};
  for (let i = 0; i < rest.length; i++) if (rest[i].startsWith('--')) o[rest[i].slice(2)] = rest[++i] ?? '';
  if (cmd === 'list') {
    const rows = list(Number(o.last) || 12);
    if (!rows.length) process.stdout.write(`No directions recorded yet (${historyFile()}).\n`);
    for (const r of rows) process.stdout.write(`${r.date}  ${r.project || '?'}: ${r.direction}${r.palette ? ` | palette: ${r.palette}` : ''}${r.type ? ` | type: ${r.type}` : ''}${r.motion ? ` | motion: ${r.motion}` : ''}${r.signature ? ` | signature: ${r.signature}` : ''}\n`);
    return 0;
  }
  if (cmd === 'add') {
    if (!o.project || !o.direction) {
      process.stdout.write('add needs --project and --direction\n');
      return 2;
    }
    process.stdout.write(`recorded in ${historyFile()}: ${JSON.stringify(add(o))}\n`);
    return 0;
  }
  process.stdout.write('usage: directions.mjs list [--last n] | add --project <name> --direction "<line>" [--palette] [--type] [--motion] [--signature]\n');
  return 2;
}

process.exitCode = cli();
