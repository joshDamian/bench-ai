# rules

Every design rule Bench holds a UI to sits in one of three tiers. The tier says whether you may disagree with it.

- **Floor** - never waived. Access, tokens, honesty, and the eyebrow. A floor finding is a defect: `design-check` exits 1 on it, and the work is not done while one stands.
- **Reflex** - a default generated UI falls into (`tells.md`). Fix it, or waive it on the record: a list item under `## Waivers` in the project's `Design.md`, with the reason it is right for this product. A reflex finding is a warning; it never changes the exit code.
- **Finish** - polish (`polish.md`). Fix it or say why not. Never blocking.

The ids below are the ones `design-check` and the design hook report, from `skills/design-check/rules.mjs`, which is the one list both read. `browser` means the gate measures the rendered page, `static` that the hook and the scanner read the source, `judged` that only a person or `design-review` can tell.

## The floor

| Rule | Seen by | The defect | What it should be |
| --- | --- | --- | --- |
| `contrast` | browser | Text under 4.5:1 on its background (3:1 for large headings), at rest, hovered or focused, in either theme | A text token that meets the ratio on that surface |
| `target` | browser | A control under 24x24px that is not a link inside a sentence | 24px minimum; 40-44px for primary and touch controls |
| `type` | browser | Body text under the project's smallest body size (16px unless `Design.md` decided another) | The body step of the scale |
| `spacing` | browser | A margin, padding or gap off the grid or not a spacing token | A `space.*` token |
| `tokens` | both | A colour or shadow written out where the project keeps tokens; a `var()` never declared | The token, or a new token added to the source with its reason |
| `semantics` | browser | Something clickable that is not a button or a link | `<button>` for actions, `<a href>` for navigation |
| `focus` | both | Focus that looks like nothing, or `outline: none` with nothing in its place | A `:focus-visible` ring at 3:1, shown at once, never faded in |
| `overflow` | browser | The page scrolls sideways at a phone width | `min-width: 0`, `minmax(0, 1fr)`, `overflow-wrap` on what is too wide |
| `motion` | both | Movement that carries on under `prefers-reduced-motion`, or a stylesheet that animates with no fallback anywhere | A `prefers-reduced-motion: reduce` block that keeps opacity and colour and drops the travel |
| `hidden-at-rest` | both | Content held at opacity 0 until a script shows it, so the page is blank when the script fails | Visible by default; hide only under a class the script adds, or enter with `@starting-style` |
| `alt` | browser | An image with no `alt` | `alt` that says what it shows, `alt=""` for decoration |
| `no-zoom` | both | A viewport that stops zoom | `width=device-width, initial-scale=1` |
| `eyebrow` | both | A small label above a heading that announces it: kicker, overline, pill chip over a headline, `TRUSTED BY` over logos | The fact goes in the heading or the line under it, or nowhere |
| `placeholder` | both | Placeholder content shipped as real: lorem ipsum, Jane Doe, Acme Inc | The product's real content, or sample data at the data boundary, said to be sample |
| `invented-proof` | judged | A metric, quote, rating, logo wall or customer count nobody supplied | The person's own proof, the product shown working, or nothing |

**The eyebrow test.** Small text before a heading survives only when it carries a fact, a destination or an identifier the reader would otherwise lack: a `<time>`, a byline, a breadcrumb, a live status. Delete the line and read the page; if all that was lost was a name for what comes next, it was an eyebrow.

**Invented proof.** No machine can tell a real "4,000 teams" from a made-up one, so this is judged, and judged every time: a number, a quote, a logo or a rating is on the page only if the person gave it. A section the page needs and has no proof for shows the product doing its job, or is left out. Sample data in a demo is fine when it is entered at the data boundary and named as sample (`worst-case-data.md`).

## Waivers and ignores

- **Waive** a reflex rule in `Design.md`, so the reason lives with the design:
  ```md
  ## Waivers
  - `glass`: the map's toolbar and sheet are the only two blurred surfaces, over moving tiles
  ```
  The reason says why it is right here, in a few words about this product. A waiver naming a floor rule is refused and the rule still fires; both the gate and the hook say so.
- **Ignore** a reflex or finish rule for the project in `.bench/design-check.json`: `"ignoreRules": ["text-wrap"]`. Prefer a waiver: an ignore says nothing about why. Floor ids are refused.
- **Out of scope**: `"ignore": [".third-party-widget", "vendor.css"]` (selectors and stylesheets the gate leaves alone), `"ignoreFiles": ["src/legacy/**"]` (files the hook leaves alone). Each one is a hole; put the reason beside it.
- A source line can carry `design-check-ignore` (or `design-check-ignore-next-line` above it) to quiet reflex and finish rules on that line only.

## Who checks what

- **The design hook** (on by default, in the pack's `hooks/hooks.json`) reads the lines each Edit or Write puts into a UI file against the static rules, and tells the session in the same turn. Quiet when clean. Off for a project with `"hook": false` in `.bench/design-check.json`; off for one run with `BENCH_DESIGN_HOOK=0`.
- **The static scanner**, the hook's engine, reads whole files or folders: `node "$(bench pack)/skills/design-check/static.mjs" src --json`. Exit 1 on a floor finding.
- **`design-check`** renders the page and measures every `browser` rule, in light and dark, hovered, focused, without scripts and with reduced motion. Exit 0 pass, 1 a floor defect, 2 could not look.
- **`design-review`** judges what nothing can measure, `judged` rules included, against the rubric.
