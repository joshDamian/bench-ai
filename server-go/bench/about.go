package bench

// About me and the rules: rules.mjs and about.mjs. The person's Never list is stopped
// before a tool runs; About me is a note of no project that every session reads.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// --- rules ---------------------------------------------------------------------------

type ruleAction struct{ Key, Label string }

var RuleActions = []ruleAction{
	{"send-email", "Send email"},
	{"submit-forms", "Submit applications or forms"},
	{"pay", "Pay or move money"},
}

var ruleDefaults = map[string]string{"send-email": "never", "submit-forms": "never", "pay": "never"}

var (
	mailTool    = regexp.MustCompile(`(?i)(gmail|outlook|mail|email|smtp|sendgrid|mailgun|postmark|resend)`)
	sendVerb    = regexp.MustCompile(`(?i)(^|[_\-.])(send|reply|forward)([_\-.]|$)|send_?(email|mail|message|draft)`)
	payTool     = regexp.MustCompile(`(?i)(^|[_\-.])(pay|payment|payout|transfer|charge|refund|purchase|checkout|send_?money|withdraw)([_\-.]|$)`)
	formTool    = regexp.MustCompile(`(?i)(^|[_\-.])(submit|apply)([_\-.]|$)|submit_?(form|application)|apply_?(job|to)`)
	mailCommand = regexp.MustCompile(`(?i)\b(sendmail|mailx?|mutt|msmtp|swaks|ssmtp)\b|smtps?://|\bsmtplib\b|nodemailer|gmail\.googleapis\.com/\S*/send|api\.sendgrid\.com|api\.mailgun\.net|api\.postmarkapp\.com|api\.resend\.com/emails`)
	payCommand  = regexp.MustCompile(`(?i)api\.stripe\.com/v1/(charges|payment_intents|transfers|payouts|refunds)|api\.paystack\.co/(transfer|charge)|api\.flutterwave\.com/v3/(transfers|charges)|api\.paypal\.com/v\d/payments`)
)

// ActionOf is which kind of action a tool call is, if one a rule covers.
func ActionOf(tool string, input *Obj) string {
	if tool == "Bash" {
		cmd := ""
		if input != nil {
			cmd = input.Str("command")
		}
		if mailCommand.MatchString(cmd) {
			return "send-email"
		}
		if payCommand.MatchString(cmd) {
			return "pay"
		}
		return ""
	}
	if !strings.HasPrefix(tool, "mcp__") {
		return ""
	}
	if mailTool.MatchString(tool) && sendVerb.MatchString(tool) {
		return "send-email"
	}
	if payTool.MatchString(tool) {
		return "pay"
	}
	if formTool.MatchString(tool) {
		return "submit-forms"
	}
	return ""
}

type Rules struct{ File string }

func NewRules(dataDir string) *Rules { return &Rules{File: filepath.Join(dataDir, "rules.json")} }

func (r *Rules) saved() map[string]string {
	m := map[string]string{}
	if b, err := os.ReadFile(r.File); err == nil {
		json.Unmarshal(b, &m)
	}
	return m
}

func (r *Rules) Values() map[string]string {
	v := map[string]string{}
	for k, d := range ruleDefaults {
		v[k] = d
	}
	for k, s := range r.saved() {
		v[k] = s
	}
	return v
}

func (r *Rules) List() []any {
	v := r.Values()
	out := []any{}
	for _, a := range RuleActions {
		out = append(out, O("key", a.Key, "label", a.Label, "value", v[a.Key]))
	}
	return out
}

func (r *Rules) Set(key, value any) ([]any, error) {
	k, _ := key.(string)
	found := false
	for _, a := range RuleActions {
		if a.Key == k {
			found = true
		}
	}
	if !found {
		return nil, fail("No such rule", 404)
	}
	val, _ := value.(string)
	if val != "allow" && val != "ask" && val != "never" {
		return nil, fail("A rule is allow, ask or never")
	}
	m := r.saved()
	m[k] = val
	os.MkdirAll(filepath.Dir(r.File), 0o755)
	b, _ := json.MarshalIndent(m, "", " ")
	os.WriteFile(r.File, b, 0o644)
	return r.List(), nil
}

