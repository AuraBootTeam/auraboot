#!/usr/bin/env node
// Designer boundary checker (B3a — manifest-only minimal gate).
//
// Enforces the early-defense-line half of DDR-2026-06-18-designer-kernel-boundary:
// every designer SURFACE directory must declare which LAYOUT FAMILY it belongs to
// via a `designer.family.json` manifest, so nobody can grow a new wild canvas /
// store / palette / property-panel kernel without it being visible and governed.
//
// B3a scope (this file) is intentionally manifest-ONLY:
//   - each discovered/expected designer surface has a designer.family.json
//   - `family` is one of the registered families (scripts/designer-family-registry.json)
//   - `layer1Kernel`, `layer0[]`, `storage` are declared
// It does NOT yet do import checks / forbidden-pattern scans / new-family rationale —
// that is B3b (see docs/backlog/2026-06-18-designer-layout-family-convergence.md §B3b).
//
// Rollout note: the backlog describes a two-step "existing warn / new·changed fail"
// rollout. This gate is introduced together with an ATOMIC backfill of every existing
// surface manifest in the same PR, so there is no mass-fail window — it ships in the
// end-state "fail all" mode directly. A future designer surface added without a
// manifest fails immediately, which is the whole point.
//
// Self-contained (node: builtins only) so it vendors per-repo and runs under a
// CI single-repo checkout, exactly like scripts/check-docs-governance.mjs and
// scripts/page-golden-audit.mjs. The enterprise copy differs only in
// .designer-boundary.json config; keep the registry registryVersion in sync.
//
// Usage:
//   node scripts/check-designer-boundary.mjs [--strict] [--json] [--quiet]
// Exit: 0 = clean (warnings allowed), 1 = error (or warning under --strict),
//       2 = config/IO failure (missing .designer-boundary.json or registry).

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Bump in lockstep with scripts/designer-family-registry.json `registryVersion`
// and the enterprise vendored copy. A registry whose registryVersion does not
// match reports S-DESIGNER-REGISTRY-STALE (warning).
export const REGISTRY_VERSION = 1;

const CONFIG_FILE = '.designer-boundary.json';
const MANIFEST_FILE = 'designer.family.json';

// A directory is treated as a candidate designer surface when its name contains
// "-designer" (covers *-designer and *-designer-sdk). Surfaces whose directory
// name does not follow that convention (e.g. core-dashboard) must be listed
// explicitly in config.expectedSurfaces.
const DESIGNER_NAME_RE = /-designer(-[a-z0-9]+)?$/;

function readJson(absPath) {
  return JSON.parse(fs.readFileSync(absPath, 'utf8'));
}

function isDir(absPath) {
  try {
    return fs.statSync(absPath).isDirectory();
  } catch {
    return false;
  }
}

function listDirs(absPath) {
  try {
    return fs
      .readdirSync(absPath, { withFileTypes: true })
      .filter((e) => e.isDirectory())
      .map((e) => e.name);
  } catch {
    return [];
  }
}

function nonEmptyString(v) {
  return typeof v === 'string' && v.trim().length > 0;
}

// Validate one parsed manifest object. Pushes findings for every structural
// problem (does NOT throw). `relPath` is the manifest file path for reporting.
function validateManifest(manifest, relPath, registry, findings) {
  const field = (name, message) =>
    findings.push({ severity: 'error', code: 'S-DESIGNER-MANIFEST-FIELD', file: relPath, message });

  if (manifest === null || typeof manifest !== 'object' || Array.isArray(manifest)) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-MANIFEST-FIELD',
      file: relPath,
      message: 'manifest must be a JSON object',
    });
    return;
  }

  if (!nonEmptyString(manifest.surface)) {
    field('surface', 'missing/empty required field `surface` (string)');
  }

  if (!nonEmptyString(manifest.family)) {
    field('family', 'missing/empty required field `family` (string)');
  } else if (!Object.prototype.hasOwnProperty.call(registry.families, manifest.family)) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-MANIFEST-BAD-FAMILY',
      file: relPath,
      message: `family "${manifest.family}" is not a registered layout family (allowed: ${Object.keys(registry.families).join(', ')})`,
    });
  }

  if (!nonEmptyString(manifest.layer1Kernel)) {
    field('layer1Kernel', 'missing/empty required field `layer1Kernel` (string; use "self" for a kernel surface)');
  }

  if (!Array.isArray(manifest.layer0)) {
    field('layer0', 'missing required field `layer0` (array of consumed Layer-0 SDK capabilities; may be empty)');
  }

  const storage = manifest.storage;
  const storageOk =
    nonEmptyString(storage) ||
    (storage !== null && typeof storage === 'object' && !Array.isArray(storage) && nonEmptyString(storage.primary));
  if (!storageOk) {
    field('storage', 'missing required field `storage` (string, or object with a non-empty `primary`)');
  }
}

