// bench-worker joins this machine to a Bench server's compute pool.
//
//	bench-worker enrol --server https://bench.example --token <your device token> --name Builder
//	bench-worker run [--config ~/.config/bench-worker/config.json]
//	bench-worker status
//	bench-worker stop
//
// run links out to the server and keeps the link by reconnecting. The server falls back to its
// own work on every failure, so this machine is never required for Bench to answer.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"bench/server-go/worker"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "run":
		err = cmdRun(os.Args[2:])
	case "enrol":
		err = cmdEnrol(os.Args[2:])
	case "status":
		err = cmdStatus(os.Args[2:])
	case "stop":
		err = cmdStop(os.Args[2:])
	case "version", "--version", "-v":
		fmt.Println("bench-worker", worker.Version)
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "bench-worker:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `bench-worker joins this machine to a Bench compute pool.

  bench-worker enrol --server URL --token TOKEN [--name NAME] [--labels a,b] [--slots N]
  bench-worker run   [--config FILE] [--server URL] [--token TOKEN] [--name NAME]
                     [--labels a,b] [--kinds exec,check,render] [--slots N] [--chrome PATH]
  bench-worker status [--config FILE]
  bench-worker stop   [--config FILE]
`)
	os.Exit(2)
}

// configPath is where enrol writes and run reads the worker's settings.
func configPath(flags map[string]string) string {
	if p := flags["config"]; p != "" {
		return p
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = "."
	}
	return filepath.Join(dir, "bench-worker", "config.json")
}

func parseFlags(args []string) (map[string]string, error) {
	out := map[string]string{}
	for i := 0; i < len(args); i++ {
		a := args[i]
		if !strings.HasPrefix(a, "--") {
			return nil, fmt.Errorf("unexpected %q", a)
		}
		name := strings.TrimPrefix(a, "--")
		if eq := strings.IndexByte(name, '='); eq >= 0 {
			out[name[:eq]] = name[eq+1:]
			continue
		}
		if i+1 >= len(args) || strings.HasPrefix(args[i+1], "--") {
			out[name] = "true"
			continue
		}
		i++
		out[name] = args[i]
	}
	return out, nil
}

func loadConfig(flags map[string]string) (worker.Config, error) {
	var cfg worker.Config
	if raw, err := os.ReadFile(configPath(flags)); err == nil {
		if err := json.Unmarshal(raw, &cfg); err != nil {
			return cfg, fmt.Errorf("the config file is not readable: %v", err)
		}
	}
	if v := flags["server"]; v != "" {
		cfg.Server = v
	}
	if v := flags["token"]; v != "" {
		cfg.Token = v
	}
	if v := flags["name"]; v != "" {
		cfg.Name = v
	}
	if v := flags["cache"]; v != "" {
		cfg.Cache = v
	}
	if v := flags["chrome"]; v != "" {
		cfg.Chrome = v
	}
	if v := flags["labels"]; v != "" {
		cfg.Labels = splitList(v)
	}
	if v := flags["kinds"]; v != "" {
		cfg.Kinds = splitList(v)
	}
	if v := flags["slots"]; v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			return cfg, fmt.Errorf("--slots takes a number of 1 or more")
		}
		cfg.Slots = n
	}
	return cfg, nil
}

func splitList(s string) []string {
	out := []string{}
	for _, x := range strings.Split(s, ",") {
		if x = strings.TrimSpace(x); x != "" {
			out = append(out, x)
		}
	}
	return out
}

func cmdRun(args []string) error {
	flags, err := parseFlags(args)
	if err != nil {
		return err
	}
	cfg, err := loadConfig(flags)
	if err != nil {
		return err
	}
	if err := cfg.Normalise(); err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	pid := filepath.Join(cfg.Cache, "worker.pid")
	os.MkdirAll(cfg.Cache, 0o755)
	os.WriteFile(pid, []byte(strconv.Itoa(os.Getpid())), 0o644)
	defer os.Remove(pid)
	logf := func(format string, a ...any) {
		fmt.Printf("%s "+format+"\n", append([]any{time.Now().Format("15:04:05")}, a...)...)
	}
	logf("worker %s (%s), kinds %s, cache %s", cfg.Name, cfg.Platform, strings.Join(cfg.Kinds, ","), cfg.Cache)
	err = worker.Run(ctx, cfg, logf)
	if err == context.Canceled {
		return nil
	}
	return err
}

func cmdEnrol(args []string) error {
	flags, err := parseFlags(args)
	if err != nil {
		return err
	}
	server := strings.TrimRight(flags["server"], "/")
	token := flags["token"]
	if server == "" || token == "" {
		return fmt.Errorf("enrol needs --server and --token (a Bench device token)")
	}
	body, _ := json.Marshal(map[string]any{
		"name": flags["name"], "platform": flags["platform"],
		"labels": splitList(flags["labels"]), "slots": atoi(flags["slots"], 1),
	})
	req, err := http.NewRequest("POST", server+"/api/workers", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return fmt.Errorf("enrol failed: %s", strings.TrimSpace(string(raw)))
	}
	var out struct {
		Worker struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"worker"`
		Token   string `json:"token"`
		Command string `json:"command"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return err
	}
	cfg := worker.Config{Server: server, Token: out.Token, Name: out.Worker.Name,
		Labels: splitList(flags["labels"]), Slots: atoi(flags["slots"], 1)}
	path := configPath(flags)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	saved, _ := json.MarshalIndent(cfg, "", "  ")
	if err := os.WriteFile(path, saved, 0o600); err != nil {
		return err
	}
	fmt.Printf("Enrolled %q (%s).\nSettings written to %s\nStart it with:\n\n  %s\n\n", out.Worker.Name, out.Worker.ID, path, out.Command)
	return nil
}

func cmdStatus(args []string) error {
	flags, err := parseFlags(args)
	if err != nil {
		return err
	}
	cfg, err := loadConfig(flags)
	if err != nil {
		return err
	}
	if err := cfg.Normalise(); err != nil {
		return err
	}
	req, err := http.NewRequest("GET", cfg.Server+"/api/workers", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return fmt.Errorf("status failed: %s", strings.TrimSpace(string(raw)))
	}
	fmt.Println(strings.TrimSpace(string(raw)))
	return nil
}

func cmdStop(args []string) error {
	flags, err := parseFlags(args)
	if err != nil {
		return err
	}
	cfg, err := loadConfig(flags)
	if err != nil {
		return err
	}
	if cfg.Cache == "" {
		if err := cfg.Normalise(); err != nil {
			return err
		}
	}
	raw, err := os.ReadFile(filepath.Join(cfg.Cache, "worker.pid"))
	if err != nil {
		return fmt.Errorf("no running worker found")
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	if err != nil {
		return fmt.Errorf("the pid file is not readable")
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	return p.Signal(syscall.SIGTERM)
}

func atoi(s string, def int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < 1 {
		return def
	}
	return n
}
