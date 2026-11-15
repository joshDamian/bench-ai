package bench

// The browser: browser.mjs. A Chromium on this server that sessions use through the
// Playwright MCP server, with a profile of its own so sign-ins last; the person takes it
// over from their phone through a stream of frames and input sent back.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
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

func (b *Browser) Ensure() bool {
	if b.Running() {
		return true
	}
	bin := ChromiumPath()
	if bin == "" {
		return false
	}
	os.MkdirAll(b.Profile, 0o755)
	b.mu.Lock()
	args := []string{"--headless=new", "--remote-debugging-port=" + strconv.Itoa(b.Port), "--remote-debugging-address=127.0.0.1", "--user-data-dir=" + b.Profile, "--no-first-run", "--no-default-browser-check", "--window-size=1280,900", "about:blank"}
	// Chromium will not start its sandbox as root, which a server often is.
	if os.Getuid() == 0 {
		args = append([]string{"--no-sandbox"}, args...)
	}
	cmd := exec.Command(bin, args...)
	if cmd.Start() == nil {
		b.cmd = cmd
		go cmd.Wait()
	}
	b.mu.Unlock()
	for i := 0; i < 50; i++ {
		if b.Running() {
			return true
		}
		time.Sleep(100 * time.Millisecond)
	}
	return false
}

func (b *Browser) Stop() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.cmd != nil && b.cmd.Process != nil {
		b.cmd.Process.Kill()
	}
	b.cmd = nil
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
	if !b.Ensure() {
		client.Close(websocket.StatusInternalError, "The browser is not installed on this server")
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
	send("Page.startScreencast", map[string]any{"format": "jpeg", "quality": 60, "maxWidth": 1280, "maxHeight": 1600, "everyNthFrame": 1})
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
		}
	}
}

func (r *Runtime) Browser() *Browser {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.browser == nil {
		r.browser = NewBrowser(r.Store.Dir)
	}
	return r.browser
}
