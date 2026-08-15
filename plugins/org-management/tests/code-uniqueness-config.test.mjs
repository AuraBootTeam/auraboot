import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const codeFields = [
  ['org_dept_code.json', 'org_dept_code'],
  ['org_pos_code.json', 'org_pos_code'],
  ['org_emp_code.json', 'org_emp_code'],
];

test('organization import lookup codes are tenant-scoped unique fields', async () => {
  for (const [fileName, expectedCode] of codeFields) {
    const url = new URL(`../config/fields/${fileName}`, import.meta.url);
    const field = JSON.parse(await readFile(url, 'utf8'));

    assert.equal(field.code, expectedCode);
    assert.equal(field.constraints?.unique, true, `${expectedCode} must be unique`);
    assert.equal(field.constraints?.maxLength, 50, `${expectedCode} must be bounded`);
  }
});
