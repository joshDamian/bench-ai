package bench

// Spending: spending.mjs. Caps for unattended runs (the sessions a schedule starts): a
// daily cap stops new ones for the day, a cap per run stops one query and asks; one
// switch pauses them all.

import (
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Spending struct{ File string }

func NewSpending(dataDir string) *Spending {
	return &Spending{File: filepath.Join(dataDir, "spending.json")}
}

func (sp *Spending) Settings() *Obj {
	o := O("daily_cap", 5.0, "run_cap", 2.0, "paused", false)
	var m map[string]any
	if b, err := os.ReadFile(sp.File); err == nil && json.Unmarshal(b, &m) == nil {
		for _, k := range []string{"daily_cap", "run_cap", "paused"} {
			if v, ok := m[k]; ok {
				o.Set(k, v)
			}
		}
	}
	return o
}

// capValue reads a cap as JavaScript's Number() would.
func capValue(v any) (float64, bool) {
	switch x := v.(type) {
	case float64:
		return x, true
	case bool:
		if x {
			return 1, true
		}
		return 0, true
	case string:
		t := strings.TrimSpace(x)
		if t == "" {
			return 0, true
		}
		f, err := strconv.ParseFloat(t, 64)
		return f, err == nil
	}
	return 0, v == nil
}

func (sp *Spending) Set(o *Obj) (*Obj, error) {
	next := sp.Settings()
	for _, k := range []string{"daily_cap", "run_cap"} {
		if !o.Has(k) {
			continue
		}
		n, ok := capValue(o.Get(k))
		if !ok || math.IsNaN(n) || math.IsInf(n, 0) || n <= 0 || n > 1000 {
			return nil, fail("A cap is between $0 and $1,000")
		}
		next.Set(k, math.Round(n*100)/100)
	}
	if o.Has("paused") {
		next.Set("paused", truthy(o.Get("paused")))
	}
	os.MkdirAll(filepath.Dir(sp.File), 0o755)
	os.WriteFile(sp.File, ToJSON(next), 0o644)
	return next, nil
}

// StartOfDay is today's midnight on the server's clock, as the store writes times.
func StartOfDay(at time.Time) string {
	y, m, d := at.Date()
	return isoTime(time.Date(y, m, d, 0, 0, 0, 0, at.Location()))
}

func (s *Store) UnattendedSince(since string) (float64, int) {
	r := s.one("SELECT COALESCE(SUM(cost),0) AS spent, COUNT(*) AS runs FROM sessions WHERE schedule_id IS NOT NULL AND created_at >= ?", since)
	if r == nil {
		return 0, 0
	}
	return r.Num("spent"), int(r.Num("runs"))
}

func (s *Service) Spending() *Spending {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.spending == nil {
		s.spending = NewSpending(s.Store.Dir)
	}
	return s.spending
}

func (s *Service) SpendingInfo() *Obj {
	spent, runs := s.Store.UnattendedSince(StartOfDay(time.Now()))
	return s.Spending().Settings().Set("today", math.Round(spent*100)/100).Set("runs_today", float64(runs))
}

func (s *Service) SetSpending(o *Obj) (*Obj, error) {
	if _, err := s.Spending().Set(o); err != nil {
		return nil, err
	}
	return s.SpendingInfo(), nil
}

// Unattended is whether an unattended run may start now, and if not why.
func (s *Service) Unattended() (bool, bool, string) {
	st := s.Spending().Settings()
	if truthy(st.Get("paused")) {
		return false, true, "Unattended runs are paused"
	}
	if spent, _ := s.Store.UnattendedSince(StartOfDay(time.Now())); spent >= st.Num("daily_cap") {
		return false, false, "Today's $" + jsNum(st.Num("daily_cap")) + " cap is reached"
	}
	return true, false, ""
}

// Budget: a run that stopped at its cap goes on with another run's worth, or stays there.
func (s *Service) Budget(id string, action any) (*Obj, error) {
	ss, err := s.Session(id)
	if err != nil {
		return nil, err
	}
	if action == "stop" {
		u := s.Store.UpdateSession(id, O("capped", false))
		s.Runtime.Emit("session", u)
		return u, nil
	}
	if action != "more" {
		return nil, fail("More or stop")
	}
	if !truthy(ss.Get("capped")) {
		return nil, fail("It has not stopped at its cap", 409)
	}
	s.Runtime.Close(id)
	s.Runtime.Emit("session", s.Store.UpdateSession(id, O("capped", false)))
	if _, err := s.Send(id, "Continue where you left off.", SendOpts{}); err != nil {
		return nil, err
	}
	return s.Session(id)
}
