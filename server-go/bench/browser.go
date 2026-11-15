package bench

// The browser: browser.mjs. A Chromium on this server that sessions use through the
// Playwright MCP server, with a profile of its own so sign-ins last; the person takes it
// over from their phone through a stream of frames and input sent back.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const PlaywrightMCP = "@playwright/mcp@0.0.84"

const BrowserNote = "# The browser\n\nYou have a browser on this server (the \"browser\" tools), signed in to the sites the person signed in to. Use it for pages that need a sign-in or that plain fetching cannot read. When a site needs the person - to sign in, or to pass a CAPTCHA or a check - do not try to get around it: ask them with AskUserQuestion, header exactly \"Take over\", saying which site and why, with the options \"Done\" and \"Skip this site\". They take over the browser from their phone and answer Done when they are through."

func ChromiumPath() string {
	if p := os.Getenv("BENCH_CHROMIUM"); p != "" {
		if _, err := os.Stat(p); err == nil {
			return p
		}
		return ""
	}
	for _, n := range []string{"chromium", "chromium-browser", "google-chrome", "google-chrome-stable"} {
		if p, err := exec.LookPath(n); err == nil {
			return p
		}
	}
	cache := os.Getenv("PLAYWRIGHT_BROWSERS_PATH")
	if cache == "" {
		cache = filepath.Join(os.Getenv("HOME"), ".cache", "ms-playwright")
	}
	entries, _ := os.ReadDir(cache)
	var dirs []string
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "chromium-") {
			if _, err := strconv.Atoi(strings.TrimPrefix(e.Name(), "chromium-")); err == nil {
				dirs = append(dirs, e.Name())
			}
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(dirs)))
	for _, d := range dirs {
		for _, sub := range []string{"chrome-linux64/chrome", "chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium"} {
			p := filepath.Join(cache, d, sub)
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	return ""
}

func SiteOf(d string) string {
	var parts []string
	for _, p := range strings.Split(strings.TrimPrefix(d, "."), ".") {
		if p != "" {
			parts = append(parts, p)
		}
	}
	if len(parts) <= 2 {
		return strings.Join(parts, ".")
	}
	return strings.Join(parts[len(parts)-2:], ".")
}

type Browser struct {
	Profile string
	Port    int
	mu      sync.Mutex
	cmd     *exec.Cmd
	// noSandbox: the sandbox failed here once, so Chromium starts without it from then on.
	noSandbox bool
	// xvfb and display: the invisible screen a windowed Chromium draws on.
	xvfb    *exec.Cmd
	display string
}

func NewBrowser(dataDir string) *Browser {
	port := 9338
	if n, err := strconv.Atoi(os.Getenv("BENCH_BROWSER_PORT")); err == nil && n > 0 {
		port = n
	}
	return &Browser{Profile: filepath.Join(dataDir, "browser"), Port: port}
}

func (b *Browser) Endpoint() string { return "http://127.0.0.1:" + strconv.Itoa(b.Port) }

func (b *Browser) getJSON(p string, out any) error {
	c := &http.Client{Timeout: 2 * time.Second}
	r, err := c.Get(b.Endpoint() + p)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(out)
}

func (b *Browser) Running() bool {
	var v map[string]any
	return b.getJSON("/json/version", &v) == nil
}

func (b *Browser) Ensure() bool { return b.Start() == nil }

// Start starts Chromium if it is installed and not already up, and says why when it cannot:
// not installed, or started and exited (most often missing system libraries on a fresh
// server), with the end of what Chromium printed.
func (b *Browser) Start() error {
	if b.Running() {
		return nil
	}
	bin := ChromiumPath()
	if bin == "" {
		return fmt.Errorf("Chromium isn't installed on the server. Install it there with: npx playwright install --with-deps chromium")
	}
	os.MkdirAll(b.Profile, 0o755)
	// Chromium will not start its sandbox as root, and newer Ubuntu (23.10 on) blocks the
	// user namespaces it needs as a normal user. A browser that only Bench drives runs
	// without it then, the usual way on a server.
	b.mu.Lock()
	noSandbox := b.noSandbox || os.Getuid() == 0
	b.mu.Unlock()
	err := b.launch(bin, noSandbox)
	if err != nil && !noSandbox && sandboxFailed.MatchString(err.Error()) {
		b.mu.Lock()
		b.noSandbox = true
		b.mu.Unlock()
		err = b.launch(bin, true)
	}
	return err
}

var sandboxFailed = regexp.MustCompile(`(?i)sandbox`)

func (b *Browser) launch(bin string, noSandbox bool) error {
	// Sites behind bot checks (Cloudflare, LinkedIn) turn away a browser that looks automated:
	// so no automation flag, and, with Xvfb installed, a normal windowed Chromium on an
	// invisible screen rather than a headless one. Without Xvfb it runs headless with an
	// ordinary user agent.
	args := []string{"--remote-debugging-port=" + strconv.Itoa(b.Port), "--remote-debugging-address=127.0.0.1", "--user-data-dir=" + b.Profile,
		"--no-first-run", "--no-default-browser-check", "--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled",
		"--window-size=1280,900", "--lang=en-US"}
	display := b.screen()
	if display == "" {
		args = append(args, "--headless=new")
		if ua := plainUserAgent(bin); ua != "" {
			args = append(args, "--user-agent="+ua)
		}
	}
	args = append(args, "about:blank")
	if noSandbox {
		args = append([]string{"--no-sandbox"}, args...)
	}
	b.mu.Lock()
	cmd := exec.Command(bin, args...)
	if display != "" {
		cmd.Env = append(os.Environ(), "DISPLAY="+display)
	}
	out := &tailBuffer{}
	cmd.Stdout = out
	cmd.Stderr = out
	exited := make(chan struct{})
	if err := cmd.Start(); err != nil {
		b.mu.Unlock()
		return fmt.Errorf("Chromium couldn't start: %v", err)
	}
	b.cmd = cmd
	go func() { cmd.Wait(); close(exited) }()
	b.mu.Unlock()
	// A first start on a small server, making the profile, can take a while.
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		if b.Running() {
			return nil
		}
		select {
		case <-exited:
			msg := out.String()
			if m := missingLib.FindStringSubmatch(msg); m != nil {
				return fmt.Errorf("Chromium couldn't start: the server is missing %s. Install what it needs with: sudo npx playwright install-deps chromium", m[1])
			}
			return fmt.Errorf("Chromium couldn't start: %s", crashLine(msg))
		case <-time.After(150 * time.Millisecond):
		}
	}
	return fmt.Errorf("Chromium took too long to start; try again")
}

