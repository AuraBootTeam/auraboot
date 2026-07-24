#!/usr/bin/env node
/**
 * Permission v2 capability-code drift gate.
 *
 * For every plugin's config/capabilities.json, verifies that each capability's `includes[]`
 * resolves to a real permission code — either declared in the SAME plugin's config/permissions.json
 * or an auto-generated model permission (`model.<model>.<action>`). Also checks capability codes are
 * unique and that `unmasksFields` entries look like `model.field`. Prevents a capability from
 * pointing at a ghost permission code (which would silently grant nothing).
 *
 * Usage:
 *   node scripts/check-capability-codes.mjs [--root <pluginsDir>]
 * Scans `<root>/* /config/capabilities.json`. Default root: ./plugins, else ./ if it holds
 * plugin dirs directly (the plugins repo layout). Exits 1 on any violation.
 */
import fs from 'node:fs';
import path from 'node:path';

// The seven CRUD-ish actions the platform always generates for a model.
const CRUD_ACTIONS = /^model\.[a-z0-9_]+\.(read|create|update|delete|manage|export|import)$/i;

// A model permission is `model.<modelCode>.<action>` where <action> comes from a
// declared command — see TestSeedController: "model." + modelCode + "." + action. The
// action set is therefore whatever the commands declare, not a fixed seven. Allowing
// only the seven flagged six legitimate codes (model.tenant_member.approve, .suspend,
// …) whose commands exist as admin:approve_member and friends.
function declaredModelActions(roots) {
  const out = new Set();
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    for (const dir of fs.readdirSync(root)) {
      const f = path.join(root, dir, 'config', 'commands.json');
      if (!fs.existsSync(f)) continue;
      let j; try { j = readJson(f); } catch { continue; }
      for (const c of Array.isArray(j) ? j : (j.commands || [])) {
        const model = c.modelCode;
        const code = c.code || c.commandCode || '';
        const action = code.includes(':') ? code.split(':').pop() : code;
        if (model && action) out.add(`${model}.${action}`);
      }
    }
  }
  return out;
}

// Codes the PLATFORM declares. A plugin capability may legitimately include
// meta.menu.read or bpm.process.read; requiring them in the plugin's own
// permissions.json flagged 22 codes that exist and work. Read from the constants class
// and the tenant bootstrap template rather than re-listing them here, so the gate
// cannot drift from the platform.
function platformPermissionCodes(repoRoot) {
  const out = new Set();
  const files = [
    path.join(repoRoot, 'platform/src/main/java/com/auraboot/framework/permission/constants/MetaPermission.java'),
    path.join(repoRoot, 'platform/src/main/resources/tenant-templates/default-bootstrap.json'),
  ];
  for (const f of files) {
    if (!fs.existsSync(f)) continue;
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"/gi)) out.add(m[1]);
  }
  return out;
}

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

function resolveRoot() {
  const explicit = arg('--root', null);
  if (explicit) return explicit;
  if (fs.existsSync(path.join(process.cwd(), 'plugins'))) return path.join(process.cwd(), 'plugins');
  return process.cwd();
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function pluginDirs(root) {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => path.join(root, d.name))
    .filter((d) => fs.existsSync(path.join(d, 'config', 'capabilities.json')));
}

const root = resolveRoot();
// The repo root is the parent of the plugin root, so platform/ resolves from it.
const repoRoot = path.resolve(root, '..');
const platformCodes = platformPermissionCodes(repoRoot);
const modelActions = declaredModelActions([root]);
const violations = [];
const seenCapCodes = new Set();
let plugins = 0;
let capabilities = 0;

for (const dir of pluginDirs(root)) {
  plugins++;
  const name = path.basename(dir);
  const caps = readJson(path.join(dir, 'config', 'capabilities.json'));
  const permFile = path.join(dir, 'config', 'permissions.json');
  const permCodes = fs.existsSync(permFile)
    ? new Set(readJson(permFile).map((p) => p.code))
    : new Set();

  for (const cap of caps) {
    capabilities++;
    if (!cap.code || !Array.isArray(cap.includes) || cap.includes.length === 0) {
      violations.push(`${name}: capability missing code or non-empty includes[]: ${JSON.stringify(cap.code)}`);
      continue;
    }
    if (seenCapCodes.has(cap.code)) {
      violations.push(`${name}: duplicate capability code '${cap.code}'`);
    }
    seenCapCodes.add(cap.code);

    for (const code of cap.includes) {
      if (permCodes.has(code) || platformCodes.has(code) || CRUD_ACTIONS.test(code)) continue;
      const m = /^model\.([a-z0-9_]+)\.([a-z0-9_]+)$/i.exec(code);
      if (m && modelActions.has(`${m[1]}.${m[2]}`)) continue;
      violations.push(`${name}: capability '${cap.code}' includes ghost permission code '${code}' — not in this plugin's permissions.json, not declared by the platform, and not model.<model>.<action> for any declared command`);
    }
    for (const f of cap.unmasksFields || []) {
      if (!/^[a-z0-9_]+\.[a-z0-9_]+$/i.test(f)) {
        violations.push(`${name}: capability '${cap.code}' unmasksFields '${f}' is not 'model.field'`);
      }
    }
  }
}

if (violations.length > 0) {
  console.error(`✗ capability-code gate: ${violations.length} violation(s)`);
  for (const v of violations) console.error(`  - ${v}`);
  process.exit(1);
}
console.log(`✓ capability-code gate: ${capabilities} capabilities across ${plugins} plugin(s), all includes resolve (root=${root})`);
