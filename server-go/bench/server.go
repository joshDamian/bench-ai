package bench

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
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
	"github.com/klauspost/compress/gzip"
)

// The HTTP and WebSocket front (src/server.mjs): every route, the auth rules, the event
// hub and the terminals, answering exactly as the Node server does.

type Server struct {
	Svc        *Service
	Terms      *Terminals
	Root       string
	Port       int
	Host       string
	Version    string
	started    time.Time
	webDir     string
	modulesDir string
	release    *Obj
	supervised any
	envToken   string
	vapid      *VAPID
	routes     []route
	hubMu      sync.Mutex
	clients    map[*client]bool
	deltaMu    sync.Mutex
	deltas     map[string]*pendingDelta
	pairMu     sync.Mutex
	pairFails  map[string]*pairFail
	pathMu     sync.Mutex
	pathCache  map[string]pathEntry
	reachMu    sync.Mutex
	reachCache map[int]reachEntry
	http       *http.Server
	closing    sync.Once
	Done       chan struct{}
}

type client struct {
	ws      *websocket.Conn
	mu      sync.Mutex
	watch   map[string]bool
	alive   bool
	out     chan []byte
	closed  chan struct{}
	queued  int
	closeMu sync.Once
}

type pendingDelta struct {
	text     strings.Builder
	thinking bool
	timer    *time.Timer
}

type pairFail struct {
	n  int
	at time.Time
}

type pathEntry struct {
	at   time.Time
	list []string
}

type reachEntry struct {
	at   time.Time
	addr string
}

type ctxt struct {
	w      http.ResponseWriter
	r      *http.Request
	u      *url.URL
	params []string
	body   *Obj
	auth   *authInfo
	sent   bool
}

type authInfo struct {
	local  bool
	device *Obj
}

type route struct {
	method string
	re     *regexp.Regexp
	fn     func(c *ctxt) (any, error)
}

var paramRe = regexp.MustCompile(`:[a-z]+`)

func (s *Server) route(method, pattern string, fn func(c *ctxt) (any, error)) {
	s.routes = append(s.routes, route{method, regexp.MustCompile("^" + paramRe.ReplaceAllString(pattern, "([^/]+)") + "$"), fn})
}

func NewServer(svc *Service, root string) *Server {
	port := 4317
	if v, ok := os.LookupEnv("PORT"); ok {
		if n, err := strconv.Atoi(v); err == nil {
			port = n
		}
	}
	host := Env("HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	reap := time.Duration(0)
	if v, err := strconv.Atoi(Env("TERMINAL_REAP_MS")); err == nil && v > 0 {
		reap = time.Duration(v) * time.Millisecond
	}
	s := &Server{Svc: svc, Terms: NewTerminals(reap), Root: root, Port: port, Host: host, started: time.Now(),
		webDir: filepath.Join(AppRoot, "web"), modulesDir: filepath.Join(AppRoot, "node_modules"), clients: map[*client]bool{}, deltas: map[string]*pendingDelta{},
		pairFails: map[string]*pairFail{}, pathCache: map[string]pathEntry{}, reachCache: map[int]reachEntry{}, Done: make(chan struct{})}
	s.Version = "2.0.0"
	if pkg, ok := ParseJSON(readFile(AppRoot, "package.json")).(*Obj); ok && pkg.Str("version") != "" {
		s.Version = pkg.Str("version")
	}
	s.release = ReleaseInfo(AppRoot)
	if v := Env("SERVICE"); v != "" {
		s.supervised = v
	}
	s.envToken = Env("TOKEN")
	s.setupPush()
	s.wire()
	s.routesInit()
	return s
}

// --- auth ------------------------------------------------------------------------------

var localHost = regexp.MustCompile(`^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$`)

func fromLocalhost(r *http.Request) bool {
	if !localHost.MatchString(r.Host) {
		return false
	}
	if r.Header.Get("X-Forwarded-For") != "" || r.Header.Get("Tailscale-User-Login") != "" {
		return false
	}
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	return localHost.MatchString(u.Host)
}

var bearerRe = regexp.MustCompile(`(?i)^Bearer\s+(.+)$`)
var cookieRe = regexp.MustCompile(`(?:^|;\s*)(?:bench|aic)=([^;]+)`)

func presentedToken(r *http.Request) string {
	if m := bearerRe.FindStringSubmatch(r.Header.Get("Authorization")); m != nil {
		return strings.TrimSpace(m[1])
	}
	if m := cookieRe.FindStringSubmatch(r.Header.Get("Cookie")); m != nil {
		v, err := url.QueryUnescape(m[1])
		if err != nil {
			return m[1]
		}
		return v
	}
	return ""
}

func (s *Server) who(r *http.Request) *authInfo {
	if fromLocalhost(r) {
		return &authInfo{local: true}
	}
	t := presentedToken(r)
	if t == "" {
		return nil
	}
	if s.envToken != "" && len(t) == len(s.envToken) && subtle.ConstantTimeCompare([]byte(t), []byte(s.envToken)) == 1 {
		return &authInfo{device: O("id", "env", "name", "BENCH_TOKEN")}
	}
	if d := s.Svc.Store.DeviceByToken(t); d != nil {
		return &authInfo{device: d}
	}
	return nil
}

func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	return err == nil && u.Host == r.Host
}

// --- push --------------------------------------------------------------------------------

func (s *Server) setupPush() {
	st := s.Svc.Store
	pub, priv := st.Setting("vapid_public"), st.Setting("vapid_private")
	var v *VAPID
	var err error
	if pub != "" && priv != "" {
		v, err = LoadVAPID(pub, priv)
	}
	if v == nil || err != nil {
		if v, err = GenerateVAPID(); err != nil {
			return
		}
		st.SetSetting("vapid_public", v.PublicKey)
		st.SetSetting("vapid_private", v.PrivateKey)
	}
	s.vapid = v
	s.Svc.Notifier = func(p *Obj) int { return s.pushAll(p, "always") }
}

var choiceKey = map[string]string{"approval": "approvals", "finished": "finished", "error": "errors"}

func wantsPush(choices any, kind string) bool {
	c, ok := choices.(string)
	if !ok || c == "" {
		return true
	}
	o, ok := ParseJSON(c).(*Obj)
	if !ok {
		return true
	}
	v, has := o.vals[choiceKey[kind]]
	return !has || v != false
}

func (s *Server) pushAll(payload *Obj, kind string) int {
	if s.vapid == nil {
		return 0
	}
	body := ToJSON(payload)
	var wg sync.WaitGroup
	var mu sync.Mutex
	sent := 0
	for _, sub := range s.Svc.Store.ListPushSubscriptions() {
		if kind != "always" && !wantsPush(sub.Get("choices"), kind) {
			continue
		}
		wg.Add(1)
		go func(sub *Obj) {
			defer wg.Done()
			err := s.vapid.Send(sub.Str("endpoint"), sub.Str("p256dh"), sub.Str("auth"), body)
			if err == nil {
				mu.Lock()
				sent++
				mu.Unlock()
				return
			}
			var pe *PushError
			if errors.As(err, &pe) && (pe.Status == 404 || pe.Status == 410) {
				s.Svc.Store.DeletePushSubscription(sub.Str("endpoint"))
			}
		}(sub)
	}
	wg.Wait()
	return sent
}

// --- the event hub ---------------------------------------------------------------------

func (s *Server) broadcast(msg *Obj, sessionID string) {
	text := ToJSON(msg)
	s.hubMu.Lock()
	list := make([]*client, 0, len(s.clients))
	for c := range s.clients {
		list = append(list, c)
	}
	s.hubMu.Unlock()
	for _, c := range list {
		if sessionID != "" {
			c.mu.Lock()
			w := c.watch[sessionID]
			c.mu.Unlock()
			if !w {
				continue
			}
		}
		c.send(text)
	}
}

func (c *client) send(b []byte) {
	c.mu.Lock()
	if c.queued > 4*1024*1024 {
		c.mu.Unlock()
		c.close(websocket.StatusPolicyViolation, "too slow")
		return
	}
	c.queued += len(b)
	c.mu.Unlock()
	select {
	case c.out <- b:
	case <-c.closed:
	default:
		c.close(websocket.StatusPolicyViolation, "too slow")
	}
}

func (c *client) close(code websocket.StatusCode, reason string) {
	c.closeMu.Do(func() {
		close(c.closed)
		c.ws.Close(code, reason)
	})
}

func (c *client) writer() {
	for {
		select {
		case b := <-c.out:
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			err := c.ws.Write(ctx, websocket.MessageText, b)
			cancel()
			c.mu.Lock()
			c.queued -= len(b)
			c.mu.Unlock()
			if err != nil {
				c.close(websocket.StatusGoingAway, "")
				return
			}
		case <-c.closed:
			return
		}
	}
}

const deltaMs = 60 * time.Millisecond

func (s *Server) flushDelta(id string) {
	s.deltaMu.Lock()
	d := s.deltas[id]
	if d == nil {
		s.deltaMu.Unlock()
		return
	}
	delete(s.deltas, id)
	d.timer.Stop()
	s.deltaMu.Unlock()
	msg := O("type", "delta", "session_id", id, "text", d.text.String())
	if d.thinking {
		msg.Set("thinking", true)
	}
	s.broadcast(msg, id)
}

