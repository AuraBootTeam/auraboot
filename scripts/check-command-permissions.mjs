#!/usr/bin/env node
/**
 * A command whose handler stage does real work must declare the permission that authorizes it.
 *
 * Why this matters is the opposite of what it looks like. Since DDR-2026-07-22, a handler inherits
 * the authority its command boundary granted — but ONLY from an AUTHORIZED verdict. A command that
 * declares no permissions produces NOT_APPLICABLE, so its handler runs with no authority at all and
 * is left exposed to the failure that motivated the whole change: the handler cannot update the rows
 * it just created, and the writer silently duplicates them (production, 2026-07-22).
 *
 * So this gate is about COVERAGE of the fix, not containment of a risk. An undeclared command is not
 * dangerous — it is simply left behind, silently, with nothing in the codebase saying so.
 *
 * "Handler stage does real work" means either:
 *   - the command declares `handler` (a PF4J plugin handler), or
 *   - a bindingRule with a `handlerClass` targets it (a Spring bean handler).
 * Purely declarative commands — the large majority — run no handler and are out of scope.
 *
 * Born green: existing violations live in command-permissions-baseline.json and the baseline is a
 * RATCHET — it may only shrink. A gate that starts red gets ignored and then hides the real failures
 * behind it, so known debt is listed explicitly instead.
 */
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'fs';
import { resolve, dirname, join, relative } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..');
const pluginsRoot = join(repoRoot, 'plugins');
const baselinePath = join(__dirname, 'command-permissions-baseline.json');

const updateBaseline = process.argv.includes('--update-baseline');

function walkJson(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) walkJson(full, out);
    else if (entry.endsWith('.json')) out.push(full);
  }
  return out;
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return null; // not our file to validate; the import validator owns malformed JSON
  }
}

function asList(value) {
  return Array.isArray(value) ? value : value == null ? [] : [value];
}

const files = walkJson(pluginsRoot);

/** command code -> { file, declaresPermissions, declaresHandler } */
const commands = new Map();
/** command codes targeted by a bindingRule that names a handlerClass */
const handlerBound = new Set();

for (const file of files) {
  const base = file.split('/').pop();
  const doc = readJson(file);
  if (!doc) continue;

  if (base.startsWith('commands') || file.includes('/commands/')) {
    for (const command of asList(doc)) {
      if (!command || typeof command !== 'object' || !command.code) continue;
      if (commands.has(command.code)) continue;
      commands.set(command.code, {
        file: relative(repoRoot, file),
        declaresPermissions: Array.isArray(command.permissions) && command.permissions.length > 0,
        declaresHandler: Boolean(command.handler),
      });
    }
  }

  if (base.startsWith('bindingRules')) {
    for (const rule of asList(doc)) {
      if (!rule || typeof rule !== 'object') continue;
      if (rule.ruleType !== 'handler' && !rule.handlerClass) continue;
      const target = rule.commandCode || rule.command;
      if (target) handlerBound.add(target);
    }
  }
}

const violations = [];
for (const [code, info] of commands) {
  const runsAHandler = info.declaresHandler || handlerBound.has(code);
  if (runsAHandler && !info.declaresPermissions) {
    violations.push({ code, file: info.file });
  }
}
violations.sort((a, b) => a.code.localeCompare(b.code));

if (updateBaseline) {
  writeFileSync(baselinePath, `${JSON.stringify({
    _comment: 'Commands whose handler stage runs but which declare no permissions. RATCHET: this '
      + 'list may only shrink. Each entry is a command that cannot inherit its boundary authority '
      + '(DDR-2026-07-22) and is therefore still exposed to the write-then-read-back failure.',
    commands: violations.map((v) => v.code),
  }, null, 2)}\n`);
  console.log(`baseline updated: ${violations.length} known undeclared handler command(s)`);
  process.exit(0);
}

const baseline = existsSync(baselinePath)
  ? new Set(readJson(baselinePath)?.commands ?? [])
  : new Set();

const unlisted = violations.filter((v) => !baseline.has(v.code));
const violationCodes = new Set(violations.map((v) => v.code));
const fixed = [...baseline].filter((code) => !violationCodes.has(code)).sort();

let failed = false;

if (unlisted.length > 0) {
  failed = true;
  console.error('command permissions: FAIL — a handler command declares no permissions');
  for (const v of unlisted) {
    console.error(`  - ${v.code}  (${v.file})`);
  }
  console.error('\n  Its handler will run with no authority, so it cannot update the rows it');
  console.error('  creates (DDR-2026-07-22). Declare the permission its callers already hold.');
}

if (fixed.length > 0) {
  failed = true;
  console.error(`\ncommand permissions: FAIL — ${fixed.length} baselined command(s) now declare permissions.`);
  console.error('  The ratchet must tighten: remove them from the baseline.');
  for (const code of fixed) console.error(`  - ${code}`);
  console.error('\n  Run: node scripts/check-command-permissions.mjs --update-baseline');
}

if (failed) process.exit(1);

console.log(
  `command permissions: PASS (${commands.size} commands, `
  + `${violations.length} known undeclared handler command(s) in the baseline)`,
);
