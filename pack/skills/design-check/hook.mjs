// @ts-check
// The design hook: after a session edits a UI file, read what the edit wrote against the
// static rules and tell the session what it finds - in the same turn, before it builds on it.
//
// Claude Code runs it on PostToolUse for Edit, Write and MultiEdit (the pack's
// hooks/hooks.json), with the tool call as JSON on stdin. It is quiet when the edit is
// clean, and quiet on anything that is not UI. It reads only the lines the edit wrote, so a
// file's old habits are not re-reported on every change; design-check and design-review
// see the whole page.
//
// Off for a project: `"hook": false` in .bench/design-check.json. Off for a run: BENCH_DESIGN_HOOK=0.
// Per rule: `"ignoreRules": ["glass"]` (reflex and finish rules only), `"ignoreFiles": ["src/legacy/**"]`,
// and reflex waivers with their reasons under `## Waivers` in Design.md.
//
// It never fails the edit. Anything it cannot do - unreadable input, a file gone, the time
// budget spent - ends it silently with exit 0.

import fs from 'node:fs';
import path from 'node:path';
import { scanText, isUiFile, uiFiles, CSS_EXT } from './static.mjs';
import { readIgnores, readWaivers } from './rules.mjs';

/** How long the hook may take, all in, before it gives up and says nothing. */
const BUDGET_MS = Number(process.env.BENCH_DESIGN_HOOK_BUDGET_MS) || 1500;
const started = Date.now();
setTimeout(() => process.exit(0), BUDGET_MS).unref();

/** @returns {Promise<string>} */
function stdin() {
  return new Promise((resolve) => {
    let s = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (c) => (s += c));
    process.stdin.on('end', () => resolve(s));
    process.stdin.on('error', () => resolve(''));
  });
}

/** A glob as a regular expression: `**` any depth, `*` within a folder, `?` one character. @param {string} glob */
function globRe(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*' && glob[i + 1] === '*') {
      re += '.*';
      i++;
      if (glob[i + 1] === '/') i++;
    } else if (c === '*') re += '[^/]*';
    else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

/** The project a file belongs to: the nearest folder above it with a .bench or a .git. @param {string} file @param {string} cwd */
function projectRoot(file, cwd) {
  for (let dir = path.dirname(file); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, '.bench')) || fs.existsSync(path.join(dir, '.git'))) return dir;
    if (dir === path.dirname(dir)) return cwd;
  }
}

/**
 * The line numbers (1-based) an edit changed, wherever its new text sits in the file: the
 * lines of the new text that were not already in the text it replaced. An Edit carries
 * unchanged lines as anchors, and a trailing newline is not a line of its own.
 * @param {string} text the file after the edit @param {string} piece the new text @param {string} [was] the text it replaced
 */
function linesOf(text, piece, was = '') {
  /** @type {Set<number>} */
  const out = new Set();
  if (!piece) return out;
  const before = new Set(String(was).split('\n').map((l) => l.trim()).filter(Boolean));
  const rows = piece.replace(/\n$/, '').split('\n');
  for (let at = text.indexOf(piece); at >= 0; at = text.indexOf(piece, at + piece.length)) {
    const first = text.slice(0, at).split('\n').length;
    rows.forEach((row, i) => {
      if (row.trim() && !before.has(row.trim())) out.add(first + i);
    });
  }
  return out;
}

