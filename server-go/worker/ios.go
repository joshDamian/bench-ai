package worker

// The iOS simulator on a Mac: a service the worker holds for the pool. A boot registers the
// simulator as a service with its udid, so later jobs that name it go back to the same worker.
// The commands are xcrun simctl's; nothing here is specific to one app.

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// iosSim is the kind name for an iOS simulator service.
const iosSim = "ios-sim"

// runIOSSim does one simulator action and reports what happened.
func (c *Conn) runIOSSim(ctx context.Context, r *request) (map[string]any, error) {
	if _, err := exec.LookPath("xcrun"); err != nil {
		return nil, fmt.Errorf("this machine has no Xcode command line tools")
	}
	action := str(r.Args["action"])
	if action == "" {
		action = "list"
	}
	if action == "list" {
		out, code, err := runCmd(ctx, "xcrun", "simctl", "list", "devices", "available")
		return map[string]any{"text": out, "exit": code}, err
	}
	udid, err := c.simDevice(ctx, r)
	if err != nil {
		return nil, err
	}
	switch action {
	case "boot":
		out, code, err := runCmd(ctx, "xcrun", "simctl", "boot", udid)
		if err != nil && !strings.Contains(out, "already booted") {
			return nil, err
		}
		_ = code
		// Wait for the runtime to answer before anyone takes a picture of it.
		for i := 0; i < 60; i++ {
			_, _, err := runCmd(ctx, "xcrun", "simctl", "bootstatus", udid, "-b")
			if err == nil {
				break
			}
			time.Sleep(time.Second)
		}
		name := simName(ctx, udid)
		c.registerService(udid, iosSim, "booted", nil, map[string]any{"device": name})
		return map[string]any{"text": "Booted " + name + " (" + udid + ")", "exit": 0,
			"service": udid, "detail": map[string]any{"device": name}}, nil
	case "shutdown":
		out, code, err := runCmd(ctx, "xcrun", "simctl", "shutdown", udid)
		c.registerService(udid, iosSim, "stopped", nil, nil)
		return map[string]any{"text": out, "exit": code}, err
	case "install":
		app := str(r.Args["app"])
		if app == "" {
			return nil, fmt.Errorf("install needs the app's path")
		}
		out, code, err := runCmd(ctx, "xcrun", "simctl", "install", udid, app)
		return map[string]any{"text": out, "exit": code}, err
	case "launch":
		bundle := str(r.Args["bundle"])
		if bundle == "" {
			return nil, fmt.Errorf("launch needs the app's bundle id")
		}
		out, code, err := runCmd(ctx, "xcrun", "simctl", "launch", udid, bundle)
		return map[string]any{"text": out, "exit": code}, err
	case "url":
		page := str(r.Args["url"])
		if page == "" {
			return nil, fmt.Errorf("url needs a URL")
		}
		out, code, err := runCmd(ctx, "xcrun", "simctl", "openurl", udid, page)
		return map[string]any{"text": out, "exit": code}, err
	case "screenshot":
		data, err := c.simScreenshot(ctx, udid)
		if err != nil {
			return nil, err
		}
		return map[string]any{"image": base64.StdEncoding.EncodeToString(data)}, nil
	case "logs":
		last := str(r.Args["last"])
		if last == "" {
			last = "2m"
		}
		out, code, err := runCmd(ctx, "xcrun", "simctl", "spawn", udid, "log", "show", "--last", last, "--style", "compact")
		return map[string]any{"text": tailString(out, 200_000), "exit": code}, err
	}
	return nil, fmt.Errorf("unknown simulator action %q", action)
}

// simDevice finds the simulator a job names: a service id, a device name or udid, else the
// first available iPhone.
func (c *Conn) simDevice(ctx context.Context, r *request) (string, error) {
	for _, ref := range []string{str(r.Args["service"]), str(r.Args["device"])} {
		if ref == "" {
			continue
		}
		out, _, err := runCmd(ctx, "xcrun", "simctl", "list", "devices", "available")
		if err != nil {
			return "", err
		}
		if id := matchDevice(out, ref); id != "" {
			return id, nil
		}
		return "", fmt.Errorf("no available simulator matches %q", ref)
	}
	out, _, err := runCmd(ctx, "xcrun", "simctl", "list", "devices", "available")
	if err != nil {
		return "", err
	}
	if id := matchDevice(out, "iPhone"); id != "" {
		return id, nil
	}
	return "", fmt.Errorf("no iPhone simulator is available")
}

// matchDevice finds a udid in `simctl list devices` output by name or udid.
func matchDevice(out, ref string) string {
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasSuffix(line, ")") || !strings.Contains(line, "(") {
			continue
		}
		// "<name> (<udid>) (<state>)": the state is last, so a name may hold parentheses of
		// its own, as "iPad Pro 11-inch (M4)" does.
		stateAt := strings.LastIndex(line, "(")
		rest := strings.TrimSpace(line[:stateAt])
		if !strings.HasSuffix(rest, ")") {
			continue
		}
		open := strings.LastIndex(rest, "(")
		if open <= 0 {
			continue
		}
		udid := rest[open+1 : len(rest)-1]
		name := strings.TrimSpace(rest[:open])
		if udid == ref || strings.EqualFold(name, ref) || (ref == "iPhone" && strings.HasPrefix(name, "iPhone")) {
			return udid
		}
	}
	return ""
}

func simName(ctx context.Context, udid string) string {
	out, _, err := runCmd(ctx, "xcrun", "simctl", "list", "devices", "available")
	if err != nil {
		return udid
	}
	for _, line := range strings.Split(out, "\n") {
		if strings.Contains(line, udid) {
			if i := strings.Index(line, "("+udid+")"); i > 0 {
				return strings.TrimSpace(line[:i])
			}
		}
	}
	return udid
}

func (c *Conn) simScreenshot(ctx context.Context, udid string) ([]byte, error) {
	dir, err := os.MkdirTemp(c.cfg.Cache, "shot-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	full := filepath.Join(dir, "shot.png")
	out, code, err := runCmd(ctx, "xcrun", "simctl", "io", udid, "screenshot", full)
	if err != nil {
		return nil, fmt.Errorf("the screenshot failed: %v %.200s", err, out)
	}
	_ = code
	return os.ReadFile(full)
}

// registerService tells the server a service is held, or that it stopped.
func (c *Conn) registerService(id, kind, state string, ports []any, detail map[string]any) {
	c.send(map[string]any{"type": "service", "id": id, "kind": kind, "state": state, "ports": ports, "detail": detail})
}

// stopService ends a service the server no longer wants, in the background so the link keeps
// reading.
func (c *Conn) stopService(id, kind string) {
	if kind != iosSim {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		out, _, err := runCmd(ctx, "xcrun", "simctl", "shutdown", id)
		if err != nil {
			c.log("service %s: %v %.200s", id, err, out)
		}
		c.registerService(id, iosSim, "stopped", nil, nil)
	}()
}

// runCmd runs one command and returns its combined output and exit code. A command that
// cannot start is an error; a command that exits non-zero is a result, with its code.
func runCmd(ctx context.Context, name string, args ...string) (string, int, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	code := 0
	if cmd.ProcessState != nil {
		code = cmd.ProcessState.ExitCode()
	}
	if err != nil && code == 0 {
		return string(out), 0, err
	}
	return string(out), code, nil
}

func tailString(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}
