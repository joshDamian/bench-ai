package bench

// Devices: other computers that share part of themselves with Bench (Go server only). Bench for
// Mac links over a WebSocket and says what it shares, in three groups: folders, apps and the
// screen, and things it can run (Android emulators through adb). Nothing else on the Mac is
// reachable. Sessions use a share through the mac_* tools; each call goes to the Mac and back.
//
// A share is decided on the Mac: a new one is only ever added there. From the phone or a
// session, Bench can only ask; the ask waits on the Mac (queued while it is asleep) until the
// person approves or declines it there. Inside a share, its rule says whether a call runs at
// once (Allowed) or is put to the person first (Asks first), and that can be answered anywhere.
//
// The share kinds are generic: what a "run" share runs is the Mac's business, so a new kind of
// thing to run needs no change here.
//
// A phone links the same way (kind "phone", from Bench for Android) and shares other things:
// an app's messages, read from its notifications and answered through their reply action
// ("messages"); the calendar; contacts; sending texts ("sms"); and preparing a message, event
// or route in its app for the person to finish ("handover"). Through Shizuku it can also share
// apps Bench may use for the person ("use", one share per app), worked on a hidden screen, and
// the notifications of those apps ("notifications"). The phone_* tools use them.

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// ShareKinds are the groups a share falls in, in the order the apps list them.
var ShareKinds = []string{"folder", "app", "screen", "run", "messages", "calendar", "contacts", "sms", "handover", "use", "notifications"}

// PhoneKinds are the share kinds only a phone has.
var PhoneKinds = []string{"messages", "calendar", "contacts", "sms", "handover", "use", "notifications"}

type device struct {
	info    *Obj // id, name, version, shares, last_seen
	conn    *websocket.Conn
	ctx     context.Context
	writeMu sync.Mutex
	waiting map[string]chan *Obj
}

// Devices holds the linked devices: their last known shares on disk, the live link in memory.
type Devices struct {
	svc  *Service
	mu   sync.Mutex
	live map[string]*device
	next int
}

func (s *Service) Devices() *Devices {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.devices == nil {
		s.devices = &Devices{svc: s, live: map[string]*device{}}
	}
	return s.devices
}

func (d *Devices) file(name string) string { return filepath.Join(d.svc.Store.Dir, name) }

func (d *Devices) readList(name string) []*Obj {
	b, err := os.ReadFile(d.file(name))
	if err != nil {
		return nil
	}
	var out []*Obj
	for _, x := range arr(ParseJSON(string(b))) {
		if o, ok := x.(*Obj); ok {
			out = append(out, o)
		}
	}
	return out
}

func (d *Devices) writeList(name string, list []*Obj) {
	all := make([]any, len(list))
	for i, x := range list {
		all[i] = x
	}
	os.WriteFile(d.file(name), ToJSON(all), 0o600)
}

// known is every device that has ever linked, with what it last shared. Callers hold d.mu.
func (d *Devices) known() []*Obj { return d.readList("devices.json") }

func (d *Devices) remember(info *Obj) {
	list := d.known()
	found := false
	for i, x := range list {
		if x.Str("id") == info.Str("id") {
			list[i] = info
			found = true
		}
	}
	if !found {
		list = append(list, info)
	}
	d.writeList("devices.json", list)
}

// cleanShares keeps what a device sent that has the shape of a share.
func cleanShares(v any) []any {
	out := []any{}
	for _, x := range arr(v) {
		o, ok := x.(*Obj)
		if !ok || o.Str("id") == "" || !inList(ShareKinds, o.Str("kind")) {
			continue
		}
		rule := o.Str("rule")
		if rule != "allow" {
			rule = "ask"
		}
		out = append(out, O("id", o.Str("id"), "kind", o.Str("kind"), "name", o.Str("name"), "path", o.Get("path"), "access", o.Str("access"), "rule", rule, "detail", o.Get("detail")))
	}
	return out
}

// List is the devices as the apps show them: online or not, their shares, and asks waiting.
func (d *Devices) List() []*Obj {
	d.mu.Lock()
	defer d.mu.Unlock()
	asks := d.readList("device-asks.json")
	out := []*Obj{}
	for _, info := range d.known() {
		id := info.Str("id")
		mine := []any{}
		for _, a := range asks {
			if a.Str("device_id") == id {
				mine = append(mine, a)
			}
		}
		_, online := d.live[id]
		shares := info.Get("shares")
		if shares == nil {
			shares = []any{}
		}
		kind := info.Str("kind")
		if kind == "" {
			kind = "mac"
		}
		out = append(out, O("id", id, "name", info.Str("name"), "kind", kind, "version", info.Get("version"), "online", online, "last_seen", info.Get("last_seen"), "shares", shares, "asks", mine))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Str("name") < out[j].Str("name") })
	return out
}

