package bench

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// enrolWorker asks the real route for a worker, signed in as a person on localhost.
func enrolWorker(t *testing.T, base string, body *Obj) (*Obj, string) {
	t.Helper()
	b, _ := json.Marshal(body)
	resp, err := http.Post(base+"/api/workers", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	out, _ := ParseJSON(string(raw)).(*Obj)
	if resp.StatusCode != 200 {
		t.Fatalf("%d %s", resp.StatusCode, raw)
	}
	return out.ObjAt("worker"), out.Str("token")
}

func TestWorkersEnrolAndList(t *testing.T) {
	svc := testService(t)
	w := svc.Workers()
	got, err := w.Enrol(O("name", "Builder", "platform", "linux", "labels", []any{"linux", "docker", "Bad Label!"}, "slots", 3), "https://bench.example/")
	if err != nil {
		t.Fatal(err)
	}
	if got.Str("token") == "" || !strings.Contains(got.Str("command"), got.Str("token")) || !strings.Contains(got.Str("command"), "https://bench.example --token") {
		t.Fatalf("enrol %s", ToJSON(got))
	}
	list := w.List()
	if len(list) != 1 {
		t.Fatalf("%d workers", len(list))
	}
	wk := list[0]
	if wk.Str("name") != "Builder" || wk.Str("platform") != "linux" || truthy(wk.Get("online")) || int(wk.Num("slots")) != 3 {
		t.Fatalf("worker %s", ToJSON(wk))
	}
	if labels := strList(wk.Get("labels")); len(labels) != 2 || labels[0] != "linux" || labels[1] != "docker" {
		t.Fatalf("labels %v", labels)
	}
}

// A worker links with its token, says what it can do, and is listed online until it goes.
func TestWorkerLinksAndSaysWhatItCanRun(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	enroled, token := enrolWorker(t, ts.URL, O("name", "Builder"))
	id := enroled.Str("id")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/api/workers/link", &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	conn.Write(ctx, websocket.MessageText, ToJSON(O("type", "hello", "version", "0.1", "platform", "linux",
		"labels", []any{"linux", "go"}, "slots", 2, "resources", O("cpus", 8, "memMb", 16384))))

	waitFor(t, func() bool {
		l := svc.Workers().List()
		return len(l) == 1 && truthy(l[0].Get("online")) && l[0].Str("version") == "0.1"
	})
	wk := svc.Workers().List()[0]
	if wk.Str("id") != id || wk.Str("version") != "0.1" || int(wk.Num("slots")) != 2 || wk.ObjAt("resources").Num("cpus") != 8 {
		t.Fatalf("worker %s", ToJSON(wk))
	}
	if labels := strList(wk.Get("labels")); len(labels) != 2 || labels[1] != "go" {
		t.Fatalf("labels %v", labels)
	}

	conn.Close(websocket.StatusNormalClosure, "")
	waitFor(t, func() bool { l := svc.Workers().List(); return len(l) == 1 && !truthy(l[0].Get("online")) })
}

// Revoking a worker refuses its token and ends its link.
func TestWorkerRevokedTokenIsRefused(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	enroled, token := enrolWorker(t, ts.URL, O("name", "Old box"))
	id := enroled.Str("id")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/api/workers/link", &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		t.Fatal(err)
	}
	waitFor(t, func() bool { l := svc.Workers().List(); return len(l) == 1 && truthy(l[0].Get("online")) })

	req, _ := http.NewRequest("DELETE", ts.URL+"/api/workers/"+id, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("revoke %d (id %q)", resp.StatusCode, id)
	}
	if len(svc.Workers().List()) != 0 {
		t.Fatal("a revoked worker is still listed")
	}
	// Its link is dropped.
	rctx, rcancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer rcancel()
	if _, _, err := conn.Read(rctx); err == nil {
		t.Fatal("the revoked worker still holds a link")
	}
	// Its token cannot link again.
	conn2, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/api/workers/link", &websocket.DialOptions{HTTPHeader: h})
	if err == nil {
		conn2.Close(websocket.StatusNormalClosure, "")
		t.Fatal("a revoked token linked again")
	}
}

// A worker's token may read the tree it was sent, and nothing more than a worker may.
func TestWorkerTokenAuth(t *testing.T) {
	svc := testService(t)
	s := NewServer(svc, AppRoot)
	ts := httptest.NewServer(s)
	defer ts.Close()
	_, token := enrolWorker(t, ts.URL, O("name", "Builder"))

	req, _ := http.NewRequest("GET", ts.URL+"/api/state", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Host = "bench.example"
	auth := s.who(req)
	if auth == nil || auth.worker == nil || auth.worker.Str("name") != "Builder" {
		t.Fatalf("worker auth %v", auth)
	}
	if person(auth) {
		t.Fatal("a worker counts as a person")
	}
}
