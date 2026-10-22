---
name: cleanup
description: "Apply when a task or session is wrapping up, before it is merged or handed back, or when the person asks to tidy up temporary files. Lists what this session or task left in /tmp - Bench records it as the tools run - keeps anything worth keeping, and removes the rest."
---

# Clean up after this work

Bench records what appears in /tmp while each of your tools runs: downloads, logs, dumps, unpacked archives, servers' working folders, browser profiles. You do not have to remember any of it. Archiving the session removes what nothing has open, so this is for tidying while the session is still open, and for keeping what should not go.

1. Call `list_artifacts` with `scope: "task"` in a task (or the default, this session, in a checkout). Each item says its size, whether a process still has it open, and which tool call made it.
2. Sort each item:
   - **Worth keeping** - a build, an export, a report, a screenshot the person asked for. Move it into the project (or tell the person where it is and why it matters) before cleaning. Never remove a deliverable to tidy up.
   - **Held open** - a dev server, a watcher, a background job you started. Stop it if you started it and it is no longer needed; leave it if the person is using it. `clean_up` will not remove anything open either way.
   - **Temporary** - everything else.
3. Call `clean_up` with the same scope. Pass `paths` when only some of the items should go.
4. Reply with what was removed and its size, what was kept and why, and anything you moved into the project.

From here on, put temporary files in your scratch folder (`$TMPDIR`) rather than /tmp: it goes with the session, and nothing has to be cleaned.

Do not reach for `rm` in /tmp yourself. `clean_up` touches only what Bench saw this work create, checks that nothing holds it open, and records what it removed; a hand-written `rm -rf /tmp/...` does none of that and can take another session's files with it.
