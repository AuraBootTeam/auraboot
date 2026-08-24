import assert from 'node:assert/strict';
import test from 'node:test';

import { assertUniqueListRecordPid, dynamicListRecords } from './row-contract.mjs';

test('normalizes the real dynamic-list envelope and proves unique PID membership', () => {
  const records = dynamicListRecords(
    {
      code: '0',
      data: {
        records: [{ pid: '01-FIRST' }, { pid: '01-TARGET' }],
      },
    },
    'product list',
  );

  assert.deepEqual(
    records.map((record) => record.pid),
    ['01-FIRST', '01-TARGET'],
  );
  assert.doesNotThrow(() => assertUniqueListRecordPid(records, '01-TARGET', 'product'));
});

test('accepts the nested data array shape used by compatible dynamic-list endpoints', () => {
  const records = dynamicListRecords({ data: { data: [{ pid: '01-TARGET' }] } }, 'brand list');

  assert.doesNotThrow(() => assertUniqueListRecordPid(records, '01-TARGET', 'brand'));
});

test('fails closed when the requested PID is absent or duplicated', () => {
  assert.throws(
    () => assertUniqueListRecordPid([{ pid: '01-OTHER' }], '01-TARGET', 'product'),
    /found 0/,
  );
  assert.throws(
    () =>
      assertUniqueListRecordPid(
        [{ pid: '01-TARGET' }, { pid: '01-TARGET' }],
        '01-TARGET',
        'product',
      ),
    /found 2/,
  );
});

test('rejects failed and structurally invalid dynamic-list responses', () => {
  assert.throws(
    () => dynamicListRecords({ code: '500', data: { records: [] } }, 'product list'),
    /failed with code 500/,
  );
  assert.throws(
    () => dynamicListRecords({ code: '0', data: {} }, 'product list'),
    /did not expose a dynamic-list records array/,
  );
});
