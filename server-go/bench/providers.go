package bench

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Providers: where a session's requests go (src/providers.mjs).

var ambientRoutingVars = []string{"ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_MODEL",
	"ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL", "CLAUDE_CODE_EFFORT_LEVEL", "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"}

type hosted struct{ label, baseURL, keyEnv string }

var hostedProviders = map[string]hosted{
	"deepseek":   {"DeepSeek", "https://api.deepseek.com/anthropic", "DEEPSEEK_API_KEY"},
	"openrouter": {"OpenRouter", "https://openrouter.ai/api", "OPENROUTER_API_KEY"},
	"fireworks":  {"Fireworks", "https://api.fireworks.ai/inference", "FIREWORKS_API_KEY"},
}

// CleanEnv is the environment without anything that would route a session elsewhere.
func CleanEnv() []string {
	drop := map[string]bool{"PORT": true}
	for _, k := range ambientRoutingVars {
		drop[k] = true
	}
	var out []string
	for _, e := range os.Environ() {
		k := e
		if i := strings.IndexByte(e, '='); i >= 0 {
			k = e[:i]
		}
		if !drop[k] {
			out = append(out, e)
		}
	}
	return out
}

// mergeEnv sets each KEY=value of add over env.
func mergeEnv(env []string, add []string) []string {
	idx := map[string]int{}
	out := append([]string(nil), env...)
	for i, e := range out {
		if j := strings.IndexByte(e, '='); j >= 0 {
			idx[e[:j]] = i
		}
	}
	for _, e := range add {
		j := strings.IndexByte(e, '=')
		if j < 0 {
			continue
		}
		if i, ok := idx[e[:j]]; ok {
			out[i] = e
		} else {
			idx[e[:j]] = len(out)
			out = append(out, e)
		}
	}
	return out
}

func whereKeysGo(keyEnv string) string {
	root := Env("ROOT")
	if root == "" {
		root, _ = os.Getwd()
	}
	return fmt.Sprintf("Add %s=... to %s, then restart the server.", keyEnv, EnvFile(root))
}

func InvocationID(m *Obj) string {
	for _, k := range []string{"invocation_model_id", "provider_model_id", "name"} {
		if s := m.Str(k); s != "" {
			return s
		}
	}
	return ""
}

// providerConfigDir is a second Claude login's own folder, when the provider names one.
func providerConfigDir(p *Obj) string {
	return p.ObjAt("config").Str("claudeConfigDir")
}

// carryTranscript copies a conversation's transcript from whichever Claude config
// folder holds it into the one the session is about to resume on. Claude Code keeps
// transcripts per config folder, so a session moved to another Claude login would
// otherwise stop with "No conversation found with session ID".
func carryTranscript(toDir, id string, fromDirs []string) {
	if toDir == "" || id == "" {
		return
	}
	name := id + ".jsonl"
	find := func(dir string) string {
		m, _ := filepath.Glob(filepath.Join(dir, "projects", "*", name))
		if len(m) == 0 {
			return ""
		}
		return m[0]
	}
	if find(toDir) != "" {
		return
	}
	for _, from := range fromDirs {
		src := find(from)
		if src == "" || filepath.Clean(from) == filepath.Clean(toDir) {
			continue
		}
		b, err := os.ReadFile(src)
		if err != nil {
			continue
		}
		dst := filepath.Join(toDir, "projects", filepath.Base(filepath.Dir(src)))
		if err := os.MkdirAll(dst, 0o700); err != nil {
			continue
		}
		if err := os.WriteFile(filepath.Join(dst, name), b, 0o600); err != nil {
			continue
		}
		return
	}
}

// ProviderEnv is what sends a session to its provider, on top of CleanEnv.
func ProviderEnv(p *Obj, model *Obj) ([]string, error) {
	h, ok := hostedProviders[p.Str("kind")]
	if !ok {
		out := []string{"BENCH_PROVIDER=" + p.Str("id")}
		if d := providerConfigDir(p); d != "" && p.Str("kind") == "claude-code" {
			out = append(out, "CLAUDE_CONFIG_DIR="+expandHome(d))
		}
		return out, nil
	}
	cfg := p.ObjAt("config")
	keyEnv := cfg.Str("apiKeyEnv")
	if keyEnv == "" {
		keyEnv = h.keyEnv
	}
	key := os.Getenv(keyEnv)
	if key == "" {
		return nil, &HTTPError{Status: 400, Msg: fmt.Sprintf("Missing %s for the %s provider. %s", keyEnv, h.label, whereKeysGo(keyEnv)), Code: "AUTH_FAILURE"}
	}
	out := []string{"BENCH_PROVIDER=" + p.Str("id"), "ANTHROPIC_BASE_URL=" + h.baseURL, "ANTHROPIC_AUTH_TOKEN=" + key}
	if model != nil {
		mid := InvocationID(model)
		out = append(out, "ANTHROPIC_MODEL="+mid, "ANTHROPIC_DEFAULT_OPUS_MODEL="+mid, "ANTHROPIC_DEFAULT_SONNET_MODEL="+mid, "ANTHROPIC_DEFAULT_HAIKU_MODEL="+mid, "CLAUDE_CODE_SUBAGENT_MODEL="+mid)
	}
	window := "786432"
	if truthy(cfg.Get("autoCompactWindow")) {
		window = str(cfg.Get("autoCompactWindow"))
	}
	out = append(out, "CLAUDE_CODE_AUTO_COMPACT_WINDOW="+window, "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1")
	return out, nil
}

// ProviderReady says whether a provider can run now, and why not.
func ProviderReady(p *Obj) (bool, any) {
	if !p.Bool("enabled") {
		return false, "Disabled"
	}
	h, ok := hostedProviders[p.Str("kind")]
	if !ok {
		if p.Str("kind") == "claude-code" {
			d := providerConfigDir(p)
			if d == "" {
				return true, nil
			}
			if exists(filepath.Join(expandHome(d), ".credentials.json")) {
				return true, nil
			}
			return false, "Not logged in: run CLAUDE_CONFIG_DIR=" + d + " claude, then /login."
		}
		return false, "Unknown kind " + p.Str("kind")
	}
	keyEnv := p.ObjAt("config").Str("apiKeyEnv")
	if keyEnv == "" {
		keyEnv = h.keyEnv
	}
	if os.Getenv(keyEnv) != "" {
		return true, nil
	}
	return false, whereKeysGo(keyEnv)
}

// PriceUsage is what a turn cost at the model's published rates; false when unpriced.
func PriceUsage(m *Obj, input, output, cacheRead, cacheWrite float64) (float64, bool) {
	if m == nil || m.Get("input_cost_per_mtok") == nil {
		return 0, false
	}
	rate := func(k string) float64 {
		if v := m.Get(k); v != nil {
			return num(v)
		}
		return m.Num("input_cost_per_mtok")
	}
	return (m.Num("input_cost_per_mtok")*input + m.Num("output_cost_per_mtok")*output + rate("cache_read_cost_per_mtok")*cacheRead + rate("cache_write_cost_per_mtok")*cacheWrite) / 1e6, true
}
