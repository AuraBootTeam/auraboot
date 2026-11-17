import { getFieldDependencies } from '~/framework/meta/rendering/fieldDependencies';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

/** Keep controlled form state and declared linkage values in the same transaction. */
export function applyFormFieldChange(
  runtime: SchemaRuntime | null,
  fieldCode: string,
  value: unknown,
): Record<string, unknown> {
  if (!runtime) return { [fieldCode]: value };
  const manager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();
  const before = { ...(manager.getContext(scopeId).form || {}) };
  manager.updateField(scopeId, fieldCode, value);
  // A declared parent scopes a choice even when no explicit linkage rule exists.
  // Reset dependent values in form state, not only their visible option labels.
  if (!Object.is(before[fieldCode], value)) {
    const fields = (runtime.getSchema().blocks || []).flatMap((block) => block.fields || []);
    const changed = new Set([fieldCode]);
    const pending = [fieldCode];
    while (pending.length) {
      const parent = pending.shift()!;
      for (const field of fields) {
        if (changed.has(field.field)) continue;
        const dependencies = getFieldDependencies(field).map((path) => path.replace(/^form\./, ''));
        if (!dependencies.includes(parent)) continue;
        changed.add(field.field);
        pending.push(field.field);
        manager.updateField(scopeId, field.field, Array.isArray(before[field.field]) ? [] : null);
      }
    }
  }
  runtime.triggerFieldLinkage(fieldCode, 'change');
  const after = manager.getContext(scopeId).form || {};
  return Object.fromEntries(
    Object.entries(after).filter(
      ([key, next]) => key !== 'pid' && (key === fieldCode || !Object.is(before[key], next)),
    ),
  );
}
