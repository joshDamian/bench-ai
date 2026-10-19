// Ask Bench: questions and actions about the whole of Bench, in words, in a conversation
// that belongs to no project. The page is a question box and the conversations so far;
// a conversation itself is the session pane, as any other session's is.
import { html, useEffect, useState, recall, remember, formatWhen } from '../lib.mjs';
import { api, useStore } from '../api.mjs';
import { showToast } from '../components/toast.mjs';
import { Dot, StatusBadge } from '../components/ui.mjs';
import { SessionPane } from './session.mjs';
import { go } from './project.mjs';

const toast = (e) => showToast(e.message || String(e), 'error');
const KEY = 'bench:ask';

export function AskView({ sessionId }) {
  if (sessionId) return html`<div class="session-page"><${SessionPane} key=${sessionId} sessionId=${sessionId} onArchived=${() => go('#/ask')} /></div>`;
  return html`<${AskHome} />`;
}

function AskHome() {
  const conversations = useStore((s) => s.sessions.filter((x) => !x.project_id));
  const [question, setQuestion] = useState(() => recall(KEY, ''));
  const [busy, setBusy] = useState(false);
  useEffect(() => remember(KEY, question || null), [question]);

  const ask = async () => {
    if (!question.trim() || busy) return;
    setBusy(true);
    try {
      const s = await api.createSession({ prompt: question });
      setQuestion('');
      go(`#/ask/${s.id}`);
    } catch (e) {
      toast(e);
      setBusy(false);
    }
  };

  return html`
    <div class="page">
      <header class="page-header">
        <div>
          <h1>Ask Bench</h1>
          <div class="muted">Every project, task, session, provider and device. It asks before it changes anything.</div>
        </div>
      </header>
      <section class="card ask-card">
        <textarea
          class="input"
          rows="3"
          aria-label="Your question"
          placeholder="What's running, and what has it cost today? · Start a task in api to bump the SDK · Which provider is failing?"
          value=${question}
          onInput=${(e) => setQuestion(e.target.value)}
          onKeyDown=${(e) => (e.metaKey || e.ctrlKey) && e.key === 'Enter' && ask()}
        />
        <div class="row ask-foot">
          <span class="muted hide-touch">⌘↵</span>
          <button class="btn" type="button" disabled=${busy || !question.trim()} onClick=${ask}>${busy ? 'Asking…' : 'Ask'}</button>
        </div>
      </section>
      ${conversations.length
        ? html`<section class="section">
            <div class="section-head"><h2>Conversations</h2></div>
            <div class="list">
              ${conversations.map(
                (s) => html`<a class="list-row" href=${`#/ask/${s.id}`} key=${s.id}>
                  <div class="list-row-main"><${Dot} status=${s.status} /><span class="row-title">${s.title}</span></div>
                  <div class="list-row-side">${s.status !== 'idle' ? html`<${StatusBadge} status=${s.status} />` : null}<span class="muted time">${formatWhen(s.updated_at)}</span></div>
                </a>`
              )}
            </div>
          </section>`
        : null}
    </div>
  `;
}
