import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const gate = new URL('./check-capability-codes.mjs', import.meta.url);
test('composition gate rejects cross-root duplicates, unknown fields and invalid dependencies', () => {
  const root = mkdtempSync(join(tmpdir(), 'capability-contract-'));
  const roots = [join(root, 'core'), join(root, 'business')];
  try {
    for (const dir of roots) mkdirSync(join(dir, 'plugin/config'), { recursive: true });
    writeFileSync(join(roots[0], 'plugin/config/permissions.json'), JSON.stringify([{code: 'record.read'}]));
    const write = (index, caps) => writeFileSync(join(roots[index], 'plugin/config/capabilities.json'), JSON.stringify(caps));
    const run = () => spawnSync(process.execPath, [gate.pathname, '--root', roots[0], '--include-root', roots[1]], {encoding: 'utf8'});
    write(0, [{code: 'core.view', includes: ['record.read']}]);
    write(1, [{code: 'business.view', includes: ['record.read']}]);
    assert.equal(run().status, 0, 'valid dependency declared by another composition plugin');
    write(1, [{code: 'core.view', includes: ['record.read']}]);
    assert.match(run().stderr, /duplicate capability/);
    write(1, [{code: 'business.view', includes: ['record.read'], permissions: ['record.write']}]);
    assert.match(run().stderr, /unknown property/);
    write(1, [{code: 'business.view', includes: ['record.read', 'record.read']}]);
    assert.match(run().stderr, /duplicate dependencies/);
    writeFileSync(join(roots[0], 'plugin/config/models.json'), JSON.stringify([{code: 'tenant_member'}]));
    writeFileSync(join(roots[0], 'plugin/config/commands.json'), JSON.stringify([{code: 'admin:approve_member', modelCode: 'tenant_member', type: 'state_transition'}]));
    write(1, [{code: 'business.view', includes: ['model.tenant_member.approve']}]);
    assert.equal(run().status, 0, 'state transitions derive the model verb');
    write(1, [{code: 'business.view', includes: ['model.tenant_member.approve_member']}]);
    assert.match(run().stderr, /ghost permission/);
    write(1, [{code: 'business.view', includes: ['record.missing']}]);
    assert.match(run().stderr, /ghost permission/);
  } finally { rmSync(root, {recursive: true, force: true}); }
});
