// @ts-check
// Watching a page: a schedule that runs when a web page changes, not at a time. Bench looks
// at the page every few hours and keeps its text; when the text changes, it starts a run with
// what is new and the person's ask ("tell me when a role in Lagos is posted"). A run that
// finds nothing that matters replies "Nothing new." and is put away without a notification.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/** What a run replies when a change does not matter. */
export const NOTHING_NEW = 'Nothing new.';

/** Whether a run's reply is the quiet one. @param {string|null|undefined} text */
export const isNothingNew = (text) => String(text ?? '').trim().replace(/^["'`]+|["'`]+$/g, '') === NOTHING_NEW;

/**
 * The fields of a watch the person sent, checked: {url, every_hours}. Null clears it.
 * @param {any} w @returns {{url:string, every_hours:number}|null}
 */
export function watchFields(w, fail = (/** @type {string} */ m) => new Error(m)) {
  if (w === null || w === undefined || w === false) return null;
  const url = String(w.url || '').trim();
  if (!/^https?:\/\/[^\s/]+\.[^\s]+$/i.test(url)) throw fail('Watch a page by its web address, starting with https://');
  const every = w.everyHours ?? w.every_hours ?? 6;
  const n = Number(every);
  if (!Number.isInteger(n) || n < 1 || n > 168) throw fail('Check every 1 to 168 hours');
  return { url, every_hours: n };
}

/** A page's readable text: tags, scripts and styles gone, one line per block. @param {string} html */
export function pageText(html) {
  return String(html)
    .replace(/<(script|style|noscript|svg|template)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<\/?(p|div|li|tr|h[1-6]|br|section|article|header|footer|ul|ol|table|main|nav)\b[^>]*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .split('\n')
    .map((l) => l.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .join('\n');
}

/** @param {string} text */
export const textHash = (text) => crypto.createHash('sha256').update(text).digest('hex');

/** The lines in the new text that the old did not have, up to a few thousand characters. @param {string} before @param {string} after */
export function addedLines(before, after) {
  const old = new Set(before.split('\n'));
  let out = '';
  for (const l of after.split('\n')) {
    if (old.has(l)) continue;
    if (out.length + l.length > 4000) return out + '…\n';
    out += l + '\n';
  }
  return out;
}

/** Where a watch keeps the page's text as it was last seen. @param {string} dataDir @param {string} id */
export const watchFile = (dataDir, id) => path.join(dataDir, 'watches', `${id}.txt`);

/** @param {string} dataDir @param {string} id */
export function lastText(dataDir, id) {
  try {
    return fs.readFileSync(watchFile(dataDir, id), 'utf8');
  } catch {
    return '';
  }
}

/** @param {string} dataDir @param {string} id @param {string} text */
export function keepText(dataDir, id, text) {
  fs.mkdirSync(path.join(dataDir, 'watches'), { recursive: true });
  fs.writeFileSync(watchFile(dataDir, id), text);
}

/** The page's text now. @param {string} url */
export async function fetchText(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(20_000), headers: { 'user-agent': 'Mozilla/5.0 (compatible; Bench)', accept: 'text/html,*/*' } });
  if (!r.ok) throw new Error(`the page answered ${r.status}`);
  return pageText(await r.text());
}

/** What a run of a watch is told. @param {string} ask @param {string} url @param {string} added */
export function watchPrompt(ask, url, added) {
  return `${url} has changed since Bench last looked. New on the page:\n\n${added.trim() || '(Only removals or small edits.)'}\n\nWhat the person asked: ${ask}\n\nOpen the page if you need more than this. If the change does not matter for what they asked, reply exactly "${NOTHING_NEW}" and nothing else.`;
}