func (s *Server) queueDelta(d *Obj) {
	id := d.Str("session_id")
	thinking := d.Bool("thinking")
	s.deltaMu.Lock()
	cur := s.deltas[id]
	if cur != nil && cur.thinking != thinking {
		s.deltaMu.Unlock()
		s.flushDelta(id)
		s.deltaMu.Lock()
	}
	if next := s.deltas[id]; next != nil {
		next.text.WriteString(d.Str("text"))
	} else {
		pd := &pendingDelta{thinking: thinking}
		pd.text.WriteString(d.Str("text"))
		pd.timer = time.AfterFunc(deltaMs, func() { s.flushDelta(id) })
		s.deltas[id] = pd
	}
	s.deltaMu.Unlock()
}

func (s *Server) wire() {
	rt := s.Svc.Runtime
	rt.On("session", func(x any) {
		ss := x.(*Obj)
		s.flushDelta(ss.Str("id"))
		s.broadcast(O("type", "session", "session", ss), "")
	})
	rt.On("session-removed", func(x any) {
		id := x.(string)
		s.flushDelta(id)
		s.broadcast(O("type", "session-removed", "id", id), "")
	})
	rt.On("event", func(x any) {
		e := x.(*Obj)
		s.flushDelta(e.Str("session_id"))
		s.broadcast(O("type", "event", "event", e), e.Str("session_id"))
	})
	rt.On("delta", func(x any) { s.queueDelta(x.(*Obj)) })
	rt.On("approval", func(x any) {
		a := x.(*Obj)
		s.broadcast(O("type", "approval", "approval", a), "")
		if ss := s.Svc.Store.GetSession(a.Str("session_id")); ss != nil {
			tool := a.Str("tool")
			if strings.HasPrefix(tool, "mcp__bench__") {
				tool = "Bench"
			}
			body := ss.Str("title")
			if a.Str("title") != "" {
				body += " — " + a.Str("title")
			}
			go s.pushAll(O("title", "Approval needed: "+tool, "body", body, "url", SessionPath(ss), "tag", "approval-"+a.Str("id"), "approvalId", a.Str("id"), "sessionId", ss.Str("id")), "approval")
		}
	})
	rt.On("suggestion", func(x any) {
		o := x.(*Obj)
		if o.Str("text") != "" {
			s.broadcast(O("type", "suggestion", "session_id", o.Str("session_id"), "text", o.Str("text")), o.Str("session_id"))
		}
	})
	rt.On("commands", func(x any) {
		o := x.(*Obj)
		s.broadcast(O("type", "commands", "session_id", o.Str("session_id"), "commands", o.Get("commands")), o.Str("session_id"))
	})
	rt.On("approval-done", func(x any) {
		s.broadcast(O("type", "approval-done").Merge(x.(*Obj)), "")
	})
	rt.On("turn-done", func(x any) {
		td := x.(TurnDone)
		ss := td.Session
		if ss == nil {
			return
		}
		if td.Limited {
			body := ss.Str("title")
			if until := ss.ObjAt("pause").Str("until"); until != "" {
				if t, ok := parseISO(until); ok {
					body += " — until " + t.Local().Format("15:04 MST")
				}
			}
			go s.pushAll(O("title", "Paused at the usage limit", "body", body, "url", SessionPath(ss), "tag", "turn-"+ss.Str("id"), "sessionId", ss.Str("id")), "error")
			return
		}
		title, kind := "Claude stopped with an error", "error"
		if td.OK {
			title, kind = "Claude finished", "finished"
		}
		go s.pushAll(O("title", title, "body", ss.Str("title"), "url", SessionPath(ss), "tag", "turn-"+ss.Str("id"), "sessionId", ss.Str("id")), kind)
	})
	s.Svc.On("task", func(t any) { s.broadcast(O("type", "task", "task", t), "") })
	s.Svc.On("git", func(p any) { s.broadcast(O("type", "git", "project_id", p), "") })
}

// --- helpers -------------------------------------------------------------------------------

const gzipMin = 1024

func acceptsGzip(header string) bool {
	star := false
	for _, part := range strings.Split(header, ",") {
		bits := strings.Split(strings.ToLower(strings.TrimSpace(part)), ";")
		name := bits[0]
		weight := 1.0
		for _, p := range bits[1:] {
			p = strings.TrimSpace(p)
			if strings.HasPrefix(p, "q=") {
				weight, _ = strconv.ParseFloat(p[2:], 64)
			}
		}
		if name == "gzip" || name == "x-gzip" {
			return weight > 0
		}
		if name == "*" && weight > 0 {
			star = true
		}
	}
	return star
}

var gzPool = sync.Pool{New: func() any { w, _ := gzip.NewWriterLevel(nil, gzip.DefaultCompression); return w }}
var bufPool = sync.Pool{New: func() any { return new(bytes.Buffer) }}

func writeJSON(w http.ResponseWriter, r *http.Request, body any, status int) {
	raw := ToJSON(body)
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	if len(raw) > gzipMin {
		h.Set("Vary", "accept-encoding")
		if acceptsGzip(r.Header.Get("Accept-Encoding")) {
			buf := bufPool.Get().(*bytes.Buffer)
			buf.Reset()
			gz := gzPool.Get().(*gzip.Writer)
			gz.Reset(buf)
			gz.Write(raw)
			gz.Close()
			gzPool.Put(gz)
			h.Set("Content-Encoding", "gzip")
			h.Set("Content-Length", strconv.Itoa(buf.Len()))
			w.WriteHeader(status)
			w.Write(buf.Bytes())
			bufPool.Put(buf)
			return
		}
	}
	h.Set("Content-Length", strconv.Itoa(len(raw)))
	w.WriteHeader(status)
	w.Write(raw)
}

const maxBody = 85 * 1024 * 1024

func readBody(r *http.Request) (*Obj, error) {
	b, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil {
		return nil, err
	}
	if len(b) > maxBody {
		return nil, fail("Request too large", 413)
	}
	if len(bytes.TrimSpace(b)) == 0 {
		return O(), nil
	}
	v, err := parseJSON(b)
	if err != nil {
		return nil, fail("Invalid JSON", 400)
	}
	o, ok := v.(*Obj)
	if !ok {
		// A JSON array or scalar: Node handles it as an object with no fields.
		return O(), nil
	}
	return o, nil
}

var staticTypes = map[string]string{".html": "text/html; charset=utf-8", ".mjs": "text/javascript; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
	".json": "application/json", ".webmanifest": "application/manifest+json", ".png": "image/png", ".jpg": "image/jpeg", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".map": "application/json"}

var vendor = map[string]string{"preact": "preact", "htm": "htm/dist", "marked": "marked/lib", "dompurify": "dompurify/dist", "xterm": "@xterm/xterm", "xterm-fit": "@xterm/addon-fit/lib", "hljs": "@highlightjs/cdn-assets", "mermaid": "mermaid/dist"}

func sendFile(w http.ResponseWriter, file string, immutable bool) {
	f, err := os.Open(file)
	if err != nil {
		w.WriteHeader(404)
		return
	}
	defer f.Close()
	t := staticTypes[filepath.Ext(file)]
	if t == "" {
		t = "application/octet-stream"
	}
	w.Header().Set("Content-Type", t)
	if immutable {
		w.Header().Set("Cache-Control", "public, max-age=604800")
	} else {
		w.Header().Set("Cache-Control", "no-cache")
	}
	w.WriteHeader(200)
	io.Copy(w, f)
}

func within(base, rel string) string {
	full := filepath.Join(base, rel)
	if filepath.IsAbs(rel) {
		full = filepath.Clean(rel)
	}
	sep := base
	if !strings.HasSuffix(sep, string(filepath.Separator)) {
		sep += string(filepath.Separator)
	}
	if full == strings.TrimRight(base, string(filepath.Separator)) || full == base || strings.HasPrefix(full, sep) {
		return full
	}
	return ""
}

func isFile(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.Mode().IsRegular()
}

func (s *Server) serveStatic(w http.ResponseWriter, r *http.Request, u *url.URL) {
	if strings.HasPrefix(u.Path, "/vendor/") {
		parts := strings.Split(u.Path, "/")
		if len(parts) >= 3 {
			if dir, ok := vendor[parts[2]]; ok {
				rel, _ := url.PathUnescape(strings.Join(parts[3:], "/"))
				if file := within(filepath.Join(s.modulesDir, dir), rel); file != "" && isFile(file) {
					sendFile(w, file, true)
					return
				}
			}
		}
		w.WriteHeader(404)
		return
	}
	rel := "index.html"
	if u.Path != "/" {
		rel, _ = url.PathUnescape(u.Path[1:])
	}
	if file := within(s.webDir, rel); file != "" && isFile(file) {
		sendFile(w, file, false)
		return
	}
	if filepath.Ext(rel) == "" {
		sendFile(w, filepath.Join(s.webDir, "index.html"), false)
		return
	}
	w.WriteHeader(404)
}

// --- session files ------------------------------------------------------------------------

var openable = map[string]string{".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp", ".avif": "image/avif", ".bmp": "image/bmp", ".ico": "image/x-icon",
	".svg": "image/svg+xml", ".pdf": "application/pdf", ".txt": "text/plain; charset=utf-8", ".log": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8", ".csv": "text/csv; charset=utf-8",
	".json": "application/json; charset=utf-8", ".xml": "application/xml; charset=utf-8", ".html": "text/html; charset=utf-8", ".htm": "text/html; charset=utf-8"}