// Device is one device as the apps show it.
func (d *Devices) Device(id string) (*Obj, error) {
	for _, x := range d.List() {
		if x.Str("id") == id {
			return x, nil
		}
	}
	return nil, fail("No such device", 404)
}

// Ask asks a device to share something more. It waits on the device until the person decides
// there; a device that is offline gets it when it next links.
func (d *Devices) Ask(deviceID string, o *Obj, sessionID string) (*Obj, error) {
	kind := o.Str("kind")
	if !inList(ShareKinds, kind) {
		return nil, fail("Ask for a folder, an app, the screen or something to run; on a phone, an app's messages, the calendar, contacts, texts or hand-overs")
	}
	path := strings.TrimSpace(o.Str("path"))
	if (kind == "folder" || kind == "app" || kind == "run" || kind == "messages" || kind == "use") && path == "" {
		return nil, fail("Say which one")
	}
	access := o.Str("access")
	switch kind {
	case "folder", "calendar":
		if access != "write" {
			access = "read"
		}
	case "messages":
		if access != "reply" {
			access = "read"
		}
	default:
		access = ""
	}
	d.mu.Lock()
	ok := false
	for _, x := range d.known() {
		if x.Str("id") == deviceID {
			ok = true
			// A phone shares phone things; a Mac, Mac things.
			if (x.Str("kind") == "phone") != inList(PhoneKinds, kind) {
				d.mu.Unlock()
				return nil, fail(fmt.Sprintf("%s does not share that kind of thing", x.Str("name")))
			}
		}
	}
	if !ok {
		d.mu.Unlock()
		return nil, fail("No such device", 404)
	}
	var sess any
	if sessionID != "" {
		sess = sessionID
	}
	a := O("id", NewID(), "device_id", deviceID, "kind", kind, "path", path, "access", access, "why", strings.TrimSpace(o.Str("why")), "session_id", sess, "created_at", isoNow())
	d.writeList("device-asks.json", append(d.readList("device-asks.json"), a))
	dev := d.live[deviceID]
	d.mu.Unlock()
	if dev != nil {
		dev.send(O("type", "ask", "ask", a))
	}
	d.svc.Emit("devices", nil)
	return a, nil
}

// CancelAsk takes back an ask the device has not answered.
func (d *Devices) CancelAsk(id string) (*Obj, error) {
	a := d.dropAsk(id)
	if a == nil {
		return nil, fail("No such request", 404)
	}
	d.mu.Lock()
	dev := d.live[a.Str("device_id")]
	d.mu.Unlock()
	if dev != nil {
		dev.send(O("type", "cancel_ask", "id", id))
	}
	d.svc.Emit("devices", nil)
	return O("ok", true), nil
}

func (d *Devices) dropAsk(id string) *Obj {
	d.mu.Lock()
	defer d.mu.Unlock()
	var found *Obj
	left := []*Obj{}
	for _, a := range d.readList("device-asks.json") {
		if a.Str("id") == id {
			found = a
		} else {
			left = append(left, a)
		}
	}
	if found != nil {
		d.writeList("device-asks.json", left)
	}
	return found
}

// Forget removes a device and what it shared from Bench; it can link again.
func (d *Devices) Forget(id string) (*Obj, error) {
	d.mu.Lock()
	list := d.known()
	left := []*Obj{}
	for _, x := range list {
		if x.Str("id") != id {
			left = append(left, x)
		}
	}
	if len(left) == len(list) {
		d.mu.Unlock()
		return nil, fail("No such device", 404)
	}
	d.writeList("devices.json", left)
	asks := []*Obj{}
	for _, a := range d.readList("device-asks.json") {
		if a.Str("device_id") != id {
			asks = append(asks, a)
		}
	}
	d.writeList("device-asks.json", asks)
	dev := d.live[id]
	d.mu.Unlock()
	if dev != nil {
		dev.conn.Close(websocket.StatusNormalClosure, "Forgotten by Bench")
	}
	d.svc.Emit("devices", nil)
	return O("ok", true), nil
}

func (dev *device) send(o *Obj) error {
	dev.writeMu.Lock()
	defer dev.writeMu.Unlock()
	ctx, cancel := context.WithTimeout(dev.ctx, 10*time.Second)
	defer cancel()
	return dev.conn.Write(ctx, websocket.MessageText, ToJSON(o))
}

