import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import {
  buildModelRouteIndex,
  buildPageIndex,
  manifestTarget,
  resolveConfiguredPath,
} from './gen-coverage-manifest.mjs';

// The page index decides which pages count as covered, so both of its failure
// directions matter and they are not symmetric:
//
//   under-count -> a covered page is reported untested. Noise; people stop
//                  believing the number.
//   over-count  -> an untested page is reported covered. The gap becomes
//                  invisible, which is the failure the manifest exists to stop.
//
// Both directions are pinned below. Removing the ?/# from the closing character
// class fails the first case; widening the opening class to a generic boundary
// fails the last.

function withSpec(contents, fn) {
  const dir = mkdtempSync(path.join(tmpdir(), 'pageindex-'));
  try {
    mkdirSync(path.join(dir, 'tests'), { recursive: true });
    for (const [name, text] of Object.entries(contents)) {
      writeFileSync(path.join(dir, 'tests', name), text);
    }
    return fn(buildPageIndex([path.join(dir, 'tests')]));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('a route in quotes is evidence', () => {
  withSpec({ 'a.spec.ts': `await page.goto('/p/c/widget_hub');` }, (idx) => {
    assert.equal(idx.has('widget_hub'), true);
  });
});

test('a route carrying query parameters is evidence', () => {
  // The regression: `/p/c/<key>?agentPid=${pid}` ends in ? , not a quote, so a
  // closing class of ["'`/] skipped it and both parameterised colleague pages
  // were reported untested while their specs sat in the tree.
  withSpec({ 'b.spec.ts': 'await page.goto(`/p/c/widget_detail?agentPid=${pid}`);' }, (idx) => {
    assert.equal(idx.has('widget_detail'), true);
  });
});

test('a route carrying a hash is evidence', () => {
  withSpec({ 'c.spec.ts': `await page.goto('/p/c/widget_tabs#profile');` }, (idx) => {
    assert.equal(idx.has('widget_tabs'), true);
  });
});

test('a bare page key in quotes is evidence', () => {
  withSpec({ 'd.spec.ts': `expect(pageKey).toBe('widget_bare');` }, (idx) => {
    assert.equal(idx.has('widget_bare'), true);
  });
});

test('a page named only in prose is NOT evidence', () => {
  // These specs really do open with "a DSL page (ai_colleague_chat) whose ...".
  // Counting that as coverage would mark a page green for being talked about.
  withSpec({ 'e.spec.ts': `/** Golden for a DSL page (widget_prose) whose block renders. */` }, (idx) => {
    assert.equal(idx.has('widget_prose'), false);
  });
});

test('a page mentioned in a bare sentence is NOT evidence', () => {
  withSpec({ 'f.spec.ts': `// TODO cover widget_todo later` }, (idx) => {
    assert.equal(idx.has('widget_todo'), false);
  });
});

test('the index records which file the evidence came from', () => {
  withSpec({ 'g.spec.ts': `await page.goto('/p/c/widget_named');` }, (idx) => {
    const files = idx.get('widget_named');
    assert.equal(files.length, 1);
    assert.ok(files[0].endsWith('g.spec.ts'), `expected g.spec.ts, got ${files[0]}`);
  });
});

test('an environment-owned spec root resolves in clean and worktree checkouts', () => {
  assert.equal(
    resolveConfiguredPath('/repo/quote', '$AURA_CORE_PROJECT_ROOT/web-admin/tests/e2e', {
      AURA_CORE_PROJECT_ROOT: '/worktrees/oss-current',
    }),
    '/worktrees/oss-current/web-admin/tests/e2e',
  );
  assert.throws(
    () => resolveConfiguredPath('/repo/quote', '${AURA_CORE_PROJECT_ROOT}/web-admin', {}),
    /requires AURA_CORE_PROJECT_ROOT/,
  );
});

test('manifest target is stable when the plugin root is the repository root', () => {
  assert.equal(manifestTarget('/worktrees/plugins', '/worktrees/plugins'), '.');
  assert.equal(manifestTarget('/worktrees/quote', '/worktrees/quote/plugin-aura'), 'plugin-aura');
});

test('default model routes distinguish list, form, and detail evidence', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'modelroute-'));
  try {
    mkdirSync(path.join(dir, 'tests'), { recursive: true });
    writeFileSync(
      path.join(dir, 'tests', 'routes.spec.ts'),
      [
        `await page.goto('/p/qo_quote_common');`,
        `await expect(page).toHaveURL(/\\/p\\/qo_quote_common\\/new/);`,
        `await page.waitForURL('/p/qo_quote_common/view/quote-1');`,
      ].join('\n'),
    );
    const index = buildModelRouteIndex([path.join(dir, 'tests')]);
    assert.equal(index.get('qo_quote_common:list').length, 1);
    assert.equal(index.get('qo_quote_common:form').length, 1);
    assert.equal(index.get('qo_quote_common:detail').length, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// Platform-native pages. The reason this dimension exists is that 97 hand-written
// React routes produced no row at all, and an absent row reads as "not applicable"
// rather than as work. So both of ITS failure directions get pinned too.

import { declaredPlatformPages, buildRouteIndex } from './gen-coverage-manifest.mjs';

function withPlugin(files, fn) {
  const dir = mkdtempSync(path.join(tmpdir(), 'platpage-'));
  try {
    for (const [rel, text] of Object.entries(files)) {
      const abs = path.join(dir, rel);
      mkdirSync(path.dirname(abs), { recursive: true });
      writeFileSync(abs, text);
    }
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const RESOURCES = `
export const RESOURCES = [
  {
    key: 'demo.real', path: '/demo/real',
    title: { en: 'Real', zh: '真' },
    file: './plugins/demo/pages/real.tsx',
  },
  {
    key: 'demo.dsl-pointer', path: '/p/c/demo_hub',
    title: { en: 'Hub', zh: '中心' },
    menu: { order: 10 },
  },
]
`;

test('a resources entry with a file is a platform page', () => {
  withPlugin({ 'app/plugins/demo/resources.ts': RESOURCES }, (root) => {
    const pages = declaredPlatformPages(root);
    assert.deepEqual(pages.map((p) => p.key), ['demo.real']);
    assert.equal(pages[0].route, '/demo/real');
  });
});

test('a fileless entry is NOT a platform page — it points at a DSL route', () => {
  // Counting it would double-count: the DSL page rows already cover that surface.
  // This is how the AI colleague pages were converted, so the case is live.
  withPlugin({ 'app/plugins/demo/resources.ts': RESOURCES }, (root) => {
    assert.equal(declaredPlatformPages(root).some((p) => p.key === 'demo.dsl-pointer'), false);
  });
});

test('visiting a detail path does NOT count as covering the parent route', () => {
  // This is what the right-hand boundary is for. A spec that opens
  // /aurabot/knowledge/kb-123 has exercised the detail page, not the list page — and
  // without the boundary the substring match would credit the list route too, quietly
  // marking an untested page green.
  //
  // (An earlier version of this test asserted the reverse — parent spec, child query —
  // which no substring match could ever satisfy, so it held whether the boundary was
  // there or not. It survived the mutation run and taught nothing.)
  const dir = mkdtempSync(path.join(tmpdir(), 'routeidx-'));
  try {
    mkdirSync(path.join(dir, 'tests'), { recursive: true });
    writeFileSync(
      path.join(dir, 'tests', 'a.spec.ts'),
      `await page.goto('/aurabot/knowledge/kb-123');`,
    );
    const hits = buildRouteIndex([path.join(dir, 'tests')]);
    assert.equal(hits('/aurabot/knowledge/kb-123').length, 1, 'the visited route is covered');
    assert.equal(
      hits('/aurabot/knowledge').length,
      0,
      'the list route must not inherit the detail route evidence',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a parameterised route is credited when a spec interpolates it', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'routeidx2-'));
  try {
    mkdirSync(path.join(dir, 'tests'), { recursive: true });
    writeFileSync(
      path.join(dir, 'tests', 'b.spec.ts'),
      'await page.goto(`/aurabot/knowledge/${kbPid}`);',
    );
    const hits = buildRouteIndex([path.join(dir, 'tests')]);
    assert.equal(hits('/aurabot/knowledge').length, 1, 'the prefix is genuinely present here');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
