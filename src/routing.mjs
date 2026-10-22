// @ts-check
// Subagent routing: which model each kind of delegated work runs on.
//
// Claude Code picks a subagent's model in this order: CLAUDE_CODE_SUBAGENT_MODEL, then the
// `model` the main agent passes when it starts one, then the `model` of the agent's own
// definition, then the session's model. Left alone, every Explore and general-purpose
// subagent inherits the session's model, so a grep runs on Opus.
//
// Bench offers every project session three tiers as SDK agent definitions - nothing is
// written into the project or its worktree - each with a model taken from the person's
// default, over which a project and then a task may set their own. The `bench:routing`
// skill in the pack teaches the main agent how to choose between them.

/** @typedef {import('./contract.mjs').Routing} Routing */
/** @typedef {import('./contract.mjs').RoutingResolved} RoutingResolved */
/** @typedef {import('@anthropic-ai/claude-agent-sdk').AgentDefinition} AgentDefinition */

/** @type {Routing} */
export const DEFAULT_ROUTING = { enabled: true, explorer: 'haiku', implementer: 'sonnet', architect: 'inherit', all: null };

export const TIERS = /** @type {const} */ (['explorer', 'implementer', 'architect']);
const FIELDS = /** @type {const} */ (['enabled', 'explorer', 'implementer', 'architect', 'all']);

/** @param {string} msg */
const fail = (msg) => Object.assign(new Error(msg), { status: 400 });

/**
 * A model as Claude Code takes it: an alias or a model id, one word.
 * @param {unknown} v @param {string} what
 */
function modelOf(v, what) {
  const m = String(v).trim();
  if (!m || m.length > 120 || /\s/.test(m)) throw fail(`${what}: name a model - haiku, sonnet, opus, inherit, or a model id`);
  return m;
}

/**
 * Checks a routing patch. `override` allows null, which hands a field back to the level
 * above; the default has no level above it, so there it resets the field.
 * @param {any} input @param {{override?:boolean}} [o]
 * @returns {Partial<Routing>}
 */
export function routingPatch(input, o = {}) {
  if (!input || typeof input !== 'object') throw fail('Routing is an object');
  /** @type {Record<string, any>} */
  const out = {};
  for (const k of FIELDS) {
    if (!(k in input)) continue;
    const v = input[k];
    if (v === null || v === '') {
      out[k] = o.override ? null : DEFAULT_ROUTING[k];
      continue;
    }
    if (k === 'enabled') out[k] = !!v;
    else out[k] = modelOf(v, k);
  }
  return out;
}

/** Only the fields an override actually sets. @param {Partial<Routing>|null|undefined} o */
const setFields = (o) => Object.fromEntries(Object.entries(o || {}).filter(([, v]) => v !== null && v !== undefined));

/**
 * The routing a session runs with: the default, then its project's, then its task's.
 * @param {Partial<Routing>|null} base the person's default, as stored
 * @param {Partial<Routing>|null} [project] @param {Partial<Routing>|null} [task]
 * @returns {RoutingResolved}
 */
export function resolveRouting(base, project, task) {
  /** @type {RoutingResolved['from']} */
  const from = {};
  /** @type {any} */
  const r = { ...DEFAULT_ROUTING, ...setFields(base) };
  for (const k of FIELDS) from[k] = 'default';
  for (const [level, o] of /** @type {const} */ ([['project', project], ['task', task]])) {
    for (const [k, v] of Object.entries(setFields(o))) {
      r[k] = v;
      from[/** @type {keyof Routing} */ (k)] = level;
    }
  }
  return { ...r, from };
}

/** What a subagent is told about its brief, whatever its tier. */
const BRIEFED = 'You were started by another agent with a short brief. Do what it asks, stay inside it, and report back concisely: what you found or changed, with file paths and line numbers, and anything you could not do. Your report is all the other agent sees of your work.';

/**
 * The three tiers as Agent SDK definitions. Each description says when to use it - that is
 * what the main agent reads when it routes - and each model is the routing's.
 * @param {Routing} r @returns {Record<string, AgentDefinition>}
 */
export function subagents(r) {
  return {
    explorer: {
      description:
        'Cheap and fast, read-only. Use for lookups whose answer is a fact, not a judgement: finding where something is defined or used, reading code to say what it does, grep-style searches across the repo, listing files, reading git history, and summarising docs, logs or long command output. Brief it with exactly what to find and what shape of answer to return. Do not use it to decide on a design or to edit anything.',
      prompt: `${BRIEFED}\n\nYou only read: search, read files, run read-only commands (git log, ls, grep, test listings). Never edit, write or delete files, and never run a command that changes anything. Answer with the facts asked for and where you found them; quote only the lines that matter rather than whole files.`,
      disallowedTools: ['Edit', 'Write', 'NotebookEdit'],
      model: r.explorer,
    },
    implementer: {
      description:
        'Mid-tier, can edit. Use for well-specified work where the what and the where are already decided: an edit described precisely, writing or extending tests for code that exists, a mechanical refactor or rename, applying the same change across files, and running the check or tests and fixing what they report. Brief it with the files, the change, and how to know it is done. If the work needs a design decision first, use architect instead.',
      prompt: `${BRIEFED}\n\nMake the change the brief describes, matching the surrounding code's style. Run the relevant tests or check before you report, and say what they printed. If the brief is ambiguous or the change turns out to need a design decision, stop and say so rather than guess.`,
      model: r.implementer,
    },
    architect: {
      description:
        'Most capable and most expensive. Use only when the work needs judgement: a problem whose cause is unclear, hard debugging after a cheaper attempt failed, designing an approach, or a change that cuts across many parts of the system. Brief it with the goal, what is known and what was tried. Not for lookups or for edits that are already specified.',
      prompt: `${BRIEFED}\n\nThink the problem through before acting: establish what is actually happening from the code and from evidence, not assumption. When you propose or make a change, say why it is right and what else it affects.`,
      model: r.architect,
    },
  };
}

/** What the main agent is told at the start, so it knows the tiers are there and how to pick. */
export const ROUTING_NOTE = `# Subagents

Bench gives you three subagents, cheapest first: \`explorer\` (read-only lookups), \`implementer\` (well-specified edits, tests, running checks) and \`architect\` (unclear problems, design, hard debugging). Before you delegate, judge how hard the piece of work is and use the cheapest tier that will do it reliably; prefer \`explorer\` over the built-in Explore and general-purpose agents for lookups. Escalate a tier when one fails, and give each a short, focused brief rather than the whole conversation. The \`bench:routing\` skill has the full guidance.`;
