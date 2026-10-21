/** Declarative, bounded projection of read rows into explicit command payloads. */
export type OmissionState = 'undefined' | 'null' | 'empty';
export interface RowProjectionDescriptor {
  paths: string | readonly string[];
  optional?: boolean;
  /** No omission fallback: optional fields must explicitly declare omitted states. */
  omitWhen?: readonly OmissionState[];
}
export type RowProjection = Record<string, string | readonly string[] | RowProjectionDescriptor>;
const FORBIDDEN = new Set(['__proto__', 'prototype', 'constructor']);

export function readOwnPath(value: unknown, path: string): unknown {
  if (typeof path !== 'string' || path.length > 256) throw new Error('Invalid row path');
  const parts = path.split('.');
  if (
    parts.length > 8 ||
    parts.some((part) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(part) || FORBIDDEN.has(part))
  ) {
    throw new Error('Invalid row path');
  }
  let current = value;
  for (const part of parts) {
    if (!current || typeof current !== 'object' || Array.isArray(current)) return undefined;
    const descriptor = Object.getOwnPropertyDescriptor(current, part);
    if (!descriptor) return undefined;
    if (!('value' in descriptor)) throw new Error('Accessor row paths are forbidden');
    current = descriptor.value;
  }
  return current;
}

export function boundedRecordRows(rows: unknown): Record<string, unknown>[] {
  if (!Array.isArray(rows) || rows.length > 1000) throw new Error('Expected at most 1000 rows');
  const result: Record<string, unknown>[] = [];
  for (let index = 0; index < rows.length; index++) {
    const descriptor = Object.getOwnPropertyDescriptor(rows, String(index));
    if (!descriptor || !('value' in descriptor))
      throw new Error('Sparse or accessor rows are forbidden');
    const row = descriptor.value;
    if (!row || typeof row !== 'object' || Array.isArray(row))
      throw new Error('Invalid projected row');
    result.push(row);
  }
  return result;
}

export function projectRows(
  rows: unknown,
  projection: RowProjection,
  identityField: string,
): Record<string, unknown>[] {
  const recordRows = boundedRecordRows(rows);
  if (
    !projection ||
    typeof projection !== 'object' ||
    Array.isArray(projection) ||
    ![Object.prototype, null].includes(Object.getPrototypeOf(projection))
  )
    throw new Error('Invalid row projection');
  const fields = Object.keys(projection);
  if (!fields.length || fields.length > 64 || !fields.includes(identityField))
    throw new Error('Required projected identity is missing');
  const mappings = fields.map((field) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(field) || FORBIDDEN.has(field))
      throw new Error('Invalid projected field');
    const descriptor = Object.getOwnPropertyDescriptor(projection, field)!;
    if (!('value' in descriptor)) throw new Error('Accessor projection is forbidden');
    let declared = descriptor.value;
    let omitWhen: readonly OmissionState[] = [];
    if (declared && typeof declared === 'object' && !Array.isArray(declared)) {
      if (![Object.prototype, null].includes(Object.getPrototypeOf(declared)))
        throw new Error('Invalid projection descriptor');
      const keys = Object.keys(declared);
      if (!keys.includes('paths') || keys.some((key) => !['paths', 'optional', 'omitWhen'].includes(key)))
        throw new Error('Invalid projection descriptor');
      for (const key of keys) {
        if (!('value' in Object.getOwnPropertyDescriptor(declared, key)!))
          throw new Error('Accessor projection descriptor is forbidden');
      }
      const optional = readOwnPath(declared, 'optional');
      const omissions = readOwnPath(declared, 'omitWhen');
      if (optional !== undefined && typeof optional !== 'boolean')
        throw new Error('Invalid optional projection');
      if (optional === true) {
        if (field === identityField) throw new Error('Projected identity cannot be optional');
        if (!Array.isArray(omissions) || !omissions.length || omissions.length > 3)
          throw new Error('Explicit omission states are required');
        const states: OmissionState[] = [];
        for (let index = 0; index < omissions.length; index++) {
          const item = Object.getOwnPropertyDescriptor(omissions, String(index));
          if (!item || !('value' in item) || !['undefined', 'null', 'empty'].includes(item.value)
            || states.includes(item.value)) throw new Error('Invalid omission states');
          states.push(item.value);
        }
        omitWhen = states;
      } else if (omissions !== undefined) throw new Error('Omission requires an optional field');
      declared = readOwnPath(declared, 'paths');
    }
    const paths = typeof declared === 'string' ? [declared] : declared;
    if (!Array.isArray(paths) || !paths.length || paths.length > 4)
      throw new Error('Invalid projected paths');
    // Validate every declared fallback even when an earlier path has a value.
    const validatedPaths: string[] = [];
    for (let index = 0; index < paths.length; index++) {
      const path = Object.getOwnPropertyDescriptor(paths, String(index));
      if (!path || !('value' in path)) throw new Error('Invalid projected paths');
      readOwnPath({}, path.value);
      validatedPaths.push(path.value);
    }
    return { field, paths: validatedPaths, omitWhen };
  });
  const identities = new Set<unknown>();
  return recordRows.map((row) => {
    if (!row || typeof row !== 'object' || Array.isArray(row))
      throw new Error('Invalid projected row');
    const output: Record<string, unknown> = {};
    for (const { field, paths, omitWhen } of mappings) {
      let value: unknown;
      for (const path of paths) {
        value = readOwnPath(row, path);
        if (value !== undefined) break;
      }
      const omitted = value === undefined ? 'undefined' : value === null ? 'null' : value === '' ? 'empty' : null;
      if (omitted && omitWhen.includes(omitted)) continue;
      if (value !== null && !['string', 'number', 'boolean'].includes(typeof value))
        throw new Error('Projected value must be scalar');
      if (typeof value === 'number' && !Number.isSafeInteger(value))
        throw new Error('Numeric projection must be a safe integer');
      output[field] = value;
    }
    const identity = output[identityField];
    if (typeof identity !== 'string' || !identity.trim() || identities.has(identity))
      throw new Error('Invalid or duplicate projected identity');
    identities.add(identity);
    return output;
  });
}
