package bench

// Jobs: bounded work a linked device runs for the server, with the server as the fallback.
//
// A device advertises capabilities in its hello ("exec", "check", "render", "serve"). Run
// picks an online device that has the capability and one free slot, sends a job, takes its
// progress, and waits for the done. Any failure - no device, a dropped link, a timeout, a
// job that says it failed - runs the server's own fallback. The Mac decides what it offers;
// the server can only use what it advertises, and nothing durable lives there.

import (
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// JobKinds are what a device can offer and the kinds of job the server sends.
var JobKinds = []string{"exec", "check", "render", "serve", "ios-sim", "android-emu"}

// JobLimit is the longest a job may run unless it asks for less.
const JobLimit = 15 * time.Minute

const jobRecent = 30

// JobSpec is one piece of work and what to do without the device.
type JobSpec struct {
	Kind    string
	Args    *Obj
	Timeout time.Duration
	// Requires limits which worker may take the job: labels, a platform, cpus, memMb.
	Requires *Obj
	// Sticky names the only worker the job may run on, for work that depends on something
	// already there, such as a service.
	Sticky string
	// Fallback runs the same work on the server. Run calls it when no device can take the
	// job, when the link drops, when the job times out, or when the device says it failed.
	Fallback func(context.Context, *JobSpec) (*JobResult, error)
}

const (
	// jobLease is how long a claim on a job survives without a heartbeat, so a worker that
	// stops answering gives the job back.
	jobLease = 60 * time.Second
	// jobHeartbeat renews a lease while the job runs.
	jobHeartbeat = 15 * time.Second
	// jobAttempts is how many different workers try a failed job before the server does it.
	jobAttempts = 2
)

// JobResult is what a job produced, wherever it ran.
type JobResult struct {
	ID       string
	OK       bool
	Text     string
	Image    string
	Exit     int
	Port     int
	Ms       int64
	RanOn    string // "mac" or "server"
	Device   string
	DeviceID string
	Addr     string // the device's own address, when it sent one
	Files    []any
}

// Jobs is the dispatcher: one per Service, and no state beyond its recent list and the
// boards it has published to a device.
type Jobs struct {
	svc    *Service
	mu     sync.Mutex
	next   int
	recent []*Obj

	hostMu sync.Mutex
	hosts  map[string]*boardHost

	trees    map[string]*jobTree
	treeNext int
}

// jobTree is a worktree packed for a device to download for a check.
type jobTree struct {
	gz   []byte
	hash string
	at   time.Time
}

// boardHost is a board a device is serving, with the path and address the server proxies to.
type boardHost struct {
	ProjectID string
	Slug      string
	DeviceID  string
	Device    string
	JobID     string
	Token     string
	Addr      string
	Port      int
	Name      string
	Mime      string
	At        time.Time
}

// Service.Jobs is the dispatcher, made on first use.
func (s *Service) Jobs() *Jobs {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.jobs == nil {
		s.jobs = &Jobs{svc: s, hosts: map[string]*boardHost{}, trees: map[string]*jobTree{}}
	}
	return s.jobs
}

// Run does the work on the first worker that can take it, else a linked device, else the
// server. The job is written to the durable queue first, so a restart or a lost worker leaves
// a record and a waiting job, not a gap.
func (j *Jobs) Run(ctx context.Context, spec *JobSpec) (*JobResult, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	if spec == nil || spec.Kind == "" {
		return nil, fmt.Errorf("A job needs a kind")
	}
	if spec.Timeout <= 0 || spec.Timeout > JobLimit {
		spec.Timeout = JobLimit
	}
	if spec.Args == nil {
		spec.Args = O()
	}
	if !inList(JobKinds, spec.Kind) {
		return nil, fmt.Errorf("No job kind %q", spec.Kind)
	}
	id := NewID()
	j.svc.Store.AddQueuedJob(id, spec.Kind, spec.Args, spec.Requires, spec.Sticky)
	j.svc.Store.ExpireQueuedJobs()
	local := func(err error) (*JobResult, error) {
		if spec.Fallback == nil {
			j.closeQueue(id, "failed", nil, err.Error())
			return nil, err
		}
		r, ferr := spec.Fallback(ctx, spec)
		if ferr != nil {
			j.closeQueue(id, "failed", nil, ferr.Error())
			return nil, ferr
		}
		if r == nil {
			r = &JobResult{}
		}
		r.RanOn = "server"
		r.Device = ""
		j.record(id, spec.Kind, "server", "", r.OK, r.Ms, "")
		j.closeQueue(id, "done", r, "")
		return r, nil
	}

	// Workers first: they are the pool, and the queue makes them safe to lose.
	exclude := map[string]bool{}
	var lastErr error
	for tries := 0; tries < jobAttempts; tries++ {
		wk := j.svc.Workers().Pick(spec.Kind, spec.Requires, spec.Sticky, exclude)
		if wk == nil {
			break
		}
		r, claimed, err := j.runOnWorker(ctx, id, wk, spec)
		if ctx.Err() != nil {
			j.svc.Store.CancelQueuedJob(id)
			return nil, ctx.Err()
		}
		if r != nil {
			return r, nil
		}
		if j.cancelled(id) {
			return nil, fmt.Errorf("The job was cancelled")
		}
		if err != nil {
			lastErr = err
			j.emit(wk.Str("id"), id, spec.Kind, "failed", wk.Str("name"), err.Error())
		}
		// Do not hand the same work to the worker that just failed it.
		exclude[wk.Str("id")] = true
		if !claimed {
			tries--
		}
		if spec.Sticky != "" {
			break
		}
	}
	if j.cancelled(id) {
		return nil, fmt.Errorf("The job was cancelled")
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("No worker took the %s job", spec.Kind)
	}

	// A linked device, such as the Mac app, still takes its kinds.
	dv, addr := j.svc.Devices().Pick(spec.Kind, "")
	if dv == nil {
		// Without a fallback the server ran nothing, so say only what happened.
		if spec.Fallback == nil {
			j.emit("", id, spec.Kind, "failed", "", lastErr.Error())
			return local(lastErr)
		}
		j.emit("", id, spec.Kind, "fallback", "server", lastErr.Error()+"; it ran on the server")
		return local(fmt.Errorf("%s; it ran on the server", lastErr))
	}
	j.svc.Store.MarkQueuedElsewhere(id, "device", dv.Str("id"))
	j.emit(dv.Str("id"), id, spec.Kind, "running", dv.Str("name"), "Started on "+dv.Str("name"))
	m, name, err := j.svc.Devices().Job(ctx, dv.Str("id"), id, spec.Kind, spec.Args, spec.Timeout, func(text string) {
		j.emit(dv.Str("id"), id, spec.Kind, "progress", dv.Str("name"), text)
	})
	if err != nil {
		if ctx.Err() != nil {
			j.svc.Store.CancelQueuedJob(id)
			return nil, ctx.Err()
		}
		j.emit(dv.Str("id"), id, spec.Kind, "failed", dv.Str("name"), err.Error())
		if spec.Fallback == nil {
			return local(err)
		}
		r, ferr := local(err)
		if ferr != nil {
			return nil, fmt.Errorf("%s; the server fallback also failed: %v", err, ferr)
		}
		return r, nil
	}
	r := &JobResult{
		ID:     id,
		OK:     truthy(m.Get("ok")),
		Text:   m.Str("text"),
		Image:  m.Str("image"),
		Exit:   int(m.Num("exit")),
		Port:   int(m.Num("port")),
		Ms:     int64(m.Num("ranMs")),
		RanOn:  "mac",
		Device: name,
		Addr:   addr,
		Files:  arr(m.Get("files")),
	}
	r.DeviceID = dv.Str("id")
	if !r.OK {
		cause := m.Str("error")
		if cause == "" {
			cause = name + " could not finish the job"
		}
		j.emit(dv.Str("id"), id, spec.Kind, "failed", name, cause)
		r2, ferr := local(fmt.Errorf("%s", cause))
		if ferr != nil {
			j.closeQueue(id, "failed", nil, cause)
			return nil, ferr
		}
		return r2, nil
	}
	j.record(id, spec.Kind, "mac", name, true, r.Ms, "")
	j.closeQueue(id, "done", r, "")
	j.emit(dv.Str("id"), id, spec.Kind, "done", name, "Finished on "+name)
	return r, nil
}

// runOnWorker claims the queued job for one worker and runs it. It returns the result when the
// job finishes, nil when it failed or another worker claimed it first, and whether it claimed.
func (j *Jobs) runOnWorker(ctx context.Context, id string, wk *Obj, spec *JobSpec) (*JobResult, bool, error) {
	workerID := wk.Str("id")
	name := wk.Str("name")
	if name == "" {
		name = "the worker"
	}
	if !j.svc.Store.ClaimQueuedJob(id, workerID, jobLease) {
		return nil, false, nil
	}
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(jobHeartbeat)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				j.svc.Store.MarkQueuedJobRunning(id, workerID)
				if !j.svc.Store.RenewQueuedJob(id, workerID, jobLease) {
					return
				}
			}
		}
	}()
	defer close(stop)
	j.emit(workerID, id, spec.Kind, "running", name, "Started on "+name)
	m, got, err := j.svc.Workers().Job(ctx, workerID, id, spec.Kind, spec.Args, spec.Timeout, func(text string) {
		j.emit(workerID, id, spec.Kind, "progress", name, text)
	})
	if err != nil {
		if ctx.Err() == nil {
			j.svc.Store.RequeueQueuedJob(id, err.Error())
		}
		return nil, true, err
	}
	r := &JobResult{
		ID: id, OK: truthy(m.Get("ok")), Text: m.Str("text"), Image: m.Str("image"),
		Exit: int(m.Num("exit")), Port: int(m.Num("port")), Ms: int64(m.Num("ranMs")),
		RanOn: "worker", Device: got, DeviceID: workerID, Files: arr(m.Get("files")),
	}
	if !r.OK {
		cause := m.Str("error")
		if cause == "" {
			cause = got + " could not finish the job"
		}
		j.svc.Store.RequeueQueuedJob(id, cause)
		return nil, true, fmt.Errorf("%s", cause)
	}
	j.svc.Store.FinishQueuedJob(id, workerID, "done", O("ran_on", "worker", "device", got, "exit", r.Exit, "ms", r.Ms, "text", tailText(r.Text)), "")
	j.record(id, spec.Kind, "worker", got, true, r.Ms, "")
	j.emit(workerID, id, spec.Kind, "done", got, "Finished on "+got)
	return r, true, nil
}

