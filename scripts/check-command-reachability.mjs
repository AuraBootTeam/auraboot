#!/usr/bin/env node
/**
 * Gate: a declared command must have a way for a user to reach it.
 *
 * A command can be perfectly declared — display name, permission code, input
 * fields, a working handler, passing unit tests — and still be unreachable,
 * because no page DSL references it. Every existing gate stays green: the
 * command is valid, the pages are valid, nothing is malformed. The only thing
 * missing is a button.
 *
 * Found the hard way: `bom:create_material` ("新增物料", permission
 * bom.library.manage, twelve input fields) appears nowhere in its plugin's
 * pages.json. It could only ever be invoked through the API, so its UI coverage
 * was not merely missing — it was unreachable, and no amount of E2E writing
 * would have covered it.
 *
 * This is the design-level root of "UI coverage is incomplete": you cannot test
 * a button that does not exist.
 *
 *   node scripts/check-command-reachability.mjs
 *   node scripts/check-command-reachability.mjs --plugin-root ../plugins
 *   node scripts/check-command-reachability.mjs --json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_CONFIG = 'scripts/command-reachability.json';

/** Commands whose reachability is not a UI question. Matched as prefixes on the
 *  part after the plugin namespace, e.g. `seed_defaults` for `bom:seed_defaults`. */
export const DEFAULT_EXEMPT_SUFFIXES = ['seed_', 'internal_'];

function readJson(abs) {
  return JSON.parse(fs.readFileSync(abs, 'utf8'));
}

function asList(doc, key) {
  if (Array.isArray(doc)) return doc;
  const v = doc?.[key];
  return Array.isArray(v) ? v : [];
}

/** Every command code declared by a plugin. */
export function declaredCommands(pluginDir) {
  const abs = path.join(pluginDir, 'config', 'commands.json');
  if (!fs.existsSync(abs)) return [];
  return asList(readJson(abs), 'commands')
    .map((c) => c?.code)
    .filter((c) => typeof c === 'string' && c.length > 0);
}

/**
 * Whether a command code appears anywhere in the plugin's page/menu DSL.
 *
 * Deliberately a substring search over the raw JSON rather than a structured
 * walk of known shapes. The DSL reaches commands through several unrelated
 * shapes — `action.command`, `onClick.args.code`, rowActions, form-buttons,
 * toolbars — and that list has grown before. A structured matcher that knows
 * four of five shapes reports "unreachable" for a command that is reachable,
 * which is the failure mode that gets a gate switched off.
 */
export function referencedCommands(pluginDir) {
  const found = new Set();
  const dir = path.join(pluginDir, 'config');
  if (!fs.existsSync(dir)) return found;

  // Both shapes are in use and the directory form is easy to miss: workflow-demo
  // keeps one JSON per page under config/pages/, so a scanner that only opens
  // config/pages.json calls nineteen reachable commands unreachable. That false
  // positive rate is how a gate gets switched off.
  const targets = [];
  const collect = (abs) => {
    if (!fs.existsSync(abs)) return;
    if (fs.statSync(abs).isDirectory()) {
      for (const name of fs.readdirSync(abs)) collect(path.join(abs, name));
    } else if (abs.endsWith('.json')) {
      targets.push(abs);
    }
  };
  for (const base of ['pages.json', 'pages', 'menus.json', 'menus']) collect(path.join(dir, base));

  for (const abs of targets) {
    const text = fs.readFileSync(abs, 'utf8');
    for (const m of text.matchAll(/"([a-z0-9_-]+:[a-z0-9_]+)"/gi)) found.add(m[1]);
  }
  return found;
}

function isExempt(code, exemptSuffixes) {
  const local = code.includes(':') ? code.slice(code.indexOf(':') + 1) : code;
  return exemptSuffixes.some((p) => local.startsWith(p));
}

