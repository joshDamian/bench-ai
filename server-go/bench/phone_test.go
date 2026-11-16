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

// A fake phone: links as a phone, shares WhatsApp (reply, asks first) and the calendar (read
// only), and answers a reply call.
func TestPhoneLinkSharesAndTools(t *testing.T) {
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
	phone, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	send := func(o *Obj) { phone.Write(ctx, websocket.MessageText, ToJSON(o)) }
	send(O("type", "hello", "id", "p1", "name", "Pixel", "kind", "phone", "shares", []any{
		O("id", "whatsapp", "kind", "messages", "name", "WhatsApp", "path", "com.whatsapp", "access", "reply", "rule", "ask"),
		O("id", "calendar", "kind", "calendar", "name", "Calendar", "access", "read", "rule", "allow"),
	}))
	waitFor(t, func() bool { l := d.List(); return len(l) == 1 && truthy(l[0].Get("online")) })
	if d.List()[0].Str("kind") != "phone" {
		t.Fatal("kind")
	}
	if d.Rule("mcp__bench__phone_reply", O("share", "WhatsApp")) != "ask" || d.Rule("mcp__bench__phone_events", O()) != "allow" || d.Rule("mcp__bench__phone_ask", O()) != "allow" {
		t.Fatal("rules")
	}
	if !strings.Contains(d.Note(), "phone_messages") || strings.Contains(d.Note(), "mac_list") {
		t.Fatal("note")
	}
	// A Mac thing is not asked of a phone.
	if _, err := d.Ask("p1", O("kind", "folder", "path", "~/x"), ""); err == nil {
		t.Fatal("folder asked of a phone")
	}
	tools := phoneTools(svc, "")
	// Read-only calendar: adding is refused here, before the phone.
	if r := tools["phone_add_event"](ctx, O("title", "x", "start", "2026-11-17T10:00:00+01:00")); !truthy(r.Get("isError")) {
		t.Fatal("add_event on a read-only calendar")
	}
	// Texts are not shared.
	if r := tools["phone_sms"](ctx, O("to", "+234", "text", "hi")); !truthy(r.Get("isError")) {
		t.Fatal("sms not shared")
	}
	done := make(chan *Obj, 1)
	go func() { done <- tools["phone_reply"](ctx, O("chat", "Sonia", "text", "On my way")) }()
	_, b, err := phone.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	c := ParseJSON(string(b)).(*Obj)
	if c.Str("tool") != "reply" || c.Str("share") != "whatsapp" || c.ObjAt("args").Str("text") != "On my way" {
		t.Fatalf("call %s", ToJSON(c))
	}
	send(O("type", "result", "id", c.Str("id"), "ok", true, "text", "Replied to Sonia"))
	if r := <-done; truthy(r.Get("isError")) {
		t.Fatalf("reply %s", ToJSON(r))
	}
}