var downloadOnly = map[string]string{".apk": "application/vnd.android.package-archive", ".aab": "application/octet-stream", ".ipa": "application/octet-stream", ".zip": "application/zip", ".gz": "application/gzip",
	".tgz": "application/gzip", ".tar": "application/x-tar", ".7z": "application/x-7z-compressed", ".jar": "application/java-archive", ".wasm": "application/wasm", ".mp4": "video/mp4", ".webm": "video/webm",
	".mp3": "audio/mpeg", ".wav": "audio/wav", ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}

var kindOrder = []string{"image", "pdf", "text", "archive", "media", "document"}
var kinds = map[string]string{"image": ".png .jpg .jpeg .gif .webp .avif .bmp .ico .svg", "pdf": ".pdf", "text": ".txt .log .md .csv .json .xml .html .htm",
	"archive": ".apk .aab .ipa .zip .gz .tgz .tar .7z .jar .wasm", "media": ".mp4 .webm .mp3 .wav", "document": ".docx .xlsx"}

func fileKind(file string) string {
	ext := strings.ToLower(filepath.Ext(file))
	if ext == "" {
		return "other"
	}
	for _, k := range kindOrder {
		for _, e := range strings.Split(kinds[k], " ") {
			if e == ext {
				return k
			}
		}
	}
	return "other"
}

type sessFile struct {
	full, real, mime string
	size             int64
	open             bool
}

func realPath(p string) string {
	r, err := filepath.EvalSymlinks(p)
	if err != nil {
		return ""
	}
	a, _ := filepath.Abs(r)
	return a
}

func (s *Server) sessionFile(sessionID, p string) *sessFile {
	ss := s.Svc.Store.GetSession(sessionID)
	if ss == nil || p == "" {
		return nil
	}
	for _, base := range []string{ss.Str("cwd"), s.Svc.Runtime.AttachmentsDir(sessionID)} {
		full := within(base, p)
		if full == "" {
			continue
		}
		rb := realPath(base)
		if rb == "" {
			continue
		}
		rf := realPath(full)
		if rf == "" {
			continue
		}
		rel, err := filepath.Rel(rb, rf)
		if err != nil || within(rb, rel) == "" || strings.HasPrefix(rel, "..") {
			continue
		}
		st, err := os.Stat(rf)
		if err != nil || !st.Mode().IsRegular() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(full))
		mime, open := openable[ext], true
		if mime == "" {
			open = false
			mime = downloadOnly[ext]
			if mime == "" {
				mime = "application/octet-stream"
			}
		}
		return &sessFile{full: full, real: rf, mime: mime, size: st.Size(), open: open}
	}
	return nil
}

var rangeRe = regexp.MustCompile(`^bytes=(\d*)-(\d*)$`)

// byteRange: (start, end, ok, satisfiable).
func byteRange(h string, size int64) (int64, int64, bool, bool) {
	m := rangeRe.FindStringSubmatch(h)
	if m == nil || (m[1] == "" && m[2] == "") {
		return 0, 0, false, true
	}
	var start, end int64
	if m[1] != "" {
		start, _ = strconv.ParseInt(m[1], 10, 64)
	} else {
		n, _ := strconv.ParseInt(m[2], 10, 64)
		start = size - n
		if start < 0 {
			start = 0
		}
	}
	end = size - 1
	if m[1] != "" && m[2] != "" {
		e, _ := strconv.ParseInt(m[2], 10, 64)
		if e < end {
			end = e
		}
	}
	if start <= end && start < size {
		return start, end, true, true
	}
	return 0, 0, true, false
}

var nonPrintable = regexp.MustCompile(`[^\x20-\x7e]|["\\]`)

func (s *Server) sendSessionFile(w http.ResponseWriter, r *http.Request, sessionID string, u *url.URL) {
	f := s.sessionFile(sessionID, u.Query().Get("path"))
	if f == nil {
		writeJSON(w, r, O("error", "Not found"), 404)
		return
	}
	inline := f.open && u.Query().Get("download") != "1"
	h := w.Header()
	h.Set("Content-Type", f.mime)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cache-Control", "private, no-cache")
	h.Set("Accept-Ranges", "bytes")
	if inline {
		h.Set("Content-Security-Policy", "sandbox")
	} else {
		name := filepath.Base(f.full)
		h.Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"; filename*=UTF-8''%s`, nonPrintable.ReplaceAllString(name, "_"), encodeURIComponent(name)))
	}
	start, end, ranged, ok := byteRange(r.Header.Get("Range"), f.size)
	if !ok {
		h.Set("Content-Range", fmt.Sprintf("bytes */%d", f.size))
		w.WriteHeader(416)
		return
	}
	if !ranged {
		start, end = 0, f.size-1
	} else {
		h.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, f.size))
	}
	length := int64(0)
	if f.size > 0 {
		length = end - start + 1
	}
	h.Set("Content-Length", strconv.FormatInt(length, 10))
	if ranged {
		w.WriteHeader(206)
	} else {
		w.WriteHeader(200)
	}
	if r.Method == "HEAD" || f.size == 0 {
		return
	}
	fh, err := os.Open(f.real)
	if err != nil {
		return
	}
	defer fh.Close()
	fh.Seek(start, io.SeekStart)
	io.CopyN(w, fh, length)
}

