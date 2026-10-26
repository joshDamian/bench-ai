# directions

Asked to invent a look, a model picks the category's default every time, and consecutive projects converge on the same one. A direction is chosen, not defaulted to: three clearly different ones are put on the table, each drawn from the product's own subject, and checked against the generic answer and against what this machine has made before.

## 1. Inventory the subject

Eight to twelve things that belong to this product and to no competitor: its name and what the name means; who uses it and where (a nurse on a night shift, a trader at six screens, a child on a tablet); its objects and their vocabulary; its numbers; the constraint it lives with; its materials and colours; the printed and physical things of its world (a lab label, a ticket, a ledger, a dashboard dial). The use scene decides light or dark, not the category.

## 2. Three directions

Each one is a short plan, distinct from the other two on at least three of: palette, type, density, shape language, motion personality, signature move.

```md
### A - Ledger
On this screen, **the bank statement's printed ledger** becomes **the table's rhythm: ruled rows, tabular figures, one ink colour per sign**.
- Palette: 4-6 named values (ground, surface, ink, muted, accent, danger), where each comes from in the subject.
- Type: one family (a second only with a job), the scale and its ratio.
- Density and shape: compact, 4px radius, borders for structure.
- Motion: Precise; signature ease; 120 / 200 / 300ms; fade + 4px rise.
- Signature: the one thing a person would remember.
```

The first blank always comes from the inventory. "Clean and modern" is not a direction; "minimal" is not a palette.

## 3. Review against the generic default

Before showing them, hold each plan up against what you would have produced for any similar product. Work through a neighbouring prompt in your head - the same product type for another company - and see whether you arrive at the same plan. Where a part reads like the default (the purple accent, the cream-and-terracotta, the icon-card grid, the overused face, `tells.md`), revise that part and say what you changed.

**The mirror test.** Would a neighbouring prompt in the same category get this exact combination? If yes, change one decision and say which.

## 4. Against the history

Bench keeps the directions chosen on this machine, across projects:

```sh
node "$(bench pack)/skills/design-tokens/directions.mjs" list            # the last 12, newest first
```

A new direction shares at most two of palette family, type family, motion personality and signature move with any of the last few. When one repeats, change it, or say why this product needs it.

## 5. Choose, record

Offer the three in one `AskUserQuestion` (a short preview each), recommended first. Unanswered: choose, and say why. Then record the choice in `Design.md` - the direction, its sentence, why it beat the other two - and in the history:

```sh
node "$(bench pack)/skills/design-tokens/directions.mjs" add --project "<name>" \
  --direction "Ledger: the printed statement becomes the table's rhythm" \
  --palette "ink on paper-white, one green, one red" --type "one grotesk, tabular" \
  --motion "Precise" --signature "rows that settle into their totals"
```

Adapted from taste-skill's inventory and idea sentence, Hallmark's mirror test and Anthropic's frontend-design plan review; see `CREDITS.md`.
