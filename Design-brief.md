# Design brief

**For:** the one person who runs Bench on their own machine or VPS and drives it from a laptop or a phone. They arrive in Settings to change one thing and leave: the model new sessions start on, a provider to switch on or test, a phone to pair, notifications for this device, a restart after an update. **Core action:** run Claude sessions in tasks and land their work - Settings exists to shape how those sessions start. **Vibe:** quiet, dense, trustworthy - a tool, not a showroom (from `Design.md` and the themes' own descriptions).
**Shell:** page-shell as Bench already has it: a 260px left sidebar on `--surface-rail` (projects, then Dashboard / Add project / Settings at the bottom); a bottom tab bar under 860px. Not changed by this brief.

## View: the shell (every route)

**Job:** hold every view - the way between projects, tasks and sessions, and the frame each page's title and its one action sit in.
**Pattern:** `page-shell`. The shape is Bench's own and stays as it is; this brief only fixes the parts of it the other sections depend on.
**Primary action:** the shell has none of its own. It carries whichever view's one action is in the page-header's right corner, and the only action it owns is **Add project** in the sidebar's footer, which is a destination rather than a page's primary. That is why it is a quiet row and not a filled button: a filled button in the furniture would compete with the real one on every screen at once.
**Hierarchy:**
1. The page title (20px `--text-xl`, 650, the theme's `--font-title`) in `.page-header`, with at most one muted line under it - a path, a hostname, a "from *branch*".
2. The view's content, left-aligned to the title.
3. The sidebar: the product mark and `Bench` (16px, 700) with the theme pill in the same row, the "N waiting on you" link when there is anything, then one row per project with its active tasks and checkout sessions nested under it.
4. The sidebar's footer - Dashboard, Add project, Settings - in `--muted`, marked with `--accent` and a `--surface-2` fill when current.

**The shell owns, and the views may rely on:** the 260px sidebar on `--surface-rail`; the bottom tab bar under 860px; the mobile topbar with its menu and back controls; the toast stack; the confirm dialog and the generic `Dialog`; the theme picker; and the `.page` column - `max-width:1100px`, padding `24px 32px 32px` on desktop and 16px on the phone.
**States:**
- **Loading:** the shell renders at once, with its real navigation, and only the content area is briefly empty while the store hydrates. A `Connecting…` gate covers the whole app only before the first paint of the shell, as `page-shell` asks.
- **Offline:** the connection is the shell's business, so the shell says so - a `--warn` `offline` badge beside the mark in the sidebar, between it and the theme pill. A view does not repeat it. The badge is for a line that has dropped, not for one still being dialled: a socket is closed until it opens, and on every load the shell paints before the first handshake answers, so the badge waits for a socket that was open and closed (or that closed after an attempt). Reading those few hundred milliseconds as `offline` is telling the person the server is gone when the server is the thing still being dialled - and it was, on any view whose own data took long enough to paint the shell first. The badge is the third thing in a header row that is 219px for 89 + 66 + 145, so in this state, and only in it, the theme pill gives up its name and becomes its colour dots - which still open the picker. A pill squeezed to "Q…" reads as a fault; a swatch reads as an icon, and the theme is not what a person needs to read while the socket is down.
- **Store error:** one `.notice-bad` above the view, inside the content area, so the navigation stays usable.
- **Happy path:** three or four projects, one with active tasks and a checkout session, a session running, one item waiting.

**Layout - desktop (1280):** `.shell` is a two-column grid, `260px minmax(0,1fr)`, full height. Sidebar `--surface-rail` with a `--line` right border, padding 16px/12px; it scrolls on its own. The content column is `--bg`; a project's folded head shows a status dot summarising its sessions, and every nested row is a link that ellipsises rather than wraps.
**Layout - phone (390):** under 860px the sidebar becomes a `min(300px,85vw)` drawer over a `--scrim`, opened from the topbar's menu button; the topbar is sticky, `--surface-rail`, with the current view's name and a back control on any screen that is not top-level. A bottom tab bar - Inbox · Sessions · Dashboard · Projects · Settings, icon over an 11px label, with a `9+`-capped badge - is pinned below, padded for the safe area, and shown **only on the five top-level screens**: a task or a conversation keeps the whole height and leaves by the topbar's back control. The scroller is padded so nothing ends under the bar.
**Avoid:** a second navigation on the phone that does the same job as the drawer; a filled button in the furniture; the tab bar on a conversation, where it would take a row from the transcript to say what the back button already says; a shell that is the same colour as the content with a border between them.
**Chosen, not asked:**
- *The tab bar is for destinations, not for tasks.* Five top-level screens is what fits a thumb; anything deeper is a place you were sent to, and a back control is the honest way out of it. This also keeps the composer off the bottom edge, where the keyboard arrives.
- *The sidebar footer holds Dashboard, Add project and Settings rather than the top.* `page-shell` puts account and settings at the bottom of the sidebar; Bench's same three every time are what a person reaches for after the projects list, not before it.
- *Emphasis in the sidebar is a fill plus an accent rule, never bold alone.* A folded project still reports on its sessions through a status dot, so folding is not the same as forgetting.
- *A button's label is one line.* A button is a thing you aim at, and one whose label wraps changes height as the text around it changes width, so the same control is 29px tall where the heading is short and 48px where it is long - which is how **New session** came to sit two lines tall on a phone with its plus stranded beside the lone word "New". Which element gives when two will not fit is settled by the box the button is in, never by the label.
- *The sidebar's theme pill names the theme; it does not preview it.* The header row is 219px of room for an 89px mark and this pill, and the longest theme Bench ships asks the pill for 145px. The name is what the pill is for, so it stays whole and the three colour dots stand down there - the sidebar they would sit on is already painted in the theme. Cut instead, `Quiet Temple`, the light default, read "Quiet T…" on every screen. Settings' own pill has the room and keeps its dots.
- *The picker's open panel follows its pill through a resize; it is not dismissed by one.* A window changing shape is not the person closing the picker. The panel is anchored to a pill that is still there, and it re-reads that pill's rectangle on render, so it is put back under it - on a rotation, on a dragged window edge, and when the browser grows the page to photograph it, which is what was losing it. A destination change is different: that moves the page out from under the panel and closes it.

## View: Home - projects (`#/`)

**Job:** the projects on this machine, and what is waiting on you right now - the screen a phone opens to.
**Pattern:** `cards-grid` for the projects, with an `list-detail`-style attention list above them.
**Primary action:** **Add project** - the one filled button, in the page header's right corner, and repeated as the empty state's own button. Adding a project is the only thing this screen is for; everything else on it is a way in.
**Hierarchy:**
1. `Projects` (20px) with **Add project** on its right.
2. **Waiting on you** (16px `--text-lg`) with a `--warn` count badge, when anything is waiting - the only tinted content on the page.
3. The project cards: name (700) with a status dot, then the shortened path and the counts in `--muted` (path in `--font-mono`, 12.5px).
4. Nothing else. A card is a name, a place and a count.

**States:**
- **Loading:** the shell's own; the store paints from its cached snapshot, so the grid is normally there on a cold open. No skeleton, and deliberately so - see *Chosen, not asked*.
- **Empty:** centred in the content area - `No projects yet` (16px), "Add a git repository on this machine. Tasks you start in it each get their own branch and worktree.", and **Add project** below it. This is the first-run empty, and it is the one place the create action is allowed to repeat.
- **Error:** a failed git or GitHub read is a toast with the message, because the grid is already drawn and there is nothing in it to replace. A failure to add a project keeps the dialog open with what was typed and says what failed.
- **Happy path:** three or four projects, one with an active task and a checkout session, one item waiting.

**Layout - desktop (1280):** `.page` (1100px max) → header → the attention list as a `--surface-1` `.list` with hairline dividers (not cards) → a `.project-grid` of `minmax(220px,1fr)` columns, 12px apart. Cards sit on `--surface-1` with the small radius; the whole card is the link.
**Layout - phone (390):** padding 16px, the title 16px. The grid falls to one column by itself; the attention list rows stack their title over their where-and-when at 650px. Card meta ellipsises rather than wrapping to a second line.
**Avoid:** the create action as a "+" tile inside the grid; a card per project carrying its tasks, its sessions and its last commit; a "waiting" banner that repeats what the Inbox badge already says; counts styled as prominently as the names.
**Chosen, not asked:**
- *A project card is not a dashboard.* It shows a name, a directory and two counts and stops. The work in a project belongs to the project's own view, and a card that summarised it would have to be read rather than scanned.
- *Waiting stays on Home even though Inbox exists.* A phone opened to Bench should show what needs a person before it shows where things live; the list is capped and each row links into the same conversation the Inbox links to.
- *No skeleton for the grid.* The store paints from a cached snapshot before the socket answers, so a skeleton would replace real, slightly-stale content with a placeholder - worse than the staleness it hides.
- *The grid's floor is 220px, not 240.* A card is a name, a place and a count, and 220px holds all three. The narrower floor is what lets the four projects a full screen usually has sit across the content rather than leaving the fourth alone under three: at 1280 the content is 956px wide inside the rail, and four 240px columns need 996. A fifth card wraps, which is what a grid does; three across is what four cards looked like before.

## View: Inbox (`#/inbox`)

**Job:** everything waiting on a person, answerable where it is listed rather than one tap deeper inside its conversation - and what finished recently, as a glance below it.
**Pattern:** `list-detail` collapsed to one column: the list is the screen and each row carries its own action, the way `table` puts a row's action in the row when the detail is a decision the size of a button.
**Primary action:** **Allow** on the first waiting card - the permission prompt's filled button. Inbox has no page-level action because the page's job is answering, and only the thing being answered knows what "yes" is: a permission asks **Allow** / Always allow / Deny, a plan asks **Approve, accept edits** / Approve, ask before edits / Keep planning, a question asks **Answer** / Skip. Exactly one of each card's buttons is filled.
**Hierarchy:**
1. `Inbox` (20px) with a `--warn` "N waiting" badge.
2. Each waiting card, on a `--warn`/`--info` tint, with the shared approval card the transcript draws: what Claude wants to do, its preview, and its one filled decision.
3. The "where" line above each card - the session's title and the project and task it belongs to - as the link into the conversation.
4. **Recently finished** (16px) below, one line per session in a muted `.list`, capped and newest first.

**States:**
- **Loading:** none of its own; the list comes from the store with the rest of the app.
- **Empty:** centred - `Nothing waiting` (16px) and "When Claude needs a decision, or finishes a turn, it lands here." No button: there is nothing to press and nothing to create. This is the all-done empty of the `states` pattern, not a first-run one.
- **Error:** answering a card that fails is a toast with the reason; the card stays, still answerable, rather than being optimistically removed and silently lost.
- **Happy path:** one permission waiting with a bash command in its preview, one plan waiting, and four or five recently finished sessions.

**Layout - desktop (1280):** a single column, 1100px max inside `.page`, 12px between cards. Each card is an `.inbox-item`: the where-line above, the approval block below, both at full width. **Recently finished** is a `.list` on `--surface-1` with hairline dividers and single-line rows.
**Layout - phone (390):** the same column, 16px padding. A card's buttons wrap under its preview rather than sitting beside it; the preview keeps its own scroll so a long command cannot push the decision off the screen.
**Avoid:** a second, simplified approval card that can drift from the transcript's; a per-row menu of actions when the row's whole point is one decision; an illustration on the all-done empty; the recently-finished list growing into a second sessions screen.
**Chosen, not asked:**
- *The approval card is the transcript's own component, not a copy.* Allow and Deny must be the same buttons in the same place here and in the conversation, or a person learns the wrong muscle in one of the two.
- *Recently finished is capped and collapsed to one line.* It is a glance at what just happened, not a second Sessions screen - the Sessions tab is one over and does that job properly.
- *An approval whose session is gone renders nothing.* A card that cannot link to what it is about is a dead end; the item disappears rather than offering a decision with nowhere to apply it.

## View: Sessions (`#/sessions`)

**Job:** every session on this machine in one flat, newest-activity-first list, filtered to what needs a person - the list a phone actually drives from.
**Pattern:** `table` collapsed to a list for the phone, with its filter as a segmented control above.
**Primary action:** **Open** - the first segment of the filter. There is no filled button on this screen on purpose: its job is choosing which session to read, and the reading happens one tap away. The segments (Open · Needs you · All) are the only controls, and each shows its count only when it is not zero.
**Hierarchy:**
1. `Sessions` (20px).
2. The filter segments (12.5px, the current one in `--text` on a `--surface-3` fill edged with a `--line-strong` hairline).
3. Each row: a status dot, the session's title (550), an optional one-line error in `--bad` at 11px, then the status badge and the relative time in `--muted` with tabular figures.
4. The status badge is the row's emphasis - Working, Needs you, Error, Idle - and it is a word as well as a colour.

**States:**
- **Loading:** none of its own.
- **Empty:** `No sessions here` (16px) and "Start one from a project, or from one of a task's tabs." This covers both the first-run case and a filter that matches nothing, because the way out is the same in both - and the filter is directly above it, saying which one you are looking at.
- **Error:** a session that failed says so in its own row, one line, ellipsised, in `--bad` - the list is not replaced by an error page because the other sessions are fine.
- **Happy path:** one Working, one Needs you, one Error with its message, and four Idle - with the counts on the segments matching.

**Layout - desktop (1280):** `.page` → header → a segmented control on `--surface-0`, as wide as its three segments and no wider → a `.list` on `--surface-1` with hairline dividers. One row per session, 16px between the title block and the badge-and-time block.
**Layout - phone (390):** under 650px the segments stretch to the full width and ellipsise their labels rather than scrolling. Under 650px a row becomes two lines - the title with its error on the first, the badge and the time on the second - so a long title is never clipped by its own row.
**Avoid:** a card per session; a filled "New session" button competing with the list; showing the project path on every row when the project is one tap away; a colour-only status.
**Chosen, not asked:**
- *Sort is waiting, then working, then the rest, each newest first.* The order a phone should be read in is "what needs me", and a pure timestamp sort buries a decision under an idle session that happened to update later.
- *One empty state for both the filter and the first run.* With the filter always visible above it, the person can see which they are in; a second, differently-worded empty for the filtered case would be a distinction the screen already makes.
- *No filled action.* The pattern wants one primary action per view; this view's is choosing, and manufacturing a button here would compete with the rows, which are the actions.
- *The filter is a control, not a toolbar.* On a desk it is the width of its three segments and sits under the title like the control it is; stretched across 956px it reads as furniture, and three 315px segments for two-word labels is a lot of empty pill for the words it holds. Only a phone spans the width, where sharing it is what makes each segment a full thumb's target.
- *The current segment carries a hairline, not just its fill.* `--surface-3` is the most raised surface and the strip is `--surface-0`, and in the light themes those two are near enough that the selected segment's fill is a couple of steps off its ground - visible as a shape in the dark themes, invisible where it matters most. The fill stays; the `--line-strong` edge is what says which one is current, which is the same hairline a dialog or a menu uses to say "this is raised".

## View: Dashboard (`#/dashboard`)

**Job:** what Bench is costing the machine - the memory and disk its own processes hold - and which conversation is holding them.
**Pattern:** `dashboard`, with the honesty of a `table` about units.
**Primary action:** **none, and that is the decision.** This is an observe-only screen: nothing here kills, cleans or stops anything. Every row that belongs to a session is a link to that session, which is where stopping it lives. A dashboard action that acted on what it was showing would make the reading and the acting the same gesture.
**Hierarchy:**
1. `Dashboard` (20px) with one muted line of host - hostname, uptime, cores - and the time of the reading on the right, marked as refreshing every 4s.
2. Four tiles: **Bench memory**, **Bench processes**, **Disk free**, **Load average**. Each is a label (12.5px, `--muted`, 600), a figure (28px `--text-2xl`, 650), and the context it is read against - "of 8.0 GB", "3 sessions · 1 terminal". A figure is never alone, and the four bottom out together: every tile carries a third line - the memory and disk tiles a meter, the processes tile the CPU its processes are using, the load tile the other two averages - so no tile is a line shorter than the three beside it. Memory, CPU, disk and load are the four axes, and each tile answers for its own.
3. **Memory & CPU by process** (16px) with a neutral count badge, then one row per process: name, kind chip, and right-aligned mono figures for memory, CPU and elapsed time.
4. **Storage** - a bar for the machine's disk and one row per storage area, each with its path in mono under its name.

**States:**
- **Loading:** `Reading the machine…` in a card, as words rather than a skeleton - the first reading takes one poll and a skeleton that resolves in under a second flashes more than it explains.
- **Empty:** there is no empty dashboard. With no data it is the loading line; a machine always has memory and disk, so a first-run empty would be a fiction.
- **Error:** the message in a `.notice-bad` above the body. A reading that fails after good readings keeps the last one on screen under the notice, because a stale figure with its timestamp is more use than a blank page.
- **Happy path:** four tiles with real figures, one with its meter into `--warn`, five process rows of which two link to sessions, and three storage rows.

**Layout - desktop (1280):** `.page` → header → four tiles across one row, 12px apart, on a grid that counts its own columns (`1fr` to 650px, `repeat(2,1fr)` from 650px, `repeat(4,1fr)` from 1100px - the width the page itself is built to) → the two sections, 20px apart, each a `.list` on `--surface-1`. The memory and disk tiles carry an 8px meter whose fill and track are the same tone, so the severity reads across the whole bar; the tone is `--good`, `--warn` or `--bad` at 75% and 90%, and it is never the only signal - the number is always beside it.
**Layout - phone (390):** the tiles fall to one column; the process rows drop the "open" link, because the whole row is the link at that width, and their figures drop to 11px so all three still fit on one line. Under about 560px the title and the reading's stamp stop sharing a row and the stamp drops under the title, held right - the stamp carries the time *and* the cadence, and the time is never the part that splits. The host line keeps a width of its own there instead of being folded into what the stamp leaves.
**Avoid:** a wall of equal stat cards; a chart of four points; colour alone saying a resource is in trouble; a figure with no period or comparison; a dashboard that acts on what it shows; polling when nobody is looking.
**Chosen, not asked:**
- *The four tiles are a fixed set, so the grid counts its own columns.* Home's project grid fills whatever it is given, because the number of projects is the person's and its rows are meant to wrap. These four are always four, and an auto-filled 240px grid of a fixed four puts **Load average** alone on a second row wherever the columns fall below four - which at 1280 they do, three 310px columns and no fourth. A counted grid cannot orphan anything and says the same thing at every width: a row of four, a block of two, or one column on a phone.
- *Polled, not pushed.* The server samples nothing while this page is closed, so opening it is what turns the measurement on. On a small machine, looking at the dashboard should not itself be the load - and a phone that wakes refreshes at once rather than waiting out the interval.
- *No primary action.* The pattern asks what the person does next and warns against inventing an "Export". Here the answer is genuinely "go and look at the conversation", and that is the row's link, not a button.
- *The two footnotes under the process list are kept.* Whether the memory figure comes from the service's cgroup or from summing the process tree changes what the number means, and a number whose meaning is unstated is worse than no number.

## View: Project (`#/p/:pid`)

**Job:** a project - its checkout's git state, a new task, and the work already in flight.
**Pattern:** `page-shell` plus `list-detail`'s list, with `form` for the new task and `dialog` for the git and project actions.
**Primary action:** **Start task** - the one filled button on the page, at the foot of the new-task card, reached with `⌘↵`. The git bar's **Commit** is a `secondary` button, not a filled one: committing is what happens to work that already exists, and the page's job is starting new work. The header's `⋯` holds the occasional and the destructive - terminal, rename, default check, remove - so a click aimed at the main action cannot land on Remove.
**Hierarchy:**
1. The project's name (20px) with its shortened path in mono under it, and the `⋯` on the right.
2. The git bar: the branch button (mono, 12.5px, `▾`), then the working state - "N changed" as a link or a muted "clean", the ahead/behind drift, a `--bad` conflicted badge - then Fetch, Pull, Push and Commit.
3. **Tasks** (16px) with an active count badge, then one row per active task: a status dot and the title, "from *base*" in mono on a wide screen, and on the right a status badge with the time it last moved - but only when the task is not idle. A badge reading Idle on every quiet row is a badge that says nothing; the dot already carries the state.
4. **Sessions in the checkout** (16px) with its one-line hint, then its rows. **Merged and closed** stays folded below with its count, so the page shows the work in flight.

**States:**
- **Loading:** the git bar alone says `Reading the repository…` in `--muted`; the project's name, its tasks and its sessions are already there from the store, so only the part that is late says it is late.
- **Empty:** two, and they are drawn differently because they are different things. **Tasks** with nothing active says "No active tasks. Start one above; each gets its own branch and worktree." - which points at the action directly above it. **Sessions in the checkout** keeps its heading, its hint and its **New session** button and simply has no rows under them: that button is the section's own, and it is the only way into a session that works in the checkout, so the section is a door rather than a subsection and is drawn whether or not there is anything behind it. A project with neither draws both sections and no rows, which is the honest picture of a project with nothing in flight.
- **Error:** a git read that fails replaces the bar with a `.notice-bad` carrying the message - the bar's controls are unusable without the state they read, so showing them beside stale data would be a lie. A failed commit, push or checkout is a toast, and the bar keeps its last true reading.
- **Happy path:** a checkout two files dirty and three ahead with a remote, three active tasks, one checkout session, and two merged tasks folded away.

**Layout - desktop (1280):** `.page` → header → the git bar as a `--surface-1` card, wrapping, with the branch and status left and the actions right → the new-task card → the sections, 20px apart, each a `.list` of rows. The new-task card's options are a `minmax(220px,1fr)` grid, and Title, Done means and Check each span the full width because they are sentences, not fields.
**Layout - phone (390):** padding 16px. The header keeps the name on one line and drops the `⋯`'s neighbouring controls rather than wrapping them; "from *base*" is hidden, because the branch a task was cut from is the thing a phone reader is least often checking; the task rows stack title over status at 650px; the new-task options become one column.
**Avoid:** the new-task box as a modal - it is the page's main action and belongs on the page; a filled Commit beside a filled Start task; the project's whole tree of tasks and sessions repeated from the sidebar; a git bar that shows Push when there is no remote.
**Chosen, not asked:**
- *One filled button: Start task.* The git bar's Commit was filled, which put two primaries on the page and made committing - a thing the person does to work that already exists - look like the page's purpose.
- *The new-task card opens as one box, and Start task arrives with the options.* The placeholder is the page's entry point and says what a task is for; focusing it unfolds the options and the button together. The button is not drawn before them because its meaning is in them - the base branch it cuts from and the model it runs on - and because it is disabled until a base branch has been read, so a version of it on arrival would be a dead button standing where the action goes. The card is on the page, not behind a dialog, which is the part that matters: the page's main action is never a layer away.
- *A merged task folds rather than disappears.* It is still a place a person goes back to read, so it keeps its row and its count and gives up only the space.
- *The checkout sessions hint stays.* A session in the checkout has no worktree and no merge step, which is the one thing about it a person has to know before starting work there - so the section says it rather than relying on the row to imply it.
- *Browsing folders never picks one.* Moving through the folder browser only looks; choosing a destination is a deliberate act, so an accidental click mid-navigation cannot set where a repository lands.

## View: Task (`#/p/:pid/t/:tid`)

**Job:** one task's whole workspace - its sessions, what it changed against its base, a shell in its worktree, and the way back into the base branch.
**Pattern:** `list-detail` in its tab form: the task's tabs are the detail's sections, and each tab is a pane that scrolls on its own.
**Primary action:** **Merge into *base*** - the one filled button in the task's head, and the "way back into the base branch" this view exists for. The three other filled buttons each belong to a state in which the head's is out of play: **Start a session**, in the pane that is empty because the task has nothing to merge; **Ask Claude to resolve them**, in the conflict notice, which only appears when the merge cannot proceed; and the ship sheet's **Commit**, which exists on the phone, where the head's Merge is folded behind **Ship…** itself. One live filled button, always.
**Hierarchy:**
1. The task's title as a rename button (20px, 700, `--accent` on hover) with its Active/Merged/Closed badge, and under it the meta line: branch → base in mono, with counts as `--good` and `--bad`.
2. The tab strip: one tab per session, then **Changes**, **Log**, and **Terminal** on an active task. The current tab is `--text` with a 2px `--accent` underline.
3. The task bar, when it has a check: the check state, the command in mono, and **Done means** with the prose in full ink.
4. The pane. In the transcript, prose is `--text-md` and the tool runs collapse to one muted line each.

**States:**
- **Loading:** the pane says it - `Loading…` for a diff or a log, and the pane is otherwise empty. The task's head, its tabs and its bar are already there.
- **Empty:** per pane, and each says something different. No sessions: "No sessions in this task." with **Start a session**. The changes against base: "No changes against *base* yet." The log: "No decisions logged yet. Sessions add them with the `log` tool, each with the evidence behind it. A loop writes here too: every attempt, and why it ended."
- **Error:** a merge that conflicts raises the `--warn` notice with the files and **Ask Claude to resolve them** / **Abort the merge**; a check that fails is the `--bad` check badge, whose dialog holds the output; anything transient is a toast.
- **Happy path:** three sessions with one running, a diff of five files against main, four log entries, and a check that last passed.

**Layout - desktop (1280):** `.task-view` is a full-height flex column: head, optional bar, the tab strip, then the pane, which is the only thing that scrolls. The head is a two-column grid with the title and meta in the first and the actions in the second. The diff pane gets a 240px file list beside it above 900px.
**Layout - phone (390):** the head becomes one column with the actions under the title and only the title in `.h1`; the tab strip keeps **Changes**, **Log** and **Terminal** whole at every width down to 280px - the narrowest the gate looks at, and narrower than any phone that has shipped - and lets the session tabs take what is left and ellipsise, each tab at least 38px tall. Keeping them whole takes two things: the destinations are not allowed to shrink at all, and only the session tab is allowed under its own content. An equal share of the strip cut Terminal to "Term"; a disproportionate share that still let every tab shrink took a *fraction* of a pixel off each destination, which is all a `text-overflow:ellipsis` needs to eat a whole letter ("Changes" asked for 54.4px and was handed 53.8, so it drew "Chang…"). At 280px the three no longer fit even so, and the strip pays for them in side padding - 4px instead of 8 - rather than in letters; below 300px the session tab keeps its status dot and gives up its name, because the five pixels that were left drew a lone "R". A destination you have to guess at is worse than a session name that is short. **Ship…** replaces Commit, Push and Merge, because three buttons do not fit a header. Swiping the body moves between sessions the way the strip does, and is ignored while a field has focus or the pane is not a session.
**Avoid:** a task view that is a conversation with tabs bolted on; Merge offered while it cannot succeed instead of disabled with a reason; the terminal tab on a task that is no longer active; tabs that scroll out of reach on a phone.
**Chosen, not asked:**
- *The tab strip is the task's navigation.* A task is one thing with several faces; making Changes and Log into separate routes with their own headers would make each face a place, and the person would lose the task they are working on.
- *Swiping changes session, not tab.* The sessions are a sequence a person moves through; Changes and Log are not, so a swipe that landed on them would be a misread of intent.
- *Ship… exists only on the phone.* The reason a phone cannot show three buttons is the reason to give it one that opens them, not to drop them.

## View: Session (`#/p/:pid/s/:sid`, `#/p/:pid/t/:tid/s/:sid`)

**Job:** one Claude session's conversation - its transcript, what it is waiting on, and the box to talk to it.
**Pattern:** `list-detail`'s detail pane at full width, with `form` for the composer and `dialog` for the terminal.
**Primary action:** **Send** - the filled, circular button at the composer's right. Typing and sending is what a session is for, so the composer is the one prominent control; the header deliberately holds no filled button at all. Model, effort, mode and the way out collapse into one chip on a phone, because four selects in a header are four targets a thumb cannot hit.
**Hierarchy:**
1. The session's title as a rename button (650, `--accent` on hover) with its status badge - Working, Needs you, Error, Idle - and the estimated spend in mono, which is the one piece of chrome a touch device drops from this line: it is a figure you read while working at a desk, not while holding the phone.
2. The transcript, capped at 900px and centred: the person's turns as `--surface-2` bubbles on the right, Claude's as plain prose on the page, subagent text at 12.5px behind a rule, tool runs collapsed to one line each, turn endings at 11px in `--muted`.
3. The pending bar, when something is waiting: a `--warn` dot, "N waiting on you", and - for a permission only - inline Allow and Deny.
4. The composer, and above it the slash menu when a `/` starts a line.

**States:**
- **Loading:** the pane says `Loading…` before the transcript arrives, and "This session is gone." if it was deleted - two different truths that would be a lie to merge.
- **Empty:** "Say what you want done. Claude works in *cwd*." with the directory in mono. It names the actual directory because that is the thing a person has to be sure of before typing.
- **Error:** the session's own failure is a `.notice-bad` in the transcript with the message. A message typed while the socket is down is queued and the composer says how many are waiting, rather than being lost or refused.
- **Happy path:** a twelve-turn conversation with two collapsed tool runs, one waiting permission with a diff preview, quick-reply chips after the last turn, and a cost figure in the header.

**Layout - desktop (1280):** `.session` is a flex row: the chat column, and - when the terminal is docked - a `--surface-rail` aside at least 320px wide with a draggable 1px split, clamped so the chat never goes under 420px. The transcript scrolls; the composer is a bordered box on `--surface-rail` whose border turns `--accent-dim` while it has focus.
**Layout - phone (390):** one column, the composer near edge-to-edge with safe-area padding. The header is two lines: the title on the first with its badge and spend, the settings chip across the second. The chip does not share the title's line even though it would fit at 62%, because its model name is unbreakable and took the room the session's own name needed - and the name is the one thing on that line the chip cannot show in full when it opens. The composer's input is 16px - the largest text on the screen - so the phone's keyboard does not zoom, and it grows to 240px rather than turning into a full-screen editor. Docking is unavailable below 1000px, where the terminal is always the modal.
**Avoid:** four selects in the header; a filled header button competing with Send; the transcript auto-scrolling while a person is reading back; a tool call drawn as loudly as the prose; an approval that is only answerable at the bottom of a long transcript.
**Chosen, not asked:**
- *Streaming text is drawn at most once a frame.* Per-few-characters re-rendering made a long reply crawl on a phone; the transcript is the one place in the app where the cost of redrawing is the person's own reading.
- *Auto-scroll follows the bottom only while the reader is there.* Otherwise it offers "New reply". Yanking a page out from under someone mid-sentence is the one thing a transcript must never do.
- *Tool runs collapse to a line, but a single call does not.* The prose is what a person reads and the steps are what they open - and collapsing one call hides a thing with nothing to gain.
- *Bench's own tools read as what they do.* "Bench: run check", not `mcp__bench__run_check`: the transcript is read by a person, not by a log parser.
- *The composer's keyboard hint is two clauses, and the second is the one that goes.* "Enter to send" is true of any keyboard; "· Shift+Enter new line · Esc to stop" is a desk's shorthand. The line asks for 198px and the bar has that for it from 520px up; squeezed below that the ellipsis landed on the separator itself - "Enter to send · …" - which reads as a fault. So the second clause is droppable and the first is not, and on a finger `hide-touch` drops the whole line.

## View: Pairing (`#/pair/:code`) and Not paired

**Job:** trade a one-time pairing code for this device's own access - and, for a device that has none and no code, say how to get one.
**Pattern:** `auth` - one narrow column, one button, the only place in Bench where a centred card is right.
**Primary action:** **Pair** - the single filled button, full width, disabled while the name is blank or the request is in flight, and reading **Pairing…** while it is. The name is pre-filled from the device, because the one field on the screen should already have an answer.
**Hierarchy:**
1. `Pair this device` (20px) and one muted line of consequence - that it will be able to run sessions on this server until unpaired in Settings.
2. The one field, **Name it**, above its input.
3. The error, when there is one, in a `--bad` notice directly above the button - not at the top of the screen away from the action.
4. The button.

**States:**
- **Loading:** none; the code is in the route and the screen is ready as it paints.
- **Empty:** not applicable - the screen has one field with a value already in it.
- **Error:** the pairing failed, in a `--bad` notice above the button: an expired code, a code already used, a server that did not answer. The name the person typed stays, so the fix is one press rather than a retype.
- **Happy path:** the code is valid, the name is the guessed device name, and Pair lands on Home as this device.

**Not paired** is the screen's other face and is not a second design: `This device is not paired` (20px) with the instruction - to open Settings on the machine running Bench and choose **Pair a device**, or run `bench pair`, then open the link it gives - with **no button at all**. It has no primary action, because every action it could offer is one the person can only take somewhere else.

**Layout - desktop (1280):** one 420px column, centred both ways in the viewport, on plain `--bg` with no shell around it - a pairing screen is a doorway, and the navigation behind it is the thing being asked for. The card is `--surface-1` with the small shadow. Centring is not the usual "dead centre" mistake here: there is nothing else on the screen to weigh against it, so the card sits where the eye already is and the button lands under the thumb that came to press it.
**Layout - phone (390):** the same column at full width inside 16px of padding, still centred. The one field is the last thing the keyboard can cover and the first thing that wants it, so the browser's own focus-scroll into view is what keeps it visible - a hand-tuned offset would fight it.
**Avoid:** two filled buttons; a sign-in form the person cannot use; leading the unpaired screen with anything but the two ways to get a code; the app shell visible around either screen.
**Chosen, not asked:**
- *The unpaired screen has no button.* It is a dead end by construction - the credential has to be made on the server - so a button here would either do nothing or pretend to. The two real actions are named in the sentence instead.
- *Pairing is not a settings page.* It is one decision with one field, and it is reached by a link that arrives out of band; `auth`'s narrow column is the right shape for it and `settings`' groups are not.
- *The name is guessed and editable.* The device usually knows what it is, and the person is the only one who can say what they want it called in the devices list - so the field is filled in, not asked for.

## View: Settings (`#/settings`)
**Job:** change how Bench runs here - what a new session starts with, which providers and devices it uses, and whether the server is healthy - one setting at a time.
**Pattern:** `settings` (and `states`). Adapted: there are eight groups, so the pattern's section nav is used on desktop; on the phone it is dropped rather than turned into a list screen of sub-routes (see *Chosen, not asked*).
**Primary action:** changing a default in **Defaults** - the first group. Per the pattern there is **no page-level filled button**: each row's control is its own action and saves the moment it changes. The only `--accent` on the page is a switch that is on, the current item in the section nav, and the focus ring. Every button in the groups is `secondary` or `ghost`; the destructive ones (Unpair) are `danger`.
**Hierarchy:**
1. The group headings - Defaults, Providers, Notifications, Devices, Install, Spend, Diagnostics, Server (20px `--text-xl`, 650, `--font-title`).
2. Each row's label (14px `--text-md`, 600, `--text`) with its value or control on the right edge.
3. The consequence line under each label (12.5px `--text-sm`, `--muted`), and the group's one-line description under its heading.
4. Footnotes and machine detail - the server's pid and data folder, the diagnostics detail column - in `--muted`, mono where they are machine values.

**Group order** (by how often a person comes for it): Defaults · Providers · Notifications · Devices · Install (only when not already running as an installed app) · Spend (only once there is spend) · Diagnostics · Server last, since its actions restart the process every session runs in.

**Rows.** A row is the full width of the content column: label and consequence on the left, the control on the right, aligned to one right edge (control column `min(280px, 100%)` for selects and fields; switches and buttons at their own width, pushed to the edge). Rows are separated by one `--line` divider; groups by `2 × --space-6` (48px) of space, never by a border or a card. A long value - the projects folder path, a pairing URL - takes the row's full width under its label.

**Saving.** Selects and switches save on change (as today). The projects folder is a text field that needs a commit: its Save button appears only while the field is edited, and Enter saves (as today). In Defaults, a save shows "Saved" beside the control for 2 seconds; a save that fails shows a one-line `--bad` message in that row with "Try again", and the control keeps its old value. Other groups keep their toasts for now.

**States:**
- **Loading:** the page header, the section nav and every group heading render at once. Groups whose data is still coming (Devices, Diagnostics, Server) show skeleton rows at the real row height on `--surface-2` with a slow shimmer that respects reduced motion, instead of "Checking…" or nothing.
- **Empty:** Devices with nothing paired says "No other devices yet. This browser needs no pairing." above the Pair row. Spend with no usage is hidden, as today - nothing to estimate. Providers is never empty (the Claude login is always listed).
- **Error:** a failed load of Devices, Diagnostics or the server's status shows, in place of its rows, a row of its own - "Couldn't load diagnostics" (or devices, or the server's status) as the label, "Check that the server is running, then try again." as its consequence, and a "Try again" button on the right edge. No toast as well: the row already says which part failed, and a toast at the top covered the page header. A Defaults save error sits in its row (above). The server not being a service is a `notice-warn` in the Server group, as today.
- **Not available:** Notifications on a browser without push, or without HTTPS, shows its one muted line saying why and offers nothing to press - as today.
- **Happy path:** four providers (one on, eight models), one or two paired devices, five or six projects in Spend, ten diagnostics rows, a supervised server with Update.

**Layout - desktop (1280):** sidebar 260px. Content area padding 32px (`--space-7`). Page header: "Settings" left (20px title), theme picker right. Below, two columns: a sticky **section nav** 200px wide - one 32px row per group, `--muted` text, the group in view marked with `--accent` text on a `--surface-2` fill (page-shell's current-destination rule) - and, 32px to its right, the **groups column, max 680px**. Each group: heading, one muted description line 4px under it, 16px to the first row, rows 16px padded top and bottom.
**Layout - phone (390):** content padding 16px. The section nav is dropped; the groups are one scrolling page with their headings as the landmarks. Rows whose control is a select or a text field stack the control under the label at full width; rows whose control is a switch or a button keep it on the right. The model picker's two selects stack, each at full width, so the provider's name is not cut off. Diagnostics keeps three columns (tick, name, detail) rather than stacking name over detail. Groups 48px apart, as on desktop.
**Avoid:** every group in its own bordered card (what it was); filled accent buttons competing in every group; a page-level Save; Unpair styled like the buttons around it; consequence lines in the same ink as labels.

**Chosen, not asked:**
- *No filled button on the page.* The pattern says so, and the old page had four (Install, Pair, Turn on, sometimes Update), each shouting equally. "Update Bench" when there are newer commits keeps its emphasis through the "N newer commits" line in `--text`, not a fill.
- *A section nav on desktop, none on phone.* Eight groups is past the pattern's "under five" for a plain page. On the phone the pattern's list-of-groups screen would turn one route into nine, with back-button and deep-link behaviour Bench does not have today; this is a restyle, so the phone keeps one scrolling page. The nav's items scroll the page; they are buttons, not `#` links, because the hash is the router's.
- *Defaults first, Install no longer first.* Install was first as a nudge; it is a one-time thing for a device, so it sits with the other device groups.
- *48px between groups* is `calc(var(--space-6) * 2)` - composed from the scale, which tops out at 32px.
- *Inline save feedback in Defaults only.* It is the primary group and its saves are the ones a person makes and leaves; the other groups' toasts are unchanged so this stays a restyle.