// Core audit. Returns { findings, configError, registryStale, scanned, skipped }.
export function auditRepo(repoRoot, options = {}) {
  const findings = [];
  const scanned = [];
  const skipped = [];

  // --- config ---------------------------------------------------------------
  const configPath = path.join(repoRoot, CONFIG_FILE);
  if (!fs.existsSync(configPath)) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-CONFIG-MISSING',
      file: CONFIG_FILE,
      message: `missing ${CONFIG_FILE} at repo root`,
    });
    return { findings, configError: true, registryStale: false, scanned, skipped };
  }
  let config;
  try {
    config = readJson(configPath);
  } catch (e) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-CONFIG-INVALID',
      file: CONFIG_FILE,
      message: `cannot parse ${CONFIG_FILE}: ${e.message}`,
    });
    return { findings, configError: true, registryStale: false, scanned, skipped };
  }

  const surfaceRoots = Array.isArray(config.surfaceRoots) ? config.surfaceRoots : [];
  const expectedSurfaces = Array.isArray(config.expectedSurfaces) ? config.expectedSurfaces : [];
  const allowlist = config.allowlist && typeof config.allowlist === 'object' ? config.allowlist : {};
  const registryRel = nonEmptyString(config.registryPath)
    ? config.registryPath
    : 'scripts/designer-family-registry.json';

  // --- registry -------------------------------------------------------------
  const registryPath = path.join(repoRoot, registryRel);
  if (!fs.existsSync(registryPath)) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-REGISTRY-MISSING',
      file: registryRel,
      message: `missing family registry at ${registryRel}`,
    });
    return { findings, configError: true, registryStale: false, scanned, skipped };
  }
  let registry;
  try {
    registry = readJson(registryPath);
  } catch (e) {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-REGISTRY-INVALID',
      file: registryRel,
      message: `cannot parse registry: ${e.message}`,
    });
    return { findings, configError: true, registryStale: false, scanned, skipped };
  }
  if (!registry.families || typeof registry.families !== 'object') {
    findings.push({
      severity: 'error',
      code: 'S-DESIGNER-REGISTRY-INVALID',
      file: registryRel,
      message: 'registry has no `families` object',
    });
    return { findings, configError: true, registryStale: false, scanned, skipped };
  }
  let registryStale = false;
  if (registry.registryVersion !== REGISTRY_VERSION) {
    registryStale = true;
    findings.push({
      severity: 'warning',
      code: 'S-DESIGNER-REGISTRY-STALE',
      file: registryRel,
      message: `registryVersion ${registry.registryVersion} != checker REGISTRY_VERSION ${REGISTRY_VERSION}; vendored copy may be out of sync`,
    });
  }

  // --- discover candidate surfaces -----------------------------------------
  // Map relPath -> { relPath, source: 'discovered'|'expected' }
  const candidates = new Map();
  for (const root of surfaceRoots) {
    const absRoot = path.join(repoRoot, root);
    for (const name of listDirs(absRoot)) {
      if (!DESIGNER_NAME_RE.test(name)) continue;
      const relPath = path.posix.join(root.split(path.sep).join('/'), name);
      candidates.set(relPath, { relPath, source: 'discovered' });
    }
  }
  for (const rel of expectedSurfaces) {
    const norm = rel.split(path.sep).join('/');
    if (!candidates.has(norm)) candidates.set(norm, { relPath: norm, source: 'expected' });
  }

  // --- check each candidate -------------------------------------------------
  for (const { relPath, source } of [...candidates.values()].sort((a, b) => a.relPath.localeCompare(b.relPath))) {
    const absDir = path.join(repoRoot, relPath);
    // expectedSurfaces may legitimately not exist on a branch — only error if it
    // exists-but-empty or is missing while declared expected. We require the dir
    // to exist (a declared expected surface that vanished is a config drift).
    if (allowlist[relPath]) {
      skipped.push({ relPath, reason: allowlist[relPath] });
      continue;
    }
    if (!isDir(absDir)) {
      // Discovered candidates always exist (we listed them). Only expected ones
      // can be missing here.
      findings.push({
        severity: 'error',
        code: 'S-DESIGNER-EXPECTED-MISSING',
        file: relPath,
        message: `expectedSurface directory not found (update .designer-boundary.json if it moved/was removed)`,
      });
      continue;
    }
    const manifestRel = path.posix.join(relPath, MANIFEST_FILE);
    const manifestAbs = path.join(repoRoot, relPath, MANIFEST_FILE);
    if (!fs.existsSync(manifestAbs)) {
      findings.push({
        severity: 'error',
        code: 'S-DESIGNER-NO-MANIFEST',
        file: manifestRel,
        message: `designer surface "${relPath}" (${source}) has no ${MANIFEST_FILE}; declare its layout family or add it to allowlist with a reason`,
      });
      scanned.push({ relPath, hasManifest: false });
      continue;
    }
    let manifest;
    try {
      manifest = readJson(manifestAbs);
    } catch (e) {
      findings.push({
        severity: 'error',
        code: 'S-DESIGNER-MANIFEST-INVALID-JSON',
        file: manifestRel,
        message: `cannot parse manifest: ${e.message}`,
      });
      scanned.push({ relPath, hasManifest: true, valid: false });
      continue;
    }
    const before = findings.length;
    validateManifest(manifest, manifestRel, registry, findings);
    scanned.push({ relPath, hasManifest: true, valid: findings.length === before, family: manifest && manifest.family });
  }

  return { findings, configError: false, registryStale, scanned, skipped };
}

