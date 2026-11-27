import type { CardDraftRows } from '../../schemas/types';

/** Fresh rows own facts and membership; only explicit editable values can survive a refresh. */
export function reconcileDraftRows(
  fresh: Record<string, unknown>[],
  previous: Record<string, unknown>[],
  rule: CardDraftRows,
  preserve: (row: Record<string, unknown>) => boolean = () => true,
): Record<string, unknown>[] {
  const validField = (field: string) => typeof field === 'string'
    && /^[A-Za-z_][A-Za-z0-9_]*$/.test(field)
    && !['__proto__', 'constructor', 'prototype'].includes(field);
  if (!Array.isArray(rule.identityFields) || !rule.identityFields.length
      || !Array.isArray(rule.preserveFields) || !rule.preserveFields.length
      || ![...rule.identityFields, ...rule.preserveFields].every(validField)
      || new Set([...rule.identityFields, ...rule.preserveFields]).size !== rule.identityFields.length + rule.preserveFields.length)
    throw new Error('Draft row reconciliation requires distinct identity and editable fields');
  const index = (rows: Record<string, unknown>[]) => {
    const result = new Map<string, Record<string, unknown>>();
    for (const row of rows) {
      if (!row || typeof row !== 'object' || Array.isArray(row)) throw new Error('Draft row must be a record');
      const identity = rule.identityFields.map(field => {
        const value = Object.hasOwn(row, field) ? row[field] : undefined;
        if (!(typeof value === 'string' && value.trim()) && !(typeof value === 'number' && Number.isFinite(value)))
          throw new Error('Draft row identity is missing');
        return value;
      });
      const key = JSON.stringify(identity);
      if (result.has(key)) throw new Error('Duplicate draft row identity');
      result.set(key, row);
    }
    return result;
  };
  const old = index(previous);
  return [...index(fresh)].map(([key, row]) => {
    const prior = old.get(key), merged = { ...row };
    if (prior && preserve(row))
      for (const field of rule.preserveFields)
        if (Object.hasOwn(prior, field)) merged[field] = prior[field];
    return merged;
  });
}
