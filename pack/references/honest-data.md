# honest-data

An app screen is judged on whether its numbers can be trusted, and a screen that contradicts itself once is not trusted again. Before building one, inventory its data and write the data down; the screen renders from it.

## Inventory, in 8 to 12 lines

- **The objects** the screen shows (deployments, invoices, members) and their fields, with units.
- **Realistic magnitudes**: how many, a typical amount, the range of a rate, how values move over a day and a year - and how small they are for a new account.
- **The people**: who opens it, how often (a glance a day or an eight-hour session), on what device.
- **The jobs**: what they do next from here, and which comes up most - that is the primary action in `Design-brief.md`.
- **The product's own things**: its name, the workspace it is scoped to, the people in it, the objects people recognise by sight. These are what make the screen this product's rather than a template's.

Then write the dataset itself, a JSON or JS file the screen renders from: believable names, dates in order, a unit on every figure (`worst-case-data.md` for the awkward ones).

## Data that tells the truth

- **Totals are computed from the rows**, never typed in separately. Sum the rounded values the screen shows, not the unrounded total: £21.68 + £8.34 - £3.81 is £26.21, and a screen that says £26.22 is caught.
- **A fact shown twice is one value.** A window, a total or a state that appears in two cards comes from one variable, and both the copy and the chart annotation are built from it.
- **A change is the difference of the two values on screen**: £5.28 against £5.22 is +£0.06.
- **Every relative figure names its baseline** in the same phrase: "+12% on last week", "45 m earlier than last Sunday". A bare "up" or "+5" next to another figure reads as measured against that one.
- **One name per moment.** The header and the card call the same time by the same words.
- **Small accounts are drawn at real size.** A new workspace with 1, 6 or 9 of something shows 1, 6 or 9 - not a chart stretched to look busy, not padding with sample rows.
- **Write the agreements as assertions** in the data file (`console.assert(sold - cancelled === rsvps)`) and read the console.
- **If a caption has to say which bars are which, encode it in the chart**: a second tone with its own legend entry, a label on the mark.
- **No vanity numbers.** A KPI is on the screen because someone acts on it; a figure nobody supplied is invented proof (`rules.md`).

Adapted from taste-skill's app-screens reference (§2.1); see `CREDITS.md`.
