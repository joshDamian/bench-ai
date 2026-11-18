package bench

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// testService is a Service on a fresh data folder that stops its scheduler with the test.
func testService(t *testing.T) *Service {
	t.Helper()
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(svc.Scheduler.Stop)
	return svc
}

// fakeMac links a stand-in for Bench for Mac and speaks its side of the wire.
type fakeMac struct {
	t    *testing.T
	conn *websocket.Conn
	ctx  context.Context
}

func newFakeMac(t *testing.T, d *Devices) *fakeMac {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		d.Link(ws)
	}))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		srv.Close()
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		conn.Close(websocket.StatusNormalClosure, "")
		cancel()
		srv.Close()
	})
	return &fakeMac{t: t, conn: conn, ctx: ctx}
}

func (m *fakeMac) send(o *Obj) {
	m.t.Helper()
	if err := m.conn.Write(m.ctx, websocket.MessageText, ToJSON(o)); err != nil {
		m.t.Fatal(err)
	}
}

func (m *fakeMac) read() *Obj {
	m.t.Helper()
	_, b, err := m.conn.Read(m.ctx)
	if err != nil {
		m.t.Fatal(err)
	}
	return ParseJSON(string(b)).(*Obj)
}

func (m *fakeMac) hello(caps []any, addr string) {
	m.t.Helper()
	m.send(O("type", "hello", "id", "mac1", "name", "MacBook", "version", "1.5", "caps", caps, "addr", addr, "shares", []any{}))
}

func TestJobsRunOnMac(t *testing.T) {
	svc := testService(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"exec", "check"}, "100.64.0.9")
	waitFor(t, func() bool { l := d.List(); return len(l) == 1 && truthy(l[0].Get("online")) })
	if caps := arr(d.List()[0].Get("caps")); len(caps) != 2 || str(caps[0]) != "exec" {
		t.Fatalf("caps %v", caps)
	}
	dv, addr := d.Pick("exec", "")
	if dv == nil || dv.Str("name") != "MacBook" || addr != "100.64.0.9" {
		t.Fatalf("pick %v %q", dv, addr)
	}
	if dv, _ := d.Pick("render", ""); dv != nil {
		t.Fatal("a capability it did not offer was picked")
	}

	fellBack := false
	events := make(chan string, 8)
	svc.On("job", func(x any) { events <- x.(*Obj).Str("state") })
	type out struct {
		r   *JobResult
		err error
	}
	ch := make(chan out, 1)
	go func() {
		r, err := svc.Jobs().Run(context.Background(), &JobSpec{
			Kind: "exec", Args: O("argv", []any{"echo", "hi"}), Timeout: 5 * time.Second,
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				fellBack = true
				return &JobResult{OK: true, Text: "local"}, nil
			},
		})
		ch <- out{r, err}
	}()
	job := mac.read()
	if job.Str("type") != "job" || job.Str("kind") != "exec" || job.Str("id") == "" {
		t.Fatalf("job %s", ToJSON(job))
	}
	if to := job.ObjAt("limits").Num("timeoutSec"); to != 5 {
		t.Fatalf("limits %s", ToJSON(job))
	}
	mac.send(O("type", "job_progress", "id", job.Str("id"), "text", "working"))
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "done on mac", "exit", 0, "ranMs", 123))
	o := <-ch
	if o.err != nil || o.r == nil {
		t.Fatalf("run: %v", o.err)
	}
	if !o.r.OK || o.r.Text != "done on mac" || o.r.RanOn != "mac" || o.r.Device != "MacBook" || o.r.Ms != 123 {
		t.Fatalf("result %+v", o.r)
	}
	if fellBack {
		t.Fatal("the fallback ran though the Mac finished")
	}
	waitFor(t, func() bool {
		for _, r := range svc.Jobs().Recent() {
			if r.(*Obj).Str("ran_on") == "mac" {
				return true
			}
		}
		return false
	})
	select {
	case s := <-events:
		if s != "running" {
			t.Fatalf("first event %q", s)
		}
	default:
		t.Fatal("no job event")
	}
}

func TestJobsFallBackWhenOffline(t *testing.T) {
	svc := testService(t)
	r, err := svc.Jobs().Run(context.Background(), &JobSpec{
		Kind: "check",
		Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
			return &JobResult{OK: true, Text: "ran here"}, nil
		},
	})
	if err != nil || r == nil || r.RanOn != "server" || r.Text != "ran here" {
		t.Fatalf("%+v %v", r, err)
	}
}

func TestJobsFallBackWhenTheLinkDrops(t *testing.T) {
	svc := testService(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"exec"}, "")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	ch := make(chan *JobResult, 1)
	go func() {
		r, _ := svc.Jobs().Run(context.Background(), &JobSpec{
			Kind: "exec", Timeout: 5 * time.Second,
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				return &JobResult{OK: true, Text: "server"}, nil
			},
		})
		ch <- r
	}()
	job := mac.read()
	if job.Str("type") != "job" {
		t.Fatalf("%s", ToJSON(job))
	}
	mac.conn.Close(websocket.StatusGoingAway, "sleep")
	select {
	case r := <-ch:
		if r == nil || r.RanOn != "server" || r.Text != "server" {
			t.Fatalf("result %+v", r)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the fallback did not run after the link dropped")
	}
}

func TestJobsTimeoutCancelsAndFallsBack(t *testing.T) {
	old := jobGrace
	jobGrace = 100 * time.Millisecond
	defer func() { jobGrace = old }()
	svc := testService(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"exec"}, "")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	ch := make(chan *JobResult, 1)
	go func() {
		r, _ := svc.Jobs().Run(context.Background(), &JobSpec{
			Kind: "exec", Timeout: 200 * time.Millisecond,
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				return &JobResult{OK: true, Text: "server"}, nil
			},
		})
		ch <- r
	}()
	job := mac.read()
	if job.Str("type") != "job" {
		t.Fatalf("%s", ToJSON(job))
	}
	cancel := mac.read()
	if cancel.Str("type") != "job_cancel" || cancel.Str("id") != job.Str("id") {
		t.Fatalf("cancel %s", ToJSON(cancel))
	}
	select {
	case r := <-ch:
		if r == nil || r.RanOn != "server" {
			t.Fatalf("result %+v", r)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the timeout did not fall back")
	}
}

func TestJobsBusyDeviceFallsBack(t *testing.T) {
	svc := testService(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"exec"}, "")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	first := make(chan *JobResult, 1)
	go func() {
		r, _ := svc.Jobs().Run(context.Background(), &JobSpec{
			Kind: "exec", Timeout: 5 * time.Second,
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) { return &JobResult{OK: true, Text: "server"}, nil },
		})
		first <- r
	}()
	job := mac.read()
	if job.Str("type") != "job" {
		t.Fatalf("%s", ToJSON(job))
	}
	r, err := svc.Jobs().Run(context.Background(), &JobSpec{
		Kind: "exec",
		Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
			return &JobResult{OK: true, Text: "second on server"}, nil
		},
	})
	if err != nil || r.RanOn != "server" || r.Text != "second on server" {
		t.Fatalf("second %+v %v", r, err)
	}
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "first done"))
	if r := <-first; r == nil || r.RanOn != "mac" {
		t.Fatalf("first %+v", r)
	}
}
