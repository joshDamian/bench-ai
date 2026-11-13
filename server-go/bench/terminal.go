package bench

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"
	"github.com/creack/pty"
)

// Terminals (src/terminal.mjs): a real shell on a pty per place, kept across reconnects
// with its scrollback, reaped when nobody has watched it for a while.

const defaultReap = 10 * time.Minute
const scrollbackChars = 256 * 1024
const maxTerminals = 24

type TermSpec struct {
	Key     string
	Cwd     string
	Command string
	Env     []string
	Keep    bool
}

type termSession struct {
	key, cwd   string
	f          *os.File
	cmd        *exec.Cmd
	pid        int
	cols, rows int
	mu         sync.Mutex
	scroll     []string
	size       int
	sockets    map[*websocket.Conn]bool
	reap       *time.Timer
	keep       bool
	alive      bool
	exited     bool
}

type Terminals struct {
	mu       sync.Mutex
	reapAfter time.Duration
	sessions map[string]*termSession
}

func NewTerminals(reap time.Duration) *Terminals {
	if reap == 0 {
		reap = defaultReap
	}
	return &Terminals{reapAfter: reap, sessions: map[string]*termSession{}}
}

func (t *Terminals) Size() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.sessions)
}

func (t *Terminals) List() []any {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := []any{}
	for _, s := range t.sessions {
		s.mu.Lock()
		if s.alive {
			out = append(out, O("key", s.key, "pid", float64(s.pid), "cwd", s.cwd, "cols", float64(s.cols), "rows", float64(s.rows), "keep", s.keep))
		}
		s.mu.Unlock()
	}
	return out
}

func shellEnv(extra []string) []string {
	env := mergeEnv(CleanEnv(), extra)
	env = mergeEnv(env, []string{"TERM=xterm-256color", "COLORTERM=truecolor"})
	var out []string
	for _, e := range env {
		if strings.HasPrefix(e, "TERM_PROGRAM=") || strings.HasPrefix(e, "TERM_PROGRAM_VERSION=") {
			continue
		}
		out = append(out, e)
	}
	return out
}

func loginShell() string {
	if s := os.Getenv("SHELL"); s != "" {
		return s
	}
	if runtime.GOOS == "darwin" {
		return "/bin/zsh"
	}
	return "/bin/bash"
}

func (t *Terminals) Open(spec TermSpec) (*termSession, error) {
	t.mu.Lock()
	if ex := t.sessions[spec.Key]; ex != nil {
		ex.mu.Lock()
		alive := ex.alive
		ex.mu.Unlock()
		if alive {
			t.mu.Unlock()
			return ex, nil
		}
		t.mu.Unlock()
		t.dispose(ex, "terminal closed")
		t.mu.Lock()
	}
	if len(t.sessions) >= maxTerminals {
		n := len(t.sessions)
		t.mu.Unlock()
		return nil, fmt.Errorf("Too many terminals open (%d); close one first", n)
	}
	t.mu.Unlock()
	args := []string{"-l"}
	if spec.Command != "" {
		args = []string{"-l", "-c", spec.Command}
	}
	cmd := exec.Command(loginShell(), args...)
	cmd.Dir = spec.Cwd
	cmd.Env = shellEnv(spec.Env)
	f, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: 80, Rows: 24})
	if err != nil {
		return nil, fmt.Errorf("Terminals are unavailable: %s", err.Error())
	}
	s := &termSession{key: spec.Key, cwd: spec.Cwd, f: f, cmd: cmd, pid: cmd.Process.Pid, cols: 80, rows: 24, sockets: map[*websocket.Conn]bool{}, keep: spec.Keep, alive: true}
	go s.read(t)
	t.mu.Lock()
	t.sessions[spec.Key] = s
	t.mu.Unlock()
	return s, nil
}

func (s *termSession) push(chunk string) {
	l := jsLen(chunk)
	if l >= scrollbackChars {
		s.scroll = []string{jsTail(chunk, scrollbackChars)}
		s.size = scrollbackChars
		return
	}
	s.scroll = append(s.scroll, chunk)
	s.size += l
	for s.size > scrollbackChars && len(s.scroll) > 0 {
		s.size -= jsLen(s.scroll[0])
		s.scroll = s.scroll[1:]
	}
}

