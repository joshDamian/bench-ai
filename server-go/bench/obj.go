// Package bench is the Go implementation of the Bench server: the same HTTP and
// WebSocket API as src/server.mjs, over the same database, so either server can run
// against a data folder and the apps cannot tell them apart.
package bench

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"unicode/utf8"
)

// Obj is a JSON object that keeps its keys in the order they were set, the way a
// JavaScript object does. Rows read from SQLite come back as one, columns in table
// order, so a response is byte-for-byte what the Node server would have sent.
type Obj struct {
	keys []string
	vals map[string]any
}

// O builds an Obj from alternating keys and values.
func O(kv ...any) *Obj {
	o := &Obj{vals: make(map[string]any, len(kv)/2)}
	for i := 0; i+1 < len(kv); i += 2 {
		o.Set(kv[i].(string), kv[i+1])
	}
	return o
}

func (o *Obj) Set(k string, v any) *Obj {
	if o.vals == nil {
		o.vals = map[string]any{}
	}
	if _, ok := o.vals[k]; !ok {
		o.keys = append(o.keys, k)
	}
	o.vals[k] = v
	return o
}

func (o *Obj) Get(k string) any {
	if o == nil {
		return nil
	}
	return o.vals[k]
}

func (o *Obj) Has(k string) bool {
	if o == nil {
		return false
	}
	_, ok := o.vals[k]
	return ok
}

func (o *Obj) Delete(k string) {
	if o == nil {
		return
	}
	if _, ok := o.vals[k]; !ok {
		return
	}
	delete(o.vals, k)
	for i, x := range o.keys {
		if x == k {
			o.keys = append(o.keys[:i], o.keys[i+1:]...)
			break
		}
	}
}

func (o *Obj) Keys() []string {
	if o == nil {
		return nil
	}
	return o.keys
}

func (o *Obj) Len() int {
	if o == nil {
		return 0
	}
	return len(o.keys)
}

// Str is the value at k as a string, "" when it is missing or not a string.
func (o *Obj) Str(k string) string {
	s, _ := o.Get(k).(string)
	return s
}

// StrP is the value at k as a *string: nil when missing or null.
func (o *Obj) StrP(k string) *string {
	if s, ok := o.Get(k).(string); ok {
		return &s
	}
	return nil
}

func (o *Obj) Num(k string) float64 { return num(o.Get(k)) }

func (o *Obj) Bool(k string) bool { return truthy(o.Get(k)) }

func (o *Obj) ObjAt(k string) *Obj {
	x, _ := o.Get(k).(*Obj)
	return x
}

// Clone is a shallow copy: `{...o}`.
func (o *Obj) Clone() *Obj {
	n := &Obj{keys: append([]string(nil), o.Keys()...), vals: make(map[string]any, o.Len())}
	for _, k := range n.keys {
		n.vals[k] = o.vals[k]
	}
	return n
}

// Merge sets every key of p on a copy of o: `{...o, ...p}`.
func (o *Obj) Merge(p *Obj) *Obj {
	n := o.Clone()
	for _, k := range p.Keys() {
		n.Set(k, p.vals[k])
	}
	return n
}

func (o *Obj) MarshalJSON() ([]byte, error) {
	if o == nil {
		return []byte("null"), nil
	}
	return appendJSON(make([]byte, 0, 256), o), nil
}

func (o *Obj) UnmarshalJSON(data []byte) error {
	v, err := parseJSON(data)
	if err != nil {
		return err
	}
	x, ok := v.(*Obj)
	if !ok {
		return fmt.Errorf("not an object")
	}
	*o = *x
	return nil
}

// undefined is a value JSON.stringify leaves out: `{a: undefined}` is `{}`.
type undefined struct{}

// Undefined marks a key to leave out of the JSON, as JavaScript does.
var Undefined = undefined{}

// marshal is JSON.stringify for one value: no HTML escaping, numbers as JavaScript
// prints them, objects in their own key order.
func marshal(v any) ([]byte, error) { return appendJSON(make([]byte, 0, 256), v), nil }

// ToJSON is JSON.stringify.
func ToJSON(v any) []byte { return appendJSON(make([]byte, 0, 512), v) }

const hexDigits = "0123456789abcdef"

// appendString writes s as JSON.stringify does: only quote, backslash and control
// characters are escaped.
func appendString(dst []byte, s string) []byte {
	dst = append(dst, '"')
	start := 0
	for i := 0; i < len(s); {
		c := s[i]
		if c >= 0x20 && c != '"' && c != '\\' && c < 0x80 {
			i++
			continue
		}
		if c < 0x80 {
			dst = append(dst, s[start:i]...)
			switch c {
			case '"':
				dst = append(dst, '\\', '"')
			case '\\':
				dst = append(dst, '\\', '\\')
			case '\n':
				dst = append(dst, '\\', 'n')
			case '\r':
				dst = append(dst, '\\', 'r')
			case '\t':
				dst = append(dst, '\\', 't')
			case '\b':
				dst = append(dst, '\\', 'b')
			case '\f':
				dst = append(dst, '\\', 'f')
			default:
				dst = append(dst, '\\', 'u', '0', '0', hexDigits[c>>4], hexDigits[c&0xf])
			}
			i++
			start = i
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			dst = append(dst, s[start:i]...)
			dst = append(dst, "\ufffd"...)
			i++
			start = i
			continue
		}
		i += size
	}
	dst = append(dst, s[start:]...)
	return append(dst, '"')
}

