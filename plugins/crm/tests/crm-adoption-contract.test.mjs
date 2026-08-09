import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const readPluginFile = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8');

test('clean-room guide stages the hybrid JAR before starting and importing CRM', async () => {
  const readme = await readPluginFile('README.md');
  const publishApi = readme.indexOf(':platform-plugin-api:publishToMavenLocal');
  const buildJar = readme.indexOf('gradle -p plugins/crm/backend clean test jar');
  const stageJar = readme.indexOf('install -m 0644');
  const startStack = readme.indexOf('scripts/dev/start-isolated.sh');
  const importDemo = readme.indexOf('scripts/import-plugins.sh');
  const runJourney = readme.indexOf('python3 plugins/crm/scripts/adoption_journey.py');

  for (const [label, position] of Object.entries({
    publishApi,
    buildJar,
    stageJar,
    startStack,
    importDemo,
    runJourney,
  })) {
    assert.notEqual(position, -1, `${label} must be documented`);
  }
  assert.ok(publishApi < buildJar, 'the plugin API must be published before the hybrid build');
  assert.ok(buildJar < stageJar, 'the built JAR must be available before staging');
  assert.ok(stageJar < startStack, 'the JAR mount must be prepared before backend startup');
  assert.ok(startStack < importDemo, 'runtime handlers must exist before DSL import');
  assert.ok(importDemo < runJourney, 'the adoption journey starts only after metadata import');
  assert.match(readme, /No data migration is part of this development-stage adoption path\./);
});

test('adoption driver exposes a machine receipt and an enforced 30-minute deadline', async () => {
  const driver = await readPluginFile('scripts/adoption_journey.py');
  assert.match(driver, /ADOPTION_STARTED_AT_EPOCH/);
  assert.match(driver, /ADOPTION_EVIDENCE_DIR/);
  assert.match(driver, /DEADLINE_SECONDS = int\(os\.environ\.get\("ADOPTION_DEADLINE_SECONDS", "1800"\)\)/);
  assert.match(driver, /"CONVERT-RELATIONSHIP-GRAPH"/);
  assert.match(driver, /"CREATE-NEXT-ACTIVITY"/);
  assert.match(driver, /"verdict": "pass"/);
});
