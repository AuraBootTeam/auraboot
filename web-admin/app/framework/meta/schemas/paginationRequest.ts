import type { PaginationRequestConfig } from './types';

const unsafe = new Set(['__proto__', 'prototype', 'constructor']);
const identity = new Set([
  'targetRecordPid',
  'targetRecordPids',
  'clientRequestId',
  'expectedVersion',
]);
const object = (value: unknown): value is Record<string, any> =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value));

export function validatePaginationRequest(
  value: unknown,
): asserts value is PaginationRequestConfig {
  if (
    !object(value) ||
    Object.keys(value).some((key) => !['pageNumPath', 'pageSizePath'].includes(key))
  )
    throw new Error('Invalid paginationRequest');
  const paths = [value.pageNumPath, value.pageSizePath];
  for (const path of paths) {
    if (
      !Array.isArray(path) ||
      path.length === 0 ||
      path.length > 8 ||
      path.some(
        (key) => typeof key !== 'string' || !key || key.trim() !== key || unsafe.has(key),
      ) ||
      identity.has(path[0])
    )
      throw new Error('Invalid paginationRequest path');
  }
  const [page, size] = paths as string[][];
  const shorter = Math.min(page.length, size.length);
  if (page.slice(0, shorter).every((key, i) => key === size[i]))
    throw new Error('Overlapping paginationRequest paths');
}

/** Clone only the changed object paths; never mutate registered/evaluated input objects. */
export function buildPaginationRequestParams(
  params: unknown,
  extra: Record<string, any> | undefined,
  mapping: PaginationRequestConfig | undefined,
): Record<string, any> {
  if (!mapping) return { ...(object(params) ? params : {}), ...extra };
  validatePaginationRequest(mapping);
  if (!object(params)) throw new Error('paginationRequest requires object params');
  const result = { ...params, ...extra };
  for (const [name, path] of [
    ['pageNum', mapping.pageNumPath],
    ['pageSize', mapping.pageSizePath],
  ] as const) {
    // A nested declaration must not also send an ambiguous configured root value.
    if (!(path.length === 1 && path[0] === name) && Object.hasOwn(params, name))
      throw new Error(`Conflicting root pagination parameter: ${name}`);
    const supplied = Boolean(extra && Object.hasOwn(extra, name));
    const value = extra?.[name];
    if (supplied && (!Number.isSafeInteger(value) || value < 1)) throw new Error(`Invalid ${name}`);
    if (supplied) delete result[name];
    let source: Record<string, any> = params;
    let target = result;
    for (const key of path.slice(0, -1)) {
      if (!Object.hasOwn(source, key) || !object(source[key]))
        throw new Error('paginationRequest cannot traverse a missing, scalar or array value');
      source = source[key];
      target[key] = { ...target[key] };
      target = target[key];
    }
    const leaf = path[path.length - 1];
    if (!Object.hasOwn(source, leaf) || !Number.isSafeInteger(source[leaf]) || source[leaf] < 1)
      throw new Error(`paginationRequest requires an initial positive ${name}`);
    if (supplied) target[leaf] = value;
  }
  return result;
}
