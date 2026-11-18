# Build the worker on its own, then run it with a token.
#
#   docker build -f worker/Dockerfile -t bench-worker .
#   docker run -d --restart=always --name bench-worker \
#     -e BENCH_SERVER=https://bench.example -e BENCH_TOKEN=<token from enrol> \
#     -v bench-worker-cache:/var/cache/bench-worker bench-worker
#
# The image carries Chromium so the worker can render pages. It holds no durable state: the
# cache volume may be thrown away at any time.
FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/bench-worker ./cmd/bench-worker

FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates chromium \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/bench-worker /usr/local/bin/bench-worker
ENV BENCH_CACHE=/var/cache/bench-worker
VOLUME /var/cache/bench-worker
# The flags come from the environment; the worker itself takes only these settings.
ENTRYPOINT ["/bin/sh", "-c", "exec bench-worker run --server \"$BENCH_SERVER\" --token \"$BENCH_TOKEN\" --name \"${BENCH_NAME:-container}\" --cache \"$BENCH_CACHE\" --chrome /usr/bin/chromium"]
