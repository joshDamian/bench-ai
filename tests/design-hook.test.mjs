// The design gate's static half and the hook that runs it after every UI edit, run the way
// Claude Code runs them: the hook as a child process with the tool call on stdin, answering
// on stdout or not at all. No browser is needed for any of this, so none of it is skipped.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { tmp } from './tmp.mjs';
import { RULES, BY_ID, readWaivers, readIgnores } from '../pack/skills/design-check/rules.mjs';
import { scanText, cssBlocks, STATIC_RULES } from '../pack/skills/design-check/static.mjs';

const PACK = new URL('../pack/', import.meta.url).pathname;
const HOOK = path.join(PACK, 'skills', 'design-check', 'hook.mjs');
const STATIC = path.join(PACK, 'skills', 'design-check', 'static.mjs');
const DIRECTIONS = path.join(PACK, 'skills', 'design-tokens', 'directions.mjs');

/** A throwaway project: a .git so the hook finds its root, and the files given. @param {Record<string,string>} files */
function project(files) {
  const dir = tmp('hook');
  fs.mkdirSync(path.join(dir, '.git'), { recursive: true });
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), text);
  }
  return dir;
}

/** Runs the hook on one tool call, as Claude Code does. @param {string} dir @param {any} call @param {Record<string,string>} [env] */
function hook(dir, call, env = {}) {
  const r = spawnSync(process.execPath, [HOOK], { input: JSON.stringify({ hook_event_name: 'PostToolUse', cwd: dir, ...call }), encoding: 'utf8', env: { ...process.env, BENCH_DESIGN_HOOK: '', ...env } });
  return { code: r.status, out: r.stdout, said: r.stdout ? JSON.parse(r.stdout).hookSpecificOutput.additionalContext : '' };
}

/** An Edit that wrote `next` into `file` - the file on disk already holds it, as after a real edit. @param {string} dir @param {string} file @param {string} next */
const edit = (dir, file, next) => ({ tool_name: 'Edit', tool_input: { file_path: path.join(dir, file), old_string: '/* here */', new_string: next } });

const EYEBROW = '.eyebrow { font-size: 12px; text-transform: uppercase; letter-spacing: 0.12em; }';
const ALL = '.card { transition: all 200ms ease-out; }';

