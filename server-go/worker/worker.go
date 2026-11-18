// Package worker is the Bench compute worker: a node process on any machine that links out to
// a Bench server, says what it can run while it is free, and takes bounded jobs. It holds no
// durable state - only a cache it may lose - so the server stays the source of truth.
//
// The protocol is the one the server already speaks to a linked device: hello, job,
// job_cancel, job_progress, job_done, ping and pong over a WebSocket.
package worker

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Version is what the worker tells the server it runs.
var Version = "0.1.0"

// Config is one worker's settings.
type Config struct {
	Server   string   `json:"server"`
	Token    string   `json:"token"`
	Name     string   `json:"name"`
	Cache    string   `json:"cache"`
	Labels   []string `json:"labels"`
	Kinds    []string `json:"kinds"`
	Slots    int      `json:"slots"`
	Chrome   string   `json:"chrome"`
	Platform string   `json:"platform"`
}

// DefaultKinds are what a worker offers when its config does not say.
var DefaultKinds = []string{"exec", "check", "render"}

// Normalise fills the defaults a worker needs to run.
func (c *Config) Normalise() error {
	c.Server = strings.TrimRight(strings.TrimSpace(c.Server), "/")
	if c.Server == "" {
		return fmt.Errorf("Give the server URL with --server")
	}
	if c.Token == "" {
		return fmt.Errorf("Give the worker's token with --token")
	}
	if c.Platform == "" {
		c.Platform = runtime.GOOS
	}
	if c.Name == "" {
		if h, err := os.Hostname(); err == nil {
			c.Name = h
		} else {
			c.Name = "worker"
		}
	}
	if c.Slots <= 0 {
		c.Slots = 1
	}
	if c.Cache == "" {
		dir, err := os.UserCacheDir()
		if err != nil {
			return err
		}
		c.Cache = filepath.Join(dir, "bench-worker")
	}
	if len(c.Kinds) == 0 {
		c.Kinds = append([]string{}, DefaultKinds...)
	}
	return nil
}

// Conn is one live link to the server.
type Conn struct {
	cfg     Config
	ws      *websocket.Conn
	writeM  sync.Mutex
	running map[string]*job
	log     func(string, ...any)
}

type job struct {
	id     string
	cancel context.CancelFunc
	done   chan struct{}
}

// Dial links to the server once and sends the hello.
func Dial(ctx context.Context, cfg Config) (*Conn, error) {
	h := http.Header{}
	h.Set("Authorization", "Bearer "+cfg.Token)
	wsURL := cfg.Server
	if strings.HasPrefix(wsURL, "https://") {
		wsURL = "wss://" + strings.TrimPrefix(wsURL, "https://")
	} else {
		wsURL = "ws://" + strings.TrimPrefix(wsURL, "http://")
	}
	ws, _, err := websocket.Dial(ctx, wsURL+"/api/workers/link", &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		return nil, err
	}
	ws.SetReadLimit(32 * 1024 * 1024)
	c := &Conn{cfg: cfg, ws: ws, running: map[string]*job{}, log: func(string, ...any) {}}
	if err := c.send(map[string]any{"type": "hello", "name": cfg.Name, "version": Version,
		"platform": cfg.Platform, "labels": cfg.Labels, "kinds": cfg.Kinds, "slots": cfg.Slots,
		"resources": resources()}); err != nil {
		ws.Close(websocket.StatusNormalClosure, "")
		return nil, err
	}
	return c, nil
}

// SetLogger sends the worker's own lines somewhere.
func (c *Conn) SetLogger(f func(string, ...any)) { c.log = f }

func resources() map[string]any {
	return map[string]any{"cpus": runtime.NumCPU(), "memMb": 0, "os": runtime.GOOS, "arch": runtime.GOARCH}
}

func (c *Conn) send(v any) error {
	if c.ws == nil { // a unit test with no link
		return nil
	}
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	c.writeM.Lock()
	defer c.writeM.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return c.ws.Write(ctx, websocket.MessageText, raw)
}

