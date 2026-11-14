package bench

import (
	"context"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// What Bench costs the machine (src/metrics.mjs), and Settings → Update (src/update.mjs).

func runCmd(timeout time.Duration, name string, args ...string) (string, int, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.Output()
	pid := 0
	if cmd.Process != nil {
		pid = cmd.Process.Pid
	}
	return string(out), pid, err == nil
}

var etimeRe = regexp.MustCompile(`^(?:(\d+)-)?(?:(\d+):)?(\d+):(\d+)$`)

func parseEtime(s string) any {
	m := etimeRe.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil {
		return nil
	}
	n := func(x string) float64 { f, _ := strconv.ParseFloat(x, 64); return f }
	return (((n(m[1])*24+n(m[2]))*60+n(m[3]))*60 + n(m[4])) * 1000
}

type psRow struct {
	pid, ppid int
	rssKb     float64
	pcpu      float64
	etimeMs   any
}

var psLine = regexp.MustCompile(`^\s*(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+(\S+)\s*(.*)$`)

func parsePs(stdout string) []psRow {
	var out []psRow
	for _, line := range strings.Split(stdout, "\n") {
		m := psLine.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		pid, _ := strconv.Atoi(m[1])
		ppid, _ := strconv.Atoi(m[2])
		rss, _ := strconv.ParseFloat(m[3], 64)
		cpu, _ := strconv.ParseFloat(m[4], 64)
		out = append(out, psRow{pid, ppid, rss, cpu, parseEtime(m[5])})
	}
	return out
}

var cgroupOnce sync.Once
var cgroupPath string

func cgroupDir() string {
	cgroupOnce.Do(func() {
		b, err := os.ReadFile("/proc/self/cgroup")
		if err != nil {
			return
		}
		m := regexp.MustCompile(`(?m)^0::(\S+)$`).FindStringSubmatch(string(b))
		if m == nil {
			return
		}
		dir := "/sys/fs/cgroup"
		if m[1] != "/" {
			dir = filepath.Join(dir, m[1])
		}
		if exists(filepath.Join(dir, "memory.current")) {
			cgroupPath = dir
		}
	})
	return cgroupPath
}

func readCgroup(dir string) *Obj {
	rd := func(f string) (string, bool) {
		b, err := os.ReadFile(filepath.Join(dir, f))
		if err != nil {
			return "", false
		}
		return strings.TrimSpace(string(b)), true
	}
	cur, ok := rd("memory.current")
	if !ok {
		return nil
	}
	n := func(v string, ok bool) any {
		if !ok || v == "max" {
			return nil
		}
		f, _ := strconv.ParseFloat(v, 64)
		return f
	}
	peak, okp := rd("memory.peak")
	max, okm := rd("memory.max")
	pids, okd := rd("pids.current")
	c, _ := strconv.ParseFloat(cur, 64)
	return O("memCurrent", c, "memPeak", n(peak, okp), "memMax", n(max, okm), "pids", n(pids, okd))
}

func memInfo() (total, free float64) {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) < 2 {
			continue
		}
		v, _ := strconv.ParseFloat(f[1], 64)
		switch f[0] {
		case "MemTotal:":
			total = v * 1024
		case "MemFree:":
			free = v * 1024
		}
	}
	return
}

func loadAvg() []any {
	b, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return []any{0.0, 0.0, 0.0}
	}
	f := strings.Fields(string(b))
	out := []any{}
	for i := 0; i < 3 && i < len(f); i++ {
		v, _ := strconv.ParseFloat(f[i], 64)
		out = append(out, v)
	}
	return out
}

func uptimeMs() float64 {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0
	}
	v, _ := strconv.ParseFloat(strings.Fields(string(b))[0], 64)
	return v * 1000
}

func machineInfo(diskPath string) *Obj {
	var diskTotal, diskFree any
	var st syscall.Statfs_t
	if syscall.Statfs(diskPath, &st) == nil {
		diskTotal = float64(st.Bsize) * float64(st.Blocks)
		diskFree = float64(st.Bsize) * float64(st.Bavail)
	}
	host, _ := os.Hostname()
	total, free := memInfo()
	return O("hostname", host, "platform", runtime.GOOS, "cpus", float64(runtime.NumCPU()), "loadavg", loadAvg(), "uptimeMs", uptimeMs(),
		"memTotal", total, "memFree", free, "diskPath", diskPath, "diskTotal", diskTotal, "diskFree", diskFree)
}

