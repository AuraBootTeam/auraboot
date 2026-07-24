#!/usr/bin/env node
/**
 * Generate a coverage manifest from the DSL and the test tree.
 *
 * The point is the denominator. A hand-written matrix lists what someone
 * remembered to write down; it drifts, and what falls out of it becomes
 * invisible rather than red. The quote/bom matrix ships its own re-verification
 * commands and was still 36–40% out of date after eight days.
 *
 * So every declared command gets a row, including the ones nothing tests —
 * `verdict: untested`, `evidence: []`. An uncovered action that is absent from
 * the matrix reads as "not applicable"; one that is present and marked untested
 * reads as work.
 *
 * Rows are described by the four axes, not by the retired `layer` enum, which
 * had no `unit` and so could not represent unit coverage at all
 * (auraboot-enterprise/docs/standards/core/testing-layering.md, DDR-2026-07-22).
 *
 *   node scripts/gen-coverage-manifest.mjs --plugin-root plugins --out docs/coverage/oss-coverage-manifest.json
 *   node scripts/gen-coverage-manifest.mjs --plugin-root ../plugins --plugin bom-standardization --out /tmp/bom.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { declaredCommands, referencedCommands } from './check-command-reachability.mjs';

import { resolveRepoRoot } from './lib/repo-root.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Where a command's coverage could live, and what each location means in axes. */
const PROBES = [
  { key: 'backend-unit', dirs: ['backend/src/test'], exts: ['.java'],
    axes: { surface: 'service', dependencies: 'hermetic', driver: 'unit' } },
  { key: 'backend-it', dirs: ['backend/src/test'], exts: ['.java'], itOnly: true,
    axes: { surface: 'service', dependencies: 'real-stack', driver: 'it' } },
  { key: 'contract', dirs: ['tests'], exts: ['.mjs', '.test.mjs'],
    axes: { surface: 'contract', dependencies: 'hermetic', driver: 'unit' } },
];

function walk(dir, exts, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const name of fs.readdirSync(dir)) {
    const abs = path.join(dir, name);
    const st = fs.statSync(abs);
    if (st.isDirectory()) walk(abs, exts, out);
    else if (exts.some((e) => abs.endsWith(e))) out.push(abs);
  }
  return out;
}

/** Files mentioning a command code, grouped by what kind of test they are. */
function coverageFor(pluginDir, code, uiIndex) {
  const hits = [];
  for (const probe of PROBES) {
    for (const rel of probe.dirs) {
      for (const file of walk(path.join(pluginDir, rel), probe.exts)) {
        const isIT = /IT\.java$/.test(file);
        if (probe.itOnly && !isIT) continue;
        if (!probe.itOnly && isIT) continue;
        if (!fs.readFileSync(file, 'utf8').includes(code)) continue;
        hits.push({ probe: probe.key, axes: probe.axes, file: path.relative(pluginDir, file) });
      }
    }
  }
  for (const file of uiIndex.get(code) ?? []) {
    hits.push({ probe: 'web-e2e', file,
      axes: { surface: 'ui', dependencies: 'real-stack', driver: 'browser' } });
  }
  return hits;
}

