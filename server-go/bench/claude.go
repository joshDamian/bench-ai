package bench

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// The Claude Code CLI, driven over its stream-json protocol: what the Agent SDK does
// under query(). One Proc is one `claude` process for one session.
//
// The protocol, as the SDK speaks it (captured from @anthropic-ai/claude-agent-sdk):
//   - stdin and stdout carry one JSON object per line.
//   - The host opens with control_request {subtype:"initialize", hooks, sdkMcpServers,
//     sdkMcpServerManifests, appendSystemPrompt, agents, promptSuggestions}; the answer
//     carries the slash commands.
//   - The CLI asks the host with control_request: can_use_tool, hook_callback, mcp_message.
//     The host answers with control_response {subtype:"success"|"error", request_id, ...}.
//     control_cancel_request withdraws one.
//   - The host asks the CLI with control_request: interrupt, set_permission_mode, set_model.
//   - Everything else on stdout is a message: system, assistant, user, result, stream_event...

// SDKVersion is the Agent SDK release whose protocol this speaks.
const SDKVersion = "0.3.302"

// AppRoot is the folder this server ships in: the checkout or release with web/ and pack/.
var AppRoot = func() string {
	if v := Env("APP_ROOT"); v != "" {
		return v
	}
	exe, err := os.Executable()
	if err == nil {
		exe, _ = filepath.EvalSymlinks(exe)
		// server-go/bench-go or server-go/bin/bench-go inside a checkout.
		for dir := filepath.Dir(exe); dir != "/" && dir != "."; dir = filepath.Dir(dir) {
			if exists(filepath.Join(dir, "web", "index.html")) && exists(filepath.Join(dir, "pack")) {
				return dir
			}
		}
	}
	wd, _ := os.Getwd()
	for dir := wd; dir != "/" && dir != "."; dir = filepath.Dir(dir) {
		if exists(filepath.Join(dir, "web", "index.html")) && exists(filepath.Join(dir, "pack")) {
			return dir
		}
	}
	return wd
}()

// Pack is Bench's own plugin, beside src/ in the app.
func Pack() string { return filepath.Join(AppRoot, "pack") }

// ClaudeBin is the Claude Code executable: BENCH_CLAUDE_BIN, else the one the Agent SDK
// ships in the app's node_modules (what the Node server runs), else `claude` on PATH.
func ClaudeBin() string {
	if v := Env("CLAUDE_BIN"); v != "" {
		return v
	}
	arch := map[string]string{"amd64": "x64", "arm64": "arm64"}[runtime.GOARCH]
	for _, name := range []string{runtime.GOOS + "-" + arch, runtime.GOOS + "-" + arch + "-musl"} {
		p := filepath.Join(AppRoot, "node_modules", "@anthropic-ai", "claude-agent-sdk-"+name, "claude")
		if exists(p) {
			return p
		}
	}
	if p, err := exec.LookPath("claude"); err == nil {
		return p
	}
	return "claude"
}

// ProcOptions is the part of the SDK's Options that Bench uses.
type ProcOptions struct {
	Cwd                string
	AddDirs            []string
	Env                []string
	SettingSources     []string // nil leaves the flag out
	Plugins            []string
	Tools              []string // nil leaves it out
	PermissionMode     string
	SkipPermissions    bool
	PartialMessages    bool
	PromptSuggestions  bool
	Model              string
	Effort             string
	Resume             string
	AppendSystemPrompt string // "" with NoSystemPrompt false is the SDK default
	Settings           *Obj
	Agents             *Obj
	MaxTurns           int
	Hooks              []HookSpec
	MCP                *MCPServer
	CanUseTool         func(ctx context.Context, req *Obj) *Obj
	OnMessage          func(m *Obj)
	OnStderr           func(line string)
}

type HookSpec struct {
	Event   string
	Timeout int // seconds, 0 for none
	Fn      func(ctx context.Context, input *Obj) *Obj
}

// Proc is one running Claude Code process.
type Proc struct {
	opts     ProcOptions
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	wmu      sync.Mutex
	mu       sync.Mutex
	pending  map[string]chan *Obj
	cancels  map[string]context.CancelFunc
	hooks    map[string]HookSpec
	Commands []any
	ready    chan struct{}
	done     chan struct{}
	exitErr  error
	closed   bool
	Pid      int
}

func randID() string { return strconv.FormatInt(rand.Int63(), 36) }