type MetricsInput struct {
	Root       string
	Sessions   []*Obj
	Projects   []*Obj
	Tasks      []*Obj
	ClaudePids map[int]string
	Terminals  []any
	Storage    []*Obj
}

type procGroup struct {
	o    *Obj
	pool []psRow
}

func procRows(procs []psRow, in MetricsInput, rootPid int) []*Obj {
	byParent := map[int][]psRow{}
	for _, p := range procs {
		byParent[p.ppid] = append(byParent[p.ppid], p)
	}
	termByPid := map[int]string{}
	for _, x := range in.Terminals {
		t := x.(*Obj)
		if t.Num("pid") > 0 {
			termByPid[int(t.Num("pid"))] = t.Str("key")
		}
	}
	groups := map[string]*procGroup{}
	var order []string
	groupOf := func(key string, make func() *Obj) *procGroup {
		g := groups[key]
		if g == nil {
			g = &procGroup{o: make()}
			groups[key] = g
			order = append(order, key)
		}
		return g
	}
	findSession := func(id string) *Obj {
		for _, s := range in.Sessions {
			if s.Str("id") == id {
				return s
			}
		}
		return nil
	}
	projectName := func(id string) string {
		for _, p := range in.Projects {
			if p.Str("id") == id {
				return p.Str("name")
			}
		}
		return ""
	}
	terminalWhere := func(key string) *Obj {
		i := strings.Index(key, ":")
		kind, id := key, ""
		if i >= 0 {
			kind, id = key[:i], key[i+1:]
		}
		switch kind {
		case "session":
			s := findSession(id)
			label := "Session shell"
			var tid, pid any
			if s != nil {
				label = firstNonEmpty(s.Str("title"), label)
				tid, pid = s.Get("task_id"), s.Get("project_id")
			}
			return O("sessionId", id, "taskId", tid, "projectId", pid, "label", label)
		case "task":
			label := "Task shell"
			var pid any
			for _, t := range in.Tasks {
				if t.Str("id") == id {
					label = t.Str("title") + " — shell"
					pid = t.Get("project_id")
				}
			}
			return O("sessionId", nil, "taskId", id, "projectId", pid, "label", label)
		case "project":
			label := "Project shell"
			if n := projectName(id); n != "" {
				label = n + " — shell"
			}
			return O("sessionId", nil, "taskId", nil, "projectId", id, "label", label)
		}
		return O("sessionId", nil, "taskId", nil, "projectId", nil, "label", "Shell")
	}
	var walk func(pid int, owner string)
	walk = func(pid int, owner string) {
		for _, child := range byParent[pid] {
			key := owner
			if child.pid != rootPid {
				if sid, ok := in.ClaudePids[child.pid]; ok {
					s := findSession(sid)
					key = "session:" + sid
					groupOf(key, func() *Obj {
						var tid, pid any
						label, sub := "Session", ""
						if s != nil {
							tid, pid = s.Get("task_id"), s.Get("project_id")
							label = firstNonEmpty(s.Str("title"), label)
							if s.Str("project_id") == "" {
								sub = "Ask Bench"
							} else {
								sub = projectName(s.Str("project_id"))
							}
						}
						return O("kind", "claude", "sessionId", sid, "taskId", tid, "projectId", pid, "label", label, "sub", sub)
					})
				} else if tk, ok := termByPid[child.pid]; ok {
					w := terminalWhere(tk)
					key = "term:" + tk
					groupOf(key, func() *Obj { return O("kind", "terminal").Merge(w).Set("sub", projectName(w.Str("projectId"))) })
				} else if owner == "" {
					key = "other"
				}
			}
			if key != "" {
				g := groupOf(key, func() *Obj {
					return O("kind", "other", "sessionId", nil, "taskId", nil, "projectId", nil, "label", "Other Bench processes", "sub", "")
				})
				g.pool = append(g.pool, child)
			}
			walk(child.pid, key)
		}
	}
	walk(rootPid, "")
	for _, p := range procs {
		if p.pid == rootPid {
			g := groupOf("server", func() *Obj {
				return O("kind", "server", "sessionId", nil, "taskId", nil, "projectId", nil, "label", "bench server", "sub", "")
			})
			g.pool = append(g.pool, p)
			break
		}
	}
	var out []*Obj
	for _, k := range order {
		g := groups[k]
		var rss, cpu, et float64
		for _, p := range g.pool {
			rss += p.rssKb
			cpu += p.pcpu
			if e, ok := p.etimeMs.(float64); ok && e > et {
				et = e
			}
		}
		var pid, etv any
		if len(g.pool) > 0 {
			pid = float64(g.pool[0].pid)
		}
		if et > 0 {
			etv = et
		}
		out = append(out, O("kind", g.o.Get("kind"), "sessionId", g.o.Get("sessionId"), "taskId", g.o.Get("taskId"), "projectId", g.o.Get("projectId"), "label", g.o.Get("label"), "sub", g.o.Get("sub"),
			"count", float64(len(g.pool)), "pid", pid, "rssKb", rss, "pcpu", math.Round(cpu*10)/10, "etimeMs", etv))
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Num("rssKb") > out[j].Num("rssKb") })
	return out
}

