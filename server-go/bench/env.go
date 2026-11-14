package bench

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Env reads BENCH_<name>, or AI_CODE_<name> from before the rename.
func Env(name string) string {
	if v, ok := os.LookupEnv("BENCH_" + name); ok {
		return v
	}
	return os.Getenv("AI_CODE_" + name)
}

func envSet(name string) bool {
	_, a := os.LookupEnv("BENCH_" + name)
	_, b := os.LookupEnv("AI_CODE_" + name)
	return a || b
}

var ProviderKeys = []string{"DEEPSEEK_API_KEY", "OPENROUTER_API_KEY", "FIREWORKS_API_KEY"}

func EnvFile(root string) string { return filepath.Join(root, "env") }

var envLine = regexp.MustCompile(`^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$`)
var dq = regexp.MustCompile(`^"(.*)"$`)
var sq = regexp.MustCompile(`^'(.*)'$`)
var dqEsc = regexp.MustCompile(`\\(["\\$` + "`" + `])`)
var trailingComment = regexp.MustCompile(`\s+#.*$`)

// ParseEnv reads KEY=value lines the way a shell profile writes them.
func ParseEnv(text string) map[string]string {
	out := map[string]string{}
	for _, raw := range regexp.MustCompile(`\r?\n`).Split(text, -1) {
		m := envLine.FindStringSubmatch(raw)
		if m == nil || strings.HasPrefix(strings.TrimSpace(raw), "#") {
			continue
		}
		v := m[2]
		if dq.MatchString(v) {
			v = dqEsc.ReplaceAllString(v[1:len(v)-1], "$1")
		} else if sq.MatchString(v) {
			v = v[1 : len(v)-1]
		} else {
			v = trailingComment.ReplaceAllString(v, "")
		}
		out[m[1]] = v
	}
	return out
}

func ReadEnvFile(root string) map[string]string {
	b, err := os.ReadFile(EnvFile(root))
	if err != nil {
		return map[string]string{}
	}
	return ParseEnv(string(b))
}

// LoadEnvFile puts the file's variables into the environment; one already set wins.
func LoadEnvFile(root string) []string {
	var set []string
	vars := ReadEnvFile(root)
	for _, k := range sortedKeys(vars) {
		v := vars[k]
		if _, ok := os.LookupEnv(k); !ok && v != "" {
			os.Setenv(k, v)
			set = append(set, k)
		}
	}
	return set
}

var plainEnvValue = regexp.MustCompile(`^[\w@%+=:,./-]*$`)

// AddToEnvFile adds variables the file does not have yet. Owner-only: it holds keys.
func AddToEnvFile(root string, vars map[string]string) []string {
	have := ReadEnvFile(root)
	var add []string
	for _, k := range sortedKeys(vars) {
		if vars[k] != "" && have[k] == "" {
			add = append(add, k)
		}
	}
	if len(add) == 0 {
		return nil
	}
	file := EnvFile(root)
	os.MkdirAll(root, 0o755)
	text := "# Read by the Bench server at startup. One KEY=value per line.\n"
	if b, err := os.ReadFile(file); err == nil {
		text = string(b)
	}
	if text != "" && !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	var lines []string
	for _, k := range add {
		v := vars[k]
		if !plainEnvValue.MatchString(v) {
			v = `"` + regexp.MustCompile(`(["\\$`+"`"+`])`).ReplaceAllString(v, `\$1`) + `"`
		}
		lines = append(lines, k+"="+v)
	}
	text += strings.Join(lines, "\n") + "\n"
	os.WriteFile(file, []byte(text), 0o600)
	os.Chmod(file, 0o600)
	return add
}

// HTTPError carries a status and the extra fields an error answer may name.
type HTTPError struct {
	Status    int
	Msg       string
	Code      string
	Conflicts []string
	Dirty     []string
	Extra     *Obj
}

func (e *HTTPError) Error() string { return e.Msg }

func fail(msg string, status ...int) *HTTPError {
	s := 400
	if len(status) > 0 {
		s = status[0]
	}
	return &HTTPError{Status: s, Msg: msg}
}

func failf(status int, format string, a ...any) *HTTPError {
	return &HTTPError{Status: status, Msg: fmt.Sprintf(format, a...)}
}

func errMsg(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
