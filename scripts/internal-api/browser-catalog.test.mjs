import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditBrowserCatalog, loadBrowserCatalog } from './browser-catalog.mjs';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const browser = 'web-admin/tests/e2e/model/internal-api-sample.spec.ts';
const api = 'web-admin/tests/api/internal-api/sample.spec.ts';
function fixture() {
  return {
    sources: [
      { file: browser, text: `import {test} from '@playwright/test';
        test.afterEach(async ({page}, info) => { await info.attach('cleanup', {body:'{}', contentType:'application/json'}); });
        test('browser flow', async ({page}, info) => { await capture('SAMPLE-01',page,info); });` },
      { file: api, text: `import {test} from '@playwright/test';
        test('wire flow', async ({request}, info) => { await info.attach('wire', {body:'{}',contentType:'application/json'}); });` },
    ],
    catalog: { profiles: { browser: { spec: browser, tests: ['browser flow'] }, api: { spec: api, tests: ['wire flow'] } },
      scenarios: [{ id: 'SAMPLE-01', test: 'browser flow', screenshot: 'SAMPLE-01.png' }] },
  };
}

test('browser catalog reconciles independent spec tests scenes and mandatory JSON evidence', () => {
  const { sources, catalog } = fixture(), result = auditBrowserCatalog(sources, catalog);
  assert.equal(result.tests.length, 2);
  assert.deepEqual(result.scenarios, [{ id: 'SAMPLE-01', test: 'browser flow', file: browser }]);
  assert.deepEqual(result.tests[0].requiredEvidence, [{ name: 'cleanup', fields: {} }]);
  assert.deepEqual(result.tests[1].requiredEvidence, [{ name: 'wire', fields: {} }]);
  assert.equal(result.tests[1].project, 'internal-api-wire');
  assert.deepEqual(auditBrowserCatalog([...sources].reverse(), catalog).tests.map(r => r.id).sort(), result.tests.map(r => r.id).sort());
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const actual = loadBrowserCatalog(root, JSON.parse(fs.readFileSync(new URL('./catalog.json', import.meta.url))));
  assert.ok(actual.tests.length > 0 && actual.scenarios.length > 0);
  const actualCatalog = JSON.parse(fs.readFileSync(new URL('./catalog.json', import.meta.url)));
  if (actualCatalog.profiles.capabilityBrowser) {
    assert.equal(actual.fixtures.length, 1);
    const changed = structuredClone(actualCatalog);
    changed.profiles.capabilityBrowser.capabilityFixture.includes = [];
    assert.throws(() => loadBrowserCatalog(root, changed), /grant denominator differ/);
    changed.profiles.capabilityBrowser.capabilityFixture.file = '../outside.json';
    assert.throws(() => loadBrowserCatalog(root, changed), /fixture path/);
  }
});

test('browser catalog rejects missing empty skipped shadowed dynamic duplicated or unregistered evidence', () => {
  const mutations = [
    f => { f.sources = []; },
    f => { f.sources.pop(); },
    f => { f.sources.push(f.sources[0]); },
    f => { f.sources[0].text = ''; },
    f => { f.sources[0].text = f.sources[0].text.replace("test('browser flow'", "test.skip('browser flow'"); },
    f => { f.sources[0].text = f.sources[0].text.replace("'browser flow'", 'dynamicTitle'); },
    f => { f.sources[0].text += ' const test = other;'; },
    f => { f.sources[0].text += "test.describe.only('exclusive',()=>{});"; },
    f => { f.sources[0].text = f.sources[0].text.replace("await capture('SAMPLE-01',page,info);", ''); },
    f => { f.sources[1].text = f.sources[1].text.replace("await info.attach('wire', {body:'{}',contentType:'application/json'});", ''); },
    f => { f.catalog.profiles.api.tests = []; },
    f => { f.catalog.profiles.api.tests.push('extra'); },
    f => { f.catalog.scenarios = []; },
    f => { f.catalog.scenarios.push({ ...f.catalog.scenarios[0], id: 'SAMPLE-02' }); },
    f => { f.catalog.scenarios[0].test = 'wire flow'; },
    f => { f.catalog.scenarios[0].screenshot = '../escape.png'; },
    f => { f.sources[0].text += "test('extra',async()=>{await capture('SAMPLE-01',page,info);});"; },
    f => { f.sources[0].text = f.sources[0].text.replace("capture('SAMPLE-01'", 'capture(dynamicId'); },
    f => { f.sources[1].text = f.sources[1].text.replace('@playwright/test', './unrelated'); },
  ];
  for (const [index, mutate] of mutations.entries()) {
    const f = fixture(); mutate(f);
    assert.throws(() => auditBrowserCatalog(f.sources, f.catalog), `mutation ${index} must fail closed`);
  }
});
