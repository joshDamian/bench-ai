package worker

// The simulator kind is tested against a stand-in for xcrun, so the test says what the worker
// does with simctl's answers without needing a Mac.

import (
	"context"
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const fakeList = `== Devices ==
-- iOS 26.5 --
    iPhone 17 Pro (2E328D25-5EB9-483A-AA1E-92359BF41FD7) (Shutdown)
    iPad Pro 11-inch (M4) (11111111-2222-3333-4444-555555555555) (Booted)
-- Unavailable: com.apple.CoreSimulator.SimRuntime.iOS-17-0 --
    iPhone 15 (99999999-8888-7777-6666-555555555555) (Shutdown)
`

// fakeXcode puts a stand-in xcrun first on PATH and returns the folder it wrote.
func fakeXcode(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	list := filepath.Join(dir, "list.txt")
	if err := os.WriteFile(list, []byte(fakeList), 0o644); err != nil {
		t.Fatal(err)
	}
	png := filepath.Join(dir, "shot.png")
	if err := os.WriteFile(png, []byte("\x89PNG fake"), 0o644); err != nil {
		t.Fatal(err)
	}
	script := `#!/bin/sh
case "$2" in
  list) cat "` + list + `" ;;
  boot) echo "Booted" ;;
  bootstatus) exit 0 ;;
  shutdown) echo "Shut down" ;;
  io) cp "` + png + `" "$5" ;;
  install) echo "installed $4" ;;
  launch) echo "launched $4" ;;
  openurl) echo "opened $4" ;;
  *) echo "unknown simctl command $2" >&2; exit 1 ;;
esac
`
	bin := filepath.Join(dir, "bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "xcrun"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+":"+os.Getenv("PATH"))
	return dir
}

func TestMatchDeviceReadsSimctlList(t *testing.T) {
	if got := matchDevice(fakeList, "iPhone 17 Pro"); got != "2E328D25-5EB9-483A-AA1E-92359BF41FD7" {
		t.Fatalf("by name %q", got)
	}
	if got := matchDevice(fakeList, "11111111-2222-3333-4444-555555555555"); got != "11111111-2222-3333-4444-555555555555" {
		t.Fatalf("by udid %q", got)
	}
	if got := matchDevice(fakeList, "iPhone"); got != "2E328D25-5EB9-483A-AA1E-92359BF41FD7" {
		t.Fatalf("first iPhone %q", got)
	}
	if got := matchDevice(fakeList, "Pixel"); got != "" {
		t.Fatalf("a device that is not there matched %q", got)
	}
}

func TestSimulatorListAndBoot(t *testing.T) {
	fakeXcode(t)
	c := testConn(t)

	res, err := c.doJob(context.Background(), &request{ID: "j1", Kind: "ios-sim", Timeout: 10 * time.Second,
		Args: map[string]any{"action": "list"}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(str(res["text"]), "iPhone 17 Pro") || num(res["exit"]) != 0 {
		t.Fatalf("list %v", res)
	}

	res, err = c.doJob(context.Background(), &request{ID: "j2", Kind: "ios-sim", Timeout: 10 * time.Second,
		Args: map[string]any{"action": "boot", "device": "iPad Pro 11-inch (M4)"}})
	if err != nil {
		t.Fatal(err)
	}
	if str(res["service"]) != "11111111-2222-3333-4444-555555555555" || !strings.Contains(str(res["text"]), "iPad Pro") {
		t.Fatalf("boot %v", res)
	}
}

func TestSimulatorScreenshotComesBackAsAnImage(t *testing.T) {
	fakeXcode(t)
	c := testConn(t)
	res, err := c.doJob(context.Background(), &request{ID: "j1", Kind: "ios-sim", Timeout: 10 * time.Second,
		Args: map[string]any{"action": "screenshot", "device": "iPhone 17 Pro"}})
	if err != nil {
		t.Fatal(err)
	}
	data, err := base64.StdEncoding.DecodeString(str(res["image"]))
	if err != nil || !strings.HasPrefix(string(data), "\x89PNG") {
		t.Fatalf("image %q", str(res["image"]))
	}
}

func TestSimulatorNeedsADeviceItCanFind(t *testing.T) {
	fakeXcode(t)
	c := testConn(t)
	_, err := c.doJob(context.Background(), &request{ID: "j1", Kind: "ios-sim", Timeout: 10 * time.Second,
		Args: map[string]any{"action": "boot", "device": "Pixel 9"}})
	if err == nil || !strings.Contains(err.Error(), "Pixel 9") {
		t.Fatalf("err %v", err)
	}
}

func TestSimulatorSaysSoWhenXcodeIsMissing(t *testing.T) {
	t.Setenv("PATH", t.TempDir())
	c := testConn(t)
	_, err := c.doJob(context.Background(), &request{ID: "j1", Kind: "ios-sim", Timeout: 10 * time.Second,
		Args: map[string]any{"action": "list"}})
	if err == nil || !strings.Contains(err.Error(), "Xcode") {
		t.Fatalf("err %v", err)
	}
}
