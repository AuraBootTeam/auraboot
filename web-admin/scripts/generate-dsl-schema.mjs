#!/usr/bin/env node
/**
 * Generate JSON Schema from TypeScript DSL types.
 *
 * Usage:  node scripts/generate-dsl-schema.mjs          (from web-admin/)
 *    or:  pnpm generate:dsl-schema                    (from web-admin/)
 *
 * Reads:  app/meta/schemas/dsl-schema-types.ts
 * Writes: ../plugins/schemas/dsl-schema.generated.json
 */
import { createGenerator } from 'ts-json-schema-generator';
import { writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ADMIN = resolve(__dirname, '..');
const ROOT = resolve(WEB_ADMIN, '..');

const config = {
  path: resolve(WEB_ADMIN, 'app/meta/schemas/dsl-schema-types.ts'),
  tsconfig: resolve(WEB_ADMIN, 'tsconfig.json'),
  type: 'DslSchema',
  additionalProperties: false,
  skipTypeCheck: true,
  topRef: true,
};

console.log('Generating DSL JSON Schema from dsl-schema-types.ts...');

const generator = createGenerator(config);
const schema = generator.createSchema(config.type);

// Post-process: merge Strict* definitions into canonical names.
// The generator emits both BlockConfig (from transitive imports via DetailTabConfig)
// and StrictBlockConfig (the strict version we maintain). We keep the Strict version
// as the canonical definition because it has all declared properties.

// Step 1: Remove the original (incomplete) definitions before rename to avoid
// JSON duplicate-key collisions where the wrong definition wins.
if (schema.definitions) {
  if (schema.definitions.StrictBlockConfig && schema.definitions.BlockConfig) {
    delete schema.definitions.BlockConfig;
  }
  if (schema.definitions.StrictAreaConfig && schema.definitions.AreaConfig) {
    delete schema.definitions.AreaConfig;
  }
}

// Step 2: Rename Strict* → canonical names in serialized JSON
const raw = JSON.stringify(schema, null, 2);

let processed = raw
  .replaceAll('"StrictBlockConfig"', '"BlockConfig"')
  .replaceAll('#/definitions/StrictBlockConfig', '#/definitions/BlockConfig')
  .replaceAll('"StrictAreaConfig"', '"AreaConfig"')
  .replaceAll('#/definitions/StrictAreaConfig', '#/definitions/AreaConfig');

const output = JSON.parse(processed);

// Post-process: enforce additionalProperties: false on object definitions
// that don't already have it set (generator sometimes misses nested types).
// Skip types with index signatures (LocalizedText) and non-object types (unions/enums).
const ALLOW_ADDITIONAL = new Set(['LocalizedText']);

for (const [name, def] of Object.entries(output.definitions || {})) {
  if (ALLOW_ADDITIONAL.has(name)) continue;
  if (def.type === 'object' && def.additionalProperties === undefined) {
    def.additionalProperties = false;
  }
}

// Post-process: extract @semantic tags from descriptions → x- custom keywords
function extractSemanticAnnotations(node) {
  if (!node || typeof node !== 'object') return;

  if (typeof node.description === 'string' && node.description.includes('[semantic ')) {
    // Extract all [semantic key:value] annotations
    const regex = /\[semantic\s+(\w+):(.+?)\]/g;
    for (const match of node.description.matchAll(regex)) {
      const [, key, value] = match;
      const trimmed = value.trim();
      node[`x-${key}`] = trimmed.includes(',')
        ? trimmed.split(',').map(v => v.trim())
        : trimmed;
    }
    // Remove annotation markers from description
    const cleaned = node.description.replace(regex, '').trim();
    if (cleaned) {
      node.description = cleaned;
    } else {
      delete node.description;
    }
  }

  // Recurse into sub-schemas
  if (node.properties) {
    for (const prop of Object.values(node.properties)) {
      extractSemanticAnnotations(prop);
    }
  }
  if (node.items) extractSemanticAnnotations(node.items);
  if (node.definitions) {
    for (const def of Object.values(node.definitions)) {
      extractSemanticAnnotations(def);
    }
  }
  if (node.anyOf) node.anyOf.forEach(extractSemanticAnnotations);
  if (node.oneOf) node.oneOf.forEach(extractSemanticAnnotations);
  if (node.allOf) node.allOf.forEach(extractSemanticAnnotations);
}

extractSemanticAnnotations(output);

/**
 * Add pageType discriminator to DslSchema.
 * Uses if/then/allOf (JSON Schema draft-07) to attach x-recommended-block-types
 * metadata per page kind. AJV ignores x- keywords; the CLI semantic validator
 * reads them to emit warnings when a page lacks expected block types.
 */
function addPageTypeDiscriminator(schema) {
  const dslDef = schema.definitions?.DslSchema;
  if (!dslDef) return;

  // Preserve any existing allOf entries
  const existing = dslDef.allOf || [];

  dslDef.allOf = [
    ...existing,
    {
      if: { properties: { kind: { const: 'List' } } },
      then: {
        properties: {
          areas: {
            description: 'List pages should contain at least one table block',
            'x-recommended-block-types': ['table', 'filters', 'toolbar', 'tabs'],
          },
        },
      },
    },
    {
      if: { properties: { kind: { const: 'Form' } } },
      then: {
        properties: {
          areas: {
            description: 'Form pages should contain form-section and form-buttons blocks',
            'x-recommended-block-types': ['form', 'form-section', 'form-buttons', 'form-wizard'],
          },
        },
      },
    },
    {
      if: { properties: { kind: { const: 'Detail' } } },
      then: {
        properties: {
          areas: {
            description: 'Detail pages typically contain tabs with sub-tables',
            'x-recommended-block-types': ['tabs', 'sub-table', 'form-section', 'description', 'approval-comments'],
          },
        },
      },
    },
    {
      if: { properties: { kind: { const: 'Dashboard' } } },
      then: {
        properties: {
          areas: {
            description: 'Dashboard pages should contain chart and stat-card blocks',
            'x-recommended-block-types': ['chart', 'stat-card', 'table', 'description'],
          },
        },
      },
    },
  ];
}

addPageTypeDiscriminator(output);

// Add metadata
output.$comment =
  'Auto-generated from web-admin/app/meta/schemas/dsl-schema-types.ts — DO NOT EDIT MANUALLY. Run: cd web-admin && pnpm generate:dsl-schema';

const outPath = resolve(ROOT, 'plugins/schemas/dsl-schema.generated.json');
writeFileSync(outPath, JSON.stringify(output, null, 2) + '\n');

// Print stats
const defCount = Object.keys(output.definitions || {}).length;
console.log(`Done — wrote ${outPath}`);
console.log(`   ${defCount} type definitions exported`);