// StartProc spawns Claude Code and sends initialize. Messages arrive on OnMessage until
// the process ends; Wait reports how it ended.
func StartProc(o ProcOptions) (*Proc, error) {
	args := []string{"--output-format", "stream-json", "--verbose", "--input-format", "stream-json"}
	if o.Effort != "" {
		args = append(args, "--effort="+o.Effort)
	}
	if o.MaxTurns > 0 {
		args = append(args, "--max-turns="+strconv.Itoa(o.MaxTurns))
	}
	if o.Model != "" {
		args = append(args, "--model="+o.Model)
	}
	if o.CanUseTool != nil {
		args = append(args, "--permission-prompt-tool=stdio")
	}
	if o.Tools != nil {
		args = append(args, "--tools="+strings.Join(o.Tools, ","))
	}
	if o.SettingSources != nil {
		args = append(args, "--setting-sources="+strings.Join(o.SettingSources, ","))
	}
	if o.PermissionMode != "" {
		args = append(args, "--permission-mode="+o.PermissionMode)
	}
	if o.SkipPermissions {
		args = append(args, "--allow-dangerously-skip-permissions")
	}
	if o.PartialMessages {
		args = append(args, "--include-partial-messages")
	}
	if o.Resume != "" {
		args = append(args, "--resume="+o.Resume)
	}
	for _, d := range o.AddDirs {
		args = append(args, "--add-dir="+d)
	}
	for _, p := range o.Plugins {
		args = append(args, "--plugin-dir="+p)
	}
	if o.Settings != nil {
		args = append(args, "--settings", string(ToJSON(o.Settings)))
	}
	cmd := exec.Command(ClaudeBin(), args...)
	cmd.Dir = o.Cwd
	env := o.Env
	if env == nil {
		env = os.Environ()
	}
	env = withDefault(env, "CLAUDE_CODE_ENTRYPOINT", "sdk-ts")
	env = withDefault(env, "CLAUDE_AGENT_SDK_VERSION", SDKVersion)
	env = withDefault(env, "CLAUDE_CODE_SDK_READS_SESSION_STATE", "1")
	cmd.Env = env
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("Failed to spawn Claude Code process: %s", err.Error())
	}
	p := &Proc{opts: o, cmd: cmd, stdin: stdin, pending: map[string]chan *Obj{}, cancels: map[string]context.CancelFunc{}, hooks: map[string]HookSpec{}, ready: make(chan struct{}), done: make(chan struct{}), Pid: cmd.Process.Pid}
	go p.readStderr(stderr)
	go p.readLoop(stdout)

	// initialize
	req := O("subtype", "initialize")
	if len(o.Hooks) > 0 {
		hooks := O()
		for i, h := range o.Hooks {
			id := fmt.Sprintf("hook_%d", i)
			p.hooks[id] = h
			entry := O("hookCallbackIds", []any{id})
			if h.Timeout > 0 {
				entry.Set("timeout", float64(h.Timeout))
			}
			list, _ := hooks.Get(h.Event).([]any)
			hooks.Set(h.Event, append(list, entry))
		}
		req.Set("hooks", hooks)
	}
	if o.MCP != nil {
		req.Set("sdkMcpServers", []any{"bench"})
		req.Set("sdkMcpServerConfigs", O("bench", o.MCP.Config))
		req.Set("sdkMcpServerManifests", O("bench", o.MCP.Manifest))
	}
	if o.AppendSystemPrompt != "" {
		req.Set("appendSystemPrompt", o.AppendSystemPrompt)
	} else {
		req.Set("systemPrompt", []any{""})
	}
	if o.Agents != nil {
		req.Set("agents", o.Agents)
	}
	if o.PromptSuggestions {
		req.Set("promptSuggestions", true)
	}
	// initialize goes first, before any message is written; its answer is awaited apart.
	wait, err := p.send(req)
	if err != nil {
		p.Close()
		return nil, err
	}
	go func() {
		resp, err := wait(60 * time.Second)
		if err == nil {
			if cmds, ok := resp.Get("commands").([]any); ok {
				p.mu.Lock()
				p.Commands = cmds
				p.mu.Unlock()
			}
		}
		close(p.ready)
	}()
	return p, nil
}

func withDefault(env []string, k, v string) []string {
	for _, e := range env {
		if strings.HasPrefix(e, k+"=") && len(e) > len(k)+1 {
			return env
		}
	}
	return append(env, k+"="+v)
}

// Ready waits for the initialize answer, and returns the slash commands it carried.
func (p *Proc) Ready(timeout time.Duration) ([]any, error) {
	select {
	case <-p.ready:
		p.mu.Lock()
		defer p.mu.Unlock()
		if p.Commands == nil {
			return nil, errors.New("no commands")
		}
		return p.Commands, nil
	case <-p.done:
		return nil, errors.New("the process ended")
	case <-time.After(timeout):
		return nil, errors.New("timed out")
	}
}

