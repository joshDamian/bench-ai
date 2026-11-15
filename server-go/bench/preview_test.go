package bench

import "testing"

func TestMDPreview(t *testing.T) {
	gist, lines := mdPreview("# 7 hackathons\n\nHackLagos has the **biggest** local prize.\nTwo are online.\n\n## HackLagos 2026 — Lagos, Nov 22\n\ntext\n\n## Solana Breakout: online, $50k\n\n## Sources\n")
	if gist != "HackLagos has the biggest local prize. Two are online." {
		t.Fatalf("gist %q", gist)
	}
	if len(lines) != 2 || lines[0].(*Obj).Str("title") != "HackLagos 2026" || lines[1].(*Obj).Str("sub") != "online, $50k" {
		t.Fatalf("lines %v", lines)
	}
	_, items := mdPreview("# T\n\nIntro.\n\n- [Paystack](https://x) - backend role\n- Moniepoint\n")
	if len(items) != 2 || items[0].(*Obj).Str("title") != "Paystack" || items[0].(*Obj).Str("sub") != "backend role" {
		t.Fatalf("items %v", items)
	}
}