/** Command code → E2E spec files mentioning it. Built once; the spec tree is big. */
function buildUiIndex(specRoots) {
  const index = new Map();
  for (const root of specRoots) {
    for (const file of walk(root, ['.spec.ts'])) {
      const text = fs.readFileSync(file, 'utf8');
      for (const m of text.matchAll(/["'`]([a-z0-9_-]+:[a-z0-9_]+)["'`]/gi)) {
        const rel = path.relative(process.cwd(), file);
        if (!index.has(m[1])) index.set(m[1], []);
        if (!index.get(m[1]).includes(rel)) index.get(m[1]).push(rel);
      }
    }
  }
  return index;
}

/**
 * Every page a plugin declares, so the page-level denominator is generated too.
 *
 * Page coverage used to live in hand-written GOLDEN-UI-COVERAGE-MATRIX.md files —
 * 29 of them across the workspace, no generator, no gate, one still citing a Docker
 * image retired months ago. A hand-written matrix lists what someone remembered; a
 * page missing from it reads as "not applicable" rather than as work. Same reasoning
 * that put commands in this manifest applies to pages.
 */
function declaredPages(pluginDir) {
  const dir = path.join(pluginDir, 'config', 'pages');
  if (!fs.existsSync(dir)) return [];
  const pages = [];
  for (const file of walk(dir, ['.json'])) {
    let j;
    try { j = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { continue; }
    for (const p of Array.isArray(j) ? j : [j]) {
      if (p && typeof p.pageKey === 'string') {
        pages.push({ pageKey: p.pageKey, kind: p.kind ?? 'unknown', modelCode: p.modelCode ?? null });
      }
    }
  }
  return pages.sort((a, b) => a.pageKey.localeCompare(b.pageKey));
}

/**
 * Page key → spec files that navigate to or name it.
 *
 * A spec reaches a page either by naming its key or by visiting its route. Both forms
 * are indexed because matching only the key would call every route-driven golden
 * "untested", and a denominator that under-counts coverage is as misleading as one
 * that over-counts it.
 */
function buildPageIndex(specRoots) {
  const index = new Map();
  const add = (key, rel) => {
    if (!index.has(key)) index.set(key, []);
    if (!index.get(key).includes(rel)) index.get(key).push(rel);
  };
  for (const root of specRoots) {
    for (const file of walk(root, ['.spec.ts'])) {
      const text = fs.readFileSync(file, 'utf8');
      const rel = path.relative(process.cwd(), file);
      // bare page keys, and the two route shapes: /p/c/<pageKey> and /p/<model>
      for (const m of text.matchAll(/["'`\/]([a-z][a-z0-9_]{3,})["'`\/]/gi)) add(m[1], rel);
    }
  }
  return index;
}

function gitCommit(root) {
  try {
    return execFileSync('git', ['-C', root, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch { return 'unknown'; }
}

export function buildManifest({ repoRoot, pluginRoot, only, specRoots, runId, target }) {
  const absSpecRoots = specRoots.map((r) => path.resolve(repoRoot, r));
  const uiIndex = buildUiIndex(absSpecRoots);
  const pageIndex = buildPageIndex(absSpecRoots);
  const groups = [];
  let untested = 0;
  let total = 0;
  let pageTotal = 0;
  let pageUntested = 0;

  const plugins = fs.readdirSync(pluginRoot).sort()
    .filter((e) => fs.statSync(path.join(pluginRoot, e)).isDirectory())
    .filter((e) => !only || e === only);

  for (const plugin of plugins) {
    const pluginDir = path.join(pluginRoot, plugin);
    const commands = declaredCommands(pluginDir);
    const pages = declaredPages(pluginDir);
    if (commands.length === 0 && pages.length === 0) continue;
    const referenced = referencedCommands(pluginDir);

    const rows = commands.sort().map((code) => {
      const hits = coverageFor(pluginDir, code, uiIndex);
      total += 1;

      // The strongest evidence present decides how the row is described: a
      // command exercised through the browser is a `ui/real-stack/browser`
      // row even if it also has a unit test.
      const order = ['web-e2e', 'backend-it', 'backend-unit', 'contract'];
      const best = order.map((k) => hits.find((h) => h.probe === k)).find(Boolean);
      const reachable = referenced.has(code);
      const hasUi = hits.some((h) => h.probe === 'web-e2e');

      let verdict = 'untested';
      if (hits.length > 0) verdict = hasUi ? 'pass' : 'partial';
      if (verdict === 'untested') untested += 1;

      return {
        id: code,
        action: code,
        ...(best?.axes ?? { surface: 'ui', dependencies: 'real-stack', driver: 'browser' }),
        evidence: hits.map((h) => h.file),
        assertion: hasUi
          ? 'driven through the UI'
          : hits.length > 0
            ? 'covered below the UI only — no browser evidence'
            : 'no test references this command',
        verdict,
        ...(reachable ? {} : { note: 'no UI entry point: no page/menu DSL references it' }),
      };
    });

    const pageRows = pages.map((p) => {
      pageTotal += 1;
      const evidence = pageIndex.get(p.pageKey) ?? [];
      if (evidence.length === 0) pageUntested += 1;
      return {
        id: `page:${p.pageKey}`,
        action: p.pageKey,
        surface: 'ui',
        dependencies: 'real-stack',
        driver: 'browser',
        evidence,
        assertion: evidence.length > 0
          ? 'a browser spec reaches this page'
          : 'no browser spec names this page or its route',
        verdict: evidence.length > 0 ? 'pass' : 'untested',
        note: `kind=${p.kind}${p.modelCode ? ` model=${p.modelCode}` : ''}`,
      };
    });

    groups.push({ id: plugin, title: plugin, rows: [...rows, ...pageRows] });
  }

  return {
    run: { id: runId, target, commit: gitCommit(repoRoot),
           generator: 'scripts/gen-coverage-manifest.mjs' },
    groups,
    stats: { commands: total, untested, pages: pageTotal, pagesUntested: pageUntested },
  };
}

function arg(argv, name, fallback = null) {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : fallback;
}

function main(argv) {
  const repoRoot = resolveRepoRoot(argv, path.resolve(HERE, '..'));
  const pluginRoot = path.resolve(repoRoot, arg(argv, '--plugin-root', 'plugins'));
  const out = arg(argv, '--out');
  const only = arg(argv, '--plugin');
  const specRoots = (arg(argv, '--spec-root', 'web-admin/tests/e2e') ?? '').split(',').filter(Boolean);
  if (!out) { console.error('usage: --out <path> [--plugin-root d] [--plugin name] [--spec-root a,b]'); return 2; }

  const manifest = buildManifest({
    repoRoot, pluginRoot, only, specRoots,
    runId: `coverage-${path.basename(pluginRoot)}${only ? `-${only}` : ''}`,
    target: path.relative(repoRoot, pluginRoot) || pluginRoot,
  });

  fs.mkdirSync(path.dirname(path.resolve(repoRoot, out)), { recursive: true });
  fs.writeFileSync(path.resolve(repoRoot, out), `${JSON.stringify(manifest, null, 2)}\n`);
  const { commands, untested, pages, pagesUntested } = manifest.stats;
  const pct = (n, d) => `${Math.round((n / Math.max(1, d)) * 100)}%`;
  console.log(`[coverage-manifest] ${out}: ${manifest.groups.length} plugin(s), `
    + `${commands} command(s), ${untested} untested (${pct(untested, commands)}); `
    + `${pages} page(s), ${pagesUntested} untested (${pct(pagesUntested, pages)})`);
  console.log('Untested rows are in the file, not omitted — they are the denominator.');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
