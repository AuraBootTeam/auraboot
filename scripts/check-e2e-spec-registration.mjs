#!/usr/bin/env node
/**
 * Gate: no E2E spec may exist without being selectable by some project.
 *
 * Playwright projects that select specs by a name allowlist (`testMatch` built
 * from an array of spec names) fail OPEN: a spec that is not in the array
 * produces `No tests found` and exit 0. The spec is written, committed,
 * reviewed — and never runs. It looks like coverage from every angle except
 * the only one that matters.
 *
 * This is not hypothetical. `playwright.config.ts` carries a comment from
 * DDR-2026-06-29 §8 recording exactly this: per-role suites were listed on the
 * gate script's command line, the project's testMatch dropped them silently,
 * "the gate went green WITHOUT running them". That was fixed by typing the
 * names into the array. The structural cause was left in place, and eleven more
 * specs have since drifted into the same hole.
 *
 * So: orphans are an error, and they can only be silenced by an explicit
 * allowlist entry carrying a reason. Registry rot — a name with no file behind
 * it — is an error too, in the other direction.
 *
 *   node scripts/check-e2e-spec-registration.mjs
 *   node scripts/check-e2e-spec-registration.mjs --json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_CONFIG = 'scripts/e2e-spec-registration.json';

/** Comments must go before parsing: a `// 'foo'` line otherwise reads as an
 *  entry, and an entry inside a comment reads as registered. Both directions
 *  produce a wrong answer that looks right. */
