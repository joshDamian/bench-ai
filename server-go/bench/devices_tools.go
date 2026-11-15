package bench

// The mac_* tools: how a session uses what a device shares (devices.go). Each call goes to the
// device and back; the device checks the share again before it does anything.

import (
	"context"
	"fmt"
	"strings"
)

func macSchema(props *Obj, required ...string) *Obj {
	req := []any{}
	for _, r := range required {
		req = append(req, r)
	}
	return O("$schema", "http://json-schema.org/draft-07/schema#", "type", "object", "properties", props, "required", req)
}

func strProp(desc string) *Obj { return O("type", "string", "description", desc) }

var shareProp = strProp("The share's id or name, from mac_shares")

// macToolList is the mac_* tools as tools/list gives them.
func macToolList() []any {
	tool := func(name, desc string, readOnly bool, schema *Obj) *Obj {
		return O("name", name, "description", desc, "inputSchema", schema,
			"annotations", O("readOnlyHint", readOnly, "destructiveHint", false), "execution", O("taskSupport", "forbidden"))
	}
	return []any{
		tool("mac_shares", "Lists the person's linked devices (their Mac), whether each is online, and what each shares: folders, apps, the screen, things it can run.", true, macSchema(O())),
		tool("mac_list", "Lists a folder inside a shared folder on the Mac.", true, macSchema(O("share", shareProp, "path", strProp("A path inside the share; empty for its top")), "share")),
		tool("mac_read", "Reads a text file inside a shared folder on the Mac (up to 256 KB).", true, macSchema(O("share", shareProp, "path", strProp("A path inside the share")), "share", "path")),
		tool("mac_write", "Writes a text file inside a shared folder the Mac shares read and write.", false, macSchema(O("share", shareProp, "path", strProp("A path inside the share"), "content", strProp("The whole file")), "share", "path", "content")),
		tool("mac_screenshot", "Takes a screenshot of the Mac's screen, when the screen is shared.", true, macSchema(O("share", shareProp), "share")),
		tool("mac_open", "Opens a shared app on the Mac, optionally with a file from a shared folder.", false, macSchema(O("share", shareProp, "file", strProp("Optional: a full path of a file in a shared folder")), "share")),
		tool("mac_run", "Runs a shared command on the Mac (such as adb for Android emulators) with arguments, and returns its output. Not a shell: arguments are passed as they are.", false,
			macSchema(O("share", shareProp, "args", O("type", "array", "items", O("type", "string"), "description", "Arguments, e.g. [\"devices\"]")), "share")),
		tool("mac_ask", "Asks the person's device to share something more. They approve or decline it on the device itself, later if it is asleep; carry on with other steps meanwhile.", false,
			macSchema(O("device", strProp("The device's name or id; empty for the only one"), "kind", O("type", "string", "enum", []any{"folder", "app", "screen", "run"}),
				"path", strProp("The folder, app or command (e.g. ~/Projects/novara, Xcode, adb)"), "access", O("type", "string", "enum", []any{"read", "write"}, "description", "For a folder"),
				"why", strProp("One line on why, shown on the device")), "kind", "why")),
	}
}

func macTools(svc *Service, sessionID string) map[string]func(context.Context, *Obj) *Obj {
	d := svc.Devices()
	call := func(tool string) func(context.Context, *Obj) *Obj {
		return func(ctx context.Context, a *Obj) *Obj {
			r, err := d.Call(ctx, a.Str("share"), tool, a)
			if err != nil {
				return toolErr(err.Error())
			}
			if img := r.Str("image"); img != "" {
				return O("content", []any{O("type", "image", "data", img, "mimeType", "image/png")})
			}
			return toolOK(r.Str("text"))
		}
	}
	return map[string]func(context.Context, *Obj) *Obj{
		"mac_shares": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			if n := d.Note(); n != "" {
				return n, nil
			}
			return "No device has linked yet. The person links their Mac from Bench for Mac.", nil
		}),
		"mac_list":       call("list"),
		"mac_read":       call("read"),
		"mac_write":      call("write"),
		"mac_screenshot": call("screenshot"),
		"mac_open":       call("open"),
		"mac_run":        call("run"),
		"mac_ask": toolFn(func(ctx context.Context, a *Obj) (string, error) {
			list := d.List()
			var dev *Obj
			for _, x := range list {
				if a.Str("device") == "" || x.Str("id") == a.Str("device") || strings.EqualFold(x.Str("name"), a.Str("device")) {
					if dev != nil && a.Str("device") == "" {
						return "", fmt.Errorf("Say which device: %d are linked", len(list))
					}
					dev = x
				}
			}
			if dev == nil {
				return "", fmt.Errorf("No such device. mac_shares lists them")
			}
			ask, err := d.Ask(dev.Str("id"), a, sessionID)
			if err != nil {
				return "", err
			}
			where := "It is on the Mac now."
			if !truthy(dev.Get("online")) {
				where = dev.Str("name") + " is offline; the request waits there."
			}
			return fmt.Sprintf("Asked %s for %s. %s The person approves or declines it on the device; you are told here when they do. Carry on with other steps.", dev.Str("name"), askLabel(ask), where), nil
		}),
	}
}

// withDevices adds the mac_* tools to a session's bench server once a device has linked, and
// the meeting_* tools when the server can take part in calls.
func withDevices(svc *Service, sessionID string, m *MCPServer) *MCPServer {
	devices := len(svc.Devices().List()) > 0
	calls := truthy(svc.Meeting().Ready().Get("audio"))
	if !devices && !calls {
		return m
	}
	extraList := []any{}
	extra := map[string]func(context.Context, *Obj) *Obj{}
	if devices {
		extraList = append(extraList, macToolList()...)
		for k, v := range macTools(svc, sessionID) {
			extra[k] = v
		}
	}
	if calls {
		extraList = append(extraList, meetingToolList()...)
		extraList = append(extraList, presentToolList()...)
		for k, v := range meetingTools(svc, sessionID) {
			extra[k] = v
		}
		for k, v := range presentTools(svc, sessionID) {
			extra[k] = v
		}
	}
	man := m.Manifest.Clone()
	list := man.ObjAt("toolsListResult").Clone()
	list.Set("tools", append(append([]any{}, arr(list.Get("tools"))...), extraList...))
	man.Set("toolsListResult", list)
	cfg := m.Config.Clone()
	names := append([]any{}, arr(cfg.Get("tools"))...)
	tools := map[string]func(context.Context, *Obj) *Obj{}
	for k, v := range m.Tools {
		tools[k] = v
	}
	for k, v := range extra {
		tools[k] = v
		names = append(names, O("name", k))
	}
	cfg.Set("tools", names)
	return &MCPServer{Manifest: man, Config: cfg, Tools: tools}
}
