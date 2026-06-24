#!/usr/bin/env node
/**
 * Validate public-record pid-only naming in a live or captured OpenAPI document.
 *
 * This is intentionally scoped to record identity contract keys. Generic resource
 * ids such as email message id, inbox item id, or permission rule id are not part
 * of this migration and are not flagged here. Scoped public-record paths are
 * checked deeply, and component schemas are checked once so non-scoped endpoints
 * cannot publish legacy record identity fields through shared DTOs.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);

const PUBLIC_RECORD_PATH_RE =
  /\/api\/(?:dynamic|records|record-share|email\/messages|email\/sequences|inbox|mobile\/inbox|mobile\/search|im\/conversations|automations?|automation\/debug|agent\/runs|meta\/auto-fill|meta\/ai|meta\/change-logs|sod|permissions\/matrix)/i;

const FORBIDDEN_RECORD_KEYS = new Set([
  'recordId',
  'recordIds',
  'targetRecordId',
  'targetRecordIds',
  'boundRecordId',
  'triggerRecordId',
  'record_id',
  'target_record_id',
  'bound_record_id',
  'trigger_record_id',
  'tenant_id',
  'created_by',
  'updated_by',
]);

function usage() {
  console.log(`Usage: node scripts/check-public-record-openapi-contract.mjs (--input <openapi.json> | --url <api-docs-url>) [--json]

Options:
  --input <file>  Read a captured OpenAPI JSON document.
  --url <url>     Fetch a live OpenAPI JSON document.
  --json          Print machine-readable result only.
`);
}

function parseArgs(argv) {
  const options = { input: null, url: null, json: false, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') options.help = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--input') options.input = argv[++i];
    else if (arg.startsWith('--input=')) options.input = arg.slice('--input='.length);
    else if (arg === '--url') options.url = argv[++i];
    else if (arg.startsWith('--url=')) options.url = arg.slice('--url='.length);
  }
  return options;
}

async function readOpenApi(options) {
  if (options.input) {
    const raw = fs.readFileSync(path.resolve(options.input), 'utf8');
    return { source: options.input, document: JSON.parse(raw) };
  }
  if (options.url) {
    const response = await fetch(options.url);
    if (!response.ok) {
      throw new Error(`OpenAPI fetch failed: ${response.status} ${response.statusText}`);
    }
    return { source: options.url, document: await response.json() };
  }
  throw new Error('Either --input or --url is required.');
}

function methodEntries(pathItem) {
  return Object.entries(pathItem ?? {}).filter(([method]) =>
    ['get', 'post', 'put', 'delete', 'patch'].includes(method));
}

function resolveRef(document, ref) {
  const prefix = '#/components/schemas/';
  if (!ref.startsWith(prefix)) return null;
  return document.components?.schemas?.[ref.slice(prefix.length)] ?? null;
}

function scanSchema(document, schema, context, findings, seenRefs = new Set(), options = {}) {
  if (!schema || typeof schema !== 'object') return;

  if (schema.$ref) {
    if (seenRefs.has(schema.$ref)) return;
    seenRefs.add(schema.$ref);
    options.referencedRefs?.add(schema.$ref);
    scanSchema(document, resolveRef(document, schema.$ref), {
      ...context,
      jsonPath: `${context.jsonPath}.$ref(${schema.$ref})`,
    }, findings, seenRefs, options);
    return;
  }

  if (schema.properties && typeof schema.properties === 'object') {
    for (const [name, child] of Object.entries(schema.properties)) {
      if (FORBIDDEN_RECORD_KEYS.has(name)) {
        findings.push({
          kind: 'schema-property',
          path: context.path,
          method: context.method,
          field: name,
          jsonPath: `${context.jsonPath}.properties.${name}`,
        });
      }
      scanSchema(document, child, {
        ...context,
        jsonPath: `${context.jsonPath}.properties.${name}`,
      }, findings, seenRefs, options);
    }
  }

  if (schema.items) {
    scanSchema(document, schema.items, {
      ...context,
      jsonPath: `${context.jsonPath}.items`,
    }, findings, seenRefs, options);
  }

  if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
    scanSchema(document, schema.additionalProperties, {
      ...context,
      jsonPath: `${context.jsonPath}.additionalProperties`,
    }, findings, seenRefs, options);
  }

  for (const key of ['allOf', 'anyOf', 'oneOf']) {
    if (!Array.isArray(schema[key])) continue;
    schema[key].forEach((child, index) =>
      scanSchema(document, child, {
        ...context,
        jsonPath: `${context.jsonPath}.${key}[${index}]`,
      }, findings, seenRefs, options));
  }

  for (const [key, child] of Object.entries(schema)) {
    if (
      key === '$ref' ||
      key === 'properties' ||
      key === 'items' ||
      key === 'additionalProperties' ||
      key === 'allOf' ||
      key === 'anyOf' ||
      key === 'oneOf'
    ) {
      continue;
    }
    if (child && typeof child === 'object') {
      scanSchema(document, child, {
        ...context,
        jsonPath: `${context.jsonPath}.${key}`,
      }, findings, seenRefs, options);
    }
  }
}

export function auditOpenApi(document) {
  const findings = [];
  const scopedPaths = [];
  const referencedRefs = new Set();

  for (const [apiPath, pathItem] of Object.entries(document.paths ?? {})) {
    if (!PUBLIC_RECORD_PATH_RE.test(apiPath)) continue;
    scopedPaths.push(apiPath);

    for (const [method, operation] of methodEntries(pathItem)) {
      for (const parameter of operation.parameters ?? []) {
        if (FORBIDDEN_RECORD_KEYS.has(parameter.name)) {
          findings.push({
            kind: 'parameter',
            path: apiPath,
            method,
            field: parameter.name,
            jsonPath: 'parameters',
          });
        }
        scanSchema(document, parameter.schema, {
          path: apiPath,
          method,
          jsonPath: `parameters.${parameter.name}.schema`,
        }, findings, new Set(), { referencedRefs });
      }

      scanSchema(document, operation.requestBody, {
        path: apiPath,
        method,
        jsonPath: 'requestBody',
      }, findings, new Set(), { referencedRefs });
      scanSchema(document, operation.responses, {
        path: apiPath,
        method,
        jsonPath: 'responses',
      }, findings, new Set(), { referencedRefs });
    }
  }

  const componentSchemas = document.components?.schemas ?? {};
  for (const [schemaName, schema] of Object.entries(componentSchemas)) {
    const ref = `#/components/schemas/${schemaName}`;
    if (referencedRefs.has(ref)) continue;
    scanSchema(document, schema, {
      path: ref,
      method: 'component',
      jsonPath: `components.schemas.${schemaName}`,
    }, findings);
  }

  return {
    scopedPathCount: new Set(scopedPaths).size,
    componentSchemaCount: Object.keys(componentSchemas).length,
    findingCount: findings.length,
    findings,
  };
}

function printHuman(result, source) {
  console.log(`Public record OpenAPI contract: ${source}`);
  console.log(`Scoped paths: ${result.scopedPathCount}`);
  if (result.findings.length) {
    console.log('Findings:');
    for (const finding of result.findings) {
      console.log(`  ERROR ${finding.kind} ${finding.method.toUpperCase()} ${finding.path} ${finding.field} at ${finding.jsonPath}`);
    }
  }
  console.log(result.findings.length === 0 ? 'PASSED.' : 'FAILED.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === SCRIPT_PATH) {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    process.exit(0);
  }

  try {
    const { source, document } = await readOpenApi(options);
    const result = auditOpenApi(document);
    if (options.json) {
      console.log(JSON.stringify({ source, ...result }, null, 2));
    } else {
      printHuman(result, source);
    }
    process.exit(result.findings.length === 0 ? 0 : 1);
  } catch (error) {
    if (options.json) {
      console.log(JSON.stringify({ error: error.message }, null, 2));
    } else {
      console.error(error.message);
    }
    process.exit(2);
  }
}
