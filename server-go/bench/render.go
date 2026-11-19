package bench

// Rendering a page to a picture or a PDF: on a linked device's Chrome when one offers it,
// else on the server's own Chromium. The interactive take-over browser never moves here.

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

// RenderSizes bound what a page may be rendered at.
const (
	renderMaxW = 2560
	renderMaxH = 4096
)

// RendererAvailable says whether any machine can draw a page for Bench.
func (s *Service) RendererAvailable() bool {
	if ChromiumPath() != "" {
		return true
	}
	dv, _ := s.Devices().Pick("render", "")
	return dv != nil || s.Workers().Pick("render", nil, "", nil) != nil
}

// Render draws a page and keeps the file, returning its path. A device does the work when
// one offers it; the server's Chromium is the fallback.
func (s *Service) Render(ctx context.Context, pageURL string, w, h int, format string) (string, error) {
	if pageURL == "" {
		return "", fmt.Errorf("Give a URL to render")
	}
	w, h = renderSize(w, h)
	if format != "pdf" {
		format = "png"
	}
	r, err := s.Jobs().Run(ctx, &JobSpec{
		Kind: "render", Timeout: 2 * time.Minute,
		Args: O("url", pageURL, "width", w, "height", h, "format", format),
		Fallback: func(ctx context.Context, _ *JobSpec) (*JobResult, error) {
			return renderHere(ctx, pageURL, w, h, format)
		},
	})
	if err != nil {
		return "", err
	}
	data, err := base64.StdEncoding.DecodeString(r.Image)
	if err != nil || len(data) == 0 {
		return "", fmt.Errorf("The render came back empty")
	}
	dir := filepath.Join(s.Store.Dir, "renders")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	full := filepath.Join(dir, "render-"+strconv.FormatInt(time.Now().UnixNano(), 36)+"."+format)
	if err := os.WriteFile(full, data, 0o644); err != nil {
		return "", err
	}
	return full, nil
}

func renderSize(w, h int) (int, int) {
	if w <= 0 {
		w = 1280
	}
	if h <= 0 {
		h = 900
	}
	if w > renderMaxW {
		w = renderMaxW
	}
	if h > renderMaxH {
		h = renderMaxH
	}
	return w, h
}

// renderHere is the server's renderer; a test swaps in a stand-in.
var renderHere = RenderHere

// RenderHere draws a page with the server's Chromium, headless and once.
func RenderHere(ctx context.Context, pageURL string, w, h int, format string) (*JobResult, error) {
	bin := ChromiumPath()
	if bin == "" {
		return nil, fmt.Errorf("Chromium isn't installed on the server")
	}
	dir, err := os.MkdirTemp("", "bench-render-")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	out := filepath.Join(dir, "out."+format)
	args := []string{"--headless=new", "--disable-gpu", "--hide-scrollbars", "--disable-dev-shm-usage",
		"--user-data-dir=" + filepath.Join(dir, "profile"), "--window-size=" + strconv.Itoa(w) + "," + strconv.Itoa(h)}
	if os.Getuid() == 0 {
		args = append(args, "--no-sandbox")
	}
	if format == "pdf" {
		args = append(args, "--no-pdf-header-footer", "--print-to-pdf="+out)
	} else {
		args = append(args, "--screenshot="+out, "--virtual-time-budget=5000")
	}
	args = append(args, pageURL)
	cctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	started := time.Now()
	cmd := exec.CommandContext(cctx, bin, args...)
	var tail tailBuffer
	cmd.Stdout = &tail
	cmd.Stderr = &tail
	runErr := cmd.Run()
	data, err := os.ReadFile(out)
	if err != nil || len(data) == 0 {
		if cctx.Err() != nil {
			return nil, fmt.Errorf("The render took too long")
		}
		if runErr != nil {
			return nil, fmt.Errorf("The browser could not draw the page: %v", runErr)
		}
		return nil, fmt.Errorf("The browser drew nothing")
	}
	return &JobResult{OK: true, Image: base64.StdEncoding.EncodeToString(data), Ms: time.Since(started).Milliseconds()}, nil
}
