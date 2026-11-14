package bench

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

)

// Notes: Markdown files beside the work, as src/notes.mjs keeps them.

const maxNote = 1 << 20
const maxIncluded = 24 * 1024
const maxIncludedTotal = 64 * 1024

var slugDrop = regexp.MustCompile(`[^\w\s-]+`)
var slugSpace = regexp.MustCompile(`[\s_]+`)
var slugDash = regexp.MustCompile(`-+`)
var slugEdge = regexp.MustCompile(`^-+|-+$`)

// NoteSlug: "KYC flow: cutting spend" -> "kyc-flow-cutting-spend".
func NoteSlug(title string) string {
	s := foldAccents(strings.ToLower(title))
	s = slugDrop.ReplaceAllString(s, " ")
	s = strings.TrimSpace(s)
	s = slugSpace.ReplaceAllString(s, "-")
	s = slugDash.ReplaceAllString(s, "-")
	if r := []rune(s); len(r) > 60 {
		s = string(r[:60])
	}
	s = slugEdge.ReplaceAllString(s, "")
	if s == "" {
		return "note"
	}
	return s
}

var headingRe = regexp.MustCompile(`^#\s+(.+?)\s*#*\s*$`)

func TitleOf(text, name string) string {
	lines := strings.SplitN(text, "\n", 31)
	if len(lines) > 30 {
		lines = lines[:30]
	}
	for _, line := range lines {
		if m := headingRe.FindStringSubmatch(line); m != nil {
			return m[1]
		}
		if strings.TrimSpace(line) != "" && !strings.HasPrefix(line, "---") {
			break
		}
	}
	return regexp.MustCompile(`(?i)\.md$`).ReplaceAllString(name, "")
}

var noteName = regexp.MustCompile(`(?i)^[\w][\w .-]{0,99}\.md$`)

func checkedNote(name any) (string, error) {
	s, ok := name.(string)
	if !ok || !noteName.MatchString(s) || strings.Contains(s, "..") {
		return "", fail("Not a note name")
	}
	return s, nil
}

type Notes struct {
	Global    string
	FlagsFile string
}

func NewNotes(dataDir string) *Notes {
	return &Notes{Global: filepath.Join(dataDir, "notes"), FlagsFile: filepath.Join(dataDir, "notes-included.json")}
}

func (n *Notes) Dir(projectPath string) string {
	if projectPath != "" {
		return filepath.Join(projectPath, "notes")
	}
	return n.Global
}

func (n *Notes) flags() *Obj {
	b, err := os.ReadFile(n.FlagsFile)
	if err != nil {
		return O()
	}
	o, ok := ParseJSON(string(b)).(*Obj)
	if !ok {
		return O()
	}
	return o
}

func mtimeMs(st os.FileInfo) float64 {
	return float64(st.ModTime().UnixNano()) / 1e6
}

func (n *Notes) List(projectPath string) []any {
	dir := n.Dir(projectPath)
	ents, err := os.ReadDir(dir)
	if err != nil {
		return []any{}
	}
	flags := n.flags()
	var out []*Obj
	for _, e := range ents {
		name := e.Name()
		if !strings.HasSuffix(strings.ToLower(name), ".md") || strings.HasPrefix(name, ".") {
			continue
		}
		full := filepath.Join(dir, name)
		st, err := os.Stat(full)
		if err != nil || !st.Mode().IsRegular() {
			continue
		}
		head := ""
		if f, err := os.Open(full); err == nil {
			buf := make([]byte, int(math.Min(float64(st.Size()), 2048)))
			k, _ := f.Read(buf)
			f.Close()
			head = string(buf[:k])
		}
		out = append(out, O("name", name, "title", TitleOf(head, name), "mtime", mtimeMs(st), "size", float64(st.Size()), "include", truthy(flags.Get(full))))
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Num("mtime") > out[j].Num("mtime") })
	res := make([]any, len(out))
	for i, o := range out {
		res[i] = o
	}
	return res
}