// Link serves one device's WebSocket until it closes: its hello, its shares as they change,
// the results of calls, and its answers to asks.
func (d *Devices) Link(ws *websocket.Conn) {
	ws.SetReadLimit(32 * 1024 * 1024)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var dev *device
	defer func() {
		if dev == nil {
			return
		}
		d.mu.Lock()
		if d.live[dev.info.Str("id")] == dev {
			delete(d.live, dev.info.Str("id"))
		}
		for _, ch := range dev.waiting {
			close(ch)
		}
		dev.waiting = map[string]chan *Obj{}
		d.mu.Unlock()
		d.svc.Emit("devices", nil)
	}()
	for {
		_, raw, err := ws.Read(ctx)
		if err != nil {
			return
		}
		msg, ok := ParseJSON(string(raw)).(*Obj)
		if !ok {
			continue
		}
		switch msg.Str("type") {
		case "hello", "shares":
			id := msg.Str("id")
			if dev != nil {
				id = dev.info.Str("id")
			}
			if id == "" {
				ws.Close(websocket.StatusPolicyViolation, "A device says who it is first")
				return
			}
			name := strings.TrimSpace(msg.Str("name"))
			if name == "" && dev != nil {
				name = dev.info.Str("name")
			}
			if name == "" {
				name = "Mac"
			}
			kind := msg.Str("kind")
			if kind != "phone" {
				kind = "mac"
			}
			if dev != nil && dev.info.Str("kind") != "" {
				kind = dev.info.Str("kind")
			}
			info := O("id", id, "name", name, "kind", kind, "version", msg.Get("version"), "shares", cleanShares(msg.Get("shares")), "last_seen", isoNow())
			d.mu.Lock()
			d.remember(info)
			if dev == nil {
				if old := d.live[id]; old != nil {
					old.conn.Close(websocket.StatusNormalClosure, "Linked again")
				}
				dev = &device{conn: ws, ctx: ctx, waiting: map[string]chan *Obj{}}
				d.live[id] = dev
			}
			dev.info = info
			// What was asked while it was away.
			var asks []*Obj
			for _, a := range d.readList("device-asks.json") {
				if a.Str("device_id") == id {
					asks = append(asks, a)
				}
			}
			d.mu.Unlock()
			if msg.Str("type") == "hello" {
				for _, a := range asks {
					dev.send(O("type", "ask", "ask", a))
				}
			}
			d.svc.Emit("devices", nil)
		case "result":
			if dev == nil {
				continue
			}
			d.mu.Lock()
			ch := dev.waiting[msg.Str("id")]
			delete(dev.waiting, msg.Str("id"))
			d.mu.Unlock()
			if ch != nil {
				ch <- msg
				close(ch)
			}
		case "answer":
			// The person decided an ask on the device; its new shares follow in a shares message.
			if a := d.dropAsk(msg.Str("id")); a != nil {
				word := "declined"
				if truthy(msg.Get("approved")) {
					word = "approved"
				}
				if sid := a.Str("session_id"); sid != "" {
					d.svc.Runtime.Notice(sid, fmt.Sprintf("Your %s %s access to %s.", dev.info.Str("name"), word, askLabel(a)))
				}
				d.svc.Emit("devices", nil)
			}
		case "ping":
			if dev != nil {
				dev.send(O("type", "pong"))
			}
		}
	}
}

func askLabel(a *Obj) string {
	switch a.Str("kind") {
	case "screen":
		return "the screen"
	case "app":
		return a.Str("path")
	case "run":
		return "run " + a.Str("path")
	case "messages":
		if a.Str("access") == "reply" {
			return a.Str("path") + " messages (read and reply)"
		}
		return a.Str("path") + " messages (read only)"
	case "calendar":
		if a.Str("access") == "write" {
			return "the calendar (read and add)"
		}
		return "the calendar (read only)"
	case "contacts":
		return "contacts"
	case "sms":
		return "sending texts"
	case "handover":
		return "preparing things in apps"
	case "use":
		return "using " + a.Str("path")
	case "notifications":
		return "reading notifications"
	}
	if a.Str("access") == "write" {
		return a.Str("path") + " (read and write)"
	}
	return a.Str("path") + " (read only)"
}