var missingLib = regexp.MustCompile(`error while loading shared libraries: ([^:\s]+)`)

// crashLine is the line of a crash that says why: Chromium's FATAL or ERROR line, else the
// last line that is not the stack trace.
func crashLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	for _, l := range lines {
		if strings.Contains(l, ":FATAL:") || strings.Contains(l, "FATAL:") {
			return trimLine(l)
		}
	}
	for _, l := range lines {
		if strings.Contains(l, ":ERROR:") {
			return trimLine(l)
		}
	}
	for i := len(lines) - 1; i >= 0; i-- {
		l := strings.TrimSpace(lines[i])
		if l != "" && !strings.Contains(l, "stack trace") && !strings.HasPrefix(l, "#") {
			return trimLine(l)
		}
	}
	return "it exited without saying why"
}

// trimLine drops Chromium's "[pid:tid:time:FATAL:file.cc(12)] " prefix and long tails.
func trimLine(l string) string {
	l = strings.TrimSpace(l)
	if i := strings.Index(l, "] "); strings.HasPrefix(l, "[") && i > 0 {
		l = l[i+2:]
	}
	if len(l) > 300 {
		l = l[:300]
	}
	return l
}

// tailBuffer keeps the last few KB a process printed.
type tailBuffer struct {
	mu  sync.Mutex
	buf []byte
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.buf = append(t.buf, p...)
	if len(t.buf) > 8192 {
		t.buf = t.buf[len(t.buf)-4096:]
	}
	return len(p), nil
}

