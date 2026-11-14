package bench

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// A push Go encrypts is one the web-push package's own decrypter (http_ece) reads back.
func TestPushDecryptsWithHttpEce(t *testing.T) {
	ece := filepath.Join(AppRoot, "node_modules", "http_ece")
	if _, err := os.Stat(ece); err != nil {
		t.Skip("http_ece not installed")
	}
	ua, _ := ecdh.P256().GenerateKey(rand.Reader)
	auth := make([]byte, 16)
	rand.Read(auth)
	var body []byte
	var authz string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ = io.ReadAll(r.Body)
		authz = r.Header.Get("Authorization")
		w.WriteHeader(201)
	}))
	defer srv.Close()
	v, err := GenerateVAPID()
	if err != nil {
		t.Fatal(err)
	}
	if err := v.Send(srv.URL+"/push/abc", b64.EncodeToString(ua.PublicKey().Bytes()), b64.EncodeToString(auth), []byte(`{"title":"Hello","body":"From Go"}`)); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(authz, "vapid t=") {
		t.Fatalf("authorization %q", authz)
	}
	script := `const ece=require(process.argv[1]);const crypto=require('crypto');
const k=crypto.createECDH('prime256v1');k.setPrivateKey(Buffer.from(process.argv[2],'base64url'));
process.stdout.write(ece.decrypt(Buffer.from(process.argv[4],'base64'),{version:'aes128gcm',privateKey:k,authSecret:process.argv[3]}).toString())`
	out, err := exec.Command("node", "-e", script, ece, b64.EncodeToString(ua.Bytes()), b64.EncodeToString(auth), base64.StdEncoding.EncodeToString(body)).CombinedOutput()
	if err != nil {
		t.Fatalf("%v: %s", err, out)
	}
	if string(out) != `{"title":"Hello","body":"From Go"}` {
		t.Fatalf("decrypted %q", out)
	}
}