// cancelled says whether a job was stopped from the queue.
func (j *Jobs) cancelled(id string) bool {
	row := j.svc.Store.QueuedJob(id)
	return row != nil && row.Str("state") == "cancelled"
}

// tailText keeps the end of a long output for the queue view.
func tailText(s string) string {
	if len(s) <= 4000 {
		return s
	}
	return s[len(s)-4000:]
}

// closeQueue records where a job ended when it did not run on a worker.
func (j *Jobs) closeQueue(id, state string, r *JobResult, errText string) {
	var res *Obj
	if r != nil {
		res = O("ran_on", r.RanOn, "device", r.Device, "ok", r.OK, "exit", r.Exit, "ms", r.Ms, "text", tailText(r.Text))
	}
	j.svc.Store.CloseQueuedJob(id, state, res, errText)
}

// CancelJob cancels a job in the queue and asks its worker to stop.
func (j *Jobs) CancelJob(id string) (*Obj, error) {
	row := j.svc.Store.QueuedJob(id)
	if row == nil {
		return nil, fail("No such job", 404)
	}
	j.svc.Store.CancelQueuedJob(id)
	if wid := row.Str("worker_id"); wid != "" {
		j.svc.Workers().CancelJob(wid, id)
		j.svc.Devices().CancelJob(wid, id)
	}
	j.emit(row.Str("worker_id"), id, row.Str("kind"), "cancelled", "", "Cancelled")
	return O("ok", true), nil
}

