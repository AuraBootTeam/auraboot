#!/usr/bin/env node
/**
 * check-controller-authz.mjs — regression guard for the deep-review fail-open finding
 * (DR-20260618-D1-perm-004/005), upgraded for the default-deny flip.
 *
 * PermissionInterceptor (default `aura.security.authz.unannotated-mode=deny`) rejects ANY
 * handler with no explicit decision: a class-level OR method-level @RequirePermission,
 * @AuthenticatedAccess, or @RequirePlatformAdmin. Decision is per handler — a method-level
 * @RequirePermission on the write methods of a controller does NOT decide its un-annotated
 * GETs (MenuController was the live example), and /api/admin paths are NOT exempt (the
 * AdminRoleInterceptor runs alongside the PermissionInterceptor, not instead of it).
 *
 * This script mirrors that per-handler semantics statically: a controller file passes when
 * a class-level guard decides all handlers; otherwise every mapping annotation must have a
 * guard in its own annotation block (window: previous statement terminator → method body
 * brace). String literals are masked before segmenting so justification text containing
 * ';' cannot corrupt the cut points. Controllers annotated @Profile("test") are never
 * wired in production and stay out of scope.
 *
 * The baseline pins files that are intentionally left undecided; it must currently be
 * empty, and any NEW undecided controller fails the build. To intentionally accept one,
 * regenerate the baseline with --write-baseline and note why in the PR.
 *
 * Usage:
 *   node scripts/check-controller-authz.mjs              # compare to baseline, exit 1 on new drift
 *   node scripts/check-controller-authz.mjs --write-baseline
 *   node scripts/check-controller-authz.mjs --json
 */
import fs from 'node:fs';
import { javaSourceRoots } from './lib/java-source-roots.mjs';
import path from 'node:path';

const REPO = process.cwd();
const SOURCES = javaSourceRoots(REPO);
const BASELINE = path.join(REPO, 'scripts/controller-authz-baseline.json');
const WRITE = process.argv.includes('--write-baseline');
const JSON_OUT = process.argv.includes('--json');

// A handler is "decided" by a class-level OR method-level RBAC guard (@RequirePermission),
// acknowledged authenticated-only marker (@AuthenticatedAccess), or the strictly stronger
// platform-admin contract (@RequirePlatformAdmin).
const GUARD = /@(?:RequirePermission|AuthenticatedAccess|RequirePlatformAdmin)\b/;
// Controllers annotated @Profile("test") are only wired into the test profile and
// are never registered in a production context, so they carry no fail-open risk.
const TEST_PROFILE = /@Profile\(\s*\{?\s*['"]test['"]/;

// Single-pass scanner that removes comments and masks string/char literals AT THE SAME
// TIME. Order matters and neither transform alone is safe:
//  - stripping comments first lets `/*` inside a string literal ("/api/admin/**") open a
//    bogus block comment that swallows the rest of the file;
//  - masking strings first lets a quote inside a comment pair with a later quote in code.
// The combined pass also fixes pairing for char literals like '"'.
function stripCommentsAndLiterals(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    const ch = src[i], next = src[i + 1];
    if (ch === '/' && next === '/') {                       // line comment → drop
      while (i < n && src[i] !== '\n') i++;
    } else if (ch === '/' && next === '*') {                // block comment → one space
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i = Math.min(n, i + 2);
      out += ' ';
    } else if (ch === '"' || ch === "'") {                  // string/char literal → masked
      const quote = ch;
      out += quote === '"' ? '""' : "''";
      i++;
      while (i < n && src[i] !== quote) {
        if (src[i] === '\\') i++;
        i++;
      }
      i++;
    } else {
      out += ch;
      i++;
    }
  }
  // Annotation arrays and nested annotations may contain braces/semicolons. Only
  // delimiters outside parentheses can end the declaration preceding a handler.
  let depth = 0;
  return [...out].map((ch) => {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    return depth > 0 && /[{};]/.test(ch) ? ' ' : ch;
  }).join('');
}

/**
 * True when at least one mapped handler in this controller has no class-level or
 * method-level guard annotation. Class-declaration-aware: class-level @RequestMapping and
 * the class-level guard are handled before per-handler scanning.
 */
function hasUndecidedHandler(rawText) {
  const src = stripCommentsAndLiterals(rawText);
  if (!/@RestController|@Controller/.test(src)) return false;
  const classDecl = src.match(/\n\s*(?:public\s+|final\s+|abstract\s+)*class\s+\w+/);
  if (!classDecl) return true; // unrecognized shape — be conservative, flag it
  const classStart = classDecl.index;
  const classEnd = classStart + classDecl[0].length;
  const before = src.slice(0, classStart);
  const classCut = Math.max(before.lastIndexOf(';'), before.lastIndexOf('}'));
  if (GUARD.test(src.slice(classCut + 1, classStart))) return false; // class-level guard decides all
  const mapping = /@(?:Get|Post|Put|Delete|Patch|Request)Mapping/g;
  let m;
  while ((m = mapping.exec(src)) !== null) {
    if (m.index < classEnd) continue; // class-level @RequestMapping
    const segStart = Math.max(src.lastIndexOf(';', m.index), src.lastIndexOf('}', m.index), classEnd);
    // Extend the window to the method body brace: scan the mapping's balanced parens, the
    // next '{' at depth 0 is the body brace — this covers guard annotations that sit AFTER
    // the mapping annotation (e.g. @PostMapping(...) \n @RequirePermission).
    let depth = 0, parenEnd = m.index;
    for (let i = m.index; i < src.length; i++) {
      const ch = src[i];
      if (ch === '(') depth++;
      else if (ch === ')' && --depth === 0) { parenEnd = i + 1; break; }
    }
    let windowEnd = src.indexOf('{', parenEnd);
    if (windowEnd < 0) {
      const semi = src.indexOf(';', parenEnd);
      windowEnd = semi >= 0 ? semi : src.length;
    }
    if (!GUARD.test(src.slice(segStart, windowEnd))) return true;
  }
  return false;
}

function walk(dir, out = []) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.isFile() && e.name.endsWith('Controller.java')) out.push(p);
  }
  return out;
}

