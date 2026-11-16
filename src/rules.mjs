// @ts-check
// Rules: what Bench may do without asking, what it asks about, and what it never does.
// The person's Never list starts here: sending email, submitting applications or forms,
// and paying or moving money. Bench stops a tool call that would do one of them before it
// runs, whatever the session was told, and says so in the conversation.
//
// Telling an action apart is done from the tool call itself: the tool's name (an email,
// payment or form tool from a connected app) and, for shell commands, the programs and
// web addresses that send mail or move money. A session that writes its own program to
// do it is not seen here; the instructions tell it plainly not to try.
import fs from 'node:fs';
import path from 'node:path';

/** @typedef {'allow'|'ask'|'never'} RuleValue */

/** The kinds of action a rule can be about, in the order the apps list them. */
export const ACTIONS = [
  { key: 'push-code', label: 'Push code' },
  { key: 'post-publicly', label: 'Post publicly' },
  { key: 'delete', label: 'Delete for good' },
  { key: 'send-email', label: 'Send email' },
  { key: 'submit-forms', label: 'Submit applications or forms' },
  { key: 'pay', label: 'Pay or move money' },
];

/** @type {Record<string, RuleValue>} */
const DEFAULTS = { 'push-code': 'ask', 'post-publicly': 'ask', delete: 'ask', 'send-email': 'never', 'submit-forms': 'never', pay: 'never' };

const MAIL_TOOL = /(gmail|outlook|mail|email|smtp|sendgrid|mailgun|postmark|resend)/i;
const SEND_VERB = /(^|[_\-.])(send|reply|forward)([_\-.]|$)|send_?(email|mail|message|draft)/i;
const PAY_TOOL = /(^|[_\-.])(pay|payment|payout|transfer|charge|refund|purchase|checkout|send_?money|withdraw)([_\-.]|$)/i;
const FORM_TOOL = /(^|[_\-.])(submit|apply)([_\-.]|$)|submit_?(form|application)|apply_?(job|to)/i;

const PAY_WORDS = /\b(pay|checkout|check out|place order|buy|purchase|transfer|donate)\b/i;
const SUBMIT_WORDS = /\b(submit|apply|send)\b/i;
const SOCIAL_TOOL = /(slack|twitter|linkedin|discord|telegram|whatsapp|mastodon|bluesky|reddit)/i;
const POST_VERB = /(^|[_\-.])(post|send|publish|tweet|message|reply|comment)([_\-.]|$)|post_?message|send_?message/i;
const GIT_TOOL = /(github|gitlab|bitbucket)/i;
const PUSH_VERB = /(^|[_\-.])(push|merge)([_\-.]|$)|push_?files|merge_?pull/i;
const DELETE_VERB = /(^|[_\-.])(delete|remove|destroy|drop|purge)([_\-.]|$)/i;
const DELETE_COMMAND = /\brm\s+-[a-zA-Z]*[rR][a-zA-Z]*\s+(\/|~\/?|\$HOME\/?)\*?(\s|$)|\bgit\s+push\b.*\s(--force|-f)\b|\bgit\s+branch\s+-D\b|\b(DROP|drop)\s+(TABLE|DATABASE|table|database)\b/;
const PUSH_COMMAND = /\bgit\s+push\b/;
const POST_COMMAND = /\bgh\s+(issue|pr)\s+(comment|create)\b|\bgh\s+release\s+create\b/;
const MAIL_COMMAND = /\b(sendmail|mailx?|mutt|msmtp|swaks|ssmtp)\b|smtps?:\/\/|\bsmtplib\b|nodemailer|gmail\.googleapis\.com\/\S*\/send|api\.sendgrid\.com|api\.mailgun\.net|api\.postmarkapp\.com|api\.resend\.com\/emails/i;
const PAY_COMMAND = /api\.stripe\.com\/v1\/(charges|payment_intents|transfers|payouts|refunds)|api\.paystack\.co\/(transfer|charge)|api\.flutterwave\.com\/v3\/(transfers|charges)|api\.paypal\.com\/v\d\/payments/i;

