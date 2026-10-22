// Subagent routing: which model each tier of delegated work runs on. The person's default
// is set in Settings; a project and then a task may set their own, field by field, and a
// field left on "Default" follows the level above. See src/routing.mjs.
import { html, useState } from '../lib.mjs';
import { DEFAULT_ROUTING, inheritRouting } from '../format.mjs';
import { api, useStore, refresh } from '../api.mjs';
import { Select } from './form.mjs';
import { Dialog } from './ui.mjs';
import { showToast } from './toast.mjs';

export const TIERS = [
  { key: 'explorer', label: 'Explorer', hint: 'Read-only lookups: finding, reading, summarising.' },
  { key: 'implementer', label: 'Implementer', hint: 'Well-specified edits, tests, running the check.' },
  { key: 'architect', label: 'Architect', hint: 'Unclear problems, design, hard debugging.' },
];

export { DEFAULT_ROUTING, inheritRouting };

const ALIASES = [
  { value: 'haiku', label: 'Haiku' },
  { value: 'sonnet', label: 'Sonnet' },
  { value: 'opus', label: 'Opus' },
  { value: 'inherit', label: "Session's model" },
];

/** The Claude login's own models, by the id Claude Code takes. */
function claudeModels(providers) {
  const claude = providers.find((p) => p.kind === 'claude-code');
  return (claude?.models || []).filter((m) => m.enabled).map((m) => ({ value: m.name, label: m.display_name || m.name }));
}

/** What a model value reads as: an alias's name, a model's display name, or the id itself. */
export function routingModelLabel(value, providers) {
  if (!value) return 'Not set';
  return ALIASES.find((a) => a.value === value)?.label || claudeModels(providers).find((m) => m.value === value)?.label || value;
}

/** A model Claude Code reported, by the name a provider gives it when one does. */
export function modelName(id, providers) {
  for (const p of providers) {
    const m = p.models?.find((x) => x.name === id || x.id === id);
    if (m) return m.display_name || m.name;
  }
  return routingModelLabel(id, providers);
}

/** The models a tier can run on. A value set elsewhere that is not in the list is kept in it. */
export function routingModelOptions(providers, current) {
  const opts = [...ALIASES, ...claudeModels(providers)];
  if (current && !opts.some((o) => o.value === current)) opts.push({ value: current, label: current });
  return opts;
}

const DEFAULT = '__default';

/**
 * A project's or a task's own subagent models. `own` is what this level sets (null fields
 * follow `parent`); `parent` is the routing it would follow, so each field can name it.
 */
export function RoutingDialog({ title, intro, own, parent, onSave, onClose }) {
  const providers = useStore((s) => s.providers);
  const [r, setR] = useState(() => ({ enabled: null, explorer: null, implementer: null, architect: null, all: null, ...(own || {}) }));
  const [busy, setBusy] = useState(false);
  const pick = (k) => (v) => setR((x) => ({ ...x, [k]: v === DEFAULT ? null : k === 'enabled' ? v === 'on' : v === '' ? null : v }));
  const def = (label) => ({ value: DEFAULT, label: `Default (${label})` });
  const save = async () => {
    setBusy(true);
    try {
      await onSave(Object.values(r).every((v) => v === null) ? null : r);
      await refresh();
      onClose();
    } catch (e) {
      showToast(e.message || String(e), 'error');
      setBusy(false);
    }
  };
  const enabled = r.enabled ?? parent.enabled;
  return html`
    <${Dialog}
      title=${title}
      onClose=${onClose}
      actions=${html`<button class="btn ghost" type="button" onClick=${onClose}>Cancel</button><button class="btn primary" type="button" disabled=${busy} onClick=${save}>${busy ? 'Saving…' : 'Save'}</button>`}
    >
      <p class="muted">${intro} A session picks a change up when its process next starts.</p>
      <div class="routing-fields">
        <label class="field">
          <span class="field-label">Route subagents by tier</span>
          <${Select}
            ariaLabel="Route subagents by tier"
            value=${r.enabled === null ? DEFAULT : r.enabled ? 'on' : 'off'}
            options=${[def(parent.enabled ? 'On' : 'Off'), { value: 'on', label: 'On' }, { value: 'off', label: 'Off' }]}
            onChange=${pick('enabled')}
          />
        </label>
        ${TIERS.map(
          (t) => html`<label class="field" key=${t.key}>
            <span class="field-label">${t.label}</span>
            <${Select}
              ariaLabel=${t.label}
              disabled=${!enabled}
              value=${r[t.key] ?? DEFAULT}
              options=${[def(routingModelLabel(parent[t.key], providers)), ...routingModelOptions(providers, r[t.key])]}
              onChange=${pick(t.key)}
            />
            <span class="field-hint">${t.hint}</span>
          </label>`
        )}
        <label class="field">
          <span class="field-label">All subagents</span>
          <${Select}
            ariaLabel="All subagents"
            disabled=${!enabled}
            value=${r.all ?? DEFAULT}
            options=${[def(routingModelLabel(parent.all, providers)), ...routingModelOptions(providers, r.all)]}
            onChange=${pick('all')}
          />
          <span class="field-hint">Outranks every tier and Claude Code's built-in subagents. Claude login only.</span>
        </label>
      </div>
    <//>
  `;
}

/** The dialog for a project: it follows the person's default. */
export function ProjectRoutingDialog({ project, onClose }) {
  const base = useStore((s) => s.defaults?.routing);
  return html`<${RoutingDialog}
    title="Subagent models"
    intro=${html`What subagents run on in <strong>${project.name}</strong>. A field on Default follows Settings.`}
    own=${project.routing}
    parent=${inheritRouting(base)}
    onSave=${(routing) => api.setProjectRouting(project.id, routing)}
    onClose=${onClose}
  />`;
}

/** The dialog for a task: it follows its project, which follows the default. */
export function TaskRoutingDialog({ task, onClose }) {
  const base = useStore((s) => s.defaults?.routing);
  const project = useStore((s) => s.projects.find((p) => p.id === task.project_id));
  return html`<${RoutingDialog}
    title="Subagent models"
    intro="What subagents run on in this task. A field on Default follows the project, and the project follows Settings."
    own=${task.routing}
    parent=${inheritRouting(base, project?.routing)}
    onSave=${(routing) => api.setTaskRouting(task.id, routing)}
    onClose=${onClose}
  />`;
}
