// Notes: plain Markdown files the person keeps beside their work. A project's are in its
// own `notes/` folder, so they travel with the code and Claude reads and edits them like
// any file; the ones that belong to no project yet live with Bench's data. There is no
// database behind them: the folder is the list, the file's first heading is its title.
//
// The one thing kept apart from the files is which notes go into every session's
// instructions - a switch per note, off unless the person turns it on.
import fs from 'node:fs';
import path from 'node:path';

const MAX_NOTE = 1 << 20;
/** What one included note, and all of them together, may add to a session's instructions. */
const MAX_INCLUDED = 24 * 1024;
const MAX_INCLUDED_TOTAL = 64 * 1024;

/** @param {string} msg @param {number} status */
const fail = (msg, status = 400) => Object.assign(new Error(msg), { status });

/** A file name for a title: "KYC flow: cutting spend" -> "kyc-flow-cutting-spend". @param {string} title */
export function slug(title) {
  const s = String(title).toLowerCase().normalize('NFKD').replace(/[^\w\s-]+/g, ' ').trim().replace(/[\s_]+/g, '-').replace(/-+/g, '-').slice(0, 60).replace(/^-+|-+$/g, '');
  return s || 'note';
}

/** The note's first heading, or its file name without the extension. @param {string} text @param {string} name */
export function titleOf(text, name) {
  for (const line of text.split('\n', 30)) {
    const m = /^#\s+(.+?)\s*#*\s*$/.exec(line);
    if (m) return m[1];
    if (line.trim() && !line.startsWith('---')) break;
  }
  return name.replace(/\.md$/i, '');
}

/** @param {unknown} name @returns {string} */
function checked(name) {
  if (typeof name !== 'string' || !/^[\w][\w .-]{0,99}\.md$/i.test(name) || name.includes('..')) throw fail('Not a note name');
  return name;
}

/** @typedef {{name:string, title:string, mtime:number, size:number, include:boolean}} NoteInfo */

export class Notes {
  /** @param {string} dataDir Bench's data folder */
  constructor(dataDir) {
    this.global = path.join(dataDir, 'notes');
    this.flagsFile = path.join(dataDir, 'notes-included.json');
  }

  /** Where a project's notes are, or the notes of no project. @param {string|null} [projectPath] */
  dir(projectPath) {
    return projectPath ? path.join(projectPath, 'notes') : this.global;
  }

