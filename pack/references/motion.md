# motion

Motion in a product answers a person's action or explains a change of state. Anything else has to earn its place, and the mode of the view (`design` skill) sets how much it may earn. The values come from the project's motion tokens (`design-tokens`): one personality, one signature easing, three durations, one entrance style. A value that is not a token is a question, as with colour.

## Should it move at all?

Ask how often the person will see it, before anything else:

| How often | Decision |
| --- | --- |
| 100+ times a day: keyboard shortcuts, a command palette, core navigation | No animation. Ever. |
| Tens of times a day: hovers, list navigation, toggles | None, or a near-instant opacity or colour change |
| Occasionally: dialogs, drawers, toasts, a settings save | The standard animation |
| Rarely: first run, an empty state filled, a success | Room for one moment of delight |

**Never animate a keyboard-initiated action.** It repeats hundreds of times a day and the animation makes it feel late.

Then name the purpose, in one word: **feedback** (the press was heard), **spatial** (where it came from, where it went), **state** (what changed), **continuity** (nothing teleports), **explanation** (marketing and onboarding only), or **delight** (rare moments only). "It looks nice" on something seen often is not a purpose: delete it. When you cannot tell whether motion feels right, removing it is usually the better design.

## Easing

| The element is | Easing |
| --- | --- |
| Entering or leaving | ease-out: it starts fast, so it answers at once |
| Moving or morphing on screen | ease-in-out |
| Changing colour on hover | ease |
| Moving constantly (a progress bar) | linear |

Never ease-in on UI: it starts slow at the moment the person is watching, and a 200ms ease-in feels slower than a 200ms ease-out. The built-in curves are weak; the project's `motion.ease` is a strong custom one, for example `cubic-bezier(0.23, 1, 0.32, 1)` (ease-out) and `cubic-bezier(0.77, 0, 0.175, 1)` (in-out). Overshoot and bounce are a reflex tell on UI (`bounce-easing`); a Playful personality may use a small one on its primary action, declared in `Design.md`.

## Duration

| Element | Duration |
| --- | --- |
| Press feedback | 100-160ms |
| Tooltips, small popovers | 125-200ms |
| Dropdowns, selects | 150-250ms |
| Dialogs, drawers | 200-300ms (up to 500ms for a large drawer) |
| Persuade sections, showcase | longer, below |

**UI motion stays under 300ms.** A 180ms dropdown feels more responsive than a 400ms one, and a faster spinner makes the same load feel shorter. Exits are faster than entrances - a person cares about what arrives, not what leaves. What a person is deliberately doing can be slow (hold-to-delete fills over 2s, linear); the system's answer is always fast (release snaps back in 200ms).

## Craft rules

- **Compositor-only.** Animate `transform` and `opacity` (and, carefully, `filter` and `clip-path`). Never `width`, `height`, `top`, `left`, `margin` or `padding`: they relayout every frame.
- **Name the properties.** Never `transition: all` (`transition-all`).
- **Nothing appears from nothing.** Enter from `scale(0.95)` with opacity 0, never `scale(0)`.
- **Origin-aware popovers.** A menu, popover or tooltip scales from its trigger (`transform-origin` on the trigger's side); a dialog stays centred.
- **Interruptible.** Anything a person can trigger twice quickly - toasts, toggles, an accordion - uses transitions, which retarget, not keyframes, which restart. Springs for drags and gestures.
- **Stagger** a group entering together by 30-80ms an item; never block input while it plays.
- **Tooltips**: a delay on the first, then instant for the neighbours while one is open; keyboard focus shows a tooltip with no delay.
- **No entrance on first render** of a page a person is returning to; `@starting-style` (or a mounted flag) for things that genuinely arrive.
- **A crossfade that shows two objects** overlapping gets 2px of blur through the middle of it.
- **Motion is never the only signal**: every animated state change also changes colour, an icon, or a label.

## Reduced motion

Every animation has a `prefers-reduced-motion: reduce` fallback (`motion` is a floor rule). Reduced means gentler, not nothing: keep opacity and colour changes that carry meaning, drop travel, scale, parallax and loops. Looping decoration stops. Content is visible with scripts off: a reveal hides only under a class the script adds (`hidden-at-rest`).

## By mode

- **Operate** (app screens, tools, settings): only the rules above. Motion answers an action - a press, an open, a save, a reorder - and stays under 300ms. No entrances on scroll, no ambient loops, no signature moment. Brand lives in how crisp it is.
- **Read** (docs, articles): almost none. Anchor jumps scroll; nothing else moves on its own.
- **Persuade** (landing, marketing, pricing): **one authored signature moment** - the thing a visitor would remember, made from the subject (`landing-page.md`) - plus a quiet entrance for each section's parts: heading, then text, then picture, 60-90ms apart, rising 16-32px with opacity, once, as the section arrives. Never the same fade on every block. A word-by-word tagline reveal is one option for the signature, not a rule. 400-800ms is fine here; controls still answer in under 300ms.
- **Experience** (portfolios, showcases): the work leads and may move as the work needs; the interface around it follows Operate.
- **Showcase motion** (an animated export of a design): its own slow timing and its own method, `showcase-motion.md`.

## Motion tokens

`design-tokens` writes four, from one personality:

| Personality | Signature ease | quick / standard / slow | Entrance |
| --- | --- | --- | --- |
| Precise (tools, dashboards; the default) | `cubic-bezier(0.2, 0, 0, 1)` | 120 / 200 / 300ms | fade + 4-8px rise |
| Calm (premium, editorial) | `cubic-bezier(0.4, 0, 0.2, 1)` | 200 / 350 / 600ms | slow fade + scale 0.98 to 1 |
| Playful (consumer, games, kids) | `cubic-bezier(0.34, 1.3, 0.64, 1)`, declared | 150 / 250 / 400ms | rise with a small settle |
| Energetic (sport, music, events) | `cubic-bezier(0.16, 1, 0.3, 1)` | 100 / 180 / 300ms | snap in from an edge |

One curve covers most of the motion; a product borrows another personality only for a single moment (a success), and says so in `Design.md`.

## Reviewing motion

Play it at 10% speed and frame by frame. Check: the right easing for its direction; the origin; the properties in sync; nothing on a keyboard action; exits faster than entrances; reduced motion. Report as a table, `| Before | After | Why |`, one row per issue, and end **Block** (anything sluggish, from-nowhere, on a frequent or keyboard action, or off the compositor with an easy fix) or **Approve**.

Adapted from Emil Kowalski's design-engineering and animation skills, LottieFiles' motion-design skill, Jakub Krehel's animation details and Vercel's guidelines; see `CREDITS.md`.
