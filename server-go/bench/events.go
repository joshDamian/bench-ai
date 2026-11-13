package bench

import (
	"sync"
	"sync/atomic"
)

// Emitter is a small EventEmitter: handlers run synchronously, in the order they were
// added, on the goroutine that emitted.
type Emitter struct {
	mu   sync.RWMutex
	next atomic.Int64
	subs map[string][]sub
}

type sub struct {
	id int64
	fn func(any)
}

// On adds a handler and returns the function that removes it.
func (e *Emitter) On(name string, fn func(any)) func() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.subs == nil {
		e.subs = map[string][]sub{}
	}
	id := e.next.Add(1)
	e.subs[name] = append(e.subs[name], sub{id, fn})
	return func() {
		e.mu.Lock()
		defer e.mu.Unlock()
		list := e.subs[name]
		for i, s := range list {
			if s.id == id {
				e.subs[name] = append(list[:i:i], list[i+1:]...)
				return
			}
		}
	}
}

func (e *Emitter) Emit(name string, payload any) {
	e.mu.RLock()
	list := append([]sub(nil), e.subs[name]...)
	e.mu.RUnlock()
	for _, s := range list {
		s.fn(payload)
	}
}