// share finds a share by id or name across the devices, with the device it is on.
func (d *Devices) share(ref string) (*Obj, *Obj, error) {
	ref = strings.TrimSpace(ref)
	for _, dv := range d.List() {
		for _, x := range arr(dv.Get("shares")) {
			sh := x.(*Obj)
			if sh.Str("id") == ref || strings.EqualFold(sh.Str("name"), ref) || (sh.Str("path") != "" && sh.Str("path") == ref) {
				return dv, sh, nil
			}
		}
	}
	return nil, nil, fmt.Errorf("No share %q. Use mac_shares or phone_shares to see what is shared; to reach something else, use mac_ask or phone_ask", ref)
}

// anyShare is the first share of a kind and its device, or nils.
func (d *Devices) anyShare(kind string) (*Obj, *Obj) {
	for _, x := range d.List() {
		for _, y := range arr(x.Get("shares")) {
			if o := y.(*Obj); o.Str("kind") == kind {
				return x, o
			}
		}
	}
	return nil, nil
}

// shareOf finds a phone share of a kind: the one named by ref (an id or a name, such as
// "WhatsApp"), or the only one of that kind when ref is empty.
func (d *Devices) shareOf(kind, ref string) (*Obj, *Obj, error) {
	ref = strings.TrimSpace(ref)
	var dv, sh *Obj
	n := 0
	for _, x := range d.List() {
		for _, y := range arr(x.Get("shares")) {
			o := y.(*Obj)
			if o.Str("kind") != kind {
				continue
			}
			if ref == "" || o.Str("id") == ref || strings.EqualFold(o.Str("name"), ref) || strings.EqualFold(o.Str("path"), ref) {
				dv, sh = x, o
				n++
			}
		}
	}
	if n == 0 {
		what := map[string]string{"messages": "messages from that app", "calendar": "its calendar", "contacts": "its contacts", "sms": "sending texts", "handover": "hand-overs"}[kind]
		return nil, nil, fmt.Errorf("No phone shares %s. phone_shares lists what is shared; phone_ask asks for more", what)
	}
	if n > 1 && ref == "" {
		return nil, nil, fmt.Errorf("More than one share fits; say which (phone_shares lists them)")
	}
	return dv, sh, nil
}

// Call sends one call to the device a share is on and waits for its answer.
func (d *Devices) Call(ctx context.Context, shareRef, tool string, args *Obj) (*Obj, error) {
	dv, sh, err := d.share(shareRef)
	if err != nil {
		return nil, err
	}
	return d.call(ctx, dv, sh, tool, args)
}

func (d *Devices) call(ctx context.Context, dv, sh *Obj, tool string, args *Obj) (*Obj, error) {
	d.mu.Lock()
	dev := d.live[dv.Str("id")]
	if dev == nil {
		d.mu.Unlock()
		if dv.Str("kind") == "phone" {
			return nil, fmt.Errorf("%s is offline (no signal, or Bench for Android is not running). Carry on with other steps and say this needs the phone", dv.Str("name"))
		}
		return nil, fmt.Errorf("%s is offline (asleep, or Bench for Mac is closed). Carry on with other steps and say this needs the Mac", dv.Str("name"))
	}
	d.next++
	id := fmt.Sprintf("c%d", d.next)
	ch := make(chan *Obj, 1)
	dev.waiting[id] = ch
	d.mu.Unlock()
	if err := dev.send(O("type", "call", "id", id, "tool", tool, "share", sh.Str("id"), "args", args)); err != nil {
		return nil, fmt.Errorf("%s could not be reached: %v", dv.Str("name"), err)
	}
	timer := time.NewTimer(3 * time.Minute)
	defer timer.Stop()
	select {
	case r, ok := <-ch:
		if !ok {
			return nil, fmt.Errorf("%s went offline before it answered", dv.Str("name"))
		}
		if !truthy(r.Get("ok")) {
			return nil, fmt.Errorf("%s", r.Str("error"))
		}
		return r, nil
	case <-timer.C:
	case <-ctx.Done():
	}
	d.mu.Lock()
	delete(dev.waiting, id)
	d.mu.Unlock()
	return nil, fmt.Errorf("%s did not answer in time", dv.Str("name"))
}

