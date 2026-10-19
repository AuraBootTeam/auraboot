import { test, expect } from '@playwright/test';
import { createRequire } from 'node:module';

const processes = createRequire(import.meta.url)(
  '../../../../plugins/test-fixtures/config/processes.json',
) as typeof import('../../../../plugins/test-fixtures/config/processes.json');

test('explicitly deploys the payment approval fixture into a fresh owned BPM runtime', async ({
  request,
}) => {
  expect(process.env.AURA_RUNTIME_REPO).toBe('auraboot');
  expect(process.env.AURA_RUNTIME_NAME).toBeTruthy();
  expect(process.env.AURA_WORKSPACE_SLOT).toMatch(/^\d+$/);
  expect(process.env.POSTGRES_DB).toBe(`auraboot_${process.env.AURA_WORKSPACE_SLOT}`);

  const fixture = processes.find((process) => process.key === 'e2et_payment_approval')!;
  expect(fixture).toBeDefined();
  const existing = await request.get('/api/bpm/process-definitions', {
    params: { keyword: fixture.key },
  });
  expect(existing.status()).toBe(200);
  const listing = await existing.json();
  expect(listing.code).toBe('0');
  expect(
    listing.data.total,
    'Reset the owned workflow runtime; never overwrite a residual fixture',
  ).toBe(0);

  const created = await request.post('/api/bpm/process-definitions', {
    data: {
      processKey: fixture.key,
      processName: fixture['name:zh-CN'],
      description: fixture.description,
      category: fixture.category,
      designerJson: JSON.stringify(fixture.designerJson),
      extension: fixture.extension,
    },
  });
  expect(created.status()).toBe(200);
  const draft = await created.json();
  expect(draft.code, JSON.stringify(draft)).toBe('0');
  expect(draft.data.pid).toEqual(expect.any(String));
  expect(draft.data.processKey).toBe(fixture.key);

  const deployed = await request.post(`/api/bpm/process-definitions/${draft.data.pid}/deploy`);
  expect(deployed.status()).toBe(200);
  const result = await deployed.json();
  expect(result.code, JSON.stringify(result)).toBe('0');
  expect(result.data.status).toBe('deployed');
  expect(result.data.deploymentId).toEqual(expect.any(String));
});
