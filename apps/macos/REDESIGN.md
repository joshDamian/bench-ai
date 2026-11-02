# Bench for Mac: the redesign

The plan the native clients are being rebuilt to. The mocks are on the canvas "Bench for Mac redesign"; each screen's job and primary action come from `Design-brief.md`.

## Screens

- **Session** — centred column (max 720), replies in full; everything Claude did between two of your messages folds to one line, "Worked for 2m 54s · 14 steps"; while working, only the current step shows. Turn end shows cost and Copy. Composer is a rounded card: attach, model line, Stop (while working), Send.
- **Session needing you** — the approval replaces the composer. One filled button: Allow (⇧⌘↩). Allow for This Session, Deny (⌘⌫), optional note.
- **Task** — title, check badge, `branch → base · +N −N · N ahead`; toolbar group Push, Pull Request…, More; filled Merge into main. Tabs: one per session, **+** (new session in this task), then Changes, Log, Loop, Terminal. Changes = file list (232px) + diff.
- **Project** — name and path; toolbar group: branch menu, Pull ↓N, Push, Commit… N. New-task card (⌘↩ Start Task). Tasks, Sessions in the checkout, Merged and closed (folded).
- **Needs you** — approvals and plans answerable in place; Finished recently below.
- **Menu bar** — Needs you (answerable), Working, New Session…, Open Bench.
- **Connect** — paste a `bench pair` link; warns when the Bench on this Mac is older and doesn't silently connect to it.
- **New Session (⌘N)** — Project (or Ask Bench), Where: New Task | Checkout, Mode, prompt, Start.
- **Go to Session (⌘K)** — search sessions; last row starts a new one from the query.
- **Settings (⌘,)** — tabs Server, Models, Alerts, Theme. Theme: all 11, "Match macOS appearance" with a dark and a light pick; per device.
- **Terminal** — a tab on a task and "Open Terminal" on any session, connected to the server's `/api/terminal` (the same shell the web shows), drawn with SwiftTerm. Terminal.app only when the server is this Mac.

## Shell

Floating sidebar (272px): ⌘K field, Needs you and Working filters, sessions grouped by project with Ask Bench as a group, server status footer. Toolbar items grouped on glass; glass only on navigation and controls, never content.

## Copy rules

- Title case for buttons and menu items; sentence case for labels, placeholders, explanations. Branch names stay as code.
- "…" on anything that opens another window or sheet.
- Buttons are one or two words naming the result. No Yes/No; Cancel is "Cancel"; never a destructive default.
- Errors: what happened and what to do ("Can't reach ubuntu-… on port 8443. Check that Tailscale is on.").
- One term each: session, task, checkout, Ask Bench; states Needs you and Working.
- Never repeat what the screen already says; cut words until the meaning breaks.

## Build order

1. Transcript: fold steps across events, live step line, turn end (Mac and Android).
2. Shell: sidebar, filters, toolbar, ⌘K, ⌘N sheet.
3. Approvals in the composer slot, Needs you view, menu bar.
4. Project and Task views (git actions, Changes diff, Log, Loop).
5. Terminal (SwiftTerm over /api/terminal), Settings tabs, themes with Match macOS appearance.
6. Connect screen with the version check.