export function auditReachability({ roots, config }) {
  const findings = [];
  const summary = [];
  const exempt = config.exemptPrefixes ?? DEFAULT_EXEMPT_SUFFIXES;

  for (const root of roots) {
    if (!fs.existsSync(root)) {
      findings.push({ level: 'error', kind: 'missing-root', root,
        message: `plugin root does not exist: ${root}` });
      continue;
    }
    for (const entry of fs.readdirSync(root).sort()) {
      const pluginDir = path.join(root, entry);
      if (!fs.statSync(pluginDir).isDirectory()) continue;
      const declared = declaredCommands(pluginDir);
      if (declared.length === 0) continue;

      const referenced = referencedCommands(pluginDir);
      const allow = config.allow?.[entry] ?? {};
      let unreachable = 0;

      for (const code of declared) {
        if (referenced.has(code) || isExempt(code, exempt)) continue;
        if (Object.prototype.hasOwnProperty.call(allow, code)) {
          if (!String(allow[code] ?? '').trim()) {
            findings.push({ level: 'error', kind: 'allow-without-reason', plugin: entry, code,
              message: `${code}: allowlisted with an empty reason — say why it has no UI entry` });
          }
          continue;
        }
        // Pre-existing debt is recorded, not excused: it still shows up in the
        // report and in the baseline file, but it does not block. Writing 57
        // individual justifications nobody researched would be worse than
        // saying plainly that they were inherited.
        const baselined = (config.baseline?.[entry] ?? []).includes(code);
        unreachable += 1;
        findings.push({
          level: baselined ? 'warn' : 'error',
          kind: baselined ? 'unreachable-baselined' : 'unreachable',
          plugin: entry,
          code,
          message: `${code} is declared but no page/menu DSL in ${entry} references it `
            + '— no UI entry point, so it can only be called through the API'
            + (baselined ? ' [pre-existing at gate introduction]' : ''),
        });
      }

      for (const code of (config.baseline?.[entry] ?? [])) {
        if (declared.includes(code)) continue;
        findings.push({ level: 'warn', kind: 'stale-baseline', plugin: entry, code,
          message: `baseline lists "${code}" but it is no longer declared; drop it — the debt is gone` });
      }

      for (const code of Object.keys(allow)) {
        if (declared.includes(code)) continue;
        findings.push({ level: 'warn', kind: 'stale-allow', plugin: entry, code,
          message: `allowlist entry "${code}" is no longer a declared command; drop it` });
      }

      summary.push({ plugin: entry, declared: declared.length, unreachable,
        allowed: Object.keys(allow).length });
    }
  }
  return { findings, summary };
}

function main(argv) {
  const repoRoot = path.resolve(HERE, '..');
  const asJson = argv.includes('--json');
  const rootFlag = argv.indexOf('--plugin-root');
  const cfgAbs = path.join(repoRoot, DEFAULT_CONFIG);
  const config = fs.existsSync(cfgAbs) ? readJson(cfgAbs) : {};
  const roots = rootFlag >= 0
    ? [path.resolve(repoRoot, argv[rootFlag + 1])]
    : (config.roots ?? ['plugins']).map((r) => path.resolve(repoRoot, r));

  const { findings, summary } = auditReachability({ roots, config });

  if (argv.includes('--update-baseline')) {
    const baseline = {};
    for (const f of findings) {
      if (f.kind !== 'unreachable' && f.kind !== 'unreachable-baselined') continue;
      (baseline[f.plugin] ??= []).push(f.code);
    }
    for (const k of Object.keys(baseline)) baseline[k].sort();
    const next = { ...config, baseline };
    fs.writeFileSync(cfgAbs, `${JSON.stringify(next, null, 2)}\n`);
    const total = Object.values(baseline).reduce((n, v) => n + v.length, 0);
    console.log(`[command-reachability] baseline written: ${total} pre-existing across ${Object.keys(baseline).length} plugin(s)`);
    console.log('This is a debt list, not an approval. New unreachable commands still fail.');
    return 0;
  }

  if (asJson) {
    console.log(JSON.stringify({ summary, findings }, null, 2));
  } else {
    const withCommands = summary.filter((s) => s.declared > 0);
    console.log(`[command-reachability] scanned ${withCommands.length} plugin(s) declaring commands`);
    for (const f of findings) {
      console.log(`  ${f.level === 'error' ? 'ERROR' : 'WARN '} ${f.kind}: ${f.message}`);
    }
  }

  const errors = findings.filter((f) => f.level === 'error');
  if (errors.length > 0) {
    console.error(`\n[command-reachability] FAIL — ${errors.length} error(s).`);
    console.error('A command with no UI entry point cannot have UI coverage — you cannot test a button that does not exist.');
    console.error(`Add an entry point, or record the exception in ${DEFAULT_CONFIG} with a reason.`);
    return 1;
  }
  console.log('[command-reachability] PASS');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