func (j *Jobs) nextID() string {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.next++
	return fmt.Sprintf("j%d", j.next)
}

// AddTree keeps a packed worktree for a device to download and returns its id. Trees are
// dropped an hour after they are packed; a device unpacks by hash into its own cache.
func (j *Jobs) AddTree(gz []byte, hash string) string {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.treeNext++
	id := fmt.Sprintf("t%d", j.treeNext)
	j.trees[id] = &jobTree{gz: gz, hash: hash, at: time.Now()}
	for k, t := range j.trees {
		if time.Since(t.at) > time.Hour {
			delete(j.trees, k)
		}
	}
	return id
}

// Tree is a packed worktree by id.
func (j *Jobs) Tree(id string) ([]byte, string, bool) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if t := j.trees[id]; t != nil {
		return t.gz, t.hash, true
	}
	return nil, "", false
}

// ChecksHostPref is where a project's checks run: "mac" when a worker or a linked device
// should take them, else "server". It is "server" until a project asks for offload.
func (s *Service) ChecksHostPref(projectID string) string {
	if s.Store.Setting("project:"+projectID+":checks_host") == "mac" {
		return "mac"
	}
	return "server"
}

// SetChecksHostPref records where a project's checks run.
func (s *Service) SetChecksHostPref(projectID, on string) {
	if on != "mac" {
		on = "server"
	}
	s.Store.SetSetting("project:"+projectID+":checks_host", on)
}

