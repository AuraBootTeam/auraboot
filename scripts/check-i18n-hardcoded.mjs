#!/usr/bin/env node
/**
 * check-i18n-hardcoded.mjs — i18n hardcoded-Chinese gate (ratchet).
 *
 * Tracks three metrics for the OSS frontend + DSL config layer:
 *   1. reactUserFacing  — user-visible hardcoded Chinese in web-admin/app/**.tsx/.ts
 *                         that does NOT go through useI18n().t()
 *   2. reactComments    — Chinese inside code comments (comments must be English)
 *   3. dslViolations    — DSL JSON text keys with raw Chinese and no `:en` counterpart,
 *                         excluding the allowed Model/Field source-language displayName
 *
 * Blessed forms that are NOT counted (see docs/.../09-i18n国际化系统.md):
 *   - React: t('key', params, 'fallback'); inline LocalizedText { zh, en } / 'zh-CN': maps
 *   - DSL:   "displayName":"门店编码" at Model/Field layer (source language + i18n source)
 *            "name:zh-CN" / "name:en" suffix pairs (auto-converted to i18n records on import)
 *            i18n.json catalog files
 *   - locale data tables (china-regions, timezoneNames, …) — proper-noun data
 *
 * Usage:
 *   node scripts/check-i18n-hardcoded.mjs --report            # full breakdown, no gate
 *   node scripts/check-i18n-hardcoded.mjs --check             # ratchet: fail if any metric > baseline
 *   node scripts/check-i18n-hardcoded.mjs --update-baseline   # snapshot current counts
 *
 * Baseline lives at scripts/.i18n-baseline.json (committed).
 */
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, basename, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = join(__dirname, '..');
const REACT_ROOT = join(REPO, 'web-admin', 'app');
const PLUGINS_ROOT = join(REPO, 'plugins');
const BASELINE = join(__dirname, '.i18n-baseline.json');

