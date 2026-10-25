import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

test('the Java size ratchet includes production sources in sibling Gradle modules', () => {
  const root = mkdtempSync(join(tmpdir(), 'batch4-module-'));
  try {
    mkdirSync(join(root, 'scripts/lib'), { recursive: true });
    cpSync(new URL('./check-batch4-gates.mjs', import.meta.url), join(root, 'scripts/check-batch4-gates.mjs'));
    cpSync(new URL('./lib/java-source-roots.mjs', import.meta.url), join(root, 'scripts/lib/java-source-roots.mjs'));
    writeFileSync(join(root, 'scripts/baseline-large-files.json'), JSON.stringify({ threshold: 30, grandfathered: [] }));
    writeFileSync(join(root, 'scripts/baseline-i18n-cjk.json'), JSON.stringify({ cjkCodepoints: 0 }));
    mkdirSync(join(root, 'web-admin/app'), { recursive: true });
    mkdirSync(join(root, 'platform/src/main/java'), { recursive: true });
    const module = join(root, 'platform/platform-agent');
    mkdirSync(join(module, 'src/main/java'), { recursive: true });
    writeFileSync(join(module, 'build.gradle'), 'plugins { id "java-library" }\n');
    const source = join(module, 'src/main/java/LargeAgent.java');
    writeFileSync(source, 'class LargeAgent {\n' + '\n'.repeat(35) + '}\n');
    const red = spawnSync(process.execPath, [join(root, 'scripts/check-batch4-gates.mjs')], { encoding: 'utf8' });
    assert.equal(red.status, 1, red.stdout + red.stderr);
    assert.match(red.stderr, /platform-agent\/src\/main\/java\/LargeAgent.java/);
    writeFileSync(source, 'class LargeAgent {}\n');
    const green = spawnSync(process.execPath, [join(root, 'scripts/check-batch4-gates.mjs')], { encoding: 'utf8' });
    assert.equal(green.status, 0, green.stdout + green.stderr);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
