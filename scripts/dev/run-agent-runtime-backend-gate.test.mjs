import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { spawnSync } from 'node:child_process';

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
  assert.equal(bindings.size, 58);
  const fingerprint = createHash('sha256').update([...bindings.keys()].sort().join('\n') + '\n').digest('hex');
  assert.equal(fingerprint, '664e1293f0a17d46569ae85af7ed427d605b95c6df033816d4c387c553c4a60a');
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


test('the runtime gate refuses missing public approval fixtures before Gradle', () => {
  const env = { ...process.env };
  delete env.AURA_APPROVAL_ROLLBACK_FIXTURES;
  const result = spawnSync('bash', [join(root, 'scripts/dev/run-agent-runtime-backend-gate.sh')], { env, encoding: 'utf8' });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /AURA_APPROVAL_ROLLBACK_FIXTURES must name/);
  assert.doesNotMatch(result.stdout, /Gradle|Task :/);
});
