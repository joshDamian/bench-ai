package bench

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPackTreeLeavesOutWhatAnInstallMakes(t *testing.T) {
	dir := t.TempDir()
	write := func(rel, body string) {
		full := filepath.Join(dir, rel)
		os.MkdirAll(filepath.Dir(full), 0o755)
		if err := os.WriteFile(full, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("src/main.go", "package main\n")
	write("README.md", "hi\n")
	write(".git/config", "x")
	write("node_modules/p/index.js", "x")
	write("dist/out.js", "x")

	gz, hash, err := PackTree(dir)
	if err != nil || hash == "" {
		t.Fatalf("%v %q", err, hash)
	}
	zr, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(zr)
	names := map[string]bool{}
	for {
		h, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		names[h.Name] = true
	}
	if !names["src/main.go"] || !names["README.md"] {
		t.Fatalf("kept %v", names)
	}
	for _, gone := range []string{".git/config", "node_modules/p/index.js", "dist/out.js"} {
		if names[gone] {
			t.Fatalf("%s was packed", gone)
		}
	}

	// The same files pack to the same name, and a changed file packs to another.
	if _, again, _ := PackTree(dir); again != hash {
		t.Fatalf("hash moved: %s then %s", hash, again)
	}
	write("src/main.go", "package main\n// changed\n")
	if _, changed, _ := PackTree(dir); changed == hash {
		t.Fatal("a changed tree kept its hash")
	}
}

// checkTask makes a project and one active task whose check is command.
func checkTask(t *testing.T, svc *Service, command string) (*Obj, string) {
	t.Helper()
	dir := t.TempDir()
	p, err := svc.AddProject(O("path", dir, "name", "Checks", "init", true))
	if err != nil {
		t.Fatal(err)
	}
	wt := filepath.Join(dir, "wt")
	os.MkdirAll(wt, 0o755)
	if err := os.WriteFile(filepath.Join(wt, "a.txt"), []byte("a\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	task := svc.Store.AddTask(O("id", NewID(), "project_id", p.Str("id"), "title", "Check", "branch", "b", "worktree", wt, "check_command", command))
	return task, p.Str("id")
}

func TestCheckRunsOnTheMac(t *testing.T) {
	svc := testService(t)
	task, projectID := checkTask(t, svc, "touch local-ran")
	svc.SetChecksHostPref(projectID, "mac")
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"check"}, "100.64.0.9")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	ch := make(chan *CheckResult, 1)
	go func() { ch <- svc.Runtime.Check(context.Background(), task.Str("id"), "", true) }()
	job := mac.read()
	if job.Str("type") != "job" || job.Str("kind") != "check" {
		t.Fatalf("job %s", ToJSON(job))
	}
	args := job.ObjAt("args")
	if args.Str("command") != "touch local-ran" || args.Str("treeUrl") == "" || args.Str("treeHash") == "" {
		t.Fatalf("args %s", ToJSON(args))
	}
	treeID := args.Str("treeId")
	if gz, hash, ok := svc.Jobs().Tree(treeID); !ok || len(gz) == 0 || hash != args.Str("treeHash") {
		t.Fatal("the tree the job names is not held")
	}
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "all good", "exit", 0, "ranMs", 42))
	res := <-ch
	if res == nil || !res.OK || !res.Fresh {
		t.Fatalf("result %+v", res)
	}
	if res.Output != "(ran on MacBook)\nall good" {
		t.Fatalf("output %q", res.Output)
	}
	if exists(filepath.Join(task.Str("worktree"), "local-ran")) {
		t.Fatal("the check also ran on the server")
	}
	if got := svc.Store.GetTask(task.Str("id")); got.Str("check_status") != "passed" {
		t.Fatalf("status %q", got.Str("check_status"))
	}
}

// A worker in the pool takes the check, exactly as the Mac app does.
func TestCheckRunsOnAWorker(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, fw := workerPair(t, ts, svc, "Builder", nil, 1)
	task, projectID := checkTask(t, svc, "go test ./...")
	svc.SetChecksHostPref(projectID, "mac")

	ch := make(chan *CheckResult, 1)
	go func() { ch <- svc.Runtime.Check(context.Background(), task.Str("id"), "", true) }()
	job := fw.read()
	if job.Str("type") != "job" || job.Str("kind") != "check" {
		t.Fatalf("job %s", ToJSON(job))
	}
	args := job.ObjAt("args")
	if args.Str("command") != "go test ./..." || args.Str("treeHash") == "" {
		t.Fatalf("args %s", ToJSON(args))
	}
	fw.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "text", "all good", "exit", 0, "ranMs", 42))
	var res *CheckResult
	select {
	case res = <-ch:
	case <-time.After(5 * time.Second):
		t.Fatal("the check did not finish")
	}
	if res == nil || !res.OK || res.Output != "(ran on Builder)\nall good" {
		t.Fatalf("result %+v", res)
	}
	if got := svc.Store.GetTask(task.Str("id")); got.Str("check_status") != "passed" {
		t.Fatalf("status %q", got.Str("check_status"))
	}
}

func TestCheckStaysHereWhenTheDeviceFails(t *testing.T) {
	svc := testService(t)
	task, projectID := checkTask(t, svc, "echo local-ran")
	svc.SetChecksHostPref(projectID, "mac")
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"check"}, "")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	ch := make(chan *CheckResult, 1)
	go func() { ch <- svc.Runtime.Check(context.Background(), task.Str("id"), "", true) }()
	job := mac.read()
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", false, "error", "no toolchain"))
	select {
	case res := <-ch:
		if res == nil || !res.OK || res.Output != "local-ran" {
			t.Fatalf("result %+v", res)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the check did not fall back")
	}
}

func TestCheckStaysHereWhenTheProjectSaysServer(t *testing.T) {
	svc := testService(t)
	task, _ := checkTask(t, svc, "echo local-ran")
	res := svc.Runtime.Check(context.Background(), task.Str("id"), "", true)
	if res == nil || !res.OK || res.Output != "local-ran" {
		t.Fatalf("result %+v", res)
	}
}
