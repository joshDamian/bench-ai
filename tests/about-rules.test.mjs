import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { actionOf, Rules } from '../src/rules.mjs';
import { About } from '../src/about.mjs';
import { Notes } from '../src/notes.mjs';

test('tool calls are told apart by what they would do', () => {
  assert.equal(actionOf('mcp__gmail__send_email', {}), 'send-email');
  assert.equal(actionOf('mcp__gmail__create_draft', {}), null, 'a draft is not a send');
  assert.equal(actionOf('mcp__gmail__search_threads', {}), null);
  assert.equal(actionOf('Bash', { command: 'echo hi | sendmail bob@example.com' }), 'send-email');
  assert.equal(actionOf('Bash', { command: 'python3 -c "import smtplib"' }), 'send-email');
  assert.equal(actionOf('Bash', { command: 'curl https://api.stripe.com/v1/transfers -d amount=5' }), 'pay');
  assert.equal(actionOf('Bash', { command: 'curl https://api.stripe.com/v1/customers' }), null);
  assert.equal(actionOf('mcp__bank__send_money', {}), 'pay');
  assert.equal(actionOf('mcp__jobs__apply_job', {}), 'submit-forms');
  assert.equal(actionOf('Read', { file_path: '/x/sendmail.md' }), null);
});

test('Never rules block, and can be changed', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-rules-'));
  const r = new Rules(dir);
  assert.equal(r.blocks('mcp__gmail__send_email', {})?.key, 'send-email');
  r.set('send-email', 'ask');
  assert.equal(r.blocks('mcp__gmail__send_email', {}), null);
  assert.throws(() => r.set('fly', 'never'), /No such rule/);
  assert.match(r.note() || '', /pay or move money/);
});

test('About me: first save switches it on; a session change can be undone', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-about-'));
  const a = new About(dir, new Notes(dir));
  const none = () => null;
  assert.equal(a.get(none).text, null);
  a.write('# About me\n\n## Who you are\n\nJosh\n');
  assert.equal(a.get(none).include, true);
  assert.ok(a.touches('Edit', { file_path: a.file }));
  a.before();
  fs.appendFileSync(a.file, '\n## How you write\n\nShort.\n');
  a.after('s1');
  assert.equal(a.get(() => ({ title: 'Job hunt' })).changed_by?.title, 'Job hunt');
  a.undo();
  assert.doesNotMatch(a.get(none).text || '', /Short/);
  assert.equal(a.get(none).changed_by, null);
  fs.writeFileSync(a.draftFile, '# About me\n\nFrom a CV\n');
  a.draft('save');
  assert.match(a.get(none).text || '', /From a CV/);
  assert.equal(a.get(none).draft, null);
});

test('leaving the box: push, post and delete are told apart', () => {
  assert.equal(actionOf('Bash', { command: 'git push origin main' }), 'push-code');
  assert.equal(actionOf('Bash', { command: 'git push --force origin main' }), 'delete');
  assert.equal(actionOf('Bash', { command: 'rm -rf node_modules' }), null);
  assert.equal(actionOf('Bash', { command: 'rm -rf ~/' }), 'delete');
  assert.equal(actionOf('Bash', { command: 'gh pr comment 5 --body hi' }), 'post-publicly');
  assert.equal(actionOf('mcp__slack__post_message', {}), 'post-publicly');
  assert.equal(actionOf('mcp__github__merge_pull_request', {}), 'push-code');
  assert.equal(actionOf('mcp__drive__delete_file', {}), 'delete');
  assert.equal(actionOf('mcp__bench__ship', {}), null);
});

test('the browser: clicks are told apart by what they click', () => {
  assert.equal(actionOf('mcp__browser__browser_click', { element: 'Submit application button' }), 'submit-forms');
  assert.equal(actionOf('mcp__browser__browser_click', { element: 'Place order' }), 'pay');
  assert.equal(actionOf('mcp__browser__browser_click', { element: 'Next page link' }), null);
  assert.equal(actionOf('mcp__browser__browser_type', { text: 'hi', submit: true }), 'submit-forms');
  assert.equal(actionOf('mcp__browser__browser_navigate', { url: 'https://x' }), null);
});
