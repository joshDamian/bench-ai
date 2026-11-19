package bench

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// toolNamed says whether the manifest the session is given lists a tool.
func toolNamed(m *MCPServer, name string) bool {
	for _, x := range arr(m.Manifest.ObjAt("toolsListResult").Get("tools")) {
		if x.(*Obj).Str("name") == name {
			return true
		}
	}
	return false
}

// The bench_exec tool sends one command to the pool and says which worker ran it.
func TestBenchExecRunsOnAWorker(t *testing.T) {
	svc := testService(t)
	none := BenchServer(svc, "s1")
	if none.Tools["bench_exec"] != nil {
		t.Fatal("bench_exec was offered with no worker enrolled")
	}
	if toolNamed(none, "bench_exec") {
		t.Fatal("the manifest lists bench_exec with no worker enrolled")
	}
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := workerPair(t, ts, svc, "Builder", []any{"go"}, 1)

	one := BenchServer(svc, "s1")
	exec := one.Tools["bench_exec"]
	if exec == nil {
		t.Fatal("no bench_exec tool with a worker enrolled")
	}
	if !toolNamed(one, "bench_exec") {
		t.Fatal("the manifest does not list bench_exec with a worker enrolled")
	}
	out := make(chan *Obj, 1)
	go func() {
		out <- exec(context.Background(), O("command", "go version", "labels", []any{"go"}, "worker", "Builder"))
	}()

	job := fw.read()
	if job.Str("type") != "job" || job.Str("kind") != "exec" {
		t.Fatalf("job %s", ToJSON(job))
	}
	if got := job.ObjAt("args").Str("command"); got != "go version" {
		t.Fatalf("command %q", got)
	}
	fw.send(O("type", "job_done", "id", job.Str("id"), "ok", true,
		"text", "go version go1.24 linux/amd64", "exit", 0, "ranMs", 12))

	var res *Obj
	select {
	case res = <-out:
	case <-time.After(5 * time.Second):
		t.Fatal("bench_exec did not return")
	}
	if truthy(res.Get("isError")) {
		t.Fatalf("tool error %s", ToJSON(res))
	}
	text := arr(res.Get("content"))[0].(*Obj).Str("text")
	for _, want := range []string{"Builder", "exit 0", "go1.24"} {
		if !strings.Contains(text, want) {
			t.Fatalf("answer %q has no %q", text, want)
		}
	}
}

// A label no worker has stops the job with the reason, and nothing runs on the server.
func TestBenchExecSaysWhenNoWorkerFits(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	workerPair(t, ts, svc, "Builder", []any{"go"}, 1)

	res := BenchServer(svc, "s1").Tools["bench_exec"](context.Background(), O("command", "true", "labels", []any{"macos"}))
	if !truthy(res.Get("isError")) {
		t.Fatalf("no error: %s", ToJSON(res))
	}
	if text := arr(res.Get("content"))[0].(*Obj).Str("text"); !strings.Contains(text, "No worker ran it") {
		t.Fatalf("answer %q", text)
	}
}

// A worker named in the call must exist, and the answer lists the ones that do.
func TestBenchExecNamesTheWorkersItHas(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	workerPair(t, ts, svc, "Builder", nil, 1)

	res := BenchServer(svc, "s1").Tools["bench_exec"](context.Background(), O("command", "true", "worker", "Nope"))
	text := arr(res.Get("content"))[0].(*Obj).Str("text")
	if !truthy(res.Get("isError")) || !strings.Contains(text, `no worker called "Nope"`) || !strings.Contains(text, "Builder") {
		t.Fatalf("answer %s", ToJSON(res))
	}
}