const CJK = /[一-鿿]/;
const LOCALEKEY = /['"](?:zh-CN|zh-TW|zh|en-US|en|ja|ja-JP|ko|ko-KR|fr|de|es|ru|ar)['"]\s*:/;
const DEV = /console\.|logger\.|throw new |new Error\(/;
const DATA_FILE = /(china-regions|timezoneNames|timezone|phone-codes|country|countries|currency|currencies)/;
const SKIP_DIRS = new Set(['node_modules', 'build', 'coverage', 'test-results', 'dist', '.gradle', 'target']);
const TEXT_KEYS = new Set(['name', 'displayName', 'title', 'label', 'description', 'placeholder',
  'message', 'unit', 'lifecycle_description', 'tooltip', 'hint', 'summary']);
const MODELFIELD_FILES = new Set(['models.json', 'fields.json']);
const MF_KEYS = new Set(['displayName', 'description', 'placeholder']);

function isTest(p) { return p.includes('__tests__') || p.includes('.test.') || p.includes('.spec.'); }

function* walk(dir, exts) {
  for (const name of readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) { yield* walk(p, exts); continue; }
    if (exts.some((e) => name.endsWith(e))) yield p;
  }
}

// ---- React scan ----
function scanReact() {
  const userFiles = {}; const commentFiles = {};
  let userFacing = 0, comments = 0, devFacing = 0, fallback = 0;
  if (!existsSync(REACT_ROOT)) return { userFacing, comments, devFacing, fallback, userFiles, commentFiles };
  for (const p of walk(REACT_ROOT, ['.tsx', '.ts'])) {
    if (isTest(p)) continue;
    const isData = DATA_FILE.test(p);
    let src;
    try { src = readFileSync(p, 'utf8'); } catch { continue; }
    const rel = relative(REPO, p);
    let inBlock = false;
    for (const ln of src.split('\n')) {
      if (!CJK.test(ln)) {
        if (ln.includes('/*') && !ln.includes('*/')) inBlock = true;
        if (ln.includes('*/')) inBlock = false;
        continue;
      }
      const s = ln.trim();
      const blockHere = inBlock;
      if (ln.includes('/*') && !ln.includes('*/')) inBlock = true;
      if (ln.includes('*/')) inBlock = false;
      // comment line?
      if (blockHere || s.startsWith('//') || s.startsWith('*') || s.startsWith('/*')) {
        comments++; commentFiles[rel] = (commentFiles[rel] || 0) + 1; continue;
      }
      const idx = ln.indexOf('//');
      if (idx !== -1 && !CJK.test(ln.slice(0, idx))) {
        comments++; commentFiles[rel] = (commentFiles[rel] || 0) + 1; continue;
      }
      if (LOCALEKEY.test(ln)) continue;       // inline LocalizedText -> ok
      if (isData) continue;                    // locale data table -> ok
      if (/\bt\(/.test(ln)) { fallback++; continue; } // t() with zh fallback -> minor, not counted
      if (DEV.test(ln)) { devFacing++; continue; }    // dev-facing -> not counted
      userFacing++; userFiles[rel] = (userFiles[rel] || 0) + 1;
    }
  }
  return { userFacing, comments, devFacing, fallback, userFiles, commentFiles };
}

// ---- DSL JSON scan (per-object, en-coverage aware) ----
function dslViolations() {
  const out = [];
  if (!existsSync(PLUGINS_ROOT)) return out;
  for (const p of walk(PLUGINS_ROOT, ['.json'])) {
    const fname = basename(p);
    if (fname.toLowerCase().includes('i18n')) continue; // catalog
    if (p.includes('/test-fixtures/')) continue; // test-only plugin (AURA_ENV=test), not a product surface
    let data;
    try { data = JSON.parse(readFileSync(p, 'utf8')); } catch { continue; }
    const rel = relative(REPO, p);
    walkJson(data, fname, rel, out);
  }
  return out;
}
function walkJson(node, fname, rel, out) {
  if (Array.isArray(node)) { for (const it of node) walkJson(it, fname, rel, out); return; }
  if (node && typeof node === 'object') {
    const suffix = {};
    for (const k of Object.keys(node)) {
      const m = k.match(/^(.+):(en-US|en|zh-CN|zh|ja-JP|ja|ko-KR|ko)$/);
      if (m) (suffix[m[1]] ||= new Set()).add(m[2]);
    }
    for (const [k, v] of Object.entries(node)) {
      if (k.includes(':')) continue;
      if (typeof v === 'string' && CJK.test(v) && TEXT_KEYS.has(k)) {
        const hasEn = suffix[k] && (suffix[k].has('en') || suffix[k].has('en-US'));
        let allowedSource = MODELFIELD_FILES.has(fname) && MF_KEYS.has(k);
        if (/^t[a-z]+_/.test(fname) && MF_KEYS.has(k)) allowedSource = true; // entity model defs
        if (!hasEn && !allowedSource) out.push({ file: rel, key: k, value: v.slice(0, 40) });
      }
      walkJson(v, fname, rel, out);
    }
  }
}

function collect() {
  const r = scanReact();
  const dsl = dslViolations();
  return {
    reactUserFacing: r.userFacing,
    reactComments: r.comments,
    dslViolations: dsl.length,
    _detail: { ...r, dsl },
  };
}

const args = process.argv.slice(2);
const cur = collect();
const metrics = { reactUserFacing: cur.reactUserFacing, reactComments: cur.reactComments, dslViolations: cur.dslViolations };

if (args.includes('--report')) {
  const d = cur._detail;
  console.log('=== i18n hardcoded-Chinese report ===');
  console.log(`reactUserFacing : ${metrics.reactUserFacing}  (files: ${Object.keys(d.userFiles).length})`);
  console.log(`reactComments   : ${metrics.reactComments}  (files: ${Object.keys(d.commentFiles).length})`);
  console.log(`dslViolations   : ${metrics.dslViolations}  (files: ${new Set(d.dsl.map((v) => v.file)).size})`);
  console.log(`(not counted) devFacing=${d.devFacing}  t()-zh-fallback=${d.fallback}`);
  const top = Object.entries(d.userFiles).sort((a, b) => b[1] - a[1]).slice(0, 15);
  console.log('\nTop React user-facing files:');
  for (const [f, c] of top) console.log(`  ${String(c).padStart(4)}  ${f}`);
  console.log('\nDSL violations by file:');
  const byf = {};
  for (const v of d.dsl) byf[v.file] = (byf[v.file] || 0) + 1;
  for (const [f, c] of Object.entries(byf).sort((a, b) => b[1] - a[1])) console.log(`  ${String(c).padStart(4)}  ${f}`);
  process.exit(0);
}

if (args.includes('--update-baseline')) {
  writeFileSync(BASELINE, JSON.stringify({ ...metrics, note: 'ratchet baseline; only decreases allowed' }, null, 2) + '\n');
  console.log('baseline written:', metrics);
  process.exit(0);
}

if (args.includes('--check')) {
  if (!existsSync(BASELINE)) { console.error('No baseline. Run --update-baseline first.'); process.exit(2); }
  const base = JSON.parse(readFileSync(BASELINE, 'utf8'));
  let failed = false;
  for (const k of ['reactUserFacing', 'reactComments', 'dslViolations']) {
    const b = base[k] ?? 0, c = metrics[k];
    const flag = c > b ? ' ❌ INCREASED' : (c < b ? ' ✅ decreased' : ' ok');
    console.log(`${k}: baseline=${b} current=${c}${flag}`);
    if (c > b) failed = true;
  }
  if (failed) { console.error('\ni18n gate FAILED: new hardcoded Chinese added. Lower it or use i18n.'); process.exit(1); }
  console.log('\ni18n gate passed (no regressions).');
  process.exit(0);
}

console.log('usage: --report | --check | --update-baseline');
console.log('current:', metrics);
