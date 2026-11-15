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

// A fake Mac: links, shares a folder (asks first) and adb (allowed), and answers calls.
func TestDevicesLinkShareAndCall(t *testing.T) {
	svc, err := NewService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	d := svc.Devices()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		d.Link(ws)
	}))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	mac, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	send := func(o *Obj) { mac.Write(ctx, websocket.MessageText, ToJSON(o)) }
	read := func() *Obj {
		_, b, err := mac.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return ParseJSON(string(b)).(*Obj)
	}
	send(O("type", "hello", "id", "mac1", "name", "MacBook", "version", "1.4", "shares", []any{
		O("id", "kron", "kind", "folder", "name", "kron", "path", "~/Projects/kron", "access", "write", "rule", "ask"),
		O("id", "adb", "kind", "run", "name", "Android emulators", "path", "adb", "access", "run", "rule", "allow"),
		O("id", "bad", "kind", "docker"),
	}))
	waitFor(t, func() bool { l := d.List(); return len(l) == 1 && truthy(l[0].Get("online")) })
	if n := len(arr(d.List()[0].Get("shares"))); n != 2 {
		t.Fatalf("%d shares kept, want 2 (an unknown kind is dropped)", n)
	}
	if d.Rule("mcp__bench__mac_read", O("share", "kron")) != "ask" || d.Rule("mcp__bench__mac_run", O("share", "adb")) != "allow" || d.Rule("Bash", O()) != "" {
		t.Fatal("rules")
	}
	if !strings.Contains(d.Note(), "Android emulators") {
		t.Fatal("note")
	}

	// A call goes to the Mac and its answer comes back.
	done := make(chan *Obj, 1)
	go func() {
		r, err := d.Call(ctx, "adb", "run", O("args", []any{"devices"}))
		if err != nil {
			t.Error(err)
		}
		done <- r
	}()
	c := read()
	if c.Str("type") != "call" || c.Str("share") != "adb" || c.Str("tool") != "run" {
		t.Fatalf("call %s", ToJSON(c))
	}
	send(O("type", "result", "id", c.Str("id"), "ok", true, "text", "emulator-5554\tdevice"))
	if r := <-done; r == nil || !strings.Contains(r.Str("text"), "emulator-5554") {
		t.Fatal("result")
	}

	// An ask reaches the Mac; its answer clears it.
	a, err := d.Ask("mac1", O("kind", "folder", "path", "~/Projects/novara", "access", "write"), "")
	if err != nil {
		t.Fatal(err)
	}
	if m := read(); m.Str("type") != "ask" || m.ObjAt("ask").Str("id") != a.Str("id") {
		t.Fatalf("ask %s", ToJSON(m))
	}
	send(O("type", "answer", "id", a.Str("id"), "approved", true))
	waitFor(t, func() bool { return len(arr(d.List()[0].Get("asks"))) == 0 })

	// Offline: calls say so; asks wait.
	mac.Close(websocket.StatusNormalClosure, "")
	waitFor(t, func() bool { return !truthy(d.List()[0].Get("online")) })
	if _, err := d.Call(ctx, "adb", "run", O()); err == nil || !strings.Contains(err.Error(), "offline") {
		t.Fatalf("offline call: %v", err)
	}
	if _, err := d.Ask("mac1", O("kind", "screen"), ""); err != nil {
		t.Fatal(err)
	}
	if len(arr(d.List()[0].Get("asks"))) != 1 {
		t.Fatal("an ask while offline waits")
	}
	if _, err := d.Forget("mac1"); err != nil || len(d.List()) != 0 {
		t.Fatal("forget")
	}
}

func waitFor(t *testing.T, ok func() bool) {
	t.Helper()
	for i := 0; i < 200; i++ {
		if ok() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("timed out")
}
