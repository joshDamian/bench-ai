package bench

import "testing"

func TestActionOf(t *testing.T) {
	cases := []struct {
		tool, cmd, want string
	}{
		{"mcp__gmail__send_email", "", "send-email"},
		{"mcp__gmail__create_draft", "", ""},
		{"Bash", "echo hi | sendmail bob@example.com", "send-email"},
		{"Bash", `python3 -c "import smtplib"`, "send-email"},
		{"Bash", "curl https://api.stripe.com/v1/transfers -d amount=5", "pay"},
		{"Bash", "curl https://api.stripe.com/v1/customers", ""},
		{"mcp__bank__send_money", "", "pay"},
		{"mcp__jobs__apply_job", "", "submit-forms"},
		{"Bash", "git push origin main", "push-code"},
		{"Bash", "git push --force origin main", "delete"},
		{"Bash", "rm -rf node_modules", ""},
		{"Bash", "rm -rf ~/", "delete"},
		{"Bash", "gh pr comment 5 --body hi", "post-publicly"},
		{"mcp__slack__post_message", "", "post-publicly"},
		{"mcp__github__merge_pull_request", "", "push-code"},
		{"mcp__drive__delete_file", "", "delete"},
		{"mcp__bench__ship", "", ""},
		{"Read", "", ""},
	}
	for _, c := range cases {
		in := O("command", c.cmd)
		if got := ActionOf(c.tool, in); got != c.want {
			t.Errorf("%s %q: got %q, want %q", c.tool, c.cmd, got, c.want)
		}
	}
}
