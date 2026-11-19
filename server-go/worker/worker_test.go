package worker

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func testConn(t *testing.T) *Conn {
	t.Helper()
	return &Conn{cfg: Config{Cache: t.TempDir()}, running: map[string]*job{}, log: func(string, ...any) {}}
}

func TestExecRunsWithoutAShell(t *testing.T) {
	c := testConn(t)
	r := &request{ID: "j1", Kind: "exec", Timeout: 10 * time.Second,
		Args: map[string]any{"argv": []any{"/bin/sh", "-c", "echo hello; exit 3"}}}
	res, err := c.doJob(context.Background(), r)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(str(res["text"]), "hello") || num(res["exit"]) != 3 {
		t.Fatalf("result %v", res)
	}
}

func TestExecRefusesAnUnknownKind(t *testing.T) {
	c := testConn(t)
	if _, err := c.doJob(context.Background(), &request{Kind: "docker", Timeout: time.Second}); err == nil {
		t.Fatal("an unknown kind ran")
	}
}

func tarGz(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, body := range files {
		h := &tar.Header{Name: name, Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg}
		if strings.HasSuffix(name, "/") {
			h.Typeflag = tar.TypeDir
			h.Size = 0
			h.Mode = 0o755
		}
		if err := tw.WriteHeader(h); err != nil {
			t.Fatal(err)
		}
		if h.Size > 0 {
			tw.Write([]byte(body))
		}
	}
	tw.Close()
	gz.Close()
	return buf.Bytes()
}

func TestCheckFetchesTheTreeAndRunsIt(t *testing.T) {
	gz := tarGz(t, map[string]string{"hello.txt": "from the tree\n"})
	sum := sha256.Sum256(gz)
	hash := hex.EncodeToString(sum[:])
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			w.WriteHeader(401)
			return
		}
		w.Write(gz)
	}))
	defer srv.Close()
	c := testConn(t)
	c.cfg.Server = srv.URL
	c.cfg.Token = "secret"
	r := &request{ID: "j2", Kind: "check", Timeout: 10 * time.Second, Args: map[string]any{
		"command": "cat hello.txt", "treeUrl": "/api/jobs/t1/tree", "treeHash": hash}}
	res, err := c.doJob(context.Background(), r)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(str(res["text"]), "from the tree") || num(res["exit"]) != 0 {
		t.Fatalf("result %v", res)
	}
	// The same tree again is served from the cache, with no second request.
	srv.Close()
	if _, err := c.doJob(context.Background(), r); err != nil {
		t.Fatalf("the cached tree was not used: %v", err)
	}
}

func TestCheckRefusesABadHash(t *testing.T) {
	gz := tarGz(t, map[string]string{"a.txt": "x"})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(gz) }))
	defer srv.Close()
	c := testConn(t)
	c.cfg.Server = srv.URL
	r := &request{ID: "j3", Kind: "check", Timeout: 5 * time.Second, Args: map[string]any{
		"command": "true", "treeUrl": "/tree", "treeHash": strings.Repeat("a", 64)}}
	if _, err := c.doJob(context.Background(), r); err == nil || !strings.Contains(err.Error(), "hash") {
		t.Fatalf("err %v", err)
	}
}

func TestUnpackRefusesTraversal(t *testing.T) {
	gz := tarGz(t, map[string]string{"../escape.txt": "x"})
	dir := t.TempDir()
	if err := unpackTree(gz, dir); err == nil {
		t.Fatal("a tree escaped its folder")
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dir), "escape.txt")); err == nil {
		t.Fatal("the file was written outside the tree")
	}
}

// The full protocol: hello out, a job in, progress and done out.
func TestWorkerProtocol(t *testing.T) {
	finished := make(chan struct{}, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer ws.Close(websocket.StatusNormalClosure, "")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_, raw, err := ws.Read(ctx)
		if err != nil {
			t.Error(err)
			return
		}
		var hello map[string]any
		json.Unmarshal(raw, &hello)
		if hello["type"] != "hello" || hello["name"] != "Test box" || hello["platform"] == "" {
			t.Errorf("hello %s", raw)
			return
		}
		ws.Write(ctx, websocket.MessageText, []byte(`{"type":"job","id":"j9","kind":"exec","args":{"argv":["/bin/sh","-c","echo working"]},"limits":{"timeoutSec":10}}`))
		for {
			_, raw, err := ws.Read(ctx)
			if err != nil {
				return
			}
			var msg map[string]any
			json.Unmarshal(raw, &msg)
			if msg["type"] == "job_done" {
				if msg["ok"] != true || !strings.Contains(msg["text"].(string), "working") {
					t.Errorf("done %s", raw)
				}
				finished <- struct{}{}
				return
			}
		}
	}))
	defer srv.Close()

	cfg := Config{Server: srv.URL, Token: "t", Name: "Test box", Cache: t.TempDir(), Slots: 1}
	if err := cfg.Normalise(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := Dial(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	go conn.Serve(ctx)
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("the worker did not finish the job")
	}
}