func (s *termSession) read(t *Terminals) {
	buf := make([]byte, 32*1024)
	var pending []byte
	for {
		n, err := s.f.Read(buf)
		if n > 0 {
			data := append(pending, buf[:n]...)
			// Keep an incomplete UTF-8 sequence for the next read.
			cut := len(data)
			for i := 1; i <= 3 && i <= len(data); i++ {
				b := data[len(data)-i]
				if b&0xC0 == 0x80 {
					continue
				}
				if b >= 0xC0 && !utf8.FullRune(data[len(data)-i:]) {
					cut = len(data) - i
				}
				break
			}
			pending = append([]byte(nil), data[cut:]...)
			text := strings.ToValidUTF8(string(data[:cut]), "�")
			if text != "" {
				s.mu.Lock()
				s.push(text)
				socks := make([]*websocket.Conn, 0, len(s.sockets))
				for ws := range s.sockets {
					socks = append(socks, ws)
				}
				s.mu.Unlock()
				for _, ws := range socks {
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
					ws.Write(ctx, websocket.MessageText, []byte(text))
					cancel()
				}
			}
		}
		if err != nil {
			break
		}
	}
	code := 0
	s.cmd.Wait()
	if s.cmd.ProcessState != nil {
		code = s.cmd.ProcessState.ExitCode()
	}
	s.mu.Lock()
	wasAlive := s.alive
	s.alive = false
	s.exited = true
	s.mu.Unlock()
	if wasAlive {
		t.dispose(s, fmt.Sprintf("shell exited (%d)", code))
	}
}

// Attach serves one socket until it closes.
func (t *Terminals) Attach(s *termSession, ws *websocket.Conn) {
	s.mu.Lock()
	if s.reap != nil {
		s.reap.Stop()
		s.reap = nil
	}
	history := strings.Join(s.scroll, "")
	s.mu.Unlock()
	ctx := context.Background()
	if history != "" {
		wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		ws.Write(wctx, websocket.MessageText, []byte(history))
		cancel()
	}
	s.mu.Lock()
	s.sockets[ws] = true
	s.mu.Unlock()
	for {
		_, raw, err := ws.Read(ctx)
		if err != nil {
			break
		}
		var msg struct {
			Type string          `json:"type"`
			Data json.RawMessage `json:"data"`
			Cols any             `json:"cols"`
			Rows any             `json:"rows"`
		}
		if json.Unmarshal(raw, &msg) != nil {
			continue
		}
		switch msg.Type {
		case "input":
			var d string
			if json.Unmarshal(msg.Data, &d) == nil {
				s.mu.Lock()
				alive := s.alive
				s.mu.Unlock()
				if alive {
					s.f.Write([]byte(d))
				}
			}
		case "resize":
			t.resize(s, num(msg.Cols), num(msg.Rows))
		case "close":
			s.mu.Lock()
			s.exited = true
			s.mu.Unlock()
			t.dispose(s, "terminal closed")
		}
	}
	t.detach(s, ws)
}

func (t *Terminals) detach(s *termSession, ws *websocket.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.sockets, ws)
	if len(s.sockets) > 0 || !s.alive || s.reap != nil || s.keep {
		return
	}
	s.reap = time.AfterFunc(t.reapAfter, func() { t.dispose(s, "terminal timed out") })
}

func (t *Terminals) resize(s *termSession, cols, rows float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.alive || math.IsNaN(cols) || math.IsNaN(rows) || cols == 0 && rows == 0 {
		return
	}
	c := int(math.Max(2, math.Floor(cols)))
	r := int(math.Max(1, math.Floor(rows)))
	if c == s.cols && r == s.rows {
		return
	}
	s.cols, s.rows = c, r
	pty.Setsize(s.f, &pty.Winsize{Cols: uint16(c), Rows: uint16(r)})
}

func (t *Terminals) CloseMatching(prefix string) {
	t.mu.Lock()
	var list []*termSession
	for _, s := range t.sessions {
		if strings.HasPrefix(s.key, prefix) {
			list = append(list, s)
		}
	}
	t.mu.Unlock()
	for _, s := range list {
		s.mu.Lock()
		s.exited = true
		s.mu.Unlock()
		t.dispose(s, "terminal closed")
	}
}

func (t *Terminals) KillAll() {
	t.mu.Lock()
	var list []*termSession
	for _, s := range t.sessions {
		list = append(list, s)
	}
	t.mu.Unlock()
	for _, s := range list {
		t.dispose(s, "server shutting down")
	}
}

func (t *Terminals) dispose(s *termSession, reason string) {
	t.mu.Lock()
	if t.sessions[s.key] == s {
		delete(t.sessions, s.key)
	}
	t.mu.Unlock()
	s.mu.Lock()
	if s.reap != nil {
		s.reap.Stop()
		s.reap = nil
	}
	code := websocket.StatusGoingAway
	if s.exited {
		code = websocket.StatusNormalClosure
	}
	socks := s.sockets
	s.sockets = map[*websocket.Conn]bool{}
	alive := s.alive
	s.alive = false
	s.mu.Unlock()
	for ws := range socks {
		ws.Close(code, reason)
	}
	if alive {
		if s.cmd.Process != nil {
			s.cmd.Process.Kill()
		}
		s.f.Close()
	}
}

var errNoTerminal = errors.New("no terminal")
