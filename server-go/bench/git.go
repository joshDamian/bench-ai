package bench

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Git, run as a child process, as src/git.mjs does.

type GitErr struct {
	Stdout, Stderr, Message string
	Code                    string
	Conflicts               []string
	Dirty                   []string
}

func (e *GitErr) Error() string { return e.Message }

// Git runs git and returns stdout without its trailing whitespace.
func Git(cwd string, args []string, env ...string) (string, error) {
	return gitInput(cwd, args, "", env...)
}

func gitInput(cwd string, args []string, input string, env ...string) (string, error) {
	cmd := exec.Command("git", args...)
	cmd.Dir = cwd
	if len(env) > 0 {
		cmd.Env = append(os.Environ(), env...)
	}
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if input != "" {
		cmd.Stdin = strings.NewReader(input)
	}
	err := cmd.Run()
	s := strings.TrimRight(out.String(), " \t\r\n")
	if err != nil {
		return s, &GitErr{Stdout: out.String(), Stderr: errb.String(), Message: fmt.Sprintf("Command failed: git %s\n%s", strings.Join(args, " "), errb.String())}
	}
	return s, nil
}

func gitMust(cwd string, args ...string) string {
	s, _ := Git(cwd, args)
	return s
}

var gitPrefix = regexp.MustCompile(`(?m)^(error|fatal): `)

// GitError is the error git printed rather than the wrapper around it.
func GitError(err error) string {
	var text string
	var ge *GitErr
	if errors.As(err, &ge) {
		text = ge.Stderr
		if strings.TrimSpace(text) == "" {
			text = ge.Stdout
		}
		if strings.TrimSpace(text) == "" {
			text = ge.Message
		}
	} else if err != nil {
		text = err.Error()
	}
	text = gitPrefix.ReplaceAllString(strings.TrimSpace(text), "")
	lines := strings.Split(text, "\n")
	if len(lines) > 6 {
		lines = lines[:6]
	}
	return strings.Join(lines, "\n")
}

// GitAsync talks to a remote: no credential prompt, and a timeout.
func GitAsync(cwd string, args []string, timeout time.Duration) (string, error) {
	if timeout == 0 {
		timeout = 120 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "git", args...)
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		return "", &GitErr{Message: GitError(&GitErr{Stdout: out.String(), Stderr: errb.String(), Message: err.Error()}), Code: "GIT"}
	}
	return strings.TrimRight(out.String(), " \t\r\n"), nil
}

func IsRepo(dir string) bool {
	s, err := Git(dir, []string{"rev-parse", "--is-inside-work-tree"})
	return err == nil && s == "true"
}

func TopLevel(dir string) (string, error) { return Git(dir, []string{"rev-parse", "--show-toplevel"}) }

// RevParse is the commit a ref names, or "".
func RevParse(dir string, ref string) string {
	if ref == "" {
		ref = "HEAD"
	}
	s, err := Git(dir, []string{"rev-parse", "--verify", "--quiet", ref + "^{commit}"})
	if err != nil {
		return ""
	}
	return s
}

func CurrentBranch(dir string) string {
	s, err := Git(dir, []string{"symbolic-ref", "--short", "-q", "HEAD"})
	if err != nil {
		return ""
	}
	return s
}

func IsAncestor(dir, a, b string) bool {
	cmd := exec.Command("git", "merge-base", "--is-ancestor", a, b)
	cmd.Dir = dir
	return cmd.Run() == nil
}

func MergeBase(dir, a, b string) string {
	s, err := Git(dir, []string{"merge-base", a, b})
	if err != nil {
		return ""
	}
	return s
}

func gitCount(dir, rng string) int {
	s, err := Git(dir, []string{"rev-list", "--count", rng})
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(s)
	return n
}

func HasRemote(dir string) bool {
	s, err := Git(dir, []string{"remote"})
	return err == nil && s != ""
}

type GitStatus struct {
	Branch, Head, Upstream any // string or nil
	Ahead, Behind          int
	Dirty, Conflicted      []string
}

