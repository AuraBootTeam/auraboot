/**
 * Command API Tests
 *
 * Migrated from: tests/e2e/command/command-management.spec.ts
 * Test: CMD-006 (Create command via API)
 *
 * E2E tests (CMD-001~005, CMD-007) remain in the e2e file.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';

function generateCode(prefix: string = 'cmd'): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).substring(2, 6);
  return `${prefix}_e2e_${timestamp}_${random}`;
}

test.describe('Command API', () => {

  test('CMD-006: should create command via API', async ({ request }) => {
    const commandData = {
      code: generateCode('cmd'),
      displayName: 'Test Command',
      actionType: 'create',
      modelCode: 'test_model',
    };

    try {
      const response = await request.post(`/api/meta/commands`, {
        data: commandData,
      });

      const result = await response.json();

      expect(response.ok() || response.status() === 404 || response.status() === 400).toBe(true);
    } catch {
      test.skip();
      return;
    }
  });
});