var duCache = struct {
	sync.Mutex
	m map[string]*duEntry
}{m: map[string]*duEntry{}}

type duEntry struct {
	bytes   any
	at      time.Time
	running bool
}

func du(dir string) any {
	duCache.Lock()
	hit := duCache.m[dir]
	if hit != nil && (time.Since(hit.at) < time.Minute || hit.running) {
		b := hit.bytes
		duCache.Unlock()
		return b
	}
	var prev any
	var at time.Time
	if hit != nil {
		prev, at = hit.bytes, hit.at
	}
	duCache.m[dir] = &duEntry{bytes: prev, at: at, running: true}
	duCache.Unlock()
	out, _, ok := runCmd(8*time.Second, "du", "-sk", dir)
	bytes := prev
	if m := regexp.MustCompile(`\d+`).FindString(out); ok && m != "" {
		kb, _ := strconv.ParseFloat(m, 64)
		bytes = kb * 1024
	}
	duCache.Lock()
	duCache.m[dir] = &duEntry{bytes: bytes, at: time.Now()}
	duCache.Unlock()
	return bytes
}

func Collect(in MetricsInput) *Obj {
	machine := machineInfo(in.Root)
	stdout, psPid, _ := runCmd(4*time.Second, "ps", "-Ao", "pid=,ppid=,rss=,pcpu=,etime=,command=")
	var procs []psRow
	for _, p := range parsePs(stdout) {
		if p.pid != psPid {
			procs = append(procs, p)
		}
	}
	rows := procRows(procs, in, os.Getpid())
	var rss, accounted float64
	for _, r := range rows {
		rss += r.Num("rssKb") * 1024
		if r.Str("kind") != "other" {
			accounted += r.Num("count")
		}
	}
	var bench *Obj
	if cg := cgroupDir(); cg != "" {
		if c := readCgroup(cg); c != nil {
			bench = c.Set("source", "cgroup")
		}
	}
	if bench == nil {
		bench = O("memCurrent", rss, "memPeak", nil, "memMax", nil, "pids", accounted, "source", "rss")
	}
	entries := []any{}
	for _, t := range in.Storage {
		b := du(t.Str("path"))
		if b != nil || exists(t.Str("path")) {
			entries = append(entries, O("name", t.Str("name"), "path", t.Str("path"), "note", t.Str("note"), "bytes", b))
		}
	}
	procsAny := make([]any, len(rows))
	for i, r := range rows {
		procsAny[i] = r
	}
	return O("at", nowMs(), "host", machine,
		"machine", O("memTotal", machine.Get("memTotal"), "memFree", machine.Get("memFree"), "diskTotal", machine.Get("diskTotal"), "diskFree", machine.Get("diskFree"), "diskPath", machine.Get("diskPath")),
		"bench", bench.Clone().Set("procs", accounted),
		"procs", procsAny,
		"storage", O("entries", entries, "diskPath", machine.Get("diskPath"), "diskTotal", machine.Get("diskTotal"), "diskFree", machine.Get("diskFree")))
}

// --- update -------------------------------------------------------------------------

var doneRe = regexp.MustCompile(`(?m)^bench-update: exit (\d+)$`)

func ReleaseInfo(appRoot string) *Obj {
	o, _ := ParseJSON(readFile(appRoot, ".bench-release.json")).(*Obj)
	return o
}

type UpdateOpts struct {
	AppRoot, LogFile, DataRoot string
	Supervised                 any
}

