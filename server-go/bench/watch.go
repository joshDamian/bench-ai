package bench

// Watching a page (src/watch.mjs): a schedule that runs when a web page changes, not at a
// time. Bench looks at the page every few hours and keeps its text; when the text changes it
// starts a run with what is new. A run that finds nothing that matters replies "Nothing new."
// and is put away without a notification.

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const NothingNew = "Nothing new."

var quoteEnds = regexp.MustCompile("^[\"'`]+|[\"'`]+$")

// IsNothingNew is whether a run's reply is the quiet one.
func IsNothingNew(text string) bool {
	return quoteEnds.ReplaceAllString(strings.TrimSpace(text), "") == NothingNew
}

var watchURL = regexp.MustCompile(`(?i)^https?://[^\s/]+\.[^\s]+$`)

// watchFields checks a watch the person sent: {url, every_hours}, or nil to clear it.
func watchFields(v any) (*Obj, error) {
	if v == nil || v == false {
		return nil, nil
	}
	w, _ := v.(*Obj)
	if w == nil {
		w = O()
	}
	url := strings.TrimSpace(str(w.Get("url")))
	if !watchURL.MatchString(url) {
		return nil, fail("Watch a page by its web address, starting with https://")
	}
	every := w.Get("everyHours")
	if every == nil {
		every = w.Get("every_hours")
	}
	n := 6.0
	if every != nil {
		n = num(every)
	}
	if n != float64(int(n)) || n < 1 || n > 168 {
		return nil, fail("Check every 1 to 168 hours")
	}
	return O("url", url, "every_hours", n), nil
}

var (
	pageDrop  []*regexp.Regexp
	pageNotes = regexp.MustCompile(`(?s)<!--.*?-->`)
	pageBlock = regexp.MustCompile(`(?i)</?(p|div|li|tr|h[1-6]|br|section|article|header|footer|ul|ol|table|main|nav)\b[^>]*>`)
	pageTag   = regexp.MustCompile(`<[^>]+>`)
	pageSpace = regexp.MustCompile(`\s+`)
)

func init() {
	for _, t := range []string{"script", "style", "noscript", "svg", "template"} {
		pageDrop = append(pageDrop, regexp.MustCompile(`(?is)<`+t+`.*?</`+t+`>`))
	}
}

// PageText is a page's readable text: tags, scripts and styles gone, one line per block.
func PageText(html string) string {
	for _, re := range pageDrop {
		html = re.ReplaceAllString(html, " ")
	}
	html = pageNotes.ReplaceAllString(html, " ")
	html = pageBlock.ReplaceAllString(html, "\n")
	html = pageTag.ReplaceAllString(html, " ")
	html = strings.NewReplacer("&nbsp;", " ", "&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", `"`, "&#39;", "'").Replace(html)
	var out []string
	for _, l := range strings.Split(html, "\n") {
		if l = strings.TrimSpace(pageSpace.ReplaceAllString(l, " ")); l != "" {
			out = append(out, l)
		}
	}
	return strings.Join(out, "\n")
}

func textHash(text string) string {
	h := sha256.Sum256([]byte(text))
	return hex.EncodeToString(h[:])
}

// AddedLines is the lines in the new text that the old did not have, up to a few thousand characters.
func AddedLines(before, after string) string {
	old := map[string]bool{}
	for _, l := range strings.Split(before, "\n") {
		old[l] = true
	}
	var b strings.Builder
	for _, l := range strings.Split(after, "\n") {
		if old[l] {
			continue
		}
		if b.Len()+len(l) > 4000 {
			return b.String() + "…\n"
		}
		b.WriteString(l + "\n")
	}
	return b.String()
}

func watchFile(dataDir, id string) string { return filepath.Join(dataDir, "watches", id+".txt") }

func lastText(dataDir, id string) string {
	b, _ := os.ReadFile(watchFile(dataDir, id))
	return string(b)
}

func keepText(dataDir, id, text string) {
	os.MkdirAll(filepath.Join(dataDir, "watches"), 0o755)
	os.WriteFile(watchFile(dataDir, id), []byte(text), 0o644)
}

func fetchText(url string) (string, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; Bench)")
	req.Header.Set("Accept", "text/html,*/*")
	res, err := (&http.Client{Timeout: 20 * time.Second}).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode >= 400 {
		return "", fmt.Errorf("the page answered %d", res.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if err != nil {
		return "", err
	}
	return PageText(string(b)), nil
}

// WatchPrompt is what a run of a watch is told.
func WatchPrompt(ask, url, added string) string {
	a := strings.TrimSpace(added)
	if a == "" {
		a = "(Only removals or small edits.)"
	}
	return url + " has changed since Bench last looked. New on the page:\n\n" + a + "\n\nWhat the person asked: " + ask +
		"\n\nOpen the page if you need more than this. If the change does not matter for what they asked, reply exactly \"" + NothingNew + "\" and nothing else."
}

// CheckDue is whether a watch is due a look at its page.
func CheckDue(sch *Obj, at time.Time) bool {
	w := sch.ObjAt("watch")
	if !truthy(sch.Get("enabled")) || w == nil {
		return false
	}
	t, ok := parseISO(sch.Str("checked_at"))
	return sch.Str("checked_at") == "" || !ok || at.Sub(t) >= time.Duration(w.Num("every_hours")*float64(time.Hour))
}

// WatchHash is the hash of the page text a watch last saw.
func (s *Store) WatchHash(id string) (string, bool) {
	r := s.one("SELECT watch_hash FROM schedules WHERE id=?", id)
	if r == nil || r.Get("watch_hash") == nil {
		return "", false
	}
	return r.Str("watch_hash"), true
}

// Look looks at a watched page. The first look only keeps its text. A change starts a run with
// what is new, unless unattended runs may not start now: then the old text is kept, so the
// change is still there at the next look.
func (sc *Scheduler) Look(sch *Obj, at time.Time) {
	id := sch.Str("id")
	sc.mu.Lock()
	if sc.looking[id] {
		sc.mu.Unlock()
		return
	}
	sc.looking[id] = true
	sc.mu.Unlock()
	defer func() { sc.mu.Lock(); delete(sc.looking, id); sc.mu.Unlock() }()
	st := sc.svc.Store
	checked := O("checked_at", isoTime(at))
	text, err := fetchText(sch.ObjAt("watch").Str("url"))
	if err != nil {
		st.UpdateSchedule(id, checked)
		fmt.Fprintf(os.Stderr, "watch %s: %v\n", sch.Str("title"), err)
		return
	}
	hash := textHash(text)
	before, had := st.WatchHash(id)
	if had && before == hash {
		st.UpdateSchedule(id, checked)
		return
	}
	if ok, _, _ := sc.svc.Unattended(); had && !ok {
		st.UpdateSchedule(id, checked)
		return
	}
	added := AddedLines(lastText(st.Dir, id), text)
	keepText(st.Dir, id, text)
	st.UpdateSchedule(id, checked.Set("watch_hash", hash))
	if had {
		if _, err := sc.svc.RunSchedule(id, added); err != nil {
			fmt.Fprintf(os.Stderr, "watch %s: %v\n", sch.Str("title"), err)
		}
	}
}

// QuietRun is whether a turn was a watch's run that found nothing that matters.
func (s *Service) QuietRun(ss *Obj, reply string) bool {
	if ss == nil || ss.Str("schedule_id") == "" {
		return false
	}
	sch := s.Store.GetSchedule(ss.Str("schedule_id"))
	return sch != nil && sch.ObjAt("watch") != nil && IsNothingNew(reply)
}
