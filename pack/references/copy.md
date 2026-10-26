# copy

Words are design content, not decoration: a screen whose words are generic looks generic whatever its layout. Write them before the markup, from the person's side - name things by what they understand, not by how the system is built. A person manages notifications, not webhook config.

## Controls

- **Verb + object.** "Save changes", "Book a table", "Invite 3 people", "Download for Mac". Never "Submit", "OK", "Continue" when something more exact is true, and never "Learn more", "Get started" or "Click here" (`generic-label`).
- **Sentence case** for buttons, labels, headings and menus: "Create project", not "Create Project".
- **One name per action, everywhere.** The button that says "Publish" leads to a toast that says "Published", and the menu item says "Publish" too.
- **One to three words** on a button. The primary action keeps its name across the page; a second action is a plain link.
- **No arrow glued to every link** (`arrow-link`); at most one, on the primary action, if the design uses arrows at all.

## Specific, not filler

- **Specific numbers.** "Ships in 5 days", "38 seats", "cuts the weekly report from 4 hours to 15 minutes" - over "fast", "intimate", "saves time". A number is only on the page if it is true (`invented-proof` is a floor rule).
- **Every relative figure names its baseline**: "+12% on last week", "3 h before close". Never a bare "+5" (`honest-data.md`).
- **Ask of every sentence whether a competitor could print it unchanged.** If so, sharpen it or cut it.

**Banned filler** - each is a reflex tell (`stock-phrase`) with a better sentence behind it:

> unlock, unleash, supercharge, seamless, elevate, empower, revolutionise, game-changing, cutting-edge, next-generation, world-class, best-in-class, industry-leading, all-in-one, effortless, blazing fast, lightning fast, reimagined, turbocharge, mission-critical, future-proof, "take your X to the next level", "say goodbye to", "in today's world", "built for the modern team", "not just X, it's Y", "more than a ..."

Also: editorial costume labels ("Field notes", "Our philosophy", "Quietly trusted by") where a plain name will do ("Pricing"); tricolons ("Fast. Simple. Secure."); rebuttal cadence ("Not a feature. A platform.") more than once; em dashes as a habit (`em-dash`); middle-dot meta strings (`middle-dot-meta`); captions that narrate the design; "Step 1", "Phase 02".

## System messages

Calm, specific, and in the interface's voice, not a person's. They never apologise and are never vague.

- **Errors** say what happened and what to do: "Couldn't save - the file is over 25 MB. Choose a smaller one." Never the exception ("TypeError: Failed to fetch", "Error 500"), and never only red.
- **Empty states** are an invitation: what will be here and the one action that fills it ("No projects yet - Create project").
- **Success** is silent when the effect is visible; a toast only for what the person cannot see happen.
- **Loading** says what is loading, and ends with …: "Loading invoices…".
- **Destructive confirmations** name the thing and the consequence: "Delete 'Q3 report'? This removes it for everyone." The button says "Delete report", not "Yes".

## Headlines

- **Two lines at most, at every width** (`long-headline`). A headline is a title: about 32 characters survives everywhere at a confident size; past about 42 you are writing a sentence, and the rest goes in the line below. Cut words first, widen the measure second, shrink last.
- Section headings are two to five words.
- Give a headline a `max-width` in `ch` and `text-wrap: balance`; author the break where it matters, and check it on the phone.
- **Shapes that work**: the name; what it is ("Furniture for small rooms"); a claim a competitor could not sign; an instruction, verb first; the place or moment. **Avoid**: the mad-lib ("The AI-powered platform for modern teams"), the tricolon, "X, reimagined", "Welcome to ...", a question nobody asked, one word styled apart (`accent-word`).
- **A statement is a paragraph.** A sentence or a quotation set large is a `<p>` or `<blockquote>`, about 25 words, at statement size (36px at most on desktop, 24px on a phone). Set larger, or marked up as a heading, it is a headline, and two lines.

Adapted from taste-skill's copy reference, elayadesign's landing-page copy rules, Vercel's guidelines and Impeccable's clarify reference; see `CREDITS.md`.
