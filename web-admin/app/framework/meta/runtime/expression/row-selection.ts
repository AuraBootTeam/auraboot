/** Bounded declarative selection against current read facts, before payload projection. */
import { boundedRecordRows, readOwnPath } from './row-projection';
export interface RowSelectionRule {
  identityField: string;
  selectedField: string;
  positiveField: string;
}
export interface CardRowSelection extends RowSelectionRule {
  defaultSelected?: boolean;
  label?: unknown;
  emptyText?: unknown;
}
const RESERVED = ['__proto__', 'prototype', 'constructor'];
export function validateRowSelection(rule: RowSelectionRule): void {
  if (
    !rule ||
    typeof rule !== 'object' ||
    Array.isArray(rule) ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(rule)) ||
    Object.keys(rule).length !== 3 ||
    Object.keys(rule).some(
      (key) => !['identityField', 'selectedField', 'positiveField'].includes(key),
    )
  )
    throw new Error('Invalid row selection rule');
  const values = ['identityField', 'selectedField', 'positiveField'].map((key) =>
    readOwnPath(rule, key),
  );
  if (
    values.some(
      (value) =>
        typeof value !== 'string' ||
        !/^[A-Za-z_][A-Za-z0-9_]*$/.test(value) ||
        RESERVED.includes(value),
    ) ||
    new Set(values).size !== 3
  )
    throw new Error('Invalid row selection fields');
}
export function positiveRowValue(value: unknown): boolean {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value < 0)
      throw new Error('Expected exact nonnegative selection metric');
    return value > 0;
  }
  if (typeof value !== 'string' || value.length > 256 || !/^\d+(?:\.\d+)?$/.test(value))
    throw new Error('Expected exact nonnegative selection metric');
  return /[1-9]/.test(value);
}
export function indexSelectionRows(
  rows: unknown,
  rule: RowSelectionRule,
): Map<string, Record<string, unknown>> {
  validateRowSelection(rule);
  const result = new Map<string, Record<string, unknown>>();
  for (const row of boundedRecordRows(rows)) {
    const identity = readOwnPath(row, rule.identityField);
    if (
      typeof identity !== 'string' ||
      !identity.trim() ||
      identity.length > 256 ||
      result.has(identity)
    )
      throw new Error('Invalid or duplicate row selection identity');
    positiveRowValue(readOwnPath(row, rule.positiveField));
    result.set(identity, row);
  }
  return result;
}
export function initializeSelectedRows(
  rows: unknown,
  config: CardRowSelection,
): Record<string, unknown>[] {
  if (
    !config ||
    typeof config !== 'object' ||
    Object.keys(config).some(
      (key) =>
        ![
          'identityField',
          'selectedField',
          'positiveField',
          'defaultSelected',
          'label',
          'emptyText',
        ].includes(key),
    )
  )
    throw new Error('Invalid card row selection');
  const rule = {
    identityField: readOwnPath(config, 'identityField'),
    selectedField: readOwnPath(config, 'selectedField'),
    positiveField: readOwnPath(config, 'positiveField'),
  } as RowSelectionRule;
  const initial = readOwnPath(config, 'defaultSelected');
  if (initial !== undefined && typeof initial !== 'boolean')
    throw new Error('Invalid default row selection');
  return [...indexSelectionRows(rows, rule).values()].map((row) => {
    const descriptors = Object.getOwnPropertyDescriptors(row);
    if (Object.values(descriptors).some((value) => !('value' in value)))
      throw new Error('Accessor rows are forbidden');
    return {
      ...row,
      [rule.selectedField]:
        initial === true && positiveRowValue(readOwnPath(row, rule.positiveField)),
    };
  });
}
export function selectRows(
  rows: unknown,
  rule: RowSelectionRule,
  currentRows: unknown,
): Record<string, unknown>[] {
  const selected = indexSelectionRows(rows, rule);
  const current = indexSelectionRows(currentRows, rule);
  const result: Record<string, unknown>[] = [];
  for (const [identity, row] of selected) {
    const chosen = readOwnPath(row, rule.selectedField);
    if (typeof chosen !== 'boolean') throw new Error('Expected explicit boolean row selection');
    const source = current.get(identity);
    if (!source) throw new Error('Selected form identity is not in the current source');
    if (!chosen) continue;
    const fact = readOwnPath(source, rule.positiveField);
    if (!positiveRowValue(fact) || readOwnPath(row, rule.positiveField) !== fact)
      throw new Error('Selected row availability changed; review current facts');
    result.push(row);
  }
  return result;
}