func encodeURIComponent(s string) string {
	var b strings.Builder
	for _, c := range []byte(s) {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || strings.IndexByte("-_.!~*'()", c) >= 0 {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func (s *Server) trackedFiles(cwd string, files []string) map[string]bool {
	if len(files) == 0 {
		return map[string]bool{}
	}
	args := []string{"-C", cwd, "ls-files", "-z", "--full-name", "--"}
	for _, f := range files {
		rel, err := filepath.Rel(cwd, f)
		if err != nil {
			rel = f
		}
		args = append(args, rel)
	}
	out, err := exec.Command("git", args...).Output()
	if err != nil {
		return nil
	}
	top, err := exec.Command("git", "-C", cwd, "rev-parse", "--show-toplevel").Output()
	if err != nil {
		return nil
	}
	t := strings.TrimSpace(string(top))
	set := map[string]bool{}
	for _, f := range strings.Split(string(out), "\x00") {
		if f != "" {
			set[filepath.Join(t, f)] = true
		}
	}
	return set
}

func (s *Server) publicURLs() []string {
	var urls []string
	if c := Env("PUBLIC_URL"); c != "" {
		urls = append(urls, strings.TrimRight(c, "/"))
	}
	ts := func(args ...string) string {
		out, _, ok := runCmd(5*time.Second, "tailscale", args...)
		if !ok {
			return ""
		}
		return out
	}
	var status, served string
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { status = ts("status", "--json"); wg.Done() }()
	go func() { served = ts("serve", "status", "--json"); wg.Done() }()
	wg.Wait()
	if status != "" {
		if st, ok := ParseJSON(status).(*Obj); ok {
			name := strings.TrimSuffix(st.ObjAt("Self").Str("DNSName"), ".")
			if name != "" {
				port := s.servedPort(served)
				if port != "" {
					urls = append(urls, "https://"+name+":"+port)
				} else {
					urls = append(urls, "https://"+name)
				}
			}
		}
	}
	seen := map[string]bool{}
	out := []string{}
	for _, u := range urls {
		if !seen[u] {
			seen[u] = true
			out = append(out, u)
		}
	}
	return out
}

func (s *Server) servedPort(raw string) string {
	if raw == "" {
		return ""
	}
	o, ok := ParseJSON(raw).(*Obj)
	if !ok {
		return ""
	}
	backend := fmt.Sprintf("http://127.0.0.1:%d", s.Port)
	web := o.ObjAt("Web")
	for _, hp := range web.Keys() {
		entry, _ := web.Get(hp).(*Obj)
		root := entry.ObjAt("Handlers").ObjAt("/")
		target := root.Get("Proxy")
		if !truthy(target) {
			target = root.Get("Text")
		}
		if !truthy(target) {
			target = root.Get("Path")
		}
		if target == backend {
			port := hp[strings.LastIndex(hp, ":")+1:]
			if port == "443" {
				return ""
			}
			return port
		}
	}
	return ""
}

func httpErr(status int, msg string) error { return fail(msg, status) }

// --- routes -------------------------------------------------------------------------------

func q(c *ctxt, k string) string { return c.u.Query().Get(k) }

func (s *Server) routesInit() {
	svc := s.Svc
	st := svc.Store
	s.route("GET", "/api/state", func(c *ctxt) (any, error) {
		var push any
		if s.vapid != nil {
			push = s.vapid.PublicKey
		}
		return O("version", s.Version, "projects", nonNil(st.ListProjects()), "tasks", svc.Tasks(""), "sessions", svc.Sessions(SessionQuery{}), "approvals", svc.Runtime.Pending(""),
			"providers", svc.Providers(), "defaults", svc.Defaults(), "me", O("local", c.auth.local, "device", c.auth.device), "push", push), nil
	})
	s.route("GET", "/api/projects", func(c *ctxt) (any, error) { return nonNil(st.ListProjects()), nil })
	s.route("POST", "/api/projects/clone", func(c *ctxt) (any, error) { return svc.CloneProject(c.body) })
	s.route("POST", "/api/projects/create", func(c *ctxt) (any, error) { return svc.CreateProject(c.body) })
	s.route("GET", "/api/github/repos", func(c *ctxt) (any, error) { return svc.GithubRepos(), nil })
	s.route("POST", "/api/projects", func(c *ctxt) (any, error) { return svc.AddProject(c.body) })
	s.route("PATCH", "/api/projects/:id", func(c *ctxt) (any, error) {
		if c.body.Has("routing") {
			if _, err := svc.SetProjectRouting(c.params[0], c.body.Get("routing")); err != nil {
				return nil, err
			}
		}
		b := c.body.Clone()
		b.Delete("routing")
		return st.UpdateProject(c.params[0], b), nil
	})
	s.route("DELETE", "/api/projects/:id", func(c *ctxt) (any, error) { svc.RemoveProject(c.params[0]); return O("ok", true), nil })
	s.route("GET", "/api/projects/:id/tree", func(c *ctxt) (any, error) {
		p, err := svc.Project(c.params[0])
		if err != nil {
			return nil, err
		}
		return O("paths", strs(s.listPaths(p.Str("path"), true))), nil
	})
	s.route("GET", "/api/projects/:id/text", func(c *ctxt) (any, error) {
		full, err := s.projectPath(c.params[0], c.u.Query()["path"])
		if err != nil {
			return nil, err
		}
		fi, err := os.Stat(full)
		if err != nil {
			return nil, err
		}
		if !fi.Mode().IsRegular() {
			return nil, httpErr(400, "Not a file")
		}
		if fi.Size() > 2<<20 {
			return nil, httpErr(413, "Too large to open here")
		}
		data, _ := os.ReadFile(full)
		if bytes.IndexByte(data, 0) >= 0 {
			return nil, httpErr(415, "Not a text file")
		}
		return O("path", q(c, "path"), "text", string(data), "mtime", mtimeMs(fi), "size", float64(fi.Size())), nil
	})
	s.route("PUT", "/api/projects/:id/text", func(c *ctxt) (any, error) {
		var rel []string
		if p, ok := c.body.Get("path").(string); ok {
			rel = []string{p}
		}
		full, err := s.projectPath(c.params[0], rel)
		if err != nil {
			return nil, err
		}
		text, ok := c.body.Get("text").(string)
		if !ok {
			return nil, httpErr(400, "No text")
		}
		fi, err := os.Stat(full)
		if err != nil {
			return nil, err
		}
		if m := c.body.Num("mtime"); m != 0 && math.Abs(mtimeMs(fi)-m) > 1 {
			return nil, httpErr(409, "The file changed since you opened it")
		}
		if err := os.WriteFile(full, []byte(text), fi.Mode()); err != nil {
			return nil, err
		}
		s.pathMu.Lock()
		s.pathCache = map[string]pathEntry{}
		s.pathMu.Unlock()
		fi, _ = os.Stat(full)
		return O("ok", true, "mtime", mtimeMs(fi)), nil
	})
	s.route("GET", "/api/projects/:id/git", func(c *ctxt) (any, error) { return svc.Git(c.params[0]) })
	s.route("GET", "/api/projects/:id/diff", func(c *ctxt) (any, error) { return svc.CheckoutDiff(c.params[0]) })
	s.route("POST", "/api/projects/:id/fetch", func(c *ctxt) (any, error) { return svc.Fetch(c.params[0]) })
	s.route("POST", "/api/projects/:id/pull", func(c *ctxt) (any, error) { return svc.Pull(c.params[0]) })
	s.route("POST", "/api/projects/:id/push", func(c *ctxt) (any, error) { return svc.Push(c.params[0]) })
	s.route("POST", "/api/projects/:id/checkout", func(c *ctxt) (any, error) { return svc.Checkout(c.params[0], c.body) })
	s.route("POST", "/api/projects/:id/commit", func(c *ctxt) (any, error) { return svc.Commit(c.params[0], c.body.Str("message")) })
	s.route("GET", "/api/fs", func(c *ctxt) (any, error) {
		home, _ := os.UserHomeDir()
		dir := q(c, "path")
		if dir == "" {
			dir = home
		}
		dir = absPath(dir)
		for !exists(dir) && filepath.Dir(dir) != dir {
			dir = filepath.Dir(dir)
		}
		ents, err := os.ReadDir(dir)
		if err != nil {
			return nil, err
		}
		type entry struct {
			name string
			repo bool
		}
		var list []entry
		for _, e := range ents {
			isDir := e.IsDir()
			if e.Type()&os.ModeSymlink != 0 {
				isDir = false
			}
			if isDir && !strings.HasPrefix(e.Name(), ".") {
				list = append(list, entry{e.Name(), exists(filepath.Join(dir, e.Name(), ".git"))})
			}
		}
		sort.SliceStable(list, func(i, j int) bool { return localeLess(list[i].name, list[j].name) })
		out := []any{}
		for _, e := range list {
			out = append(out, O("name", e.name, "repo", e.repo))
		}
		var parent any
		if filepath.Dir(dir) != dir {
			parent = filepath.Dir(dir)
		}
		return O("path", dir, "parent", parent, "repo", exists(filepath.Join(dir, ".git")), "entries", out), nil
	})
	s.route("POST", "/api/fs/mkdir", func(c *ctxt) (any, error) {
		parent := absPath(str(c.body.Get("parent")))
		name := strings.TrimSpace(str(c.body.Get("name")))
		if name == "" || strings.Contains(name, "/") || strings.HasPrefix(name, ".") || !exists(parent) {
			return nil, fail("Choose a folder name")
		}
		dir := filepath.Join(parent, name)
		if err := os.Mkdir(dir, 0o755); err != nil {
			return nil, fail(nodeFsError(err, "mkdir", dir))
		}
		return O("path", dir), nil
	})
	s.route("GET", "/api/tasks", func(c *ctxt) (any, error) { return svc.Tasks(q(c, "projectId")), nil })
	s.route("POST", "/api/tasks", func(c *ctxt) (any, error) { return svc.CreateTask(c.body.Str("projectId"), c.body) })
	s.route("GET", "/api/tasks/:id", func(c *ctxt) (any, error) {
		t, err := svc.Task(c.params[0])
		if err != nil {
			return nil, err
		}
		g, err := svc.TaskGit(c.params[0])
		if err != nil {
			return nil, err
		}
		return O("task", t, "git", g, "sessions", svc.Sessions(SessionQuery{TaskID: c.params[0], Archived: true})), nil
	})
	s.route("PATCH", "/api/tasks/:id", func(c *ctxt) (any, error) { return svc.EditTask(c.params[0], c.body) })
	s.route("GET", "/api/tasks/:id/git", func(c *ctxt) (any, error) { return svc.TaskGit(c.params[0]) })
	s.route("GET", "/api/tasks/:id/diff", func(c *ctxt) (any, error) { return svc.TaskDiff(c.params[0]) })
	s.route("POST", "/api/tasks/:id/commit", func(c *ctxt) (any, error) { return svc.CommitTask(c.params[0], c.body.Str("message")) })
	s.route("POST", "/api/tasks/:id/update", func(c *ctxt) (any, error) { return svc.UpdateFromBase(c.params[0], "") })
	s.route("POST", "/api/tasks/:id/abort-merge", func(c *ctxt) (any, error) { return svc.AbortTaskMerge(c.params[0]) })
	s.route("POST", "/api/tasks/:id/merge", func(c *ctxt) (any, error) { return svc.MergeTask(c.params[0], c.body) })
	s.route("POST", "/api/tasks/:id/push", func(c *ctxt) (any, error) { return svc.PushTask(c.params[0]) })
	s.route("POST", "/api/tasks/:id/pr", func(c *ctxt) (any, error) { return svc.PullRequest(c.params[0], c.body) })
	s.route("POST", "/api/tasks/:id/close", func(c *ctxt) (any, error) {
		r, err := svc.CloseTask(c.params[0], c.body)
		if err != nil {
			return nil, err
		}
		s.Terms.CloseMatching("task:" + c.params[0])
		return r, nil
	})
	s.route("POST", "/api/tasks/:id/reopen", func(c *ctxt) (any, error) { return svc.ReopenTask(c.params[0]) })
	s.route("POST", "/api/tasks/:id/check", func(c *ctxt) (any, error) { return svc.CheckTask(c.params[0]) })
	s.route("GET", "/api/tasks/:id/check-suggestions", func(c *ctxt) (any, error) {
		t, err := svc.Task(c.params[0])
		if err != nil {
			return nil, err
		}
		where := t.Str("worktree")
		if where == "" || !exists(where) {
			p, err := svc.Project(t.Str("project_id"))
			if err != nil {
				return nil, err
			}
			where = p.Str("path")
		}
		return O("suggestions", SuggestChecks(where)), nil
	})
	s.route("GET", "/api/tasks/:id/log", func(c *ctxt) (any, error) { return svc.TaskLog(c.params[0]) })
	s.route("GET", "/api/sessions", func(c *ctxt) (any, error) {
		return svc.Sessions(SessionQuery{ProjectID: q(c, "projectId"), TaskID: q(c, "taskId"), Archived: q(c, "archived") == "1"}), nil
	})
	s.route("POST", "/api/sessions", func(c *ctxt) (any, error) { return svc.CreateSession(c.body) })
	s.route("GET", "/api/sessions/:id", func(c *ctxt) (any, error) {
		ss, err := svc.Session(c.params[0])
		if err != nil {
			return nil, err
		}
		evs, err := svc.Events(c.params[0], EventQuery{After: atoi(q(c, "after"))})
		if err != nil {
			return nil, err
		}
		return O("session", ss, "events", evs, "approvals", svc.Runtime.Pending(c.params[0]), "live", svc.Runtime.IsLive(c.params[0]), "commands", svc.Runtime.Commands(c.params[0])), nil
	})
	s.route("GET", "/api/sessions/:id/commands", func(c *ctxt) (any, error) { return svc.Runtime.ListCommands(c.params[0]) })
	s.route("GET", "/api/sessions/:id/events", func(c *ctxt) (any, error) {
		return svc.Events(c.params[0], EventQuery{Before: atoi(q(c, "before")), After: atoi(q(c, "after")), Limit: atoi(q(c, "limit"))})
	})
	s.route("GET", "/api/sessions/:id/events/:event/thinking", func(c *ctxt) (any, error) { return svc.EventThinking(c.params[0], atoi(c.params[1])) })
	s.route("POST", "/api/sessions/:id/messages", func(c *ctxt) (any, error) {
		return svc.Send(c.params[0], str(c.body.Get("text")), SendOpts{Images: arr(c.body.Get("images")), Files: arr(c.body.Get("files")), Uploads: arr(c.body.Get("uploads"))})
	})
	s.route("GET", "/api/sessions/:id/files", func(c *ctxt) (any, error) {
		s.sendSessionFile(c.w, c.r, c.params[0], c.u)
		c.sent = true
		return nil, nil
	})
	notesIn := func(id any) (string, error) {
		if !truthy(id) {
			return "", nil
		}
		p, err := svc.Project(str(id))
		if err != nil {
			return "", err
		}
		return p.Str("path"), nil
	}
	s.route("GET", "/api/notes", func(c *ctxt) (any, error) {
		id := q(c, "project")
		where, err := notesIn(id)
		if err != nil {
			return nil, err
		}
		elsewhere := 0
		if id == "" {
			for _, p := range st.ListProjects() {
				elsewhere += len(svc.Runtime.Notes().List(p.Str("path")))
			}
		}
		return O("notes", svc.Runtime.Notes().List(where), "instructions", where != "" && exists(filepath.Join(where, "CLAUDE.md")), "elsewhere", float64(elsewhere)), nil
	})
	s.route("GET", "/api/notes/text", func(c *ctxt) (any, error) {
		where, err := notesIn(q(c, "project"))
		if err != nil {
			return nil, err
		}
		var name any
		if v, ok := c.u.Query()["name"]; ok {
			name = v[0]
		}
		return svc.Runtime.Notes().Read(where, name)
	})
	s.route("POST", "/api/notes", func(c *ctxt) (any, error) {
		where, err := notesIn(c.body.Get("project"))
		if err != nil {
			return nil, err
		}
		var text *string
		if t, ok := c.body.Get("text").(string); ok {
			text = &t
		}
		return svc.Runtime.Notes().Create(where, c.body.Get("title"), text)
	})
	s.route("PUT", "/api/notes/text", func(c *ctxt) (any, error) {
		where, err := notesIn(c.body.Get("project"))
		if err != nil {
			return nil, err
		}
		return svc.Runtime.Notes().Write(where, c.body.Get("name"), c.body.Get("text"), num(c.body.Get("mtime")))
	})
	s.route("PATCH", "/api/notes", func(c *ctxt) (any, error) {
		where, err := notesIn(c.body.Get("project"))
		if err != nil {
			return nil, err
		}
		return svc.Runtime.Notes().SetInclude(where, c.body.Get("name"), c.body.Bool("include"))
	})
	s.route("DELETE", "/api/notes", func(c *ctxt) (any, error) {
		where, err := notesIn(q(c, "project"))
		if err != nil {
			return nil, err
		}
		var name any
		if v, ok := c.u.Query()["name"]; ok {
			name = v[0]
		}
		if err := svc.Runtime.Notes().Remove(where, name); err != nil {
			return nil, err
		}
		return O("ok", true), nil
	})
	s.route("POST", "/api/notes/promote", func(c *ctxt) (any, error) {
		if _, err := svc.Runtime.Notes().Read("", c.body.Get("name")); err != nil {
			return nil, err
		}
		project, err := svc.CreateProject(O("name", str(c.body.Get("projectName"))))
		if err != nil {
			return nil, err
		}
		name, err := svc.Runtime.Notes().Move(c.body.Get("name"), project.Str("path"))
		if err != nil {
			return nil, err
		}
		return O("project", project, "name", name), nil
	})
	s.route("GET", "/api/sessions/:id/paths", func(c *ctxt) (any, error) {
		ss, err := svc.Session(c.params[0])
		if err != nil {
			return nil, err
		}
		qq := q(c, "q")
		pp := ""
		if pid := ss.Str("project_id"); pid != "" {
			if p := st.GetProject(pid); p != nil {
				pp = p.Str("path")
			}
		}
		notes := svc.Runtime.Notes().Suggest(pp, qq)
		asFiles := map[string]bool{}
		for _, n := range notes {
			asFiles["notes/"+n.(*Obj).Str("token")[5:]+".md"] = true
		}
		var paths []any
		for _, p := range rankPaths(s.listPaths(ss.Str("cwd"), false), qq) {
			if !asFiles[p] {
				paths = append(paths, p)
			}
			if len(paths) == 30 {
				break
			}
		}
		if paths == nil {
			paths = []any{}
		}
		return O("paths", paths, "notes", notes), nil
	})
	s.route("POST", "/api/sessions/:id/files/stat", func(c *ctxt) (any, error) {
		ss, err := svc.Session(c.params[0])
		if err != nil {
			return nil, err
		}
		var paths []string
		for i, x := range arr(c.body.Get("paths")) {
			if i >= 500 {
				break
			}
			paths = append(paths, str(x))
		}
		attachments := svc.Runtime.AttachmentsDir(ss.Str("id")) + string(filepath.Separator)
		type found struct {
			p string
			f *sessFile
		}
		var fs []found
		for _, p := range paths {
			if f := s.sessionFile(ss.Str("id"), p); f != nil {
				fs = append(fs, found{p, f})
			}
		}
		seen := map[string]bool{}
		var toAsk []string
		for _, x := range fs {
			if !seen[x.f.full] && !strings.HasPrefix(x.f.full, attachments) {
				seen[x.f.full] = true
				toAsk = append(toAsk, x.f.full)
			}
		}
		tracked := s.trackedFiles(ss.Str("cwd"), toAsk)
		files := O()
		for _, x := range fs {
			kind := fileKind(x.f.full)
			var artifact bool
			if strings.HasPrefix(x.f.full, attachments) {
				artifact = true
			} else if tracked != nil {
				artifact = !tracked[x.f.full]
			} else {
				artifact = kind != "text" && kind != "other"
			}
			files.Set(x.p, O("path", x.f.full, "name", filepath.Base(x.f.full), "mime", x.f.mime, "size", float64(x.f.size), "open", x.f.open, "kind", kind, "artifact", artifact))
		}
		return files, nil
	})
	s.route("GET", "/api/sessions/:id/routing", func(c *ctxt) (any, error) { return svc.SessionRouting(c.params[0]) })
	s.route("POST", "/api/sessions/:id/limit", func(c *ctxt) (any, error) { return svc.SessionLimit(c.params[0], c.body) })
	s.route("POST", "/api/sessions/:id/interrupt", func(c *ctxt) (any, error) {
		ok, err := svc.Interrupt(c.params[0])
		if err != nil {
			return nil, err
		}
		return O("ok", ok), nil
	})
	s.route("PATCH", "/api/sessions/:id", func(c *ctxt) (any, error) { return svc.UpdateSession(c.params[0], c.body) })
	s.route("POST", "/api/sessions/:id/archive", func(c *ctxt) (any, error) {
		archived := c.body.Get("archived") != false
		r, err := svc.ArchiveSession(c.params[0], archived)
		if err != nil {
			return nil, err
		}
		if archived {
			s.Terms.CloseMatching("shell:" + c.params[0])
		}
		return r, nil
	})
	s.route("DELETE", "/api/sessions/:id", func(c *ctxt) (any, error) {
		r, err := svc.DeleteSession(c.params[0])
		if err != nil {
			return nil, err
		}
		s.Terms.CloseMatching("shell:" + c.params[0])
		return r, nil
	})
	s.route("POST", "/api/approvals/:id", func(c *ctxt) (any, error) {
		ok, err := svc.Runtime.Answer(c.params[0], c.body)
		if err != nil {
			return nil, err
		}
		return O("ok", ok), nil
	})
	s.route("GET", "/api/providers", func(c *ctxt) (any, error) { return svc.Providers(), nil })
	s.route("PATCH", "/api/providers/:id", func(c *ctxt) (any, error) { return st.UpdateProvider(c.params[0], c.body) })
	s.route("POST", "/api/providers/:id/test", func(c *ctxt) (any, error) { return svc.TestProvider(c.params[0], c.body.Str("modelId")) })
	s.route("PATCH", "/api/models/:id", func(c *ctxt) (any, error) {
		id, _ := url.PathUnescape(c.params[0])
		return st.UpdateModel(id, c.body), nil
	})
	s.route("GET", "/api/defaults", func(c *ctxt) (any, error) { return svc.Defaults(), nil })
	s.route("PUT", "/api/defaults", func(c *ctxt) (any, error) { return svc.SetDefaults(c.body) })
	s.route("GET", "/api/usage", func(c *ctxt) (any, error) { return st.Usage(), nil })
	s.route("GET", "/api/usage/models", func(c *ctxt) (any, error) { return st.UsageByModel(q(c, "projectId"), q(c, "taskId")), nil })
	s.route("GET", "/api/doctor", func(c *ctxt) (any, error) { return svc.Doctor(), nil })
	s.route("GET", "/api/devices", func(c *ctxt) (any, error) { return st.ListDevices(), nil })
	s.route("DELETE", "/api/devices/:id", func(c *ctxt) (any, error) { return O("ok", st.RevokeDevice(c.params[0])), nil })
	s.route("POST", "/api/pairing", func(c *ctxt) (any, error) {
		p, err := s.newPairing()
		if err != nil {
			return nil, err
		}
		var qr any
		if urls := strList(p.Get("urls")); len(urls) > 0 {
			if svg, err := QRSVG(urls[0]); err == nil {
				qr = svg
			}
		}
		return p.Set("qr", qr), nil
	})
	s.route("GET", "/api/push/key", func(c *ctxt) (any, error) {
		if s.vapid == nil {
			return nil, fail("Push is not available", 503)
		}
		return O("key", s.vapid.PublicKey), nil
	})
	s.route("POST", "/api/push/subscribe", func(c *ctxt) (any, error) { st.AddPushSubscription(c.body); return O("ok", true), nil })
	s.route("POST", "/api/push/unsubscribe", func(c *ctxt) (any, error) { return O("ok", st.DeletePushSubscription(c.body.Str("endpoint")) > 0), nil })
	s.route("POST", "/api/push/choices", func(c *ctxt) (any, error) {
		return O("ok", st.SetPushChoices(c.body.Str("endpoint"), c.body.Get("choices")) > 0), nil
	})
	s.route("GET", "/api/server/status", func(c *ctxt) (any, error) { return s.status(), nil })
	upd := func() UpdateOpts {
		return UpdateOpts{AppRoot: AppRoot, LogFile: filepath.Join(s.Root, ".bench", "update.log"), DataRoot: s.Root, Supervised: s.supervised}
	}
	s.route("GET", "/api/server/update", func(c *ctxt) (any, error) { return UpdateStatus(upd()), nil })
	s.route("POST", "/api/server/update", func(c *ctxt) (any, error) { return StartUpdate(upd()) })
	s.route("GET", "/api/metrics", func(c *ctxt) (any, error) { return s.metrics(), nil })
	svc.Host = &Host{Pair: s.newPairing, Metrics: s.metrics, Status: s.status}
	s.route("POST", "/api/server/restart", func(c *ctxt) (any, error) {
		if s.supervised == nil {
			return nil, fail("Not running as a service, so nothing would start it again. Run `bench service install` on the server.", 409)
		}
		time.AfterFunc(100*time.Millisecond, s.Shutdown)
		return O("ok", true), nil
	})
	s.route("POST", "/api/server/shutdown", func(c *ctxt) (any, error) {
		if !c.auth.local {
			return nil, fail("Only from this machine", 403)
		}
		time.AfterFunc(50*time.Millisecond, s.Shutdown)
		return O("ok", true), nil
	})
}

func atoi(s string) int {
	f, err := strconv.ParseFloat(s, 64)
	if err != nil || f != f {
		return 0
	}
	return int(f)
}

// localeLess approximates String.prototype.localeCompare for folder names.
func localeLess(a, b string) bool {
	la, lb := strings.ToLower(a), strings.ToLower(b)
	if la != lb {
		return la < lb
	}
	return a > b
}

func nodeFsError(err error, op, path string) string {
	if os.IsExist(err) {
		return fmt.Sprintf("EEXIST: file already exists, %s '%s'", op, path)
	}
	return err.Error()
}

func (s *Server) status() *Obj {
	var commit any
	if s.release != nil {
		commit = or(s.release.Get("commit"), nil)
	}
	return O("pid", float64(os.Getpid()), "startedAt", float64(s.started.UnixMilli()), "uptimeMs", float64(time.Since(s.started).Milliseconds()), "version", s.Version, "commit", commit,
		"root", s.Root, "terminals", float64(s.Terms.Size()), "supervised", s.supervised, "server", "go")
}

func (s *Server) metrics() *Obj {
	projects := s.Svc.Store.ListProjects()
	home, _ := os.UserHomeDir()
	releases := Env("RELEASE_DIR")
	if releases == "" {
		releases = filepath.Join(home, ".local", "share", "bench", "releases")
	}
	targets := []*Obj{O("name", "Bench data", "path", filepath.Join(s.Root, ".bench"), "note", "database, attachments"), O("name", "Releases", "path", releases, "note", "installed versions")}
	for i, p := range projects {
		if i >= 8 {
			break
		}
		targets = append(targets, O("name", p.Str("name"), "path", p.Str("path"), "note", "checkout"), O("name", p.Str("name")+" worktrees", "path", WorktreeRoot(p.Str("path")), "note", "task branches"))
	}
	return Collect(MetricsInput{Root: s.Root, Sessions: s.Svc.Sessions(SessionQuery{}), Projects: projects, Tasks: s.Svc.Tasks(""), ClaudePids: s.Svc.Runtime.Pids(), Terminals: s.Terms.List(), Storage: targets})
}

func (s *Server) newPairing() (*Obj, error) {
	b := make([]byte, 9)
	rand.Read(b)
	code := base64.RawURLEncoding.EncodeToString(b)
	s.Svc.Store.AddPairing(code, 10*time.Minute)
	urls := []any{}
	for _, base := range s.publicURLs() {
		urls = append(urls, base+"/#/pair/"+code)
	}
	return O("code", code, "urls", urls, "expiresIn", 600.0), nil
}

func (s *Server) projectPath(id string, rel []string) (string, error) {
	p, err := s.Svc.Project(id)
	if err != nil {
		return "", err
	}
	root := realPath(p.Str("path"))
	if len(rel) == 0 || rel[0] == "" {
		return "", httpErr(400, "No path")
	}
	full := filepath.Join(root, rel[0])
	if filepath.IsAbs(rel[0]) {
		full = rel[0]
	}
	real := realPath(full)
	if real == "" {
		return "", httpErr(404, "No such file")
	}
	if !strings.HasPrefix(real, root+string(filepath.Separator)) {
		return "", httpErr(403, "Outside the project")
	}
	return real, nil
}

var skipDirs = map[string]bool{"node_modules": true, ".git": true, "build": true, "dist": true, ".build": true, "DerivedData": true}

func (s *Server) listPaths(cwd string, all bool) []string {
	key := cwd
	if all {
		key = cwd + "\x00all"
	}
	s.pathMu.Lock()
	if hit, ok := s.pathCache[key]; ok && time.Since(hit.at) < 15*time.Second {
		s.pathMu.Unlock()
		return hit.list
	}
	s.pathMu.Unlock()
	cmd := exec.Command("git", "ls-files", "-co", "--exclude-standard")
	cmd.Dir = cwd
	out, err := cmd.Output()
	var list []string
	if err == nil {
		list = splitLines(string(out))
	} else {
		list = []string{}
		var walk func(dir string, depth int)
		walk = func(dir string, depth int) {
			if depth > 6 || len(list) > 20000 {
				return
			}
			ents, err := os.ReadDir(filepath.Join(cwd, dir))
			if err != nil {
				return
			}
			for _, e := range ents {
				if strings.HasPrefix(e.Name(), ".") || skipDirs[e.Name()] {
					continue
				}
				rel := e.Name()
				if dir != "" {
					rel = dir + "/" + e.Name()
				}
				if e.IsDir() {
					walk(rel, depth+1)
				} else {
					list = append(list, rel)
				}
			}
		}
		walk("", 0)
	}
	if list == nil {
		list = []string{}
	}
	s.pathMu.Lock()
	s.pathCache[key] = pathEntry{time.Now(), list}
	s.pathMu.Unlock()
	return list
}

func rankPaths(list []string, q string) []string {
	q = strings.ToLower(q)
	if q == "" {
		out := append([]string(nil), list...)
		sort.SliceStable(out, func(i, j int) bool {
			di, dj := strings.Count(out[i], "/"), strings.Count(out[j], "/")
			if di != dj {
				return di < dj
			}
			return localeLess(out[i], out[j])
		})
		return out
	}
	type sc struct {
		score, l int
		p        string
	}
	var scored []sc
	for _, p := range list {
		lp := strings.ToLower(p)
		name := lp[strings.LastIndex(lp, "/")+1:]
		score := -1
		switch {
		case strings.HasPrefix(name, q):
			score = 0
		case strings.Contains(name, q):
			score = 1
		case strings.Contains(lp, q):
			score = 2
		}
		if score < 0 {
			qr := []rune(q)
			i := 0
			for _, c := range lp {
				if i < len(qr) && c == qr[i] {
					i++
				}
			}
			if i < len(qr) {
				continue
			}
			score = 3
		}
		scored = append(scored, sc{score, jsLen(p), p})
	}
	sort.SliceStable(scored, func(i, j int) bool {
		if scored[i].score != scored[j].score {
			return scored[i].score < scored[j].score
		}
		return scored[i].l < scored[j].l
	})
	out := make([]string, len(scored))
	for i, x := range scored {
		out[i] = x.p
	}
	return out
}

// --- the request handler --------------------------------------------------------------

var previewRe = regexp.MustCompile(`^/api/sessions/([^/]+)/preview/(\d{2,5})(/.*)?$`)
var uploadRe = regexp.MustCompile(`^/api/sessions/([^/]+)/uploads$`)

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("panic on %s %s: %v", r.Method, r.URL.Path, rec)
			writeJSON(w, r, O("error", fmt.Sprint(rec)), 500)
		}
	}()
	u := r.URL
	if strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		s.upgrade(w, r)
		return
	}
	if !strings.HasPrefix(u.Path, "/api/") {
		s.serveStatic(w, r, u)
		return
	}
	if u.Path == "/api/pair" && r.Method == "POST" {
		s.pair(w, r, u)
		return
	}
	if u.Path == "/api/ping" {
		writeJSON(w, r, O("ok", true, "paired", s.who(r) != nil), 200)
		return
	}
	if u.Path == "/api/hosts" && s.who(r) != nil {
		writeJSON(w, r, O("hosts", strs(ownHosts())), 200)
		return
	}
	auth := s.who(r)
	if auth == nil {
		writeJSON(w, r, O("error", "unauthorized"), 401)
		return
	}
	if m := previewRe.FindStringSubmatch(u.Path); m != nil {
		id, _ := url.PathUnescape(m[1])
		port, _ := strconv.Atoi(m[2])
		rest := m[3]
		if rest == "" {
			rest = "/"
		}
		s.proxyPreview(w, r, u, id, port, rest)
		return
	}
	if r.Method == "POST" {
		if m := uploadRe.FindStringSubmatch(u.Path); m != nil {
			id, _ := url.PathUnescape(m[1])
			s.receiveUpload(w, r, u, id)
			return
		}
	}
	if r.Method != "GET" && r.Method != "DELETE" && !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		writeJSON(w, r, O("error", "Send JSON"), 415)
		return
	}
	for _, rt := range s.routes {
		if rt.method != r.Method {
			continue
		}
		m := rt.re.FindStringSubmatch(u.Path)
		if m == nil {
			continue
		}
		params := make([]string, len(m)-1)
		for i, p := range m[1:] {
			params[i], _ = url.PathUnescape(p)
		}
		c := &ctxt{w: w, r: r, u: u, params: params, auth: auth, body: O()}
		if r.Method != "GET" && r.Method != "DELETE" {
			b, err := readBody(r)
			if err != nil {
				s.fail(w, r, err)
				return
			}
			c.body = b
		}
		out, err := rt.fn(c)
		if c.sent {
			return
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		writeJSON(w, r, out, 200)
		return
	}
	writeJSON(w, r, O("error", "Not found"), 404)
}