func appendJSON(dst []byte, v any) []byte {
	switch x := v.(type) {
	case nil:
		return append(dst, "null"...)
	case *Obj:
		if x == nil {
			return append(dst, "null"...)
		}
		dst = append(dst, '{')
		first := true
		for _, k := range x.keys {
			val := x.vals[k]
			if _, skip := val.(undefined); skip {
				continue
			}
			if !first {
				dst = append(dst, ',')
			}
			first = false
			dst = appendString(dst, k)
			dst = append(dst, ':')
			dst = appendJSON(dst, val)
		}
		return append(dst, '}')
	case string:
		return appendString(dst, x)
	case float64:
		return append(dst, jsNum(x)...)
	case bool:
		if x {
			return append(dst, "true"...)
		}
		return append(dst, "false"...)
	case int:
		return strconv.AppendInt(dst, int64(x), 10)
	case int64:
		return strconv.AppendInt(dst, x, 10)
	case float32:
		return append(dst, jsNum(float64(x))...)
	case []any:
		dst = append(dst, '[')
		for i, e := range x {
			if i > 0 {
				dst = append(dst, ',')
			}
			if _, u := e.(undefined); u {
				dst = append(dst, "null"...)
				continue
			}
			dst = appendJSON(dst, e)
		}
		return append(dst, ']')
	case []*Obj:
		dst = append(dst, '[')
		for i, e := range x {
			if i > 0 {
				dst = append(dst, ',')
			}
			dst = appendJSON(dst, e)
		}
		return append(dst, ']')
	case []string:
		dst = append(dst, '[')
		for i, e := range x {
			if i > 0 {
				dst = append(dst, ',')
			}
			dst = appendString(dst, e)
		}
		return append(dst, ']')
	case json.RawMessage:
		return append(dst, x...)
	case undefined:
		return append(dst, "null"...)
	}
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return append(dst, "null"...)
	}
	return append(dst, bytes.TrimRight(b.Bytes(), "\n")...)
}

func jsonString(s string) []byte { return appendString(nil, s) }

// jsNum prints a number as JavaScript's String(n) does.
func jsNum(f float64) string {
	if f != f || f > 1.7976931348623157e308 || f < -1.7976931348623157e308 {
		return "null"
	}
	if f == float64(int64(f)) && f < 1e21 && f > -1e21 {
		return strconv.FormatInt(int64(f), 10)
	}
	abs := f
	if abs < 0 {
		abs = -abs
	}
	if abs >= 1e21 || abs < 1e-6 {
		s := strconv.FormatFloat(f, 'e', -1, 64)
		// Go writes e+21 / e-07; JavaScript writes e+21 / e-7.
		if i := bytes.IndexByte([]byte(s), 'e'); i >= 0 {
			mant, exp := s[:i], s[i+1:]
			sign := exp[0]
			digits := exp[1:]
			for len(digits) > 1 && digits[0] == '0' {
				digits = digits[1:]
			}
			return mant + "e" + string(sign) + digits
		}
		return s
	}
	return strconv.FormatFloat(f, 'f', -1, 64)
}

// parseJSON is JSON.parse: objects come back as *Obj in their own key order, numbers
// as float64, arrays as []any.
func parseJSON(data []byte) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	v, err := parseValue(dec)
	if err != nil {
		return nil, err
	}
	if dec.More() {
		return nil, fmt.Errorf("unexpected data after JSON")
	}
	if _, err := dec.Token(); err == nil {
		return nil, fmt.Errorf("unexpected data after JSON")
	}
	return v, nil
}

func parseValue(dec *json.Decoder) (any, error) {
	t, err := dec.Token()
	if err != nil {
		return nil, err
	}
	return parseFrom(dec, t)
}

func parseFrom(dec *json.Decoder, t json.Token) (any, error) {
	switch x := t.(type) {
	case json.Delim:
		switch x {
		case '{':
			o := &Obj{vals: map[string]any{}}
			for dec.More() {
				kt, err := dec.Token()
				if err != nil {
					return nil, err
				}
				k, _ := kt.(string)
				v, err := parseValue(dec)
				if err != nil {
					return nil, err
				}
				o.Set(k, v)
			}
			if _, err := dec.Token(); err != nil {
				return nil, err
			}
			return o, nil
		case '[':
			arr := []any{}
			for dec.More() {
				v, err := parseValue(dec)
				if err != nil {
					return nil, err
				}
				arr = append(arr, v)
			}
			if _, err := dec.Token(); err != nil {
				return nil, err
			}
			return arr, nil
		}
	case json.Number:
		f, err := x.Float64()
		if err != nil {
			return nil, err
		}
		return f, nil
	case string, bool, nil:
		return x, nil
	}
	return nil, fmt.Errorf("bad JSON token %v", t)
}

// ParseJSON parses text, or returns nil for text that is not JSON.
func ParseJSON(s string) any {
	v, err := parseJSON([]byte(s))
	if err != nil {
		return nil
	}
	return v
}

// --- JavaScript-ish helpers -----------------------------------------------------

func num(v any) float64 {
	switch x := v.(type) {
	case float64:
		return x
	case int:
		return float64(x)
	case int64:
		return float64(x)
	case bool:
		if x {
			return 1
		}
	case string:
		f, err := strconv.ParseFloat(x, 64)
		if err == nil {
			return f
		}
	}
	return 0
}

func truthy(v any) bool {
	switch x := v.(type) {
	case nil, undefined:
		return false
	case bool:
		return x
	case string:
		return x != ""
	case float64:
		return x != 0 && x == x
	case int:
		return x != 0
	case int64:
		return x != 0
	}
	return true
}

// str is String(v) for the values a request body can hold.
func str(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		return jsNum(x)
	case bool:
		if x {
			return "true"
		}
		return "false"
	}
	return string(ToJSON(v))
}

func arr(v any) []any {
	a, _ := v.([]any)
	return a
}

func sortedKeys[M ~map[string]V, V any](m M) []string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	sort.Strings(ks)
	return ks
}