func (s GitStatus) Obj() *Obj {
	return O("branch", s.Branch, "head", s.Head, "upstream", s.Upstream, "ahead", float64(s.Ahead), "behind", float64(s.Behind), "dirty", strs(s.Dirty), "conflicted", strs(s.Conflicted))
}

func strs(a []string) []any {
	out := make([]any, len(a))
	for i, s := range a {
		out[i] = s
	}
	return out
}

var abRe = regexp.MustCompile(`\+(\d+) -(\d+)`)

// Status reads the working tree in one `git status --porcelain=v2`.
func Status(dir string) (GitStatus, error) {
	out, err := Git(dir, []string{"status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all"})
	s := GitStatus{Dirty: []string{}, Conflicted: []string{}}
	if err != nil {
		return s, err
	}
	parts := strings.Split(out, "\x00")
	for i := 0; i < len(parts); i++ {
		line := parts[i]
		if line == "" {
			continue
		}
		switch {
		case strings.HasPrefix(line, "# branch.oid "):
			if v := line[13:]; v != "(initial)" {
				s.Head = v
			}
		case strings.HasPrefix(line, "# branch.head "):
			if v := line[14:]; v != "(detached)" {
				s.Branch = v
			}
		case strings.HasPrefix(line, "# branch.upstream "):
			s.Upstream = line[18:]
		case strings.HasPrefix(line, "# branch.ab "):
			if m := abRe.FindStringSubmatch(line); m != nil {
				s.Ahead, _ = strconv.Atoi(m[1])
				s.Behind, _ = strconv.Atoi(m[2])
			}
		case strings.HasPrefix(line, "1 "):
			s.Dirty = append(s.Dirty, fieldsFrom(line, 8))
		case strings.HasPrefix(line, "2 "):
			s.Dirty = append(s.Dirty, fieldsFrom(line, 9))
			i++
		case strings.HasPrefix(line, "u "):
			p := fieldsFrom(line, 10)
			s.Conflicted = append(s.Conflicted, p)
			s.Dirty = append(s.Dirty, p)
		case strings.HasPrefix(line, "? "):
			s.Dirty = append(s.Dirty, line[2:])
		}
	}
	return s, nil
}

// fieldsFrom is line.split(' ').slice(n).join(' ').
func fieldsFrom(line string, n int) string {
	parts := strings.Split(line, " ")
	if n >= len(parts) {
		return ""
	}
	return strings.Join(parts[n:], " ")
}

func isTaskBranch(b string) bool { return strings.HasPrefix(b, "bench/") || strings.HasPrefix(b, "ai-code/") }

func splitLines(s string) []string {
	var out []string
	for _, l := range strings.Split(s, "\n") {
		if l != "" {
			out = append(out, l)
		}
	}
	return out
}

func Branches(dir string) []any {
	cur := CurrentBranch(dir)
	local := splitLines(gitMust(dir, "for-each-ref", "--format=%(refname:short)", "--sort=-committerdate", "refs/heads"))
	var remote []string
	for _, r := range splitLines(gitMust(dir, "for-each-ref", "--format=%(refname:short)", "--sort=-committerdate", "refs/remotes")) {
		if !strings.HasSuffix(r, "/HEAD") && strings.Contains(r, "/") {
			remote = append(remote, r)
		}
	}
	have := map[string]bool{}
	for _, b := range local {
		have[b] = true
	}
	out := []any{}
	for _, b := range local {
		if !isTaskBranch(b) {
			out = append(out, O("name", b, "remoteOnly", false, "current", b == cur))
		}
	}
	for _, r := range remote {
		name := r[strings.Index(r, "/")+1:]
		if have[name] || isTaskBranch(name) {
			continue
		}
		have[name] = true
		out = append(out, O("name", name, "remoteOnly", true, "current", false))
	}
	return out
}

