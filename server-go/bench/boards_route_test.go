package bench

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/coder/websocket"
)

// boardSite is a project with two boards on disk, served by the real route table.
func boardSite(t *testing.T) (*Service, *Server, string, *Obj) {
	t.Helper()
	svc := testService(t)
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "boards"), 0o755)
	if err := os.WriteFile(filepath.Join(dir, "boards", "status.md"), []byte("# Status\nall good\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	page := `<h1>Dash</h1><script>document.title="ok"</script>`
	if err := os.WriteFile(filepath.Join(dir, "boards", "dash.html"), []byte(page), 0o644); err != nil {
		t.Fatal(err)
	}
	p, err := svc.AddProject(O("path", dir, "name", "Boards", "init", true))
	if err != nil {
		t.Fatal(err)
	}
	return svc, NewServer(svc, AppRoot), p.Str("id"), p
}

func get(t *testing.T, url string) (*http.Response, string) {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp, string(b)
}

func TestBoardRouteServesMarkdownAndHTML(t *testing.T) {
	_, s, id, _ := boardSite(t)
	ts := httptest.NewServer(s)
	defer ts.Close()

	resp, body := get(t, ts.URL+"/api/projects/"+id+"/boards/status")
	if resp.StatusCode != 200 || body != "# Status\nall good\n" {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/plain") {
		t.Fatalf("markdown type %q", ct)
	}
	if resp.Header.Get("X-Bench-Served-By") != "server" {
		t.Fatalf("served by %q", resp.Header.Get("X-Bench-Served-By"))
	}

	resp, body = get(t, ts.URL+"/api/projects/"+id+"/boards/dash.html")
	if resp.StatusCode != 200 || !strings.Contains(body, "<h1>Dash</h1>") {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Fatalf("html type %q", ct)
	}
	if csp := resp.Header.Get("Content-Security-Policy"); csp != "sandbox allow-scripts" {
		t.Fatalf("csp %q", csp)
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("html is not nosniff")
	}
}

func TestBoardRouteRefusesUnknownAndClimbingNames(t *testing.T) {
	_, s, id, _ := boardSite(t)
	ts := httptest.NewServer(s)
	defer ts.Close()
	for _, slug := range []string{"nope", "%2e%2e%2fsecret", "..%2Fsecret", "status.md.bak"} {
		if resp, _ := get(t, ts.URL+"/api/projects/"+id+"/boards/"+slug); resp.StatusCode != 404 {
			t.Fatalf("%s gave %d", slug, resp.StatusCode)
		}
	}
	if resp, _ := get(t, ts.URL+"/api/projects/nope/boards/status"); resp.StatusCode != 404 {
		t.Fatalf("unknown project gave %d", resp.StatusCode)
	}
}

// hostOnMac replies to a serve job with the port of a real server that holds the bytes.
func hostOnMac(t *testing.T, mac *fakeMac, page *string) *httptest.Server {
	t.Helper()
	site := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, *page)
	}))
	t.Cleanup(site.Close)
	_, portStr, _ := net.SplitHostPort(strings.TrimPrefix(site.URL, "http://"))
	port, _ := strconv.Atoi(portStr)
	job := mac.read()
	if job.Str("type") != "job" || job.Str("kind") != "serve" {
		t.Fatalf("job %s", ToJSON(job))
	}
	if job.ObjAt("args").Str("token") == "" || job.ObjAt("args").Str("data") == "" {
		t.Fatalf("serve args %s", ToJSON(job.ObjAt("args")))
	}
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "port", port, "ranMs", 3))
	return site
}

func TestBoardRouteProxiesToTheHostingMac(t *testing.T) {
	svc, s, id, _ := boardSite(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"serve"}, "127.0.0.1")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })
	ts := httptest.NewServer(s)
	defer ts.Close()

	page := "<h1>from the mac</h1>"
	errCh := make(chan error, 1)
	go func() {
		resp, err := http.Post(ts.URL+"/api/projects/"+id+"/boards/dash.html/host", "application/json", strings.NewReader(`{"on":"mac"}`))
		if err != nil {
			errCh <- err
			return
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		if resp.StatusCode != 200 || !strings.Contains(string(b), `"on":"mac"`) {
			errCh <- fmt.Errorf("host gave %d %s", resp.StatusCode, b)
			return
		}
		errCh <- nil
	}()
	site := hostOnMac(t, mac, &page)
	if err := <-errCh; err != nil {
		t.Fatal(err)
	}
	if svc.BoardHostPref(id) != "mac" {
		t.Fatalf("preference %q", svc.BoardHostPref(id))
	}
	resp, body := get(t, ts.URL+"/api/projects/"+id+"/boards/dash.html")
	if resp.StatusCode != 200 || body != page {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
	if by := resp.Header.Get("X-Bench-Served-By"); by != "mac:MacBook" {
		t.Fatalf("served by %q", by)
	}

	// The Mac sleeps: its link and its server both go. The next request is answered from the file.
	mac.conn.Close(websocket.StatusNormalClosure, "sleep")
	site.Close()
	waitFor(t, func() bool { l := d.List(); return len(l) == 1 && !truthy(l[0].Get("online")) })
	resp, body = get(t, ts.URL+"/api/projects/"+id+"/boards/dash.html")
	if resp.StatusCode != 200 || !strings.Contains(body, "<h1>Dash</h1>") {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
	if by := resp.Header.Get("X-Bench-Served-By"); by != "server" {
		t.Fatalf("after the drop, served by %q", by)
	}
}

func TestBoardHostFallsBackWhenReachFails(t *testing.T) {
	svc, s, id, _ := boardSite(t)
	d := svc.Devices()
	mac := newFakeMac(t, d)
	mac.hello([]any{"serve"}, "127.0.0.1")
	waitFor(t, func() bool { return len(d.List()) == 1 && truthy(d.List()[0].Get("online")) })
	ts := httptest.NewServer(s)
	defer ts.Close()

	// A port nothing listens on: hosting succeeds but every fetch falls back.
	dead, _ := net.Listen("tcp", "127.0.0.1:0")
	port := dead.Addr().(*net.TCPAddr).Port
	dead.Close()
	go func() {
		resp, err := http.Post(ts.URL+"/api/projects/"+id+"/boards/status/host", "application/json", strings.NewReader(`{"on":"mac"}`))
		if err == nil {
			resp.Body.Close()
		}
	}()
	job := mac.read()
	mac.send(O("type", "job_done", "id", job.Str("id"), "ok", true, "port", port))
	waitFor(t, func() bool { return svc.Jobs().BoardHost(id, "status") != nil })
	resp, body := get(t, ts.URL+"/api/projects/"+id+"/boards/status")
	if resp.StatusCode != 200 || body != "# Status\nall good\n" {
		t.Fatalf("%d %q", resp.StatusCode, body)
	}
	if by := resp.Header.Get("X-Bench-Served-By"); by != "server" {
		t.Fatalf("served by %q", by)
	}
}

func TestBoardHostWithNoDeviceIsAnError(t *testing.T) {
	svc, _, id, _ := boardSite(t)
	_, err := svc.HostBoard(id, "status", "mac")
	if err == nil {
		t.Fatal("hosting with no device succeeded")
	}
	if svc.BoardHostPref(id) != "server" {
		t.Fatalf("preference %q", svc.BoardHostPref(id))
	}
	if _, err := svc.HostBoard(id, "status", "cloud"); err == nil {
		t.Fatal("an unknown target was accepted")
	}
}
