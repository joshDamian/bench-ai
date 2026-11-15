// @ts-check
// Recipes: a prompt the person saved to run again, with blanks they fill in each time
// ("Find 5 remote {role} jobs in {region}"). They are listed in New, under the four.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/** The names of a prompt's blanks, in order, each once. @param {string} prompt */
export function blanksOf(prompt) {
  return [...new Set([...String(prompt).matchAll(/\{([^{}\n]{1,40})\}/g)].map((m) => m[1].trim()).filter(Boolean))];
}

export class Recipes {
  /** @param {string} dataDir */
  constructor(dataDir) {
    this.file = path.join(dataDir, 'recipes.json');
  }

  /** @returns {{id:string, title:string, prompt:string, project_id:string|null, created_at:string}[]} */
  #read() {
    try {
      const list = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return Array.isArray(list) ? list : [];
    } catch {
      return [];
    }
  }

  /** @param {any[]} list */
  #write(list) {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify(list, null, 2));
  }

  /** @param {any} r */
  #view(r) {
    return { id: r.id, title: r.title, prompt: r.prompt, project_id: r.project_id ?? null, blanks: blanksOf(r.prompt), created_at: r.created_at };
  }

  list() {
    return this.#read().map((r) => this.#view(r));
  }

  /** @param {{title:string, prompt:string, project_id:string|null}} o */
  add(o) {
    const r = { id: crypto.randomUUID(), title: o.title, prompt: o.prompt, project_id: o.project_id, created_at: new Date().toISOString() };
    this.#write([...this.#read(), r]);
    return this.#view(r);
  }

  /** @param {string} id @param {Record<string, any>} patch */
  update(id, patch) {
    const list = this.#read();
    const r = list.find((x) => x.id === id);
    if (!r) return null;
    Object.assign(r, patch);
    this.#write(list);
    return this.#view(r);
  }

  /** @param {string} id */
  remove(id) {
    const list = this.#read();
    const left = list.filter((x) => x.id !== id);
    if (left.length === list.length) return false;
    this.#write(left);
    return true;
  }
}
