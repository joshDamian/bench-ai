package worker

// The kinds a worker runs: exec (argv, no shell), check (a command inside a tree the server
// sent), render (a page to a picture or PDF). Each is bounded by the job's timeout and keeps
// only the tail of a long output. An exit code other than 0 is a result, not a failure; a job
// that cannot start is a failure.

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// request is one job as the worker reads it.
type request struct {
	ID      string
	Kind    string
	Args    map[string]any
	Timeout time.Duration
}

const (
	outputCap = 1 << 20 // the tail of output kept for the server
	argvMax   = 128
)

func (c *Conn) doJob(ctx context.Context, r *request) (map[string]any, error) {
	switch r.Kind {
	case "exec":
		return c.runArgv(ctx, r)
	case "check":
		return c.runCheck(ctx, r)
	case "render":
		return c.runRender(ctx, r)
	}
	return nil, fmt.Errorf("this worker does not run %s jobs", r.Kind)
}

func (c *Conn) runArgv(ctx context.Context, r *request) (map[string]any, error) {
	argv := strList(r.Args["argv"])
	if len(argv) == 0 {
		if cmd := str(r.Args["command"]); cmd != "" {
			return c.runShell(ctx, r, cmd, c.workDir())
		}
		return nil, fmt.Errorf("exec needs argv")
	}
	if len(argv) > argvMax {
		return nil, fmt.Errorf("too many arguments")
	}
	cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
	cmd.Dir = c.workDir()
	return c.collect(r, cmd)
}

// runCheck downloads the tree the server packed, unpacks it under the cache by hash, and runs
// the project's command there. The tree stays cached so the next check of the same tree is
// local.
func (c *Conn) runCheck(ctx context.Context, r *request) (map[string]any, error) {
	command := str(r.Args["command"])
	if command == "" {
		return nil, fmt.Errorf("check needs a command")
	}
	dir, err := c.fetchTree(ctx, r)
	if err != nil {
		return nil, err
	}
	return c.runShell(ctx, r, command, dir)
}

func (c *Conn) fetchTree(ctx context.Context, r *request) (string, error) {
	hash := str(r.Args["treeHash"])
	rel := str(r.Args["treeUrl"])
	if hash == "" || rel == "" {
		return "", fmt.Errorf("check needs the tree's hash and URL")
	}
	if len(hash) < 16 || strings.ContainsAny(hash, `/\.`) {
		return "", fmt.Errorf("the tree hash is not a hash")
	}
	dir := filepath.Join(c.cfg.Cache, "trees", hash)
	if _, err := os.Stat(filepath.Join(dir, ".bench-ok")); err == nil {
		return dir, nil
	}
	url := rel
	if strings.HasPrefix(rel, "/") {
		url = c.cfg.Server + rel
	}
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+c.cfg.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("the tree could not be fetched: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("the tree could not be fetched: %s", resp.Status)
	}
	gz, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(gz)
	if hex.EncodeToString(sum[:]) != hash {
		return "", fmt.Errorf("the tree did not match its hash")
	}
	os.MkdirAll(filepath.Join(c.cfg.Cache, "trees"), 0o755)
	tmp, err := os.MkdirTemp(filepath.Join(c.cfg.Cache, "trees"), "incoming-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tmp)
	if err := unpackTree(gz, tmp); err != nil {
		return "", err
	}
	os.WriteFile(filepath.Join(tmp, ".bench-ok"), []byte(r.ID), 0o644)
	os.MkdirAll(filepath.Dir(dir), 0o755)
	if err := os.Rename(tmp, dir); err != nil {
		// Another check unpacked the same tree at once; theirs will do.
		if _, statErr := os.Stat(filepath.Join(dir, ".bench-ok")); statErr != nil {
			return "", err
		}
	}
	return dir, nil
}

func (c *Conn) runShell(ctx context.Context, r *request, command, dir string) (map[string]any, error) {
	if len(command) > 8000 {
		return nil, fmt.Errorf("the command is too long")
	}
	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", command)
	cmd.Dir = dir
	return c.collect(r, cmd)
}

// collect runs a command, streams its output as progress lines, and keeps the tail.
func (c *Conn) collect(r *request, cmd *exec.Cmd) (map[string]any, error) {
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	cmd.Stderr = pw
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	var tail capWriter
	sent := make(chan struct{})
	go func() {
		defer close(sent)
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 64*1024), 256*1024)
		for sc.Scan() {
			line := sc.Text()
			tail.WriteString(line + "\n")
			c.progress(r.ID, line)
		}
		io.Copy(io.Discard, pr)
	}()
	err := cmd.Wait()
	pw.Close()
	<-sent
	code := 0
	if cmd.ProcessState != nil {
		code = cmd.ProcessState.ExitCode()
	}
	if err != nil && code == 0 {
		return nil, err
	}
	return map[string]any{"text": tail.String(), "exit": code}, nil
}

