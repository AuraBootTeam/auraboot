#!/usr/bin/env node
// Guard: new dynamic model codes must follow the four-layer naming standard.
//
//   <domain>_<object>_common
//   <domain>_<object>_<industry>_<business_name>
//   <domain>_<object>_<industry>_ext
//   <domain>_<object>_<industry>_link
//
// Existing (legacy) model codes are grandfathered via model-code-baseline.json so
// naming can improve incrementally without inventing aliases or hidden migrations.
// New model codes that are NOT in the baseline MUST conform, and MUST NOT use a bare
// industry-only prefix such as `pe_` that hides which common object they extend.
//
// Public contract: plugins/crm/README.md and the enterprise CRM system reference.
//
// Usage: node check-model-naming.mjs [auraboot-root]
// Exit 0 = pass, 1 = violations found.

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const scriptDir = dirname(fileURLToPath(import.meta.url)); // plugins/crm/scripts
const ROOT = resolve(process.argv[2] ?? join(scriptDir, '..', '..', '..')); // auraboot root

const MODEL_FILES = [
  'plugins/crm/config/models.json',
  'plugins/req/config/models.json',
  'plugins/pcba-crm/config/models.json',
  'plugins/bom-standardization/config/models.json',
  'plugins/sales/config/models.json',
  'plugins/pcba-sales/config/models.json',
  'plugins/pcba-solution/config/models.json',
  'plugins/pcba-industry/config/models.json',
  'plugins/inventory/config/models.json',
  'plugins/pcba-finance/config/models.json',
  'plugins/pcba-compliance/config/models.json',
  'plugins/procurement/config/models.json',
  'plugins/pcba-procurement/config/models.json',
  'plugins/crm-incentive/config/models.json',
  'aura-quote/plugin-aura/quote-core/config/models.json',
  'aura-quote/plugin-aura/quote-engine/config/models.json',
  'aura-quote/plugin-aura/pcba-quote/config/models.json',
];

const baselinePath = join(scriptDir, 'model-code-baseline.json');
const baseline = new Set(
  existsSync(baselinePath) ? JSON.parse(readFileSync(baselinePath, 'utf8')) : [],
);

// Conforming new-model regexes.
const COMMON = /^[a-z][a-z0-9]*(_[a-z0-9]+)*_common$/;
const EXT = /^[a-z][a-z0-9]*(_[a-z0-9]+)*_ext$/;
const LINK = /^[a-z][a-z0-9]*(_[a-z0-9]+)*_link$/;
// <domain>_<object>_<industry>_<business>: requires >= 4 underscore-separated tokens
// and a recognised industry token as the 3rd-from-... heuristic. We keep it permissive:
// an industry token must appear, followed by at least one business token.
const INDUSTRIES = ['pcba', 'saas', 'services', 'construction', 'auto', 'medical', 'retail'];
const INDUSTRY_BUSINESS = new RegExp(
  `^[a-z][a-z0-9]*(_[a-z0-9]+)*_(${INDUSTRIES.join('|')})_[a-z0-9]+(_[a-z0-9]+)*$`,
);

// Bare industry-only prefixes that hide the common object (Doc3 §2.2 / §8-P0).
const FORBIDDEN_NEW_PREFIX = /^pe_/;

function isConforming(code) {
  if (FORBIDDEN_NEW_PREFIX.test(code)) return false;
  return COMMON.test(code) || EXT.test(code) || LINK.test(code) || INDUSTRY_BUSINESS.test(code);
}

const violations = [];
let scanned = 0;
let newCount = 0;

for (const rel of MODEL_FILES) {
  const f = join(ROOT, rel);
  if (!existsSync(f)) continue;
  let models;
  try {
    models = JSON.parse(readFileSync(f, 'utf8'));
  } catch (e) {
    console.error(`Failed to parse ${rel}: ${e.message}`);
    process.exit(2);
  }
  for (const m of models) {
    if (!m || typeof m.code !== 'string') continue;
    scanned += 1;
    const code = m.code;
    if (baseline.has(code)) continue; // grandfathered legacy model
    newCount += 1;
    if (!isConforming(code)) {
      violations.push({ rel, code });
    }
  }
}

console.log(`Model naming guard: scanned ${scanned} models, ${newCount} new (not in baseline).`);

if (scanned === 0) {
  console.error(`Model naming guard found no models under ${ROOT}; refusing an empty green result.`);
  process.exit(2);
}

if (violations.length > 0) {
  console.error('\nNon-conforming NEW model codes (must follow four-layer naming standard):');
  for (const v of violations) {
    console.error(`  ✗ ${v.code}   (${v.rel})`);
  }
  console.error(
    '\nNew model codes must end with _common / _ext / _link, or use ' +
      '_<industry>_<business_name>, and must not use a bare `pe_` industry prefix.',
  );
  console.error('If a code is an intentional legacy addition, add it to ' +
    'plugins/crm/scripts/model-code-baseline.json with an explicit review justification.');
  process.exit(1);
}

console.log('Model naming guard passed.');
