package bench

// Workers: the machines that run compute jobs for Bench. A worker is a node process on any
// machine (the Mac, a Linux box, a container) that links out to the server, says what it can
// run while it is free, and takes bounded jobs. It is listed apart from the linked devices:
// a device serves a person, a worker serves the pool.
//
// A worker enrols with a one-time token made for it; only the token's hash is kept. The token
// is the worker's whole credential, so revoking the worker ends its link and refuses the token
// from then on. The link carries hello, ping, and later job messages; the server reaches the
// worker over this same WebSocket, never by dialling in.

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const workerReadLimit = 32 * 1024 * 1024

// WorkerKinds are the kinds a worker offers when its hello does not say. Services such as
// serving a board or holding an emulator are not among them: those need a sticky instance and
// are asked for by name.
var WorkerKinds = []string{"exec", "check", "render"}

// worker is one live link. info is the last hello: version, labels, slots, resources.
type worker struct {
	id      string
	info    *Obj
	conn    *websocket.Conn
	ctx     context.Context
	writeMu sync.Mutex
	jobs    map[string]*jobWait
}

// Workers holds the registry rows in SQLite and the live links in memory.
type Workers struct {
	svc  *Service
	mu   sync.Mutex
	live map[string]*worker
}

func (s *Service) Workers() *Workers {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.workers == nil {
		s.workers = &Workers{svc: s, live: map[string]*worker{}}
	}
	return s.workers
}

// NewWorkerToken makes the credential a worker enrols with.
func NewWorkerToken() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// Enrol adds a worker and returns it with its token, shown once, and the command to start it.
func (w *Workers) Enrol(o *Obj, serverURL string) (*Obj, error) {
	name := strings.TrimSpace(o.Str("name"))
	if name == "" {
		name = "Worker"
	}
	platform := strings.ToLower(strings.TrimSpace(o.Str("platform")))
	labels := cleanLabels(o.Get("labels"))
	slots := int(o.Num("slots"))
	if slots <= 0 {
		slots = 1
	}
	if slots > 16 {
		slots = 16
	}
	token := NewWorkerToken()
	row := w.svc.Store.AddWorker(jsSlice(name, 60), token, platform, labels, slots)
	cmd := "bench-worker run --server " + strings.TrimRight(serverURL, "/") + " --token " + token
	return O("worker", w.shape(row, nil), "token", token, "command", cmd), nil
}

// cleanLabels keeps short label words: letters, digits, dot, dash and underscore.
func cleanLabels(v any) []any {
	out := []any{}
	seen := map[string]bool{}
	for _, x := range arr(v) {
		word := strings.ToLower(strings.TrimSpace(str(x)))
		if word == "" || len(word) > 32 || seen[word] {
			continue
		}
		ok := true
		for _, r := range word {
			if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' || r == '-' || r == '_') {
				ok = false
				break
			}
		}
		if ok {
			seen[word] = true
			out = append(out, word)
		}
	}
	return out
}

// storedLabels reads the enrolment labels, kept as JSON text in the row.
func storedLabels(row *Obj) []any {
	if text, ok := row.Get("labels").(string); ok {
		return arr(ParseJSON(text))
	}
	return arr(row.Get("labels"))
}

// shape is one worker as the apps show it: the stored row, plus what the live link says.
func (w *Workers) shape(row *Obj, live *worker) *Obj {
	online := live != nil
	labels := storedLabels(row)
	platform := row.Str("platform")
	slots := int(row.Num("slots"))
	version, resources := any(nil), any(nil)
	kinds := []any{}
	job, jobKind := "", ""
	if online && live.info != nil {
		if v := arr(live.info.Get("labels")); len(v) > 0 {
			labels = v
		}
		if p := live.info.Str("platform"); p != "" {
			platform = p
		}
		if n := int(live.info.Num("slots")); n > 0 {
			slots = n
		}
		version = live.info.Get("version")
		resources = live.info.Get("resources")
		if v := arr(live.info.Get("kinds")); len(v) > 0 {
			kinds = v
		} else {
			for _, k := range WorkerKinds {
				kinds = append(kinds, k)
			}
		}
		if len(live.jobs) > 0 {
			for id := range live.jobs {
				job = id
				break
			}
			jobKind = live.info.Str("job_kind")
		}
	}
	return O("id", row.Str("id"), "name", row.Str("name"), "platform", platform, "labels", labels,
		"slots", slots, "online", online, "version", version, "kinds", kinds, "resources", resources,
		"job", job, "job_kind", jobKind,
		"last_seen", row.Get("last_seen_at"), "created_at", row.Get("created_at"))
}