func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	status := 400
	body := O("error", err.Error())
	var he *HTTPError
	if errors.As(err, &he) {
		status = he.Status
		if he.Code != "" {
			body.Set("code", he.Code)
		}
		if he.Conflicts != nil {
			body.Set("conflicts", strs(he.Conflicts))
		}
		if he.Dirty != nil {
			body.Set("dirty", strs(he.Dirty))
		}
	} else if os.IsNotExist(err) {
		var pe *os.PathError
		if errors.As(err, &pe) {
			body.Set("error", fmt.Sprintf("ENOENT: no such file or directory, %s '%s'", pe.Op, pe.Path))
			body.Set("code", "ENOENT")
		}
	}
	writeJSON(w, r, body, status)
}

func (s *Server) pair(w http.ResponseWriter, r *http.Request, u *url.URL) {
	addr, _, _ := net.SplitHostPort(r.RemoteAddr)
	if addr == "" {
		addr = "?"
	}
	s.pairMu.Lock()
	f := s.pairFails[addr]
	if f == nil {
		f = &pairFail{}
	}
	blocked := f.n >= 10 && time.Since(f.at) < 15*time.Minute
	s.pairMu.Unlock()
	if blocked {
		writeJSON(w, r, O("error", "Too many attempts; try again later"), 429)
		return
	}
	body, err := readBody(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !s.Svc.Store.UsePairing(str(body.Get("code"))) {
		s.pairMu.Lock()
		s.pairFails[addr] = &pairFail{n: f.n + 1, at: time.Now()}
		s.pairMu.Unlock()
		writeJSON(w, r, O("error", "That pairing code is wrong or has expired"), 401)
		return
	}
	s.pairMu.Lock()
	delete(s.pairFails, addr)
	s.pairMu.Unlock()
	tb := make([]byte, 32)
	rand.Read(tb)
	token := base64.RawURLEncoding.EncodeToString(tb)
	name := str(body.Get("name"))
	if name == "" {
		name = "Device"
	}
	device := s.Svc.Store.AddDevice(jsSlice(name, 60), token)
	secure := r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" || regexp.MustCompile(`\.ts\.net(:\d+)?$`).MatchString(r.Host)
	cookie := "bench=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000"
	if secure {
		cookie += "; Secure"
	}
	w.Header().Set("Set-Cookie", cookie)
	writeJSON(w, r, O("device", device), 200)
}

func (s *Server) receiveUpload(w http.ResponseWriter, r *http.Request, u *url.URL, sessionID string) {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/octet-stream") {
		writeJSON(w, r, O("error", "Send the file as application/octet-stream"), 415)
		return
	}
	if r.ContentLength > MaxUpload {
		writeJSON(w, r, O("error", fmt.Sprintf("Files over %d MB cannot be attached", MaxUpload/1024/1024)), 413)
		return
	}
	if _, err := s.Svc.Session(sessionID); err != nil {
		s.fail(w, r, err)
		return
	}
	name := u.Query().Get("name")
	if name == "" {
		name = "file"
	}
	out, err := s.Svc.Runtime.ReceiveFile(sessionID, name, u.Query().Get("type"), r.Body)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, r, out, 200)
}

