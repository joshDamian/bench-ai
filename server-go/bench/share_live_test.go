package bench

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// A deck is made, shared from a call's tab without a picker, and moved.
func TestPresentLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	call := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "<title>Meet</title><p>call</p>")
	}))
	defer call.Close()
	root, _ := os.MkdirTemp("", "pl")
	svc, err := NewService(root)
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Scheduler.Stop()
	b := svc.Runtime.Browser()
	b.Port = 9465
	defer b.Stop()
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	tabs := b.tabs()
	cdpCall(tabs[0].ws, "Page.navigate", map[string]any{"url": call.URL})
	time.Sleep(time.Second)
	m := svc.Meeting()
	out, err := m.present("s1", "Remote backend roles", "# Remote backend roles\n\nThree worth a look, found this week\n\n---\n\n## The shortlist\n\n- **Affirm**: Senior Backend, AI Platform\n- **Mrsool**: SDE III, Ruby and Go\n- **Discord**: Staff, User Safety\n\n---\n\n## My pick\n\n> Discord: the team, the stack and the pay line up.\n\nNext: draft a cold email to the hiring manager")
	if err != nil {
		t.Fatal(err)
	}
	t.Log(out[:80])
	time.Sleep(1500 * time.Millisecond)
	var callWS string
	for _, x := range b.tabs() {
		if x.title == "Meet" {
			callWS = x.ws
		}
	}
	perm, _ := cdpCall(callWS, "Runtime.evaluate", map[string]any{"expression": `navigator.permissions.query({name:"microphone"}).then(p=>p.state)`, "awaitPromise": true, "returnByValue": true})
	t.Logf("microphone permission: %v", perm["result"])
	mic, err := cdpCall(callWS, "Runtime.evaluate", map[string]any{
		"expression": `navigator.mediaDevices.getUserMedia({audio:true}).then(s=>"mic ok: "+s.getAudioTracks()[0].label).catch(e=>"ERR "+e.name)`, "awaitPromise": true, "userGesture": true, "returnByValue": true,
	})
	if err != nil {
		t.Fatalf("mic: %v", err)
	}
	t.Logf("%v", mic["result"].(map[string]any)["value"])
	r, err := cdpCall(callWS, "Runtime.evaluate", map[string]any{
		"expression": `navigator.mediaDevices.getDisplayMedia({video:true}).then(s=>s.getVideoTracks()[0].getSettings().displaySurface).catch(e=>"ERR "+e.name+": "+e.message)`,
		"awaitPromise": true, "userGesture": true, "returnByValue": true,
	})
	if err != nil {
		t.Fatal(err)
	}
	got := r["result"].(map[string]any)["value"]
	t.Logf("share from the call: %v", got)
	if got != "browser" {
		t.Fatal("the deck's tab was not shared")
	}
	s, err := m.slide("next")
	if err != nil {
		t.Fatal(err)
	}
	t.Log(s)
	if !strings.Contains(s, "2 of 3") {
		t.Fatal("slide")
	}
	// A picture of slide 2, to look at.
	for _, x := range b.tabs() {
		if x.id == m.deckTab {
			cdpCall(x.ws, "Emulation.setDeviceMetricsOverride", map[string]any{"width": 1280, "height": 720, "deviceScaleFactor": 1, "mobile": false})
			shot, err := cdpCall(x.ws, "Page.captureScreenshot", map[string]any{"format": "png"})
			if err == nil {
				png, _ := base64.StdEncoding.DecodeString(shot["data"].(string))
				os.WriteFile(os.Getenv("SHOT"), png, 0o644)
			}
		}
	}
	m.slide("end")
}
