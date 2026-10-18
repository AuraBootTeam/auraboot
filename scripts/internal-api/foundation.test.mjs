import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditTests } from './foundation.mjs';

test('foundation audit rejects empty missing repeated skipped failed and unexpected test evidence', () => {
  const passed = name => ({name, status:'passed'});
  assert.throws(() => auditTests([], []));
  assert.throws(() => auditTests(['A'], []));
  assert.throws(() => auditTests(['A','B'], [passed('A'),passed('A')]));
  assert.throws(() => auditTests(['A'], [{...passed('A'),skip:true}]));
  assert.throws(() => auditTests(['A'], [{...passed('A'),todo:true}]));
  assert.throws(() => auditTests(['A'], [{name:'A',status:'failed'}]));
  assert.throws(() => auditTests(['A'], [passed('empty-file.test.mjs')]));
});

test('foundation audit accepts each registered passing test exactly once', () => {
  assert.deepEqual(auditTests(['A','B'], [{name:'B',status:'passed'},{name:'A',status:'passed'}]), {
    registered:2, executed:2, passed:2, skipped:0,
  });
});

test('CLI entrypoints reject missing arguments when invoked through a symlink', async () => {
  const fs = await import('node:fs');
  const os = await import('node:os');
  const path = await import('node:path');
  const {fileURLToPath} = await import('node:url');
  const {spawnSync} = await import('node:child_process');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'internal-api-cli-'));
  try {
    for (const script of ['foundation.mjs','drift.mjs','export.mjs','discover.mjs','toolchain/generate.mjs']) {
      const link = path.join(directory, script.replaceAll('/','-'));
      fs.symlinkSync(fileURLToPath(new URL(script, import.meta.url)), link);
      const child = spawnSync(process.execPath,[link],{encoding:'utf8',timeout:20000});
      assert.equal(child.status,1,`CLI must execute and reject missing arguments: ${script}`);
      assert.match(child.stderr,/Usage:|--url is required/);
    }
  } finally { fs.rmSync(directory,{recursive:true,force:true}); }
});
