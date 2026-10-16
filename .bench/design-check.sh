#!/bin/sh
# The design gate: 0 passed, 1 a defect it names, 2 it could not look.
#
# Starts a fresh Bench on a data folder of its own, with the fake SDK so nothing is spent,
# seeds it with a typical day's work, gates every route in .bench/design-check.json, and
# stops only what it started. The browser is on loopback, so it is this machine's own and
# needs no pairing: it sees the real views, never the Unpaired page.
#
# If $PORT already answers, that Bench is gated as it is - nothing is seeded into it and
# nothing is stopped. The default port is not 4317 so a Bench in daily use is left alone.
#
# Arguments go to the gate, so the same seeded app gives the screenshots:
#   sh .bench/design-check.sh --only-shots --shots .bench/evidence --shots-themes light,dark
PORT=${PORT:-4399}; export PORT
URL="http://127.0.0.1:$PORT"
HERE=$(pwd)
# This repository ships the gate; gate with the one in this tree, not an installed release.
if [ -f "$HERE/pack/skills/design-check/design-check.mjs" ]; then PACK="$HERE/pack"; else PACK=$(bench pack); fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/bench-design-check.XXXXXX") || exit 2
APP=
cleanup() {
  # `cli.mjs serve` runs the server in its own process, with no children of its own: the
  # pid started here is the whole app. Never by port or by name.
  [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 2' INT TERM

up() { node -e "fetch('$URL/api/ping').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"; }

if up; then
  echo "design-check: $URL already answers - gating it as it is, seeding nothing" >&2
  IDS=$(node .bench/design-check.seed.mjs "$URL" --pick) || exit 2
else
  mkdir -p "$WORK/data" "$WORK/repos"
  BENCH_ROOT="$WORK/data" BENCH_FAKE_SDK=1 node src/cli.mjs serve >"$WORK/server.log" 2>&1 &
  APP=$!
  i=0
  until up; do
    i=$((i + 1))
    if [ $i -gt 120 ] || ! kill -0 "$APP" 2>/dev/null; then
      echo "design-check: could not look - Bench did not start on $URL"; cat "$WORK/server.log"; exit 2
    fi
    sleep 0.25
  done
  IDS=$(node .bench/design-check.seed.mjs "$URL" "$WORK/repos" | tail -n 1) || { echo "design-check: could not look - seeding failed"; exit 2; }
fi

# The committed config with this run's ids in its routes.
node -e '
  const fs = require("fs");
  const [file, ids, out, url] = process.argv.slice(1);
  const c = JSON.parse(fs.readFileSync(file, "utf8"));
  const id = JSON.parse(ids);
  c.routes = c.routes.map((r) => r.replace(/\{(\w+)\}/g, (_, k) => id[k]));
  c.url = url;
  fs.writeFileSync(out, JSON.stringify(c));
' .bench/design-check.json "$IDS" "$WORK/config.json" "$URL" || exit 2

node "$PACK/skills/design-check/design-check.mjs" --config "$WORK/config.json" "$@"
exit $?
