// @ts-check
// The design gate's static half: reads source - CSS, markup, components - for the rules a
// rendered page is not needed to see, and the ones a render cannot see at all (copy habits,
// a stylesheet's motion with no reduced-motion fallback, `transition: all`). No browser, no
// network: it is what the edit hook runs after every UI edit, so it has to be cheap.
//
//   node static.mjs <file|dir>... [--json]
//
//   0  no floor finding (reflex and finish findings are warnings)
//   1  a floor finding
//   2  nothing to read
//
// The rule ids are the ones in rules.mjs, which says each rule's tier.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BY_ID, tierOf } from './rules.mjs';

/** One thing the source says. @typedef {{rule:string, tier:string, file:string, line:number, match:string, detail:string}} StaticFinding */

export const CSS_EXT = new Set(['.css', '.scss', '.sass', '.less', '.pcss', '.styl']);
export const MARKUP_EXT = new Set(['.html', '.htm', '.vue', '.svelte', '.astro', '.mdx', '.jsx', '.tsx', '.hbs', '.njk', '.liquid', '.erb', '.php']);
export const SCRIPT_EXT = new Set(['.js', '.mjs', '.cjs', '.ts', '.mts']);
/** Whether a file is one the scanner reads at all. @param {string} file */
export const isUiFile = (file) => {
  const ext = path.extname(file).toLowerCase();
  return CSS_EXT.has(ext) || MARKUP_EXT.has(ext) || SCRIPT_EXT.has(ext);
};
/** A plain script is read only when it draws UI: markup in a template, a JSX tag, a class list. @param {string} text */
const drawsUi = (text) => /html`|css`|<[a-z][\w-]*[\s>/]|className=|\.innerHTML\b|\.classList\b|createElement\(/.test(text);

/** Files whose job is to hold the tokens: their literals are the point. @param {string} file */
const isTokenFile = (file) => /(^|[\\/])([\w-]*tokens?[\w-]*|themes?|variables|palette|colou?rs)\.(css|scss|less|json)$/i.test(file);

// ---------------------------------------------------------------------------
// Reading the source

/** Line number (1-based) of each offset in a text. @param {string} text */
function lineIndex(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) starts.push(i + 1);
  return (/** @type {number} */ at) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= at) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  };
}

/**
 * The rule blocks of a stylesheet, each with its selector, its declarations, and the
 * at-rules and selectors it sits inside - `@media (hover: hover)`, `@starting-style`,
 * `:root` - which several rules need to know. Comments and strings are stepped over. It is
 * not a full CSS parser and does not need to be: it reads what was written.
 * @param {string} css @param {number} base where this stylesheet starts in its file
 * @returns {Array<{selector:string, at:number, ancestors:string[], decls:Array<{prop:string, value:string, at:number}>}>}
 */
export function cssBlocks(css, base = 0) {
  /** @type {Array<{selector:string, at:number, ancestors:string[], decls:Array<{prop:string, value:string, at:number}>}>} */
  const out = [];
  /** @type {Array<{selector:string, at:number, ancestors:string[], decls:Array<{prop:string, value:string, at:number}>}>} */
  const stack = [];
  let start = 0;
  const declare = (/** @type {number} */ end) => {
    const text = css.slice(start, end);
    const top = stack[stack.length - 1];
    const m = /^\s*([-\w]+)\s*:([\s\S]*)$/.exec(text);
    if (top && m) top.decls.push({ prop: m[1].toLowerCase(), value: m[2].trim(), at: base + start + (text.length - text.trimStart().length) });
  };
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === '/' && css[i + 1] === '*') {
      const end = css.indexOf('*/', i + 2);
      i = end < 0 ? css.length : end + 1;
      continue;
    }
    if (c === '"' || c === "'") {
      const end = css.indexOf(c, i + 1);
      i = end < 0 ? css.length : end;
      continue;
    }
    if (c === '{') {
      const prelude = css.slice(start, i).replace(/\/\*[\s\S]*?\*\//g, '').trim();
      const block = { selector: prelude, at: base + start + (css.slice(start, i).length - css.slice(start, i).trimStart().length), ancestors: stack.map((b) => b.selector), decls: [] };
      stack.push(block);
      out.push(block);
      start = i + 1;
    } else if (c === ';') {
      declare(i);
      start = i + 1;
    } else if (c === '}') {
      declare(i);
      stack.pop();
      start = i + 1;
    }
  }
  return out;
}

