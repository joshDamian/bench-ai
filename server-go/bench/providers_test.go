package bench

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// The second Claude login is in the catalog, with its own models.
func TestSecondClaudeLoginIsSeeded(t *testing.T) {
	svc := testService(t)
	p := svc.Store.GetProvider("anthropic-claude-code-2")
	if p == nil {
		t.Fatal("the second Claude login is not in the catalog")
	}
	if p.Str("kind") != "claude-code" {
		t.Fatalf("kind %q", p.Str("kind"))
	}
	if !p.Bool("enabled") {
		t.Fatal("the second Claude login is disabled")
	}
	if d := providerConfigDir(p); d != "~/.claude-2" {
		t.Fatalf("config dir %q", d)
	}
	if n := len(svc.Store.ListModels(p.Str("id"))); n != len(svc.Store.ListModels("anthropic-claude-code")) {
		t.Fatalf("%d models, the first login has %d", n, len(svc.Store.ListModels("anthropic-claude-code")))
	}
}

// Each Claude login spawns with its own folder; the first keeps the server's own.
func TestProviderEnvGivesEachClaudeLoginItsOwnFolder(t *testing.T) {
	svc := testService(t)
	envOf := func(id string) []string {
		t.Helper()
		env, err := ProviderEnv(svc.Store.GetProvider(id), nil)
		if err != nil {
			t.Fatal(err)
		}
		return env
	}
	home, _ := os.UserHomeDir()
	want := "CLAUDE_CONFIG_DIR=" + filepath.Join(home, ".claude-2")

	var got string
	for _, e := range envOf("anthropic-claude-code-2") {
		if strings.HasPrefix(e, "CLAUDE_CONFIG_DIR=") {
			got = e
		}
	}
	if got != want {
		t.Fatalf("%q, want %q", got, want)
	}
	for _, e := range envOf("anthropic-claude-code") {
		if strings.HasPrefix(e, "CLAUDE_CONFIG_DIR=") {
			t.Fatalf("the first login carries %q", e)
		}
	}
}