func (n *Notes) Read(projectPath string, name any) (*Obj, error) {
	nm, err := checkedNote(name)
	if err != nil {
		return nil, err
	}
	full := filepath.Join(n.Dir(projectPath), nm)
	st, err := os.Stat(full)
	if err != nil {
		return nil, fail("No such note", 404)
	}
	if st.Size() > maxNote {
		return nil, fail("Too large to open as a note", 413)
	}
	b, _ := os.ReadFile(full)
	text := string(b)
	return O("name", nm, "title", TitleOf(text, nm), "text", text, "mtime", mtimeMs(st), "size", float64(st.Size()), "path", full, "include", truthy(n.flags().Get(full))), nil
}

func (n *Notes) Write(projectPath string, name, text any, mtime float64) (*Obj, error) {
	t, ok := text.(string)
	if !ok {
		return nil, fail("No text")
	}
	if len(t) > maxNote {
		return nil, fail("Too large for a note", 413)
	}
	nm, err := checkedNote(name)
	if err != nil {
		return nil, err
	}
	dir := n.Dir(projectPath)
	full := filepath.Join(dir, nm)
	if mtime != 0 {
		if st, err := os.Stat(full); err == nil && math.Abs(mtimeMs(st)-mtime) > 1 {
			return nil, fail("The note changed since you opened it", 409)
		}
	}
	os.MkdirAll(dir, 0o755)
	if err := os.WriteFile(full, []byte(t), 0o644); err != nil {
		return nil, fail(err.Error())
	}
	return n.Read(projectPath, nm)
}

func (n *Notes) Create(projectPath string, title any, text *string) (*Obj, error) {
	t := strings.TrimSpace(str(title))
	if t == "" {
		t = "Untitled"
	}
	dir := n.Dir(projectPath)
	os.MkdirAll(dir, 0o755)
	base := NoteSlug(t)
	name := base + ".md"
	for k := 2; exists(filepath.Join(dir, name)); k++ {
		name = fmt.Sprintf("%s-%d.md", base, k)
	}
	body := "# " + t + "\n\n"
	if text != nil {
		body = *text
	}
	return n.Write(projectPath, name, body, 0)
}

func (n *Notes) Remove(projectPath string, name any) error {
	nm, err := checkedNote(name)
	if err != nil {
		return err
	}
	full := filepath.Join(n.Dir(projectPath), nm)
	os.RemoveAll(full)
	n.flag(full, false)
	return nil
}

func (n *Notes) flag(full string, on bool) {
	flags := n.flags()
	if on {
		flags.Set(full, true)
	} else {
		flags.Delete(full)
	}
	for _, k := range append([]string(nil), flags.Keys()...) {
		if !exists(k) {
			flags.Delete(k)
		}
	}
	os.MkdirAll(filepath.Dir(n.FlagsFile), 0o755)
	// JSON.stringify(flags, null, 1)
	var b strings.Builder
	if flags.Len() == 0 {
		b.WriteString("{}")
	} else {
		b.WriteString("{\n")
		for i, k := range flags.Keys() {
			kb, _ := json.Marshal(k)
			b.WriteString(" " + string(kb) + ": true")
			if i < flags.Len()-1 {
				b.WriteString(",")
			}
			b.WriteString("\n")
		}
		b.WriteString("}")
	}
	os.WriteFile(n.FlagsFile, []byte(b.String()), 0o644)
}

func (n *Notes) SetInclude(projectPath string, name any, on bool) (*Obj, error) {
	note, err := n.Read(projectPath, name)
	if err != nil {
		return nil, err
	}
	n.flag(note.Str("path"), on)
	return note.Clone().Set("include", on), nil
}

