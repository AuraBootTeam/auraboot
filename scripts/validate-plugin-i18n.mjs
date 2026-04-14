#!/usr/bin/env node
/**
 * Validates every plugins/<plugin>/config/i18n.json against the plugin i18n contract.
 *
 * Contract (mirrors com.auraboot.framework.plugin.dto.imports.I18nDefinitionDTO):
 *   - File is a flat JSON array (NOT nested { locale: { key: value } })
 *   - Each entry has required string "key"
 *   - Each entry has at least one translation (zh-CN, en-US, ja-JP, ko-KR, or xx-XX)
 *   - Optional: "source", "refType" (one of model|field|command|page|menu|permission|dict|misc)
 *   - No unknown top-level properties per entry
 *
 * Fails fast to prevent malformed formats (e.g. the nested shape that previously broke
 * page-manager imports).
 *
 * Usage:
 *   node scripts/validate-plugin-i18n.mjs            # validate all plugins
 *   node scripts/validate-plugin-i18n.mjs platform-admin page-manager
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = dirname(__dirname);
const pluginsDir = join(repoRoot, 'plugins');

const KNOWN_LOCALES = new Set(['zh-CN', 'en-US', 'ja-JP', 'ko-KR']);
// refType is advisory lineage metadata; any non-empty string is accepted.
const LOCALE_PATTERN = /^[a-z]{2}-[A-Z]{2}$/;
const ALLOWED_KEYS = new Set(['key', 'source', 'refType']);

function isLocaleKey(name) {
  return LOCALE_PATTERN.test(name);
}

function validateEntry(entry, index) {
  const errors = [];
  if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
    errors.push(`[${index}] entry must be an object`);
    return errors;
  }

  if (typeof entry.key !== 'string' || entry.key.length === 0) {
    errors.push(`[${index}] missing or empty "key"`);
  }

  if (entry.source !== undefined && typeof entry.source !== 'string') {
    errors.push(`[${index}] "source" must be a string`);
  }

  if (entry.refType !== undefined && (typeof entry.refType !== 'string' || entry.refType.length === 0)) {
    errors.push(`[${index}] "refType" must be a non-empty string if present`);
  }

  let hasTranslation = false;
  for (const [prop, value] of Object.entries(entry)) {
    if (ALLOWED_KEYS.has(prop)) continue;
    if (KNOWN_LOCALES.has(prop) || isLocaleKey(prop)) {
      if (typeof value !== 'string') {
        errors.push(`[${index}] locale "${prop}" value must be a string`);
      } else {
        hasTranslation = true;
      }
      continue;
    }
    errors.push(`[${index}] unknown property "${prop}"`);
  }

  if (!hasTranslation) {
    errors.push(`[${index}] no translations (need at least one locale, e.g. "zh-CN" or "en-US")`);
  }

  return errors;
}

const requested = process.argv.slice(2);
const pluginNames = requested.length
  ? requested
  : readdirSync(pluginsDir).filter(
      (name) => name !== 'schemas' && name !== 'scripts' && statSync(join(pluginsDir, name)).isDirectory(),
    );

let failed = 0;
let scanned = 0;

for (const name of pluginNames) {
  const file = join(pluginsDir, name, 'config', 'i18n.json');
  if (!existsSync(file)) continue;
  scanned += 1;

  let data;
  try {
    data = JSON.parse(readFileSync(file, 'utf8'));
  } catch (err) {
    console.error(`✗ ${name}/config/i18n.json — invalid JSON: ${err.message}`);
    failed += 1;
    continue;
  }

  if (!Array.isArray(data)) {
    console.error(
      `✗ ${name}/config/i18n.json — must be a flat array. Got ${typeof data}. ` +
        `Convert {locale: {key: value}} shape to [{key, zh-CN, en-US, source, refType}].`,
    );
    failed += 1;
    continue;
  }

  const errors = [];
  data.forEach((entry, i) => errors.push(...validateEntry(entry, i)));

  if (errors.length > 0) {
    console.error(`✗ ${name}/config/i18n.json — ${errors.length} violation(s):`);
    for (const err of errors.slice(0, 10)) {
      console.error(`    ${err}`);
    }
    if (errors.length > 10) {
      console.error(`    … ${errors.length - 10} more`);
    }
    failed += 1;
    continue;
  }

  console.log(`✓ ${name}/config/i18n.json (${data.length} entries)`);
}

console.log(`\nScanned: ${scanned} files, Failed: ${failed}`);
process.exit(failed > 0 ? 1 : 0);