func (t *tailBuffer) String() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return string(t.buf)
}

func (b *Browser) Stop() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cmd != nil && b.cmd.Process != nil {
		b.cmd.Process.Kill()
	}
	b.cmd = nil
	if b.xvfb != nil && b.xvfb.Process != nil {
		b.xvfb.Process.Kill()
	}
	b.xvfb = nil
}

// screen is an invisible X screen for a windowed Chromium: one Xvfb of Bench's own, started
// on first use, or "" when Xvfb is not installed (then Chromium runs headless).
func (b *Browser) screen() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.xvfb != nil && b.xvfb.ProcessState == nil {
		return b.display
	}
	bin, err := exec.LookPath("Xvfb")
	if err != nil {
		return ""
	}
	n := 90 + b.Port%9
	display := fmt.Sprintf(":%d", n)
	// An X server another Bench left running on this display is used as it is.
	if _, err := os.Stat(fmt.Sprintf("/tmp/.X11-unix/X%d", n)); err == nil {
		b.display = display
		return display
	}
	cmd := exec.Command(bin, display, "-screen", "0", "1280x900x24", "-nolisten", "tcp")
	if cmd.Start() != nil {
		return ""
	}
	go cmd.Wait()
	b.xvfb, b.display = cmd, display
	for i := 0; i < 30; i++ {
		if _, err := os.Stat(fmt.Sprintf("/tmp/.X11-unix/X%d", n)); err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	return display
}

// plainUserAgent is a desktop Chrome user agent for this Chromium's version, without the
// "HeadlessChrome" that headless mode puts in its own.
func plainUserAgent(bin string) string {
	out, err := exec.Command(bin, "--version").Output()
	if err != nil {
		return ""
	}
	v := regexp.MustCompile(`(\d+)\.\d+\.\d+\.\d+`).FindStringSubmatch(string(out))
	if v == nil {
		return ""
	}
	return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + v[1] + ".0.0.0 Safari/537.36"
}

// MCP is the server sessions use to drive the browser, when it is installed.
func (b *Browser) MCP() *Obj {
	if ChromiumPath() == "" {
		return nil
	}
	return O("type", "stdio", "command", "npx", "args", []any{"-y", PlaywrightMCP, "--cdp-endpoint", b.Endpoint()})
}

func (b *Browser) pageSocket() string {
	var list []map[string]any
	if b.getJSON("/json", &list) != nil {
		return ""
	}
	for _, t := range list {
		if t["type"] == "page" {
			s, _ := t["webSocketDebuggerUrl"].(string)
			return s
		}
	}
	return ""
}

func cdpCall(wsURL, method string, params any) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		return nil, err
	}
	defer c.Close(websocket.StatusNormalClosure, "")
	c.SetReadLimit(32 << 20)
	if params == nil {
		params = map[string]any{}
	}
	msg, _ := json.Marshal(map[string]any{"id": 1, "method": method, "params": params})
	if err := c.Write(ctx, websocket.MessageText, msg); err != nil {
		return nil, err
	}
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			return nil, err
		}
		var m map[string]any
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		if id, _ := m["id"].(float64); id == 1 {
			if e, ok := m["error"].(map[string]any); ok {
				return nil, fmt.Errorf("%v", e["message"])
			}
			r, _ := m["result"].(map[string]any)
			return r, nil
		}
	}
}