func ownHosts() []string {
	host, _ := os.Hostname()
	seen := map[string]bool{}
	var out []string
	add := func(x string) {
		if !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	for _, x := range []string{"localhost", "127.0.0.1", "::1", host} {
		add(x)
	}
	ifaces, _ := net.Interfaces()
	for _, i := range ifaces {
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				add(ipn.IP.String())
			}
		}
	}
	return out
}

var loopbackName = regexp.MustCompile(`^(localhost|127\.0\.0\.1|::1)$`)
var addrLike = regexp.MustCompile(`^[\d.]+$|:`)

func (s *Server) reachable(target int) string {
	s.reachMu.Lock()
	if hit, ok := s.reachCache[target]; ok && hit.addr != "" && time.Since(hit.at) < 30*time.Second {
		s.reachMu.Unlock()
		return hit.addr
	}
	s.reachMu.Unlock()
	addrs := []string{"127.0.0.1"}
	for _, a := range ownHosts() {
		if !loopbackName.MatchString(a) && addrLike.MatchString(a) && !strings.HasPrefix(a, "fe80") {
			addrs = append(addrs, a)
		}
	}
	for _, a := range addrs {
		conn, err := net.DialTimeout("tcp", net.JoinHostPort(a, strconv.Itoa(target)), 800*time.Millisecond)
		if err == nil {
			conn.Close()
			s.reachMu.Lock()
			s.reachCache[target] = reachEntry{time.Now(), a}
			s.reachMu.Unlock()
			return a
		}
	}
	return ""
}

