package bench

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// The hook is in a call page before its scripts run, and sees its audio flowing.
func TestCallAudioHookLive(t *testing.T) {
	if os.Getenv("LIVE") == "" {
		t.Skip()
	}
	// A call page: two connections to each other, sending the microphone.
	page := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `<title>call</title><script>
(async()=>{const s=await navigator.mediaDevices.getUserMedia({audio:true});
const a=new RTCPeerConnection(),b=new RTCPeerConnection();
a.onicecandidate=e=>e.candidate&&b.addIceCandidate(e.candidate);b.onicecandidate=e=>e.candidate&&a.addIceCandidate(e.candidate);
s.getTracks().forEach(t=>a.addTrack(t,s));const o=await a.createOffer();await a.setLocalDescription(o);await b.setRemoteDescription(o);
const ans=await b.createAnswer();await b.setLocalDescription(ans);await a.setRemoteDescription(ans);window.ready=true})();
</script>`)
	}))
	defer page.Close()
	dir, _ := os.MkdirTemp("", "ca")
	b := NewBrowser(dir)
	b.Port = 9466
	defer b.Stop()
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	var tab map[string]any
	if err := b.putJSON("/json/new?"+page.URL, &tab); err != nil {
		t.Fatal(err)
	}
	time.Sleep(4 * time.Second)
	ws := b.tab(tab["id"].(string)).ws
	read := func() string {
		r, err := cdpCall(ws, "Runtime.evaluate", map[string]any{"expression": "window.__benchAudio?window.__benchAudio().then(JSON.stringify):'no hook'", "awaitPromise": true, "returnByValue": true})
		if err != nil {
			t.Fatal(err)
		}
		return fmt.Sprint(r["result"].(map[string]any)["value"])
	}
	first := read()
	time.Sleep(2 * time.Second)
	second := read()
	t.Logf("%s\n%s", first, second)
	if first == "no hook" || first == second {
		t.Fatal("the hook did not see audio flowing")
	}
	// Bench speaking: the page's microphone level rises.
	AudioSetup()
	f, _ := os.CreateTemp("", "lvl-*.wav")
	f.Close()
	exec.Command("espeak-ng", "-w", f.Name(), "Testing the level of the voice going into the call, one two three.").Run()
	go exec.Command("paplay", "--device="+sinkMic, f.Name()).Run()
	best := ""
	for i := 0; i < 8; i++ {
		time.Sleep(400 * time.Millisecond)
		if v := read(); strings.Contains(v, `"level":0.`) || strings.Contains(v, `"level":1`) {
			best = v
		}
	}
	t.Logf("while speaking: %s", best)
	if best == "" {
		t.Fatal("the level never rose while Bench spoke")
	}
}