// Blocks is the Never rule a tool call would break, or nil.
func (r *Rules) Blocks(tool string, input *Obj) *ruleAction {
	k := ActionOf(tool, input)
	if k == "" || r.Values()[k] != "never" {
		return nil
	}
	for _, a := range RuleActions {
		if a.Key == k {
			a := a
			return &a
		}
	}
	return nil
}

func (r *Rules) Note() string {
	v := r.Values()
	var never []string
	for _, a := range RuleActions {
		if v[a.Key] == "never" {
			never = append(never, strings.ToLower(a.Label))
		}
	}
	if len(never) == 0 {
		return ""
	}
	return "# Never\n\nThe person never wants Bench to do these, and Bench stops them before they run: " + strings.Join(never, "; ") + ". Do not look for another way. Prepare the thing instead and leave the last step to the person: an email becomes a drafts file in the reports folder, a form or application becomes the filled-in answers in a report."
}

// --- About me ------------------------------------------------------------------------

const AboutName = "about-me.md"

type About struct {
	notes     *Notes
	stateFile string
}

func NewAbout(dataDir string, notes *Notes) *About {
	return &About{notes: notes, stateFile: filepath.Join(dataDir, "about.json")}
}

func (a *About) File() string      { return filepath.Join(a.notes.Global, AboutName) }
func (a *About) DraftFile() string { return filepath.Join(a.notes.Global, ".about-me.draft.md") }
func (a *About) PrevFile() string  { return filepath.Join(a.notes.Global, ".about-me.prev.md") }

func readText(p string) (string, bool) {
	b, err := os.ReadFile(p)
	if err != nil {
		return "", false
	}
	return string(b), true
}

func (a *About) changedBy() string {
	var m map[string]any
	if b, err := os.ReadFile(a.stateFile); err == nil {
		json.Unmarshal(b, &m)
	}
	s, _ := m["changed_by"].(string)
	return s
}

func (a *About) setChangedBy(id string) {
	os.MkdirAll(filepath.Dir(a.stateFile), 0o755)
	var v any
	if id != "" {
		v = id
	}
	b, _ := json.Marshal(map[string]any{"changed_by": v})
	os.WriteFile(a.stateFile, b, 0o644)
}

func (a *About) Get(sessionOf func(string) *Obj) *Obj {
	text, has := readText(a.File())
	draft, hasDraft := readText(a.DraftFile())
	include := false
	if has {
		if n, err := a.notes.Read("", AboutName); err == nil {
			include = truthy(n.Get("include"))
		}
	}
	var changed any
	if by := a.changedBy(); by != "" {
		if _, err := os.Stat(a.PrevFile()); err == nil {
			var title any
			if s := sessionOf(by); s != nil {
				title = s.Get("title")
			}
			changed = O("session_id", by, "title", title)
		}
	}
	var t, d any
	if has {
		t = text
	}
	if hasDraft {
		d = draft
	}
	return O("text", t, "draft", d, "include", include, "path", a.File(), "changed_by", changed)
}

func (a *About) Write(text string) error {
	_, err := os.Stat(a.File())
	first := err != nil
	if _, err := a.notes.Write("", AboutName, text, 0); err != nil {
		return err
	}
	if first {
		a.notes.SetInclude("", AboutName, true)
	}
	os.Remove(a.PrevFile())
	a.setChangedBy("")
	return nil
}

func (a *About) Include(on bool) error {
	if _, err := os.Stat(a.File()); err != nil {
		return fail("Write About me first")
	}
	_, err := a.notes.SetInclude("", AboutName, on)
	return err
}

func (a *About) Draft(action any) error {
	text, ok := readText(a.DraftFile())
	if !ok {
		return fail("There is no draft", 404)
	}
	switch action {
	case "save":
		if err := a.Write(text); err != nil {
			return err
		}
	case "discard":
	default:
		return fail("Save or discard")
	}
	os.Remove(a.DraftFile())
	return nil
}

