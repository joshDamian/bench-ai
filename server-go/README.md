# bench-go

The Bench server, in Go: a second implementation of `src/server.mjs` and everything
behind it, to run beside the Node server and compare. It serves the same HTTP and
WebSocket API, reads and writes the same database schema, and serves the same web app,
so the Mac, Android and web clients can point at it unchanged.

## What it covers

Everything the Node server does over its API:

- **Projects, tasks and git:** add, clone or create a project; the checkout's branches,
  fetch, pull, push, commit and diff; tasks in their own worktrees, with the update from
  base, merge (merge or squash, with or without cleanup), close, reopen, push and PR.
- **Sessions on Claude Code:** start, resume, interrupt, mode, model and effort changes,
  attachments and uploads, `@file` and `@note:` mentions, approvals (with "always allow",
  plan exit and questions), slash commands, prompt suggestions, usage and cost, and
  limit pauses with auto-resume and hand-off to a fallback provider.
- **Bench's own tools for sessions:** the `bench` MCP server for project sessions and the
  Ask Bench one, all of their tools, served in-process over the same control protocol.
- **The task's bar:** the check run on Stop (up to three tries per message), the
  loop-until-done with its caps and write-up, reviews and the decision log.
- **The rest:** notes, the Files tab, subagent routing, providers and models, what
  sessions leave in /tmp, terminals (pty, tabs, scrollback), the dev-server preview
  proxy (HTTP and WebSocket), pairing with QR codes, Web Push, metrics, doctor, the server
  status, restart and Update.

It drives the `claude` CLI directly over its stream-json control protocol: the same
arguments, `initialize` request, `can_use_tool`, `hook_callback` and `mcp_message`
exchanges the Agent SDK uses, captured from `@anthropic-ai/claude-agent-sdk` 0.3.302. By
default it runs the Claude Code binary that SDK ships in `node_modules`, the one the Node
server runs; `BENCH_CLAUDE_BIN` points it at another.

Not ported: the one-time import of a v1 (pipeline-era) database, and the `bench` CLI's
other commands. bench-go only serves (`bench-go serve`) and copies a data folder
(`bench-go copy-data`); keep using `bench` for the rest.

## Try it on the server

```sh
cd ~/bench-ai            # the checkout
git pull
sh server-go/install --service
```

That builds bench-go (it downloads a Go toolchain into `~/.local/share/bench-go` if the
machine has none; it needs a C compiler, for SQLite) and starts it as `bench-go.service`
on port 4318, on `~/.bench-go`: a copy of `~/.bench` taken as it installs.

To reach it from a phone or the Mac, give it its own Tailscale port:

```sh
tailscale serve --bg --https=8444 http://127.0.0.1:4318
```

and add `https://<machine>.<tailnet>.ts.net:8444` as a server in the app. Devices paired
with the Node server are paired with the copy too.

- `sh server-go/install --service --recopy` replaces the copy with a fresh one.
- `systemctl --user stop bench-go` stops it; `systemctl --user disable --now bench-go`
  and deleting `~/.config/systemd/user/bench-go.service`, `~/.local/share/bench-go` and
  `~/.bench-go` removes it.

**What is shared.** The copy is of Bench's own data: the database, keys file, notes and
attachments. Project folders and task worktrees are the same folders on disk for both
servers, and so are Claude Code's own conversation files. So don't work on the same task
from both at once, and remember that merging a task in bench-go merges it in the real
repository. Both servers also send push notifications to the same devices, each for its
own sessions.

## Conformance

```sh
go build -o server-go/bin/bench-go ./server-go/cmd/bench-go   # from server-go/: go build -o bin/bench-go ./cmd/bench-go
node server-go/conformance/run.mjs
```

Runs one scenario of about 120 steps against both servers, each on a fresh data folder
and its own copy of a test repository, both on `conformance/fake-claude.mjs` (a stand-in
CLI that speaks the stream-json protocol, so no model is called). Every answer, and the
WebSocket stream, is normalised (ids, times, hashes, paths) and compared byte for byte.

`go test ./bench` checks the JSON writer against Node's `JSON.stringify` and the Web Push
encryption against the `web-push` package's own decrypter.

## Benchmark

```sh
node server-go/benchmark/run.mjs
```

Starts both servers on fresh data with the fake CLI, seeds the same projects, tasks and
transcripts into both, and measures startup, memory, HTTP latency and throughput (at 1
and 16 concurrent clients), session turns (one session, and ten at once) and WebSocket
fan-out (50 clients on one streaming session). Memory and CPU are the server process's
alone. It writes a Markdown and a JSON report.

Against the two servers running on the VPS, read-only (GET requests only, so neither
changes):

```sh
node server-go/benchmark/run.mjs \
  --node-url=http://127.0.0.1:4317 --go-url=http://127.0.0.1:4318 \
  --node-pid=$(systemctl --user show -p MainPID --value bench) \
  --go-pid=$(systemctl --user show -p MainPID --value bench-go)
```

## Layout

- `cmd/bench-go`: the entry point.
- `bench/`: the server, one file per Node module: `store.go` (store.mjs), `git.go`,
  `service.go`, `runtime.go`, `claude.go` (the CLI protocol), `tools.go` and `ask.go` (the
  MCP tools; their schemas are in `mcp_manifests.json`, captured from the Node server's zod
  definitions), `loop.go` (loop and resume), `notes.go`, `check.go`, `routing.go` (routing
  and limits), `terminal.go`, `metrics.go` (metrics and update), `push.go`, `server.go`.
- `obj.go`: an ordered JSON object, so answers keep JavaScript's key order.

## Taking turns on the same data

To drive bench-go on your real data, with the option to go back to Node at any time:

```sh
sh server-go/install --shared     # backs up ~/.bench once, then stops Node and starts Go
sh server-go/switch node          # back to Node
sh server-go/switch go            # and to Go again
sh server-go/switch status        # which one is on
```

Both servers use `~/.bench` and the Node server's port, so the apps and your Tailscale address
need no change. Only one runs at a time: `switch` stops one before starting the other, and the
one you switch to is the one that starts at login. The two share the same schema and migrations,
so either can open what the other wrote. Running sessions resume on whichever server is on.

The apps can also keep several servers (Settings → Server → Add a server) if you'd rather run
bench-go on its own copy and port and switch in the app instead.
