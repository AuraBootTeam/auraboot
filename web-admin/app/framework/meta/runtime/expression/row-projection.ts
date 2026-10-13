/** Declarative, bounded projection of read rows into explicit command payloads. */
export type RowProjection = Record<string, string | readonly string[]>;
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
    const paths = typeof descriptor.value === 'string' ? [descriptor.value] : descriptor.value;
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
    return { field, paths: validatedPaths };
  });
  const identities = new Set<unknown>();
  return recordRows.map((row) => {
    if (!row || typeof row !== 'object' || Array.isArray(row))
      throw new Error('Invalid projected row');
    const output: Record<string, unknown> = {};
    for (const { field, paths } of mappings) {
      let value: unknown;
      for (const path of paths) {
        value = readOwnPath(row, path);
        if (value !== undefined) break;
      }
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