func lastRun(logFile string) any {
	b, err := os.ReadFile(logFile)
	if err != nil {
		return nil
	}
	st, _ := os.Stat(logFile)
	text := string(b)
	m := doneRe.FindStringSubmatch(text)
	var exit any
	if m != nil {
		v, _ := strconv.ParseFloat(m[1], 64)
		exit = v
	}
	lines := strings.Split(strings.TrimRight(doneRe.ReplaceAllString(text, ""), " \t\r\n"), "\n")
	if len(lines) > 30 {
		lines = lines[len(lines)-30:]
	}
	return O("running", m == nil && time.Since(st.ModTime()) < 10*time.Minute, "exit", exit, "at", isoTime(st.ModTime()), "tail", strings.Join(lines, "\n"))
}

func UpdateStatus(o UpdateOpts) *Obj {
	installed := ReleaseInfo(o.AppRoot)
	last := lastRun(o.LogFile)
	var source any
	if installed != nil {
		source = or(installed.Get("source"), nil)
	}
	out := O("installed", installed, "source", source, "sourceCommit", nil, "sourceDirty", false, "newer", nil, "canUpdate", false, "reason", nil, "last", last)
	if installed == nil {
		if exists(filepath.Join(o.AppRoot, ".git")) {
			out.Set("reason", "Bench is running from its checkout, not an installed copy, so there is nothing to update: restart it to run the latest code.")
		} else {
			out.Set("reason", "This copy was installed before Bench recorded where it came from. Run bin/install --service once in the checkout; after that, Update works from here.")
		}
		return out
	}
	src := installed.Str("source")
	if !exists(filepath.Join(src, "server-go", "install")) {
		out.Set("reason", fmt.Sprintf("The checkout it was installed from, %s, is gone or no longer has server-go/install.", src))
		return out
	}
	if c := RevParse(src, ""); c != "" {
		out.Set("sourceCommit", c)
		st, _ := Git(src, []string{"status", "--porcelain"})
		out.Set("sourceDirty", strings.TrimSpace(st) != "")
		if ic := installed.Str("commit"); ic != "" {
			n, _ := Git(src, []string{"rev-list", "--count", ic + ".." + c})
			out.Set("newer", num(n))
		}
	}
	lo, _ := last.(*Obj)
	switch {
	case !truthy(o.Supervised):
		out.Set("reason", "Not running as a service, so nothing would start the updated server. Run server-go/install --service on the machine.")
	case runtime.GOOS == "linux" && exec.Command("systemd-run", "--version").Run() != nil:
		out.Set("reason", "systemd-run is not available, so the installer could not outlive the restart.")
	case lo != nil && lo.Bool("running"):
		out.Set("reason", "An update is already running.")
	}
	out.Set("canUpdate", out.Get("reason") == nil)
	return out
}

func StartUpdate(o UpdateOpts) (*Obj, error) {
	st := UpdateStatus(o)
	if !st.Bool("canUpdate") || st.Str("source") == "" {
		msg := firstNonEmpty(st.Str("reason"), "Cannot update from here")
		return nil, fail(msg, 409)
	}
	src := st.Str("source")
	os.MkdirAll(filepath.Dir(o.LogFile), 0o755)
	os.WriteFile(o.LogFile, []byte(fmt.Sprintf("Updating Bench from %s at %s\n", src, isoNow())), 0o644)
	script := `cd "$1" && BENCH_DATA_DIR="$2" sh server-go/install --service >>"$3" 2>&1; echo "bench-update: exit $?" >>"$3"`
	args := []string{"-c", script, "bench-update", src, o.DataRoot, o.LogFile}
	var cmd *exec.Cmd
	if runtime.GOOS == "linux" {
		cmd = exec.Command("systemd-run", append([]string{"--user", "--scope", "--quiet", "--collect", fmt.Sprintf("--unit=bench-go-update-%d", time.Now().UnixMilli()), "sh"}, args...)...)
	} else {
		cmd = exec.Command("sh", args...)
	}
	cmd.Dir = src
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		f, _ := os.OpenFile(o.LogFile, os.O_APPEND|os.O_WRONLY, 0o644)
		f.WriteString(err.Error() + "\nbench-update: exit 127\n")
		f.Close()
	} else {
		go cmd.Wait()
	}
	var from any
	if in := st.ObjAt("installed"); in != nil {
		from = or(in.Get("commit"), nil)
	}
	return O("started", true, "source", src, "from", from, "to", st.Get("sourceCommit")), nil
}
