import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { Notes, slug, titleOf } from '../src/notes.mjs';
import { mentionedFiles } from '../src/runtime.mjs';
import { tmp } from './tmp.mjs';

// Notes are files: a project's in its notes/ folder, the rest with Bench's data. These
// cover the folder as the list, names made from titles, a save that will not undo an edit
// it has not seen, the per-note switch into every session, and @note: in a message.

test('a note is a file named for its title, listed newest first', () => {
  const notes = new Notes(tmp('data'));
  const project = tmp('project');
  const a = notes.create(project, 'KYC flow: cutting WhatsApp spend');
  assert.equal(a.name, 'kyc-flow-cutting-whatsapp-spend.md');
  assert.equal(a.title, 'KYC flow: cutting WhatsApp spend');
  assert.ok(fs.existsSync(path.join(project, 'notes', a.name)));
  const b = notes.create(project, 'KYC flow: cutting WhatsApp spend');
  assert.equal(b.name, 'kyc-flow-cutting-whatsapp-spend-2.md');
  assert.deepEqual(notes.list(project).map((n) => n.name).sort(), [a.name, b.name].sort());
  assert.deepEqual(notes.list(null), []);
  assert.equal(slug('  ¿Qué?  '), 'que');
  assert.equal(titleOf('no heading here', 'plan.md'), 'plan');
});

test('names cannot leave the notes folder', () => {
  const notes = new Notes(tmp('data'));
  for (const bad of ['../x.md', 'a/b.md', '.hidden.md', 'x.txt', '', null]) assert.throws(() => notes.read(null, bad), /Not a note name/);
  assert.equal(notes.resolve(null, '../../etc/passwd'), null);
});

test('a save refuses to overwrite a change it has not seen', () => {
  const notes = new Notes(tmp('data'));
  const n = notes.create(null, 'Plan');
  fs.writeFileSync(n.path, '# Plan\n\nClaude added this.\n');
  fs.utimesSync(n.path, new Date(), new Date(Date.now() + 5000));
  assert.throws(() => notes.write(null, n.name, '# Plan\n\nMine.\n', n.mtime), (e) => e.status === 409);
  assert.match(notes.write(null, n.name, '# Plan\n\nMine.\n').text, /Mine/);
});

test('only notes switched on go into a session, and only where they belong', () => {
  const notes = new Notes(tmp('data'));
  const mine = tmp('mine');
  const other = tmp('other');
  const a = notes.create(mine, 'House rules', '# House rules\n\nNever send the video.\n');
  notes.create(mine, 'Scratch', '# Scratch\n\nNot for sessions.\n');
  const g = notes.create(null, 'Weekly plan', '# Weekly plan\n\nShip notes.\n');
  assert.doesNotMatch(notes.brief(mine), /Never send the video/);
  notes.setInclude(mine, a.name, true);
  notes.setInclude(null, g.name, true);
  assert.equal(notes.list(mine).find((n) => n.name === a.name)?.include, true);
  assert.match(notes.brief(mine), /Never send the video/);
  assert.match(notes.brief(mine), /Ship notes/);
  assert.doesNotMatch(notes.brief(mine), /Not for sessions/);
  assert.doesNotMatch(notes.brief(other), /Never send the video/);
  assert.match(notes.brief(other), /Ship notes/);
  notes.remove(mine, a.name);
  assert.doesNotMatch(notes.brief(mine), /Never send the video/);
});

test('@note: attaches the project note first, then one of no project', () => {
  const notes = new Notes(tmp('data'));
  const project = tmp('project');
  const cwd = tmp('worktree');
  notes.create(project, 'Spec', '# Spec\n\nProject spec.\n');
  notes.create(null, 'Spec', '# Spec\n\nGlobal spec.\n');
  notes.create(null, 'Idea', '# Idea\n\nPayouts.\n');
  const at = (text) => mentionedFiles(cwd, text, (name) => notes.resolve(project, name));
  assert.match(at('build @note:spec'), /Project spec/);
  assert.match(at('think about @note:idea.'), /Payouts/);
  assert.equal(at('see @note:nope and @note:../../etc/passwd'), '');
  assert.deepEqual(notes.suggest(project, 'sp').map((n) => [n.token, n.where]), [['note:spec', 'project']]);
});

test('a note moves into the project it became', () => {
  const notes = new Notes(tmp('data'));
  const project = tmp('project');
  const n = notes.create(null, 'Payouts reconciliation');
  notes.setInclude(null, n.name, true);
  assert.equal(notes.move(n.name, project), n.name);
  assert.deepEqual(notes.list(null), []);
  assert.equal(notes.list(project)[0].include, true);
});