/**
 * The stylesheets inside a markup file - `<style>` blocks and `style="..."` attributes - as
 * offsets into it, so a finding still points at the right line.
 * @param {string} text @returns {Array<{css:string, at:number, inline:boolean}>}
 */
function embeddedCss(text) {
  /** @type {Array<{css:string, at:number, inline:boolean}>} */
  const out = [];
  for (const m of text.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/gi)) out.push({ css: m[1], at: /** @type {number} */ (m.index) + m[0].indexOf(m[1]), inline: false });
  for (const m of text.matchAll(/\sstyle\s*=\s*"([^"]*)"/gi)) out.push({ css: `x{${m[1]}}`, at: /** @type {number} */ (m.index) + m[0].indexOf(m[1]) - 2, inline: true });
  // A template literal tagged `css` - a styled component, a lit stylesheet - is a stylesheet too.
  for (const m of text.matchAll(/\bcss`([\s\S]*?)`/g)) out.push({ css: m[1], at: /** @type {number} */ (m.index) + 4, inline: false });
  return out;
}

/** The text a person reads, between tags: `>Get started<`. @param {string} text */
function textRuns(text) {
  /** @type {Array<{text:string, at:number}>} */
  const out = [];
  const bare = text.replace(/<(script|style)\b[\s\S]*?<\/\1>/gi, (s) => ' '.repeat(s.length)).replace(/<!--[\s\S]*?-->/g, (s) => ' '.repeat(s.length));
  for (const m of bare.matchAll(/>([^<>]{1,400})</g)) {
    // Expressions in a template are code, not copy.
    const words = m[1].replace(/\$\{[^}]*\}|\{[^}]*\}/g, ' ');
    if (/\S/.test(words)) out.push({ text: words, at: /** @type {number} */ (m.index) + 1 });
  }
  return out;
}

/** The class lists in markup: `class="..."`, `className="..."`, `` className={`...`} ``. @param {string} text */
function classLists(text) {
  /** @type {Array<{text:string, at:number}>} */
  const out = [];
  for (const m of text.matchAll(/\bclass(?:Name)?\s*=\s*(?:"([^"]*)"|'([^']*)'|\{\s*`([^`]*)`\s*\}|\{\s*"([^"]*)"\s*\})/g)) {
    const v = m[1] ?? m[2] ?? m[3] ?? m[4] ?? '';
    out.push({ text: v, at: /** @type {number} */ (m.index) + m[0].indexOf(v) });
  }
  return out;
}

// ---------------------------------------------------------------------------
// What counts

