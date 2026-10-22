// @ts-check
import { providerReady } from './providers.mjs';

/** @typedef {import('./contract.mjs').Session} Session */
/** @typedef {import('./contract.mjs').SessionPause} SessionPause */
/** @typedef {import('./contract.mjs').LimitSettings} LimitSettings */

// After the usage limit: the Claude login's limit stops a session mid-task, and this sends it
// back to work when the limit lifts.
//
// The runtime says 'limit' when a turn ended on it. The session is marked paused - it stays
// idle; `pause` says until when - and the pause is the whole record: it lives in the session's
// row, so a restart finds every paused session and its time again. One timer is kept, for the
// next session due. At the reset time plus a buffer the session is sent "Continue where you
// left off.", one at a time and a stagger apart, so a handful paused together do not all start
// at the same second. A session that hits the limit again goes back to paused with the new
// time; one that keeps hitting it with no time ahead of it backs off, and after MAX_TRIES Bench
// stops sending and leaves it for the person.
//
// Instead of waiting, the person can ask for the work to be handed to another provider: a new
// session there, in the same task, given a summary of where the paused one got to. A session
// cannot change provider in place - the environment is fixed when its process starts - and a
// hosted model cannot read the Claude login's transcript, so a fresh session with a handoff
// is how the work moves.

export const RESUME_TEXT = 'Continue where you left off.';
/** After the reset time, before resuming: Claude Code's clock and the limit's are not ours. */
export const RESUME_BUFFER_MS = 90 * 1000;
/** Between two sessions resumed together. */
export const STAGGER_MS = 30 * 1000;
/** When the limit named no time, or named one already past, how long to wait before trying. */
export const RETRY_MS = 30 * 60 * 1000;
/** Resumes in a row that find the limit still there before Bench stops trying. */
export const MAX_TRIES = 3;
// setTimeout holds at most 2^31-1 ms; a weekly limit is further off than that can say.
const MAX_TIMER_MS = 60 * 60 * 1000;

/** @param {number} ms */
const when = (ms) => new Date(ms).toISOString();

/**
 * When a paused session is due to be resumed. Null when it is not to be: handed to another
 * provider, or given up on.
 * @param {SessionPause} p
 */
export function dueAt(p) {
  if (p.handed_to || p.tries > MAX_TRIES) return null;
  if (p.until) return Date.parse(p.until) + RESUME_BUFFER_MS;
  return Date.parse(p.at) + RETRY_MS * Math.max(1, p.tries);
}

export class Resumer {
  /**
   * @param {import('./service.mjs').Service} svc
   * @param {{now?: () => number, stagger?: number}} [o] the clock and the stagger, for tests
   */
  constructor(svc, o = {}) {
    this.svc = svc;
    this.store = svc.store;
    this.runtime = svc.runtime;
    this.now = o.now || Date.now;
    this.stagger = o.stagger ?? STAGGER_MS;
    /** @type {ReturnType<typeof setTimeout>|null} */
    this.timer = null;
    /** When the last resume was sent, so the next keeps its distance. */
    this.lastSent = 0;
    /** Sessions this scheduler sent back to work, and how many times in a row. A turn that
     * ends without the limit clears it. Kept in memory: a restart costs at most a few tries.
     * @type {Map<string, number>} */
    this.sent = new Map();
    this.runtime.on('limit', (e) => this.limitHit(e));
    this.runtime.on('turn-done', ({ session, limited }) => {
      if (session && !limited) this.sent.delete(session.id);
    });
    // Paused sessions survive a restart in their rows; the timer is set again from them.
    this.schedule();
  }

  /** @returns {LimitSettings} */
  settings() {
    /** @type {LimitSettings['fallback']} */
    let fallback = null;
    try {
      fallback = JSON.parse(this.store.getSetting('limit_fallback') || 'null');
    } catch {
      /* unreadable is none */
    }
    return { autoResume: this.store.getSetting('auto_resume') !== '0', fallback };
  }

  /** Whether Bench resumes this session itself. @param {Session} s */
  autoResume(s) {
    return s.auto_resume ?? this.settings().autoResume;
  }

  /**
   * The runtime says a turn ended on the usage limit.
   * @param {{session_id:string, message:string, until:Date|null}} e
   */
  limitHit(e) {
    const s = this.store.getSession(e.session_id);
    if (!s) return;
    const now = this.now();
    const tries = this.sent.get(s.id) || 0;
    let until = e.until && e.until.getTime() > now - RESUME_BUFFER_MS ? when(e.until.getTime()) : null;
    // Resumed at the time it named and refused again: that time was not the one that matters.
    if (until && tries && Date.parse(until) + RESUME_BUFFER_MS <= now) until = null;
    /** @type {SessionPause} */
    const pause = { until, message: e.message, at: when(now), tries, handed_to: null };
    this.#set(s.id, pause);
    if (tries > MAX_TRIES) {
      this.runtime.record(s.id, 'notice', { text: `The usage limit is still there after ${MAX_TRIES} tries; Bench has stopped resuming this session. Send a message to carry on.` });
    } else {
      const handed = this.#handOff(s, pause);
      if (!handed) {
        const due = dueAt(pause);
        const auto = this.autoResume(s);
        this.runtime.record(s.id, 'notice', {
          text: `Paused: ${e.message}.${auto && due ? ` Bench will send "${RESUME_TEXT}" at ${new Date(due).toUTCString().replace(/:00 GMT$/, ' UTC')}.` : ' Auto-resume is off for this session.'}`,
        });
      }
    }
    this.schedule();
  }

