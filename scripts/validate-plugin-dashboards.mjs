#!/usr/bin/env node
/**
 * Validates every plugins/<plugin>/config/dashboards/*.json against the Plan #8
 * dashboard contract (plugins/schemas/dashboards.schema.json).
 *
 * Usage:
 *   node scripts/validate-plugin-dashboards.mjs                          # validate all plugins in this repo
 *   node scripts/validate-plugin-dashboards.mjs showcase                 # validate one plugin
 *   node scripts/validate-plugin-dashboards.mjs --plugins-dir /path/to/plugins  # scan external plugins dir
 *
 * Exit code:
 *   0 — all files valid
 *   1 — one or more files failed
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = dirname(__dirname);
const schemaPath = join(repoRoot, 'plugins', 'schemas', 'dashboards.schema.json');

// Parse --plugins-dir flag
const args = process.argv.slice(2);
let pluginsDir = join(repoRoot, 'plugins');
const pluginsDirIdx = args.indexOf('--plugins-dir');
if (pluginsDirIdx !== -1 && args[pluginsDirIdx + 1]) {
  pluginsDir = resolve(args[pluginsDirIdx + 1]);
  args.splice(pluginsDirIdx, 2);
}

if (!existsSync(schemaPath)) {
  console.error(`Schema not found: ${schemaPath}`);
  process.exit(1);
}

const schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));

// Minimal JSON Schema validator — checks required fields + common types.
// Matches the dashboards.schema.json shape; not a full Ajv substitute.
function validateDashboard(obj, filePath) {
  const errors = [];
  const required = schema.required || [];
  for (const field of required) {
    if (obj[field] === undefined || obj[field] === null) {
      errors.push(`missing required field: ${field}`);
    }
  }

  if (typeof obj.code !== 'string' || !obj.code.match(/^[a-z][a-z0-9_]*$/i)) {
    errors.push(`code must be a string matching /^[a-z][a-z0-9_]*$/i (got ${JSON.stringify(obj.code)})`);
  }

  if (obj.title !== undefined && typeof obj.title !== 'string' && typeof obj.title !== 'object') {
    errors.push(`title must be a string or LocalizedText object`);
  }

  if (obj.widgets !== undefined && !Array.isArray(obj.widgets)) {
    errors.push(`widgets must be an array`);
  }

  if (Array.isArray(obj.widgets)) {
    obj.widgets.forEach((w, i) => {
      if (!w || typeof w !== 'object') {
        errors.push(`widgets[${i}] must be an object`);
        return;
      }
      // Required string fields per schema
      for (const field of ['id', 'type']) {
        if (typeof w[field] !== 'string' || w[field].length === 0) {
          errors.push(`widgets[${i}].${field} is required and must be a non-empty string`);
        }
      }
      // Required integer fields per schema
      for (const field of ['x', 'y', 'w', 'h']) {
        if (typeof w[field] !== 'number' || !Number.isInteger(w[field])) {
          errors.push(`widgets[${i}].${field} is required and must be an integer`);
        }
      }
      // w and h must be >= 1
      if (typeof w.w === 'number' && w.w < 1) {
        errors.push(`widgets[${i}].w must be >= 1`);
      }
      if (typeof w.h === 'number' && w.h < 1) {
        errors.push(`widgets[${i}].h must be >= 1`);
      }
    });
  }

  if (obj.scope !== undefined && !['personal', 'team', 'global', 'workbench'].includes(obj.scope)) {
    errors.push(`scope must be one of personal|team|global|workbench (got ${JSON.stringify(obj.scope)})`);
  }

  if (obj.status !== undefined && !['draft', 'published'].includes(obj.status)) {
    errors.push(`status must be one of draft|published (got ${JSON.stringify(obj.status)})`);
  }

  return errors;
}

function validateFile(filePath) {
  let content;
  try {
    content = JSON.parse(readFileSync(filePath, 'utf-8'));
  } catch (e) {
    return [`invalid JSON: ${e.message}`];
  }
  if (Array.isArray(content)) {
    return content.flatMap((item, i) =>
      validateDashboard(item, filePath).map((msg) => `[${i}] ${msg}`),
    );
  }
  return validateDashboard(content, filePath);
}

const targets = args;
const plugins = targets.length
  ? targets
  : readdirSync(pluginsDir).filter((name) => {
      const full = join(pluginsDir, name);
      return statSync(full).isDirectory() && existsSync(join(full, 'config', 'dashboards'));
    });

let failed = 0;
let scanned = 0;
for (const plugin of plugins) {
  const dashDir = join(pluginsDir, plugin, 'config', 'dashboards');
  if (!existsSync(dashDir)) {
    if (targets.length) console.warn(`[${plugin}] no config/dashboards/ directory — skipped`);
    continue;
  }
  const files = readdirSync(dashDir).filter((f) => f.endsWith('.json'));
  for (const file of files) {
    scanned++;
    const filePath = join(dashDir, file);
    const errors = validateFile(filePath);
    if (errors.length) {
      failed++;
      console.error(`\n✗ ${plugin}/${file}`);
      for (const err of errors) console.error(`    ${err}`);
    } else {
      console.log(`✓ ${plugin}/${file}`);
    }
  }
}

console.log(`\nScanned ${scanned} file(s); ${failed} failed.`);
process.exit(failed > 0 ? 1 : 0);
