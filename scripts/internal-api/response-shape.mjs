import { createRequire } from 'node:module';
import { resolvePointer, validateDocument, requireCondition } from './contract.mjs';

const require = createRequire(new URL('./toolchain/package.json', import.meta.url));
const Ajv = require('@redocly/ajv/dist/2020.js').default;

/** Validate JSON response structure. Formats and business invariants require separate assertions. */
export function createResponseShapeValidator(document) {
  validateDocument(document);
  requireCondition(/^3\.1\./.test(document.openapi), 'Response shape validation requires OpenAPI 3.1 JSON Schema semantics');
  const ajv = new Ajv({ strict: false, allErrors: true, validateFormats: false });
  const compiled = new Map();
  const dereference = value => value?.$ref ? resolvePointer(document, value.$ref) : value;
  return ({ method, path, status, contentType, payload }) => {
    const operation = document.paths[path]?.[method];
    requireCondition(operation, `Undeclared response operation: ${method} ${path}`);
    requireCondition(Number.isInteger(status) && status >= 100 && status <= 599, 'Invalid HTTP response status');
    const responses = operation.responses;
    const response = dereference(responses[String(status)] ?? responses[`${Math.floor(status / 100)}XX`] ?? responses.default);
    requireCondition(response, `Undeclared response status: ${method} ${path} ${status}`);
    const mediaType = contentType?.split(';')[0].trim().toLowerCase();
    requireCondition(mediaType === 'application/json' || mediaType?.endsWith('+json'), `Expected a JSON response, received ${mediaType}`);
    const media = response.content?.[mediaType] ?? response.content?.['application/*'] ?? response.content?.['*/*'];
    requireCondition(media?.schema, `Undeclared response media/schema: ${method} ${path} ${status} ${mediaType}`);
    const key = JSON.stringify(media.schema);
    if (!compiled.has(key)) compiled.set(key, ajv.compile({ ...media.schema, components: document.components }));
    const validate = compiled.get(key);
    const valid = validate(payload);
    return { valid, errors: structuredClone(validate.errors ?? []), claim: 'json-response-structure-only', formatValidation: 'not-performed' };
  };
}
