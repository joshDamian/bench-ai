package bench

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// --- subagent routing (src/routing.mjs) ---------------------------------------------

var routingFields = []string{"enabled", "explorer", "implementer", "architect", "all"}

func DefaultRouting() *Obj {
	return O("enabled", true, "explorer", "haiku", "implementer", "sonnet", "architect", "inherit", "all", nil)
}

var spaceRe = regexp.MustCompile(`\s`)

func modelOf(v any, what string) (string, error) {
	m := strings.TrimSpace(str(v))
	if m == "" || len(m) > 120 || spaceRe.MatchString(m) {
		return "", fail(what + ": name a model - haiku, sonnet, opus, inherit, or a model id")
	}
	return m, nil
}

// RoutingPatch checks a routing patch; override lets null hand a field to the level above.
func RoutingPatch(input any, override bool) (*Obj, error) {
	in, ok := input.(*Obj)
	if !ok || in == nil {
		return nil, fail("Routing is an object")
	}
	out := O()
	def := DefaultRouting()
	for _, k := range routingFields {
		if !in.Has(k) {
			continue
		}
		v := in.Get(k)
		if v == nil || v == "" {
			if override {
				out.Set(k, nil)
			} else {
				out.Set(k, def.Get(k))
			}
			continue
		}
		if k == "enabled" {
			out.Set(k, truthy(v))
		} else {
			m, err := modelOf(v, k)
			if err != nil {
				return nil, err
			}
			out.Set(k, m)
		}
	}
	return out, nil
}

func setFields(o *Obj) *Obj {
	out := O()
	for _, k := range o.Keys() {
		if v := o.Get(k); v != nil {
			if _, u := v.(undefined); !u {
				out.Set(k, v)
			}
		}
	}
	return out
}

func ResolveRouting(base, project, task *Obj) *Obj {
	from := O()
	r := DefaultRouting().Merge(setFields(base))
	for _, k := range routingFields {
		from.Set(k, "default")
	}
	for _, lv := range []struct {
		name string
		o    *Obj
	}{{"project", project}, {"task", task}} {
		sf := setFields(lv.o)
		for _, k := range sf.Keys() {
			r.Set(k, sf.Get(k))
			from.Set(k, lv.name)
		}
	}
	return r.Set("from", from)
}

const briefed = "You were started by another agent with a short brief. Do what it asks, stay inside it, and report back concisely: what you found or changed, with file paths and line numbers, and anything you could not do. Your report is all the other agent sees of your work."

func Subagents(r *Obj) *Obj {
	return O(
		"explorer", O(
			"description", "Cheap and fast, read-only. Use for lookups whose answer is a fact, not a judgement: finding where something is defined or used, reading code to say what it does, grep-style searches across the repo, listing files, reading git history, and summarising docs, logs or long command output. Brief it with exactly what to find and what shape of answer to return. Do not use it to decide on a design or to edit anything.",
			"prompt", briefed+"\n\nYou only read: search, read files, run read-only commands (git log, ls, grep, test listings). Never edit, write or delete files, and never run a command that changes anything. Answer with the facts asked for and where you found them; quote only the lines that matter rather than whole files.",
			"disallowedTools", []any{"Edit", "Write", "NotebookEdit"},
			"model", r.Get("explorer"),
		),
		"implementer", O(
			"description", "Mid-tier, can edit. Use for well-specified work where the what and the where are already decided: an edit described precisely, writing or extending tests for code that exists, a mechanical refactor or rename, applying the same change across files, and running the check or tests and fixing what they report. Brief it with the files, the change, and how to know it is done. If the work needs a design decision first, use architect instead.",
			"prompt", briefed+"\n\nMake the change the brief describes, matching the surrounding code's style. Run the relevant tests or check before you report, and say what they printed. If the brief is ambiguous or the change turns out to need a design decision, stop and say so rather than guess.",
			"model", r.Get("implementer"),
		),
		"architect", O(
			"description", "Most capable and most expensive. Use only when the work needs judgement: a problem whose cause is unclear, hard debugging after a cheaper attempt failed, designing an approach, or a change that cuts across many parts of the system. Brief it with the goal, what is known and what was tried. Not for lookups or for edits that are already specified.",
			"prompt", briefed+"\n\nThink the problem through before acting: establish what is actually happening from the code and from evidence, not assumption. When you propose or make a change, say why it is right and what else it affects.",
			"model", r.Get("architect"),
		),
	)
}

