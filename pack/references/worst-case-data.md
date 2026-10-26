# worst-case-data

Demo data is chosen, usually without anyone noticing, to make the design look good: names that fit on one line, counts that never need a separator, every optional field filled. A screen is not finished until it has met the most awkward real user it will have. Undo the flattering choice one field at a time.

## Rules

1. **Plausible or schema-backed, never random.** Every worst-case value is something a real person could produce, or the longest value the backend accepts (the validation schema, the column, the API contract). `"aaaaaaaaaaaa"` proves nothing. No limit anywhere is a finding: "unbounded".
2. **Through the data boundary, never by hand-editing markup.** The worst case enters where the demo data does: the fixture, the mock, the props, the API stub, the seed script. Editing the HTML or CSS to produce a break tests your edit, not the screen.
3. **One dataset, many failures.** Spread the breaks across the first rows - row 1 the long name, row 2 the long email, row 3 the one-letter name - as real data does.
4. **Dev-only switch.** A `?data=worst` query read where the fixture is chosen, or a prototype route; never shipped. Keep the fixture afterwards: it is the regression test.
5. **Report before fixing.** Some breaks are design decisions (truncate or wrap?); list them with a fix each, then decide.

## Map the surface first

List every value the screen renders, with its source, type, limit and whether it is optional - including the ones people forget: counts in headers, relative times, badge text, button labels from data, tooltips, avatars, and the list's own length.

## The values

| Kind | Worst case | What it breaks |
| --- | --- | --- |
| Names | `Aleksandra Wiśniewska-Kowalczyk`, `Christopher Alexander Montgomery III`, `Jo`, `J`, `Đặng Thị Ngọc Hân`, `王秀英`, `نور الهدى عبد الرحمن`, `🦊 Fox`, missing | wrapping, initials, clipped diacritics, RTL, half an emoji, the fallback |
| Emails, URLs, ids | `bartholomew.fitzgerald@northwind-industries-holdings.example.com`, `a@b.co`, a 100-character URL, a UUID, `Q3 Board Deck - FINAL (revised) v12.pdf` | unbreakable strings pushing siblings off the row; end-truncation hiding the part that differs |
| Labels from data | `Senior Product Design Engineer, Platform Infrastructure`, `Benachrichtigungseinstellungen`, French at +30%, twelve tags, an empty title, `<script>`, a newline | wrapping badges, overflowing buttons, escaping, collapsed rows |
| Numbers | `0`, `1`, `1284`, `1000000`, `-42.5`, `0.1 + 0.2`, `142%`, `null`, a value that changes live | "1 members", missing separators, raw floats, `NaN`, bars past their bounds, jitter |
| Collections | 0, 1, exactly a page and a page + 1, 1,000+ unpaginated, one item 10x the others, identical names | empty states, "1 of 1", empty page 2, scroll performance, ragged rows |
| Time | now, 12 days, 11 months, 3 years ago, the future, `1970-01-01`, 23:30 in another zone | "0 seconds ago", thresholds, a zero timestamp, the wrong day |
| Media | a 404 avatar, none, a 4000x200 panorama, a slow image, a dark logo on dark | broken-image icons, distortion, layout shift, invisible marks |
| States | loading, an API error, partial data, every enum value at once, no permission, the current user in the list | skeletons that do not match, raw errors, misaligned rows |

Use `example.com` and `.test` domains so a fixture never points at a real inbox.

## Then look

At the component's real container width, at 320px, and at the widest layout; at 200% zoom; in dark mode and RTL if the product has them. Shoot both datasets with the `verify-ui` shooter and compare. The usual causes, and their fixes:

| You see | Fix |
| --- | --- |
| An avatar squashed into a pill | `flex-shrink: 0` on fixed-size boxes |
| Text overflowing instead of wrapping | `min-width: 0` on the text column (`minmax(0, 1fr)` in a grid) |
| An email running past the edge | `overflow-wrap: anywhere` on that element |
| The trailing menu pushed off the row | `min-width: 0` in the middle, `flex-shrink: 0` on the action |
| A badge on two lines | `white-space: nowrap; flex-shrink: 0`, and decide what yields |
| Wrong initials, `�` | initials from grapheme clusters (`Intl.Segmenter`), first and last word, a fallback |
| "1 members", `1284`, `NaN` | `Intl.PluralRules`, `Intl.NumberFormat`, a null guard |
| Truncated text nobody can read | the full value in a `title` or the detail view |

Per field, decide: **wrap** what identifies a thing; **truncate at the end** secondary meta; **truncate in the middle** what differs at the end (file names, paths, hashes); **clamp** multi-line previews; **never truncate** numbers, amounts or dates.

**Report** one row per break, worst first - Broken (unreadable, unreachable, wrong data), Ugly (readable but visibly wrong), Fragile (one realistic step from breaking) - with the field, the value, what happens, and the fix.

Adapted from Emil Kowalski's break-ui skill and its catalog; see `CREDITS.md`.
