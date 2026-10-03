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
  runtime.triggerFieldLinkage(fieldCode, 'change');
  const after = manager.getContext(scopeId).form || {};
  return Object.fromEntries(
    Object.entries(after).filter(
      ([key, next]) => key === fieldCode || !Object.is(before[key], next),
    ),
  );
}