const RoutingNote = "# Subagents\n\nBench gives you three subagents, cheapest first: `explorer` (read-only lookups), `implementer` (well-specified edits, tests, running checks) and `architect` (unclear problems, design, hard debugging). Before you delegate, judge how hard the piece of work is and use the cheapest tier that will do it reliably; prefer `explorer` over the built-in Explore and general-purpose agents for lookups. Escalate a tier when one fails, and give each a short, focused brief rather than the whole conversation. The `bench:routing` skill has the full guidance."

// --- usage limits (src/limits.mjs) -------------------------------------------------

var limitPrefixes = []string{"You've hit your", "You've reached your", "You're out of usage credits", "You're out of extra usage"}
var months = []string{"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"}
var resetsRe = regexp.MustCompile(`(?i)resets (?:at )?(?:([A-Za-z]{3})[a-z]* (\d{1,2}),? (?:(\d{4}),? )?(?:at )?)?(\d{1,2})(?::(\d{2}))?\s?([ap]m)(?: \(([^)]+)\))?`)
var limitName = regexp.MustCompile(`^You've (?:hit|reached) your (.+?)(?: ·|\.|$)`)

func IsLimitText(text string) bool {
	t := strings.TrimSpace(text)
	for _, p := range limitPrefixes {
		if strings.HasPrefix(t, p) {
			return true
		}
	}
	return false
}

type LimitHit struct {
	Message  string
	Limit    any
	ResetsAt *time.Time
}

func zoneLoc(zone string) *time.Location {
	if zone == "" {
		return time.Local
	}
	if strings.EqualFold(zone, "utc") {
		return time.UTC
	}
	l, err := time.LoadLocation(zone)
	if err != nil {
		return nil
	}
	return l
}

// ParseLimit reads a usage-limit message; nil when the text is not one.
func ParseLimit(text string, now time.Time) *LimitHit {
	message := strings.TrimSpace(text)
	if !IsLimitText(message) {
		return nil
	}
	var limit any
	if m := limitName.FindStringSubmatch(message); m != nil && m[1] != "" {
		limit = m[1]
	}
	m := resetsRe.FindStringSubmatch(message)
	if m == nil {
		return &LimitHit{Message: message, Limit: limit}
	}
	mon, day, year, hour, minute, ampm, zoneText := m[1], m[2], m[3], m[4], m[5], m[6], m[7]
	zone := ""
	if zoneText != "" {
		zone = zoneText
	}
	loc := zoneLoc(zone)
	if loc == nil {
		return &LimitHit{Message: message, Limit: limit}
	}
	hh, _ := strconv.Atoi(hour)
	h := hh % 12
	if strings.ToLower(ampm) == "pm" {
		h += 12
	}
	mi, _ := strconv.Atoi(minute)
	today := now.In(loc)
	var at time.Time
	if mon != "" {
		mo := -1
		for i, x := range months {
			if x == strings.ToLower(mon) {
				mo = i
			}
		}
		if mo < 0 {
			return &LimitHit{Message: message, Limit: limit}
		}
		y := today.Year()
		if year != "" {
			y, _ = strconv.Atoi(year)
		}
		d, _ := strconv.Atoi(day)
		at = time.Date(y, time.Month(mo+1), d, h, mi, 0, 0, loc)
		if year == "" && at.Before(now.Add(-24*time.Hour)) {
			at = time.Date(today.Year()+1, time.Month(mo+1), d, h, mi, 0, 0, loc)
		}
	} else {
		at = time.Date(today.Year(), today.Month(), today.Day(), h, mi, 0, 0, loc)
		if at.Before(now.Add(-time.Minute)) {
			at = time.Date(today.Year(), today.Month(), today.Day()+1, h, mi, 0, 0, loc)
		}
	}
	return &LimitHit{Message: message, Limit: limit, ResetsAt: &at}
}

// LimitFromEvent: the reset time in a rate_limit_event that refused a request.
func LimitFromEvent(info *Obj) *time.Time {
	if info == nil || info.Str("status") != "rejected" {
		return nil
	}
	s := info.Get("resetsAt")
	if s == nil {
		s = info.Get("overageResetsAt")
	}
	f, ok := s.(float64)
	if !ok || f <= 0 {
		return nil
	}
	t := time.Unix(int64(f), int64((f-float64(int64(f)))*1e9))
	return &t
}

func fmtTime(t time.Time) string { return fmt.Sprint(isoTime(t)) }
