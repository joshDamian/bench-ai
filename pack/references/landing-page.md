# landing-page

A landing page wins one intent: **one offer, one audience, one primary action.** It is Persuade mode (`design` skill): full commitment, a point of view, and every floor rule still holding. Work in this order; the first four steps decide whether the page is any good, and none of them is markup.

## The run

1. **Inventory the subject.** Eight to twelve things that belong to this product and no competitor: its name and what it means, its place, its numbers (the price, the seats, the minutes), the constraint it lives with, its materials, its rituals, the printed things of its world (a ticket, a label, a receipt). And what material truly exists: screenshots, the running product, photographs, real numbers, quotes with names. Anything absent stays absent.
2. **Claims with proof.** The page as a list of claims, one per section, each with what proves it:
   ```
   1. It is a three-legged oak stool, made to order.        -> the stool, large
   2. Each seat is cut from one board.                       -> three close-ups of the grain
   3. It takes six weeks, and you can follow it.             -> the stages, photographed
   4. It costs 240 and the maker delivers it.                -> price, button
   ```
   A section with no claim is filler: delete it. The order of the argument is the order of the page - which is how it escapes hero, logos, three features, testimonial, pricing, call to action.
3. **The idea.** Fill both blanks from the inventory before building: *On this page, [something that belongs to the subject] becomes [the device the direction calls for].* Then the competitor test: put the nearest competitor in the sentence; if it still works, the idea belongs to the category. Give the page **one live thing** made from the idea - something the visitor can do or that differs each visit, usable with a thumb, with a plain fallback without scripts.
4. **The copy deck before markup.** The whole page as plain text (`copy.md`): headlines of two lines at most, one supporting sentence under the hero headline, one primary action named verb + object, every section's full content. Sample details where the brief left gaps (room names and rates, dish prices) are expected, listed for the person to confirm; proof is never invented.
5. **Build, still.** Tokens first (`design-tokens`), then the page from the direction, with motion off in your head: a page that needs animation to look finished is not finished.
6. **Gate.** `design-check` (floor 0, every reflex fixed or waived) and the ship list below.
7. **Look.** Screenshots at 1440, 768 and 390, judged with `design-review`'s rubric; fix the top three; at most three rounds.
8. **Motion last** (`motion.md`): one signature moment, entrances for each section's parts, feedback on every control. A word-by-word tagline reveal is one possible signature - an option, not a rule.

## Structure

- **Above the fold:** a headline (the outcome, or what it is, for whom); a supporting line that adds the how; the primary action as a real button; one proof signal if a real one exists; the hero visual - the product itself, captured, or the subject's strongest real picture.
- **The argument:** the problem and what changes; three to five benefits stated as outcomes, each with its proof beside it; how it works if it is not obvious; real proof.
- **Objections:** the questions buyers really ask, answered plainly; risk reversal (a trial, a free plan, cancel anytime, a guarantee) when one is true; the same primary action again.
- Pick the shape and say why: a classic hero and sections (understandable from a screenshot); a long-form story (it must educate); a minimal conversion page (high-intent traffic, a download or a waitlist); a comparison page (people search "X vs Y").

## Conversion rules

- One primary action; nothing competing with it above the fold. The accent colour goes on that action.
- Match the message to where the visitor came from: the ad's promise is the hero's.
- Benefits before features; specific numbers before adjectives.
- Proof sits beside the claim it supports, never in a band at the bottom.
- A mobile layout that is its own composition, not the desktop squeezed.
- Index evergreen pages with a real title and description; keep ad-only or time-bound pages `noindex`.

## Ship list

A page is not done until it has: a custom 404; `<title>`, meta description, `og:image` and sharing tags; a favicon; a skip link; privacy and terms links (and cookie consent where the law asks); alt text on every meaningful image; semantic landmarks (`nav`, `main`, `section`, `footer`); form validation for required fields and email format, and no form that says "Sent" with no request behind it; a way back from every page; no `#` links.

## Persuade guidance, not rules

Pages with real pictures beat pages of type and drawings; a richer page full of real content beats a thin page with good manners. Fullness comes from scale and pictures, never from more words. Equal outer margins; no stretch of the page blank for more than half a screen; no section huddled on one side with the other half empty.

Adapted from taste-skill's page run and elayadesign's landing-page-design (Part A); see `CREDITS.md`.
