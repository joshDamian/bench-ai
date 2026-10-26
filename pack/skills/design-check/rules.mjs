// @ts-check
// The one list of what the design gate and the design hook hold a page to, in three tiers:
//
//   floor   never waived: access, tokens, honesty, the eyebrow. A floor finding is a defect,
//           and design-check exits 1 on it.
//   reflex  a default generated UI falls into. Waivable only with a reason written in the
//           project's Design.md, under `## Waivers`. Reported as a warning; never changes the
//           exit code.
//   finish  polish. Reported as a note to answer; never blocks.
//
// `how` says who can see it: `browser` design-check measures the rendered page, `static` the
// hook and the scanner read the source, `both`, or `judged` - only a person or design-review
// can tell (the references say how to judge it).
//
// The references say the same thing in prose: floor rules in references/rules.md, reflex
// rules in references/tells.md, finish rules in references/polish.md. A test holds every id
// here to appearing in its file, so the two cannot drift apart.

/** @typedef {'floor'|'reflex'|'finish'} Tier */
/** @typedef {{id:string, tier:Tier, how:'browser'|'static'|'both'|'judged', tell:string, instead:string}} Rule */

/** @type {Rule[]} */
export const RULES = [
  // Floor: access, tokens, honesty.
  { id: 'contrast', tier: 'floor', how: 'browser', tell: 'Text under 4.5:1 on its background (3:1 for large headings), at rest, on hover or on focus, in either theme', instead: 'A text token that meets the ratio on that surface' },
  { id: 'target', tier: 'floor', how: 'browser', tell: 'A control under 24x24px that is not a link inside a sentence', instead: 'A 24px minimum box, 40-44px for primary and touch controls' },
  { id: 'type', tier: 'floor', how: 'browser', tell: 'Body text under the project\'s smallest body size (16px unless Design.md set another)', instead: 'The body step of the type scale' },
  { id: 'spacing', tier: 'floor', how: 'browser', tell: 'A margin, padding or gap off the spacing grid, or not one of the spacing tokens', instead: 'A `space.*` token' },
  { id: 'tokens', tier: 'floor', how: 'both', tell: 'A colour or shadow written out where the project keeps tokens, or a `var()` that was never declared', instead: 'The token, or a new token added to the source with its reason' },
  { id: 'semantics', tier: 'floor', how: 'browser', tell: 'Something clickable that is not a button or a link', instead: '`<button>` for actions, `<a href>` for navigation' },
  { id: 'focus', tier: 'floor', how: 'both', tell: 'A focusable element that looks the same focused, or `outline: none` with nothing in its place', instead: 'A `:focus-visible` ring at 3:1, shown at once' },
  { id: 'overflow', tier: 'floor', how: 'browser', tell: 'The page scrolls sideways on a phone', instead: '`min-width: 0`, `minmax(0, 1fr)`, `overflow-wrap: anywhere` on the thing that is too wide' },
  { id: 'motion', tier: 'floor', how: 'both', tell: 'Movement that keeps going with prefers-reduced-motion set, or animation with no reduced-motion fallback', instead: 'A `prefers-reduced-motion: reduce` block that keeps opacity and colour and drops the travel' },
  { id: 'hidden-at-rest', tier: 'floor', how: 'both', tell: 'Content held at opacity 0 or hidden until a script reveals it, so the page ships blank when the script fails', instead: 'Visible by default; hide only under a class the script adds, or animate in with `@starting-style`' },
  { id: 'alt', tier: 'floor', how: 'browser', tell: 'An image with no `alt` attribute', instead: '`alt` that says what the picture shows, or `alt=""` for decoration' },
  { id: 'no-zoom', tier: 'floor', how: 'both', tell: 'A viewport that stops the person zooming (`user-scalable=no`, `maximum-scale=1`)', instead: '`width=device-width, initial-scale=1` and nothing else' },
  { id: 'eyebrow', tier: 'floor', how: 'both', tell: 'A small label above a heading that announces it: a kicker, an overline, a pill chip over the headline', instead: 'Put the fact in the heading or the sentence under it, or delete it' },
  { id: 'placeholder', tier: 'floor', how: 'both', tell: 'Placeholder content shipped as if real: lorem ipsum, Jane Doe, Acme Inc', instead: 'The product\'s real content, or plainly marked sample data at the data boundary' },
  { id: 'invented-proof', tier: 'floor', how: 'judged', tell: 'A metric, quote, logo wall, rating or customer count nobody supplied', instead: 'The person\'s own proof, the product shown working, or nothing' },

  // Reflex: the defaults generated UI falls into.
  { id: 'icon-tile-grid', tier: 'reflex', how: 'browser', tell: 'A row of same-size cards, each an icon in a rounded tile over a heading and a line', instead: 'A composition from the content: a list set large, a split, the product itself' },
  { id: 'nested-cards', tier: 'reflex', how: 'browser', tell: 'A card inside a card', instead: 'One containment layer; group inside it with space and type' },
  { id: 'hero-metric', tier: 'reflex', how: 'browser', tell: 'The hero-metric template: a row of big numbers over small labels', instead: 'The one number that matters, with what it means and its baseline; the rest in a table' },
  { id: 'gradient-text', tier: 'reflex', how: 'both', tell: 'Text filled with a gradient', instead: 'Solid ink; emphasis from size or weight' },
  { id: 'ai-palette', tier: 'reflex', how: 'both', tell: 'The purple/violet/indigo palette, or a purple-to-blue gradient', instead: 'A colour taken from the product\'s own subject' },
  { id: 'cream-terracotta', tier: 'reflex', how: 'both', tell: 'A warm cream ground with a terracotta or clay accent', instead: 'A palette from the subject; warmth from a stone, an ink or a photograph' },
  { id: 'mono-costume', tier: 'reflex', how: 'both', tell: 'Monospace on labels, captions or headings that are not code, data or a measurement', instead: 'The text face, a step smaller; tabular figures for numbers' },
  { id: 'section-numbers', tier: 'reflex', how: 'both', tell: '01 / 02 / 03 section markers, (001), "Step 02"', instead: 'Order by position and the words; an `<ol>` when it really is a sequence' },
  { id: 'tracked-caps', tier: 'reflex', how: 'both', tell: 'Tiny uppercase letter-spaced labels', instead: 'Sentence case in the body face, a step smaller, untracked' },
  { id: 'glass', tier: 'reflex', how: 'both', tell: 'Backdrop blur used as decoration on panels and cards', instead: 'An opaque surface one tone step from the ground; one blurred bar at most' },
  { id: 'side-tab', tier: 'reflex', how: 'both', tell: 'A thick coloured border on one side of a card, callout or list item', instead: 'A tone step, an icon, or the words' },
  { id: 'border-shadow-card', tier: 'reflex', how: 'browser', tell: 'A row of cards each wearing a hairline border and a wide soft shadow', instead: 'One of the two, as Design.md decides; a shadow only on what floats' },
  { id: 'dark-glow', tier: 'reflex', how: 'both', tell: 'A coloured glow: a zero-offset chromatic shadow or a radial halo behind content', instead: 'Depth from lightness steps; shadows with offset and blur' },
  { id: 'bounce-easing', tier: 'reflex', how: 'both', tell: 'Bounce, elastic or overshoot easing on UI', instead: 'A strong ease-out (`motion.ease`)' },
  { id: 'pulsing-dot', tier: 'reflex', how: 'both', tell: 'A small dot pulsing forever to look live', instead: 'A still dot with a label; pulse only data that really is live' },
  { id: 'marquee', tier: 'reflex', how: 'both', tell: 'Content that scrolls by itself forever', instead: 'A still row the reader moves through at their own pace' },
  { id: 'middle-dot-meta', tier: 'reflex', how: 'both', tell: 'Meta strings joined with middle dots: A · B · C', instead: 'Line breaks, columns, or one clause of plain words' },
  { id: 'arrow-link', tier: 'reflex', how: 'both', tell: 'An arrow glued to link or button text: "Learn more →"', instead: 'A label that names where it goes; at most one arrow, on the primary action' },
  { id: 'accent-word', tier: 'reflex', how: 'browser', tell: 'One word of a heading set apart by colour, italic, weight or face', instead: 'The heading in one weight, one style, one colour' },
  { id: 'overused-font', tier: 'reflex', how: 'both', tell: 'The face every generator reaches for (Inter, Roboto, Poppins, Space Grotesk, Geist, Fraunces, Playfair Display, ...) chosen by default', instead: 'A face chosen for this product, with the reason in Design.md' },
  { id: 'font-count', tier: 'reflex', how: 'browser', tell: 'More than two typefaces in running use', instead: 'One typeface by default; two when the second has a job' },
  { id: 'off-scale-type', tier: 'reflex', how: 'browser', tell: 'A font size that is not a step of the type scale', instead: 'The nearest `font.size.*` step' },
  { id: 'long-headline', tier: 'reflex', how: 'browser', tell: 'A headline that wraps past two lines', instead: 'Cut words, then widen the measure; size last' },
  { id: 'stock-phrase', tier: 'reflex', how: 'both', tell: 'Stock marketing phrases: seamless, supercharge, unlock, elevate, empower, cutting-edge, world-class, "not just X"', instead: 'The specific thing it does, in its own nouns and a real number' },
  { id: 'generic-label', tier: 'reflex', how: 'both', tell: 'A button or link named "Learn more", "Get started", "Click here", "Submit"', instead: 'Verb + object: "Book a table", "Save changes"' },
  { id: 'em-dash', tier: 'reflex', how: 'both', tell: 'Em dashes as a habit, five or more on a page', instead: 'A full stop or a comma' },
  { id: 'emoji-icons', tier: 'reflex', how: 'both', tell: 'Emoji standing in for icons or bullets', instead: 'One icon set, or the words' },
  { id: 'transition-all', tier: 'reflex', how: 'both', tell: '`transition: all`', instead: 'The properties that change: `transition: transform var(--motion-quick) var(--motion-ease)`' },

  // Finish: details that make it feel built, never blocking.
  { id: 'text-wrap', tier: 'finish', how: 'browser', tell: 'Headings without `text-wrap: balance`, paragraphs without `text-wrap: pretty`', instead: 'Both, set once in the base styles' },
  { id: 'tabular-nums', tier: 'finish', how: 'browser', tell: 'Numbers in tables, totals or counters set in proportional figures', instead: '`font-variant-numeric: tabular-nums`' },
  { id: 'browser-surfaces', tier: 'finish', how: 'browser', tell: 'Selection, caret, scrollbar and form accents left at the browser\'s defaults', instead: '`::selection`, `caret-color`, `scrollbar-color`, `accent-color` from the tokens' },
  { id: 'concentric-radius', tier: 'finish', how: 'browser', tell: 'A rounded box inside a rounded box with the same radius', instead: 'Outer radius = inner radius + the padding between them' },
  { id: 'heading-skip', tier: 'finish', how: 'browser', tell: 'A heading level skipped (h1 then h3)', instead: 'The next level down' },
  { id: 'img-size', tier: 'finish', how: 'browser', tell: 'An image with no width and height, so the page jumps as it loads', instead: '`width` and `height` attributes, or an `aspect-ratio`' },
  { id: 'doc-meta', tier: 'finish', how: 'browser', tell: 'A document with no `lang`, no `<title>`, or a dark theme with no `color-scheme`', instead: 'All three' },
  { id: 'slow-feedback', tier: 'finish', how: 'browser', tell: 'A control whose hover or press feedback takes over 300ms', instead: '`motion.duration.quick` (100-200ms)' },
  { id: 'typography-marks', tier: 'finish', how: 'both', tell: 'Three dots for an ellipsis, or straight quotes in prose', instead: '… and curly quotes' },
  { id: 'ease-in', tier: 'finish', how: 'static', tell: '`ease-in` on a UI transition: it starts slow, at the moment the person is watching', instead: 'ease-out for enter and exit, ease-in-out for movement on screen' },
  { id: 'scale-zero', tier: 'finish', how: 'static', tell: 'An entrance from `scale(0)`', instead: '`scale(0.95)` with opacity 0' },
  { id: 'hover-ungated', tier: 'finish', how: 'static', tell: 'Hover movement that touch screens trigger on tap', instead: 'Wrap it in `@media (hover: hover) and (pointer: fine)`' },
  { id: 'vh-units', tier: 'finish', how: 'static', tell: '`100vh`, which jumps on phones as the toolbar shows and hides', instead: '`100dvh` or `100svh`' },
];

