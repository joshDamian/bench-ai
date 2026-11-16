package bench

// Presenting in a call (Go server only): a session writes slides in Markdown, Bench makes them
// a deck page titled "Bench presents: …", opens it in a new tab, and the session shares that
// tab from the call. Chromium is started with --auto-select-tab-capture-source-by-title=Bench
// presents, so the share starts without its picker, which no tool can click. The deck is moved
// with meeting_slide while the session talks, without leaving the call's tab.

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// DeckTitlePrefix is what a tab's title starts with for the browser to share it on its own.
const DeckTitlePrefix = "Bench presents"

// deckAccess is how the person gets to a deck themselves.
const deckAccess = "it is one self-contained file, and the person can open it on their phone from Home in the Bench app (Ready for you) or from this session's files."

var (
	mdImage  = regexp.MustCompile(`!\[([^\]]*)\]\(([^)\s]+)\)`)
	mdLinkH  = regexp.MustCompile(`\[([^\]]+)\]\(([^)\s]+)\)`)
	mdStrong = regexp.MustCompile(`\*\*([^*]+)\*\*`)
	mdEm     = regexp.MustCompile(`(^|[^*])\*([^*\s][^*]*)\*`)
	mdCode   = regexp.MustCompile("`([^`]+)`")
	mdOL     = regexp.MustCompile(`^\d+[.)]\s+`)
	mdUL     = regexp.MustCompile(`^[-*•]\s+`)
)

// inline is one line of Markdown as HTML: images, links, bold, italics, code.
func inline(s string) string {
	s = html.EscapeString(s)
	s = mdImage.ReplaceAllString(s, `<img alt="$1" src="$2">`)
	s = mdLinkH.ReplaceAllString(s, `<a href="$2">$1</a>`)
	s = mdStrong.ReplaceAllString(s, `<strong>$1</strong>`)
	s = mdEm.ReplaceAllString(s, `$1<em>$2</em>`)
	s = mdCode.ReplaceAllString(s, `<code>$1</code>`)
	return s
}

// slideHTML is one slide's Markdown as HTML: headings, lists, paragraphs, images.
func slideHTML(md string) string {
	var b strings.Builder
	list := ""
	closeList := func() {
		if list != "" {
			b.WriteString("</" + list + ">")
			list = ""
		}
	}
	for _, line := range strings.Split(md, "\n") {
		t := strings.TrimSpace(line)
		switch {
		case t == "":
			closeList()
		case strings.HasPrefix(t, "### "):
			closeList()
			b.WriteString("<h3>" + inline(t[4:]) + "</h3>")
		case strings.HasPrefix(t, "## "):
			closeList()
			b.WriteString("<h2>" + inline(t[3:]) + "</h2>")
		case strings.HasPrefix(t, "# "):
			closeList()
			b.WriteString("<h1>" + inline(t[2:]) + "</h1>")
		case mdUL.MatchString(t) || mdOL.MatchString(t):
			kind := "ul"
			item := mdUL.ReplaceAllString(t, "")
			if mdOL.MatchString(t) {
				kind, item = "ol", mdOL.ReplaceAllString(t, "")
			}
			if list != kind {
				closeList()
				b.WriteString("<" + kind + ">")
				list = kind
			}
			b.WriteString("<li>" + inline(item) + "</li>")
		case strings.HasPrefix(t, "> "):
			closeList()
			b.WriteString("<blockquote>" + inline(t[2:]) + "</blockquote>")
		default:
			closeList()
			b.WriteString("<p>" + inline(t) + "</p>")
		}
	}
	closeList()
	return b.String()
}

