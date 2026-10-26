# critique

How to judge a rendered screen: the passes, the score, the caps, and the heuristics. Use it on screenshots, never on the code - you wrote the code and will read your intentions into it; the picture shows only what is there. A passing gate means nothing banned was measured, never that the screen is good.

## The passes

Make each on its own, at both widths, in both themes.

1. **One second, then five.** Glance away: what did you see? One answer - a word, a picture, a number - or there is no focal point. Then five seconds as a stranger: what is this, who is it for, what do I do next?
2. **Squint.** The screen reduces to two or three masses of clearly different size. Many small equal masses: nothing leads.
3. **Cover the name.** Could this belong to a competitor, or another industry? Swap the name and the noun: if it still works, the screen has no idea of its own.
4. **Edges.** Count the distinct left edges down the screen: two or three reused on purpose, not six.
5. **Space and frame.** Related things close, unrelated far; the gap between sections larger than any inside one; equal outer margins; no region empty by accident; no section huddled on one side.
6. **Type.** A decisive step from body to headline; five sizes or fewer; no orphan on a headline's last line; body lines under about 75 characters.
7. **Colour.** Which colour holds most of the surface, which supports, where is the accent? An accent in ten places means nothing; on the one action, it means "here".
8. **The brief.** Every view's job, mode, primary action, hierarchy, states and phone layout as `Design-brief.md` says. A contradiction is a finding.
9. **Sameness.** One word per section's layout (split, list, grid, statement, table). The same word three times is the template showing.
10. **Phone.** Its own composition, or the desktop squeezed?
11. **States and data.** Empty, loading, error and the worst case present (`worst-case-data.md`); totals that add up (`honest-data.md`).
12. **Tells.** Anything from `tells.md` the gate could not see.

## The score

Four criteria, 1-10 each, weighted by the view's mode:

| Criterion | Operate / Read | Persuade / Experience |
| --- | --- | --- |
| **Usability** - orientation in three seconds, readable, obvious actions, honest phone layout | 40% | 30% |
| **Design** - composition, type, colour, imagery, craft | 35% | 40% |
| **Content** - specific, true, edited words and data | 15% | 10% |
| **Creativity** - an idea from the subject, carried through | 10% | 20% |

Calibration: 7 is clean and professional with little tension; 8 confident, one or two soft spots; 9 is rare. If your first score for your own work is 9, score it again as someone who did not build it.

## Caps

These override the arithmetic.

- A floor finding open: overall at most **5**.
- A reflex finding neither fixed nor waived: overall at most **6.5**.
- The cover-the-name pass fails: creativity at most **6**.
- The five-second pass fails (no clear title or purpose on the first screen): usability at most **6**.
- No real control for the primary action on the first screen: usability at most **7**.
- A view contradicts its brief (another primary action, a missing state, a different phone layout): usability at most **6.5**.
- Placeholder content, or totals that do not add up: content at most **5**.
- A section or state the brief asked for missing: content at most **6**.
- Unequal outer margins, a section huddled on one side, or a blank stretch over half a screen: design at most **6.5**.
- Persuade: the idea passes for the nearest competitor, creativity at most **7**; one identical fade on every block, design at most **7**.

## Heuristics

Score Nielsen's ten, 0-4, where they apply; one that cannot apply to this surface is `n/a` with a one-line reason, and the total is out of 4 times the number scored (/40 when all apply, /32 when two are n/a). A 4 is genuinely excellent; most real screens land at 50-80%.

| # | Heuristic | 0 | 4 |
| --- | --- | --- | --- |
| 1 | Visibility of system status | actions vanish without response | every action confirms; progress always visible |
| 2 | Match with the real world | system words, internal names | the person's own words and order |
| 3 | User control and freedom | no undo, no way back | undo, cancel and back everywhere |
| 4 | Consistency and standards | the same thing named and drawn several ways | one name, one look, platform conventions |
| 5 | Error prevention | easy to destroy, easy to mistype | constraints, confirmations, good defaults |
| 6 | Recognition over recall | the person must remember across screens | options and context visible where needed |
| 7 | Flexibility and efficiency | one slow path only | shortcuts and bulk actions for the frequent user |
| 8 | Aesthetic and minimalist design | everything competes | only what serves the task, in clear order |
| 9 | Recovering from errors | codes, blame, dead ends | what happened, why, and the fix, in place |
| 10 | Help and documentation | none where it is needed | help in context, findable, short |

On Persuade and Experience surfaces, 7 and 10 are usually `n/a`. Bands by percentage: 90% excellent, 70% good, 50% acceptable, 30% poor, below that critical.

## Writing it down

```
ROUND n - <view>, <mode>
Seen in one second: ...
Idea (one sentence): ...
Scores: usability _ · design _ · content _ · creativity _ -> overall _._  (caps applied: ...)
Heuristics: _/_ (n/a: ...)
Top three problems, most visible first, each with its screenshot and its fix:
1. ...
```

Name problems the way a designer would to a colleague: "the supporting line starts 40px left of the headline's edge", not "could be more polished". Fix in this order, since later items cannot rescue earlier ones: the idea, hierarchy, composition, type, imagery, colour, detail, motion.

Adapted from taste-skill's critique (passes, weighted score, caps) and Impeccable's critique (Nielsen heuristics); see `CREDITS.md`.