func GitState(dir string) (*Obj, error) {
	s, err := Status(dir)
	if err != nil {
		return nil, fail(GitError(err))
	}
	return s.Obj().Set("branches", Branches(dir)).Set("hasRemote", HasRemote(dir)), nil
}

func CheckedOutAt(dir string) map[string]string {
	m := map[string]string{}
	at := ""
	for _, l := range strings.Split(gitMust(dir, "worktree", "list", "--porcelain"), "\n") {
		if strings.HasPrefix(l, "worktree ") {
			at = l[9:]
		} else if strings.HasPrefix(l, "branch refs/heads/") && at != "" {
			m[l[18:]] = at
		}
	}
	return m
}

func EnsureLocalBranch(dir, name string) error {
	if RevParse(dir, "refs/heads/"+name) != "" {
		return nil
	}
	r := splitLines(gitMust(dir, "for-each-ref", "--format=%(refname:short)", "refs/remotes/*/"+name))
	if len(r) == 0 {
		return fmt.Errorf("No branch named %s", name)
	}
	_, err := Git(dir, []string{"branch", "--track", name, r[0]})
	return err
}

func plural(n int, word string) string {
	if n == 1 {
		return word
	}
	return word + "s"
}

func GitCheckout(dir, name string, create bool, from string, stash bool) (*Obj, error) {
	s, err := Status(dir)
	if err != nil {
		return nil, err
	}
	if len(s.Conflicted) > 0 {
		return nil, fmt.Errorf("Resolve the conflicts first: %s", strings.Join(s.Conflicted, ", "))
	}
	stashed := false
	branch := "HEAD"
	if b, ok := s.Branch.(string); ok && b != "" {
		branch = b
	}
	if len(s.Dirty) > 0 {
		if !stash {
			return nil, &GitErr{Message: fmt.Sprintf("%d uncommitted %s on %s", len(s.Dirty), plural(len(s.Dirty), "change"), branch), Code: "DIRTY", Dirty: s.Dirty}
		}
		Git(dir, []string{"stash", "push", "--include-untracked", "-m", fmt.Sprintf("bench: switching from %s to %s", branch, name)})
		stashed = true
	}
	var e error
	if create {
		args := []string{"switch", "-c", name}
		if from != "" {
			args = append(args, from)
		}
		_, e = Git(dir, args)
	} else {
		if e = EnsureLocalBranch(dir, name); e == nil {
			_, e = Git(dir, []string{"switch", name})
		}
	}
	if e != nil {
		if stashed {
			Git(dir, []string{"stash", "pop"})
		}
		return nil, errors.New(GitError(e))
	}
	return O("branch", name, "stashed", stashed), nil
}

func FetchAll(dir string) error { _, err := GitAsync(dir, []string{"fetch", "--all", "--prune"}, 0); return err }
func Pull(dir string) error     { _, err := GitAsync(dir, []string{"pull", "--ff-only"}, 0); return err }
func Push(dir, branch string) error {
	remote := "origin"
	if r := strings.Split(gitMust(dir, "remote"), "\n")[0]; r != "" {
		remote = r
	}
	_, err := GitAsync(dir, []string{"push", "-u", remote, branch}, 0)
	return err
}

// CommitAll stages everything and commits; "" when there was nothing.
func CommitAll(dir, message string) (string, error) {
	s, err := Status(dir)
	if err != nil {
		return "", err
	}
	if len(s.Dirty) == 0 {
		return "", nil
	}
	if _, err := Git(dir, []string{"add", "-A"}); err != nil {
		return "", err
	}
	if _, err := Git(dir, []string{"commit", "-q", "-m", message}); err != nil {
		return "", err
	}
	return Git(dir, []string{"rev-parse", "HEAD"})
}

func WorktreeRoot(repo string) string {
	if v := Env("WORKTREE_ROOT"); v != "" {
		return v
	}
	return filepath.Join(filepath.Dir(repo), ".bench-worktrees-"+filepath.Base(repo))
}

