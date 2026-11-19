package bench

import (
	"context"
	"encoding/base64"
	"os"
	"testing"
	"time"
)

// fakeRenderHere stands in for the server's Chromium.
func fakeRenderHere(t *testing.T, called *bool) {
	t.Helper()
	old := renderHere
	renderHere = func(ctx context.Context, url string, w, h int, format string) (*JobResult, error) {
		*called = true
		return &JobResult{OK: true, Image: base64.StdEncoding.EncodeToString([]byte("server picture")), Ms: 5}, nil
	}
	t.Cleanup(func() { renderHere = old })
}

func TestRenderRunsOnTheMac(t *testing.T) {
	svc := testService(t)
	called := false
	fakeRenderHere(t, &called)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"render"}, "")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })

	ch := make(chan string, 1)
	errs := make(chan error, 1)
	go func() {
		f, err := svc.Render(context.Background(), "https://example.com/", 800, 600, "png")
		if err != nil {
			errs <- err
			return
		}
		ch <- f
	}()
	job := mac.read()
	args := job.ObjAt("args")
	if job.Str("kind") != "render" || args.Str("url") != "https://example.com/" || int(args.Num("width")) != 800 || args.Str("format") != "png" {
		t.Fatalf("job %s", ToJSON(job))
	}
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "image", base64.StdEncoding.EncodeToString([]byte("mac picture")), "ranMs", 12))
	var full string
	select {
	case full = <-ch:
	case err := <-errs:
		t.Fatal(err)
	case <-time.After(5 * time.Second):
		t.Fatal("the render did not finish")
	}
	data, err := os.ReadFile(full)
	if err != nil || string(data) != "mac picture" {
		t.Fatalf("%q %v", data, err)
	}
	if called {
		t.Fatal("the server also drew it")
	}
}

func TestRenderFallsBackToTheServer(t *testing.T) {
	svc := testService(t)
	called := false
	fakeRenderHere(t, &called)
	full, err := svc.Render(context.Background(), "https://example.com/", 0, 0, "pdf")
	if err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("the server did not draw it")
	}
	data, err := os.ReadFile(full)
	if err != nil || string(data) != "server picture" {
		t.Fatalf("%q %v", data, err)
	}
	if len(full) < 4 || full[len(full)-4:] != ".pdf" {
		t.Fatalf("file %q", full)
	}
}
