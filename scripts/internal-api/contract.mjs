import { createHash } from 'node:crypto';

export const HTTP_METHODS = Object.freeze(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace']);
const METHODS = new Set(HTTP_METHODS);

export function requireCondition(value, message) {
  if (!value) throw new Error(message);
}

export function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, stableValue(value[key])]));
  }
  return value;
}

export function canonicalJson(value) {
  return JSON.stringify(stableValue(value), null, 2) + '\n';
}

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

export function operations(document) {
  const rows = [];
  for (const [path, item] of Object.entries(document.paths ?? {})) {
    requireCondition(path.startsWith('/'), `Invalid OpenAPI path: ${path}`);
    for (const [method, operation] of Object.entries(item)) {
      if (!METHODS.has(method)) continue;
      requireCondition(operation && typeof operation === 'object', `Invalid operation: ${method} ${path}`);
      rows.push({ method, path, operationId: operation.operationId, operation, pathItem: item });
    }
  }
  return rows.sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`, 'en'));
}

export function resolvePointer(document, reference) {
  requireCondition(reference.startsWith('#/'), `Only bundled local references are allowed: ${reference}`);
  let value = document;
  for (const token of reference.slice(2).split('/')) {
    const key = token.replaceAll('~1', '/').replaceAll('~0', '~');
    requireCondition(value && Object.hasOwn(value, key), `Unresolved reference: ${reference}`);
    value = value[key];
  }
  return value;
}

export function validateDocument(document) {
  requireCondition(/^3\.(0|1)\.\d+$/.test(document?.openapi), 'An OpenAPI 3.0/3.1 document is required');
  requireCondition(document.info?.title && document.info?.version, 'OpenAPI info title/version is required');
  const rows = operations(document);
  requireCondition(rows.length > 0, 'OpenAPI operation surface is empty');
  const ids = new Set();
  for (const row of rows) {
    requireCondition(typeof row.operationId === 'string' && row.operationId.length > 0,
      `Missing operationId: ${row.method} ${row.path}`);
    requireCondition(!ids.has(row.operationId), `Duplicate operationId: ${row.operationId}`);
    ids.add(row.operationId);
    requireCondition(row.operation.responses && Object.keys(row.operation.responses).length,
      `Missing responses: ${row.method} ${row.path}`);
    const parameters = [...(row.pathItem.parameters ?? []), ...(row.operation.parameters ?? [])]
      .map(p => p.$ref ? resolvePointer(document, p.$ref) : p);
    for (const match of row.path.matchAll(/\{([^}]+)\}/g)) {
      requireCondition(parameters.some(p => p.in === 'path' && p.name === match[1] && p.required === true),
        `Missing required path parameter ${match[1]}: ${row.method} ${row.path}`);
    }
    for (const p of parameters.filter(p => p.in === 'path')) {
      requireCondition(row.path.includes(`{${p.name}}`) && p.required === true,
        `Invalid path parameter ${p.name}: ${row.method} ${row.path}`);
    }
  }
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) resolvePointer(document, value.$ref);
    for (const child of Object.values(value)) visit(child);
  }
  visit(document);
  return rows;
}

/** Preserve contract semantics, array order and all schema/security constraints. */
export function normalizeDocument(document) {
  validateDocument(document);
  const normalized = structuredClone(document);
  normalized.servers = [{ url: '/' }];
  // Per-path/per-operation servers override root servers and also need a relative origin.
  for (const item of Object.values(normalized.paths)) {
    if (item.servers) item.servers = [{ url: '/' }];
    for (const method of HTTP_METHODS) {
      if (item[method]?.servers) item[method].servers = [{ url: '/' }];
    }
  }
  return stableValue(normalized);
}

export function assertSameContract(expected, actual) {
  const a = canonicalJson(normalizeDocument(expected));
  const b = canonicalJson(normalizeDocument(actual));
  requireCondition(a === b, `Internal API contract drift: expected ${sha256(a)}, actual ${sha256(b)}`);
  return sha256(a);
}

/** Select an explicit reviewed operation set; never silently omit a missing selector. */
export function selectOperations(document, selectors) {
  validateDocument(document);
  requireCondition(Array.isArray(selectors) && selectors.length > 0, 'Operation selectors are empty');
  const result = structuredClone(document);
  result.paths = {};
  const seen = new Set();
  for (const { method, path } of selectors) {
    const key = `${method} ${path}`;
    requireCondition(METHODS.has(method), `Invalid selector method: ${method}`);
    requireCondition(!seen.has(key), `Duplicate operation selector: ${key}`);
    requireCondition(document.paths[path]?.[method], `Selected operation is absent: ${key}`);
    seen.add(key);
    const item = result.paths[path] ??= {};
    for (const [name, value] of Object.entries(document.paths[path])) {
      if (!METHODS.has(name)) item[name] = structuredClone(value);
    }
    item[method] = structuredClone(document.paths[path][method]);
  }
  // Traverse references from the selected operations, retaining transitive and cyclic schemas.
  const required = new Set();
  function collect(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref?.startsWith('#/components/schemas/')) {
      const key = value.$ref.split('/').at(-1).replaceAll('~1', '/').replaceAll('~0', '~');
      if (!required.has(key)) {
        required.add(key);
        collect(resolvePointer(document, value.$ref));
      }
    } else if (value.$ref) {
      // Parameters, requestBodies and responses may themselves reference schemas.
      if (!required.has(value.$ref)) {
        required.add(value.$ref);
        collect(resolvePointer(document, value.$ref));
      }
    }
    for (const child of Object.values(value)) collect(child);
  }
  collect(result.paths);
  // Other component groups remain intact; collect their dependencies before pruning schemas.
  for (const [group, components] of Object.entries(result.components ?? {})) {
    if (group !== 'schemas') collect(components);
  }
  if (result.components?.schemas) result.components.schemas = Object.fromEntries(
    Object.entries(result.components.schemas).filter(([name]) => required.has(name)));
  return normalizeDocument(result);
}