func (b *Browser) cookies() []map[string]any {
	ws := b.pageSocket()
	if ws == "" {
		return nil
	}
	r, err := cdpCall(ws, "Network.getAllCookies", nil)
	if err != nil {
		return nil
	}
	var out []map[string]any
	for _, c := range arr(r["cookies"]) {
		if m, ok := c.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func (b *Browser) Sites() []any {
	seen := map[string]bool{}
	var names []string
	for _, c := range b.cookies() {
		s := SiteOf(fmt.Sprint(c["domain"]))
		if !seen[s] {
			seen[s] = true
			names = append(names, s)
		}
	}
	sort.Strings(names)
	out := []any{}
	for _, n := range names {
		out = append(out, n)
	}
	return out
}

func (b *Browser) SignOut(site string) {
	if !b.Running() {
		return
	}
	ws := b.pageSocket()
	for _, c := range b.cookies() {
		if SiteOf(fmt.Sprint(c["domain"])) == site {
			cdpCall(ws, "Network.deleteCookies", map[string]any{"name": c["name"], "domain": c["domain"], "path": c["path"]})
		}
	}
}

func (b *Browser) Status() *Obj {
	installed := ChromiumPath() != ""
	running := installed && b.Running()
	sites := []any{}
	if running {
		sites = b.Sites()
	}
	return O("installed", installed, "running", running, "sites", sites)
}

var browserKeys = map[string]map[string]any{
	"Enter":     {"key": "Enter", "code": "Enter", "windowsVirtualKeyCode": 13, "text": "\r"},
	"Backspace": {"key": "Backspace", "code": "Backspace", "windowsVirtualKeyCode": 8},
	"Tab":       {"key": "Tab", "code": "Tab", "windowsVirtualKeyCode": 9},
}

// View streams the browser's page to the person and their input back: the take-over view.
func (b *Browser) View(client *websocket.Conn) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := b.Start(); err != nil {
		// The reason in full as a message: a close reason is cut at 123 bytes.
		wctx, wcancel := context.WithTimeout(ctx, 5*time.Second)
		client.Write(wctx, websocket.MessageText, ToJSON(O("type", "error", "message", err.Error())))
		wcancel()
		client.Close(websocket.StatusInternalError, "The browser couldn't start")
		return
	}
	wsURL := b.pageSocket()
	if wsURL == "" {
		client.Close(websocket.StatusInternalError, "The browser has no page open")
		return
	}
	page, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		client.Close(websocket.StatusInternalError, "The browser could not be reached")
		return
	}
	defer page.Close(websocket.StatusNormalClosure, "")
	page.SetReadLimit(32 << 20)
	client.SetReadLimit(1 << 20)
	var wmu sync.Mutex
	next := 100
	send := func(method string, params map[string]any) {
		wmu.Lock()
		defer wmu.Unlock()
		if params == nil {
			params = map[string]any{}
		}
		msg, _ := json.Marshal(map[string]any{"id": next, "method": method, "params": params})
		next++
		page.Write(ctx, websocket.MessageText, msg)
	}
	var cmu sync.Mutex
	toClient := func(v any) {
		cmu.Lock()
		defer cmu.Unlock()
		msg, _ := json.Marshal(v)
		client.Write(ctx, websocket.MessageText, msg)
	}
	var mmu sync.Mutex
	width, height := 1280.0, 900.0
	send("Page.enable", nil)
	send("Page.startScreencast", map[string]any{"format": "jpeg", "quality": 85, "maxWidth": 1600, "maxHeight": 2400, "everyNthFrame": 1})
	// While the person is in control, the page is laid out at their phone's size; after,
	// it goes back to the size sessions use.
	defer func() {
		cctx, ccancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer ccancel()
		msg, _ := json.Marshal(map[string]any{"id": 99, "method": "Emulation.clearDeviceMetricsOverride", "params": map[string]any{}})
		page.Write(cctx, websocket.MessageText, msg)
	}()
	var list []map[string]any
	if b.getJSON("/json", &list) == nil {
		for _, t := range list {
			if t["webSocketDebuggerUrl"] == wsURL {
				toClient(map[string]any{"type": "url", "url": t["url"], "title": t["title"]})
			}
		}
	}
	go func() {
		defer cancel()
		for {
			_, data, err := page.Read(ctx)
			if err != nil {
				return
			}
			var m map[string]any
			if json.Unmarshal(data, &m) != nil {
				continue
			}
			p, _ := m["params"].(map[string]any)
			switch m["method"] {
			case "Page.screencastFrame":
				meta, _ := p["metadata"].(map[string]any)
				mmu.Lock()
				width, _ = meta["deviceWidth"].(float64)
				height, _ = meta["deviceHeight"].(float64)
				w, h := width, height
				mmu.Unlock()
				send("Page.screencastFrameAck", map[string]any{"sessionId": p["sessionId"]})
				toClient(map[string]any{"type": "frame", "data": p["data"], "width": w, "height": h})
			case "Page.frameNavigated":
				if f, ok := p["frame"].(map[string]any); ok && f["parentId"] == nil {
					toClient(map[string]any{"type": "url", "url": f["url"]})
				}
			}
		}
	}()
	for {
		_, data, err := client.Read(ctx)
		if err != nil {
			return
		}
		var m map[string]any
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		mmu.Lock()
		w, h := width, height
		mmu.Unlock()
		switch m["type"] {
		case "tap":
			x, _ := m["x"].(float64)
			y, _ := m["y"].(float64)
			for _, t := range []string{"mousePressed", "mouseReleased"} {
				send("Input.dispatchMouseEvent", map[string]any{"type": t, "x": x * w, "y": y * h, "button": "left", "clickCount": 1})
			}
		case "scroll":
			dy, _ := m["dy"].(float64)
			send("Input.dispatchMouseEvent", map[string]any{"type": "mouseWheel", "x": w / 2, "y": h / 2, "deltaX": 0, "deltaY": dy * h})
		case "text":
			if t, ok := m["text"].(string); ok {
				if len(t) > 2000 {
					t = t[:2000]
				}
				send("Input.insertText", map[string]any{"text": t})
			}
		case "key":
			if k, ok := browserKeys[fmt.Sprint(m["key"])]; ok {
				for _, t := range []string{"keyDown", "keyUp"} {
					p := map[string]any{"type": t}
					for kk, v := range k {
						p[kk] = v
					}
					send("Input.dispatchKeyEvent", p)
				}
			}
		case "back":
			send("Runtime.evaluate", map[string]any{"expression": "history.back()"})
		case "size":
			// The phone's view, in its CSS pixels: pages lay out at its width. Not as a phone
			// (mobile: false): a desktop browser claiming to be a phone looks like a bot.
			vw, _ := m["width"].(float64)
			vh, _ := m["height"].(float64)
			if vw >= 200 && vw <= 2000 && vh >= 200 && vh <= 3000 {
				send("Emulation.setDeviceMetricsOverride", map[string]any{"width": int(vw), "height": int(vh), "deviceScaleFactor": 2, "mobile": false})
			}
		case "go":
			if u := webAddress(fmt.Sprint(m["url"])); u != "" {
				send("Page.navigate", map[string]any{"url": u})
			}
		}
	}
}

// webAddress turns what the person typed into a page to open: "linkedin.com" is
// https://linkedin.com, and words with no dot in them are a search.
func webAddress(s string) string {
	s = strings.TrimSpace(s)
	switch {
	case s == "":
		return ""
	case strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://"):
		return s
	case !strings.ContainsAny(s, " ") && strings.Contains(s, "."):
		return "https://" + s
	}
	return "https://duckduckgo.com/?q=" + url.QueryEscape(s)
}

func (r *Runtime) Browser() *Browser {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.browser == nil {
		r.browser = NewBrowser(r.Store.Dir)
	}
	return r.browser
}