// CanTake says whether a worker or a linked device offers the kind right now, so a caller
// can skip work nobody would run, such as packing a tree for a check.
func (s *Service) CanTake(kind string) bool {
	if dv, _ := s.Devices().Pick(kind, ""); dv != nil {
		return true
	}
	return s.Workers().Pick(kind, nil, "", nil) != nil
}

// remoteCheck runs a task's check on a worker or a linked device when the project asks for
// it and one offers check jobs. A nil result with no error means the server should run it.
func (s *Service) remoteCheck(ctx context.Context, t *Obj, command, worktree string) (*CheckResult, error) {
	projectID := t.Str("project_id")
	if projectID == "" || s.ChecksHostPref(projectID) != "mac" {
		return nil, nil
	}
	if !s.CanTake("check") {
		return nil, nil
	}
	gz, hash, err := PackTree(worktree)
	if err != nil {
		return nil, nil
	}
	treeID := s.Jobs().AddTree(gz, hash)
	r, err := s.Jobs().Run(ctx, &JobSpec{
		Kind: "check", Timeout: CheckTimeout + time.Minute,
		Args: O("command", command, "treeId", treeID, "treeHash", hash, "treeUrl", "/api/jobs/"+treeID+"/tree"),
	})
	if err != nil || r == nil || r.RanOn == "server" {
		return nil, nil
	}
	return &CheckResult{OK: r.OK, Code: float64(r.Exit), Output: "(ran on " + r.Device + ")\n" + r.Text, Ms: float64(r.Ms)}, nil
}

func boardKey(projectID, slug string) string { return projectID + "/" + slug }

// HostBoard publishes a board to a device and asks it to serve it, replacing any host the
// project already had. The file stays where it is; the copy on the device is just for serving.
func (j *Jobs) HostBoard(projectID, slug, name, mime string, data []byte) (*boardHost, error) {
	j.StopBoard(projectID, slug)
	token := NewID()
	r, err := j.Run(context.Background(), &JobSpec{
		Kind:    "serve",
		Args:    O("token", token, "name", name, "mime", mime, "keep", true, "data", base64.StdEncoding.EncodeToString(data)),
		Timeout: 30 * time.Second,
	})
	if err != nil {
		return nil, err
	}
	if r == nil || r.RanOn != "mac" {
		return nil, fmt.Errorf("No linked device hosted the board")
	}
	if r.Port <= 0 || r.Addr == "" {
		return nil, fmt.Errorf("%s hosted the board but the server cannot reach it", r.Device)
	}
	h := &boardHost{
		ProjectID: projectID, Slug: slug, DeviceID: r.DeviceID, Device: r.Device,
		JobID: r.ID, Token: token, Addr: r.Addr, Port: r.Port, Name: name, Mime: mime, At: time.Now(),
	}
	j.hostMu.Lock()
	j.hosts[boardKey(projectID, slug)] = h
	j.hostMu.Unlock()
	return h, nil
}

// BoardHost is the device serving a board, or nil when none is.
func (j *Jobs) BoardHost(projectID, slug string) *boardHost {
	j.hostMu.Lock()
	defer j.hostMu.Unlock()
	if h := j.hosts[boardKey(projectID, slug)]; h != nil {
		c := *h
		return &c
	}
	return nil
}

