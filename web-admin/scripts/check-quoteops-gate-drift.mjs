#!/usr/bin/env node
/**
 * QuoteOps gate spec-drift guard.
 *
 * The focused quoteops RELEASE gate runs `--project=quoteops`, whose testMatch
 * is built from `quoteOpsCurrentSpecNames` in playwright.config.ts. A spec that
 * a gate command line names but that is NOT in that array is dropped SILENTLY
 * by the project filter — the gate then goes GREEN without ever running it
 * (2026-06 incident, OSS#1156 / DDR-2026-06-29 §8: five per-role specs vanished
 * this way and the gate only ran 4 tests yet reported success).
 *
 * This check makes that failure mode impossible to reach unnoticed: every
 * pcba-solution quote/bom spec on disk MUST be classified as either
 *   - part of the release gate (`quoteOpsCurrentSpecNames`), or
 *   - explicitly excluded (scripts/quoteops-gate-classification.json).
 * A brand-new spec that is in neither list fails this check, so its author is
 * forced to decide — it can never silently escape the gate.
 *
 * HARD failures (exit 1):
 *   1. A pcba-solution quote/bom spec on disk is in neither list (unclassified).
 *   2. A name in quoteOpsCurrentSpecNames has no matching spec file (stale).
 *   3. A name in the exclusion list has no matching spec file (stale).
 *   4. A spec is in BOTH lists (contradiction).
 *
 * Usage: node scripts/check-quoteops-gate-drift.mjs
 */
import { readFileSync, readdirSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const webAdmin = resolve(__dirname, '..');
const configPath = resolve(webAdmin, 'playwright.config.ts');
const specDir = resolve(webAdmin, 'tests/e2e/pcba-solution');
const classificationPath = resolve(__dirname, 'quoteops-gate-classification.json');

/** A pcba-solution spec is "quote/bom" (in scope for the release gate question) when
 *  its basename starts with quote- or bom-. Other pcba-solution specs (pure CRM,
 *  aurabot, etc.) are out of scope for this guard. */
function isQuoteBomSpec(name) {
  return /^(quote|bom)[-]/.test(name);
}

function extractAllowlist(configSource) {
  const block = configSource.match(/const quoteOpsCurrentSpecNames = \[([\s\S]*?)\];/);
  if (!block) {
    throw new Error('could not locate quoteOpsCurrentSpecNames in playwright.config.ts');
  }
  // Strip `//` line comments first — the array carries an inline comment that
  // references `quoteops` in backticks, which must NOT be read as a spec name.
  const body = block[1].replace(/\/\/[^\n]*/g, '');
  return [...body.matchAll(/['"]([a-z0-9-]+)['"]/g)].map((m) => m[1]);
}

function main() {
  const configSource = readFileSync(configPath, 'utf8');
  const allowlist = extractAllowlist(configSource);
  const classification = JSON.parse(readFileSync(classificationPath, 'utf8'));
  const excluded = Object.keys(classification.excludedFromQuoteOpsGate || {}).filter(
    (k) => !k.startsWith('$'),
  );

  const diskSpecs = readdirSync(specDir)
    .filter((f) => f.endsWith('.spec.ts'))
    .map((f) => f.replace(/\.spec\.ts$/, ''));
  const diskSet = new Set(diskSpecs);
  const quoteBomOnDisk = diskSpecs.filter(isQuoteBomSpec);

  const allowSet = new Set(allowlist);
  const excludeSet = new Set(excluded);
  const errors = [];

  // 1. Every quote/bom spec on disk must be classified.
  for (const name of quoteBomOnDisk) {
    const inAllow = allowSet.has(name);
    const inExclude = excludeSet.has(name);
    if (!inAllow && !inExclude) {
      errors.push(
        `UNCLASSIFIED: tests/e2e/pcba-solution/${name}.spec.ts is neither in ` +
          `quoteOpsCurrentSpecNames (playwright.config.ts) nor in the exclusion list ` +
          `(scripts/quoteops-gate-classification.json). A new quote/bom spec must be ` +
          `consciously classified or the quoteops gate would run green WITHOUT it.`,
      );
    }
    if (inAllow && inExclude) {
      errors.push(
        `CONTRADICTION: ${name} is in BOTH quoteOpsCurrentSpecNames and the exclusion list. Pick one.`,
      );
    }
  }

  // 2/3. No stale entries pointing at deleted specs.
  for (const name of allowlist) {
    if (!diskSet.has(name)) {
      errors.push(
        `STALE ALLOWLIST: quoteOpsCurrentSpecNames names "${name}" but ` +
          `tests/e2e/pcba-solution/${name}.spec.ts does not exist.`,
      );
    }
  }
  for (const name of excluded) {
    if (!diskSet.has(name)) {
      errors.push(
        `STALE EXCLUSION: quoteops-gate-classification.json names "${name}" but ` +
          `tests/e2e/pcba-solution/${name}.spec.ts does not exist.`,
      );
    }
  }

  if (errors.length > 0) {
    console.error('quoteops gate spec-drift check FAILED:\n');
    for (const e of errors) console.error(`  - ${e}\n`);
    console.error(
      `Fix: add the spec to quoteOpsCurrentSpecNames in playwright.config.ts (to run it in the ` +
        `release gate) OR to scripts/quoteops-gate-classification.json with a reason (if it runs ` +
        `only under the enterprise/chromium projects).`,
    );
    process.exit(1);
  }

  console.log(
    `quoteops gate spec-drift OK: ${quoteBomOnDisk.length} quote/bom pcba-solution specs ` +
      `classified (${allowlist.length} in the release gate, ${excluded.length} explicitly excluded).`,
  );
}

main();