const unguarded = [];
for (const file of SOURCES.flatMap(source => walk(source))) {
  const text = fs.readFileSync(file, 'utf8');
  if (TEST_PROFILE.test(text)) continue; // @Profile("test") — never wired in prod
  if (!hasUndecidedHandler(text)) continue;
  unguarded.push(path.relative(REPO, file));
}
unguarded.sort();

/**
 * The baseline accepts two entry shapes:
 *
 *   "platform/.../FooController.java"
 *   { "file": "platform/.../FooController.java", "reason": "why this is accepted" }
 *
 * Bare strings are the inherited entries, from before this gate asked for a reason. New
 * entries must carry one — see the --write-baseline path below.
 */
function readBaseline() {
  const raw = JSON.parse(fs.readFileSync(BASELINE, 'utf8'));
  const files = [];
  const reasons = new Map();
  for (const entry of raw) {
    if (typeof entry === 'string') {
      files.push(entry);
    } else if (entry && typeof entry.file === 'string') {
      files.push(entry.file);
      if (entry.reason) reasons.set(entry.file, entry.reason);
    } else {
      console.error(`[controller-authz] malformed baseline entry: ${JSON.stringify(entry)}`);
      process.exit(2);
    }
  }
  return { files, reasons };
}

let baseline = [];
let baselineReasons = new Map();
try {
  const parsed = readBaseline();
  baseline = parsed.files;
  baselineReasons = parsed.reasons;
} catch {
  if (!WRITE) {
    console.error(`[controller-authz] missing baseline ${BASELINE} — run with --write-baseline first`);
    process.exit(2);
  }
}

if (WRITE) {
  // Rewriting used to flatten everything back to bare strings, which silently destroyed any
  // reason anyone had recorded — that is why this file accumulated 40 entries with no
  // justification for any of them. Reasons are now preserved, and a genuinely new entry has
  // to explain itself: an accepted fail-open surface with no stated reason is indistinguishable
  // from one nobody looked at.
  const reasonArg = process.argv.indexOf('--reason');
  const reason = reasonArg > -1 ? process.argv[reasonArg + 1] : null;
  const brandNew = unguarded.filter((f) => !baseline.includes(f));

  if (brandNew.length && !reason) {
    console.error(`\n❌ ${brandNew.length} controller(s) would be newly baselined with no reason:`);
    brandNew.forEach((f) => console.error(`   + ${f}`));
    console.error('\nRe-run with --reason "why this fail-open surface is accepted".');
    process.exit(1);
  }

  const out = unguarded.map((f) => {
    const existing = baselineReasons.get(f);
    if (existing) return { file: f, reason: existing };
    if (brandNew.includes(f) && reason) return { file: f, reason };
    return f;   // inherited entry, reason never recorded — left as-is rather than invented
  });
  fs.writeFileSync(BASELINE, JSON.stringify(out, null, 2) + '\n');
  console.log(`[controller-authz] baseline written: ${out.length} entries `
    + `(${out.filter((e) => typeof e !== 'string').length} with a recorded reason)`);
  process.exit(0);
}

const baseSet = new Set(baseline);
const curSet = new Set(unguarded);
const added = unguarded.filter((f) => !baseSet.has(f));
const removed = baseline.filter((f) => !curSet.has(f));

if (JSON_OUT) {
  console.log(JSON.stringify({ total: unguarded.length, baseline: baseline.length, added, removed }, null, 2));
}

console.log(`[controller-authz] unguarded mapped controllers: ${unguarded.length} (baseline ${baseline.length})`);
if (removed.length) {
  console.log(`[controller-authz] ${removed.length} baselined controller(s) now guarded/removed (good — prune baseline):`);
  removed.forEach((f) => console.log(`   - ${f}`));
}
if (added.length) {
  console.error(`\n❌ ${added.length} NEW unguarded mapped controller(s) — add @RequirePermission/@AuthenticatedAccess/@RequirePlatformAdmin (class level decides all handlers), or (for @Profile("test")-only fixtures) exclude via that annotation, or --write-baseline with justification:`);
  added.forEach((f) => console.error(`   + ${f}`));
  process.exit(1);
}
console.log('✅ controller-authz check passed (every mapped controller carries an explicit authz decision).');
