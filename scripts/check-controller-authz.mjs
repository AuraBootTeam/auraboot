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
const ADMIN_PATH = /["(]\s*"?\/api\/admin\//;

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
  const text = fs.readFileSync(file, 'utf8');
  if (!/@RestController|@Controller/.test(text)) continue;
  if (!WRITE_MAPPING.test(text)) continue;       // no write surface
  if (GUARD.test(text)) continue;                 // has a @RequirePermission somewhere
  if (ADMIN_PATH.test(text)) continue;            // AdminRoleInterceptor covers /api/admin/**
  unguarded.push(path.relative(REPO, file));
}
unguarded.sort();

if (WRITE) {
  fs.writeFileSync(BASELINE, JSON.stringify(unguarded, null, 2) + '\n');
  console.log(`[controller-authz] baseline written: ${unguarded.length} unguarded write controllers`);
  process.exit(0);
}

let baseline = [];
try { baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8')); } catch {
  console.error(`[controller-authz] missing baseline ${BASELINE} — run with --write-baseline first`);
  process.exit(2);
}
const baseSet = new Set(baseline);
const curSet = new Set(unguarded);
const added = unguarded.filter((f) => !baseSet.has(f));
const removed = baseline.filter((f) => !curSet.has(f));

if (JSON_OUT) {
  console.log(JSON.stringify({ total: unguarded.length, baseline: baseline.length, added, removed }, null, 2));
}

console.log(`[controller-authz] unguarded write controllers: ${unguarded.length} (baseline ${baseline.length})`);
if (removed.length) {
  console.log(`[controller-authz] ${removed.length} baselined controller(s) now guarded/removed (good — prune baseline):`);
  removed.forEach((f) => console.log(`   - ${f}`));
}
if (added.length) {
  console.error(`\n❌ ${added.length} NEW unguarded write controller(s) — add @RequirePermission, move under /api/admin, or (if genuinely self-scoped) --write-baseline with justification:`);
  added.forEach((f) => console.error(`   + ${f}`));
  process.exit(1);
}
console.log('✅ controller-authz check passed (no new fail-open write controllers).');