  /** @returns {Record<string, true>} */
  #flags() {
    try {
      return JSON.parse(fs.readFileSync(this.flagsFile, 'utf8'));
    } catch {
      return {};
    }
  }

  /** @param {string|null} [projectPath] @returns {NoteInfo[]} newest first */
  list(projectPath) {
    const dir = this.dir(projectPath);
    let names;
    try {
      names = fs.readdirSync(dir).filter((n) => /\.md$/i.test(n) && !n.startsWith('.'));
    } catch {
      return [];
    }
    const flags = this.#flags();
    /** @type {NoteInfo[]} */
    const out = [];
    for (const name of names) {
      const full = path.join(dir, name);
      let st;
      try {
        st = fs.statSync(full);
      } catch {
        continue;
      }
      if (!st.isFile()) continue;
      // The title needs only the top of the file.
      let head = '';
      try {
        const fd = fs.openSync(full, 'r');
        const buf = Buffer.alloc(Math.min(st.size, 2048));
        fs.readSync(fd, buf, 0, buf.length, 0);
        fs.closeSync(fd);
        head = buf.toString('utf8');
      } catch {}
      out.push({ name, title: titleOf(head, name), mtime: st.mtimeMs, size: st.size, include: !!flags[full] });
    }
    return out.sort((a, b) => b.mtime - a.mtime);
  }

  /** @param {string|null|undefined} projectPath @param {unknown} name */
  read(projectPath, name) {
    const full = path.join(this.dir(projectPath), checked(name));
    let st;
    try {
      st = fs.statSync(full);
    } catch {
      throw fail('No such note', 404);
    }
    if (st.size > MAX_NOTE) throw fail('Too large to open as a note', 413);
    const text = fs.readFileSync(full, 'utf8');
    return { name: /** @type {string} */ (name), title: titleOf(text, /** @type {string} */ (name)), text, mtime: st.mtimeMs, size: st.size, path: full, include: !!this.#flags()[full] };
  }

  /**
   * Saves a note. With `mtime`, refuses when the file changed since it was read - Claude
   * may have edited it in a session - so the person's save cannot silently undo that.
   * @param {string|null|undefined} projectPath @param {unknown} name @param {unknown} text @param {number} [mtime]
   */
  write(projectPath, name, text, mtime) {
    if (typeof text !== 'string') throw fail('No text');
    if (Buffer.byteLength(text) > MAX_NOTE) throw fail('Too large for a note', 413);
    const dir = this.dir(projectPath);
    const full = path.join(dir, checked(name));
    if (mtime) {
      let st = null;
      try {
        st = fs.statSync(full);
      } catch {}
      if (st && Math.abs(st.mtimeMs - mtime) > 1) throw fail('The note changed since you opened it', 409);
    }
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(full, text);
    return this.read(projectPath, name);
  }

  /** A new note under a name made from its title, numbered if that name is taken. @param {string|null|undefined} projectPath @param {unknown} title @param {string} [text] */
  create(projectPath, title, text) {
    const t = String(title || '').trim() || 'Untitled';
    const dir = this.dir(projectPath);
    fs.mkdirSync(dir, { recursive: true });
    const base = slug(t);
    let name = `${base}.md`;
    for (let n = 2; fs.existsSync(path.join(dir, name)); n++) name = `${base}-${n}.md`;
    return this.write(projectPath, name, text ?? `# ${t}\n\n`);
  }

  /** @param {string|null|undefined} projectPath @param {unknown} name */
  remove(projectPath, name) {
    const full = path.join(this.dir(projectPath), checked(name));
    fs.rmSync(full, { force: true });
    this.#flag(full, false);
  }

  /** @param {string} full @param {boolean} on */
  #flag(full, on) {
    const flags = this.#flags();
    if (on) flags[full] = true;
    else delete flags[full];
    // Notes that have gone take their switch with them.
    for (const k of Object.keys(flags)) if (!fs.existsSync(k)) delete flags[k];
    fs.mkdirSync(path.dirname(this.flagsFile), { recursive: true });
    fs.writeFileSync(this.flagsFile, JSON.stringify(flags, null, 1));
  }

  /** Whether a note goes into every session's instructions. @param {string|null|undefined} projectPath @param {unknown} name @param {boolean} on */
  setInclude(projectPath, name, on) {
    const note = this.read(projectPath, name);
    this.#flag(note.path, !!on);
    return { ...note, include: !!on };
  }

  /** Moves a note that belonged to no project into one: an idea that became work. @param {unknown} name @param {string} projectPath */
  move(name, projectPath) {
    const from = path.join(this.global, checked(name));
    if (!fs.existsSync(from)) throw fail('No such note', 404);
    const dir = this.dir(projectPath);
    fs.mkdirSync(dir, { recursive: true });
    let to = path.join(dir, /** @type {string} */ (name));
    for (let n = 2; fs.existsSync(to); n++) to = path.join(dir, /** @type {string} */ (name).replace(/\.md$/i, `-${n}.md`));
    const on = !!this.#flags()[from];
    fs.copyFileSync(from, to);
    fs.rmSync(from);
    this.#flag(from, false);
    if (on) this.#flag(to, true);
    return path.basename(to);
  }

  /**
   * The file a message's `@note:name` means: the project's note of that name, else the
   * one that belongs to no project. The `.md` may be left off.
   * @param {string|null|undefined} projectPath @param {string} token
   */
  resolve(projectPath, token) {
    const name = /\.md$/i.test(token) ? token : `${token}.md`;
    if (!/^[\w][\w .-]{0,99}\.md$/i.test(name) || name.includes('..')) return null;
    for (const dir of projectPath ? [this.dir(projectPath), this.global] : [this.global]) {
      const full = path.join(dir, name);
      if (fs.existsSync(full)) return full;
    }
    return null;
  }

  /**
   * Notes for the composer's @ list, the project's before the rest, matching by title or name.
   * @param {string|null|undefined} projectPath @param {string} q
   * @returns {{token:string, title:string, where:string}[]}
   */
  suggest(projectPath, q) {
    const needle = q.toLowerCase().replace(/^note:/, '');
    /** @type {{token:string, title:string, where:string}[]} */
    const out = [];
    const seen = new Set();
    for (const [where, list] of /** @type {[string, NoteInfo[]][]} */ ([['project', projectPath ? this.list(projectPath) : []], ['notes', this.list(null)]])) {
      for (const n of list) {
        const stem = n.name.replace(/\.md$/i, '');
        if (seen.has(stem) || (needle && !stem.toLowerCase().includes(needle) && !n.title.toLowerCase().includes(needle))) continue;
        seen.add(stem);
        out.push({ token: `note:${stem}`, title: n.title, where });
      }
    }
    // A name with a space in it cannot be an @ token; it is still a file to open.
    return out.filter((n) => !/\s/.test(n.token)).slice(0, 8);
  }

  /**
   * What a session is told about notes: where they are, so "save this as a note" has a
   * place to go, and the text of the ones switched into every session.
   * @param {string|null|undefined} projectPath
   */
  brief(projectPath) {
    const where = projectPath
      ? `This project's notes are Markdown files in ${this.dir(projectPath)}; notes that belong to no project are in ${this.global}.`
      : `The person's notes are Markdown files in ${this.global}.`;
    let out = `# Notes\n\n${where} They are the person's own working notes - plans, specs, things to remember - and Bench shows them beside their sessions. When asked to save or write something as a note, write a Markdown file there, starting with a \`# Title\` line, under a short lowercase-hyphenated name, and say its path in your reply. Read a note when the person points at one; do not go through them unasked. Settled decisions and standing rules for a project belong in its CLAUDE.md, not in a note.`;
    const flags = this.#flags();
    let total = 0;
    for (const full of Object.keys(flags).sort()) {
      const dir = path.dirname(full);
      if (dir !== this.global && !(projectPath && dir === this.dir(projectPath))) continue;
      let text;
      try {
        text = fs.readFileSync(full, 'utf8');
      } catch {
        continue;
      }
      if (text.length > MAX_INCLUDED) text = `${text.slice(0, MAX_INCLUDED)}\n\n(The rest is in the file.)`;
      if (total + text.length > MAX_INCLUDED_TOTAL) break;
      total += text.length;
      out += `\n\n## A note the person keeps in every session: ${full}\n\n${text.trim()}`;
    }
    return out;
  }
}
