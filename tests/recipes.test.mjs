import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { Service } from '../src/service.mjs';
import { blanksOf } from '../src/recipes.mjs';
import { thenSteps } from '../src/reports.mjs';
import { fakeQuery } from './fake-sdk.mjs';
import { tmp } from './tmp.mjs';

test('a recipe has blanks, each once, in order', () => {
  assert.deepEqual(blanksOf('Find 5 remote {role} jobs in {region}, {role} only'), ['role', 'region']);
  assert.deepEqual(blanksOf('No blanks here, {} or {\n}'), []);
});

test('a reply offers next steps on its "Then:" lines', () => {
  assert.deepEqual(thenSteps('Done.\n\nThen: Draft cold emails for the top 3\n- **Then:** Keep watching'), ['Draft cold emails for the top 3', 'Keep watching']);
  assert.equal(thenSteps('Then is when it ran.'), null);
  assert.equal(thenSteps(null), null);
});

test('recipes are saved, changed and removed', () => {
  const root = tmp('root');
  process.env.CLAUDE_CONFIG_DIR = path.join(root, 'claude');
  const svc = new Service(root, { query: fakeQuery });
  const dir = tmp('repo');
  execFileSync('git', ['init', '-q', dir]);
  const project = svc.addProject({ path: dir });
  assert.throws(() => svc.addRecipe({ title: ' ', prompt: 'x' }), /needs a name/);
  const r = svc.addRecipe({ title: 'Job hunt', prompt: 'Find 5 remote {role} jobs in {region}', projectId: project.id });
  assert.deepEqual(r.blanks, ['role', 'region']);
  assert.equal(svc.updateRecipe(r.id, { title: 'Jobs' }).title, 'Jobs');
  assert.equal(svc.recipes().length, 1);
  svc.deleteRecipe(r.id);
  assert.throws(() => svc.deleteRecipe(r.id), /No such recipe/);
});
