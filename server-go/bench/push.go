package bench

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// Web Push, as the web-push package sends it: RFC 8291 aes128gcm encryption and a VAPID
// (RFC 8292) ES256 token, written on the standard library.

type VAPID struct {
	PublicKey, PrivateKey string // base64url, as web-push stores them
	priv                  *ecdsa.PrivateKey
}

var b64 = base64.RawURLEncoding

func decodeB64(s string) ([]byte, error) {
	s = strings.TrimRight(s, "=")
	if b, err := b64.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(s)
}

func GenerateVAPID() (*VAPID, error) {
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return LoadVAPID(b64.EncodeToString(k.PublicKey().Bytes()), b64.EncodeToString(k.Bytes()))
}

func LoadVAPID(pub, priv string) (*VAPID, error) {
	pb, err := decodeB64(pub)
	if err != nil || len(pb) != 65 {
		return nil, errors.New("bad VAPID public key")
	}
	d, err := decodeB64(priv)
	if err != nil || len(d) != 32 {
		return nil, errors.New("bad VAPID private key")
	}
	key := &ecdsa.PrivateKey{PublicKey: ecdsa.PublicKey{Curve: elliptic.P256(), X: new(big.Int).SetBytes(pb[1:33]), Y: new(big.Int).SetBytes(pb[33:])}, D: new(big.Int).SetBytes(d)}
	return &VAPID{PublicKey: pub, PrivateKey: priv, priv: key}, nil
}

func (v *VAPID) token(endpoint string) (string, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return "", err
	}
	header := b64.EncodeToString([]byte(`{"typ":"JWT","alg":"ES256"}`))
	claims := b64.EncodeToString(ToJSON(O("aud", u.Scheme+"://"+u.Host, "exp", float64(time.Now().Add(12*time.Hour).Unix()), "sub", "mailto:noreply@localhost")))
	h := sha256.Sum256([]byte(header + "." + claims))
	r, s, err := ecdsa.Sign(rand.Reader, v.priv, h[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return header + "." + claims + "." + b64.EncodeToString(sig), nil
}

// PushError carries the push service's status, so a gone subscription can be dropped.
type PushError struct{ Status int }

func (e *PushError) Error() string { return fmt.Sprintf("push service answered %d", e.Status) }

var pushClient = &http.Client{Timeout: 30 * time.Second}

// Send encrypts payload for one subscription and posts it.
func (v *VAPID) Send(endpoint, p256dh, auth string, payload []byte) error {
	uaPub, err := decodeB64(p256dh)
	if err != nil {
		return err
	}
	secret, err := decodeB64(auth)
	if err != nil {
		return err
	}
	ua, err := ecdh.P256().NewPublicKey(uaPub)
	if err != nil {
		return err
	}
	eph, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	shared, err := eph.ECDH(ua)
	if err != nil {
		return err
	}
	asPub := eph.PublicKey().Bytes()
	prkKey, err := hkdf.Extract(sha256.New, shared, secret)
	if err != nil {
		return err
	}
	info := append(append([]byte("WebPush: info\x00"), uaPub...), asPub...)
	ikm, err := hkdf.Expand(sha256.New, prkKey, string(info), 32)
	if err != nil {
		return err
	}
	salt := make([]byte, 16)
	rand.Read(salt)
	prk, err := hkdf.Extract(sha256.New, ikm, salt)
	if err != nil {
		return err
	}
	cek, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	nonce, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	block, err := aes.NewCipher(cek)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	ct := gcm.Seal(nil, nonce, append(append([]byte(nil), payload...), 0x02), nil)
	var body bytes.Buffer
	body.Write(salt)
	binary.Write(&body, binary.BigEndian, uint32(4096))
	body.WriteByte(byte(len(asPub)))
	body.Write(asPub)
	body.Write(ct)
	tok, err := v.token(endpoint)
	if err != nil {
		return err
	}
	req, err := http.NewRequest("POST", endpoint, &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Encoding", "aes128gcm")
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("TTL", "2419200")
	req.Header.Set("Authorization", "vapid t="+tok+", k="+v.PublicKey)
	res, err := pushClient.Do(req)
	if err != nil {
		return err
	}
	res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return &PushError{Status: res.StatusCode}
	}
	return nil
}

// QRSVG is what QRCode.toString(text, {type:'svg', margin:1}) draws.
func QRSVG(text string) (string, error) {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return "", err
	}
	q.DisableBorder = true
	bm := q.Bitmap()
	n := len(bm)
	size := n + 2
	var d strings.Builder
	for y, row := range bm {
		run := 0
		start := -1
		for x := 0; x <= n; x++ {
			on := x < n && row[x]
			if on {
				if start < 0 {
					start = x
				}
				run++
				continue
			}
			if start >= 0 {
				fmt.Fprintf(&d, "M%d %d.5h%d", start+1, y+1, run)
				start, run = -1, 0
			}
		}
	}
	return fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges"><path fill="#ffffff" d="M0 0h%dv%dH0z"/><path stroke="#000000" d="%s"/></svg>`+"\n", size, size, size, size, d.String()), nil
}
