package bench

import (
	"os/exec"
	"strings"
	"testing"
)

// Each value, written by appendJSON, is byte-for-byte what JSON.stringify writes.
func TestJSONMatchesNode(t *testing.T) {
	cases := []string{`"a\"b\\c\n\t\r\b\f\u0001\u001f é ü 😀 <>&"`, `[1,1.5,-0.25,1e+21,1e-7,123456789012,0.1,null,true,false,{"z":1,"a":[]}]`, `{"b":2,"a":{"y":"x","k":[1,{"q":null}]}}`, `"  "`}
	for _, c := range cases {
		want, err := exec.Command("node", "-e", "process.stdout.write(JSON.stringify(JSON.parse(process.argv[1])))", c).Output()
		if err != nil {
			t.Fatal(err)
		}
		got := string(ToJSON(ParseJSON(c)))
		if got != string(want) {
			t.Errorf("\n got %s\nwant %s", got, want)
		}
	}
	if s := string(ToJSON(O("a", Undefined, "b", 1.0))); s != `{"b":1}` {
		t.Errorf("undefined: %s", s)
	}
	if !strings.Contains(string(ToJSON("x\xffy")), `�`) {
		t.Error("invalid utf-8")
	}
}
