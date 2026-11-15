package bench

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// The take-over view follows a popup as it opens, and lists the tabs.
func TestViewFollowsPopupLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	dir, _ := os.MkdirTemp("", "bv")
	b := NewBrowser(dir)
	b.Port = 9463
	defer b.Stop()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, _ := websocket.Accept(w, r, nil)
		b.View(ws)
	}))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	c.SetReadLimit(64 << 20)
	read := func(want string) map[string]any {
		for {
			_, d, err := c.Read(ctx)
			if err != nil {
				t.Fatalf("waiting for %s: %v", want, err)
			}
			var m map[string]any
			json.Unmarshal(d, &m)
			if m["type"] == "error" {
				t.Fatalf("error: %v", m["message"])
			}
			if m["type"] == want {
				return m
			}
		}
	}
	read("frame")
	// The page opens a popup, as "Sign in with Google" does.
	var tab map[string]any
	if err := b.putJSON("/json/new?about:blank", &tab); err != nil {
		t.Fatal(err)
	}
	for {
		m := read("tabs")
		list := m["tabs"].([]any)
		cur := ""
		for _, x := range list {
			if x.(map[string]any)["current"] == true {
				cur = x.(map[string]any)["id"].(string)
			}
		}
		t.Logf("%d tabs, showing %s (new one %v)", len(list), cur, tab["id"])
		if len(list) == 2 && cur == tab["id"] {
			break
		}
	}
	read("frame")
}
