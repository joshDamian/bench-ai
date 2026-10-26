# polish

The finish tier: details nobody names and everybody feels. None blocks; each one found is fixed or answered. Ids are what `design-check` and the hook report (`rules.md`); the rest are for `design-review` and your own pass. Express every fix in the project's own styling system and tokens - never a second system to apply one detail.

## Type

- **Wrapping.** `text-wrap: balance` on headings, `text-wrap: pretty` on body copy, set once in the base styles (`text-wrap`). No one word alone on a last line.
- **Figures.** `font-variant-numeric: tabular-nums` on tables, totals, timers and anything that updates (`tabular-nums`). Numbers right-aligned in columns, with their headers.
- **Marks.** … not `...`; curly quotes in prose; an en dash for ranges, 9–5; a non-breaking space between a number and its unit, `10&nbsp;MB` (`typography-marks`). Loading text ends with …: "Saving…".
- **Measure.** Body lines 45-75 characters (`max-width: 65ch`). Headings carry more space above than below.
- **Smoothing.** `-webkit-font-smoothing: antialiased` on the root on macOS.
- **Headings in order.** h1, h2, h3 - no level skipped (`heading-skip`).

## Surfaces

- **Concentric radii.** Outer radius = inner radius + the padding between them (`concentric-radius`). Past about 24px of padding the two are separate surfaces: choose each.
- **Optical alignment.** An icon beside text takes 2px less padding on its side; a play triangle sits 1-2px right of centre.
- **Elevation.** A shadow only on what floats - menus, dialogs, a sticky bar - unless `Design.md` decided otherwise; structural borders stay borders.
- **Image edges.** A 1px outline at 10% black (10% white in dark) on photographs, so they hold their edge on any ground.
- **Icons.** One set. Stroke matched to the text weight beside it (1.5px by regular text, 2px by semibold). `currentColor`, states from colour and opacity; outline by default, fill for the active state.
- **Hit areas.** 40px minimum in dense desktop UI, 44px for touch; grow a small control's area with a pseudo-element, never overlapping its neighbour's.
- **Images reserve their space.** `width` and `height`, or `aspect-ratio`, so nothing jumps (`img-size`).

## The browser's own surfaces

The parts you did not draw still ship. Theme them from the tokens (`browser-surfaces`):

- `::selection` in the accent's tint;
- `caret-color` on fields;
- `scrollbar-color` (and `::-webkit-scrollbar`) on scrolling panes;
- `accent-color` on checkboxes, radios and ranges;
- the focus ring from `color.primary`, offset 2px;
- `text-underline-offset` on links;
- `color-scheme: dark` on a dark theme, and `<meta name="theme-color">` matching the page; a `lang`, a `<title>` (`doc-meta`).

## States

Every control has the full set: rest, hover, active (pressed), focus-visible, disabled, loading. Every view that loads has empty, loading and error (`patterns/states.md`). Active is `scale(0.97)` or a 1px drop for 100-160ms; hover feedback answers in under 100ms; anything over 300ms feels slow (`slow-feedback`).

## Interaction

- **Forms.** Labels on every field, clickable; the right `type`, `inputmode` and `autocomplete`; paste never blocked; errors inline, next to the field, the first one focused on submit; the submit button live until the request starts, then a spinner in it.
- **Destructive actions** confirm, or offer undo for a window.
- **Long content.** Flex children that hold text get `min-width: 0`; every text box has a plan for a long value: wrap, truncate with the full value reachable, or clamp (`worst-case-data.md`).
- **URLs keep state.** Filters, tabs, pagination and open panels live in the query, so a reload and a shared link land in the same place.
- **Touch.** `touch-action: manipulation`; `overscroll-behavior: contain` in sheets and drawers; hover effects behind `@media (hover: hover) and (pointer: fine)` (`hover-ungated`); safe-area insets on fixed bars; `100dvh`, not `100vh` (`vh-units`).
- **Locale.** Dates, numbers and plurals through `Intl` - never "1 members".

## Motion details

`motion.md` decides whether to animate. Once it does: name the properties, never `all`; enter from `scale(0.95)` and opacity 0, never `scale(0)` (`scale-zero`); ease-out for enter and exit, ease-in-out for movement on screen, never ease-in on UI (`ease-in`); transitions, not keyframes, for anything a person can trigger twice quickly; `will-change` only on what stutters, and only `transform`, `opacity` or `filter`; no enter animation on first render.

## The craft floor, in one look

Before you call a screen finished, one batched look at the render answers all of these: contrast holds on every surface; shadows have offset and blur; related things close, unrelated far, more space above a heading than below; the body measure holds at every width with the real copy; one authored motion moment, not scattered effects; every state present; the browser's surfaces themed; controls named for their action and errors naming the fix; every requirement of the brief findable in seconds.

Adapted from Jakub Krehel's make-interfaces-feel-better, Vercel's Web Interface Guidelines, Impeccable's craft floor and Emil Kowalski's review checklist; see `CREDITS.md`.
