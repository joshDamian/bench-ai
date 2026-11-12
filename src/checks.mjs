// Commands a project probably checks itself with, read from the files that say what kind
// of project it is: a package.json's scripts, a Cargo.toml, a go.mod and so on. They are
// suggestions for a task's check; nothing runs them until the person picks one.
import fs from 'node:fs';
import path from 'node:path';

/** @typedef {{command:string, from:string}} Suggestion */

/** @param {string} dir @param {string} name */
const has = (dir, name) => fs.existsSync(path.join(dir, name));

/** @param {string} dir @param {string} name */
function read(dir, name) {
  try {
    return fs.readFileSync(path.join(dir, name), 'utf8');
  } catch {
    return '';
  }
}

/** The package manager a Node project uses, from its lockfile. @param {string} dir */
function runner(dir) {
  if (has(dir, 'pnpm-lock.yaml')) return 'pnpm';
  if (has(dir, 'yarn.lock')) return 'yarn';
  if (has(dir, 'bun.lockb') || has(dir, 'bun.lock')) return 'bun';
  return 'npm';
}

/** @param {string} dir @returns {Suggestion[]} at most six, the likeliest first */
export function suggestChecks(dir) {
  /** @type {Suggestion[]} */
  const out = [];
  const add = (/** @type {string} */ command, /** @type {string} */ from) => {
    if (!out.some((s) => s.command === command)) out.push({ command, from });
  };

  const pkg = read(dir, 'package.json');
  if (pkg) {
    let scripts = {};
    try {
      scripts = JSON.parse(pkg).scripts || {};
    } catch {}
    const pm = runner(dir);
    const run = (/** @type {string} */ s) => (s === 'test' && pm !== 'bun' ? `${pm} test` : `${pm} run ${s}`);
    for (const s of ['test', 'typecheck', 'check', 'lint', 'build']) {
      const body = /** @type {Record<string,string>} */ (scripts)[s];
      // npm's placeholder test script fails by design; it is not a check.
      if (body && !/no test specified/.test(body)) add(run(s), `package.json · ${s}`);
    }
  }
  if (has(dir, 'tsconfig.json') && !out.some((s) => /typecheck|tsc/.test(s.command))) add('npx tsc --noEmit', 'tsconfig.json');
  if (has(dir, 'Cargo.toml')) add('cargo test', 'Cargo.toml');
  if (has(dir, 'go.mod')) add('go test ./...', 'go.mod');
  if (has(dir, 'pyproject.toml') || has(dir, 'pytest.ini') || has(dir, 'setup.cfg')) {
    if (has(dir, 'pytest.ini') || /pytest/.test(read(dir, 'pyproject.toml'))) add('pytest', has(dir, 'pytest.ini') ? 'pytest.ini' : 'pyproject.toml');
  }
  const make = read(dir, 'Makefile');
  for (const target of ['test', 'check']) if (new RegExp(`^${target}:`, 'm').test(make)) add(`make ${target}`, 'Makefile');
  if (has(dir, 'gradlew')) add('./gradlew test', 'gradlew');
  if (has(dir, 'Package.swift')) add('swift test', 'Package.swift');
  return out.slice(0, 6);
}
