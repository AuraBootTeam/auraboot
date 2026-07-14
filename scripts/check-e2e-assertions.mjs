#!/usr/bin/env node
/**
 * check-e2e-assertions — an assertion that something is ABSENT must be able to fail.
 *
 * Why (2026-07-14): a list golden asserted
 *     expect(page.locator('[data-testid="filter-chip-bar"]')).toHaveCount(0)
 * against a testid the component never rendered. The assertion passed, proved nothing, and
 * would have kept passing no matter how broken the page got. An always-true assertion is
 * worse than no assertion: it occupies the slot where a real check should have been.
 *
 * Rule: for every `toHaveCount(0)` / `not.toBeVisible()` / `toBeNull()` written against a
 * literal `data-testid` / `getByTestId`, that testid must exist somewhere in the frontend
 * source. If it does not, the spec is asserting the absence of something that could never
 * be present.
 *
 * Deliberately narrow: only literal testids in absence-assertions are checked. Dynamic
 * testids (template strings) are skipped rather than guessed at — a check that cries wolf
 * gets disabled, and a disabled check protects nobody.
 */
import { readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';

const REPO = path.resolve(new URL('..', import.meta.url).pathname);
const WEB = path.join(REPO, 'web-admin');
const SPECS = path.join(WEB, 'tests');
const APP = path.join(WEB, 'app');

const list = (dir, ext) =>
  execSync(`find ${dir} -name '*.${ext}' -not -path '*/node_modules/*'`, { encoding: 'utf8' })
    .trim()
    .split('\n')
    .filter(Boolean);

const specFiles = [...list(SPECS, 'ts'), ...list(SPECS, 'tsx')];
// Frontend source AND plugin DSL: a block's `id` in pages.json surfaces as its data-testid,
// so a testid can be perfectly real without appearing anywhere in web-admin/app.
const dslFiles = execSync(
  `find ${path.join(REPO, 'plugins')} -path '*/config/*' -name '*.json'`,
  { encoding: 'utf8' },
)
  .trim()
  .split('\n')
  .filter(Boolean);
const appSrc = [...list(APP, 'ts'), ...list(APP, 'tsx'), ...dslFiles]
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n');

/**
 * A testid counts as renderable if the frontend mentions it *at all* — as a literal
 * anywhere (it may reach `data-testid` through a ternary, a variable, or a constant), or
 * as the static prefix of a template like `inspector-field-${key}`.
 *
 * Deliberately generous. A first draft only recognised `data-testid="literal"` and flagged
 * two perfectly good assertions (a ternary-assigned banner, a template-built inspector id)
 * — a 2-in-3 false-positive rate. A gate that cries wolf gets switched off, and a
 * switched-off gate protects nobody; better to miss some real offenders than to be ignored.
 */
const literals = new Set();
for (const m of appSrc.matchAll(/['"]([a-z][a-z0-9]*(?:-[a-z0-9]+)+)['"]/g)) literals.add(m[1]);

const prefixes = new Set();
for (const m of appSrc.matchAll(/`([a-z][a-z0-9-]*-)\$\{/g)) prefixes.add(m[1]);

const canRender = (id) =>
  literals.has(id) || [...prefixes].some((p) => p.length > 3 && id.startsWith(p));

// absence assertions carrying a literal testid on the same line
const ABSENCE =
  /(?:getByTestId\(\s*['"]([\w:-]+)['"]\s*\)|\[data-testid=["']([\w:-]+)["']\])[^\n]*?(?:toHaveCount\(\s*0\s*\)|not\.toBeVisible\(\)|not\.toBeInTheDocument\(\)|toBeNull\(\))/g;

/**
 * Asserting that an *unrendered* testid is absent is sometimes exactly right:
 *   - guarding a RETIRED feature against coming back (a removed tab), or
 *   - asserting an edition boundary (an enterprise-only element must not appear in OSS).
 * Statically these are indistinguishable from the bug this gate exists for — asserting the
 * absence of something that never existed and never could. So the intent must be declared,
 * not inferred: mark the assertion `// gate:absent-by-design — <reason>` and it is accepted.
 */
const BY_DESIGN = /gate:(?:absent-by-design|retired)/;

const offenders = [];
let checked = 0;
let retired = 0;
for (const f of specFiles) {
  const s = readFileSync(f, 'utf8');
  const lines = s.split('\n');
  for (const m of s.matchAll(ABSENCE)) {
    const id = m[1] || m[2];
    checked++;
    if (canRender(id)) continue;
    // The spec itself can prove the testid is real: if the same file ALSO asserts it
    // visible / clicks it, then this toHaveCount(0) is a state assertion ("not showing
    // *yet*"), not an always-true one. Common when the spec seeds its own page schema, so
    // the testid exists in neither web-admin/app nor plugins/ — but very much exists at
    // runtime. (detail-all-blocks: line 441 asserts absent, line 448 asserts visible.)
    const POSITIVE = new RegExp(
      `getByTestId\\(\\s*['"]${id}['"]\\s*\\)[^\\n]*?(?:toBeVisible|toContainText|toHaveText|toBeInTheDocument|click)|` +
        `\\[data-testid=["']${id}["']\\][^\\n]*?(?:toBeVisible|toContainText|click)`,
    );
    if (POSITIVE.test(s)) continue;
    const lineNo = s.slice(0, m.index).split('\n').length;
    // marker may sit on the assertion line or the comment line above it
    const context = `${lines[lineNo - 1] ?? ''}\n${lines[lineNo - 2] ?? ''}`;
    if (BY_DESIGN.test(context)) {
      retired++;
      continue;
    }
    offenders.push({ file: path.relative(REPO, f), line: lineNo, id });
  }
}

console.log(
  `Scanned ${specFiles.length} spec file(s); ${checked} absence-assertion(s) with a literal testid` +
    (retired ? `; ${retired} marked gate:absent-by-design.` : '.'),
);

if (!offenders.length) {
  console.log('✅ every "assert absent" testid is one the frontend can actually render.');
  process.exit(0);
}

console.error('\n❌ Always-true assertions — this testid is never rendered anywhere:\n');
for (const o of offenders) {
  console.error(`  ${o.file}:${o.line}`);
  console.error(`      asserts "${o.id}" is absent, but no component ever renders it.`);
  console.error(`      → the assertion cannot fail. Either assert against a testid that really`);
  console.error(`        exists, or — if the absence IS the point (a retired feature that must not`);
  console.error(`        come back, an enterprise-only element that must not leak into OSS) — say so:`);
  console.error(`        \`// gate:absent-by-design — <reason>\` on that line.\n`);
}
process.exit(1);
