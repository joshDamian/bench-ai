import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { suggestChecks } from '../src/checks.mjs';
import { tmp } from './tmp.mjs';

// A project's likely checks, from the files that say what kind of project it is.

const project = (files) => {
  const dir = tmp('checks');
  for (const [name, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, name), body);
  return dir;
};
const commands = (dir) => suggestChecks(dir).map((s) => s.command);

test('a Node project: its scripts, with its own package manager', () => {
  assert.deepEqual(commands(project({ 'package.json': JSON.stringify({ scripts: { test: 'jest', lint: 'eslint .', dev: 'vite' } }), 'tsconfig.json': '{}' })),
    ['npm test', 'npm run lint', 'npx tsc --noEmit']);
  assert.deepEqual(commands(project({ 'package.json': JSON.stringify({ scripts: { test: 'vitest', typecheck: 'tsc' } }), 'pnpm-lock.yaml': '', 'tsconfig.json': '{}' })),
    ['pnpm test', 'pnpm run typecheck']);
});

test("npm's placeholder test script is not a check", () => {
  assert.deepEqual(commands(project({ 'package.json': JSON.stringify({ scripts: { test: 'echo "Error: no test specified" && exit 1' } }) })), []);
});

test('other kinds of project', () => {
  assert.deepEqual(commands(project({ 'Cargo.toml': '' })), ['cargo test']);
  assert.deepEqual(commands(project({ 'go.mod': '' })), ['go test ./...']);
  assert.deepEqual(commands(project({ 'pyproject.toml': '[tool.pytest.ini_options]' })), ['pytest']);
  assert.deepEqual(commands(project({ Makefile: 'build:\n\tcc x.c\ntest: build\n\t./x\n' })), ['make test']);
});

test('nothing to go on: no suggestions', () => {
  assert.deepEqual(commands(project({ 'README.md': '# hi' })), []);
});
