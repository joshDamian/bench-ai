package bench

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// fakeWorker links to a server's worker route with its token and speaks a worker's side.
type fakeWorker struct {
	t    *testing.T
	conn *websocket.Conn
	ctx  context.Context
	name string
}

func newFakeWorker(t *testing.T, base, token string) *fakeWorker {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(base, "http")+"/api/workers/link", &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		conn.Close(websocket.StatusNormalClosure, "")
		cancel()
	})
	return &fakeWorker{t: t, conn: conn, ctx: ctx}
}

func (f *fakeWorker) send(o *Obj) {
	f.t.Helper()
	if err := f.conn.Write(f.ctx, websocket.MessageText, ToJSON(o)); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fakeWorker) read() *Obj {
	f.t.Helper()
	ctx, cancel := context.WithTimeout(f.ctx, 10*time.Second)
	defer cancel()
	_, b, err := f.conn.Read(ctx)
	if err != nil {
		f.t.Fatal(err)
	}
	return ParseJSON(string(b)).(*Obj)
}

// hello says what the worker offers; name is used to tell it apart from another.
func (f *fakeWorker) hello(name string, kinds, labels []any, slots int, cpus float64) {
	f.name = name
	f.send(O("type", "hello", "name", name, "version", "0.2", "platform", "linux",
		"kinds", kinds, "labels", labels, "slots", slots, "resources", O("cpus", cpus, "memMb", 8192)))
}

// workerPair enrols one worker on the server and links it.
func workerPair(t *testing.T, ts *httptest.Server, svc *Service, name string, labels []any, slots int) (*Obj, *fakeWorker) {
	t.Helper()
	enroled, token := enrolWorker(t, ts.URL, O("name", name, "platform", "linux", "labels", labels, "slots", slots))
	fw := newFakeWorker(t, ts.URL, token)
	fw.hello(name, []any{"exec", "check", "render"}, labels, slots, 8)
	waitFor(t, func() bool {
		wk := svc.Workers().Worker(enroled.Str("id"))
		return wk != nil && truthy(wk.Get("online")) && wk.Str("version") == "0.2"
	})
	return enroled, fw
}

func TestQueueSurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	svc, err := NewService(dir)
	if err != nil {
		t.Fatal(err)
	}
	svc.Store.AddQueuedJob("q1", "check", O("command", "go test"), O("labels", []any{"go"}), "")
	svc.Scheduler.Stop()

	again, err := NewService(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer again.Scheduler.Stop()
	if n := again.Store.JobQueueDepth(); n != 1 {
		t.Fatalf("depth after restart %d", n)
	}
	if !again.Store.ClaimQueuedJob("q1", "w1", time.Minute) {
		t.Fatal("the waiting job could not be claimed after the restart")
	}
	if again.Store.ClaimQueuedJob("q1", "w2", time.Minute) {
		t.Fatal("one job was claimed twice")
	}
}

func TestLeaseExpiresAndTheJobMoves(t *testing.T) {
	svc := testService(t)
	st := svc.Store
	st.AddQueuedJob("q1", "check", O(), O(), "")
	if !st.ClaimQueuedJob("q1", "w1", time.Minute) {
		t.Fatal("claim")
	}
	if len(st.ExpireQueuedJobs()) != 0 {
		t.Fatal("a live lease expired")
	}
	// A worker that stopped answering leaves a lease that has run out.
	st.exec("UPDATE job_queue SET lease_until=? WHERE id=?", isoTime(time.Now().Add(-time.Second)), "q1")
	expired := st.ExpireQueuedJobs()
	if len(expired) != 1 || expired[0].Str("worker_id") != "w1" {
		t.Fatalf("expired %v", expired)
	}
	if st.QueuedJob("q1").Str("state") != "queued" {
		t.Fatal("the job did not return to the queue")
	}
	if !st.ClaimQueuedJob("q1", "w2", time.Minute) {
		t.Fatal("the job did not move to the next worker")
	}
}

func TestJobRunsOnAWorker(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := workerPair(t, ts, svc, "Builder", []any{"go"}, 1)

	done := make(chan *JobResult, 1)
	errs := make(chan error, 1)
	go func() {
		r, err := svc.Jobs().Run(context.Background(), &JobSpec{Kind: "check", Timeout: 10 * time.Second,
			Args: O("command", "go vet ./..."),
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				t.Error("the server ran a job a worker could take")
				return &JobResult{OK: true}, nil
			}})
		if err != nil {
			errs <- err
			return
		}
		done <- r
	}()
	job := fw.read()
	if job.Str("type") != "job" || job.Str("kind") != "check" || job.ObjAt("args").Str("command") != "go vet ./..." {
		t.Fatalf("job %s", ToJSON(job))
	}
	fw.send(O("type", "job_progress", "id", job.Str("id"), "text", "building"))
	fw.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "all good", "exit", 0, "ranMs", 42))

	var r *JobResult
	select {
	case r = <-done:
	case err := <-errs:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("the job did not finish")
	}
	if !r.OK || r.RanOn != "worker" || r.Device != "Builder" || r.Text != "all good" {
		t.Fatalf("result %+v", r)
	}
	row := svc.Store.QueuedJob(job.Str("id"))
	if row.Str("state") != "done" || row.Str("worker_id") == "" {
		t.Fatalf("row %s", ToJSON(row))
	}
	waitFor(t, func() bool { return svc.Workers().List()[0].Str("job") == "" })
}