const MONO = /\b(mono|monospace|courier|consolas|menlo|monaco|sfmono|jetbrains|fira code|source code|ibm plex mono|geist mono|roboto mono|space mono|dm mono)\b|ui-monospace/i;
// Where monospace is the content and not a costume: code, terminals, diffs, hashes, paths.
const CODEISH = /\b(code|pre|kbd|samp|var|tt|terminal|term|console|shell|cmd|diff|log|logs|editor|xterm|hljs|prism|shiki|syntax|sha|hash|commit|path|file|mono|digits|tabular|output|stdout|stderr|token|key|branch|git|ref|pid|proc|cli|command|slash|viewer|source)\b/i;
const OVERUSED = /^["']?(Inter|Roboto|Open Sans|Poppins|Montserrat|Lato|Nunito|Raleway|Space Grotesk|Plus Jakarta Sans|Geist|DM Sans|Manrope|Outfit|Sora|Fraunces|Playfair Display|Instrument Serif|Newsreader)["']?(\s*,|\s*$)/i;
const STOCK = /\b(unlock(s|ing)?|unleash(es|ing)?|supercharge[sd]?|seamless(ly)?|elevate[sd]?|empower(s|ing)?|revolutioni[sz]e[sd]?|game[- ]chang(er|ing)|cutting[- ]edge|next[- ]gen(eration)?|world[- ]class|best[- ]in[- ]class|industry[- ]leading|all[- ]in[- ]one|effortless(ly)?|blazing(ly)?[- ]fast|lightning[- ]fast|reimagin(e|ed|ing)|turbocharge[sd]?|mission[- ]critical|future[- ]proof|take your [^.]{1,30} to the next level|say goodbye to|in today'?s [a-z]+ (world|landscape)|built for the modern|not just an? [^.,]{1,30}[,.]? (it'?s|but))\b/i;
const GENERIC_LABEL = /^\s*(learn more|get started|click here|read more|submit|discover more|explore now|find out more)\s*[.!]?\s*$/i;
const PLACEHOLDER = /lorem ipsum|dolor sit amet|\b(John|Jane) Doe\b|\bAcme (Inc|Corp|Co)\b/i;
const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{26FF}\u{2700}-\u{2712}\u{2719}-\u{27BF}]/u;
const PURPLE_HEX = /#(6366f1|8b5cf6|7c3aed|a855f7|4f46e5|a78bfa|818cf8|9333ea|6d28d9|c084fc|5b21b6|4338ca)\b/i;
const CREAM_TERRACOTTA = /#(d97757|da7756|c15f3c|cc785c|bd5d3a|f0eee6|faf9f5|f5f4ed|eeece2|e8e6dc|f4f1ea)\b/i;
const COLOR_LITERAL = /#[0-9a-f]{3,8}\b|\b(rgba?|hsla?|oklch|oklab|lab|lch)\(/i;
const COLOR_PROP = /^(color|background|background-color|border|border-(top|right|bottom|left|block|inline)(-color)?|border-color|outline|outline-color|fill|stroke|box-shadow|text-shadow|caret-color|accent-color|text-decoration-color|column-rule-color)$/;

/** @param {string} v */
const lengthPx = (v) => {
  const m = /^(-?\d*\.?\d+)(px|rem|em)?$/.exec(v.trim());
  if (!m) return null;
  return m[2] === 'rem' || m[2] === 'em' ? Number(m[1]) * 16 : Number(m[1]);
};

/** A cubic-bezier that leaves 0..1 overshoots, which is what a bounce is. @param {string} v */
const overshoots = (v) => [...v.matchAll(/cubic-bezier\(([^)]*)\)/g)].some((m) => {
  const n = m[1].split(',').map(Number);
  return n.length === 4 && (n[1] > 1 || n[3] > 1 || n[1] < 0 || n[3] < 0);
});

/**
 * Everything the source of one file says, against the static rules.
 * @param {string} text the file's contents
 * @param {{file?:string, hasTokens?:boolean, reducedMotionElsewhere?:boolean}} [o]
 * @returns {StaticFinding[]}
 */
