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
  { key: 'send-email', label: 'Send email' },
  { key: 'submit-forms', label: 'Submit applications or forms' },
  { key: 'pay', label: 'Pay or move money' },
];

/** @type {Record<string, RuleValue>} */
const DEFAULTS = { 'send-email': 'never', 'submit-forms': 'never', pay: 'never' };

const MAIL_TOOL = /(gmail|outlook|mail|email|smtp|sendgrid|mailgun|postmark|resend)/i;
const SEND_VERB = /(^|[_\-.])(send|reply|forward)([_\-.]|$)|send_?(email|mail|message|draft)/i;
const PAY_TOOL = /(^|[_\-.])(pay|payment|payout|transfer|charge|refund|purchase|checkout|send_?money|withdraw)([_\-.]|$)/i;
const FORM_TOOL = /(^|[_\-.])(submit|apply)([_\-.]|$)|submit_?(form|application)|apply_?(job|to)/i;

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
    return null;
  }
  // Tools from connected apps are named mcp__<server>__<tool>.
  if (!name.startsWith('mcp__')) return null;
  if (MAIL_TOOL.test(name) && SEND_VERB.test(name)) return 'send-email';
  if (PAY_TOOL.test(name)) return 'pay';
  if (FORM_TOOL.test(name)) return 'submit-forms';
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

  /** The Never rule a tool call would break, or null. @param {string} tool @param {any} input */
  blocks(tool, input) {
    const kind = actionOf(tool, input);
    if (!kind || this.values()[kind] !== 'never') return null;
    return ACTIONS.find((a) => a.key === kind) || null;
  }

  /** What a session is told, so it does not try in the first place. */
  note() {
    const never = this.list().filter((r) => r.value === 'never');
    if (!never.length) return null;
    return `# Never\n\nThe person never wants Bench to do these, and Bench stops them before they run: ${never.map((r) => r.label.toLowerCase()).join('; ')}. Do not look for another way. Prepare the thing instead and leave the last step to the person: an email becomes a drafts file in the reports folder, a form or application becomes the filled-in answers in a report.`;
  }
}
