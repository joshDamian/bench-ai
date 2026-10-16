// A stand-in for the Agent SDK's query(), driven by what the test sends.
//
//   "write <file> <text>"  asks permission for Write, and writes the file if allowed
//   "ask"                  asks an AskUserQuestion and replies with the answer
//   "plan"                 asks to leave plan mode with a plan
//   "slow"                 holds the turn open until interrupted
//   "fail"                 ends the turn as an error
//   "new skill"            pushes a changed command list, as a skill found mid-session does
//   "suggest <text>"       sends that text as the turn's prompt suggestion, as a real
//                          model does when it has a guess at what comes next
//   anything else          replies "echo: <text>"
//
// Every turn reasons first, in a thinking block of its own, before the reply - a real
// transcript is mostly this. Stop hooks in options.hooks run at the end of each turn;
// see below.
//
// Every call is recorded on `calls`, so a test can see the options a session started with.
import fs from 'node:fs';
import path from 'node:path';

export const calls = [];

// What the model reasons through before it answers. A real block is prose that runs to
// thousands of characters; this is a couple of paragraphs, which is enough for a view
// that folds it, counts it or fetches it to have something of the right shape.
const THOUGHTS = [
  'Let me work out what is actually being asked before I change anything.',
  'There are a couple of readings of this and only one of them is worth acting on, so I want to look at how it works today first.',
  'The code that matters is the branch that already fails; the rest of the file is not what this is about.',
  'Once I have read it, the change is small - the shape is there and one case is missing from it.',
  'I should run the check afterwards rather than assume, because the other cases are what a change like this breaks.',
];

/** The reasoning for one turn, ending at a length a real one often reaches. @param {string} text */
function reasoning(text) {
  let out = `They asked: ${text}\n`;
  for (let i = 0; out.length < 1200; i++) out += `${THOUGHTS[i % THOUGHTS.length]}\n`;
  return out;
}

/** What supportedCommands() answers, as Claude Code describes them. */
export const COMMANDS = [
  { name: 'compact', description: 'Free up context by summarizing the conversation so far', argumentHint: '<optional custom summarization instructions>' },
  { name: 'bench:make-verify', description: '(bench) Writes a verify skill for this project', argumentHint: '' },
];

