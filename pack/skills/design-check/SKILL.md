---
name: design-check
description: "Apply when a UI change is ready to verify, before calling it done, or when wiring a project's design gate. Renders the page in a browser and holds it to the floor rules (contrast, targets, type size, spacing grid, tokens, real buttons, focus, sideways scroll, reduced motion, content without scripts, alt, zoom, eyebrows, placeholder copy), reports reflex tells and finish details as warnings, and answers pass, defect, or could-not-look. Not for backend changes."
---

# Gate a page's design

A session can see a page's HTML but not the page. This renders it in a real browser and holds it to what a machine can judge, in the three tiers of `$(bench pack)/references/rules.md`: **floor** findings are defects; **reflex** findings (`references/tells.md`) are warnings to fix or waive under `## Waivers` in `Design.md`; **finish** findings (`references/polish.md`) are notes. Only the floor changes the exit code. It cannot tell you the layout is right or the design is any good - `verify-ui`, `design-review` and the person's eyes are for that.

Its static half also runs on its own and as the design hook: `node "$(bench pack)/skills/design-check/static.mjs" <files or folders> --json` reads the source with no browser (exit 1 on a floor finding).

1. Find how the app runs and at what URL: package scripts, README, the project's own verify skill. Start it. Every command you write down, run first.
2. Find the design tokens - a CSS file of custom properties, or JSON of values. The gate looks for the usual names itself (`tokens.css`, `design-tokens.json`, `src/styles/tokens.css`) and takes `--tokens <file>` for anything else. **No token file: say so and move on** - the colour and spacing token rules are skipped, and the gate says so in its report. Do not add a token file so the gate has something to read; a project gets one through `design-tokens`, with the person.
3. Write `.bench/design-check.json` so every later run and the task's check judge the same pages:
   ```json
   { "routes": ["/", "/settings"], "themes": ["light", "dark"], "grid": 8, "bodyPx": 16, "wait": 30000 }
   ```
   `grid` only when the project is not on 8s. `bodyPx` only when the project has decided on a body text smaller than 16px and written that decision down (in `Design.md`, say) - never to make a finding go away. `themeScript` only when the app picks its theme from its own state - a localStorage key, a class it sets itself - rather than from `prefers-color-scheme`, which is all `--themes` sets otherwise; without it such an app renders the same theme twice and the dark contrast check sees nothing. Find where the app reads its theme, write the script that sets it as `.bench/design-check.theme.js`, and add `"themeScript": ".bench/design-check.theme.js"` to the config: it runs before the app's own scripts on every load with `theme` (`"light"` or `"dark"`) in scope, for example `localStorage.setItem('app:theme', theme);`. If it throws, the gate could not look. Check it works by shooting both themes and opening the pictures. `ignore` takes selectors the rules cannot judge, such as a third-party widget's; each one is a hole in the gate, so put the reason next to it in the file. `ignoreRules` takes reflex or finish rule ids the project does not want reported (floor ids are refused and still fire); prefer a waiver in `Design.md`, which says why. The same file controls the design hook: `"hook": false` turns it off for the project, `"ignoreFiles": ["src/legacy/**"]` leaves files out of it.
4. Run it. `PACK="$(bench pack)"` is the folder the skills and their scripts live in - if `bench` is not on your PATH, it is the newest `~/.local/share/bench/releases/*/pack` here, or ask the person.
   `node "$PACK/skills/design-check/design-check.mjs" --url http://127.0.0.1:5173 --json`. `--help` lists the rest: `--routes`, `--themes`, `--theme-script`, `--widths`, `--width`, `--grid`, `--body-px`, `--ignore`, `--ignore-rules`, `--wait`, `--shots`, `--only-shots`, `--label`.
5. Read the exit code, not the text. **0 passed, 1 found a defect and named it, 2 could not look.** Fix each defect - the finding says which selector, which value, and what it should be. Then read the warnings (`warnings` in the JSON): fix each reflex finding or waive it in `Design.md` with its reason; fix or answer each finish note. A pass with reflex warnings left unanswered is not done. A 2 means the gate never saw your page: wrong URL or route, the app not up, a browser that is not installed. Fix that and run it again. A 2 is a failure, never a pass.
6. Wire it as one command in `.bench/design-check.sh`, which is what a check runs:
   ```sh
   #!/bin/sh
   # Starts the app, waits for it to answer, gates the pages, stops only what it started.
   PORT=${PORT:-5173}; export PORT
   npm start & APP=$!
   trap 'kill $APP 2>/dev/null' EXIT     # runs however the script ends
   # ...wait until $PORT answers, or give up
   node "$(bench pack)/skills/design-check/design-check.mjs" --url "http://127.0.0.1:$PORT"
   exit $?                               # the gate's code, 2 included
   ```
   No `exec` and no `set -e` on that line: `exec` would replace the shell and the trap would never stop the app, and `set -e` would leave it running too. Carry the code out by hand. If the URL already answers before you start anything, gate what is running, start nothing, and stop nothing - you did not start it. Stop only the process you started, and if the launcher spawns children of its own, stop its whole process group: never kill by port or by name.
7. Prove the wiring, both ways: with the app down it must fail rather than pass, and with a defect planted - a `width: 15px` button - it must come back 1 and name that button. Put the tree back as you found it, and check you left no process of yours running.
8. Tell the sessions that come after you. Put this block in the project's `CLAUDE.md` - create the file if there is none, otherwise add the block and change nothing else. If the markers are already there, replace what is between them rather than adding a second block:
   ```md
   <!-- bench:design-check -->
   UI changes: before you finish, run `/bench:verify-ui` and show its before and after screenshots. The design gate is `sh .bench/design-check.sh`: 0 passed, 1 a defect it names, 2 it could not look, which is a failure. Fix what it names.
   <!-- /bench:design-check -->
   ```
9. Commit `CLAUDE.md`, `.bench/design-check.sh` and `.bench/design-check.json` (and `.bench/design-check.theme.js` if you wrote one). A task works in its own worktree, which has only what is committed, so wiring left untracked is not there for the next task. If `git check-ignore -v .bench/design-check.sh` names a rule, the project ignores `.bench/`: change that rule from `.bench/` to `.bench/*` (git cannot re-include a file whose folder is ignored) and add `!.bench/design-check.*` under it, then check again. A project that gitignores `.bench/` must un-ignore the config this way, or the next task never sees it.
10. A session cannot set its own task's check. Hand the person the line to paste into the task's check - `sh .bench/design-check.sh` - and how long it takes to run.

**Reply:** the config you wrote, what you added to `CLAUDE.md`, the command you ran, its real output and exit code, what you fixed, and anything the gate cannot see here that a person should look at.