// Deck is a slide deck page: slides are the Markdown's parts between lines of "---".
func Deck(title, md string) (page string, slides int) {
	var parts []string
	for _, p := range regexp.MustCompile(`(?m)^\s*---+\s*$`).Split(md, -1) {
		if strings.TrimSpace(p) != "" {
			parts = append(parts, p)
		}
	}
	if len(parts) == 0 {
		parts = []string{"# " + title}
	}
	var b strings.Builder
	for i, p := range parts {
		fmt.Fprintf(&b, `<section class="slide%s">%s</section>`, map[bool]string{true: " on", false: ""}[i == 0], slideHTML(p))
	}
	return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>` + html.EscapeString(DeckTitlePrefix+": "+title) + `</title>
<style>
:root{--bg:#0f1115;--surface:#171a21;--text:#eef0f4;--muted:#9aa1ad;--accent:#7aa2ff;--line:#2a2f3a}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--text);font:400 4vmin/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
.slide{display:none;position:absolute;inset:0;padding:8vmin 10vmin 10vmin;flex-direction:column;justify-content:center;gap:3vmin}
.slide.on{display:flex}
h1{font-size:10vmin;line-height:1.1;margin:0;letter-spacing:-.02em}
h2{font-size:7.5vmin;line-height:1.15;margin:0 0 1vmin;letter-spacing:-.01em}
h3{font-size:4.4vmin;margin:0;color:var(--accent);font-weight:600}
p{margin:0;max-width:70ch;color:var(--text)}
ul,ol{margin:0;padding-left:1.2em;display:grid;gap:2vmin}
li::marker{color:var(--accent)}
strong{color:#fff}em{color:var(--muted)}
code{font:500 .9em ui-monospace,Menlo,monospace;background:var(--surface);border:1px solid var(--line);border-radius:.4em;padding:.05em .35em}
blockquote{margin:0;padding-left:1em;border-left:.3em solid var(--accent);color:var(--muted);font-size:1.1em}
img{max-width:100%;max-height:55vh;border-radius:1vmin;object-fit:contain;align-self:flex-start}
a{color:var(--accent)}
.count{position:fixed;right:3vmin;bottom:2.4vmin;color:var(--muted);font-size:2.4vmin;font-variant-numeric:tabular-nums}
.bar{position:fixed;left:0;bottom:0;height:.5vmin;background:var(--accent);transition:width .3s}
</style></head><body>` + b.String() + `<div class="count"></div><div class="bar"></div>
<script>
const slides=[...document.querySelectorAll('.slide')];let at=0;
function show(i){at=Math.max(0,Math.min(slides.length-1,i));slides.forEach((s,k)=>s.classList.toggle('on',k===at));
document.querySelector('.count').textContent=(at+1)+' / '+slides.length;document.querySelector('.bar').style.width=((at+1)/slides.length*100)+'%';return {at:at+1,of:slides.length}}
window.deck={go:n=>show(n-1),next:()=>show(at+1),prev:()=>show(at-1),where:()=>({at:at+1,of:slides.length})};
addEventListener('keydown',e=>{if(['ArrowRight','PageDown',' '].includes(e.key))show(at+1);if(['ArrowLeft','PageUp'].includes(e.key))show(at-1)});
show(0);
</script></body></html>`, len(parts)
}

// present makes a deck in the session's reports folder and opens it in a new tab.
func (m *Meeting) present(sessionID, title, md string) (string, error) {
	title = strings.TrimSpace(title)
	if title == "" {
		return "", fmt.Errorf("Give the deck a title")
	}
	page, n := Deck(title, md)
	dir := ReportsDir(m.svc.Runtime.AttachmentsDir(sessionID))
	os.MkdirAll(dir, 0o755)
	cwd := ""
	if ss := m.svc.Store.GetSession(sessionID); ss != nil {
		cwd = ss.Str("cwd")
	}
	page = inlineImages(page, cwd)
	slug := strings.Trim(regexp.MustCompile(`[^a-z0-9]+`).ReplaceAllString(strings.ToLower(title), "-"), "-")
	if slug == "" {
		slug = "slides"
	}
	file := filepath.Join(dir, slug+".slides.html")
	if err := os.WriteFile(file, []byte(page), 0o644); err != nil {
		return "", err
	}
	b := m.svc.Runtime.Browser()
	if err := b.Start(); err != nil {
		return "", err
	}
	fileURL := (&url.URL{Scheme: "file", Path: file}).String()
	// A deck already open (and likely being shared) is reused: the new one loads in its tab, so
	// the share carries on and there is never a second "Bench presents" tab to choose between.
	m.mu.Lock()
	old := m.deckTab
	m.mu.Unlock()
	if t := b.tab(old); t != nil {
		if _, err := cdpCall(t.ws, "Page.navigate", map[string]any{"url": fileURL}); err != nil {
			return "", fmt.Errorf("could not load the deck: %v", err)
		}
		b.front(old)
		m.mu.Lock()
		m.presenting = true
		m.mu.Unlock()
		m.svc.Emit("meeting", nil)
		return fmt.Sprintf("The new deck (%d slides) replaced the last one in the same tab, so if it was being shared it still is - nothing to click. Saved as %s; %s Move it with meeting_slide.", n, file, deckAccess), nil
	}
	var tab map[string]any
	if err := b.putJSON("/json/new?"+fileURL, &tab); err != nil {
		return "", fmt.Errorf("could not open the deck: %v", err)
	}
	id, _ := tab["id"].(string)
	b.front(id)
	m.mu.Lock()
	m.deckTab, m.presenting = id, true
	m.mu.Unlock()
	m.svc.Emit("meeting", nil)
	return fmt.Sprintf("The deck (%d slides) is open in a new tab, saved as %s; %s To present it: in the call's tab, click Meet's \"Share screen\" (or \"Present now\") button - the browser shares the deck's tab on its own, with no picker, because its title starts with %q. Stay in the call's tab. Move the deck with meeting_slide (next, prev, a number, or end) and narrate each slide with meeting_say kind \"slide\". When done: meeting_slide end, then click Meet's \"Stop presenting\".", n, file, deckAccess, DeckTitlePrefix), nil
}

// slide moves the deck: "next", "prev", a slide number, or "end" (stops presenting it).
func (m *Meeting) slide(to string) (string, error) {
	m.mu.Lock()
	id := m.deckTab
	m.mu.Unlock()
	if id == "" {
		return "", fmt.Errorf("No deck is open: make one with meeting_present")
	}
	b := m.svc.Runtime.Browser()
	if to == "end" {
		m.mu.Lock()
		m.presenting = false
		m.mu.Unlock()
		b.frontCall()
		m.svc.Emit("meeting", nil)
		return "Presenting ended for speech: lines follow the person's rule again. Click Meet's \"Stop presenting\" in the call's tab; the deck stays in your reports.", nil
	}
	t := b.tab(id)
	if t == nil {
		return "", fmt.Errorf("The deck's tab was closed: open it again with meeting_present")
	}
	ws := t.ws
	// The shared tab in front, so it is drawn and the call sees the new slide.
	b.front(id)
	expr := "deck.next()"
	switch {
	case to == "prev" || to == "back":
		expr = "deck.prev()"
	case regexp.MustCompile(`^\d+$`).MatchString(to):
		expr = "deck.go(" + to + ")"
	}
	// Moved, then two frames waited for: the slide has been drawn, not just chosen.
	r, err := cdpCall(ws, "Runtime.evaluate", map[string]any{
		"expression":    "new Promise(ok=>{const w=" + expr + ";requestAnimationFrame(()=>requestAnimationFrame(()=>ok(JSON.stringify({at:w.at,of:w.of,seen:document.visibilityState}))))})",
		"awaitPromise":  true,
		"returnByValue": true,
	})
	if err != nil {
		return "", err
	}
	v, _ := r["result"].(map[string]any)["value"].(string)
	var w struct {
		At, Of int
		Seen   string
	}
	json.Unmarshal([]byte(v), &w)
	if w.Seen != "visible" {
		return fmt.Sprintf("Moved to slide %d of %d, but the deck's tab is %s, so the call may still show the old slide. Say so if they mention it.", w.At, w.Of, w.Seen), nil
	}
	return fmt.Sprintf("On slide %d of %d, drawn in the shared tab.", w.At, w.Of), nil
}

var imgSrc = regexp.MustCompile(`<img alt="([^"]*)" src="([^"]+)">`)

// inlineImages puts each image into the page itself, so the deck is one file that opens
// anywhere: a web image is fetched, a file is read (relative to the session's folder). One
// that can't be had becomes its alt text.
func inlineImages(page, base string) string {
	client := &http.Client{Timeout: 10 * time.Second}
	return imgSrc.ReplaceAllStringFunc(page, func(tag string) string {
		m := imgSrc.FindStringSubmatch(tag)
		alt, src := m[1], html.UnescapeString(m[2])
		var data []byte
		if strings.HasPrefix(src, "data:") {
			return tag
		}
		if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
			if r, err := client.Get(src); err == nil {
				if r.StatusCode < 400 {
					data, _ = io.ReadAll(io.LimitReader(r.Body, 6<<20))
				}
				r.Body.Close()
			}
		} else {
			p := strings.TrimPrefix(src, "file://")
			if !filepath.IsAbs(p) && base != "" {
				p = filepath.Join(base, p)
			}
			data, _ = os.ReadFile(p)
		}
		if len(data) == 0 || len(data) > 5<<20 {
			return `<p><em>` + html.EscapeString(alt) + `</em></p>`
		}
		mime := http.DetectContentType(data)
		if strings.HasSuffix(strings.ToLower(src), ".svg") {
			mime = "image/svg+xml"
		}
		if !strings.HasPrefix(mime, "image/") {
			return `<p><em>` + html.EscapeString(alt) + `</em></p>`
		}
		return `<img alt="` + html.EscapeString(alt) + `" src="data:` + mime + `;base64,` + base64.StdEncoding.EncodeToString(data) + `">`
	})
}

// tab is one open tab by id, or nil.
func (b *Browser) tab(id string) *browserTab {
	if id == "" {
		return nil
	}
	for _, t := range b.tabs() {
		if t.id == id {
			return &t
		}
	}
	return nil
}

// front brings a tab to the front of the browser's window.
func (b *Browser) front(id string) {
	c := &http.Client{Timeout: 2 * time.Second}
	if r, err := c.Get(b.Endpoint() + "/json/activate/" + id); err == nil {
		r.Body.Close()
	}
}

// frontCall brings the call's tab back to the front, after presenting.
func (b *Browser) frontCall() {
	for _, t := range b.tabs() {
		if strings.Contains(t.url, "meet.google.com/") || strings.Contains(t.url, "zoom.us/") || strings.Contains(t.url, "teams.microsoft.com") {
			b.front(t.id)
			return
		}
	}
}

func presentToolList() []any {
	tool := func(name, desc string, readOnly bool, schema *Obj) *Obj {
		return O("name", name, "description", desc, "inputSchema", schema,
			"annotations", O("readOnlyHint", readOnly, "destructiveHint", false), "execution", O("taskSupport", "forbidden"))
	}
	return []any{
		tool("meeting_present", "Makes slides into a deck and opens it in a new browser tab, ready to share in the call. Write the slides in Markdown, one slide per part, parts separated by a line of ---. Size the deck to the point, not to the data: lead with the answer or the decision and the ranked shortlist, group everything else on one slide, and keep it to about 4 to 8 slides - never one slide per item. Each slide: a heading and at most 4 short points, or one image.", false,
			macSchema(O("title", strProp("The deck's title"), "slides", strProp("The slides in Markdown, separated by lines of ---")), "title", "slides")),
		tool("meeting_slide", "Moves the shared deck without leaving the call's tab: next, prev, a slide number, or end (when done presenting).", false,
			macSchema(O("to", strProp("next, prev, a slide number, or end")), "to")),
	}
}

func presentTools(svc *Service, sessionID string) map[string]func(context.Context, *Obj) *Obj {
	m := svc.Meeting()
	return map[string]func(context.Context, *Obj) *Obj{
		"meeting_present": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			return m.present(sessionID, a.Str("title"), a.Str("slides"))
		}),
		"meeting_slide": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			to := strings.ToLower(strings.TrimSpace(a.Str("to")))
			if to == "" {
				to = "next"
			}
			return m.slide(to)
		}),
	}
}

// grantMedia lets every page use the microphone (Bench's) and camera without asking: the
// browser is Bench's own, and Meet must not stop at a prompt no tool can answer. Chromium drops
// a grant when the connection that made it closes, so one connection is kept open for as long
// as the browser runs.
func (b *Browser) grantMedia() {
	b.mu.Lock()
	held := b.grant != nil
	b.mu.Unlock()
	if held {
		return
	}
	var v map[string]any
	if b.getJSON("/json/version", &v) != nil {
		return
	}
	ws, _ := v["webSocketDebuggerUrl"].(string)
	if ws == "" {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	c, _, err := websocket.Dial(ctx, ws, nil)
	if err != nil {
		cancel()
		return
	}
	var wmu sync.Mutex
	next := 0
	send := func(sessionID, method string, params map[string]any) error {
		wmu.Lock()
		defer wmu.Unlock()
		next++
		m := map[string]any{"id": next, "method": method, "params": params}
		if sessionID != "" {
			m["sessionId"] = sessionID
		}
		msg, _ := json.Marshal(m)
		return c.Write(ctx, websocket.MessageText, msg)
	}
	if send("", "Browser.grantPermissions", map[string]any{"permissions": []any{"audioCapture", "videoCapture"}}) != nil {
		cancel()
		return
	}
	// Every page gets the call-audio hook (callAudioHook) before its own scripts run, so a
	// call's audio connections can be checked: new pages wait for it, open ones get it for
	// their next load.
	send("", "Target.setAutoAttach", map[string]any{"autoAttach": true, "waitForDebuggerOnStart": true, "flatten": true})
	b.mu.Lock()
	b.grant = cancel
	b.mu.Unlock()
	go func() {
		// Held until the browser goes; then the next start grants again.
		for {
			_, data, err := c.Read(ctx)
			if err != nil {
				break
			}
			var ev struct {
				Method string `json:"method"`
				Params struct {
					SessionID  string `json:"sessionId"`
					TargetInfo struct {
						Type string `json:"type"`
					} `json:"targetInfo"`
					Waiting bool `json:"waitingForDebugger"`
				} `json:"params"`
			}
			if json.Unmarshal(data, &ev) != nil || ev.Method != "Target.attachedToTarget" {
				continue
			}
			sid := ev.Params.SessionID
			if ev.Params.TargetInfo.Type == "page" {
				send(sid, "Page.addScriptToEvaluateOnNewDocument", map[string]any{"source": callAudioHook})
				send(sid, "Runtime.evaluate", map[string]any{"expression": callAudioHook})
			}
			if ev.Params.Waiting {
				send(sid, "Runtime.runIfWaitingForDebugger", map[string]any{})
			}
		}
		c.Close(websocket.StatusNormalClosure, "")
		b.mu.Lock()
		b.grant = nil
		b.mu.Unlock()
		cancel()
	}()
}
