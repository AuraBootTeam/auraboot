import assert from 'node:assert/strict';
import { chmodSync, cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

test('JSONB guard checks an agent-owned entity against an independent schema fixture', () => {
  const root = mkdtempSync(join(tmpdir(), 'jsonb-module-'));
  try {
    mkdirSync(join(root, 'scripts/lib'), { recursive: true });
    cpSync(new URL('./check-jsonb-typehandler.sh', import.meta.url), join(root, 'scripts/check-jsonb-typehandler.sh'));
    cpSync(new URL('./lib/java-source-roots.mjs', import.meta.url), join(root, 'scripts/lib/java-source-roots.mjs'));
    mkdirSync(join(root, 'platform/src/main/java'), { recursive: true });
    const module = join(root, 'platform/platform-agent');
    mkdirSync(join(module, 'src/main/java/sample/entity'), { recursive: true });
    writeFileSync(join(module, 'build.gradle'), 'plugins { id "java-library" }\n');
    mkdirSync(join(root, 'bin'));
    const psql = join(root, 'bin/psql');
    writeFileSync(psql, '#!/usr/bin/env node\nif (process.argv.some(arg => arg.includes("information_schema.columns"))) process.stdout.write("ab_agent_probe\\tpayload\\n");\n');
    chmodSync(psql, 0o755);
    const entity = join(module, 'src/main/java/sample/entity/AgentProbe.java');
    writeFileSync(entity, 'package sample.entity;\n@TableName("ab_agent_probe")\nclass AgentProbe {\n @TableField(value = "payload") private String payload;\n}\n');
    const options = { encoding: 'utf8', env: { ...process.env, PATH: join(root, 'bin') + ':' + process.env.PATH } };
    const red = spawnSync('bash', [join(root, 'scripts/check-jsonb-typehandler.sh')], options);
    assert.equal(red.status, 1, red.stdout + red.stderr);
    assert.match(red.stdout + red.stderr, /AgentProbe|ab_agent_probe/);
    writeFileSync(entity, 'package sample.entity;\n@TableName("ab_agent_probe")\nclass AgentProbe {\n @TableField(value = "payload", typeHandler = JsonbStringTypeHandler.class) private String payload;\n}\n');
    const green = spawnSync('bash', [join(root, 'scripts/check-jsonb-typehandler.sh')], options);
    assert.equal(green.status, 0, green.stdout + green.stderr);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
