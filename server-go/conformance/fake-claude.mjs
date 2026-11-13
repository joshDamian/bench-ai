#!/usr/bin/env node
// A stand-in for the Claude Code CLI that speaks its stream-json protocol, for testing
// and benchmarking both Bench servers without a model call. Point either server at it
// with BENCH_CLAUDE_BIN.
//
// What a message does depends on its text:
//   "ASK <tool>"     asks permission for <tool> (can_use_tool) and reports the answer
//   "MCP <tool>"     calls the bench MCP tool <tool> with {} and replies with its result
//   "SLOW <ms>"      waits before answering, streaming as it goes
//   "BIG <n>"        replies with n characters, in deltas
//   anything else    echoes it back as the reply
// Every turn ends with a result carrying usage, as the real CLI's does.
import readline from 'node:readline';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
const flag = (name) => args.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const sessionId = flag('resume') || crypto.randomUUID();
const model = flag('model') || 'claude-fake-1';
const out = (m) => process.stdout.write(`${JSON.stringify(m)}\n`);
let n = 0;
const pending = new Map();
let hooks = {};
let initialized = false;
let queue = Promise.resolve();
let turns = 0;
let cost = 0;

function request(req) {
  const id = `fake-${++n}`;
  out({ type: 'control_request', request_id: id, request: req });
  return new Promise((resolve) => pending.set(id, resolve));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function turn(text) {
  turns++;
  const msgId = `msg_${crypto.randomUUID().slice(0, 12)}`;
  if (turns === 1) out({ type: 'system', subtype: 'init', cwd: process.cwd(), session_id: sessionId, model, permissionMode: flag('permission-mode') || 'default', claude_code_version: '0.0.0-fake', tools: [], plugin_errors: [] });
  let reply = `Echo: ${text}`;
  const ask = /^ASK (\S+)/.exec(text);
  const mcp = /^MCP (\S+)/.exec(text);
  const slow = /^SLOW (\d+)/.exec(text);
  const big = /^BIG (\d+)/.exec(text);
  if (ask) {
    const tid = `toolu_${crypto.randomUUID().slice(0, 10)}`;
    out({ type: 'assistant', message: { id: msgId, model, role: 'assistant', content: [{ type: 'tool_use', id: tid, name: ask[1], input: { command: 'echo hi' } }] }, parent_tool_use_id: null, session_id: sessionId });
    const r = await request({ subtype: 'can_use_tool', tool_name: ask[1], input: { command: 'echo hi' }, tool_use_id: tid, permission_suggestions: [] });
    const allowed = r.response?.behavior === 'allow';
    out({ type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: tid, content: allowed ? 'hi' : `denied: ${r.response?.message || ''}`, is_error: !allowed }] }, parent_tool_use_id: null, session_id: sessionId });
    reply = allowed ? 'The tool ran.' : 'The tool was denied.';
  } else if (mcp) {
    const r = await request({ subtype: 'mcp_message', server_name: 'bench', message: { jsonrpc: '2.0', id: n + 1000, method: 'tools/call', params: { name: mcp[1], arguments: {} } } });
    const res = r.response?.mcp_response?.result;
    reply = (res?.content || []).map((c) => c.text).join('\n') || JSON.stringify(r.response);
  } else if (slow) {
    const steps = 10;
    for (let i = 0; i < steps; i++) {
      await sleep(Number(slow[1]) / steps);
      out({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: `tick ${i} ` } }, parent_tool_use_id: null, session_id: sessionId });
    }
    reply = 'Done waiting.';
  } else if (big) {
    reply = 'x'.repeat(Number(big[1]));
  }
  for (let i = 0; i < reply.length; i += 40) out({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: reply.slice(i, i + 40) } }, parent_tool_use_id: null, session_id: sessionId });
  out({ type: 'assistant', message: { id: msgId, model, role: 'assistant', content: [{ type: 'text', text: reply }] }, parent_tool_use_id: null, session_id: sessionId });
  for (const id of hooks.Stop || []) await request({ subtype: 'hook_callback', callback_id: id, input: { hook_event_name: 'Stop', session_id: sessionId } });
  cost += 0.001;
  out({ type: 'result', subtype: 'success', is_error: false, duration_ms: 5, num_turns: turns, result: reply, session_id: sessionId, total_cost_usd: cost,
    modelUsage: { [model]: { inputTokens: 10 * turns, outputTokens: 5 * turns, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, costUSD: cost } } });
  out({ type: 'prompt_suggestion', suggestion: 'Thanks' });
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  let m;
  try { m = JSON.parse(line); } catch { return; }
  if (m.type === 'control_response') {
    const r = pending.get(m.response.request_id);
    pending.delete(m.response.request_id);
    r?.(m.response);
    return;
  }
  if (m.type === 'control_request') {
    const req = m.request;
    if (req.subtype === 'initialize') {
      initialized = true;
      hooks = Object.fromEntries(Object.entries(req.hooks || {}).map(([k, v]) => [k, v.flatMap((x) => x.hookCallbackIds)]));
      out({ type: 'control_response', response: { subtype: 'success', request_id: m.request_id, response: { commands: [{ name: 'review', description: 'Review the change', argumentHint: '' }, { name: 'fake:hello', description: '(fake) Say hello', argumentHint: '<name>' }] } } });
    } else out({ type: 'control_response', response: { subtype: 'success', request_id: m.request_id, response: {} } });
    return;
  }
  if (m.type === 'user') {
    const content = m.message.content;
    const text = typeof content === 'string' ? content : content.filter((b) => b.type === 'text').map((b) => b.text).join('\n');
    queue = queue.then(() => turn(text.split('\n')[0]));
  }
});
rl.on('close', () => queue.then(() => process.exit(0)));