func CreateWorktree(repo, dir, branch, base string) error {
	os.MkdirAll(filepath.Dir(dir), 0o755)
	if _, err := Git(repo, []string{"worktree", "add", "-q", "-b", branch, dir, base}); err != nil {
		return err
	}
	nm := filepath.Join(repo, "node_modules")
	link := filepath.Join(dir, "node_modules")
	if exists(nm) && !exists(link) {
		ExcludeLine(repo, "/node_modules")
		os.Symlink(nm, link)
	}
	return nil
}

func RemoveWorktree(repo, dir string) error {
	var err error
	if exists(dir) {
		_, err = Git(repo, []string{"worktree", "remove", "--force", dir})
	} else {
		_, err = Git(repo, []string{"worktree", "prune"})
	}
	return err
}

func DeleteBranch(repo, branch string) error {
	_, err := Git(repo, []string{"branch", "-D", branch})
	return err
}

func ExcludeLine(repo, line string) {
	common, err := Git(repo, []string{"rev-parse", "--git-common-dir"})
	if err != nil {
		return
	}
	if !filepath.IsAbs(common) {
		common = filepath.Join(repo, common)
	}
	f := filepath.Join(common, "info", "exclude")
	os.MkdirAll(filepath.Dir(f), 0o755)
	b, _ := os.ReadFile(f)
	s := string(b)
	for _, x := range strings.Split(s, "\n") {
		if strings.TrimSpace(x) == line {
			return
		}
	}
	pre := ""
	if s != "" && !strings.HasSuffix(s, "\n") {
		pre = "\n"
	}
	fh, err := os.OpenFile(f, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err == nil {
		fh.WriteString(pre + line + "\n")
		fh.Close()
	}
}

func tmpIndex() string {
	return filepath.Join(os.TempDir(), fmt.Sprintf("bench-index-%d-%s", os.Getpid(), strconv.FormatInt(rand.Int63(), 36)))
}

func diffWorking(dir, ref string, extra ...string) (string, error) {
	tmp := tmpIndex()
	defer os.Remove(tmp)
	env := "GIT_INDEX_FILE=" + tmp
	if _, err := Git(dir, []string{"read-tree", ref}, env); err != nil {
		return "", err
	}
	if _, err := Git(dir, []string{"add", "-A"}, env); err != nil {
		return "", err
	}
	args := append([]string{"diff", "--cached", "--no-color", "--no-ext-diff"}, extra...)
	return Git(dir, append(args, ref), env)
}

func WorkingDiff(dir, ref string) (string, error) { return diffWorking(dir, ref) }

// WorkingTree is the tree id the working directory would commit as.
func WorkingTree(dir string) (string, error) {
	tmp := tmpIndex()
	defer os.Remove(tmp)
	env := "GIT_INDEX_FILE=" + tmp
	own, err := Git(dir, []string{"rev-parse", "--path-format=absolute", "--git-path", "index"})
	if err != nil {
		return "", err
	}
	if b, err := os.ReadFile(own); err == nil {
		os.WriteFile(tmp, b, 0o644)
	} else if RevParse(dir, "") != "" {
		if _, err := Git(dir, []string{"read-tree", "HEAD"}, env); err != nil {
			return "", err
		}
	}
	if _, err := Git(dir, []string{"add", "-A"}, env); err != nil {
		return "", err
	}
	return Git(dir, []string{"write-tree"}, env)
}

func numstat(rows string) *Obj {
	files, added, removed := 0, 0, 0
	for _, row := range splitLines(rows) {
		p := strings.Split(row, "\t")
		files++
		a, _ := strconv.Atoi(p[0])
		added += a
		if len(p) > 1 {
			r, _ := strconv.Atoi(p[1])
			removed += r
		}
	}
	return O("files", float64(files), "added", float64(added), "removed", float64(removed))
}

func WorkingStat(dir, ref string) (*Obj, error) {
	out, err := diffWorking(dir, ref, "--numstat")
	if err != nil {
		return nil, err
	}
	return numstat(out), nil
}

func CommittedStat(dir, from, to string) *Obj {
	rows, err := Git(dir, []string{"diff", "--numstat", from, to})
	if err != nil {
		return O("files", 0.0, "added", 0.0, "removed", 0.0)
	}
	return numstat(rows)
}

func CommittedDiff(dir, from, to string) string {
	s, err := Git(dir, []string{"diff", from, to})
	if err != nil {
		return ""
	}
	return s
}

type MergeTreeResult struct {
	Clean     bool
	Tree      string
	Conflicts []string
}

var conflictLine = regexp.MustCompile(`^\d{6} [0-9a-f]+ \d\t(.+)$`)

func MergeTree(dir, a, b string) MergeTreeResult {
	out, err := Git(dir, []string{"merge-tree", "--write-tree", a, b})
	if err == nil {
		return MergeTreeResult{Clean: true, Tree: strings.Split(out, "\n")[0], Conflicts: []string{}}
	}
	var ge *GitErr
	stdout := ""
	if errors.As(err, &ge) {
		stdout = ge.Stdout
	}
	lines := strings.Split(stdout, "\n")
	seen := map[string]bool{}
	conflicts := []string{}
	for _, l := range lines[1:] {
		if m := conflictLine.FindStringSubmatch(l); m != nil && !seen[m[1]] {
			seen[m[1]] = true
			conflicts = append(conflicts, m[1])
		}
	}
	return MergeTreeResult{Clean: false, Tree: lines[0], Conflicts: conflicts}
}

// Land puts a task's branch on its target, as src/git.mjs land() does.
func Land(repo, branch, target, message, strategy string) (*Obj, error) {
	if strategy == "" {
		strategy = "merge"
	}
	tip := RevParse(repo, "refs/heads/"+branch)
	base := RevParse(repo, "refs/heads/"+target)
	if tip == "" || base == "" {
		which := branch
		if tip != "" {
			which = target
		}
		return nil, fmt.Errorf("Cannot find %s", which)
	}
	if IsAncestor(repo, tip, base) {
		return nil, &GitErr{Message: fmt.Sprintf("%s already contains %s", target, branch), Code: "MERGED"}
	}
	m := MergeTree(repo, base, tip)
	if !m.Clean {
		return nil, &GitErr{Message: fmt.Sprintf("Conflicts with %s: %s", target, strings.Join(m.Conflicts, ", ")), Code: "CONFLICT", Conflicts: m.Conflicts}
	}
	ff := strategy == "merge" && IsAncestor(repo, base, tip)
	if at := CheckedOutAt(repo)[target]; at != "" {
		var err error
		if strategy == "squash" {
			if _, err = Git(at, []string{"merge", "--squash", branch}); err == nil {
				_, err = Git(at, []string{"commit", "-q", "-m", message})
			}
		} else {
			mode := "--no-ff"
			if ff {
				mode = "--ff-only"
			}
			_, err = Git(at, []string{"merge", mode, "--no-edit", "-m", message, branch})
		}
		if err != nil {
			if strategy == "squash" {
				Git(at, []string{"reset", "--merge"})
			} else {
				Git(at, []string{"merge", "--abort"})
			}
			return nil, &GitErr{Message: fmt.Sprintf("git refused to merge in %s: %s", at, GitError(err)), Code: "CHECKOUT"}
		}
		head, _ := Git(at, []string{"rev-parse", "HEAD"})
		return O("commit", head, "fastForward", ff), nil
	}
	commit := tip
	if !ff {
		args := []string{"commit-tree", m.Tree, "-p", base}
		if strategy != "squash" {
			args = append(args, "-p", tip)
		}
		var err error
		if commit, err = Git(repo, append(args, "-m", message)); err != nil {
			return nil, err
		}
	}
	if _, err := Git(repo, []string{"update-ref", "refs/heads/" + target, commit, base}); err != nil {
		return nil, err
	}
	return O("commit", commit, "fastForward", ff), nil
}

func MergeInto(dir, target string) (*Obj, error) {
	if _, err := Git(dir, []string{"merge", "--no-edit", target}); err != nil {
		s, _ := Status(dir)
		if len(s.Conflicted) > 0 {
			return O("ok", false, "conflicts", strs(s.Conflicted)), nil
		}
		return nil, errors.New(GitError(err))
	}
	return O("ok", true, "conflicts", []any{}), nil
}

func AbortMerge(dir string) error {
	_, err := Git(dir, []string{"merge", "--abort"})
	return err
}

func Compare(dir, branch, target string) (int, int) {
	return gitCount(dir, target+".."+branch), gitCount(dir, branch+".."+target)
}

// --- GitHub --------------------------------------------------------------------

type RepoSpec struct {
	URL, Name string
	GitHub    string
}

var shortRepo = regexp.MustCompile(`^([\w.-]+)/([\w.-]+?)(?:\.git)?$`)
var ghRepo = regexp.MustCompile(`github\.com[:/]([\w.-]+)/([\w.-]+?)(?:\.git)?/?$`)
var repoName = regexp.MustCompile(`([^/:]+?)(?:\.git)?/?$`)

func ParseRepoSpec(spec string) (RepoSpec, error) {
	s := strings.TrimSpace(spec)
	if s == "" {
		return RepoSpec{}, errors.New("Name a repository")
	}
	if strings.HasPrefix(s, "-") {
		return RepoSpec{}, errors.New("That is not a repository")
	}
	if m := shortRepo.FindStringSubmatch(s); m != nil && !strings.Contains(s, ":") && !strings.HasPrefix(s, ".") && !strings.HasPrefix(s, "/") {
		return RepoSpec{URL: "https://github.com/" + m[1] + "/" + m[2] + ".git", Name: m[2], GitHub: m[1] + "/" + m[2]}, nil
	}
	name := ""
	if m := repoName.FindStringSubmatch(s); m != nil {
		name = strings.TrimLeft(m[1], ".")
	}
	if name == "" {
		return RepoSpec{}, errors.New("Cannot tell the repository name from that")
	}
	gh := ""
	if m := ghRepo.FindStringSubmatch(s); m != nil {
		gh = m[1] + "/" + m[2]
	}
	return RepoSpec{URL: s, Name: name, GitHub: gh}, nil
}

func Clone(spec RepoSpec, dir string) error {
	os.MkdirAll(filepath.Dir(dir), 0o755)
	var cmd *exec.Cmd
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	if spec.GitHub != "" && GhReady() {
		cmd = exec.CommandContext(ctx, "gh", "repo", "clone", spec.GitHub, dir)
	} else {
		cmd = exec.CommandContext(ctx, "git", "clone", "--", spec.URL, dir)
	}
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	var errb bytes.Buffer
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		os.RemoveAll(dir)
		msg := GitError(&GitErr{Stderr: errb.String(), Message: err.Error()})
		if msg == "" {
			msg = "The clone failed"
		}
		return errors.New(msg)
	}
	return nil
}

func GithubRepos() []any {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "gh", "repo", "list", "--limit", "200", "--json", "nameWithOwner,description,isPrivate,updatedAt").Output()
	if err != nil {
		return []any{}
	}
	list, ok := ParseJSON(string(out)).([]any)
	if !ok {
		return []any{}
	}
	res := []any{}
	for _, x := range list {
		r, _ := x.(*Obj)
		res = append(res, O("name", r.Get("nameWithOwner"), "description", or(r.Get("description"), ""), "private", r.Bool("isPrivate"), "updatedAt", r.Get("updatedAt")))
	}
	return res
}

func GhReady() bool {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, "gh", "auth", "status").Run() == nil
}

func CreatePullRequest(dir, head, base, title, body string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "gh", "pr", "create", "--head", head, "--base", base, "--title", title, "--body", body)
	cmd.Dir = dir
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(errb.String())
		if msg == "" {
			msg = err.Error()
		}
		return "", errors.New(msg)
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	return lines[len(lines)-1], nil
}