var hopHeaders = regexp.MustCompile(`(?i)^(authorization|cookie|host|connection|upgrade|accept-encoding)$`)
var backHop = regexp.MustCompile(`(?i)^(connection|transfer-encoding|keep-alive)$`)

func (s *Server) proxyPreview(w http.ResponseWriter, r *http.Request, u *url.URL, sessionID string, target int, rest string) {
	if _, err := s.Svc.Session(sessionID); err != nil {
		writeJSON(w, r, O("error", "No such session"), 404)
		return
	}
	if target < 1024 || target > 65535 || target == s.Port {
		writeJSON(w, r, O("error", "Not a port Bench previews"), 403)
		return
	}
	addr := s.reachable(target)
	if addr == "" {
		writeJSON(w, r, O("error", fmt.Sprintf("Nothing is listening on :%d", target), "code", "down"), 502)
		return
	}
	path := rest
	if u.RawQuery != "" {
		path += "?" + u.RawQuery
	}
	req, err := http.NewRequest(r.Method, "http://"+net.JoinHostPort(addr, strconv.Itoa(target))+path, r.Body)
	if err != nil {
		writeJSON(w, r, O("error", err.Error()), 502)
		return
	}
	for k, v := range r.Header {
		if hopHeaders.MatchString(k) {
			continue
		}
		req.Header[k] = v
	}
	req.Host = fmt.Sprintf("localhost:%d", target)
	client := &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	tr := &http.Transport{DisableCompression: true}
	client.Transport = tr
	res, err := client.Do(req)
	if err != nil {
		refused := strings.Contains(err.Error(), "connection refused")
		msg, code := fmt.Sprintf("The server on :%d didn't answer", target), "error"
		if refused {
			msg, code = fmt.Sprintf("Nothing is listening on :%d", target), "down"
		}
		writeJSON(w, r, O("error", msg, "code", code), 502)
		return
	}
	defer res.Body.Close()
	for k, v := range res.Header {
		if !backHop.MatchString(k) {
			w.Header()[k] = v
		}
	}
	w.WriteHeader(res.StatusCode)
	io.Copy(w, res.Body)
}

// --- WebSockets ----------------------------------------------------------------------

func refuse(w http.ResponseWriter, status string) {
	hj, ok := w.(http.Hijacker)
	if !ok {
		code, _ := strconv.Atoi(strings.Fields(status)[0])
		w.WriteHeader(code)
		return
	}
	conn, buf, err := hj.Hijack()
	if err != nil {
		return
	}
	buf.WriteString("HTTP/1.1 " + status + "\r\nConnection: close\r\n\r\n")
	buf.Flush()
	conn.Close()
}

