---
name: design
description: "Apply when about to build, restyle, animate or review anything a person sees - a screen, page, component, layout, theme, CSS, copy on a screen, motion, or an animated export of a design - in any stack. Not for backend, API, CLI or data work with no UI. Decides the view's mode, which rules apply, and routes to design-tokens, design-brief, the patterns, the references, design-check, verify-ui and design-review."
---

# Design: start here for any UI work

Generated UI looks generic because a session starts drawing before it decides anything, then fills the gaps with defaults. This skill makes the decisions in order and points at what to read. `PACK="$(bench pack)"` is where everything lives - if `bench` is not on your PATH, it is the newest `~/.local/share/bench/releases/*/pack`.

## 1. Name the mode

Each view has a mode, from what success looks like on that surface - not from the product. A tool's landing page is Persuade; a fashion brand's docs are Read.

| Mode | The person | Surfaces | Ambition |
| --- | --- | --- | --- |
| **Operate** | completes a task | app screens, dashboards, settings, editors, admin, tools | precision and restraint; known patterns earn their place; motion only answers an action, under 300ms |
| **Persuade** | decides and acts | landing, marketing, pricing, campaigns | full commitment: an idea from the subject, real pictures, one signature motion moment |
| **Read** | understands | docs, articles, guides, changelogs | structure for comprehension, a measure worth staying in, almost no motion |
| **Experience** | is inside the work | portfolios, galleries, showcases | the work leads from the first screen; the interface recedes |
| **Showcase motion** | watches a design come alive | an animated export (MP4, GIF) of a design | an exact replica, slow loops (`references/showcase-motion.md`) |

Record each view's mode in `Design-brief.md` (`**Mode:** Operate`). A view's mode decides which guidance applies below; the floor applies to all of them.

## 2. The three tiers

Everything is held to `references/rules.md`:

- **Floor**, never waived: contrast, focus-visible, targets, semantics, reduced-motion fallback, content visible without scripts, alt text, zoom; tokens only; never invented proof (metrics, quotes, logos, testimonials) or placeholder copy; no eyebrow label above a heading.
- **Reflex**, the tells generated UI falls into (`references/tells.md`): fix, or waive with a reason under `## Waivers` in `Design.md`.
- **Finish**, polish (`references/polish.md`): fix or answer.

The design hook reads every UI edit against the static rules and tells you in the same turn. Act on what it says rather than re-auditing by hand; floor findings are fixed before you go on.

## 3. Route

Do the first that applies, then carry on down:

1. **No tokens yet** (`design-tokens.json`, `tokens.css`, theme config) - `design-tokens`. It offers three directions from the subject, writes the tokens (motion included) and `Design.md`.
2. **A new view, or a view whose job changed** - `design-brief`: the job, mode, primary action, states and both widths, before any component.
3. **Building** - read the patterns the brief names (`$PACK/patterns/`), then by mode:
   - every mode: `references/copy.md`, `references/polish.md`;
   - Operate: `references/honest-data.md` (the data, before the screen) and `references/worst-case-data.md` (before you call it done);
   - Persuade: `references/landing-page.md` (its run replaces "start with the hero");
   - anything that moves: `references/motion.md`; an animated export: `references/showcase-motion.md`.
4. **A small change** to an existing screen - keep its identity: the tokens and `Design.md` win; touch only what was asked.
5. **Before you finish** - `design-check` (the gate: 0 passed, 1 a floor defect, 2 could not look), `verify-ui` (before and after screenshots), then `design-review` for anything a person will look at closely.

## 4. While building

- Values come from tokens; a missing value is a token added with its reason, never a literal.
- One primary action per view, in the accent colour. One typeface unless a second has a job. Sizes on the type scale. Concentric radii. `text-wrap: balance` on headings, `pretty` on body. The full state set: hover, active, focus, loading, empty, error.
- Headlines two lines at most; equal outer margins; no blank stretches; buttons verb + object in sentence case; specific numbers.
- Spend boldness in one place, keep the rest quiet. Then look at the render - a screenshot shows what the code only intends.

**Reply** with the mode you named for each view and the route you took, so the person can correct either.
