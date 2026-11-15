// @ts-check
// Reports: what a session made for the person to read or use, as opposed to files it worked
// on. Each session has a reports folder inside its attachments folder; Claude puts what it
// made there, in the format that fits (see REPORTS_NOTE):
//   name.md          to read: rendered at reading width
//   name.html        to use: sort, filter, tick off; shown in a sandboxed viewer
//   name.drafts.json emails to send: cards with Open in Gmail, Copy and Mark sent
// The apps list them on Home ("Ready for you") until the session is looked at.
import fs from 'node:fs';
import path from 'node:path';
import { findBoard, boardTitle, newCount } from './boards.mjs';

/** @param {string} attachmentsDir */
export const reportsDir = (attachmentsDir) => path.join(attachmentsDir, 'reports');

export const REPORTS_NOTE = (/** @type {string} */ dir) => `# Reports for the person

When the person asked for something to read or use - research, a shortlist, a comparison, a plan, emails to send - put it in this session's reports folder, ${dir} (create it if it is not there), and give its full path in your reply, so the person can open it from there. Bench lists it on the person's phone and Mac, where they read it later, often away from you. Pick the format by what they will do with it:

- To read: Markdown (.md). Lead with the answer, then the detail; links inline.
- To use: one self-contained HTML page (.html) when it needs sorting, filtering, checkboxes or a chart. Inline its CSS and script; it gets no network. Use the CSS variables --bg, --surface, --text, --muted, --accent, --line for colours, so it matches the person's light or dark theme, and keep state in localStorage. For choices use buttons or chips, not <select>: a select opens the phone's own unstyled picker.
- Emails to send: a drafts file (name.drafts.json): {"title": "...", "drafts": [{"to": "address", "subject": "...", "body": "plain text", "why": "one line on why this person"}]}. Bench never sends them; the person opens each in their own email app. Never send email yourself.

One file per output, with a clear name - it is what the person sees. Mixing is fine: a job hunt can be an HTML board and a drafts file.

When there is an obvious next step the person may want, end your reply with it on its own line, as "Then: " and the step in a few words they would say themselves ("Then: Draft cold emails for the top 3"). Two at most; none when nothing follows.`;

/** The next steps a reply offered, from its "Then: …" lines; null when it offered none. @param {string|null|undefined} reply */
export function thenSteps(reply) {
  const steps = [...String(reply ?? '').matchAll(/^[ \t]*(?:[-*][ \t]*)?\**Then:\**[ \t]*(.+?)[ \t]*$/gim)].map((m) => m[1].replace(/\*+$/, '').trim()).filter((x) => x && x.length <= 120);
  return steps.length ? steps.slice(0, 2) : null;
}

/** @param {string} file */
export function kindOf(file) {
  if (file.endsWith('.drafts.json')) return 'drafts';
  if (/\.html?$/i.test(file)) return 'page';
  if (/\.(md|markdown)$/i.test(file)) return 'report';
  return 'file';
}

/** A report's title: its first heading, its <title>, the drafts file's title, else its name. @param {string} full @param {string} kind */
function titleOf(full, kind) {
  const name = path.basename(full).replace(/\.drafts\.json$|\.[^.]+$/, '');
  try {
    const head = fs.readFileSync(full, 'utf8').slice(0, 20000);
    if (kind === 'report') return head.match(/^#\s+(.+)$/m)?.[1]?.trim() || name;
    if (kind === 'page') return head.match(/<title>([^<]+)<\/title>/i)?.[1]?.trim() || name;
    if (kind === 'drafts') return String(JSON.parse(fs.readFileSync(full, 'utf8')).title || name);
  } catch {
    // An unreadable or half-written file still lists, by its name.
  }
  return name;
}

/** @param {string} full */
function drafts(full) {
  try {
    const d = JSON.parse(fs.readFileSync(full, 'utf8'));
    return Array.isArray(d.drafts) ? d.drafts : [];
  } catch {
    return [];
  }
}

/**
 * Every session's reports, newest first. `seen` is whether the person looked at the session
 * after the report was last written.
 * @param {import('./service.mjs').Service} svc
 */
export function listReports(svc) {
  const root = path.join(svc.store.dir, 'attachments');
  const out = [];
  for (const id of fs.existsSync(root) ? fs.readdirSync(root) : []) {
    const s = svc.store.getSession(id);
    if (!s) continue;
    const dir = reportsDir(path.join(root, id));
    if (!fs.existsSync(dir)) continue;
    for (const file of fs.readdirSync(dir)) {
      const full = path.join(dir, file);
      const st = fs.statSync(full, { throwIfNoEntry: false });
      if (!st?.isFile()) continue;
      const kind = kindOf(file);
      const modified = st.mtime.toISOString();
      const item = { session_id: id, path: full, name: file, kind, title: titleOf(full, kind), size: st.size, modified, seen: !!s.seen_at && s.seen_at >= modified, schedule_id: s.schedule_id || null };
      if (kind === 'drafts') {
        const list = drafts(full);
        Object.assign(item, { drafts: list.length, unsent: list.filter((/** @type {any} */ x) => !x.sent_at).length });
      }
      out.push(item);
    }
  }
  // Boards: one per schedule that keeps one, seen when its latest run was looked at after it changed.
  for (const sch of svc.store.listSchedules()) {
    if (!sch.keep_board) continue;
    const project = svc.store.getProject(sch.project_id);
    const file = project ? findBoard(project.path, sch.title) : null;
    const last = file ? svc.store.sessionsOfSchedule(sch.id)[0] : null;
    if (!file || !last) continue;
    const st = fs.statSync(file);
    const modified = st.mtime.toISOString();
    out.push({ session_id: last.id, path: file, name: path.basename(file), kind: 'board', title: boardTitle(file, sch.title), size: st.size, modified, seen: !!last.seen_at && last.seen_at >= modified, schedule_id: sch.id, new: newCount(file) });
  }
  return out.sort((a, b) => (a.modified < b.modified ? 1 : -1));
}

/** Marks one draft sent or not, in the file itself, so every device sees it. @param {import('./service.mjs').Service} svc @param {string} sessionId @param {string} file @param {number} index @param {boolean} sent */
export function markDraft(svc, sessionId, file, index, sent) {
  const dir = reportsDir(svc.runtime.attachmentsDir(sessionId));
  const full = path.resolve(dir, path.basename(file));
  if (path.dirname(full) !== dir || !fs.existsSync(full) || kindOf(full) !== 'drafts') throw Object.assign(new Error('No such drafts file'), { status: 404 });
  const d = JSON.parse(fs.readFileSync(full, 'utf8'));
  const item = d.drafts?.[index];
  if (!item) throw Object.assign(new Error('No such draft'), { status: 404 });
  if (sent) item.sent_at = new Date().toISOString();
  else delete item.sent_at;
  // Written in place keeps the file's time, so marking a draft does not make it "new" again.
  const st = fs.statSync(full);
  fs.writeFileSync(full, JSON.stringify(d, null, 2));
  fs.utimesSync(full, st.atime, st.mtime);
  return { ok: true, sent_at: item.sent_at ?? null };
}
