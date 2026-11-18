package bench

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image/jpeg"
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
	readText := func(want string) map[string]any {
		for {
			typ, d, err := c.Read(ctx)
			if err != nil {
				t.Fatalf("waiting for %s: %v", want, err)
			}
			if typ != websocket.MessageText {
				continue // a frame
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
	readFrame := func() []byte {
		for {
			typ, d, err := c.Read(ctx)
			if err != nil {
				t.Fatalf("waiting for a frame: %v", err)
			}
			if typ == websocket.MessageBinary {
				return d
			}
		}
	}
	// This client reads binary frames, and says so as the apps do. The first frame also
	// says the browser is up, for the popup below.
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"size","width":1280,"height":900,"binaryFrames":true}`))
	readFrame()
	// The page opens a popup, as "Sign in with Google" does.
	var tab map[string]any
	if err := b.putJSON("/json/new?about:blank", &tab); err != nil {
		t.Fatal(err)
	}
	for {
		m := readText("tabs")
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
	readFrame()
}

// An app that hasn't been updated doesn't read binary frames, so it must keep getting the
// JSON text frames it has always had.
func TestViewKeepsTextFramesForOldAppsLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	dir, _ := os.MkdirTemp("", "bv")
	b := NewBrowser(dir)
	b.Port = 9465
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
	// No binaryFrames: the old protocol.
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"size","width":411,"height":860}`))
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"scroll","dy":0.1}`))
	for {
		typ, d, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("waiting for a text frame: %v", err)
		}
		if typ != websocket.MessageText {
			t.Fatalf("an old app got a %v message", typ)
		}
		var m map[string]any
		json.Unmarshal(d, &m)
		if m["type"] != "frame" {
			continue
		}
		img, _ := base64.StdEncoding.DecodeString(fmt.Sprint(m["data"]))
		if _, err := jpeg.DecodeConfig(bytes.NewReader(img)); err != nil {
			t.Fatalf("a text frame is not JPEG: %v", err)
		}
		return
	}
}

// Once input settles, the view sends a still drawn at the screen's resolution: the
// screencast itself is always CSS-pixel sized, so a kept frame is what makes text sharp
// on a phone or Retina screen.
func TestViewStillIsSharpLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	dir, _ := os.MkdirTemp("", "bv")
	b := NewBrowser(dir)
	b.Port = 9464
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

	// The phone's view, then a wheel: the still follows the input.
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"size","width":411,"height":860,"binaryFrames":true}`))
	c.Write(ctx, websocket.MessageText, []byte(`{"type":"scroll","dy":0.1}`))
	for {
		typ, d, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("waiting for a still: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		cfg, err := jpeg.DecodeConfig(bytes.NewReader(d))
		if err != nil {
			t.Fatalf("a frame is not JPEG: %v", err)
		}
		// captureScreenshot at deviceScaleFactor 2: twice the 411 CSS pixels.
		if cfg.Width == 822 && cfg.Height == 1720 {
			return
		}
	}
}