// Rule is what a mac_* call's share says: "allow", "ask", or "" when it names no known share.
func (d *Devices) Rule(tool string, input *Obj) string {
	if strings.HasPrefix(tool, toolPrefix+"phone_") {
		name := strings.TrimPrefix(tool, toolPrefix)
		if name == "phone_confirm" {
			return "ask" // the person answers it, always
		}
		kind := phoneToolKind[name]
		if kind == "" {
			return "allow" // phone_shares, phone_ask, and the hands on an app already opened
		}
		ref := input.Str("share")
		if name == "phone_open" {
			ref = input.Str("app")
		}
		_, sh, err := d.shareOf(kind, ref)
		if err != nil {
			return "allow" // the tool says it is not shared
		}
		return sh.Str("rule")
	}
	if !strings.HasPrefix(tool, toolPrefix+"mac_") {
		return ""
	}
	name := strings.TrimPrefix(tool, toolPrefix)
	if name == "mac_shares" {
		return "allow"
	}
	if name == "mac_ask" {
		// Asking changes nothing: the Mac decides.
		return "allow"
	}
	_, sh, err := d.share(input.Str("share"))
	if err != nil {
		return "allow" // the tool says it does not exist
	}
	return sh.Str("rule")
}

// Note is what a session is told about the devices, or "" when none has linked.
func (d *Devices) Note() string {
	list := d.List()
	if len(list) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString("# The person's other devices\n\nThese devices share part of themselves with you; nothing else on them is reachable. To reach something not shared, ask (mac_ask, phone_ask): the person approves it on the device, so carry on with other steps meanwhile.\n")
	mac, phone := false, false
	for _, dv := range list {
		if dv.Str("kind") == "phone" {
			phone = true
		} else {
			mac = true
		}
	}
	if mac {
		b.WriteString("\nA Mac: use the mac_* tools (mac_shares lists them; mac_list, mac_read and mac_write for folders; mac_screenshot for the screen; mac_open for apps; mac_run for things it can run, such as adb for Android emulators).\n")
	}
	if phone {
		b.WriteString("\nThe person's phone: use the phone_* tools. phone_messages reads new messages from a shared app (from its notifications, so only what arrived recently) and phone_reply answers one through the notification; phone_events and phone_add_event for the calendar; phone_contacts to look up a number; phone_sms sends a text; phone_handover prepares a message, email, event, route, call or link in its app for the person to finish there, the way to do anything an app will not let you finish yourself (a WhatsApp message to someone who has not written, say). Apps shared to use: phone_open opens one on a hidden screen (the person keeps using the phone) and shows it; then phone_tap, phone_type, phone_swipe and phone_key act on it, each answering with the screen after, and phone_done closes it. Before any step that pays, books, orders, sends, posts or deletes, call phone_confirm and wait for the yes. phone_notifications reads the notifications of those apps (a driver arriving, a message). Prefer the lightest tool that does the job. Write replies and texts as the person would: short, in their voice.\n")
	}
	for _, dv := range list {
		state := "online"
		if !truthy(dv.Get("online")) {
			state = "offline"
		}
		fmt.Fprintf(&b, "\n%s (%s):\n", dv.Str("name"), state)
		for _, x := range arr(dv.Get("shares")) {
			sh := x.(*Obj)
			fmt.Fprintf(&b, "- %s: %s, %s%s\n", sh.Str("id"), sh.Str("name"), shareAccess(sh), map[bool]string{true: "", false: " (asks the person first)"}[sh.Str("rule") == "allow"])
		}
	}
	return b.String()
}

func shareAccess(sh *Obj) string {
	switch sh.Str("kind") {
	case "folder":
		if sh.Str("access") == "write" {
			return "folder " + sh.Str("path") + ", read and write"
		}
		return "folder " + sh.Str("path") + ", read only"
	case "screen":
		return "screen, look only"
	case "app":
		return "app, open it"
	case "messages":
		if sh.Str("access") == "reply" {
			return sh.Str("name") + " messages, read and reply"
		}
		return sh.Str("name") + " messages, read only"
	case "calendar":
		if sh.Str("access") == "write" {
			return "calendar, read and add events"
		}
		return "calendar, read only"
	case "contacts":
		return "contacts, look up"
	case "sms":
		return "texts, send"
	case "handover":
		return "hand-overs: prepare things in apps for the person to finish"
	case "use":
		return "the app " + sh.Str("name") + ", to use for the person (phone_open)"
	case "notifications":
		return "notifications of the apps shared to use"
	}
	return "runs " + sh.Str("path")
}

// DevicesReach is the Devices group of Can reach.
func (d *Devices) Reach() []any {
	out := []any{}
	for _, dv := range d.List() {
		n := len(arr(dv.Get("shares")))
		detail := "Offline"
		if truthy(dv.Get("online")) {
			detail = "Online"
		}
		out = append(out, O("key", dv.Str("id"), "name", dv.Str("name"), "detail", fmt.Sprintf("%s · %s", detail, pluralN(n, "share"))))
	}
	return out
}