async function main() {
  if (/^(0|off|false|no)$/i.test(process.env.BENCH_DESIGN_HOOK || '')) return;
  /** @type {any} */
  let call;
  try {
    call = JSON.parse(await stdin());
  } catch {
    return;
  }
  const input = call?.tool_input || {};
  const cwd = call?.cwd || process.cwd();
  const file = input.file_path ? path.resolve(cwd, String(input.file_path)) : null;
  if (!file || !isUiFile(file)) return;

  const root = projectRoot(file, cwd);
  /** @type {Record<string, any>} */
  let config = {};
  try {
    config = JSON.parse(fs.readFileSync(path.join(root, '.bench', 'design-check.json'), 'utf8'));
  } catch {
    /* no config, or one that does not parse: the defaults */
  }
  if (config.hook === false) return;
  const rel = path.relative(root, file).split(path.sep).join('/');
  if ((Array.isArray(config.ignoreFiles) ? config.ignoreFiles : []).some((/** @type {string} */ g) => globRe(String(g)).test(rel))) return;

  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return;
  }
  // What the edit wrote: the whole file for a Write, the new strings for an Edit.
  /** @type {Set<number>} */
  let lines = new Set();
  if (typeof input.content === 'string') lines = new Set(text.split('\n').map((_, i) => i + 1));
  else if (typeof input.new_string === 'string') lines = linesOf(text, input.new_string, input.old_string);
  else if (Array.isArray(input.edits)) for (const e of input.edits) for (const n of linesOf(text, String(e?.new_string || ''), String(e?.old_string || ''))) lines.add(n);
  if (!lines.size) return;

  const hasTokens = ['design-tokens.json', 'tokens.json', 'tokens.css', 'src/tokens.css', 'src/styles/tokens.css', 'styles/tokens.css'].some((f) => fs.existsSync(path.join(root, f)));
  // Only worth looking for when this file animates and says nothing about reduced motion.
  let reducedMotionElsewhere = true;
  if (/@keyframes|animation\s*:/.test(text) && !/prefers-reduced-motion/.test(text)) {
    const deadline = started + BUDGET_MS * 0.6;
    reducedMotionElsewhere = uiFiles([root], { limit: 400, deadline }).some((f) => {
      if (!CSS_EXT.has(path.extname(f)) && !/\.(vue|svelte|astro|html)$/.test(f)) return false;
      try {
        return /prefers-reduced-motion/.test(fs.readFileSync(f, 'utf8'));
      } catch {
        return false;
      }
    });
  }

  const { ignored, refused } = readIgnores(config.ignoreRules);
  let designMd = '';
  try {
    designMd = fs.readFileSync(path.join(root, 'Design.md'), 'utf8');
  } catch {
    /* no Design.md: nothing is waived */
  }
  const { waived } = readWaivers(designMd);

  const seen = new Set();
  const found = scanText(text, { file: rel, hasTokens, reducedMotionElsewhere }).filter((f) => {
    if (!lines.has(f.line)) return false;
    if (f.tier !== 'floor' && (ignored.has(f.rule) || waived.has(f.rule))) return false;
    const key = `${f.rule}:${f.line}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  if (!found.length) return;

  const order = { floor: 0, reflex: 1, finish: 2 };
  found.sort((a, b) => order[/** @type {'floor'} */ (a.tier)] - order[/** @type {'floor'} */ (b.tier)] || a.line - b.line);
  const shown = found.slice(0, 12);
  const floors = found.filter((f) => f.tier === 'floor').length;
  const say = [
    `Bench design hook: ${found.length} finding${found.length === 1 ? '' : 's'} in the lines this edit wrote to ${rel}${floors ? ` (${floors} floor)` : ''}.`,
    ...shown.map((f) => `- ${f.tier} \`${f.rule}\` ${rel}:${f.line} \`${f.match}\` - ${f.detail}`),
    ...(found.length > shown.length ? [`- and ${found.length - shown.length} more; \`node "$(bench pack)/skills/design-check/static.mjs" ${rel}\` lists them all`] : []),
    '',
    'Floor findings are defects: fix them before going on. A reflex finding is fixed, or waived with its reason under `## Waivers` in Design.md. A finish finding is polish: fix it or say why not. The rules and what replaces each are in `$(bench pack)/references/` (rules.md, tells.md, polish.md).',
    ...(refused.length ? [`Not applied from .bench/design-check.json ignoreRules: ${refused.join('; ')}.`] : []),
  ];
  process.stdout.write(`${JSON.stringify({ hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: say.join('\n') } })}\n`);
}

try {
  await main();
} catch {
  /* the hook never gets in the way of an edit */
}
process.exit(0);
