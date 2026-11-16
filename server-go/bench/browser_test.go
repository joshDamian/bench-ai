package bench

import "testing"

func TestCrashLineSaysWhy(t *testing.T) {
	out := "[1234:1234:1115/093552.123:FATAL:zygote_host_impl_linux.cc(132)] No usable sandbox! If you are running on Ubuntu 23.10+ or another Linux distro that has disabled unprivileged user namespaces with AppArmor, see https://chromium.googlesource.com/x\n#0 0x55d0 base::debug::CollectStackTrace()\n#1 0x55d1 (anonymous)\n[end of stack trace]\n"
	got := crashLine(out)
	if got[:19] != "No usable sandbox! " {
		t.Fatalf("crashLine = %q", got)
	}
	if !sandboxFailed.MatchString(out) {
		t.Fatal("a sandbox failure is seen as one")
	}
}

func TestWebAddress(t *testing.T) {
	for in, want := range map[string]string{"linkedin.com/jobs": "https://linkedin.com/jobs", "http://x.test": "http://x.test", "remote jobs lagos": "https://duckduckgo.com/?q=remote+jobs+lagos", " ": ""} {
		if got := webAddress(in); got != want {
			t.Errorf("webAddress(%q) = %q, want %q", in, got, want)
		}
	}
}
