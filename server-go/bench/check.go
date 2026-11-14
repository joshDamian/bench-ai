package bench

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

// A task's check: a shell command run in its worktree, as src/check.mjs runs it.

const CheckTimeout = 10 * time.Minute
const checkOutputChars = 8000

type CheckResult struct {
	OK       bool
	Code     any // float64 or nil
	Output   string
	Ms       float64
	TimedOut bool
	Fresh    bool
}

// RunCheck runs the command in its own process group, so a timeout stops everything
// it started and nothing else. ctx cancelling is the person stopping it.
func RunCheck(ctx context.Context, command, cwd string, env []string, timeout time.Duration) CheckResult {
	if timeout == 0 {
		timeout = CheckTimeout
	}
	started := time.Now()
	cmd := exec.Command("sh", "-c", command)
	cmd.Dir = cwd
	if env != nil {
		cmd.Env = env
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	cmd.Stderr = pw
	var mu sync.Mutex
	out := ""
	done := make(chan struct{})
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := pr.Read(buf)
			if n > 0 {
				mu.Lock()
				out += string(buf[:n])
				if len(out) > checkOutputChars*2 {
					out = out[len(out)-checkOutputChars:]
				}
				mu.Unlock()
			}
			if err != nil {
				break
			}
		}
		close(done)
	}()
	kill := func() {
		if cmd.Process != nil {
			syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		}
	}
	extra := ""
	var code any
	timedOut := false
	if err := cmd.Start(); err != nil {
		extra = "\n" + err.Error()
		pw.Close()
	} else {
		waited := make(chan error, 1)
		go func() { waited <- cmd.Wait() }()
		timer := time.NewTimer(timeout)
		select {
		case err := <-waited:
			timer.Stop()
			code = exitCode(err, cmd)
		case <-timer.C:
			timedOut = true
			kill()
			code = exitCode(<-waited, cmd)
		case <-ctx.Done():
			kill()
			code = exitCode(<-waited, cmd)
		}
		kill()
		pw.Close()
	}
	<-done
	mu.Lock()
	tail := strings.TrimRight(out+extra, " \t\r\n")
	mu.Unlock()
	output := tail
	if jsLen(tail) > checkOutputChars {
		output = "… (earlier output cut)\n" + tail[len(tail)-checkOutputChars:]
	}
	if timedOut {
		output = fmt.Sprintf("%s\n(stopped after %ds)", output, int(timeout.Seconds()+0.5))
	}
	return CheckResult{OK: code == float64(0) && !timedOut, Code: code, Output: output, Ms: float64(time.Since(started).Milliseconds()), TimedOut: timedOut}
}

// exitCode is what Node's 'close' event reports: the code, or null when a signal ended it.
func exitCode(err error, cmd *exec.Cmd) any {
	if cmd.ProcessState == nil {
		return nil
	}
	if ws, ok := cmd.ProcessState.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
		return nil
	}
	return float64(cmd.ProcessState.ExitCode())
}

// --- suggestions --------------------------------------------------------------------

func readFile(dir, name string) string {
	b, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return ""
	}
	return string(b)
}

func hasFile(dir, name string) bool { return exists(filepath.Join(dir, name)) }

func pkgRunner(dir string) string {
	switch {
	case hasFile(dir, "pnpm-lock.yaml"):
		return "pnpm"
	case hasFile(dir, "yarn.lock"):
		return "yarn"
	case hasFile(dir, "bun.lockb"), hasFile(dir, "bun.lock"):
		return "bun"
	}
	return "npm"
}

// SuggestChecks: the checks a folder suggests, at most six, likeliest first.
func SuggestChecks(dir string) []any {
	out := []any{}
	add := func(command, from string) {
		for _, s := range out {
			if s.(*Obj).Str("command") == command {
				return
			}
		}
		out = append(out, O("command", command, "from", from))
	}
	if pkg := readFile(dir, "package.json"); pkg != "" {
		var p struct {
			Scripts map[string]any `json:"scripts"`
		}
		json.Unmarshal([]byte(pkg), &p)
		pm := pkgRunner(dir)
		for _, s := range []string{"test", "typecheck", "check", "lint", "build"} {
			body, _ := p.Scripts[s].(string)
			if body != "" && !strings.Contains(body, "no test specified") {
				cmd := pm + " run " + s
				if s == "test" && pm != "bun" {
					cmd = pm + " test"
				}
				add(cmd, "package.json · "+s)
			}
		}
	}
	if hasFile(dir, "tsconfig.json") {
		has := false
		for _, s := range out {
			c := s.(*Obj).Str("command")
			if strings.Contains(c, "typecheck") || strings.Contains(c, "tsc") {
				has = true
			}
		}
		if !has {
			add("npx tsc --noEmit", "tsconfig.json")
		}
	}
	if hasFile(dir, "Cargo.toml") {
		add("cargo test", "Cargo.toml")
	}
	if hasFile(dir, "go.mod") {
		add("go test ./...", "go.mod")
	}
	if hasFile(dir, "pyproject.toml") || hasFile(dir, "pytest.ini") || hasFile(dir, "setup.cfg") {
		if hasFile(dir, "pytest.ini") || strings.Contains(readFile(dir, "pyproject.toml"), "pytest") {
			from := "pyproject.toml"
			if hasFile(dir, "pytest.ini") {
				from = "pytest.ini"
			}
			add("pytest", from)
		}
	}
	make := readFile(dir, "Makefile")
	for _, t := range []string{"test", "check"} {
		if regexp.MustCompile(`(?m)^` + t + `:`).MatchString(make) {
			add("make "+t, "Makefile")
		}
	}
	if hasFile(dir, "gradlew") {
		add("./gradlew test", "gradlew")
	}
	if hasFile(dir, "Package.swift") {
		add("swift test", "Package.swift")
	}
	if len(out) > 6 {
		out = out[:6]
	}
	return out
}

