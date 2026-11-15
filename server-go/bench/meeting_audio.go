package bench

// Is the call hearing Bench, and is Bench hearing the call? Bench can only know it played a line
// into its microphone; whether Meet took it and sent it is in the call page's own audio
// connections. Every page gets callAudioHook before its scripts run (present.go, grantMedia),
// which keeps the page's RTCPeerConnections; the monitor reads their stats every two seconds.

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// callAudioHook keeps a page's audio connections, and window.__benchAudio() sums them up:
// bytes sent and received, the microphone's level, and whether the microphone is muted.
const callAudioHook = `(()=>{if(window.__benchAudio||!window.RTCPeerConnection)return;const pcs=[];const O=window.RTCPeerConnection;
window.RTCPeerConnection=new Proxy(O,{construct(t,a,nt){const pc=Reflect.construct(t,a,nt);pcs.push(pc);return pc}});
window.__benchAudio=async()=>{let sent=0,recv=0,level=0,muted=null,live=0;for(const pc of pcs){if(pc.connectionState==='closed')continue;live++;
try{const st=await pc.getStats();st.forEach(r=>{if(r.type==='outbound-rtp'&&r.kind==='audio')sent+=r.bytesSent||0;if(r.type==='inbound-rtp'&&r.kind==='audio')recv+=r.bytesReceived||0;
if(r.type==='media-source'&&r.kind==='audio'&&typeof r.audioLevel==='number')level=Math.max(level,r.audioLevel)});
pc.getSenders().forEach(s=>{if(s.track&&s.track.kind==='audio')muted=(muted===true)||!s.track.enabled||s.track.muted})}catch(e){}}
return {pcs:live,sent,recv,level,muted}}})();`

type callAudio struct {
	Pcs   int     `json:"pcs"`
	Sent  float64 `json:"sent"`
	Recv  float64 `json:"recv"`
	Level float64 `json:"level"`
	Muted *bool   `json:"muted"`
}

// callTab is the tab a call is in, or nil.
func (b *Browser) callTab() *browserTab {
	for _, t := range b.tabs() {
		if strings.Contains(t.url, "meet.google.com/") || strings.Contains(t.url, "zoom.us/") || strings.Contains(t.url, "teams.microsoft.com") || strings.Contains(t.url, "teams.live.com") {
			return &t
		}
	}
	return nil
}

func (b *Browser) callAudio() (*callAudio, error) {
	t := b.callTab()
	if t == nil {
		return nil, fmt.Errorf("no call tab")
	}
	r, err := cdpCall(t.ws, "Runtime.evaluate", map[string]any{"expression": "window.__benchAudio?window.__benchAudio().then(JSON.stringify):null", "awaitPromise": true, "returnByValue": true})
	if err != nil {
		return nil, err
	}
	v, _ := r["result"].(map[string]any)["value"].(string)
	if v == "" {
		return nil, fmt.Errorf("the call page was open before Bench could watch it")
	}
	var a callAudio
	if err := json.Unmarshal([]byte(v), &a); err != nil {
		return nil, err
	}
	return &a, nil
}

// monitor checks the call's audio every two seconds while a meeting is on, and tells the
// session when its voice stops reaching the call, or the call's audio stops reaching Bench.
func (m *Meeting) monitor(sessionID string, stop <-chan struct{}) {
	var last *callAudio
	var lastAt time.Time
	quietOut, quietIn, deaf := 0, 0, 0
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case <-tick.C:
		}
		a, err := m.svc.Runtime.Browser().callAudio()
		out, in := "unknown", "unknown"
		why := ""
		if err == nil && a.Pcs > 0 {
			m.mu.Lock()
			playing := m.playing != nil
			m.mu.Unlock()
			// Silence from Meet's microphone while a line is playing, twice running.
			if playing && a.Level < 0.001 {
				deaf++
			} else if playing {
				deaf = 0
			}
			out, in = "ok", "ok"
			if last != nil && time.Since(lastAt) < 5*time.Second {
				if a.Sent <= last.Sent {
					quietOut++
				} else {
					quietOut = 0
				}
				if a.Recv <= last.Recv {
					quietIn++
				} else {
					quietIn = 0
				}
			}
			switch {
			case a.Muted != nil && *a.Muted:
				out, why = "muted", "Meet's microphone is muted in the call"
			case quietOut >= 2:
				out, why = "silent", "the browser hasn't sent any audio to the call for a few seconds"
			case deaf >= 2:
				out, why = "silent", "Meet isn't picking up your voice (its microphone is getting silence)"
			}
			if quietIn >= 3 {
				in = "silent"
			}
			last, lastAt = a, time.Now()
		}
		m.mu.Lock()
		if m.sessionID != sessionID {
			m.mu.Unlock()
			return
		}
		was := m.audioOut
		m.audioOut, m.audioIn, m.audioWhy = out, in, why
		tell := (out == "muted" || out == "silent") && was != out && time.Since(m.toldAudio) > time.Minute
		if tell {
			m.toldAudio = time.Now()
		}
		m.mu.Unlock()
		if was != out {
			m.svc.Emit("meeting", nil)
		}
		if tell {
			m.svc.Runtime.Notice(sessionID, "Bench's voice isn't reaching the call: "+why+".")
			ss := m.svc.Store.GetSession(sessionID)
			idle := ss != nil && ss.Str("status") != "running"
			msg := "Bench: your voice isn't reaching the call - " + why + ". Until it's back, write to them in the Meet chat instead of speaking, and say so there."
			if idle {
				m.svc.Send(sessionID, msg, SendOpts{})
			} else {
				m.mu.Lock()
				m.pending = append(m.pending, "("+msg+")")
				m.mu.Unlock()
			}
		}
	}
}
