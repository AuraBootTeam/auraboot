#!/usr/bin/env node
/**
 * Audit all plugin pages.json files against the generated DSL JSON Schema.
 *
 * Reports additionalProperties violations — properties used in plugin JSON
 * that are not declared in dsl-schema-types.ts.
 *
 * Usage:  node scripts/audit-dsl-violations.mjs          (from web-admin/)
 *         node web-admin/scripts/audit-dsl-violations.mjs (from repo root)
 */
import Ajv from 'ajv';
import { readFileSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { globSync } from 'glob';

const __dirname = dirname(fileURLToPath(import.meta.url));
// Support running from web-admin/ or repo root
const ROOT = resolve(__dirname, '..', '..');

const schemaPath = resolve(ROOT, 'plugins/schemas/dsl-schema.generated.json');
if (!existsSync(schemaPath)) {
  console.error('ERROR: dsl-schema.generated.json not found. Run: cd web-admin && pnpm generate:dsl-schema');
  process.exit(1);
}
const fullSchema = JSON.parse(readFileSync(schemaPath, 'utf-8'));

// AJV 6 (draft-07): Inline DslSchema definition at root + carry definitions
// This avoids AJV6's "$ref siblings ignored" issue at schema root.
const schemaForValidation = {
  ...fullSchema.definitions.DslSchema,
  definitions: fullSchema.definitions,
};

const ajv = new Ajv({ allErrors: true, strict: false });
const validate = ajv.compile(schemaForValidation);

const pagesFiles = globSync('plugins/*/config/pages.json', { cwd: ROOT });

let totalPages = 0;
let totalViolations = 0;
const violationsByPlugin = new Map();

for (const relPath of pagesFiles.sort()) {
  const absPath = resolve(ROOT, relPath);
  const pluginName = relPath.split('/')[1];
  let pages;
  try { pages = JSON.parse(readFileSync(absPath, 'utf-8')); } catch { continue; }
  if (!Array.isArray(pages)) continue;

  const pluginViolations = [];
  for (const page of pages) {
    totalPages++;
    const dsl = page.dslSchema || page.dsl_schema;
    if (!dsl) continue;
    const valid = validate(dsl);
    if (!valid && validate.errors) {
      for (const err of validate.errors) {
        if (err.keyword === 'additionalProperties') {
          pluginViolations.push({
            pageKey: page.pageKey || page.page_key || '?',
            path: err.dataPath || err.instancePath || '/',
            property: err.params?.additionalProperty,
          });
          totalViolations++;
        }
      }
    }
  }
  if (pluginViolations.length > 0) violationsByPlugin.set(pluginName, pluginViolations);
}

console.log(`\n${'='.repeat(60)}`);
console.log(`DSL Schema Audit Report`);
console.log(`${'='.repeat(60)}`);
console.log(`Total pages scanned: ${totalPages}`);
console.log(`Total additionalProperties violations: ${totalViolations}`);
console.log(`Plugins with violations: ${violationsByPlugin.size}\n`);

// Deduplicated summary
const uniqueViolations = new Map();
for (const [, violations] of violationsByPlugin) {
  for (const v of violations) {
    // Extract the type context from path (e.g., ".fields[0]" -> FieldConfig)
    const pathKey = v.property + ' @ ' + inferType(v.path);
    uniqueViolations.set(pathKey, (uniqueViolations.get(pathKey) || 0) + 1);
  }
}
console.log('Unique violation types:');
for (const [key, count] of [...uniqueViolations.entries()].sort((a, b) => b - a)) {
  console.log(`  ${String(count).padStart(4)}x  ${key}`);
}
console.log();

// Per-plugin detail
for (const [plugin, violations] of violationsByPlugin) {
  console.log(`  ${plugin} (${violations.length} violations):`);
  for (const v of violations) {
    console.log(`    ${v.pageKey}  ${v.path} -> "${v.property}"`);
  }
  console.log();
}

if (totalViolations === 0) {
  console.log('All plugins pass schema validation!\n');
} else {
  console.log(`${totalViolations} violations found.`);
  console.log('Fix: add property to dsl-schema-types.ts + regenerate, OR fix plugin JSON\n');
}

process.exit(totalViolations > 0 ? 1 : 0);

/**
 * Infer the type context from a dataPath.
 * e.g., ".areas['main'].blocks[0].fields[2]" -> "FieldConfig"
 */
function inferType(path) {
  if (!path || path === '/') return 'DslSchema';
  if (/\.fields\[\d+\]$/.test(path)) return 'FieldConfig';
  if (/\.columns\[\d+\]$/.test(path)) return 'ColumnConfig';
  if (/\.buttons\[\d+\]$/.test(path)) return 'ButtonConfig';
  if (/\.rowActions\[\d+\]$/.test(path)) return 'ButtonConfig(rowAction)';
  if (/\.tabs\[\d+\]$/.test(path)) return 'ListTabConfig';
  if (/\.blocks\[\d+\]$/.test(path)) return 'BlockConfig';
  if (/\.subTable$/.test(path)) return 'SubTableConfig';
  if (/\.resolveVia$/.test(path)) return 'ResolveViaConfig';
  if (/\.filterExpression\[\d+\]$/.test(path)) return 'TabFilterExpression';
  if (/\.flow\[\d+\]$/.test(path)) return 'FlowStep';
  return path;
}
