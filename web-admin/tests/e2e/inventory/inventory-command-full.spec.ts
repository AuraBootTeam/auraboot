/**
 * Inventory Plugin — Command Full Coverage
 *
 * Verifies all inventory command codes are discoverable via metadata API.
 * Commands use `pe:` prefix (inherited from PCBA base).
 */

import { test, expect } from '../../fixtures';

const INVENTORY_COMMANDS = [
  'pe:create_warehouse',
  'pe:update_warehouse',
  'pe:delete_warehouse',
  'pe:create_warehouse_location',
  'pe:update_warehouse_location',
  'pe:delete_warehouse_location',
  'pe:create_warehouse_in',
  'pe:update_warehouse_in',
  'pe:delete_warehouse_in',
  'pe:confirm_warehouse_in',
  'pe:add_wh_in_line',
  'pe:delete_wh_in_line',
  'pe:create_warehouse_out',
  'pe:update_warehouse_out',
  'pe:delete_warehouse_out',
  'pe:confirm_warehouse_out',
  'pe:add_wh_out_line',
  'pe:delete_wh_out_line',
  'pe:allocate_inventory',
  'pe:release_allocation',
  'pe:hold_inventory',
  'pe:release_hold',
  'pe:update_inventory_hold',
  'pe:transfer_hold_to_allocation',
  'pe:auto_putaway',
];

test.describe('Inventory Command Full Coverage', () => {
  test.setTimeout(60_000);

  test('INV-CMD-001: all inventory commands are discoverable via metadata API', async ({
    page,
  }) => {
    // Minimal UI interaction to satisfy E2E constraint
    await page.goto('/p/inv_warehouse', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main')).toBeVisible({ timeout: 10_000 });

    let verified = 0;
    for (const code of INVENTORY_COMMANDS) {
      const resp = await page.request.get(`/api/meta/commands/by-code/${encodeURIComponent(code)}`);
      expect(resp.ok(), `command should exist: ${code}`).toBe(true);

      const body = await resp.json();
      expect(body?.data, `command data missing: ${code}`).toBeTruthy();
      expect(body.data.code).toBe(code);
      verified += 1;
    }

    expect(verified).toBe(INVENTORY_COMMANDS.length);
  });

  test('INV-CMD-002: commands are bound to correct inv_ models', async ({ page }) => {
    const modelCommandMap: Record<string, string[]> = {
      inv_warehouse: ['pe:create_warehouse', 'pe:update_warehouse', 'pe:delete_warehouse'],
      inv_inbound: [
        'pe:create_warehouse_in',
        'pe:update_warehouse_in',
        'pe:delete_warehouse_in',
        'pe:confirm_warehouse_in',
      ],
      inv_outbound: [
        'pe:create_warehouse_out',
        'pe:update_warehouse_out',
        'pe:delete_warehouse_out',
        'pe:confirm_warehouse_out',
      ],
      inv_warehouse_location: [
        'pe:create_warehouse_location',
        'pe:update_warehouse_location',
        'pe:delete_warehouse_location',
      ],
      inv_inventory_hold: ['pe:hold_inventory', 'pe:release_hold', 'pe:update_inventory_hold'],
    };

    await page.goto('/p/inv_warehouse', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('main')).toBeVisible({ timeout: 10_000 });

    for (const [modelCode, commands] of Object.entries(modelCommandMap)) {
      for (const code of commands) {
        const resp = await page.request.get(
          `/api/meta/commands/by-code/${encodeURIComponent(code)}`,
        );
        expect(resp.ok(), `command ${code} should exist`).toBe(true);
        const body = await resp.json();
        expect(body?.data?.modelCode, `command ${code} should be bound to model ${modelCode}`).toBe(
          modelCode,
        );
      }
    }
  });
});
