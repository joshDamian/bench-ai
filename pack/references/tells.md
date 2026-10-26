# tells

A tell is a device that makes a screen read as generated. Most began as a good idea on a good site; they became tells by turning up by default, everywhere, with no reason. None is banned outright: each is a **reflex** rule (`rules.md`), fixed, or waived in `Design.md` with the reason it is right for this product. What replaces a tell is a decision, never another stock device: the replacements below are procedures, not products, because every "use this instead" list becomes the next wave of tells.

Where the gate or the hook can see a tell, its id is given; the rest are judged in `design-review`.

## Structure

| Tell | Id | Instead |
| --- | --- | --- |
| A row of same-size cards, each an icon in a rounded tile over a heading and a line | `icon-tile-grid` | A composition from the content: a list set large, a split, the product doing each thing |
| A card inside a card | `nested-cards` | One containment layer; group inside it with space and type |
| The hero-metric template: big numbers over small labels in a row, an accent on one | `hero-metric` | The one number that matters, what it means, against what baseline; the rest in a table |
| The stock page: hero, three features, logos, testimonial, pricing, call to action | judged | The order of the argument (`landing-page.md`) |
| Every section the same layout, separated the same way | judged | A different composition per section; seams climb different steps (space, tone, a rule, a surface) |
| Everything centred | judged | One shared left edge; centre one moment at most |
| One medium radius on everything, regardless of nesting | judged | A radius per level, concentric (`polish.md`) |
| A fake dashboard of grey bars and donuts standing in for a product | judged | The real product, captured, or none |
| A hand-drawn browser or OS window around a screenshot | judged | The screenshot in a `<figure>`, or one plain frame |
| A large illustration assembled from primitive shapes | judged | A real picture, the product, or type at scale |
| A floating pill navigation with blur | judged | A plain bar: wordmark, a few links, one action |
| Ending on an email sign-up when the job is not a mailing list | judged | End on what the visitor came to do |

## Surface and colour

| Tell | Id | Instead |
| --- | --- | --- |
| Gradient-filled text | `gradient-text` | Solid ink; emphasis from size or weight |
| The purple/violet/indigo palette, purple-to-blue gradients, cyan on near-black | `ai-palette` | A colour taken from the product's own subject |
| A warm cream ground with a terracotta or clay accent (the assistant's own colours) | `cream-terracotta` | Warmth from a stone, an ink or a photograph; the accent from the subject |
| A near-black ground with one acid-green or vermilion accent | judged | A dark palette mixed for this product, no glow |
| Glass: backdrop blur as decoration on cards and panels | `glass` | Opaque surfaces one tone step from the ground; one blurred bar at most |
| A thick coloured border down one side of a card or callout | `side-tab` | A tone step, an icon, or the words |
| A row of cards each in a hairline border and a wide soft shadow | `border-shadow-card` | One of the two, as `Design.md` decides; a shadow only on what floats |
| Coloured glows: zero-offset chromatic shadows, radial halos and spotlights behind content | `dark-glow` | Depth from lightness steps; shadows with offset and blur |
| Hard offset block shadows outside a world that chose them | judged | The project's shadow tokens |
| Decorative grid-line, stripe or dot backgrounds with nothing under them | judged | A plain surface; grids belong to canvases and maps |
| Broadsheet cosplay: hairline rules everywhere, zero radius, dense columns | judged | Structure from space and tone |

Borders and shadows as such are the project's decision, made once in `Design.md`; only the generated combinations above are tells.

## Type

| Tell | Id | Instead |
| --- | --- | --- |
| Monospace as a costume for "technical": labels, captions, headings | `mono-costume` | The text face, a step smaller; monospace for code, data and measurements; tabular figures for numbers |
| 01 / 02 / 03 section markers, (001), "Step 02" | `section-numbers` | Order by position and the words; an `<ol>` when it is a real sequence |
| Tiny uppercase letter-spaced labels | `tracked-caps` | Sentence case in the body face, a step smaller, untracked |
| One word of a heading set apart: italic, colour, weight, a highlighter | `accent-word` | The heading in one weight, one style, one colour; a line break if it needs a pause |
| The face every generator reaches for (Inter, Roboto, Poppins, Space Grotesk, Geist, Fraunces, Playfair Display, ...) | `overused-font` | A face chosen for this product, with the reason in `Design.md`. Overused is not forbidden: chosen is the point |
| More than two typefaces | `font-count` | One typeface by default; a second only with a job |
| A size that is not a step of the scale | `off-scale-type` | The nearest `font.size.*` step |
| A headline that wraps past two lines | `long-headline` | Cut words, then widen the measure; size last |
| An oversized italic serif headline as the hero | judged | Roman, set at the scale's display step |
| A flat hierarchy: every size within 1.25x of the next | judged | At least one decisive step (3x body to headline) |

## Copy

| Tell | Id | Instead |
| --- | --- | --- |
| Stock marketing phrases: seamless, supercharge, unlock, elevate, empower, cutting-edge, world-class, "not just X, it's Y" | `stock-phrase` | What it does, in its own nouns, with a real number (`copy.md`) |
| "Learn more", "Get started", "Click here", "Submit" | `generic-label` | Verb + object: "Book a table", "Save changes" |
| Meta strings joined by middle dots: A · B · C | `middle-dot-meta` | Line breaks, columns, or one plain clause |
| An arrow glued to link text: "See the plans →" | `arrow-link` | A label that says where it goes; one arrow at most, on the primary action |
| Em dashes as a habit | `em-dash` | A full stop or a comma |
| Emoji as icons or bullets | `emoji-icons` | One icon set, or the words |
| Rebuttal cadence: "Not a feature. A platform." three times over | judged | Say it once, plainly |
| "Theater", "field notes", "quietly trusted by": performative labels | judged | Plain section names: "Pricing" |
| Decoration text: version pills in the hero, locale and weather strips, scroll cues | judged | A fact stays, as a caption or a footer line; a decoration goes |

## Motion

| Tell | Id | Instead |
| --- | --- | --- |
| `transition: all` | `transition-all` | The properties that change |
| Bounce, elastic or overshoot easing on UI | `bounce-easing` | The project's `motion.ease`, a strong ease-out |
| A small dot pulsing forever to look live | `pulsing-dot` | A still dot with a label; pulse only data that is live |
| A marquee: content scrolling by itself forever | `marquee` | A still row the reader moves at their own pace |
| A decorative blinking cursor where nothing can be typed | judged | Real fields draw their own caret |
| The same fade-up on every section; a hover lift on every card | judged | One authored moment (`motion.md`); feedback only where a person acts |

## Why removal is not enough

A screen with every tell removed and nothing in their place is an empty screen with good manners, and that reads as generated too: anyone could guess it from the category and this list. The tells were filling the place where a decision should have been. Fill it first - the direction from the subject (`design-tokens`), the brief (`design-brief`), the real content - and run this list after.

Merged from Impeccable's detector registry, taste-skill's tells and lint rules, Hallmark's gates and Anthropic's frontend-design calibration list; see `CREDITS.md`.
