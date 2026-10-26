---
name: design-tokens
description: "Apply when UI work starts in a project with no design tokens, when the person asks for a design system, a theme, a look or a rebrand, or says the UI looks generic. Finds the token source and holds every value to it; with none, offers three directions drawn from the product's subject, then writes design-tokens.json (colour, type, space, radius, shadow, motion) and Design.md. Not for backend work."
---

# Work from the tokens

Hardcoded values are why generated UI looks generic, and so is a look nobody chose. One source of truth fixes the first: every colour, size, space, radius, shadow and motion value comes from a token, and a value that is not a token is a bug. A chosen direction fixes the second. `PACK="$(bench pack)"` - if `bench` is not on your PATH, it is the newest `~/.local/share/bench/releases/*/pack`.

1. **Find the source.** Look for `design-tokens.json`, `tokens.json`, `tokens.css`, CSS custom properties on `:root`, a Tailwind or theme config, a component library (MUI, Chakra, shadcn, a local `ui/` folder), and a `Design.md`. If there is one, it is the source: use it, and **do not invent new values**. A value it lacks is a question for the person, or a token added to the source with its reason - never a literal in a component. A source with no motion tokens gets them added (step 4), with the person told.
2. **No source: onboard.** Ask the person, in one `AskUserQuestion` round where you can:
   - form factor - phone, desktop, both;
   - app type - tool, content, commerce, dashboard, game, …;
   - identity - the name, who uses it, the one core action, three vibe words;
   - colour direction - a brand colour, or a mood to derive one from.
   Unanswered is fine: choose, and say you chose.
3. **Offer three directions, then choose one** - `$PACK/references/directions.md`, in full:
   - inventory the subject (8-12 things only this product has) and write each direction as *On this screen, X becomes Y*, with its palette, type, density, motion personality and signature;
   - review each against the generic default (would a neighbouring prompt land on the same plan?) and against `$PACK/references/tells.md`; revise what is default, and say what changed - this is the mirror test;
   - read the cross-project history, `node "$PACK/skills/design-tokens/directions.mjs" list`, and do not repeat a recent one;
   - offer the three in one `AskUserQuestion`, recommended first; record the chosen one with `directions.mjs add` and in `Design.md`.
4. **Write `design-tokens.json`** at the root - the canonical source, and where `design-check` finds it. Plain values with their units (`"16px"`, `"#0b6bcb"`, `"200ms"`), which is what the gate reads, grouped: `color.light.*` and `color.dark.*` (the same names in both: `bg`, `surface`, `text`, `text-muted`, `primary`, `on-primary`, `border`, `danger`, …), `font.family` (one by default), `font.size.*` (a scale - 12, 14, 16, 20, 24, 32, 40 or a ratio you state), `font.weight.*`, `line-height.*`, `space.*` (multiples of 8, with 4 for hairline gaps), `radius.*` (nested radii concentric: outer = inner + padding), `shadow.*`, and `motion.*`:
   - `motion.personality` - one of Precise (the default for tools), Calm, Playful, Energetic, from the vibe words;
   - `motion.ease` - one signature curve for most motion, with `motion.ease-move` only if the personality needs a second;
   - `motion.duration.quick` / `.standard` / `.slow` - three durations; for Operate views `slow` stays at or under 300ms;
   - `motion.entrance` - one entrance style, in words ("fade + 6px rise").
   The table of starting values per personality is in `$PACK/references/motion.md`. Text on its background meets 4.5:1 in both themes - check it.
5. **Write `Design.md`** beside it: the direction and its sentence, and why it beat the other two; the tokens in light and dark, the type scale, the 8pt spacing, the motion personality; the hierarchy rules (one primary action per view, in the accent; size, weight and colour in that order of emphasis), section isolation (sections are separated by space or a surface change, not by stacking borders), whether this product uses borders or shadows for its surfaces; a `## Waivers` section, empty until a reflex rule is waived with its reason; and **a stated rationale for each decision** - why this colour for these vibe words, why this scale for this form factor.
6. **Wire it.** Generate the CSS custom properties from the JSON (a `tokens.css`, or the theme file the stack expects) with dark under `prefers-color-scheme: dark` or the app's theme switch, and read every value from them. Then add to the project's `CLAUDE.md`, between `<!-- bench:design-tokens -->` markers, one line: tokens in `design-tokens.json` are mandatory, `Design.md` says why, and no hardcoded values. Commit all of it.
7. **Prove it.** Run `design-check` (it enforces the tokens: a colour or spacing written out instead of taken from a token is a defect, and a size off the type scale is a reflex warning) and fix what it names.
8. **Then the screen.** Tokens say what values to use, not what to build: before the first component, write its brief with `design-brief`, which picks the patterns in `$PACK/patterns/`.

**Reply:** where the tokens came from (found, or onboarded and with what answers), the three directions offered and which won and why, the files written, and the gate's real output.