/**
 * Which kind of action a tool call is, if it is one a rule covers.
 * @param {string} tool @param {any} input
 * @returns {string|null}
 */
export function actionOf(tool, input) {
  const name = String(tool || '');
  if (name === 'Bash') {
    const cmd = String(input?.command || '');
    if (MAIL_COMMAND.test(cmd)) return 'send-email';
    if (PAY_COMMAND.test(cmd)) return 'pay';
    if (DELETE_COMMAND.test(cmd)) return 'delete';
    if (PUSH_COMMAND.test(cmd)) return 'push-code';
    if (POST_COMMAND.test(cmd)) return 'post-publicly';
    return null;
  }
  // Tools from connected apps are named mcp__<server>__<tool>. Bench's own tools are its
  // own flows, asked about in their own words.
  if (!name.startsWith('mcp__') || name.startsWith('mcp__bench__')) return null;
  // The browser: a click is told apart by what it clicks, typing by whether it submits.
  if (name.startsWith('mcp__browser__')) {
    const what = String(input?.element || '');
    if (/browser_(click|press_key)$/.test(name) && PAY_WORDS.test(what)) return 'pay';
    if (/browser_click$/.test(name) && SUBMIT_WORDS.test(what)) return 'submit-forms';
    if (/browser_type$/.test(name) && input?.submit === true) return 'submit-forms';
    return null;
  }
  if (MAIL_TOOL.test(name) && SEND_VERB.test(name)) return 'send-email';
  if (PAY_TOOL.test(name)) return 'pay';
  if (FORM_TOOL.test(name)) return 'submit-forms';
  if (SOCIAL_TOOL.test(name) && POST_VERB.test(name)) return 'post-publicly';
  if (GIT_TOOL.test(name) && PUSH_VERB.test(name)) return 'push-code';
  if (DELETE_VERB.test(name)) return 'delete';
  return null;
}

export class Rules {
  /** @param {string} dataDir */
  constructor(dataDir) {
    this.file = path.join(dataDir, 'rules.json');
  }

  /** @returns {Record<string, RuleValue>} */
  values() {
    /** @type {Record<string, RuleValue>} */
    let saved = {};
    try {
      saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch {}
    return { ...DEFAULTS, ...saved };
  }

  /** Every rule, for the apps. */
  list() {
    const v = this.values();
    return ACTIONS.map((a) => ({ ...a, value: v[a.key] }));
  }

  /** @param {unknown} key @param {unknown} value */
  set(key, value) {
    if (!ACTIONS.some((a) => a.key === key)) throw Object.assign(new Error('No such rule'), { status: 404 });
    if (!['allow', 'ask', 'never'].includes(String(value))) throw Object.assign(new Error('A rule is allow, ask or never'), { status: 400 });
    let saved = {};
    try {
      saved = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch {}
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify({ ...saved, [String(key)]: value }, null, 1));
    return this.list();
  }

  /** The rule a tool call falls under, when it asks or never: null when it is allowed. @param {string} tool @param {any} input */
  decide(tool, input) {
    const kind = actionOf(tool, input);
    const value = kind ? this.values()[kind] : 'allow';
    if (!kind || value === 'allow') return null;
    const a = /** @type {{key:string,label:string}} */ (ACTIONS.find((x) => x.key === kind));
    return { ...a, value };
  }

  /** The Never rule a tool call would break, or null. @param {string} tool @param {any} input */
  blocks(tool, input) {
    const d = this.decide(tool, input);
    return d?.value === 'never' ? d : null;
  }

  /** What a session is told, so it does not try in the first place. */
  note() {
    const never = this.list().filter((r) => r.value === 'never');
    if (!never.length) return null;
    return `# Never\n\nThe person never wants Bench to do these, and Bench stops them before they run: ${never.map((r) => r.label.toLowerCase()).join('; ')}. Do not look for another way. Prepare the thing instead and leave the last step to the person: an email becomes a drafts file in the reports folder, a form or application becomes the filled-in answers in a report.`;
  }
}