  /** @param {string} id @param {SessionPause|null} pause */
  #set(id, pause) {
    this.runtime.emit('session', this.store.updateSession(id, { pause }));
  }

  /**
   * Hands a paused session's work to the person's fallback provider, when they chose one.
   * Only a task or checkout session's - a review only reads, and an Ask Bench conversation is
   * the person's own. Returns the new session's id, or null when nothing was handed over.
   * @param {Session} s @param {SessionPause} pause
   */
  #handOff(s, pause) {
    const fb = this.settings().fallback;
    if (!fb || !s.project_id || s.purpose === 'review' || fb.providerId === s.provider_id) return null;
    if (s.auto_resume === false) return null;
    const provider = this.store.getProvider(fb.providerId);
    if (!provider || !providerReady(provider).ready) {
      this.runtime.record(s.id, 'notice', { text: `Could not hand this work to the fallback provider: ${provider ? providerReady(provider).reason : 'it is gone'}. Waiting for the limit instead.` });
      return null;
    }
    try {
      const next = this.svc.createSession({
        projectId: s.project_id,
        taskId: s.task_id,
        title: `${s.title} (continued)`.slice(0, 120),
        providerId: provider.id,
        modelId: fb.modelId,
        mode: s.permission_mode,
        startedBy: s.started_by_session,
        prompt: this.handoff(s, pause),
      });
      this.#set(s.id, { ...pause, handed_to: next.id });
      this.runtime.record(s.id, 'notice', { text: `Paused: ${pause.message}. The work was handed to a new session on ${provider.name}: "${next.title}".` });
      return next.id;
    } catch (err) {
      this.runtime.record(s.id, 'notice', { text: `Could not hand this work to ${provider.name}: ${/** @type {Error} */ (err).message}. Waiting for the limit instead.` });
      return null;
    }
  }

  /**
   * What a session taking over is told: what the work is, and where the paused one got to,
   * from Bench's own transcript - the paused session cannot be asked, it is out of usage.
   * @param {Session} s @param {SessionPause} pause
   */
  handoff(s, pause) {
    const task = s.task_id ? this.store.getTask(s.task_id) : null;
    const events = this.store.listEvents(s.id, { limit: 400 });
    const first = events.find((e) => e.kind === 'user')?.data?.text || '';
    const said = events
      .filter((e) => e.kind === 'assistant' && !e.data?.parent)
      .flatMap((e) => (e.data?.content || []).filter((/** @type {any} */ b) => b.type === 'text').map((/** @type {any} */ b) => String(b.text)))
      .filter((t) => !/^You've (hit|reached) your/.test(t));
    const recent = [];
    let room = 6000;
    for (let i = said.length - 1; i >= 0 && room > 0; i--) {
      const t = said[i].length > 2000 ? `${said[i].slice(0, 2000)}…` : said[i];
      recent.unshift(t);
      room -= t.length;
    }
    return [
      `You are taking over work from another session ("${s.title}"), which stopped because the Claude login reached its usage limit (${pause.message}). It cannot continue until the limit resets, so carry the work on from where it got to.`,
      task ? `The task: ${task.title}${task.done ? `\nDone means: ${task.done}` : ''}` : null,
      first ? `What the person first asked it:\n<<<\n${first.slice(0, 4000)}\n>>>` : null,
      recent.length ? `What it last said, oldest first:\n<<<\n${recent.join('\n\n---\n\n')}\n>>>` : null,
      'Its changes, if it made any, are in the working tree: read `git status` and `git diff` before you start, then continue the work rather than redoing it.',
    ]
      .filter(Boolean)
      .join('\n\n');
  }

  /** Sessions due a resume, soonest first: paused, auto-resume on, not archived. */
  #queue() {
    return this.store
      .pausedSessions()
      .filter((s) => !s.archived_at && s.pause && this.autoResume(s))
      .map((s) => ({ s, due: dueAt(/** @type {SessionPause} */ (s.pause)) }))
      .filter((x) => x.due !== null)
      .sort((a, b) => /** @type {number} */ (a.due) - /** @type {number} */ (b.due));
  }

  /** When the next resume goes, if one is due at all. */
  next() {
    const first = this.#queue()[0];
    if (!first) return null;
    return Math.max(/** @type {number} */ (first.due), this.lastSent + this.stagger);
  }

  /** Sets the timer for the next session due. Call after anything that changes the queue. */
  schedule() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    const at = this.next();
    if (at === null) return;
    this.timer = setTimeout(() => this.tick(), Math.min(MAX_TIMER_MS, Math.max(0, at - this.now())));
    this.timer.unref?.();
  }

  /** Sends the session that is due, if one is and the stagger allows, then sets the next timer. */
  tick() {
    this.timer = null;
    const now = this.now();
    const at = this.next();
    if (at !== null && at <= now) {
      const first = this.#queue()[0];
      if (first) this.resume(first.s.id);
    }
    this.schedule();
  }

  /**
   * Sends a paused session back to work now. A session someone already took over - busy, or
   * no longer paused - is left alone.
   * @param {string} id
   */
  resume(id) {
    const s = this.store.getSession(id);
    if (!s?.pause) return false;
    if (this.runtime.busy(id)) {
      this.#set(id, null);
      return false;
    }
    this.lastSent = this.now();
    this.sent.set(id, (this.sent.get(id) || 0) + 1);
    try {
      this.runtime.record(id, 'notice', { text: 'The usage limit has reset; Bench is resuming this session.' });
      this.svc.send(id, RESUME_TEXT);
      return true;
    } catch (e) {
      // A task closed or a folder gone while it waited: nothing to resume into.
      this.#set(id, null);
      this.runtime.record(id, 'notice', { text: `Could not resume: ${/** @type {Error} */ (e).message}` });
      return false;
    }
  }

  /** A person changed a setting or a session's switch: the queue may have changed. */
  changed() {
    this.schedule();
  }

  shutdown() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}
