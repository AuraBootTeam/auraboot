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
import {
  declaredCommands,
  referencedCommandsInRoots,
} from './check-command-reachability.mjs';

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

// A browser golden is not always a .spec.ts. aura-quote drives Playwright from Python
// (browser-automation-runner-selection.md sanctions both), and iot/crawler ship .mjs
// goldens. Indexing only TypeScript reported every quote page as untested — a gap
// overstated by construction, which gets ignored exactly as fast as one understated.
const SPEC_EXTS = ['.spec.ts', '.py', '.mjs'];

/** Command code → E2E spec files mentioning it. Built once; the spec tree is big. */
function buildUiIndex(specRoots) {
  const index = new Map();
  for (const root of specRoots) {
    for (const file of walk(root, SPEC_EXTS)) {
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
  // Two shapes are in use and both are legal: config/pages/ as a directory of one file
  // per page, and config/pages.json as a single array. 26 plugins use the second form
  // and 50 the first; reading only the directory made those 26 contribute zero rows,
  // which reads as "this plugin has no pages" rather than "this plugin was not counted".
  // A denominator with a silent hole in it is the exact failure this file exists to stop.
  const dir = path.join(pluginDir, 'config', 'pages');
  const single = path.join(pluginDir, 'config', 'pages.json');
  const files = [
    ...(fs.existsSync(dir) && fs.statSync(dir).isDirectory() ? walk(dir, ['.json']) : []),
    ...(fs.existsSync(single) ? [single] : []),
  ];
  if (files.length === 0) return [];
  const pages = [];
  for (const file of files) {
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
export function buildPageIndex(specRoots) {
  const index = new Map();
  const add = (key, rel) => {
    if (!index.has(key)) index.set(key, []);
    if (!index.get(key).includes(rel)) index.get(key).push(rel);
  };
  for (const root of specRoots) {
    for (const file of walk(root, SPEC_EXTS)) {
      const text = fs.readFileSync(file, 'utf8');
      const rel = path.relative(process.cwd(), file);
      // bare page keys, and the two route shapes: /p/c/<pageKey> and /p/<model>.
      //
      // The closing class must accept ? and # : a page reached with parameters
      // appears as `/p/c/ai_colleague_detail?agentPid=${pid}`, and requiring a
      // quote or slash on the right reported both parameterised colleague pages
      // as untested while their specs sat right there. An understated denominator
      // gets ignored as fast as an overstated one.
      //
      // The opening class stays strict on purpose. Widening it to any boundary
      // would match prose — these very specs say "a DSL page (ai_colleague_chat)"
      // in their header comment — and a mention in a comment is not evidence.
      // False coverage is the worse failure of the two.
      for (const m of text.matchAll(/["'`\/]([a-z][a-z0-9_]{3,})["'`\/?#]/gi)) add(m[1], rel);
    }
  }
  return index;
}

export function buildModelRouteIndex(specRoots) {
  const index = new Map();
  const add = (key, rel) => {
    if (!index.has(key)) index.set(key, []);
    if (!index.get(key).includes(rel)) index.get(key).push(rel);
  };
  for (const root of specRoots) {
    for (const file of walk(root, SPEC_EXTS)) {
      const text = fs.readFileSync(file, 'utf8').replaceAll('\\/', '/');
      const rel = path.relative(process.cwd(), file);
      for (const match of text.matchAll(/\/p\/([a-z][a-z0-9_]+)(?:\/(new|view|edit))?(?=["'`/?#),\s])/gi)) {
        const kind = match[2] === 'view' ? 'detail' : match[2] ? 'form' : 'list';
        add(`${match[1]}:${kind}`, rel);
      }
    }
  }
  return index;
}

/**
 * Platform-native pages: hand-written React routes declared in a plugin's resources.ts.
 *
 * These never appeared in the denominator. The generator reads plugin DSL, and a
 * platform page is neither a DSL page nor a command — so the knowledge-base UI
 * (1250 lines, zero browser specs until 2026-07-25) produced no row at all. Not
 * "untested" — absent, which reads as "not applicable" and is exactly the failure
 * TEST-SYSTEM rule 1 is about.
 *
 * Only entries carrying a `file` count. A fileless entry is a menu pointer at a DSL
 * route (that is how the AI colleague pages were converted), and the DSL page rows
 * already cover those — counting both would double-count the same surface.
 *
 * Parsed from the source rather than from a running app's /actuator/mappings: a
 * generator that needs a live stack cannot run where the manifest is checked.
 */
export function declaredPlatformPages(webAdminRoot) {
  const dir = path.join(webAdminRoot, 'app', 'plugins');
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const plugin of fs.readdirSync(dir)) {
    const file = path.join(dir, plugin, 'resources.ts');
    if (!fs.existsSync(file)) continue;
    const text = fs.readFileSync(file, 'utf8');
    // Each resource is one { ... } block; key/path/file may sit on separate lines.
    for (const block of text.split(/\}\s*,?\s*(?=\{|\]$)/)) {
      const key = /key:\s*'([^']+)'/.exec(block)?.[1];
      const route = /path:\s*'([^']+)'/.exec(block)?.[1];
      const src = /file:\s*'([^']+)'/.exec(block)?.[1];
      if (key && route && src) out.push({ plugin, key, route, file: src });
    }
  }
  return out.sort((a, b) => a.key.localeCompare(b.key));
}

/**
 * Route → spec files that navigate to it.
 *
 * Bounded on the right the same way page keys are, so `/aurabot/knowledge` is not
 * credited with the coverage of `/aurabot/knowledge/:pid`, and a route opened with
 * parameters still counts.
 */
export function buildRouteIndex(specRoots) {
  const files = [];
  for (const root of specRoots) for (const f of walk(root, SPEC_EXTS)) files.push(f);
  const texts = files.map((f) => [path.relative(process.cwd(), f), fs.readFileSync(f, 'utf8')]);
  return (route) => {
    const hits = [];
    const esc = route.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`["'\`]${esc}(?=["'\`?#]|/\\$\\{)`);
    for (const [rel, text] of texts) if (re.test(text)) hits.push(rel);
    return hits;
  };
}

export function resolveConfiguredPath(repoRoot, configured, env = process.env) {
  const match = /^\$(?:\{([A-Z][A-Z0-9_]*)\}|([A-Z][A-Z0-9_]*))(?:\/(.*))?$/.exec(
    configured,
  );
  if (!match) return path.resolve(repoRoot, configured);
  const variable = match[1] ?? match[2];
  const root = env[variable];
  if (!root) {
    throw new Error(`coverage spec root requires ${variable}: ${configured}`);
  }
  return path.resolve(root, match[3] ?? '');
}

function gitCommit(root) {
  try {
    return execFileSync('git', ['-C', root, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch { return 'unknown'; }
}

export function buildManifest({
  repoRoot,
  pluginRoot,
  only,
  specRoots,
  runId,
  target,
  env = process.env,
}) {
  const absSpecRoots = specRoots.map((r) => resolveConfiguredPath(repoRoot, r, env));
  const uiIndex = buildUiIndex(absSpecRoots);
  const pageIndex = buildPageIndex(absSpecRoots);
  const modelRouteIndex = buildModelRouteIndex(absSpecRoots);
  const groups = [];
  let untested = 0;
  let total = 0;
  let pageTotal = 0;
  let pageUntested = 0;
  const referenced = referencedCommandsInRoots([pluginRoot]);

  const plugins = fs.readdirSync(pluginRoot).sort()
    .filter((e) => fs.statSync(path.join(pluginRoot, e)).isDirectory())
    .filter((e) => !only || e === only);

  for (const plugin of plugins) {
    const pluginDir = path.join(pluginRoot, plugin);
    const commands = declaredCommands(pluginDir);
    const pages = declaredPages(pluginDir);
    if (commands.length === 0 && pages.length === 0) continue;
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
      const evidence = new Set(pageIndex.get(p.pageKey) ?? []);
      if (p.modelCode && p.pageKey === `${p.modelCode}_${p.kind}`) {
        for (const file of modelRouteIndex.get(`${p.modelCode}:${p.kind}`) ?? []) {
          evidence.add(file);
        }
      }
      const evidenceFiles = [...evidence].sort();
      if (evidenceFiles.length === 0) pageUntested += 1;
      return {
        id: `page:${p.pageKey}`,
        action: p.pageKey,
        surface: 'ui',
        dependencies: 'real-stack',
        driver: 'browser',
        evidence: evidenceFiles,
        assertion: evidenceFiles.length > 0
          ? 'a browser spec reaches this page'
          : 'no browser spec names this page or its route',
        verdict: evidenceFiles.length > 0 ? 'pass' : 'untested',
        note: `kind=${p.kind}${p.modelCode ? ` model=${p.modelCode}` : ''}`,
      };
    });

    groups.push({ id: plugin, title: plugin, rows: [...rows, ...pageRows] });
  }

  // Platform-native pages. Kept in their own group and counted separately so the
  // report can answer "how covered is the DSL surface" and "how covered is the
  // hand-written surface" without one diluting the other.
  let platformTotal = 0;
  let platformUntested = 0;
  const webAdminRoot = path.join(repoRoot, 'web-admin');
  const platformPages = declaredPlatformPages(webAdminRoot);
  if (platformPages.length > 0) {
    const routeHits = buildRouteIndex(absSpecRoots);
    const platformRows = platformPages.map((pp) => {
      platformTotal += 1;
      const evidence = routeHits(pp.route);
      if (evidence.length === 0) platformUntested += 1;
      return {
        id: `platform-page:${pp.key}`,
        action: pp.route,
        surface: 'platform-page',
        dependencies: 'real-stack',
        driver: 'browser',
        evidence,
        assertion: evidence.length > 0
          ? 'a browser spec navigates to this route'
          : 'no browser spec navigates to this route',
        verdict: evidence.length > 0 ? 'pass' : 'untested',
        note: `plugin=${pp.plugin} file=${pp.file}`,
      };
    });
    groups.push({ id: 'platform-pages', title: 'platform-native pages (hand-written React)', rows: platformRows });
  }

  return {
    run: { id: runId, target, commit: gitCommit(repoRoot),
           generator: 'scripts/gen-coverage-manifest.mjs' },
    groups,
    stats: { commands: total, untested, pages: pageTotal, pagesUntested: pageUntested,
             platformPages: platformTotal, platformPagesUntested: platformUntested },
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
