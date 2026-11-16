// @ts-check
// About me: who the person is, read by every session so drafts and applications come out
// with their details in them. It is a note of no project, about-me.md, with the notes'
// own "in every session" switch, so the Mac's notes show it and Claude edits it like any
// file. Two hidden files sit beside it: a draft made from a CV, which is used only once
// the person saves it, and the version before a session last changed it, for Undo.
import fs from 'node:fs';
import path from 'node:path';

export const ABOUT_NAME = 'about-me.md';

export const ABOUT_TEMPLATE = '# About me\n\n## Who you are\n\n## Your work\n\n## Links\n\n## How you write\n';

export class About {
  /** @param {string} dataDir @param {import('./notes.mjs').Notes} notes */
  constructor(dataDir, notes) {
    this.notes = notes;
    this.stateFile = path.join(dataDir, 'about.json');
  }

  get file() {
    return path.join(this.notes.global, ABOUT_NAME);
  }
  get draftFile() {
    return path.join(this.notes.global, '.about-me.draft.md');
  }
  get prevFile() {
    return path.join(this.notes.global, '.about-me.prev.md');
  }

  /** @returns {{changed_by?:string|null}} */
  #state() {
    try {
      return JSON.parse(fs.readFileSync(this.stateFile, 'utf8'));
    } catch {
      return {};
    }
  }

  /** @param {{changed_by?:string|null}} s */
  #save(s) {
    fs.mkdirSync(path.dirname(this.stateFile), { recursive: true });
    fs.writeFileSync(this.stateFile, JSON.stringify(s));
  }

  /** @param {string} file */
  #read(file) {
    try {
      return fs.readFileSync(file, 'utf8');
    } catch {
      return null;
    }
  }

  /**
   * About me as the apps show it.
   * @param {(id: string) => {title?: string}|null|undefined} sessionOf
   */
  get(sessionOf) {
    const text = this.#read(this.file);
    const by = this.#state().changed_by || null;
    const s = by ? sessionOf(by) : null;
    let include = false;
    if (text !== null) {
      try {
        include = this.notes.read(null, ABOUT_NAME).include;
      } catch {}
    }
    return {
      text,
      draft: this.#read(this.draftFile),
      include,
      path: this.file,
      changed_by: by && fs.existsSync(this.prevFile) ? { session_id: by, title: s?.title || null } : null,
    };
  }

  /** The person's own save: it replaces any session's change, so there is nothing to undo. @param {string} text */
  write(text) {
    const first = !fs.existsSync(this.file);
    this.notes.write(null, ABOUT_NAME, text);
    if (first) this.notes.setInclude(null, ABOUT_NAME, true);
    fs.rmSync(this.prevFile, { force: true });
    this.#save({ changed_by: null });
  }

  /** @param {boolean} on */
  include(on) {
    if (!fs.existsSync(this.file)) throw Object.assign(new Error('Write About me first'), { status: 400 });
    this.notes.setInclude(null, ABOUT_NAME, on);
  }

  /** Saves or throws away the draft made from a CV. @param {unknown} action */
  draft(action) {
    const text = this.#read(this.draftFile);
    if (text === null) throw Object.assign(new Error('There is no draft'), { status: 404 });
    if (action === 'save') this.write(text);
    else if (action !== 'discard') throw Object.assign(new Error('Save or discard'), { status: 400 });
    fs.rmSync(this.draftFile, { force: true });
  }

  /** Puts back what a session last changed. */
  undo() {
    const prev = this.#read(this.prevFile);
    if (prev === null) throw Object.assign(new Error('Nothing to undo'), { status: 404 });
    // Empty: the session wrote About me for the first time, so undoing removes it.
    if (prev === '') this.notes.remove(null, ABOUT_NAME);
    else this.notes.write(null, ABOUT_NAME, prev);
    fs.rmSync(this.prevFile, { force: true });
    this.#save({ changed_by: null });
  }

  /** Whether a tool call is about to change About me. @param {string} tool @param {any} input */
  touches(tool, input) {
    if (!['Write', 'Edit', 'MultiEdit'].includes(tool)) return false;
    const p = String(input?.file_path || '');
    return !!p && path.resolve(p) === path.resolve(this.file);
  }

  /** Before a session changes it: keeps the version to go back to. */
  before() {
    fs.mkdirSync(this.notes.global, { recursive: true });
    fs.writeFileSync(this.prevFile, this.#read(this.file) ?? '');
  }

  /** After a session changed it. @param {string} sessionId */
  after(sessionId) {
    if (!fs.existsSync(this.file)) return;
    // A session that writes About me for the first time switches it on, as the person's own first save does.
    if (this.#read(this.prevFile) === '') {
      try {
        this.notes.setInclude(null, ABOUT_NAME, true);
      } catch {}
    }
    this.#save({ changed_by: sessionId });
  }

  /** What every session is told about it. */
  note() {
    return `# About me\n\nThe person keeps a note about themselves in ${this.file}: who they are, their work, their links and how they write. Use it whenever you write for them or as them, and fill in their own details rather than leaving blanks. When they ask you to remember something about themselves or how they like things done, add one short line under the right heading there and say "Added to About me" in your reply. Do not add anything they did not ask for.`;
  }

  /** The request that turns a CV into a draft. */
  cvPrompt() {
    return `Read the attached CV and write a draft of my About me to ${this.draftFile}. Use exactly these headings, in this order: "## Who you are", "## Your work", "## Links", "## How you write". Write short plain sentences and only what the CV says; under a heading the CV says nothing about, write "Not in the CV." Start the file with "# About me". Do not write anything else to that file or anywhere else. Then reply in one line that the draft is ready to check.`;
  }
}
