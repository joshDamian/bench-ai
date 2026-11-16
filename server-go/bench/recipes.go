package bench

// Recipes (src/recipes.mjs): a prompt the person saved to run again, with blanks they fill in
// each time ("Find 5 remote {role} jobs in {region}"). Listed in New, under the four.

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

var (
	blankRe   = regexp.MustCompile(`\{([^{}\n]{1,40})\}`)
	recipesMu sync.Mutex
)

// BlanksOf is the names of a prompt's blanks, in order, each once.
func BlanksOf(prompt string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, m := range blankRe.FindAllStringSubmatch(prompt, -1) {
		n := strings.TrimSpace(m[1])
		if n != "" && !seen[n] {
			seen[n] = true
			out = append(out, n)
		}
	}
	return out
}

func (s *Service) recipesFile() string { return filepath.Join(s.Store.Dir, "recipes.json") }

func (s *Service) readRecipes() []*Obj {
	b, err := os.ReadFile(s.recipesFile())
	if err != nil {
		return nil
	}
	var out []*Obj
	for _, x := range arr(ParseJSON(string(b))) {
		if o, ok := x.(*Obj); ok {
			out = append(out, o)
		}
	}
	return out
}

func (s *Service) writeRecipes(list []*Obj) {
	os.MkdirAll(filepath.Dir(s.recipesFile()), 0o755)
	all := make([]any, len(list))
	for i, r := range list {
		all[i] = r
	}
	os.WriteFile(s.recipesFile(), ToJSON(all), 0o644)
}

func recipeView(r *Obj) *Obj {
	blanks := []any{}
	for _, b := range BlanksOf(r.Str("prompt")) {
		blanks = append(blanks, b)
	}
	return O("id", r.Get("id"), "title", r.Get("title"), "prompt", r.Get("prompt"), "project_id", r.Get("project_id"), "blanks", blanks, "created_at", r.Get("created_at"))
}

func (s *Service) Recipes() []*Obj {
	recipesMu.Lock()
	defer recipesMu.Unlock()
	out := []*Obj{}
	for _, r := range s.readRecipes() {
		out = append(out, recipeView(r))
	}
	return out
}

func (s *Service) recipeFields(o *Obj, partial bool) (*Obj, error) {
	row := O()
	has := func(k string) bool { return o.Has(k) && !isUndef(o.Get(k)) }
	if !partial || has("title") {
		if strings.TrimSpace(str(o.Get("title"))) == "" {
			return nil, fail("A recipe needs a name")
		}
		row.Set("title", strings.TrimSpace(str(o.Get("title"))))
	}
	if !partial || has("prompt") {
		if strings.TrimSpace(str(o.Get("prompt"))) == "" {
			return nil, fail("A recipe needs a prompt")
		}
		row.Set("prompt", strings.TrimSpace(str(o.Get("prompt"))))
	}
	if !partial || has("projectId") {
		if truthy(o.Get("projectId")) {
			p, err := s.Project(str(o.Get("projectId")))
			if err != nil {
				return nil, err
			}
			row.Set("project_id", p.Str("id"))
		} else {
			row.Set("project_id", nil)
		}
	}
	return row, nil
}

func (s *Service) AddRecipe(o *Obj) (*Obj, error) {
	row, err := s.recipeFields(o, false)
	if err != nil {
		return nil, err
	}
	recipesMu.Lock()
	defer recipesMu.Unlock()
	r := O("id", NewID(), "title", row.Get("title"), "prompt", row.Get("prompt"), "project_id", row.Get("project_id"), "created_at", isoNow())
	s.writeRecipes(append(s.readRecipes(), r))
	return recipeView(r), nil
}

func (s *Service) UpdateRecipe(id string, o *Obj) (*Obj, error) {
	row, err := s.recipeFields(o, true)
	if err != nil {
		return nil, err
	}
	recipesMu.Lock()
	defer recipesMu.Unlock()
	list := s.readRecipes()
	for _, r := range list {
		if r.Str("id") == id {
			for _, k := range row.Keys() {
				r.Set(k, row.Get(k))
			}
			s.writeRecipes(list)
			return recipeView(r), nil
		}
	}
	return nil, fail("No such recipe", 404)
}

func (s *Service) DeleteRecipe(id string) (*Obj, error) {
	recipesMu.Lock()
	defer recipesMu.Unlock()
	list := s.readRecipes()
	left := []*Obj{}
	for _, r := range list {
		if r.Str("id") != id {
			left = append(left, r)
		}
	}
	if len(left) == len(list) {
		return nil, fail("No such recipe", 404)
	}
	s.writeRecipes(left)
	return O("ok", true), nil
}

var thenLine = regexp.MustCompile(`(?im)^[ \t]*(?:[-*][ \t]*)?\**Then:\**[ \t]*(.+?)[ \t]*$`)
var trailingStars = regexp.MustCompile(`\*+$`)

// ThenSteps is the next steps a reply offered, from its "Then: …" lines; nil when none.
func ThenSteps(reply string) []any {
	var out []any
	for _, m := range thenLine.FindAllStringSubmatch(reply, -1) {
		x := strings.TrimSpace(trailingStars.ReplaceAllString(m[1], ""))
		if x != "" && len([]rune(x)) <= 120 {
			out = append(out, x)
		}
	}
	if len(out) == 0 {
		return nil
	}
	if len(out) > 2 {
		out = out[:2]
	}
	return out
}
