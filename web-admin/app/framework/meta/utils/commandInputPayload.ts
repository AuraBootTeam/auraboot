/** Group prompt values without letting them replace a captured command context. */
export function mergeCommandInputPayload(
  payload: Record<string, unknown>,
  inputs: Record<string, unknown>,
  key?: unknown,
): Record<string, unknown> {
  if (key === undefined) return { ...payload, ...inputs };
  if (
    typeof key !== 'string' ||
    !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key) ||
    ['__proto__', 'prototype', 'constructor'].includes(key) ||
    Object.prototype.hasOwnProperty.call(payload, key)
  ) {
    throw new Error('Invalid or conflicting command input payload key');
  }
  return { ...payload, [key]: { ...inputs } };
}
