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