func (a *About) Undo() error {
	prev, ok := readText(a.PrevFile())
	if !ok {
		return fail("Nothing to undo", 404)
	}
	if prev == "" {
		a.notes.Remove("", AboutName)
	} else if _, err := a.notes.Write("", AboutName, prev, 0); err != nil {
		return err
	}
	os.Remove(a.PrevFile())
	a.setChangedBy("")
	return nil
}

func (a *About) Touches(tool string, input *Obj) bool {
	if tool != "Write" && tool != "Edit" && tool != "MultiEdit" {
		return false
	}
	if input == nil || input.Str("file_path") == "" {
		return false
	}
	p, _ := filepath.Abs(input.Str("file_path"))
	f, _ := filepath.Abs(a.File())
	return p == f
}

func (a *About) Before() {
	os.MkdirAll(a.notes.Global, 0o755)
	text, _ := readText(a.File())
	os.WriteFile(a.PrevFile(), []byte(text), 0o644)
}

func (a *About) After(sessionID string) {
	if _, err := os.Stat(a.File()); err != nil {
		return
	}
	if prev, ok := readText(a.PrevFile()); ok && prev == "" {
		a.notes.SetInclude("", AboutName, true)
	}
	a.setChangedBy(sessionID)
}

func (a *About) Note() string {
	return "# About me\n\nThe person keeps a note about themselves in " + a.File() + ": who they are, their work, their links and how they write. Use it whenever you write for them or as them, and fill in their own details rather than leaving blanks. When they ask you to remember something about themselves or how they like things done, add one short line under the right heading there and say \"Added to About me\" in your reply. Do not add anything they did not ask for."
}

func (a *About) CVPrompt() string {
	return "Read the attached CV and write a draft of my About me to " + a.DraftFile() + ". Use exactly these headings, in this order: \"## Who you are\", \"## Your work\", \"## Links\", \"## How you write\". Write short plain sentences and only what the CV says; under a heading the CV says nothing about, write \"Not in the CV.\" Start the file with \"# About me\". Do not write anything else to that file or anywhere else. Then reply in one line that the draft is ready to check."
}

// --- runtime and service -------------------------------------------------------------

func (r *Runtime) Rules() *Rules {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.rules == nil {
		r.rules = NewRules(r.Store.Dir)
	}
	return r.rules
}

func (r *Runtime) About() *About {
	n := r.Notes()
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.about == nil {
		r.about = NewAbout(r.Store.Dir, n)
	}
	return r.about
}

func (s *Service) About() *Obj {
	return s.Runtime.About().Get(func(id string) *Obj { return s.Store.GetSession(id) })
}

func (s *Service) SaveAbout(o *Obj) (*Obj, error) {
	if o.Has("text") {
		t, ok := o.Get("text").(string)
		if !ok {
			return nil, fail("About me is text")
		}
		if err := s.Runtime.About().Write(t); err != nil {
			return nil, err
		}
	}
	if o.Has("include") {
		if err := s.Runtime.About().Include(truthy(o.Get("include"))); err != nil {
			return nil, err
		}
	}
	return s.About(), nil
}

func (s *Service) AboutDraft(action any) (*Obj, error) {
	if err := s.Runtime.About().Draft(action); err != nil {
		return nil, err
	}
	return s.About(), nil
}

func (s *Service) AboutUndo() (*Obj, error) {
	if err := s.Runtime.About().Undo(); err != nil {
		return nil, err
	}
	return s.About(), nil
}

func (s *Service) AboutFromCV() (*Obj, error) {
	p, err := s.Research()
	if err != nil {
		return nil, err
	}
	sess, err := s.CreateSession(O("projectId", p.Str("id"), "title", "About me from your CV", "mode", "acceptEdits"))
	if err != nil {
		return nil, err
	}
	return O("session", sess, "prompt", s.Runtime.About().CVPrompt()), nil
}