// A second login is ready only once its folder holds a login.
func TestASecondClaudeLoginIsReadyOnlyWhenLoggedIn(t *testing.T) {
	svc := testService(t)
	dir := t.TempDir()
	if _, err := svc.Store.UpdateProvider("anthropic-claude-code-2", O("config", O("claudeConfigDir", dir))); err != nil {
		t.Fatal(err)
	}
	p := svc.Store.GetProvider("anthropic-claude-code-2")
	if ready, reason := ProviderReady(p); ready {
		t.Fatalf("ready without credentials: %v", reason)
	} else if !strings.Contains(str(reason), "/login") {
		t.Fatalf("reason %q does not say how to log in", str(reason))
	}
	if err := os.WriteFile(filepath.Join(dir, ".credentials.json"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if ready, reason := ProviderReady(svc.Store.GetProvider("anthropic-claude-code-2")); !ready {
		t.Fatalf("not ready after login: %v", reason)
	}
}

// Either Claude login can be the fallback, as long as it is not the one sessions run on.
func TestASecondClaudeLoginCanBeTheFallback(t *testing.T) {
	svc := testService(t)
	if _, err := svc.SetDefaults(O("limits", O("fallback", O("providerId", "anthropic-claude-code")))); err == nil {
		t.Fatal("the default provider was accepted as its own fallback")
	} else if !strings.Contains(err.Error(), "different provider") {
		t.Fatalf("error %q", err.Error())
	}
	got, err := svc.SetDefaults(O("limits", O("fallback", O("providerId", "anthropic-claude-code-2", "modelId", "anthropic-2:claude-opus-5-5"))))
	if err != nil {
		t.Fatal(err)
	}
	fb := got.ObjAt("limits").ObjAt("fallback")
	if fb == nil || fb.Str("providerId") != "anthropic-claude-code-2" {
		t.Fatalf("fallback %s", ToJSON(got.Get("limits")))
	}
}

// A limit on the first login hands the work to a session running the second, in its folder.
func TestALimitHandsWorkToTheSecondClaudeLogin(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("reads the spawned process's environment from /proc")
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("BENCH_CLAUDE_BIN", fakeClaude(t))
	if err := os.MkdirAll(filepath.Join(home, ".claude-2"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, ".claude-2", ".credentials.json"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}

	svc := testService(t)
	p, err := svc.AddProject(O("path", t.TempDir(), "init", true))
	if err != nil {
		t.Fatal(err)
	}
	s, err := svc.CreateSession(O("projectId", p.Str("id"), "title", "the work"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.SetDefaults(O("limits", O("fallback", O("providerId", "anthropic-claude-code-2")))); err != nil {
		t.Fatal(err)
	}

	svc.Resumer.limitHit(LimitEvent{SessionID: s.Str("id"), Message: "You've hit your session limit"})

	pause := svc.Store.GetSession(s.Str("id")).ObjAt("pause")
	if pause == nil {
		t.Fatal("the session was not paused")
	}
	next := pause.Str("handed_to")
	if next == "" {
		t.Fatalf("no handover: %s", ToJSON(pause))
	}
	t.Cleanup(func() { svc.Runtime.Close(next) })
	n := svc.Store.GetSession(next)
	if n == nil {
		t.Fatalf("the new session %s is not stored", next)
	}
	if n.Str("provider_id") != "anthropic-claude-code-2" {
		t.Fatalf("the work was handed to %s", n.Str("provider_id"))
	}

	pid := 0
	svc.Runtime.mu.Lock()
	if l := svc.Runtime.live[next]; l != nil && l.proc != nil {
		pid = l.proc.Pid
	}
	svc.Runtime.mu.Unlock()
	if pid == 0 {
		t.Fatal("the new session has no process")
	}
	want := "CLAUDE_CONFIG_DIR=" + filepath.Join(home, ".claude-2")
	var seen []string
	// /proc can show an empty environment for a moment right after exec.
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		b, err := os.ReadFile(fmt.Sprintf("/proc/%d/environ", pid))
		if err != nil {
			t.Fatal(err)
		}
		seen = seen[:0]
		for _, e := range strings.Split(string(b), "\x00") {
			if e == want {
				return
			}
			if strings.Contains(e, "CLAUDE_CONFIG_DIR") || strings.HasPrefix(e, "BENCH_PROVIDER=") || strings.HasPrefix(e, "HOME=") {
				seen = append(seen, e)
			}
		}
	}
	t.Fatalf("the new session's process does not carry %q; it has %v (provider %s, model %s)", want, seen, n.Str("provider_id"), n.Str("model_id"))
}

// fakeClaude is a stand-in Claude Code that reads its input and never answers.
func fakeClaude(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "claude")
	if err := os.WriteFile(path, []byte("#!/bin/sh\nwhile read line; do :; done\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	return path
}

// A conversation started on one Claude login is copied into the other login's folder.
func TestCarryTranscriptCopiesAConversationToAnotherLogin(t *testing.T) {
	first, second := t.TempDir(), t.TempDir()
	srcDir := filepath.Join(first, "projects", "-home-dev-bench-projects-research")
	if err := os.MkdirAll(srcDir, 0o700); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(srcDir, "abc-123.jsonl")
	if err := os.WriteFile(src, []byte("{\"type\":\"user\"}\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	carryTranscript(second, "abc-123", []string{first})
	dst := filepath.Join(second, "projects", "-home-dev-bench-projects-research", "abc-123.jsonl")
	b, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("no copy: %v", err)
	}
	if string(b) != "{\"type\":\"user\"}\n" {
		t.Fatalf("copy reads %q", b)
	}
	if st, _ := os.Stat(dst); st.Mode().Perm() != 0o600 {
		t.Fatalf("copy mode %v", st.Mode().Perm())
	}

	// A later copy does not overwrite what the second login itself has written.
	if err := os.WriteFile(dst, []byte("grown\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	carryTranscript(second, "abc-123", []string{first})
	if b, _ := os.ReadFile(dst); string(b) != "grown\n" {
		t.Fatalf("the copy was overwritten: %q", b)
	}

	// An unknown conversation copies nothing.
	carryTranscript(second, "not-there", []string{first})
	if _, err := os.Stat(filepath.Join(second, "projects", "-home-dev-bench-projects-research", "not-there.jsonl")); !os.IsNotExist(err) {
		t.Fatal("an unknown conversation was copied")
	}
}