export function scanText(text, o = {}) {
  const file = o.file || '<text>';
  const ext = path.extname(file).toLowerCase();
  const css = CSS_EXT.has(ext);
  const markup = MARKUP_EXT.has(ext) || (SCRIPT_EXT.has(ext) && drawsUi(text));
  if (!css && !markup) return [];
  const lineOf = lineIndex(text);
  /** @type {StaticFinding[]} */
  const out = [];
  /** @param {string} rule @param {number} at @param {string} match @param {string} [detail] */
  const add = (rule, at, match, detail) => {
    const r = BY_ID.get(rule);
    out.push({ rule, tier: tierOf(rule), file, line: lineOf(at), match: match.trim().replace(/\s+/g, ' ').slice(0, 80), detail: detail || `${r?.tell}. Instead: ${r?.instead}` });
  };

  // Lines a person has said are deliberate keep reflex and finish rules quiet. A floor rule
  // is never quieted this way; a file that is out of scope goes in the config's ignoreFiles.
  const lines = text.split('\n');
  const quiet = new Set();
  lines.forEach((l, i) => {
    if (/design-check-ignore-next-line/.test(l)) quiet.add(i + 2);
    else if (/design-check-ignore\b/.test(l)) quiet.add(i + 1);
  });

  // --- stylesheets
  const sheets = css ? [{ css: text, at: 0, inline: false }] : embeddedCss(text);
  let animatedAt = -1;
  let glassCount = 0;
  let firstGlass = -1;
  const tokenFile = isTokenFile(file);
  for (const sheet of sheets) {
    for (const b of cssBlocks(sheet.css, sheet.at)) {
      const sel = b.selector;
      if (sel.startsWith('@') && !b.decls.length) continue;
      const within = b.ancestors.join(' ');
      const decl = (/** @type {RegExp} */ p) => b.decls.find((d) => p.test(d.prop));
      const custom = b.decls.filter((d) => d.prop.startsWith('--')).length;
      // A block that is mostly custom properties is a token block: its literals are the point.
      const tokenBlock = tokenFile || custom * 2 >= b.decls.length || /:root|\[data-theme|\.theme\b|\.dark\b|\.light\b/.test(sel);
      const inKeyframes = /@keyframes/.test(within);

      if (/\.(eyebrow|kicker|overline|pre-?title|super-?title|section-(label|tag))\b/i.test(sel)) add('eyebrow', b.at, sel);

      // focus: a focus style that takes the ring away and puts nothing in its place.
      if (/:focus/.test(sel)) {
        const off = decl(/^outline$/);
        const replaced = b.decls.some((d) => /^(box-shadow|border|border-color|border-bottom|outline-color|outline-style|outline-width|background|background-color|text-decoration|color)$/.test(d.prop) && !/^(none|0)$/.test(d.value));
        if (off && /^(none|0|0px)$/.test(off.value) && !replaced) add('focus', off.at, `${sel} { outline: ${off.value} }`);
      }

      // hidden at rest: a reveal class that starts invisible and waits for a script.
      const zero = decl(/^opacity$/);
      if (zero && /^0(\.0+)?(\s*!important)?$/.test(zero.value) && /reveal|fade|aos|animate|appear|in-?view|on-?scroll|scroll-?in|slide-?in|entrance/i.test(sel) && !/\.js\b|\.has-js|\.js-|\[data-js/.test(`${within} ${sel}`) && !/@starting-style/.test(within) && !decl(/^animation(-name)?$/)) {
        add('hidden-at-rest', zero.at, `${sel} { opacity: ${zero.value} }`);
      }

      for (const d of b.decls) {
        const v = d.value;
        if (d.prop.startsWith('--')) continue;
        if (!tokenBlock && !inKeyframes && o.hasTokens && COLOR_PROP.test(d.prop) && COLOR_LITERAL.test(v.replace(/var\([^)]*\)/g, ''))) add('tokens', d.at, `${d.prop}: ${v}`, `${d.prop}: ${v} is a colour written out where the project keeps tokens. Instead: the token, or a new token added to the source with its reason`);
        if (/^(background-clip|-webkit-background-clip)$/.test(d.prop) && /text/.test(v)) add('gradient-text', d.at, `${d.prop}: ${v}`);
        if (PURPLE_HEX.test(v)) add('ai-palette', d.at, `${d.prop}: ${v}`);
        if (CREAM_TERRACOTTA.test(v)) add('cream-terracotta', d.at, `${d.prop}: ${v}`);
        if (/^font(-family)?$/.test(d.prop) && MONO.test(v) && !CODEISH.test(`${within} ${sel}`)) add('mono-costume', d.at, `${sel} { ${d.prop}: ${v} }`);
        if (d.prop === 'font-family' && OVERUSED.test(v)) add('overused-font', d.at, `font-family: ${v}`);
        if (d.prop === 'list-style-type' && /decimal-leading-zero/.test(v)) add('section-numbers', d.at, `${d.prop}: ${v}`);
        if (/^(-webkit-)?backdrop-filter$/.test(d.prop) && !/^none$/.test(v)) {
          glassCount++;
          if (firstGlass < 0) firstGlass = d.at;
        }
        if (/^border-(left|right|inline-start|inline-end)(-width)?$/.test(d.prop) && /(^|\s)([2-9]|\d{2})(px)?\b/.test(v) && !/var\(--[\w-]*(line|border|rule|divider|separator|muted|subtle|gr[ae]y|neutral)/.test(v) && !/blockquote/.test(sel)) add('side-tab', d.at, `${sel} { ${d.prop}: ${v} }`);
        if (/^(box-shadow|text-shadow)$/.test(d.prop)) {
          // A glow: no offset, a real blur, and a colour - not the black or grey of a shadow.
          for (const layer of v.split(/,(?![^(]*\))/)) {
            if (/^\s*(inset\s+)?0(px)?\s+0(px)?\s+([1-9]\d*)px/.test(layer) && /#|rgba?\(|hsla?\(|oklch\(|var\(--(accent|primary|brand|glow|neon)/i.test(layer) && !/rgba?\(\s*0\s*,\s*0\s*,\s*0|#000\b|#0{6}\b|var\(--(shadow|black)/i.test(layer)) {
              add('dark-glow', d.at, `${d.prop}: ${v}`);
              break;
            }
          }
        }
        if (/^(transition|transition-timing-function|animation|animation-timing-function)$/.test(d.prop)) {
          if (overshoots(v) || /\b(bounce|elastic|ease-?out-?back|back-?out)\b/i.test(v)) add('bounce-easing', d.at, `${d.prop}: ${v}`);
          if (/(^|[\s,])ease-in(?![-\w])/.test(v)) add('ease-in', d.at, `${d.prop}: ${v}`);
        }
        if (/^(transition|transition-property)$/.test(d.prop) && /(^|[\s,])all(\s|,|$)/.test(v)) add('transition-all', d.at, `${d.prop}: ${v}`);
        if (/^(animation|animation-name)$/.test(d.prop) && /\b(marquee|ticker|scroll-?x|scroll-?left)\b/i.test(v)) add('marquee', d.at, `${d.prop}: ${v}`);
        if (/^(animation|animation-name)$/.test(d.prop) && !/^none$/.test(v) && animatedAt < 0) animatedAt = d.at;
        if (/^(height|min-height|max-height)$/.test(d.prop) && /\b100vh\b/.test(v)) add('vh-units', d.at, `${d.prop}: ${v}`);
        if (d.prop === 'transform' && /scale\(\s*0\s*\)/.test(v)) add('scale-zero', d.at, `transform: ${v}`);
      }

      // Tiny tracked capitals: uppercase, letter-spaced, set small.
      const upper = decl(/^text-transform$/);
      const track = decl(/^letter-spacing$/);
      const size = decl(/^font-size$/);
      if (upper && /uppercase/.test(upper.value) && track && /^(0?\.0[4-9]|0?\.[1-9]\d*|[1-9]\d*(\.\d+)?)em$|^[1-9](\.\d+)?px$/.test(track.value) && (!size || (lengthPx(size.value) ?? 99) <= 15)) add('tracked-caps', upper.at, sel);

      // A pulsing dot: a small round thing that animates forever.
      const anim = decl(/^animation$/);
      const round = decl(/^border-radius$/);
      const w = lengthPx(decl(/^(width|inline-size)$/)?.value || '');
      if (anim && /\binfinite\b/.test(anim.value) && round && /50%|9999|999px|100%/.test(round.value) && w !== null && w <= 16) add('pulsing-dot', anim.at, `${sel} { animation: ${anim.value} }`);

      // Hover movement that a tap sets off.
      if (/:hover/.test(sel) && !/hover\s*:\s*hover/.test(within) && b.decls.some((d) => d.prop === 'transform' || d.prop === 'translate' || d.prop === 'scale')) add('hover-ungated', b.at, sel);
    }
  }
  if (glassCount >= 2) add('glass', firstGlass, `${glassCount} backdrop-filter surfaces`, `${glassCount} surfaces blurred with backdrop-filter. ${BY_ID.get('glass')?.tell}. Instead: ${BY_ID.get('glass')?.instead}`);
  // Movement with nowhere saying what happens when a person has asked for less of it.
  if (animatedAt >= 0 && !/prefers-reduced-motion/.test(text) && !o.reducedMotionElsewhere) add('motion', animatedAt, 'animation with no prefers-reduced-motion fallback', `This stylesheet animates and no stylesheet in the project has a prefers-reduced-motion block. Instead: ${BY_ID.get('motion')?.instead}`);

  // --- markup and components
  if (markup) {
    for (const c of classLists(text)) {
      const cls = c.text;
      if (/\b(eyebrow|kicker|overline|pre-?title|super-?title|section-(label|tag))\b/i.test(cls)) add('eyebrow', c.at, `class="${cls}"`);
      if (/\b(focus:)?outline-none\b/.test(cls) && !/\b(focus(-visible|-within)?|has-\[:focus[^\]]*\]):(ring|outline|border|shadow|bg|underline)/.test(cls)) add('focus', c.at, `class="${cls}"`);
      if (/\bbg-clip-text\b/.test(cls)) add('gradient-text', c.at, `class="${cls}"`);
      if (/\b(bg|text|from|to|via|border|ring|fill|stroke|shadow|decoration|outline)-(purple|violet|indigo|fuchsia)-\d{2,3}\b/.test(cls) || PURPLE_HEX.test(cls)) add('ai-palette', c.at, `class="${cls}"`);
      if (/\bfont-mono\b/.test(cls) && !CODEISH.test(cls.replace(/\bfont-mono\b/g, ''))) add('mono-costume', c.at, `class="${cls}"`);
      if (/\buppercase\b/.test(cls) && /\btracking-(wide|wider|widest|\[[^\]]+\])/.test(cls)) add('tracked-caps', c.at, `class="${cls}"`);
      if (/\bbackdrop-blur(-\w+)?\b/.test(cls)) {
        glassCount++;
        if (firstGlass < 0) firstGlass = c.at;
      }
      if (/\bborder-[lrse]-([2-8]|\[\d+px\])\b/.test(cls)) add('side-tab', c.at, `class="${cls}"`);
      if (/\bshadow-(?!sm\b|md\b|lg\b|xl\b|2xl\b|inner\b|none\b)[a-z]+-\d{3}(\/\d+)?\b/.test(cls)) add('dark-glow', c.at, `class="${cls}"`);
      if (/\banimate-bounce\b/.test(cls)) add('bounce-easing', c.at, `class="${cls}"`);
      if (/\banimate-ping\b/.test(cls) || (/\banimate-pulse\b/.test(cls) && /\brounded-full\b/.test(cls) && /\b(h|w|size)-([1-4]|1\.5|2\.5)\b/.test(cls))) add('pulsing-dot', c.at, `class="${cls}"`);
      if (/\bmarquee\b/.test(cls)) add('marquee', c.at, `class="${cls}"`);
      if (/\btransition-all\b/.test(cls)) add('transition-all', c.at, `class="${cls}"`);
      if (/(^|\s)ease-in(\s|$)/.test(cls)) add('ease-in', c.at, `class="${cls}"`);
      if (/\b(bg|text|border|fill|stroke|ring|from|to|via)-\[(#|rgb|hsl)/.test(cls) && o.hasTokens) add('tokens', c.at, `class="${cls}"`, `A colour written out in a class where the project keeps tokens. Instead: the token's utility, or a token added with its reason`);
      if (/\b(min-)?h-screen\b/.test(cls)) add('vh-units', c.at, `class="${cls}"`);
      if (/\bscale-0\b/.test(cls)) add('scale-zero', c.at, `class="${cls}"`);
    }
    for (const m of text.matchAll(/<marquee\b/gi)) add('marquee', /** @type {number} */ (m.index), '<marquee>');
    for (const m of text.matchAll(/user-scalable\s*=\s*(no|0)|maximum-scale\s*=\s*1(\.0)?(?![\d.])/gi)) add('no-zoom', /** @type {number} */ (m.index), m[0]);
    for (const m of text.matchAll(/family=(Inter|Roboto|Open\+Sans|Poppins|Montserrat|Lato|Space\+Grotesk|Plus\+Jakarta\+Sans|Geist|DM\+Sans|Manrope|Outfit|Sora|Fraunces|Playfair\+Display|Instrument\+Serif)\b|from ["']next\/font\/google["']\s*;?[^\n]*\b(Inter|Roboto|Poppins|Space_Grotesk|Geist|Fraunces|Playfair_Display|Instrument_Serif)\b/g)) add('overused-font', /** @type {number} */ (m.index), m[0]);

    let dashes = 0;
    /** @type {number} */
    let firstDash = -1;
    for (const run of textRuns(text)) {
      const t = run.text;
      if (PLACEHOLDER.test(t)) add('placeholder', run.at, t);
      const stock = STOCK.exec(t);
      if (stock) add('stock-phrase', run.at + stock.index, stock[0], `"${stock[0]}". ${BY_ID.get('stock-phrase')?.tell}. Instead: ${BY_ID.get('stock-phrase')?.instead}`);
      if (GENERIC_LABEL.test(t.replace(/\s*(→|↗|&rarr;|-&gt;|->)\s*$/, ''))) add('generic-label', run.at, t);
      if (/^\s*[(\[]?0[1-9][)\]]?\.?\s*$|^\s*(step|phase|chapter|part|no\.?)\s*0\d\s*$/i.test(t)) add('section-numbers', run.at, t);
      if ((t.match(/\s(·|&middot;|&#183;)\s/g) || []).length >= 2) add('middle-dot-meta', run.at, t);
      if (/\S\s*(→|↗|&rarr;|-&gt;|->)\s*$/.test(t)) add('arrow-link', run.at, t);
      if (EMOJI.test(t)) add('emoji-icons', run.at, t);
      if (/\w\.\.\.(\s|$)/.test(t)) add('typography-marks', run.at, t, `"..." where an ellipsis belongs. Instead: …`);
      const n = (t.match(/—|&mdash;/g) || []).length;
      if (n && firstDash < 0) firstDash = run.at;
      dashes += n;
    }
    if (dashes >= 5) add('em-dash', firstDash, `${dashes} em dashes`, `${dashes} em dashes in this file's copy. ${BY_ID.get('em-dash')?.tell}. Instead: ${BY_ID.get('em-dash')?.instead}`);
  }
  if (glassCount >= 2 && !out.some((f) => f.rule === 'glass')) add('glass', firstGlass, `${glassCount} blurred surfaces`, `${glassCount} surfaces blurred with backdrop-filter. ${BY_ID.get('glass')?.tell}. Instead: ${BY_ID.get('glass')?.instead}`);

  // One rule once per line, however many declarations on it set it off.
  const once = new Set();
  return out.filter((f) => {
    const key = `${f.rule}:${f.line}`;
    if (once.has(key)) return false;
    once.add(key);
    return f.tier === 'floor' || !quiet.has(f.line);
  });
}

/** Every rule id the static half can name: what a test holds rules.mjs to. */
export const STATIC_RULES = ['eyebrow', 'focus', 'hidden-at-rest', 'tokens', 'gradient-text', 'ai-palette', 'cream-terracotta', 'mono-costume', 'overused-font', 'section-numbers', 'side-tab', 'dark-glow', 'bounce-easing', 'ease-in', 'transition-all', 'marquee', 'vh-units', 'scale-zero', 'tracked-caps', 'pulsing-dot', 'hover-ungated', 'glass', 'motion', 'no-zoom', 'placeholder', 'stock-phrase', 'generic-label', 'middle-dot-meta', 'arrow-link', 'emoji-icons', 'typography-marks', 'em-dash'];

// ---------------------------------------------------------------------------
// Finding files

const SKIP = new Set(['node_modules', '.git', '.next', '.nuxt', '.svelte-kit', '.output', 'dist', 'build', 'out', 'coverage', 'vendor', '.turbo', '.vercel', '.cache']);

/**
 * The UI files under some paths, most recently written first, stopping at a count or a
 * deadline - whichever comes first - so a huge tree cannot make the caller slow.
 * @param {string[]} roots @param {{limit?:number, deadline?:number}} [o]
 */
export function uiFiles(roots, o = {}) {
  const limit = o.limit ?? 5000;
  const deadline = o.deadline ?? Infinity;
  /** @type {string[]} */
  const out = [];
  const walk = (/** @type {string} */ p) => {
    if (out.length >= limit || Date.now() > deadline) return;
    let st;
    try {
      st = fs.statSync(p);
    } catch {
      return;
    }
    if (st.isDirectory()) {
      for (const f of fs.readdirSync(p)) if (!SKIP.has(f) && !f.startsWith('.')) walk(path.join(p, f));
    } else if (isUiFile(p) && st.size < 2_000_000 && !/\.min\.|\.d\.ts$/.test(p)) out.push(p);
  };
  for (const r of roots) walk(r);
  return out;
}

async function cli() {
  const args = process.argv.slice(2);
  const json = args.includes('--json');
  const roots = args.filter((a) => !a.startsWith('--'));
  const files = uiFiles(roots.length ? roots : ['.']);
  if (!files.length) {
    process.stdout.write(`design-check static: nothing to read under ${roots.join(', ') || '.'}\n`);
    return 2;
  }
  const hasTokens = ['design-tokens.json', 'tokens.json', 'tokens.css', 'src/tokens.css', 'src/styles/tokens.css'].some((f) => fs.existsSync(f));
  const reducedMotionElsewhere = files.some((f) => CSS_EXT.has(path.extname(f)) && /prefers-reduced-motion/.test(fs.readFileSync(f, 'utf8')));
  /** @type {StaticFinding[]} */
  const findings = [];
  for (const f of files) findings.push(...scanText(fs.readFileSync(f, 'utf8'), { file: path.relative(process.cwd(), f) || f, hasTokens, reducedMotionElsewhere }));
  const floor = findings.filter((f) => f.tier === 'floor');
  if (json) process.stdout.write(`${JSON.stringify({ code: floor.length ? 1 : 0, files: files.length, findings }, null, 2)}\n`);
  else {
    process.stdout.write(`design-check static: ${files.length} file${files.length === 1 ? '' : 's'}, ${floor.length} floor, ${findings.length - floor.length} reflex/finish\n`);
    for (const f of findings) process.stdout.write(`  ${f.tier.padEnd(6)}  ${f.rule.padEnd(16)}  ${f.file}:${f.line}  ${f.match}\n`);
  }
  return floor.length ? 1 : 0;
}

// Run as a command, not when the hook or a test imports it.
if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await cli();
