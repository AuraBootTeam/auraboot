import { test, expect } from '@playwright/test';
import processes from '../../../../plugins/test-fixtures/config/processes.json' with { type: 'json' };

test('requires the real BPM application before automation execution tests', async ({ request }) => {
  const response = await request.get('/api/bpm/process-definitions/deployed');
  expect(
    response.status(),
    'Compose the real BPM plugin, its migrations and WorkflowCapability provider into this owned runtime',
  ).toBe(200);
  const result = await response.json();
  expect(result.code).toBe('0');
  const fixture = processes.find((process) => process.key === 'e2et_payment_approval')!;
  const matches = result.data.filter(
    (process: { processKey: string }) => process.processKey === fixture.key,
  );
  expect(
    matches,
    'Explicit workflow fixture setup must deploy exactly one current payment process',
  ).toHaveLength(1);
  const detail = await request.get(`/api/bpm/process-definitions/${matches[0].pid}`);
  expect(detail.status()).toBe(200);
  const persisted = await detail.json();
  expect(persisted.code).toBe('0');
  const definition = persisted.data;
  expect(definition).toMatchObject({
    processName: fixture['name:zh-CN'],
    status: 'deployed',
    isCurrent: true,
    extension: fixture.extension,
  });
  expect(definition.deploymentId).toEqual(expect.any(String));
  expect(JSON.parse(definition.designerJson)).toEqual(fixture.designerJson);
});