func (s *Server) upgrade(w http.ResponseWriter, r *http.Request) {
	u := r.URL
	if !sameOrigin(r) {
		refuse(w, "403 Forbidden")
		return
	}
	if s.who(r) == nil {
		refuse(w, "401 Unauthorized")
		return
	}
	if m := previewRe.FindStringSubmatch(u.Path); m != nil {
		id, _ := url.PathUnescape(m[1])
		port, _ := strconv.Atoi(m[2])
		rest := m[3]
		if rest == "" {
			rest = "/"
		}
		s.tunnelPreview(w, r, u, id, port, rest)
		return
	}
	switch u.Path {
	case "/api/ws":
		ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true, CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		ws.SetReadLimit(1024 * 1024)
		c := &client{ws: ws, watch: map[string]bool{}, alive: true, out: make(chan []byte, 4096), closed: make(chan struct{})}
		s.hubMu.Lock()
		s.clients[c] = true
		s.hubMu.Unlock()
		go c.writer()
		c.send(ToJSON(O("type", "hello", "sessions", s.Svc.Sessions(SessionQuery{}), "approvals", s.Svc.Runtime.Pending(""))))
		go s.heartbeat(c)
		for {
			_, raw, err := ws.Read(context.Background())
			if err != nil {
				break
			}
			msg, ok := ParseJSON(string(raw)).(*Obj)
			if !ok {
				continue
			}
			switch msg.Str("type") {
			case "watch":
				if ids, ok := msg.Get("ids").([]any); ok {
					set := map[string]bool{}
					for i, x := range ids {
						if i >= 50 {
							break
						}
						set[str(x)] = true
					}
					c.mu.Lock()
					c.watch = set
					c.mu.Unlock()
				}
			case "ping":
				c.mu.Lock()
				c.alive = true
				c.mu.Unlock()
				c.send(ToJSON(O("type", "pong")))
			}
		}
		s.hubMu.Lock()
		delete(s.clients, c)
		s.hubMu.Unlock()
		c.close(websocket.StatusNormalClosure, "")
	case "/api/terminal":
		if Env("DISABLE_TERMINAL") != "" {
			refuse(w, "403 Forbidden")
			return
		}
		spec, errStatus := s.terminalSpec(u)
		if errStatus != "" {
			refuse(w, errStatus)
			return
		}
		ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true, CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		ws.SetReadLimit(1024 * 1024)
		t, err := s.Terms.Open(spec)
		if err != nil {
			msg := err.Error()
			if len(msg) > 120 {
				msg = msg[:120]
			}
			ws.Close(websocket.StatusInternalError, msg)
			return
		}
		s.Terms.Attach(t, ws)
	default:
		hj, ok := w.(http.Hijacker)
		if ok {
			if conn, _, err := hj.Hijack(); err == nil {
				conn.Close()
			}
		}
	}
}

// heartbeat pings every 30s; a client that has not answered by the next round is closed.
func (s *Server) heartbeat(c *client) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			err := c.ws.Ping(ctx)
			cancel()
			if err != nil {
				s.hubMu.Lock()
				delete(s.clients, c)
				s.hubMu.Unlock()
				c.close(websocket.StatusGoingAway, "")
				return
			}
		}
	}
}

var tabRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,32}$`)
var claudeIDRe = regexp.MustCompile(`(?i)^[0-9a-f-]{8,64}$`)

func (s *Server) terminalSpec(u *url.URL) (TermSpec, string) {
	qv := u.Query()
	tab, hasTab := qv["tab"]
	if hasTab && !tabRe.MatchString(tab[0]) {
		return TermSpec{}, "400 Bad Request"
	}
	tabbed := func(sp TermSpec) TermSpec {
		if hasTab && tab[0] != "" {
			sp.Key += "#" + tab[0]
			sp.Keep = true
		}
		return sp
	}
	svc := s.Svc
	if qv.Get("session") != "" && qv.Get("shell") != "" {
		ss, err := svc.Session(qv.Get("session"))
		if err != nil {
			return TermSpec{}, "404 Not Found"
		}
		if !exists(ss.Str("cwd")) {
			return TermSpec{}, "409 Conflict"
		}
		return tabbed(TermSpec{Key: "shell:" + ss.Str("id"), Cwd: ss.Str("cwd")}), ""
	}
	if qv.Get("session") != "" {
		ss, err := svc.Session(qv.Get("session"))
		if err != nil {
			return TermSpec{}, "404 Not Found"
		}
		cid := ss.Str("claude_session_id")
		if cid == "" || !claudeIDRe.MatchString(cid) || !validMode(ss.Str("permission_mode")) {
			return TermSpec{}, "409 Conflict"
		}
		if svc.Runtime.IsLive(ss.Str("id")) {
			if ss.Str("status") == "running" || ss.Str("status") == "waiting" {
				return TermSpec{}, "409 Conflict"
			}
			svc.Runtime.Close(ss.Str("id"))
		}
		var env []string
		if p := svc.Store.GetProvider(ss.Str("provider_id")); p != nil {
			var m *Obj
			if mid := ss.Str("model_id"); mid != "" {
				m = svc.Store.GetModel(mid)
			}
			if pe, err := ProviderEnv(p, m); err == nil {
				env = pe
			} else {
				return TermSpec{}, "404 Not Found"
			}
		}
		return TermSpec{Key: "session:" + ss.Str("id"), Cwd: ss.Str("cwd"), Command: fmt.Sprintf("exec claude --resume %s --permission-mode %s", cid, ss.Str("permission_mode")), Env: env}, ""
	}
	if qv.Get("task") != "" {
		t, err := svc.Task(qv.Get("task"))
		if err != nil {
			return TermSpec{}, "404 Not Found"
		}
		if !exists(t.Str("worktree")) {
			return TermSpec{}, "409 Conflict"
		}
		return tabbed(TermSpec{Key: "task:" + t.Str("id"), Cwd: t.Str("worktree")}), ""
	}
	if qv.Get("project") != "" {
		p, err := svc.Project(qv.Get("project"))
		if err != nil {
			return TermSpec{}, "404 Not Found"
		}
		return tabbed(TermSpec{Key: "project:" + p.Str("id"), Cwd: p.Str("path")}), ""
	}
	return TermSpec{}, "400 Bad Request"
}

func (s *Server) tunnelPreview(w http.ResponseWriter, r *http.Request, u *url.URL, sessionID string, target int, rest string) {
	if _, err := s.Svc.Session(sessionID); err != nil {
		refuse(w, "404 Not Found")
		return
	}
	if target < 1024 || target > 65535 || target == s.Port {
		refuse(w, "403 Forbidden")
		return
	}
	path := rest
	if u.RawQuery != "" {
		path += "?" + u.RawQuery
	}
	lines := []string{fmt.Sprintf("GET %s HTTP/1.1", path), fmt.Sprintf("Host: localhost:%d", target), fmt.Sprintf("Origin: http://localhost:%d", target)}
	for k, vs := range r.Header {
		if regexp.MustCompile(`(?i)^(host|origin|authorization|cookie)$`).MatchString(k) {
			continue
		}
		for _, v := range vs {
			lines = append(lines, k+": "+v)
		}
	}
	addr := s.reachable(target)
	if addr == "" {
		refuse(w, "502 Bad Gateway")
		return
	}
	up, err := net.Dial("tcp", net.JoinHostPort(addr, strconv.Itoa(target)))
	if err != nil {
		refuse(w, "502 Bad Gateway")
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		up.Close()
		return
	}
	conn, buf, err := hj.Hijack()
	if err != nil {
		up.Close()
		return
	}
	up.Write([]byte(strings.Join(lines, "\r\n") + "\r\n\r\n"))
	if n := buf.Reader.Buffered(); n > 0 {
		b, _ := buf.Reader.Peek(n)
		up.Write(b)
	}
	go func() { io.Copy(conn, up); conn.Close() }()
	go func() { io.Copy(up, bufio.NewReader(conn)); up.Close() }()
}

// --- lifecycle ---------------------------------------------------------------------------

var loopbackBind = regexp.MustCompile(`^(localhost|127\.\d+\.\d+\.\d+|::1)$`)

func (s *Server) Listen() error {
	if !loopbackBind.MatchString(s.Host) && Env("ALLOW_EXPOSED") == "" {
		return fmt.Errorf("BENCH_HOST=%s exposes the server beyond this machine. Prefer `bench share` (tailscale serve).\nSet BENCH_ALLOW_EXPOSED=1 to bind it anyway; every request will still need a paired device.", s.Host)
	}
	ln, err := net.Listen("tcp", net.JoinHostPort(s.Host, strconv.Itoa(s.Port)))
	if err != nil {
		return err
	}
	bound := ln.Addr().(*net.TCPAddr).Port
	s.Port = bound
	s.http = &http.Server{Handler: s, ReadHeaderTimeout: 30 * time.Second}
	fmt.Printf("Bench (Go): http://localhost:%d\n", bound)
	if loopbackBind.MatchString(s.Host) {
		fmt.Println("Other devices: run `bench share`, then pair from Settings or `bench pair`.")
	}
	go func() {
		s.http.Serve(ln)
	}()
	return nil
}

func (s *Server) Shutdown() {
	s.closing.Do(func() {
		s.Svc.Shutdown()
		s.Terms.KillAll()
		s.hubMu.Lock()
		for c := range s.clients {
			c.close(websocket.StatusGoingAway, "server shutting down")
		}
		s.hubMu.Unlock()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if s.http != nil {
			s.http.Shutdown(ctx)
		}
		close(s.Done)
	})
}