func (n *Notes) Move(name any, projectPath string) (string, error) {
	nm, err := checkedNote(name)
	if err != nil {
		return "", err
	}
	from := filepath.Join(n.Global, nm)
	if !exists(from) {
		return "", fail("No such note", 404)
	}
	dir := n.Dir(projectPath)
	os.MkdirAll(dir, 0o755)
	to := filepath.Join(dir, nm)
	for k := 2; exists(to); k++ {
		to = filepath.Join(dir, regexp.MustCompile(`(?i)\.md$`).ReplaceAllString(nm, fmt.Sprintf("-%d.md", k)))
	}
	on := truthy(n.flags().Get(from))
	b, err := os.ReadFile(from)
	if err != nil {
		return "", err
	}
	os.WriteFile(to, b, 0o644)
	os.Remove(from)
	n.flag(from, false)
	if on {
		n.flag(to, true)
	}
	return filepath.Base(to), nil
}

// Resolve is the file a message's `@note:name` means.
func (n *Notes) Resolve(projectPath, token string) string {
	name := token
	if !strings.HasSuffix(strings.ToLower(token), ".md") {
		name = token + ".md"
	}
	if !noteName.MatchString(name) || strings.Contains(name, "..") {
		return ""
	}
	dirs := []string{n.Global}
	if projectPath != "" {
		dirs = []string{n.Dir(projectPath), n.Global}
	}
	for _, d := range dirs {
		if full := filepath.Join(d, name); exists(full) {
			return full
		}
	}
	return ""
}

func (n *Notes) Suggest(projectPath, q string) []any {
	needle := strings.TrimPrefix(strings.ToLower(q), "note:")
	out := []any{}
	seen := map[string]bool{}
	var proj []any
	if projectPath != "" {
		proj = n.List(projectPath)
	}
	for _, grp := range []struct {
		where string
		list  []any
	}{{"project", proj}, {"notes", n.List("")}} {
		for _, x := range grp.list {
			nt := x.(*Obj)
			stem := regexp.MustCompile(`(?i)\.md$`).ReplaceAllString(nt.Str("name"), "")
			if seen[stem] || (needle != "" && !strings.Contains(strings.ToLower(stem), needle) && !strings.Contains(strings.ToLower(nt.Str("title")), needle)) {
				continue
			}
			seen[stem] = true
			out = append(out, O("token", "note:"+stem, "title", nt.Str("title"), "where", grp.where))
		}
	}
	filtered := []any{}
	for _, x := range out {
		if !spaceRe.MatchString(x.(*Obj).Str("token")) {
			filtered = append(filtered, x)
		}
	}
	if len(filtered) > 8 {
		filtered = filtered[:8]
	}
	return filtered
}

func (n *Notes) Brief(projectPath string) string {
	var where string
	if projectPath != "" {
		where = fmt.Sprintf("This project's notes are Markdown files in %s; notes that belong to no project are in %s.", n.Dir(projectPath), n.Global)
	} else {
		where = fmt.Sprintf("The person's notes are Markdown files in %s.", n.Global)
	}
	out := "# Notes\n\n" + where + " They are the person's own working notes - plans, specs, things to remember - and Bench shows them beside their sessions. When asked to save or write something as a note, write a Markdown file there, starting with a `# Title` line, under a short lowercase-hyphenated name, and say its path in your reply. Read a note when the person points at one; do not go through them unasked. Settled decisions and standing rules for a project belong in its CLAUDE.md, not in a note."
	flags := n.flags()
	keys := append([]string(nil), flags.Keys()...)
	sort.Strings(keys)
	total := 0
	for _, full := range keys {
		dir := filepath.Dir(full)
		if dir != n.Global && !(projectPath != "" && dir == n.Dir(projectPath)) {
			continue
		}
		b, err := os.ReadFile(full)
		if err != nil {
			continue
		}
		text := string(b)
		if jsLen(text) > maxIncluded {
			text = text[:maxIncluded] + "\n\n(The rest is in the file.)"
		}
		if total+jsLen(text) > maxIncludedTotal {
			break
		}
		total += jsLen(text)
		out += fmt.Sprintf("\n\n## A note the person keeps in every session: %s\n\n%s", full, strings.TrimSpace(text))
	}
	return out
}