export function fakeQuery({ prompt, options }) {
  const sessionId = options.resume || `claude-${Math.random().toString(36).slice(2, 10)}`;
  const record = { options, mode: options.permissionMode, model: options.model, closed: false, interrupted: 0 };
  calls.push(record);
  let total = options.resume ? 0.5 : 0;
  let wakeInterrupt = null;
  const out = [];
  let notify = null;
  const emit = (m) => {
    out.push(m);
    notify?.();
  };

  (async () => {
    emit({ type: 'system', subtype: 'init', session_id: sessionId, model: options.model || 'default', permissionMode: options.permissionMode, cwd: options.cwd, slash_commands: ['compact'] });
    for await (const msg of prompt) {
      if (record.closed) break;
      const content = msg.message.content;
      const text = typeof content === 'string' ? content : content.filter((b) => b.type === 'text').map((b) => b.text).join(' ');
      const id = `msg_${Math.random().toString(36).slice(2)}`;
      emit({ type: 'stream_event', parent_tool_use_id: null, event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'thinking about it' } } });
      let reply = `echo: ${text}`;
      let isError = false;
      let suggestion = '';
      if (text.startsWith('write ')) {
        const [, file, ...rest] = text.split(' ');
        const input = { file_path: path.join(options.cwd, file), content: rest.join(' ') };
        emit({ type: 'assistant', parent_tool_use_id: null, message: { id, content: [{ type: 'tool_use', id: 'tu1', name: 'Write', input }] } });
        const ac = new AbortController();
        const r = await options.canUseTool('Write', input, { signal: ac.signal, suggestions: [{ type: 'addRules', rules: [{ toolName: 'Write' }], behavior: 'allow', destination: 'localSettings' }], title: `Claude wants to write ${file}` });
        if (r.behavior === 'allow') fs.writeFileSync(r.updatedInput.file_path, r.updatedInput.content);
        emit({ type: 'user', parent_tool_use_id: null, message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tu1', is_error: r.behavior !== 'allow', content: r.behavior === 'allow' ? 'written' : r.message }] } });
        reply = r.behavior === 'allow' ? 'wrote it' : `not allowed: ${r.message}`;
      } else if (text === 'ask') {
        const r = await options.canUseTool('AskUserQuestion', { questions: [{ question: 'Which?', options: [{ label: 'A' }, { label: 'B' }] }] }, { signal: new AbortController().signal });
        reply = `answered ${JSON.stringify(r.updatedInput?.answers || null)}`;
      } else if (text === 'plan') {
        const r = await options.canUseTool('ExitPlanMode', { plan: '## Plan\n\n1. Read the code\n2. Change it' }, { signal: new AbortController().signal });
        reply = r.behavior === 'allow' ? 'plan approved' : `plan rejected: ${r.message}`;
      } else if (text === 'slow') {
        if (!record.interruptPending) await new Promise((r) => (wakeInterrupt = r));
        record.interruptPending = false;
        reply = 'interrupted';
        isError = true;
      } else if (text === 'fail') isError = true;
      else if (text.startsWith('suggest ')) suggestion = text.slice('suggest '.length).trim();
      else if (text === 'new skill') {
        record.commands = [...COMMANDS, { name: 'deploy', description: 'Ship it', argumentHint: '<env>' }];
        emit({ type: 'system', subtype: 'commands_changed', commands: record.commands, session_id: sessionId });
      }
      // Reasoning first, in a message of its own, the way it arrives: it is the largest
      // part of a real transcript by a long way and the transcript view folds it away, so
      // every turn here has some for the views that fold it to fold.
      emit({ type: 'assistant', parent_tool_use_id: null, message: { id: `${id}t`, content: [{ type: 'thinking', thinking: reasoning(text), signature: 'sig' }] } });
      emit({ type: 'assistant', parent_tool_use_id: null, message: { id: `${id}b`, content: [{ type: 'text', text: reply }] } });
      // Stop hooks, as Claude Code runs them: a block sends the model back to work with
      // the reason. This model answers each one by changing a file, which is what a real
      // one fixing things does to the worktree, then tries to stop again.
      for (let round = 0; !isError && options.hooks?.Stop && round < 10; round++) {
        let blocked = null;
        for (const matcher of options.hooks.Stop) {
          for (const hook of matcher.hooks) {
            const r = await hook({ hook_event_name: 'Stop', stop_hook_active: round > 0, background_tasks: [] }, undefined, { signal: new AbortController().signal });
            if (r?.decision === 'block') blocked = r.reason;
          }
        }
        if (!blocked) break;
        record.blocked = [...(record.blocked || []), blocked];
        fs.writeFileSync(path.join(options.cwd, `fix-${round}.txt`), blocked.slice(0, 40));
        emit({ type: 'assistant', parent_tool_use_id: null, message: { id: `${id}r${round}`, content: [{ type: 'text', text: `fixing (round ${round})` }] } });
      }
      // Last, after every block this turn put in the transcript: the client drops the
      // suggestion it holds when the next turn's own text arrives, so one sent before the
      // reply would be wiped by the reply.
      if (suggestion) emit({ type: 'prompt_suggestion', suggestion });
      total += 0.01;
      emit({
        type: 'result',
        subtype: isError ? 'error_during_execution' : 'success',
        is_error: isError,
        duration_ms: 5,
        num_turns: 1,
        total_cost_usd: total,
        modelUsage: { m: { inputTokens: 10, outputTokens: 5, cacheReadInputTokens: 0, cacheCreationInputTokens: 0 } },
        queued_turn_count: 0,
        errors: isError ? ['it failed'] : [],
      });
    }
    record.closed = true;
    notify?.();
  })();

  const gen = (async function* () {
    for (;;) {
      if (out.length) {
        yield out.shift();
        continue;
      }
      if (record.closed) return;
      await new Promise((r) => (notify = r));
      notify = null;
    }
  })();
  return Object.assign(gen, {
    record,
    async interrupt() {
      record.interrupted++;
      // An interrupt can arrive before the message it stops has been read.
      if (wakeInterrupt) wakeInterrupt();
      else record.interruptPending = true;
    },
    async setPermissionMode(m) {
      record.mode = m;
    },
    async supportedCommands() {
      record.asked = (record.asked || 0) + 1;
      return record.commands || COMMANDS;
    },
    async setModel(m) {
      record.model = m;
    },
    close() {
      record.closed = true;
      notify?.();
    },
  });
}
