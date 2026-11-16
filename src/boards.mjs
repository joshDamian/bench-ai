// @ts-check
// Living results: a schedule that keeps one board instead of a new report every run. The
// board lives in the schedule's project, boards/<name>.md (or .html when it needs sorting or
// ticking off), so the project's file viewer serves it. Each run updates it in place and
// marks what is new, changed or gone since the last run; the version before each run is
// kept in boards/.history/<name>/ (the last ten).
import fs from 'node:fs';
import path from 'node:path';
import { slug, titleOf as noteTitle } from './notes.mjs';

const KEEP = 10;

/** @param {string} projectPath @param {string} title */
export function boardBase(projectPath, title) {
  return path.join(projectPath, 'boards', slug(title));
}

/** The board's file, if it has been written. @param {string} projectPath @param {string} title */
export function findBoard(projectPath, title) {
  for (const ext of ['.md', '.html']) {
    const f = boardBase(projectPath, title) + ext;
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/** @param {string} projectPath @param {string} title */
function historyDir(projectPath, title) {
  return path.join(projectPath, 'boards', '.history', slug(title));
}

/** Keeps the board as it was before a run, and only the last ten. @param {string} projectPath @param {string} title */
export function snapshotBoard(projectPath, title) {
  const f = findBoard(projectPath, title);
  if (!f) return;
  const dir = historyDir(projectPath, title);
  fs.mkdirSync(dir, { recursive: true });
  const stamp = fs.statSync(f).mtime.toISOString().slice(0, 16).replace(':', '-');
  fs.copyFileSync(f, path.join(dir, stamp + path.extname(f)));
  const old = fs.readdirSync(dir).sort().reverse().slice(KEEP);
  for (const n of old) fs.rmSync(path.join(dir, n), { force: true });
}

/** Earlier versions, newest first. @param {string} projectPath @param {string} title */
export function boardHistory(projectPath, title) {
  try {
    return fs.readdirSync(historyDir(projectPath, title)).sort().reverse();
  } catch {
    return [];
  }
}

/** How many entries the last run marked new. @param {string} full */
export function newCount(full) {
  let text = '';
  try {
    text = fs.readFileSync(full, 'utf8');
  } catch {
    return 0;
  }
  return (full.endsWith('.html') ? text.match(/data-status=["']new["']/gi) : text.match(/·\s*New\s*$/gm))?.length || 0;
}

/** The board's title: its first heading, else the schedule's. @param {string} full @param {string} fallback */
export function boardTitle(full, fallback) {
  try {
    const head = fs.readFileSync(full, 'utf8').slice(0, 20000);
    if (full.endsWith('.html')) return head.match(/<title>([^<]+)<\/title>/i)?.[1]?.trim() || fallback;
    return noteTitle(head, fallback) || fallback;
  } catch {
    return fallback;
  }
}

/** What a run of a board-keeping schedule is told. @param {string} projectPath @param {string} title */
export function BOARD_NOTE(projectPath, title) {
  const base = boardBase(projectPath, title);
  return `Keep one board for this, not a new report: ${base}.md (or ${base}.html when it needs sorting or ticking off; keep using the same file every run). If it exists, update it in place: first remove the marks from the last run and drop entries marked Gone, then add what is new, update what changed, and mark them at the end of their line - " · New", " · Changed: what changed", " · Gone" for what is no longer there (in HTML, data-status="new", "changed" or "gone" on the entry). Start the file with a "# " heading and a line "Updated" with today's date. Do not write a separate report, and give the board's path in your reply.`;
}
