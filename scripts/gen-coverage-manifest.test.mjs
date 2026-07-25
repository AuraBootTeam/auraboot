import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { buildPageIndex } from './gen-coverage-manifest.mjs';

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
