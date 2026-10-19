import { test, expect } from '@playwright/test';

test('requires the real BPM application before automation execution tests', async ({ request }) => {
  const response = await request.get('/api/bpm/process-definitions');
  expect(
    response.status(),
    'Compose the real BPM plugin, its migrations and WorkflowCapability provider into this owned runtime',
  ).toBe(200);
  expect((await response.json()).code).toBe('0');
});
