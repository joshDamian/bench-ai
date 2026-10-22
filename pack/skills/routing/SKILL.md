---
name: routing
description: "Apply when about to delegate work to a subagent, or when the person asks how subagents are chosen. Picks the cheapest of Bench's tiers - explorer, implementer, architect - that will do the work reliably, says when to escalate, and how to write a short, focused brief."
---

# Route work to the right subagent

Bench gives this session three subagents. They differ in model, so in cost and in what they can be trusted with. If none of `explorer`, `implementer` or `architect` is in your agent list, routing is off for this project: ignore this skill.

| Tier | Runs on (by default) | Use it for |
|---|---|---|
| `explorer` | Haiku | Facts: where is X defined or used, what does this file do, which tests cover Y, what did the last commits change, what does this log say. Read-only. |
| `implementer` | Sonnet | Decided work: an edit whose what and where are known, tests for code that exists, a rename or mechanical refactor, running the check and fixing what it reports. |
| `architect` | This session's model | Judgement: an unclear cause, debugging a cheaper tier could not crack, designing an approach, a change that cuts across the system. |

The person can change the models per project or per task; the tiers stay the same.

## 1. Judge the work before you delegate

Ask of the piece of work, not of the whole task:

- **Is the answer a fact or a judgement?** A fact - a location, a list, a summary - goes to `explorer`.
- **Is the change already decided?** If you could write the diff's description in two sentences, naming the files, it goes to `implementer`.
- **Would a wrong answer be expensive, or is the cause unknown?** That is `architect` - or yourself, when you already hold the context it needs.

Do it yourself, without a subagent, when it is a single quick lookup (one grep, one file) or when the context needed is already in this conversation and would take longer to brief than to use.

Prefer `explorer` to the built-in Explore and general-purpose agents for lookups: those run on this session's model.

## 2. Escalate on failure, not in advance

Start at the cheapest tier that is likely to succeed. Move up one tier when:

- the report is wrong, vague, or misses what was asked;
- an `implementer` says the brief needs a design decision, or its change fails the check twice;
- an `explorer` cannot find something you are sure exists.

When you escalate, pass on what the cheaper tier found and where it went wrong, so the next one does not repeat it. Do not escalate just because a task is important; escalate because the cheaper tier showed it cannot do this piece.

## 3. Write a short brief

A subagent sees only its brief. Give it what it needs and nothing else:

- **The goal**, in one or two sentences.
- **Where to look**: files, directories, symbols - the ones you know.
- **Constraints**: what not to touch, the style to match, the check to run.
- **What to return**: the shape of the answer - a list of paths with line numbers, a yes/no with evidence, a summary under N lines, "the diff and the check's output".

Do not paste the conversation, earlier tool output, or files it can read itself. A brief over a screen long is usually a sign the piece is not well enough defined to delegate - define it first, or give it to `architect`.

Run independent lookups in parallel: several `explorer` briefs at once are cheaper and faster than one broad one.

## 4. Check what comes back

Read the report before acting on it. Verify a claim that matters - open the file at the line it names - before you build on it. A subagent's "done" is a claim; the check is the evidence.