// StopBoard tells the device to stop serving a board. It says whether one was hosting it.
func (j *Jobs) StopBoard(projectID, slug string) bool {
	j.hostMu.Lock()
	h := j.hosts[boardKey(projectID, slug)]
	delete(j.hosts, boardKey(projectID, slug))
	j.hostMu.Unlock()
	if h == nil {
		return false
	}
	j.svc.Devices().CancelJob(h.DeviceID, h.JobID)
	return true
}

// URL is where the device serves the board: an unguessable path on its own address.
func (h *boardHost) URL() string {
	return "http://" + net.JoinHostPort(h.Addr, fmt.Sprint(h.Port)) + "/j/" + h.Token + "/" + h.Name
}

// BoardHostPref is where a project's boards are served: "mac" when a device hosts them,
// else "server". It is "server" until a project asks for the Mac.
func (s *Service) BoardHostPref(projectID string) string {
	if s.Store.Setting("project:"+projectID+":boards_host") == "mac" {
		return "mac"
	}
	return "server"
}

// SetBoardHostPref records the choice for a project, without touching a running host.
func (s *Service) SetBoardHostPref(projectID, on string) {
	s.Store.SetSetting("project:"+projectID+":boards_host", on)
}

// HostBoard starts or stops a device serving a project's board. "mac" asks a linked device;
// it answers 502 when no device can host it, and the board stays served from the server.
func (s *Service) HostBoard(projectID, name, on string) (*Obj, error) {
	p, err := s.Project(projectID)
	if err != nil {
		return nil, err
	}
	full, mime, err := BoardBySlug(p.Str("path"), name)
	if err != nil {
		return nil, httpErr(404, err.Error())
	}
	slug := BoardSlug(full)
	switch on {
	case "mac":
		data, err := os.ReadFile(full)
		if err != nil {
			return nil, err
		}
		bh, err := s.Jobs().HostBoard(projectID, slug, filepath.Base(full), mime, data)
		if err != nil {
			s.SetBoardHostPref(projectID, "server")
			return nil, httpErr(502, err.Error())
		}
		s.SetBoardHostPref(projectID, "mac")
		return O("ok", true, "on", "mac", "device", bh.Device, "url", BoardURL(projectID, slug)), nil
	case "server", "":
		s.Jobs().StopBoard(projectID, slug)
		s.SetBoardHostPref(projectID, "server")
		return O("ok", true, "on", "server", "url", BoardURL(projectID, slug)), nil
	}
	return nil, httpErr(400, `Ask for "mac" or "server"`)
}

// record keeps the last few jobs for GET /api/jobs and the status view.
func (j *Jobs) record(id, kind, ranOn, device string, ok bool, ms int64, text string) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.recent = append([]*Obj{O("id", id, "kind", kind, "ran_on", ranOn, "device", device, "ok", ok, "ms", ms, "text", text, "at", isoNow())}, j.recent...)
	if len(j.recent) > jobRecent {
		j.recent = j.recent[:jobRecent]
	}
}

// emit tells app clients a job changed, through the same event hub as tasks and devices.
func (j *Jobs) emit(deviceID, id, kind, state, device, text string) {
	j.svc.Emit("job", O("device_id", deviceID, "id", id, "kind", kind, "state", state, "device", device, "text", text, "at", isoNow()))
}

// Recent is the last jobs the dispatcher ran, newest first.
func (j *Jobs) Recent() []any {
	j.mu.Lock()
	defer j.mu.Unlock()
	out := make([]any, len(j.recent))
	for i, r := range j.recent {
		out[i] = r
	}
	return out
}

// cleanCaps keeps the capabilities a device sent that the server knows.
func cleanCaps(v any) []any {
	out := []any{}
	for _, x := range arr(v) {
		s := str(x)
		if inList(JobKinds, s) && !hasCap(out, s) {
			out = append(out, s)
		}
	}
	return out
}

// hasCap says whether a capability list offers one.
func hasCap(caps any, cap string) bool {
	for _, x := range arr(caps) {
		if str(x) == cap {
			return true
		}
	}
	return false
}