func (p *Proc) write(v any) error {
	p.wmu.Lock()
	defer p.wmu.Unlock()
	b := append(ToJSON(v), '\n')
	_, err := p.stdin.Write(b)
	return err
}

// Send pushes one user message into the conversation.
func (p *Proc) Send(m *Obj) error { return p.write(m) }

// Request sends a control request to the CLI and waits for its answer.
func (p *Proc) Request(req *Obj, timeout time.Duration) (*Obj, error) {
	wait, err := p.send(req)
	if err != nil {
		return nil, err
	}
	return wait(timeout)
}

// send writes a control request now and returns the wait for its answer, so a request
// can be ordered before what is written after it.
func (p *Proc) send(req *Obj) (func(time.Duration) (*Obj, error), error) {
	id := randID()
	ch := make(chan *Obj, 1)
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, errors.New("closed")
	}
	p.pending[id] = ch
	p.mu.Unlock()
	if err := p.write(O("request_id", id, "type", "control_request", "request", req)); err != nil {
		return nil, err
	}
	return func(timeout time.Duration) (*Obj, error) {
		var t <-chan time.Time
		if timeout > 0 {
			t = time.After(timeout)
		}
		select {
		case r := <-ch:
			if r == nil {
				return nil, errors.New("closed")
			}
			if r.Str("subtype") == "error" {
				return nil, errors.New(r.Str("error"))
			}
			resp := r.ObjAt("response")
			if resp == nil {
				resp = O()
			}
			return resp, nil
		case <-p.done:
			return nil, errors.New("the process ended")
		case <-t:
			p.mu.Lock()
			delete(p.pending, id)
			p.mu.Unlock()
			return nil, errors.New("timed out")
		}
	}, nil
}

func (p *Proc) Interrupt() error {
	_, err := p.Request(O("subtype", "interrupt"), 30*time.Second)
	return err
}

func (p *Proc) SetPermissionMode(mode string) error {
	_, err := p.Request(O("subtype", "set_permission_mode", "mode", mode), 30*time.Second)
	return err
}

func (p *Proc) SetModel(model string) error {
	_, err := p.Request(O("subtype", "set_model", "model", model), 30*time.Second)
	return err
}

func (p *Proc) readStderr(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for sc.Scan() {
		if p.opts.OnStderr != nil {
			p.opts.OnStderr(sc.Text())
		}
	}
}

func (p *Proc) readLoop(r io.Reader) {
	br := bufio.NewReaderSize(r, 256*1024)
	for {
		line, err := br.ReadBytes('\n')
		if len(bytes.TrimSpace(line)) > 0 {
			p.dispatch(line)
		}
		if err != nil {
			break
		}
	}
	werr := p.cmd.Wait()
	p.mu.Lock()
	closed := p.closed
	p.closed = true
	for id, ch := range p.pending {
		close(ch)
		delete(p.pending, id)
	}
	for _, c := range p.cancels {
		c()
	}
	p.mu.Unlock()
	if werr != nil && !closed {
		code := -1
		if p.cmd.ProcessState != nil {
			code = p.cmd.ProcessState.ExitCode()
		}
		if code >= 0 {
			p.exitErr = fmt.Errorf("Claude Code process exited with code %d", code)
		} else {
			p.exitErr = fmt.Errorf("Claude Code process terminated by signal")
		}
	}
	close(p.done)
}

func (p *Proc) dispatch(line []byte) {
	v, err := parseJSON(line)
	if err != nil {
		return
	}
	m, ok := v.(*Obj)
	if !ok {
		return
	}
	switch m.Str("type") {
	case "control_response":
		r := m.ObjAt("response")
		id := r.Str("request_id")
		p.mu.Lock()
		ch := p.pending[id]
		delete(p.pending, id)
		p.mu.Unlock()
		if ch != nil {
			ch <- r
		}
	case "control_request":
		go p.answer(m)
	case "control_cancel_request":
		p.mu.Lock()
		if c := p.cancels[m.Str("request_id")]; c != nil {
			c()
			delete(p.cancels, m.Str("request_id"))
		}
		p.mu.Unlock()
	case "keep_alive":
	default:
		if p.opts.OnMessage != nil {
			p.opts.OnMessage(m)
		}
	}
}

