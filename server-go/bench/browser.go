package bench

// The browser: browser.mjs. A Chromium on this server that sessions use through the
// Playwright MCP server, with a profile of its own so sign-ins last; the person takes it
// over from their phone through a stream of frames and input sent back.

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
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

const BrowserNote = "# The browser\n\nYou have a browser on this server (the \"browser\" tools), signed in to the sites the person signed in to. Use it for pages that need a sign-in or that plain fetching cannot read. When a site needs the person - to sign in, or to pass a CAPTCHA or a check - do not try to get around it: ask them with AskUserQuestion, header exactly \"Take over\", saying which site and why, with the options \"Done\" and \"Skip this site\". They take over the browser from their phone and answer Done when they are through.\n\nTabs: the browser is shared and may already have pages in use - a call, a sign-in, a page another step needs. Never navigate away from a tab you or the person may still need: open other sites in a new tab (browser_tabs, action new), work there, and switch back (browser_tabs, action select) when you are done. Above all, never leave a call's tab while you are in the call."

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
	// grant ends the connection that holds the microphone permission (grantMedia).
	grant context.CancelFunc
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

// putJSON is getJSON with PUT, which newer Chromium wants for /json/new.
func (b *Browser) putJSON(p string, out any) error {
	req, err := http.NewRequest("PUT", b.Endpoint()+p, nil)
	if err != nil {
		return err
	}
	r, err := (&http.Client{Timeout: 2 * time.Second}).Do(req)
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
		b.grantMedia()
		return nil
	}
	defer b.grantMedia()
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
		// A shared deck sits behind the call's tab: background tabs must keep drawing, or the
		// call shows a frozen slide.
		"--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
		"--window-size=1280,900", "--lang=en-US"}
	display := b.screen()
	// Calls: Chromium plays into Bench's speaker and hears Bench's microphone, and does not
	// ask before using them. Without PulseAudio it has no sound, as before.
	audio := AudioSetup() == nil
	if audio {
		// The microphone is allowed through the browser's permissions (grantMedia), not
		// --use-fake-ui-for-media-stream, which would also answer screen sharing with the whole
		// screen, and that cannot be captured under Xvfb. A tab titled "Bench presents…" (a deck)
		// is shared without the picker no tool can click.
		args = append(args, "--auto-select-tab-capture-source-by-title="+DeckTitlePrefix, "--autoplay-policy=no-user-gesture-required")
	}
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
	if b.grant != nil {
		b.grant()
	}
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
	// An X server another Bench left running on this display is used as it is; a socket file
	// with nothing behind it (an Xvfb that died) is cleared, or Chromium cannot start.
	sock := fmt.Sprintf("/tmp/.X11-unix/X%d", n)
	if c, err := net.DialTimeout("unix", sock, time.Second); err == nil {
		c.Close()
		b.display = display
		return display
	}
	os.Remove(sock)
	os.Remove(fmt.Sprintf("/tmp/.X%d-lock", n))
	cmd := exec.Command(bin, display, "-screen", "0", "1280x900x24", "-nolisten", "tcp")
	if cmd.Start() != nil {
		return ""
	}
	go cmd.Wait()
	b.xvfb, b.display = cmd, display
	for i := 0; i < 30; i++ {
		if c, err := net.DialTimeout("unix", sock, 200*time.Millisecond); err == nil {
			c.Close()
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
// browserTab is one page in the browser, as /json lists it.
type browserTab struct{ id, ws, url, title string }

func (b *Browser) tabs() []browserTab {
	var list []map[string]any
	if b.getJSON("/json", &list) != nil {
		return nil
	}
	var out []browserTab
	for _, t := range list {
		if t["type"] != "page" {
			continue
		}
		id, _ := t["id"].(string)
		ws, _ := t["webSocketDebuggerUrl"].(string)
		u, _ := t["url"].(string)
		title, _ := t["title"].(string)
		if id != "" && ws != "" {
			out = append(out, browserTab{id, ws, u, title})
		}
	}
	return out
}

// viewPage is the tab the take-over view shows: its own DevTools connection.
type viewPage struct {
	id     string
	conn   *websocket.Conn
	cancel context.CancelFunc
	mu     sync.Mutex
	next   int
}

func (p *viewPage) send(ctx context.Context, method string, params map[string]any) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if params == nil {
		params = map[string]any{}
	}
	p.next++
	msg, _ := json.Marshal(map[string]any{"id": p.next, "method": method, "params": params})
	p.conn.Write(ctx, websocket.MessageText, msg)
}

// close puts the tab back as the sessions use it, and lets go of it.
func (p *viewPage) close() {
	cctx, ccancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer ccancel()
	p.send(cctx, "Page.stopScreencast", nil)
	p.send(cctx, "Emulation.clearDeviceMetricsOverride", nil)
	p.cancel()
	p.conn.Close(websocket.StatusNormalClosure, "")
}

// View streams the browser to a person and their input back: the take-over view. It shows
// one tab at a time and follows the browser: a tab or popup that opens (a sign-in window, a
// page the session opened) is shown as it opens, and when the shown one closes it goes back
// to another. The tabs are listed so the person can switch.
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
	client.SetReadLimit(1 << 20)
	var cmu sync.Mutex
	toClient := func(v any) {
		cmu.Lock()
		defer cmu.Unlock()
		msg, _ := json.Marshal(v)
		client.Write(ctx, websocket.MessageText, msg)
	}
	var mu sync.Mutex // guards cur, width, height, vw, vh
	var cur *viewPage
	width, height := 1280.0, 900.0
	vw, vh := 0.0, 0.0
	current := func() *viewPage { mu.Lock(); defer mu.Unlock(); return cur }

	attach := func(t browserTab) bool {
		pctx, pcancel := context.WithCancel(ctx)
		conn, _, err := websocket.Dial(pctx, t.ws, nil)
		if err != nil {
			pcancel()
			return false
		}
		conn.SetReadLimit(32 << 20)
		p := &viewPage{id: t.id, conn: conn, cancel: pcancel, next: 100}
		mu.Lock()
		old := cur
		cur = p
		w, h := vw, vh
		mu.Unlock()
		if old != nil {
			old.close()
		}
		// Bring it to the front, so what the person sees is what the browser shows.
		b.getJSON("/json/activate/"+t.id, nil)
		p.send(pctx, "Page.enable", nil)
		if w > 0 {
			p.send(pctx, "Emulation.setDeviceMetricsOverride", map[string]any{"width": int(w), "height": int(h), "deviceScaleFactor": 2, "mobile": false})
		}
		p.send(pctx, "Page.startScreencast", map[string]any{"format": "jpeg", "quality": 85, "maxWidth": 1600, "maxHeight": 2400, "everyNthFrame": 1})
		toClient(map[string]any{"type": "url", "url": t.url, "title": t.title})
		go func() {
			for {
				_, data, err := conn.Read(pctx)
				if err != nil {
					return
				}
				var m map[string]any
				if json.Unmarshal(data, &m) != nil {
					continue
				}
				pm, _ := m["params"].(map[string]any)
				switch m["method"] {
				case "Page.screencastFrame":
					meta, _ := pm["metadata"].(map[string]any)
					fw, _ := meta["deviceWidth"].(float64)
					fh, _ := meta["deviceHeight"].(float64)
					p.send(pctx, "Page.screencastFrameAck", map[string]any{"sessionId": pm["sessionId"]})
					if current() != p {
						continue
					}
					mu.Lock()
					width, height = fw, fh
					mu.Unlock()
					toClient(map[string]any{"type": "frame", "data": pm["data"], "width": fw, "height": fh})
				case "Page.frameNavigated":
					if f, ok := pm["frame"].(map[string]any); ok && f["parentId"] == nil && current() == p {
						toClient(map[string]any{"type": "url", "url": f["url"]})
					}
				}
			}
		}()
		return true
	}
	defer func() {
		if p := current(); p != nil {
			p.close()
		}
	}()

	tabs := b.tabs()
	if len(tabs) == 0 || !attach(tabs[0]) {
		client.Close(websocket.StatusInternalError, "The browser has no page open")
		return
	}
	// Follow the browser's tabs: list them for the person, show a new one as it opens, and
	// go back to another when the shown one closes.
	go func() {
		seen := map[string]bool{}
		for _, t := range tabs {
			seen[t.id] = true
		}
		last := ""
		tick := time.NewTicker(800 * time.Millisecond)
		defer tick.Stop()
		for {
			list := b.tabs()
			shown := current()
			var opened *browserTab
			alive := false
			for i, t := range list {
				if !seen[t.id] {
					seen[t.id] = true
					opened = &list[i]
				}
				alive = alive || (shown != nil && t.id == shown.id)
			}
			switch {
			case opened != nil:
				attach(*opened)
			case !alive && len(list) > 0:
				attach(list[0])
			}
			shown = current()
			items := []any{}
			for _, t := range list {
				items = append(items, map[string]any{"id": t.id, "title": t.title, "url": t.url, "current": shown != nil && t.id == shown.id})
			}
			if sig := string(ToJSON(items)); sig != last {
				last = sig
				toClient(map[string]any{"type": "tabs", "tabs": items})
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
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
		p := current()
		mu.Lock()
		w, h := width, height
		mu.Unlock()
		send := func(method string, params map[string]any) { p.send(ctx, method, params) }
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
			nw, _ := m["width"].(float64)
			nh, _ := m["height"].(float64)
			if nw >= 200 && nw <= 2000 && nh >= 200 && nh <= 3000 {
				mu.Lock()
				vw, vh = nw, nh
				mu.Unlock()
				send("Emulation.setDeviceMetricsOverride", map[string]any{"width": int(nw), "height": int(nh), "deviceScaleFactor": 2, "mobile": false})
			}
		case "go":
			if u := webAddress(fmt.Sprint(m["url"])); u != "" {
				send("Page.navigate", map[string]any{"url": u})
			}
		case "tab":
			id := fmt.Sprint(m["id"])
			for _, t := range b.tabs() {
				if t.id == id && t.id != p.id {
					attach(t)
				}
			}
		case "new_tab":
			// A new tab, for the person to go somewhere without leaving the session's page.
			var t map[string]any
			if b.putJSON("/json/new?about:blank", &t) == nil {
				// The follower shows it as it appears.
				_ = t
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
