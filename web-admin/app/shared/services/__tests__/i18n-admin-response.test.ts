import { it as test } from 'vitest';
import assert from 'node:assert/strict';
import { readI18nAdminResponse } from '../i18n-admin-response';

test('successful envelope returns persisted resource data', async () => {
  const resource = { pid: 'exact-resource', status: 'approved' };
  assert.deepEqual(await readI18nAdminResponse(Response.json({ code: '0', data: resource })), resource);
});
const rejectedResponses: Array<[string, () => Response]> = [
  ['HTTP 200 business failure', () => Response.json({ code: '400', message: 'Cannot approve draft' })],
  ['legacy success flag', () => Response.json({ success: true })],
  ['numeric success code', () => Response.json({ code: 0 })],
  ['HTTP permission failure', () => Response.json({ code: '0' }, { status: 403 })],
  ['invalid JSON', () => new Response('not JSON')],
];
for (const [name, response] of rejectedResponses) test(`rejects ${name}`, async () => {
  await assert.rejects(readI18nAdminResponse(response()));
});
test('business failure preserves the backend feedback', async () => {
  await assert.rejects(readI18nAdminResponse(Response.json({ code: '400', message: 'Rejection reason is required' })), /Rejection reason is required/);
});