export function stripLineComments(source) {
  return source
    .split('\n')
    .map((line) => line.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** The string literals of `const <name> = [ ... ]`, comments already removed. */
export function readNameArray(source, arrayName) {
  const cleaned = stripLineComments(source);
  const start = cleaned.search(new RegExp(`const\\s+${arrayName}\\s*=\\s*\\[`));
  if (start < 0) return null;
  const open = cleaned.indexOf('[', start);
  let depth = 0;
  let end = -1;
  for (let i = open; i < cleaned.length; i += 1) {
    if (cleaned[i] === '[') depth += 1;
    else if (cleaned[i] === ']') {
      depth -= 1;
      if (depth === 0) { end = i; break; }
    }
  }
  if (end < 0) return null;
  const body = cleaned.slice(open + 1, end);
  return [...new Set(
    [...body.matchAll(/'([^']+)'|"([^"]+)"/g)]
      .map((m) => (m[1] ?? m[2]).trim())
      .filter(Boolean),
  )];
}

function specNamesIn(dirAbs) {
  if (!fs.existsSync(dirAbs)) return null;
  return fs.readdirSync(dirAbs)
    .filter((f) => f.endsWith('.spec.ts'))
    .map((f) => f.slice(0, -'.spec.ts'.length))
    .sort();
}

export function auditRegistrations({ root, config }) {
  const findings = [];
  const summary = [];

  for (const reg of config.registries ?? []) {
    const dirAbs = path.join(root, reg.dir);
    const files = specNamesIn(dirAbs);
    if (files === null) {
      findings.push({ level: 'error', kind: 'missing-dir', registry: reg.dir,
        message: `registry directory does not exist: ${reg.dir}` });
      continue;
    }

    const cfgAbs = path.join(root, reg.configFile);
    if (!fs.existsSync(cfgAbs)) {
      findings.push({ level: 'error', kind: 'missing-config', registry: reg.dir,
        message: `config file does not exist: ${reg.configFile}` });
      continue;
    }
    const registered = readNameArray(fs.readFileSync(cfgAbs, 'utf8'), reg.arrayName);
    if (registered === null) {
      findings.push({ level: 'error', kind: 'missing-array', registry: reg.dir,
        message: `array ${reg.arrayName} not found in ${reg.configFile}` });
      continue;
    }

    const allow = config.allow?.[reg.dir] ?? {};
    const registeredSet = new Set(registered);
    const fileSet = new Set(files);

    for (const spec of files) {
      if (registeredSet.has(spec)) continue;
      if (Object.prototype.hasOwnProperty.call(allow, spec)) {
        if (!String(allow[spec] ?? '').trim()) {
          findings.push({ level: 'error', kind: 'allow-without-reason', registry: reg.dir, spec,
            message: `${spec}: allowlisted with an empty reason — say why it does not run` });
        }
        continue;
      }
      // Pre-existing orphans are recorded as debt rather than excused. They
      // still print, and the baseline file is the triage worklist; what the
      // gate blocks is a NEW spec drifting into the same hole.
      const baselined = (config.baseline?.[reg.dir] ?? []).includes(spec);
      findings.push({
        level: baselined ? 'warn' : 'error',
        kind: baselined ? 'orphan-baselined' : 'orphan',
        registry: reg.dir,
        spec,
        message: `${spec}.spec.ts exists but project "${reg.project}" can never select it `
          + `(not in ${reg.arrayName}); it runs as "No tests found" + exit 0`
          + (baselined ? ' [pre-existing at gate introduction — awaiting triage]' : ''),
      });
    }

    for (const name of registered) {
      if (fileSet.has(name)) continue;
      findings.push({ level: 'error', kind: 'rot', registry: reg.dir, spec: name,
        message: `${reg.arrayName} lists "${name}" but ${reg.dir}/${name}.spec.ts does not exist` });
    }

    for (const spec of (config.baseline?.[reg.dir] ?? [])) {
      if (fileSet.has(spec)) continue;
      findings.push({ level: 'warn', kind: 'stale-baseline', registry: reg.dir, spec,
        message: `baseline lists "${spec}" but the file is gone; drop it — the debt is resolved` });
    }

    for (const spec of Object.keys(allow)) {
      if (fileSet.has(spec)) continue;
      findings.push({ level: 'warn', kind: 'stale-allow', registry: reg.dir, spec,
        message: `allowlist entry "${spec}" has no file; drop it` });
    }

    summary.push({ registry: reg.dir, project: reg.project,
      files: files.length, registered: registered.length,
      allowed: Object.keys(allow).length });
  }

  return { findings, summary };
}

function loadConfig(root, rel) {
  const abs = path.join(root, rel);
  if (!fs.existsSync(abs)) throw new Error(`missing config: ${rel}`);
  return JSON.parse(fs.readFileSync(abs, 'utf8'));
}

function main(argv) {
  const root = path.resolve(HERE, '..');
  const asJson = argv.includes('--json');
  const config = loadConfig(root, DEFAULT_CONFIG);
  const { findings, summary } = auditRegistrations({ root, config });

  if (argv.includes('--update-baseline')) {
    const baseline = {};
    for (const f of findings) {
      if (f.kind !== 'orphan' && f.kind !== 'orphan-baselined') continue;
      (baseline[f.registry] ??= []).push(f.spec);
    }
    for (const k of Object.keys(baseline)) baseline[k].sort();
    fs.writeFileSync(path.join(root, DEFAULT_CONFIG), `${JSON.stringify({ ...config, baseline }, null, 2)}\n`);
    const total = Object.values(baseline).reduce((n, v) => n + v.length, 0);
    console.log(`[spec-registration] baseline written: ${total} orphan spec(s) recorded as debt`);
    console.log('This is a triage worklist, not an approval. New orphans still fail.');
    return 0;
  }

  if (asJson) {
    console.log(JSON.stringify({ summary, findings }, null, 2));
  } else {
    for (const s of summary) {
      console.log(`[spec-registration] ${s.registry} (project ${s.project}): `
        + `${s.files} spec files, ${s.registered} registered, ${s.allowed} allowlisted`);
    }
    for (const f of findings) {
      console.log(`  ${f.level === 'error' ? 'ERROR' : 'WARN '} ${f.kind}: ${f.message}`);
    }
  }

  const errors = findings.filter((f) => f.level === 'error');
  if (errors.length > 0) {
    console.error(`\n[spec-registration] FAIL — ${errors.length} error(s).`);
    console.error('An unregistered spec is not a slow test; it is a test that never runs.');
    console.error(`Register it, or add it to "allow" in ${DEFAULT_CONFIG} with a reason.`);
    return 1;
  }
  console.log('[spec-registration] PASS');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
