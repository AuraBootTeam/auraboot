/**
 * PCBA IoT Device & Data Point Smoke Tests
 *
 * Validates IoT module pages and basic CRUD:
 * - IoT Device list page, create device
 * - IoT Data Point list page, create data point linked to device
 *
 * Prerequisites:
 *   - pcba-industry plugin imported, IoT models published
 *   - Menus registered under /pcba-erp/iot-*
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
} from '../helpers/index';

test.describe('PCBA IoT Smoke Tests @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('iot');
  let devicePid: string;

  // =========================================================================
  // TESTS
  // =========================================================================

  test('IOT-001: Navigate to IoT Devices list page via menu', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-iot-device');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/p\/pe_iot_device/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('IOT-002: Create an IoT device via command API', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'pe:create_iot_device',
      {
        pe_iotd_code: `DEV_${uid}`,
        pe_iotd_name: `IoT Device ${uid}`,
        pe_iotd_type: 'sensor',
        pe_iotd_protocol: 'mqtt',
        pe_iotd_ip_address: '192.168.1.100',
        pe_iotd_port: 1883,
        pe_iotd_status: 'active',
      },
      undefined,
      'create',
    );
    devicePid = result.recordId;
    expect(devicePid, 'Device should be created with a record ID').toBeTruthy();

    // Verify device exists in list
    const records = await queryFilteredList(page, 'pe-iot-device', 'pe_iotd_code', `DEV_${uid}`, {
      operator: 'EQ',
    });
    expect(records.length, 'Created IoT device should appear in list').toBeGreaterThanOrEqual(1);
  });

  test('IOT-003: Navigate to IoT Data Points list page', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-iot-data-point');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/p\/pe_iot_data_point/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('IOT-004: Create a data point for the device', async ({ page }) => {
    expect(devicePid, 'Device should have been created in IOT-002').toBeTruthy();

    const dpUid = uniqueId('DP');
    const result = await executeCommandViaApi(
      page,
      'pe:create_iot_data_point',
      {
        pe_iotdp_code: `DP_${dpUid}`,
        pe_iotdp_name: `Temperature ${dpUid}`,
        pe_iotdp_device_id: devicePid,
        pe_iotdp_data_type: 'float',
        pe_iotdp_unit: 'Celsius',
        pe_iotdp_collection_interval: 60,
        pe_iotdp_min_value: -40,
        pe_iotdp_max_value: 125,
        pe_iotdp_status: 'active',
      },
      undefined,
      'create',
    );
    expect(result.recordId, 'Data point should be created').toBeTruthy();

    // Verify data point exists
    const records = await queryFilteredList(
      page,
      'pe-iot-data-point',
      'pe_iotdp_code',
      `DP_${dpUid}`,
      { operator: 'EQ' },
    );
    expect(records.length, 'Created data point should appear in list').toBeGreaterThanOrEqual(1);
  });
});