/** @type {Map<string, Rule>} */
export const BY_ID = new Map(RULES.map((r) => [r.id, r]));

/** @param {string} id */
export const tierOf = (id) => BY_ID.get(id)?.tier || 'floor';

/**
 * The reflex waivers a project has written down, from its Design.md: list items under a
 * `## Waivers` heading, `` - `glass`: the toolbar is the one blurred bar, over the canvas ``.
 * A waiver must give a reason of a few words; one that names a floor rule is refused.
 * @param {string} text the Design.md text
 * @returns {{waived:Map<string,string>, refused:string[]}}
 */
export function readWaivers(text) {
  /** @type {Map<string,string>} */
  const waived = new Map();
  /** @type {string[]} */
  const refused = [];
  const section = /^##\s+Waivers\s*$([\s\S]*?)(?=^##\s|(?![\s\S]))/m.exec(String(text || ''));
  if (!section) return { waived, refused };
  for (const m of section[1].matchAll(/^\s*[-*]\s+`?([a-z][\w-]*)`?\s*[:–—-]\s*(.+)$/gm)) {
    const [, id, reason] = m;
    const rule = BY_ID.get(id);
    if (!rule) refused.push(`${id}: no such rule`);
    else if (rule.tier === 'floor') refused.push(`${id}: a floor rule is never waived`);
    else if (reason.trim().split(/\s+/).length < 4) refused.push(`${id}: the reason has to say why, in a few words`);
    else waived.set(id, reason.trim());
  }
  return { waived, refused };
}

/**
 * Which rules a project has asked not to hear about: reflex and finish ones only.
 * @param {unknown} list @returns {{ignored:Set<string>, refused:string[]}}
 */
export function readIgnores(list) {
  /** @type {Set<string>} */
  const ignored = new Set();
  /** @type {string[]} */
  const refused = [];
  for (const id of Array.isArray(list) ? list.map(String) : []) {
    const rule = BY_ID.get(id);
    if (!rule) refused.push(`${id}: no such rule`);
    else if (rule.tier === 'floor') refused.push(`${id}: a floor rule cannot be ignored`);
    else ignored.add(id);
  }
  return { ignored, refused };
}
