#!/usr/bin/env node
/**
 * check-controller-authz.mjs — regression guard for the deep-review fail-open finding
 * (DR-20260618-D1-perm-004/005).
 *
 * The PermissionInterceptor fail-opens for handlers with no @RequirePermission
 * (annotation == null -> allow). Controllers that expose a write mapping
 * (@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping), have no @RequirePermission,
 * and are NOT under /api/admin/** (which AdminRoleInterceptor covers) are therefore
 * reachable by any authenticated user.
 *
 * This script does NOT try to decide which of those are legitimately self-scoped vs
 * which need an admin guard — that classification needs domain judgment. Instead it
 * pins the CURRENT set as a baseline and FAILS only when a NEW unguarded write
 * controller appears, so the fail-open surface cannot silently grow. To intentionally
 * accept a new one (e.g. a genuinely self-scoped endpoint), regenerate the baseline
 * with --write-baseline and note why in the PR.
 *
 * Usage:
 *   node scripts/check-controller-authz.mjs              # compare to baseline, exit 1 on new drift
 *   node scripts/check-controller-authz.mjs --write-baseline
 *   node scripts/check-controller-authz.mjs --json
 */
import fs from 'node:fs';
import path from 'node:path';

const REPO = process.cwd();
const SRC = path.join(REPO, 'platform/src/main/java');
const BASELINE = path.join(REPO, 'scripts/controller-authz-baseline.json');
const WRITE = process.argv.includes('--write-baseline');
const JSON_OUT = process.argv.includes('--json');

const WRITE_MAPPING = /@(Post|Put|Delete|Patch)Mapping/;
// A controller is "decided" once it carries either an RBAC guard (@RequirePermission) or an
// explicit acknowledged authenticated-only marker (@AuthenticatedAccess). Both drop it out of the
// undecided baseline.
const GUARD = /@RequirePermission|@AuthenticatedAccess/;
// Controllers annotated @Profile("test") are only wired into the test profile and
// are never registered in a production context, so they carry no fail-open risk.
const TEST_PROFILE = /@Profile\(\s*\{?\s*['"]test['"]/;
const ADMIN_PATH = /["(]\s*"?\/api\/admin\//;
// High-sensitivity READ surfaces: AI observability (/api/ai/**) and IM (/api/im/**).
// A GET-only controller here that shadow-allows any logged-in user can leak other
// users' data (raw prompts, agent activity, tenant spend). Read endpoints used to be
// out of scope entirely (SEC-002); they now also need an explicit @RequirePermission
// or @AuthenticatedAccess decision.
const READ_MAPPING = /@GetMapping/;
const SENSITIVE_READ_PATH = /["'`]\/api\/(ai|im)\//;

// Strip block + line comments so a javadoc that merely MENTIONS an annotation
// ({@link RequirePermission}, "// gated by @AuthenticatedAccess") can never satisfy the
// guard/mapping regexes — only real code counts. The [^:] guard avoids eating http://.
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
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
for (const file of walk(SRC)) {
  const text = stripComments(fs.readFileSync(file, 'utf8'));
  if (!/@RestController|@Controller/.test(text)) continue;
  if (GUARD.test(text)) continue;                 // has a real @RequirePermission/@AuthenticatedAccess
  if (ADMIN_PATH.test(text)) continue;            // AdminRoleInterceptor covers /api/admin/**
  if (TEST_PROFILE.test(text)) continue;          // @Profile("test") controller — never wired in prod
  const hasWrite = WRITE_MAPPING.test(text);      // @Post/@Put/@Delete/@Patch surface
  const hasSensitiveRead = READ_MAPPING.test(text) && SENSITIVE_READ_PATH.test(text);
  if (!hasWrite && !hasSensitiveRead) continue;   // no surface that requires a decision
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

console.log(`[controller-authz] unguarded write/sensitive-read controllers: ${unguarded.length} (baseline ${baseline.length})`);
if (removed.length) {
  console.log(`[controller-authz] ${removed.length} baselined controller(s) now guarded/removed (good — prune baseline):`);
  removed.forEach((f) => console.log(`   - ${f}`));
}
if (added.length) {
  console.error(`\n❌ ${added.length} NEW unguarded write or sensitive-read (/api/ai|/api/im) controller(s) — add @RequirePermission/@AuthenticatedAccess, move under /api/admin, or (if intentionally accepted) --write-baseline with justification:`);
  added.forEach((f) => console.error(`   + ${f}`));
  process.exit(1);
}
console.log('✅ controller-authz check passed (no new fail-open write/sensitive-read controllers).');