func (p *Proc) answer(m *Obj) {
	id := m.Str("request_id")
	req := m.ObjAt("request")
	ctx, cancel := context.WithCancel(context.Background())
	p.mu.Lock()
	if _, dup := p.cancels[id]; dup {
		p.mu.Unlock()
		cancel()
		return
	}
	p.cancels[id] = cancel
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.cancels, id)
		p.mu.Unlock()
		cancel()
	}()
	var resp *Obj
	var err error
	func() {
		defer func() {
			if r := recover(); r != nil {
				err = fmt.Errorf("%v", r)
			}
		}()
		switch req.Str("subtype") {
		case "can_use_tool":
			if p.opts.CanUseTool == nil {
				err = errors.New("canUseTool callback is not provided.")
				return
			}
			resp = p.opts.CanUseTool(ctx, req)
			if resp != nil {
				resp = resp.Clone().Set("toolUseID", req.Get("tool_use_id"))
			}
		case "hook_callback":
			h, ok := p.hooks[req.Str("callback_id")]
			if !ok {
				err = fmt.Errorf("No hook callback found for ID: %s", req.Str("callback_id"))
				return
			}
			resp = h.Fn(ctx, req.ObjAt("input"))
			if resp == nil {
				resp = O()
			}
		case "mcp_message":
			if p.opts.MCP == nil {
				err = fmt.Errorf("SDK MCP server not found: %s", req.Str("server_name"))
				return
			}
			msg := req.ObjAt("message")
			if msg.Has("id") && msg.Get("id") != nil {
				resp = O("mcp_response", p.opts.MCP.Handle(ctx, msg))
			} else {
				resp = O("mcp_response", O("jsonrpc", "2.0", "result", O(), "id", 0.0))
			}
		default:
			err = fmt.Errorf("Unsupported control request subtype: %s", req.Str("subtype"))
		}
	}()
	if ctx.Err() != nil && resp == nil && err == nil {
		return
	}
	if err != nil {
		p.write(O("type", "control_response", "response", O("subtype", "error", "request_id", id, "error", err.Error())))
		return
	}
	p.write(O("type", "control_response", "response", O("subtype", "success", "request_id", id, "response", resp)))
}

// Close ends the process: stdin closed, then a SIGTERM, then a SIGKILL.
func (p *Proc) Close() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	p.mu.Unlock()
	p.stdin.Close()
	if p.cmd.Process != nil {
		p.cmd.Process.Signal(syscall.SIGTERM)
		go func() {
			select {
			case <-p.done:
			case <-time.After(5 * time.Second):
				p.cmd.Process.Kill()
			}
		}()
	}
}

// Wait blocks until the process is gone; the error is a failure it ended with.
func (p *Proc) Wait() error {
	<-p.done
	return p.exitErr
}

func (p *Proc) Done() <-chan struct{} { return p.done }

func (p *Proc) Closed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.closed
}

// --- the in-process MCP server --------------------------------------------------------

// MCPServer is the `bench` server a session's Claude Code calls through mcp_message.
type MCPServer struct {
	Manifest *Obj
	Config   *Obj
	Tools    map[string]func(ctx context.Context, args *Obj) *Obj
}

func (s *MCPServer) Handle(ctx context.Context, msg *Obj) *Obj {
	id := msg.Get("id")
	reply := func(result any) *Obj { return O("jsonrpc", "2.0", "id", id, "result", result) }
	switch msg.Str("method") {
	case "initialize":
		return reply(s.Manifest.Get("initializeResult"))
	case "tools/list":
		return reply(s.Manifest.Get("toolsListResult"))
	case "ping":
		return reply(O())
	case "tools/call":
		params := msg.ObjAt("params")
		name := params.Str("name")
		fn := s.Tools[name]
		if fn == nil {
			return O("jsonrpc", "2.0", "id", id, "error", O("code", -32602.0, "message", "Tool "+name+" not found"))
		}
		args := params.ObjAt("arguments")
		if args == nil {
			args = O()
		}
		var out *Obj
		func() {
			defer func() {
				if r := recover(); r != nil {
					out = toolErr(fmt.Sprint(r))
				}
			}()
			out = fn(ctx, args)
		}()
		return reply(out)
	}
	return O("jsonrpc", "2.0", "id", id, "error", O("code", -32601.0, "message", "Method not found"))
}

func toolOK(text string) *Obj {
	return O("content", []any{O("type", "text", "text", text)})
}

func toolErr(text string) *Obj {
	return O("content", []any{O("type", "text", "text", text)}, "isError", true)
}

func init() {
	_ = json.Valid
}