// List is every worker, online first, then by name.
func (w *Workers) List() []*Obj {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := []*Obj{}
	for _, row := range w.svc.Store.ListWorkers() {
		out = append(out, w.shape(row, w.live[row.Str("id")]))
	}
	sort.SliceStable(out, func(i, j int) bool {
		if truthy(out[i].Get("online")) != truthy(out[j].Get("online")) {
			return truthy(out[i].Get("online"))
		}
		return out[i].Str("name") < out[j].Str("name")
	})
	return out
}

// Revoke ends a worker's link and refuses its token from then on.
func (w *Workers) Revoke(id string) (*Obj, error) {
	if !w.svc.Store.RevokeWorker(id) {
		return nil, fail("No such worker", 404)
	}
	w.mu.Lock()
	live := w.live[id]
	w.mu.Unlock()
	if live != nil {
		live.conn.Close(websocket.StatusNormalClosure, "Revoked by Bench")
	}
	w.svc.Emit("workers", nil)
	return O("ok", true), nil
}

// Link serves one worker's WebSocket: its hello and ping, until it closes or is revoked.
func (w *Workers) Link(ws *websocket.Conn, row *Obj) {
	ws.SetReadLimit(workerReadLimit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	id := row.Str("id")
	wk := &worker{id: id, info: O("name", row.Str("name"), "version", nil, "platform", row.Str("platform"), "labels", storedLabels(row), "slots", row.Num("slots")), conn: ws, ctx: ctx, jobs: map[string]*jobWait{}}
	w.mu.Lock()
	if old := w.live[id]; old != nil {
		old.conn.Close(websocket.StatusNormalClosure, "Linked again")
	}
	w.live[id] = wk
	w.mu.Unlock()
	w.svc.Emit("workers", nil)
	defer func() {
		w.mu.Lock()
		if w.live[id] == wk {
			delete(w.live, id)
		}
		for _, jw := range wk.jobs {
			close(jw.done)
		}
		wk.jobs = map[string]*jobWait{}
		w.mu.Unlock()
		w.svc.Emit("workers", nil)
	}()
	for {
		_, raw, err := ws.Read(ctx)
		if err != nil {
			return
		}
		msg, ok := ParseJSON(string(raw)).(*Obj)
		if !ok {
			continue
		}
		switch msg.Str("type") {
		case "hello":
			w.mu.Lock()
			kinds := cleanCaps(msg.Get("kinds"))
			if len(kinds) == 0 && !msg.Has("kinds") {
				kinds = arr(wk.info.Get("kinds")) // a hello without kinds keeps the last ones
			}
			if len(kinds) == 0 {
				for _, k := range WorkerKinds {
					kinds = append(kinds, k)
				}
			}
			name := wk.info.Str("name")
			wk.info = O("name", name, "version", msg.Get("version"), "platform", msg.Str("platform"),
				"labels", cleanLabels(msg.Get("labels")), "slots", msg.Num("slots"),
				"kinds", kinds, "resources", msg.Get("resources"), "job", wk.info.Str("job"), "job_kind", wk.info.Str("job_kind"))
			w.mu.Unlock()
			w.svc.Emit("workers", nil)
		case "job_progress", "job_done":
			w.mu.Lock()
			jw := wk.jobs[msg.Str("id")]
			if jw != nil && msg.Str("type") == "job_done" {
				delete(wk.jobs, msg.Str("id"))
				wk.info.Set("job", "").Set("job_kind", "")
			}
			w.mu.Unlock()
			if jw == nil {
				continue
			}
			if msg.Str("type") == "job_progress" {
				select {
				case jw.progress <- msg.Str("text"):
				default:
				}
				continue
			}
			jw.done <- msg
			close(jw.done)
		case "ping":
			wk.send(O("type", "pong"))
		}
	}
}

func (wk *worker) send(o *Obj) error {
	wk.writeMu.Lock()
	defer wk.writeMu.Unlock()
	ctx, cancel := context.WithTimeout(wk.ctx, 10*time.Second)
	defer cancel()
	return wk.conn.Write(ctx, websocket.MessageText, ToJSON(o))
}

// Connected says whether a worker holds a live link.
func (w *Workers) Connected(id string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.live[id] != nil
}

// Worker is one worker as the apps see it, or nil.
func (w *Workers) Worker(id string) *Obj {
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, row := range w.svc.Store.ListWorkers() {
		if row.Str("id") == id {
			return w.shape(row, w.live[id])
		}
	}
	return nil
}

// Pick is a connected worker that offers the kind, has a free slot and meets what the job
// requires. A sticky id limits it to that worker. exclude names the workers already tried.
func (w *Workers) Pick(kind string, req *Obj, sticky string, exclude map[string]bool) *Obj {
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, row := range w.svc.Store.ListWorkers() {
		id := row.Str("id")
		if exclude[id] {
			continue
		}
		if sticky != "" && sticky != id {
			continue
		}
		wk := w.live[id]
		if wk == nil || wk.busy() {
			continue
		}
		info := w.shape(row, wk)
		kinds := arr(info.Get("kinds"))
		if len(kinds) > 0 && !hasCap(kinds, kind) {
			continue
		}
		if !meets(info, req) {
			continue
		}
		return info
	}
	return nil
}

// busy is true when every slot is taken. Callers hold w.mu.
func (wk *worker) busy() bool {
	slots := int(wk.info.Num("slots"))
	if slots <= 0 {
		slots = 1
	}
	return len(wk.jobs) >= slots
}

// meets says whether a worker's platform, labels and resources cover a job's requirements.
func meets(info, req *Obj) bool {
	if req == nil {
		return true
	}
	if want := strings.ToLower(req.Str("platform")); want != "" && strings.ToLower(info.Str("platform")) != want {
		return false
	}
	have := map[string]bool{}
	for _, x := range arr(info.Get("labels")) {
		have[strings.ToLower(str(x))] = true
	}
	for _, x := range arr(req.Get("labels")) {
		if !have[strings.ToLower(str(x))] {
			return false
		}
	}
	res := info.ObjAt("resources")
	if res == nil {
		return req.Num("cpus") == 0 && req.Num("memMb") == 0
	}
	return res.Num("cpus") >= req.Num("cpus") && res.Num("memMb") >= req.Num("memMb")
}

// Job sends one job to a worker and waits for its done, passing progress lines as they come.
// The worker must hold a free slot; the job row's lease is renewed by the caller.
func (w *Workers) Job(ctx context.Context, workerID, id, kind string, args *Obj, timeout time.Duration, onProgress func(string)) (*Obj, string, error) {
	w.mu.Lock()
	wk := w.live[workerID]
	if wk == nil {
		w.mu.Unlock()
		return nil, "", errWorkerGone
	}
	name := wk.info.Str("name")
	if name == "" {
		name = "the worker"
	}
	if wk.busy() {
		w.mu.Unlock()
		return nil, name, errWorkerBusy
	}
	jw := &jobWait{progress: make(chan string, 32), done: make(chan *Obj, 1)}
	wk.jobs[id] = jw
	wk.info.Set("job_kind", kind)
	w.mu.Unlock()
	w.svc.Emit("workers", nil)
	sendErr := wk.send(O("type", "job", "id", id, "kind", kind, "args", args, "limits", O("timeoutSec", int(timeout.Seconds()))))
	if sendErr != nil {
		w.mu.Lock()
		delete(wk.jobs, id)
		w.mu.Unlock()
		return nil, name, fmt.Errorf("%s could not be reached: %v", name, sendErr)
	}
	timer := time.NewTimer(timeout + jobGrace)
	defer timer.Stop()
	for {
		select {
		case text := <-jw.progress:
			if onProgress != nil {
				onProgress(text)
			}
		case m, ok := <-jw.done:
			if !ok {
				return nil, name, errWorkerGone
			}
			return m, name, nil
		case <-timer.C:
			w.CancelJob(workerID, id)
			return nil, name, fmt.Errorf("%s did not finish the job in time", name)
		case <-ctx.Done():
			w.CancelJob(workerID, id)
			return nil, name, ctx.Err()
		}
	}
}

// CancelJob stops a job on a worker.
func (w *Workers) CancelJob(workerID, id string) error {
	w.mu.Lock()
	wk := w.live[workerID]
	if wk != nil {
		if jw := wk.jobs[id]; jw != nil {
			delete(wk.jobs, id)
			close(jw.done) // the caller waiting on it stops now
		}
	}
	w.mu.Unlock()
	if wk == nil {
		return errWorkerGone
	}
	w.svc.Emit("workers", nil)
	return wk.send(O("type", "job_cancel", "id", id))
}

var (
	errWorkerGone = fmt.Errorf("The worker went offline")
	errWorkerBusy = fmt.Errorf("The worker has no free slot")
)
