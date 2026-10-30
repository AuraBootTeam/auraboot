import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const script = readFileSync(join(root, 'scripts/dev/run-agent-runtime-backend-gate.sh'), 'utf8');

test('the established runtime catalog targets every current test source owner', () => {
  const bindings = new Map();
  for (const block of script.split(/(?=^\.\/gradlew\s)/m)) {
    const task = block.match(/^\.\/gradlew\s+(:[\w:-]+)/)?.[1];
    for (const match of block.matchAll(/--tests '([^']+)'/g)) {
      if (!bindings.has(match[1])) bindings.set(match[1], new Set());
      bindings.get(match[1]).add(task);
    }
  }
  assert.equal(bindings.size, 61);
  const fingerprint = createHash('sha256').update([...bindings.keys()].sort().join('\n') + '\n').digest('hex');
  assert.equal(fingerprint, 'f7e578f7a54f6b82d66467ca538af36c527b75c6f3ca50e2dad5c62d2ddaf56f');
  const classes = [];
  const visit = (directory, task) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = join(directory, entry.name);
      if (entry.isDirectory()) visit(file, task);
      else if (entry.name.endsWith('.java')) {
        const source = readFileSync(file, 'utf8');
        const packageName = source.match(/^package\s+([\w.]+);/m)?.[1];
        if (packageName) classes.push({ name: packageName + '.' + entry.name.slice(0, -5), task, source });
      }
    }
  };
  for (const [task, source] of [
    [':test', 'platform/src/test/java'],
    [':platform-agent:test', 'platform/platform-agent/src/test/java'],
    [':platform-app:test', 'platform/platform-app/src/test/java'],
    [':platform-app:integrationTest', 'platform/platform-app/src/integrationTest/java'],
  ]) visit(join(root, source), task);
  for (const [selector, actual] of bindings) {
    const regex = new RegExp('^' + selector.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replaceAll('*', '.*') + '$');
    let matched = classes.filter(item => regex.test(item.name));
    if (matched.length === 0) {
      const split = selector.lastIndexOf('.');
      matched = classes.filter(item => item.name === selector.slice(0, split));
      for (const item of matched) assert.match(item.source, new RegExp('\\b' + selector.slice(split + 1) + '\\s*\\('));
    }
    assert.ok(matched.length > 0, `Unowned selector: ${selector}`);
    assert.deepEqual([...actual].sort(), [...new Set(matched.map(item => item.task))].sort(), selector);
  }
});

test('the PostgreSQL governance member is registered and cannot reuse a cached green', () => {
  assert.match(script, /^\.\/gradlew :platform-agent:memoryGovernanceIntegrationTest --no-daemon$/m);
  const build = readFileSync(join(root, 'platform/platform-agent/build.gradle'), 'utf8');
  const task = build.slice(build.indexOf("tasks.register('memoryGovernanceIntegrationTest'"));
  assert.match(task, /includeTags 'agent-memory-postgres'/);
  assert.match(task, /outputs\.upToDateWhen \{ false \}/);
  assert.match(task, /outputs\.cacheIf \{ false \}/);
  const member = readFileSync(join(root, 'platform/platform-agent/src/test/java/com/auraboot/framework/agent/service/MemoryGovernancePostgresTest.java'), 'utf8');
  assert.match(member, /@Tag\("agent-memory-postgres"\)/);
  assert.match(member, /throw new IllegalStateException\("Requires an explicit TEST_DATABASE_URL/);
});