func TestFailedJobTriesAnotherWorkerThenTheServer(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fwA := workerPair(t, ts, svc, "Alpha", nil, 1)
	_, fwB := workerPair(t, ts, svc, "Beta", nil, 1)

	failOne := func(fw *fakeWorker, seen chan<- string) {
		job := fw.read()
		seen <- fw.name
		fw.send(O("type", "job_done", "id", job.Str("id"), "ok", false, "error", "command failed"))
	}
	done := make(chan *JobResult, 1)
	go func() {
		r, err := svc.Jobs().Run(context.Background(), &JobSpec{Kind: "exec", Timeout: 10 * time.Second,
			Args: O("argv", []any{"false"}),
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				return &JobResult{OK: true, Text: "done on the server"}, nil
			}})
		if err != nil {
			t.Error(err)
			return
		}
		done <- r
	}()
	seen := make(chan string, 2)
	go failOne(fwA, seen)
	go failOne(fwB, seen)

	names := map[string]bool{}
	for i := 0; i < 2; i++ {
		select {
		case n := <-seen:
			names[n] = true
		case <-time.After(5 * time.Second):
			t.Fatalf("only %v was asked", names)
		}
	}
	if !names["Alpha"] || !names["Beta"] {
		t.Fatalf("workers asked %v", names)
	}
	select {
	case r := <-done:
		if r.RanOn != "server" || r.Text != "done on the server" {
			t.Fatalf("result %+v", r)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the job did not fall back")
	}
	if svc.Store.JobQueueDepth() != 0 {
		t.Fatal("the finished job is still waiting")
	}
}

func TestTwoWorkersTakeTwoJobsAtOnce(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fwA := workerPair(t, ts, svc, "Alpha", nil, 1)
	_, fwB := workerPair(t, ts, svc, "Beta", nil, 1)

	started := make(chan string, 2)
	release := make(chan struct{})
	runOne := func(fw *fakeWorker) *JobResult {
		job := fw.read()
		started <- fw.name
		<-release
		fw.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", fw.name, "ranMs", 1))
		return &JobResult{Device: fw.name}
	}
	go runOne(fwA)
	go runOne(fwB)

	var wg sync.WaitGroup
	results := make(chan string, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, err := svc.Jobs().Run(context.Background(), &JobSpec{Kind: "exec", Timeout: 10 * time.Second,
				Args: O("argv", []any{"true"}), Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
					return nil, context.DeadlineExceeded
				}})
			if err != nil {
				t.Error(err)
				return
			}
			results <- r.Device
		}()
	}
	names := map[string]bool{}
	for i := 0; i < 2; i++ {
		select {
		case n := <-started:
			names[n] = true
		case <-time.After(5 * time.Second):
			t.Fatalf("only %v started; two jobs did not run at once", names)
		}
	}
	close(release)
	wg.Wait()
	close(results)
	got := map[string]bool{}
	for n := range results {
		got[n] = true
	}
	if !got["Alpha"] || !got["Beta"] {
		t.Fatalf("results came from %v", got)
	}
}

func TestStickyJobGoesOnlyToItsWorker(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fwA := workerPair(t, ts, svc, "Alpha", nil, 1)
	beta, fwB := workerPair(t, ts, svc, "Beta", nil, 1)

	done := make(chan *JobResult, 1)
	go func() {
		r, err := svc.Jobs().Run(context.Background(), &JobSpec{Kind: "exec", Timeout: 10 * time.Second,
			Args: O("argv", []any{"true"}), Sticky: beta.Str("id"),
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				return nil, context.DeadlineExceeded
			}})
		if err != nil {
			t.Error(err)
			return
		}
		done <- r
	}()
	job := fwB.read()
	fwB.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "here", "ranMs", 1))
	if r := <-done; r.Device != "Beta" {
		t.Fatalf("the sticky job ran on %q", r.Device)
	}
	// Alpha was never asked: a read with a short deadline finds nothing.
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if _, _, err := fwA.conn.Read(ctx); err == nil {
		t.Fatal("the other worker was asked too")
	}
}

func TestPickMatchesWhatAJobNeeds(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := workerPair(t, ts, svc, "Builder", []any{"go", "docker"}, 1)
	id := svc.Workers().List()[0].Str("id")

	if w := svc.Workers().Pick("check", O("labels", []any{"go"}), "", nil); w == nil || w.Str("id") != id {
		t.Fatal("a worker with the label was not picked")
	}
	if w := svc.Workers().Pick("check", O("labels", []any{"xcode"}), "", nil); w != nil {
		t.Fatal("a label the worker lacks was enough")
	}
	if w := svc.Workers().Pick("check", O("platform", "darwin"), "", nil); w != nil {
		t.Fatal("the wrong platform was enough")
	}
	if w := svc.Workers().Pick("serve", nil, "", nil); w != nil {
		t.Fatal("a worker offered a service kind it did not list")
	}
	_ = fw
}

func TestCancellingAJobStopsItEverywhere(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := workerPair(t, ts, svc, "Builder", nil, 1)

	errc := make(chan error, 1)
	go func() {
		_, err := svc.Jobs().Run(context.Background(), &JobSpec{Kind: "check", Timeout: 10 * time.Second,
			Args: O("command", "slow"),
			Fallback: func(context.Context, *JobSpec) (*JobResult, error) {
				t.Error("a cancelled job fell back to the server")
				return &JobResult{OK: true}, nil
			}})
		errc <- err
	}()
	job := fw.read()
	if _, err := svc.Jobs().CancelJob(job.Str("id")); err != nil {
		t.Fatal(err)
	}
	stop := fw.read()
	if stop.Str("type") != "job_cancel" || stop.Str("id") != job.Str("id") {
		t.Fatalf("worker told %s", ToJSON(stop))
	}
	select {
	case err := <-errc:
		if err == nil || !strings.Contains(err.Error(), "cancelled") {
			t.Fatalf("run ended with %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the cancelled job kept waiting")
	}
	if st := svc.Store.QueuedJob(job.Str("id")).Str("state"); st != "cancelled" {
		t.Fatalf("state %q", st)
	}
}