test('every rule has one tier and a reference that explains it, and every id the detectors use is in the registry', () => {
  const files = { floor: 'rules.md', reflex: 'tells.md', finish: 'polish.md' };
  assert.equal(new Set(RULES.map((r) => r.id)).size, RULES.length, 'ids are unique');
  for (const r of RULES) {
    assert.ok(['floor', 'reflex', 'finish'].includes(r.tier), `${r.id} has a tier`);
    const text = fs.readFileSync(path.join(PACK, 'references', files[r.tier]), 'utf8');
    assert.ok(text.includes(`\`${r.id}\``), `${r.id} (${r.tier}) is explained in references/${files[r.tier]}`);
    assert.ok(r.tell && r.instead, `${r.id} says what it is and what replaces it`);
  }
  for (const id of STATIC_RULES) assert.ok(BY_ID.has(id), `the scanner's ${id} is in the registry`);
  // The browser half reports its ids as strings in the page functions and the gate.
  const browser = ['tells.mjs', 'design-check.mjs'].map((f) => fs.readFileSync(path.join(PACK, 'skills', 'design-check', f), 'utf8')).join('\n');
  for (const [, id] of browser.matchAll(/(?:hit\(|rule: )'([a-z-]+)'/g)) assert.ok(BY_ID.has(id), `the gate's ${id} is in the registry`);
  for (const r of RULES.filter((x) => x.how === 'static' || x.how === 'both')) assert.ok(STATIC_RULES.includes(r.id), `${r.id} says the scanner sees it, and it does`);
  // The floor the person set is the floor, and nothing they placed in reflex slipped into it.
  for (const id of ['contrast', 'focus', 'target', 'semantics', 'motion', 'hidden-at-rest', 'tokens', 'invented-proof', 'eyebrow']) assert.equal(BY_ID.get(id)?.tier, 'floor', `${id} is floor`);
  for (const id of ['icon-tile-grid', 'nested-cards', 'hero-metric', 'gradient-text', 'ai-palette', 'cream-terracotta', 'mono-costume', 'section-numbers', 'tracked-caps', 'glass', 'side-tab', 'border-shadow-card', 'dark-glow', 'bounce-easing', 'pulsing-dot', 'marquee', 'middle-dot-meta', 'arrow-link', 'accent-word', 'overused-font', 'stock-phrase', 'em-dash']) assert.equal(BY_ID.get(id)?.tier, 'reflex', `${id} is reflex`);
});

test('a waiver needs a reason and never reaches the floor; an ignore never reaches the floor either', () => {
  const { waived, refused } = readWaivers('# Design\n\n## Waivers\n- `glass`: the map toolbar is the one blurred bar over moving tiles\n- `gradient-text`: because\n- `eyebrow`: the client insists on them everywhere\n- `nope`: a rule that does not exist at all\n\n## Colour\n- `marquee`: not under the waivers heading, so not a waiver\n');
  assert.deepEqual([...waived.keys()], ['glass']);
  assert.equal(waived.get('glass'), 'the map toolbar is the one blurred bar over moving tiles');
  assert.ok(refused.some((r) => /gradient-text: the reason/.test(r)));
  assert.ok(refused.some((r) => /eyebrow: a floor rule is never waived/.test(r)));
  assert.ok(refused.some((r) => /nope: no such rule/.test(r)));
  assert.equal(readWaivers('').waived.size, 0);
  const ig = readIgnores(['text-wrap', 'contrast']);
  assert.deepEqual([...ig.ignored], ['text-wrap']);
  assert.match(ig.refused[0], /contrast: a floor rule cannot be ignored/);
});

test('the scanner names each planted tell at its line, in its tier, and says nothing about a clean stylesheet', () => {
  const css = [
    EYEBROW, // 1 eyebrow (floor), tracked-caps
    ALL, // 2 transition-all
    '.btn:focus { outline: none; }', // 3 focus (floor)
    '.reveal { opacity: 0; }', // 4 hidden-at-rest (floor)
    '.glow { box-shadow: 0 0 24px rgba(124, 58, 237, 0.6); }', // 5 dark-glow
    '.title { background-clip: text; -webkit-background-clip: text; }', // 6 gradient-text
    '.tag { font-family: "JetBrains Mono", monospace; }', // 7 mono-costume
    '.note { border-left: 4px solid var(--accent); }', // 8 side-tab
    '.pop { transition: transform 300ms cubic-bezier(0.34, 1.56, 0.64, 1); }', // 9 bounce-easing
    '.hero { min-height: 100vh; }', // 10 vh-units (finish)
    '@keyframes spin { to { transform: rotate(1turn); } }', // 11
    '.spinner { animation: spin 1s linear infinite; }', // 12 motion (floor): no reduced-motion anywhere
  ].join('\n');
  const found = scanText(css, { file: 'app.css' });
  const at = (/** @type {string} */ rule) => found.filter((f) => f.rule === rule).map((f) => f.line);
  assert.deepEqual(at('eyebrow'), [1]);
  assert.deepEqual(at('tracked-caps'), [1]);
  assert.deepEqual(at('transition-all'), [2]);
  assert.deepEqual(at('focus'), [3]);
  assert.deepEqual(at('hidden-at-rest'), [4]);
  assert.deepEqual(at('dark-glow'), [5]);
  assert.deepEqual(at('gradient-text'), [6]);
  assert.deepEqual(at('mono-costume'), [7]);
  assert.deepEqual(at('side-tab'), [8]);
  assert.deepEqual(at('bounce-easing'), [9]);
  assert.deepEqual(at('vh-units'), [10]);
  assert.deepEqual(at('motion'), [12]);
  for (const f of found) assert.equal(f.tier, BY_ID.get(f.rule)?.tier);
  assert.deepEqual(scanText(css, { file: 'app.css', reducedMotionElsewhere: true }).filter((f) => f.rule === 'motion'), [], 'a reduced-motion block in another stylesheet counts');

  const clean = [
    ':root { --ink: #1f2937; --accent: #0b6bcb; }',
    '.card { padding: var(--space-2); color: var(--ink); transition: transform 160ms var(--motion-ease); }',
    '.btn:focus-visible { outline: 3px solid var(--accent); }',
    '.js .reveal { opacity: 0; }',
    'code, pre { font-family: ui-monospace, monospace; }',
    '@media (hover: hover) and (pointer: fine) { .card:hover { transform: translateY(-2px); } }',
    '@media (prefers-reduced-motion: reduce) { * { transition: none; } }',
  ].join('\n');
  assert.deepEqual(scanText(clean, { file: 'app.css', hasTokens: true }), [], 'tokens in :root, a focus ring, a script-scoped reveal, code in mono, gated hover');
  assert.deepEqual(scanText('.a { color: #ff0000; }', { file: 'app.css' }), [], 'a literal colour is only a defect where the project keeps tokens');
  assert.equal(scanText('.a { color: #ff0000; }', { file: 'app.css', hasTokens: true })[0].rule, 'tokens');
  assert.deepEqual(scanText('.a { color: #ff0000; }', { file: 'tokens.css', hasTokens: true }), [], 'a token file is where literals belong');
});

test('the scanner reads markup and components for the copy and class-list tells, and leaves plain scripts alone', () => {
  const html = [
    '<meta name="viewport" content="width=device-width, maximum-scale=1">', // 1 no-zoom
    '<p class="kicker">New</p><h2>Supercharge your mornings</h2>', // 2 eyebrow, stock-phrase
    '<a href="/plans">Learn more →</a>', // 3 generic-label, arrow-link
    '<p>Lorem ipsum dolor sit amet</p>', // 4 placeholder
    '<span>Ada · 12 min · Tuesday</span>', // 5 middle-dot-meta
    '<span>01</span>', // 6 section-numbers
    '<div class="backdrop-blur-md"></div><div class="backdrop-blur"></div>', // 7 glass
    '<li>🚀 Fast</li>', // 8 emoji-icons
    '<span class="h-2 w-2 rounded-full animate-ping"></span>', // 9 pulsing-dot
    '<p class="uppercase tracking-widest text-xs">Label</p>', // 10 tracked-caps
    '<p>Loading...</p>', // 11 typography-marks
  ].join('\n');
  const found = scanText(html, { file: 'index.html' });
  const has = (/** @type {string} */ rule, /** @type {number} */ line) => assert.ok(found.some((f) => f.rule === rule && f.line === line), `${rule} at ${line}; got ${found.map((f) => `${f.rule}:${f.line}`).join(' ')}`);
  has('no-zoom', 1);
  has('eyebrow', 2);
  has('stock-phrase', 2);
  has('generic-label', 3);
  has('arrow-link', 3);
  has('placeholder', 4);
  has('middle-dot-meta', 5);
  has('section-numbers', 6);
  has('glass', 7);
  has('emoji-icons', 8);
  has('pulsing-dot', 9);
  has('tracked-caps', 10);
  has('typography-marks', 11);
  const jsx = 'export const Hero = () => <h1 className="bg-clip-text text-transparent bg-gradient-to-r from-purple-500 to-blue-500">Hi</h1>;';
  const j = scanText(jsx, { file: 'Hero.tsx' }).map((f) => f.rule);
  assert.ok(j.includes('gradient-text') && j.includes('ai-palette'), j.join(' '));
  assert.deepEqual(scanText('export function add(a, b) { return a + b; } // seamless', { file: 'math.mjs' }), [], 'a script that draws no UI is not read');
  assert.deepEqual(scanText('Supercharge', { file: 'notes.py' }), [], 'nor is a file that is not UI');
  // A line marked deliberate quiets reflex rules on it, never a floor rule.
  const quiet = scanText('.a { transition: all 1s; } /* design-check-ignore */\n.eyebrow { color: red; } /* design-check-ignore */', { file: 'a.css' });
  assert.deepEqual(quiet.map((f) => f.rule), ['eyebrow']);
});

test('the css reader keeps nesting, so a rule knows what it sits inside', () => {
  const blocks = cssBlocks('/* {a} */ @media (hover: hover) { .x:hover { transform: none; content: "}"; } }\n:root { --a: 1px; }');
  const x = blocks.find((b) => b.selector === '.x:hover');
  assert.deepEqual(x?.ancestors, ['@media (hover: hover)']);
  assert.deepEqual(x?.decls.map((d) => d.prop), ['transform', 'content']);
  assert.equal(blocks.find((b) => b.selector === ':root')?.decls[0].prop, '--a');
});

test('the static scanner as a command: 1 on a floor finding, 0 on warnings alone, 2 with nothing to read', () => {
  const dir = project({ 'a.css': `${EYEBROW}\n`, 'b.css': `${ALL}\n`, 'empty/readme.txt': 'nothing here' });
  const run = (/** @type {string[]} */ args) => spawnSync(process.execPath, [STATIC, ...args], { cwd: dir, encoding: 'utf8' });
  const floor = run(['a.css', '--json']);
  assert.equal(floor.status, 1, floor.stdout);
  assert.equal(JSON.parse(floor.stdout).findings[0].rule, 'eyebrow');
  assert.equal(run(['b.css']).status, 0);
  assert.match(run(['b.css']).stdout, /reflex +transition-all +b\.css:1/);
  assert.equal(run(['empty']).status, 2);
});

test('the hook tells the session what an edit wrote that breaks a rule, and is quiet when it is clean', () => {
  const dir = project({ 'src/app.css': `.a { color: var(--ink); }\n${EYEBROW}\n${ALL}\n` });
  const bad = hook(dir, edit(dir, 'src/app.css', `${EYEBROW}\n${ALL}`));
  assert.equal(bad.code, 0, 'the hook never fails the edit');
  assert.match(bad.said, /3 findings in the lines this edit wrote to src\/app\.css \(1 floor\)/);
  assert.match(bad.said, /floor `eyebrow` src\/app\.css:2/);
  assert.match(bad.said, /reflex `transition-all` src\/app\.css:3/);
  assert.match(bad.said, /Instead: /, 'each finding says what replaces it');
  assert.match(bad.said, /\$\(bench pack\)\/references\//, 'and where the rules are written');
  assert.equal(JSON.parse(bad.out).hookSpecificOutput.hookEventName, 'PostToolUse');

  // Only what this edit wrote: the eyebrow two lines down is old, and this edit is clean.
  const clean = hook(dir, edit(dir, 'src/app.css', '.a { color: var(--ink); }'));
  assert.equal(clean.out, '', 'a clean edit produces nothing at all');
  // An Edit's anchor lines are not what it wrote, and a trailing newline is not a line: an
  // edit that adds one clean rule under the h1, ending in a newline, is clean even with the
  // old eyebrow right after it. (Found in a live session, where this was reported.)
  const anchored = project({ 'app.css': `h1 { font-size: 32px; }\np { max-width: 65ch; }\n${EYEBROW}\n` });
  assert.equal(hook(anchored, { tool_name: 'Edit', tool_input: { file_path: path.join(anchored, 'app.css'), old_string: 'h1 { font-size: 32px; }\n', new_string: 'h1 { font-size: 32px; }\np { max-width: 65ch; }\n' } }).out, '');
  assert.match(hook(anchored, { tool_name: 'Edit', tool_input: { file_path: path.join(anchored, 'app.css'), old_string: 'p { max-width: 65ch; }\n', new_string: `p { max-width: 65ch; }\n${EYEBROW}\n` } }).said, /eyebrow/, 'but a line it did add is read');
  // A Write wrote every line, so it is judged whole.
  assert.match(hook(dir, { tool_name: 'Write', tool_input: { file_path: path.join(dir, 'src/app.css'), content: 'x' } }).said, /eyebrow/);
  // MultiEdit, each new string.
  assert.match(hook(dir, { tool_name: 'MultiEdit', tool_input: { file_path: path.join(dir, 'src/app.css'), edits: [{ old_string: 'a', new_string: ALL }] } }).said, /transition-all/);
  // Not UI, not there, not JSON: silence, and exit 0.
  const py = project({ 'app.py': 'print("supercharge")\n' });
  assert.equal(hook(py, { tool_name: 'Write', tool_input: { file_path: path.join(py, 'app.py'), content: '' } }).out, '');
  assert.equal(hook(dir, edit(dir, 'src/gone.css', ALL)).out, '');
  const junk = spawnSync(process.execPath, [HOOK], { input: 'not json', encoding: 'utf8' });
  assert.equal(junk.status, 0);
  assert.equal(junk.stdout, '');
});

test('the hook has an off-switch per project and per run, and per-rule ignores that stop at the floor', () => {
  const dir = project({ 'app.css': `${EYEBROW}\n${ALL}\n` });
  const call = edit(dir, 'app.css', `${EYEBROW}\n${ALL}`);
  assert.match(hook(dir, call).said, /eyebrow/);
  assert.equal(hook(dir, call, { BENCH_DESIGN_HOOK: '0' }).out, '', 'off for a run');

  fs.mkdirSync(path.join(dir, '.bench'));
  fs.writeFileSync(path.join(dir, '.bench', 'design-check.json'), JSON.stringify({ hook: false }));
  assert.equal(hook(dir, call).out, '', 'off for the project');

  fs.writeFileSync(path.join(dir, '.bench', 'design-check.json'), JSON.stringify({ ignoreRules: ['transition-all', 'tracked-caps', 'eyebrow'] }));
  const ignoring = hook(dir, call).said;
  assert.doesNotMatch(ignoring, /transition-all|`tracked-caps`/, 'reflex rules can be ignored');
  assert.match(ignoring, /floor `eyebrow`/, 'a floor rule cannot');
  assert.match(ignoring, /Not applied from \.bench\/design-check\.json ignoreRules: eyebrow: a floor rule cannot be ignored/);

  fs.writeFileSync(path.join(dir, '.bench', 'design-check.json'), JSON.stringify({}));
  fs.writeFileSync(path.join(dir, 'Design.md'), '# Design\n\n## Waivers\n- `transition-all`: legacy cards animate four properties at once, cleaned up in Q3\n');
  assert.doesNotMatch(hook(dir, call).said, /transition-all/, 'a reflex waiver in Design.md quiets it');

  fs.writeFileSync(path.join(dir, '.bench', 'design-check.json'), JSON.stringify({ ignoreFiles: ['**/*.css'] }));
  assert.equal(hook(dir, call).out, '', 'a file out of scope is left alone whole');
});

test('the hook is wired into the pack: PostToolUse on edits, run from the plugin root, with a timeout', () => {
  const wired = JSON.parse(fs.readFileSync(path.join(PACK, 'hooks', 'hooks.json'), 'utf8'));
  const [entry] = wired.hooks.PostToolUse;
  assert.match(entry.matcher, /Edit/);
  assert.match(entry.matcher, /Write/);
  assert.match(entry.hooks[0].command, /\$\{CLAUDE_PLUGIN_ROOT\}\/skills\/design-check\/hook\.mjs/);
  assert.ok(entry.hooks[0].timeout > 0 && entry.hooks[0].timeout <= 10, 'it cannot hold an edit up for long');
});

test('the directions chosen are kept across projects, newest first, so the next one can avoid them', () => {
  const data = tmp('dirs');
  const run = (/** @type {string[]} */ args) => spawnSync(process.execPath, [DIRECTIONS, ...args], { encoding: 'utf8', env: { ...process.env, BENCH_DATA_DIR: data } });
  assert.match(run(['list']).stdout, /No directions recorded yet/);
  assert.equal(run(['add', '--project', 'ledger', '--direction', 'the printed statement becomes the table', '--motion', 'Precise']).status, 0);
  assert.equal(run(['add', '--project', 'kiln', '--direction', 'the firing chart becomes the progress bar']).status, 0);
  const listed = run(['list']).stdout.trim().split('\n');
  assert.match(listed[0], /kiln: the firing chart/);
  assert.match(listed[1], /ledger: the printed statement .*motion: Precise/);
  assert.equal(run(['add', '--project', 'x']).status, 2, 'a direction needs its sentence');
  assert.ok(fs.existsSync(path.join(data, 'design-directions.jsonl')));
});

test('every source the design references adapt is credited with its licence text', () => {
  const credits = fs.readFileSync(path.join(PACK, 'CREDITS.md'), 'utf8');
  for (const repo of ['Leonxlnx/taste-skill', 'elayadesign/ai-design-skills', 'pbakaus/impeccable', 'Nutlope/hallmark', 'emilkowalski/skills', 'jakubkrehel/make-interfaces-feel-better', 'vercel-labs/web-interface-guidelines', 'LottieFiles/motion-design-skill', 'ibelick/ui-skills']) assert.ok(credits.includes(`https://github.com/${repo}`), `${repo} is credited`);
  assert.equal((credits.match(/Permission is hereby granted, free of charge/g) || []).length, 8, 'each MIT licence in full');
  assert.match(credits, /Apache License\s+Version 2\.0/);
  assert.match(credits, /Changes: /, 'and the Apache-licensed source says what was changed');
  for (const f of fs.readdirSync(path.join(PACK, 'references'))) assert.match(fs.readFileSync(path.join(PACK, 'references', f), 'utf8'), /CREDITS\.md|^# rules/m, `${f} points at its credits`);
});