// capWriter keeps the last outputCap bytes written to it.
type capWriter struct {
	mu sync.Mutex
	b  []byte
}

func (w *capWriter) WriteString(s string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.b = append(w.b, s...)
	if len(w.b) > outputCap {
		w.b = w.b[len(w.b)-outputCap:]
	}
}

func (w *capWriter) String() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return string(w.b)
}

// workDir is a scratch folder for exec jobs that were given no tree.
func (c *Conn) workDir() string {
	dir := filepath.Join(c.cfg.Cache, "work")
	os.MkdirAll(dir, 0o755)
	return dir
}

// runRender draws a page with the machine's Chrome and returns the file as base64.
func (c *Conn) runRender(ctx context.Context, r *request) (map[string]any, error) {
	pageURL := str(r.Args["url"])
	if pageURL == "" {
		return nil, fmt.Errorf("render needs a URL")
	}
	format := str(r.Args["format"])
	if format != "pdf" {
		format = "png"
	}
	width := int(num(r.Args["width"]))
	height := int(num(r.Args["height"]))
	if width <= 0 {
		width = 1280
	}
	if height <= 0 {
		height = 900
	}
	chrome := c.cfg.Chrome
	if chrome == "" {
		chrome = findChrome()
	}
	if chrome == "" {
		return nil, fmt.Errorf("no Chrome or Chromium on this machine")
	}
	dir, err := os.MkdirTemp(c.cfg.Cache, "render-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	out := filepath.Join(dir, "page."+format)
	args := []string{"--headless=new", "--disable-gpu", "--hide-scrollbars", "--disable-dev-shm-usage",
		"--user-data-dir=" + filepath.Join(dir, "profile"), "--virtual-time-budget=5000",
		"--window-size=" + strconv.Itoa(width) + "," + strconv.Itoa(height)}
	if format == "pdf" {
		args = append(args, "--no-pdf-header-footer", "--print-to-pdf="+out)
	} else {
		args = append(args, "--screenshot="+out)
	}
	args = append(args, pageURL)
	cmd := exec.CommandContext(ctx, chrome, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("the render failed: %v %.300s", err, stderr.String())
	}
	data, err := os.ReadFile(out)
	if err != nil || len(data) == 0 {
		return nil, fmt.Errorf("the render came back empty")
	}
	return map[string]any{"image": base64.StdEncoding.EncodeToString(data)}, nil
}

func findChrome() string {
	for _, name := range []string{"google-chrome", "chromium", "chromium-browser", "google-chrome-stable"} {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}
	for _, p := range []string{"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
		"/Applications/Chromium.app/Contents/MacOS/Chromium"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func strList(v any) []string {
	out := []string{}
	for _, x := range arrOf(v) {
		if s, ok := x.(string); ok && s != "" {
			out = append(out, s)
		}
	}
	return out
}

func arrOf(v any) []any {
	out, _ := v.([]any)
	return out
}

func num(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	}
	return 0
}