// --- reporting ---------------------------------------------------------------

function printResult(result, options) {
  if (options.json) {
    console.log(JSON.stringify(result, null, 2));
    return;
  }
  const errors = result.findings.filter((f) => f.severity === 'error');
  const warnings = result.findings.filter((f) => f.severity === 'warning');

  if (!options.quiet) {
    if (result.skipped.length) {
      console.log('\nAllowlisted (skipped):');
      for (const s of result.skipped) console.log(`  - ${s.relPath} -- ${s.reason}`);
    }
    if (result.scanned.length) {
      console.log('\nSurfaces:');
      for (const s of result.scanned) {
        const fam = s.family ? ` [${s.family}]` : '';
        const status = !s.hasManifest ? 'NO MANIFEST' : s.valid ? 'ok' : 'INVALID';
        console.log(`  - ${s.relPath}${fam}: ${status}`);
      }
    }
    const groups = [
      ['Errors', errors],
      ['Warnings', warnings],
    ];
    for (const [label, items] of groups) {
      if (!items.length) continue;
      console.log(`\n${label}:`);
      for (const f of items) console.log(`  ${f.severity.toUpperCase()} ${f.code} ${f.file} -- ${f.message}`);
    }
  }
  console.log(`\nSummary: ${errors.length} error(s), ${warnings.length} warning(s)`);
  console.log(errors.length || (options.strict && warnings.length) ? 'FAILED.' : 'PASSED.');
}

function parseArgs(argv) {
  const options = { strict: false, json: false, quiet: false, help: false };
  for (const a of argv) {
    if (a === '--strict') options.strict = true;
    else if (a === '--json') options.json = true;
    else if (a === '--quiet') options.quiet = true;
    else if (a === '--help' || a === '-h') options.help = true;
  }
  return options;
}

function printUsage() {
  console.log(`Usage: node scripts/check-designer-boundary.mjs [--strict] [--json] [--quiet]

Validates that every designer surface declares its layout family via
${MANIFEST_FILE}, per DDR-2026-06-18-designer-kernel-boundary (B3a manifest-only).
Config: ${CONFIG_FILE} at repo root. Registry: scripts/designer-family-registry.json.
Exit: 0 clean, 1 error (or warning under --strict), 2 config/IO failure.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printUsage();
    process.exit(0);
  }
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const result = auditRepo(repoRoot, options);
  printResult(result, options);
  if (result.configError) process.exit(2);
  const hasErrors = result.findings.some((f) => f.severity === 'error');
  const hasWarnings = result.findings.some((f) => f.severity === 'warning');
  process.exit(hasErrors || (options.strict && hasWarnings) ? 1 : 0);
}