// --- what tools leave in /tmp ---------------------------------------------------------

func tmpRoots() []string {
	var roots []string
	if set := os.Getenv("BENCH_TMP_ROOTS"); set != "" {
		for _, r := range strings.Split(set, ":") {
			if r != "" {
				roots = append(roots, r)
			}
		}
	} else {
		roots = []string{"/tmp", os.TempDir()}
	}
	seen := map[string]bool{}
	var out []string
	for _, r := range roots {
		a, _ := filepath.Abs(r)
		if seen[a] {
			continue
		}
		seen[a] = true
		if st, err := os.Stat(a); err == nil && st.IsDir() {
			out = append(out, a)
		}
	}
	return out
}

var sharedTmp = []*regexp.Regexp{regexp.MustCompile(`^\.`), regexp.MustCompile(`^claude-\d+$`), regexp.MustCompile(`^systemd-private-`), regexp.MustCompile(`^snap-private-tmp$`), regexp.MustCompile(`^tmux-\d+$`), regexp.MustCompile(`^ssh-`), regexp.MustCompile(`^node-compile-cache$`), regexp.MustCompile(`^v8-compile-cache`), regexp.MustCompile(`^hsperfdata_`), regexp.MustCompile(`^pulse-`), regexp.MustCompile(`^dbus-`)}

func ownable(name string) bool {
	for _, re := range sharedTmp {
		if re.MatchString(name) {
			return false
		}
	}
	return true
}

func tmpSnapshot() map[string]bool {
	out := map[string]bool{}
	for _, root := range tmpRoots() {
		ents, err := os.ReadDir(root)
		if err != nil {
			continue
		}
		for _, e := range ents {
			if ownable(e.Name()) {
				out[filepath.Join(root, e.Name())] = true
			}
		}
	}
	return out
}

func appeared(before, after map[string]bool) []string {
	var out []string
	for p := range after {
		if !before[p] {
			out = append(out, p)
		}
	}
	return out
}

func removable(p string) bool {
	r, _ := filepath.Abs(p)
	dir := filepath.Dir(r)
	for _, root := range tmpRoots() {
		if root == dir {
			return ownable(filepath.Base(r))
		}
	}
	return false
}

var digits = regexp.MustCompile(`^\d+$`)

// holders: the processes holding anything under each path open; nil when the system
// cannot say.
func holders(paths []string) map[string][]any {
	if !exists("/proc/self/fd") {
		return nil
	}
	out := map[string][]any{}
	for _, p := range paths {
		out[p] = []any{}
	}
	ents, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	under := func(target string) []string {
		var r []string
		for _, p := range paths {
			if target == p || strings.HasPrefix(target, p+"/") {
				r = append(r, p)
			}
		}
		return r
	}
	for _, e := range ents {
		pid := e.Name()
		if !digits.MatchString(pid) {
			continue
		}
		seen := map[string]bool{}
		look := func(link string) {
			t, err := os.Readlink(link)
			if err != nil {
				return
			}
			for _, p := range under(strings.TrimSuffix(t, " (deleted)")) {
				seen[p] = true
			}
		}
		look("/proc/" + pid + "/cwd")
		fds, _ := os.ReadDir("/proc/" + pid + "/fd")
		for _, fd := range fds {
			look("/proc/" + pid + "/fd/" + fd.Name())
		}
		for p := range seen {
			out[p] = append(out[p], num(pid))
		}
	}
	return out
}

func sizeOf(p string) (float64, bool) {
	const capN = 20000
	var bytes int64
	n := 0
	stack := []string{p}
	for len(stack) > 0 {
		cur := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		st, err := os.Lstat(cur)
		if err != nil {
			continue
		}
		bytes += st.Size()
		n++
		if n >= capN {
			return float64(bytes), true
		}
		if st.IsDir() {
			ents, err := os.ReadDir(cur)
			if err == nil {
				for _, e := range ents {
					stack = append(stack, filepath.Join(cur, e.Name()))
				}
			}
		}
	}
	return float64(bytes), false
}