// Serve reads the server's messages until the link closes. Jobs run in their own goroutines,
// so several may run at once when the slots allow.
func (c *Conn) Serve(ctx context.Context) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	defer func() {
		c.writeM.Lock()
		defer c.writeM.Unlock()
		for _, j := range c.running {
			j.cancel()
		}
	}()
	for {
		_, raw, err := c.ws.Read(ctx)
		if err != nil {
			return err
		}
		var msg map[string]any
		if err := json.Unmarshal(raw, &msg); err != nil {
			continue
		}
		switch str(msg["type"]) {
		case "job":
			c.start(jobFrom(msg), ctx)
		case "job_cancel":
			c.stop(str(msg["id"]))
		case "ping":
			c.send(map[string]any{"type": "pong"})
		case "pong":
		}
	}
}

// Close ends the link.
func (c *Conn) Close() {
	c.ws.Close(websocket.StatusNormalClosure, "")
}

func jobFrom(msg map[string]any) *request {
	r := &request{ID: str(msg["id"]), Kind: str(msg["kind"]), Args: map[string]any{}}
	if args, ok := msg["args"].(map[string]any); ok {
		r.Args = args
	}
	if limits, ok := msg["limits"].(map[string]any); ok {
		if n, ok := limits["timeoutSec"].(float64); ok && n > 0 {
			r.Timeout = time.Duration(n) * time.Second
		}
	}
	if r.Timeout <= 0 {
		r.Timeout = 15 * time.Minute
	}
	return r
}

// start runs one job with its own deadline. A job that panics fails; it never takes the
// worker down.
func (c *Conn) start(r *request, parent context.Context) {
	ctx, cancel := context.WithTimeout(parent, r.Timeout+5*time.Second)
	j := &job{id: r.ID, cancel: cancel, done: make(chan struct{})}
	c.writeM.Lock()
	c.running[r.ID] = j
	c.writeM.Unlock()
	c.log("job %s: %s", r.ID, r.Kind)
	go func() {
		defer func() {
			if p := recover(); p != nil {
				c.send(map[string]any{"type": "job_done", "id": r.ID, "ok": false, "error": fmt.Sprint("the job panicked: ", p)})
			}
			cancel()
			c.writeM.Lock()
			delete(c.running, r.ID)
			c.writeM.Unlock()
			close(j.done)
		}()
		started := time.Now()
		res, err := c.doJob(ctx, r)
		if err != nil {
			c.send(map[string]any{"type": "job_done", "id": r.ID, "ok": false, "error": err.Error(), "ranMs": time.Since(started).Milliseconds()})
			return
		}
		res["type"] = "job_done"
		res["id"] = r.ID
		res["ok"] = true
		res["ranMs"] = time.Since(started).Milliseconds()
		c.send(res)
	}()
}

func (c *Conn) stop(id string) {
	c.writeM.Lock()
	j := c.running[id]
	delete(c.running, id)
	c.writeM.Unlock()
	if j != nil {
		j.cancel()
	}
}

// progress sends a line of a running job's output, keeping messages small.
func (c *Conn) progress(id, text string) {
	if len(text) > 8000 {
		text = text[len(text)-8000:]
	}
	c.send(map[string]any{"type": "job_progress", "id": id, "text": text})
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

// Run links and re-links until ctx ends, waiting a little longer after each failure. A dropped
// link costs the jobs it was running, never the worker.
func Run(ctx context.Context, cfg Config, log func(string, ...any)) error {
	if err := cfg.Normalise(); err != nil {
		return err
	}
	if log == nil {
		log = func(string, ...any) {}
	}
	wait := time.Second
	for ctx.Err() == nil {
		conn, err := Dial(ctx, cfg)
		if err == nil {
			log("linked to %s as %s", cfg.Server, cfg.Name)
			wait = time.Second
			err = conn.Serve(ctx)
			conn.Close()
		}
		if ctx.Err() != nil {
			break
		}
		if err != nil {
			log("link lost: %v; reconnecting in %s", err, wait)
		}
		select {
		case <-ctx.Done():
		case <-time.After(wait):
		}
		if wait < 30*time.Second {
			wait *= 2
		}
	}
	return ctx.Err()
}
