/**
 * Device Test Helpers for E2E Tests
 *
 * Provides utility functions for device-related E2E tests:
 * - Test data generation
 * - API helper methods
 * - Cleanup utilities
 *
 * @author AuraBoot E2E Test
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { ApiClient } from './api-client';

// ============================================================================
// Types
// ============================================================================

export interface DeviceData {
  device_id: string;
  device_name: string;
  device_type: DeviceType;
  manufacturer: Manufacturer;
  status: DeviceStatus;
  price?: number;
  serial_number?: string;
  install_date?: string;
  location?: string;
  notes?: string;
}

export type DeviceType = 'sensor' | 'actuator' | 'controller' | 'gateway';
export type Manufacturer = 'siemens' | 'abb' | 'schneider' | 'honeywell';
export type DeviceStatus = 'inactive' | 'online' | 'offline' | 'maintenance' | 'fault' | 'retired';

export interface DeviceApiResponse {
  pid: string;
  device_id: string;
  device_name: string;
  device_type: string;
  manufacturer: string;
  status: string;
  price?: number;
  serial_number?: string;
  created_at: string;
  updated_at: string;
}

// ============================================================================
// Test Data Generation
// ============================================================================

/**
 * Generate unique device test data
 */
export function createDeviceTestData(overrides?: Partial<DeviceData>): DeviceData {
  const timestamp = Date.now();
  const random = Math.random().toString(36).slice(2, 8);

  return {
    device_id: `DEV_E2E_${timestamp}_${random}`,
    device_name: `E2E Test Device ${timestamp}`,
    device_type: 'sensor',
    manufacturer: 'siemens',
    status: 'inactive',
    price: 9999.99,
    serial_number: `SN-${timestamp}-${random}`,
    install_date: new Date().toISOString().split('T')[0],
    ...overrides,
  };
}

/**
 * Generate multiple device test records
 */
export function createBatchDeviceData(
  count: number,
  baseOverrides?: Partial<DeviceData>,
): DeviceData[] {
  const devices: DeviceData[] = [];

  for (let i = 0; i < count; i++) {
    devices.push(
      createDeviceTestData({
        ...baseOverrides,
        device_name: `Batch Device ${i + 1}_${Date.now()}`,
      }),
    );
  }

  return devices;
}

/**
 * Generate device data for each status
 */
export function createDeviceForEachStatus(): DeviceData[] {
  const statuses: DeviceStatus[] = [
    'inactive',
    'online',
    'offline',
    'maintenance',
    'fault',
    'retired',
  ];

  return statuses.map((status) =>
    createDeviceTestData({
      device_name: `${status} Device ${Date.now()}`,
      status,
    }),
  );
}

// ============================================================================
// API Helpers
// ============================================================================

/**
 * Device API Client extending base ApiClient
 */
export class DeviceApiClient {
  private api: ApiClient;
  private baseUrl = 'http://localhost:5173';

  constructor(pageOrApi: Page | ApiClient) {
    if (pageOrApi instanceof ApiClient) {
      this.api = pageOrApi;
    } else {
      this.api = new ApiClient(pageOrApi);
    }
  }

  /**
   * Create a new device
   */
  async createDevice(data: DeviceData): Promise<DeviceApiResponse | null> {
    try {
      const response = await (this.api as any).request.post(
        `${this.baseUrl}/api/dynamic/device_list/create`,
        {
          data,
        },
      );
      const result = await response.json();
      return this.api.isSuccess(result) ? result.data : null;
    } catch (error) {
      console.error('Failed to create device:', error);
      return null;
    }
  }

  /**
   * Get device by PID
   */
  async getDevice(pid: string): Promise<DeviceApiResponse | null> {
    try {
      const response = await (this.api as any).request.get(
        `${this.baseUrl}/api/dynamic/device_list/${pid}`,
      );
      const result = await response.json();
      return this.api.isSuccess(result) ? result.data : null;
    } catch (error) {
      console.error('Failed to get device:', error);
      return null;
    }
  }

  /**
   * Update device
   */
  async updateDevice(pid: string, data: Partial<DeviceData>): Promise<DeviceApiResponse | null> {
    try {
      const response = await (this.api as any).request.put(
        `${this.baseUrl}/api/dynamic/device_list/${pid}`,
        {
          data,
        },
      );
      const result = await response.json();
      return this.api.isSuccess(result) ? result.data : null;
    } catch (error) {
      console.error('Failed to update device:', error);
      return null;
    }
  }

  /**
   * Delete device
   */
  async deleteDevice(pid: string): Promise<boolean> {
    try {
      const response = await (this.api as any).request.delete(
        `${this.baseUrl}/api/dynamic/device_list/${pid}`,
      );
      const result = await response.json();
      return this.api.isSuccess(result);
    } catch (error) {
      console.error('Failed to delete device:', error);
      return false;
    }
  }

  /**
   * Execute device command
   */
  async executeCommand(
    commandCode: string,
    payload: Record<string, any>,
    targetRecordId?: string,
  ): Promise<any> {
    try {
      const response = await (this.api as any).request.post(
        `${this.baseUrl}/api/meta/commands/${commandCode}/execute`,
        {
          data: {
            payload,
            operationType: targetRecordId ? 'update' : 'create',
            targetRecordId,
            clientRequestId: `e2e_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
          },
        },
      );
      return response.json();
    } catch (error) {
      console.error('Failed to execute command:', error);
      return null;
    }
  }

  /**
   * Activate device (inactive -> ONLINE)
   */
  async activateDevice(pid: string): Promise<any> {
    return this.executeCommand('activate_device', { status: 'online' }, pid);
  }

  /**
   * Shutdown device (ONLINE -> OFFLINE)
   */
  async shutdownDevice(pid: string): Promise<any> {
    return this.executeCommand('shutdown_device', { status: 'offline' }, pid);
  }

  /**
   * Repair device (ONLINE -> MAINTENANCE)
   */
  async repairDevice(pid: string, repairNote: string): Promise<any> {
    return this.executeCommand('repair_device', { status: 'maintenance', repairNote }, pid);
  }

  /**
   * Complete repair (MAINTENANCE -> ONLINE)
   */
  async completeRepair(pid: string): Promise<any> {
    return this.executeCommand('complete_repair', { status: 'online' }, pid);
  }
}

// ============================================================================
// Cleanup Utilities
// ============================================================================

/**
 * Track created devices for cleanup
 */
export class DeviceTestRegistry {
  private devices: string[] = [];

  /**
   * Register device PID for cleanup
   */
  register(pid: string): void {
    this.devices.push(pid);
  }

  /**
   * Get all registered PIDs
   */
  getAll(): string[] {
    return [...this.devices];
  }

  /**
   * Clear registry
   */
  clear(): void {
    this.devices = [];
  }

  /**
   * Cleanup all registered devices
   */
  async cleanupAll(api: DeviceApiClient): Promise<void> {
    for (const pid of this.devices) {
      try {
        await api.deleteDevice(pid);
      } catch (error) {
        console.warn(`Failed to cleanup device ${pid}:`, error);
      }
    }
    this.clear();
  }
}

// ============================================================================
// Page Object Helpers
// ============================================================================

/**
 * Device List Page helper
 */
export class DeviceListPage {
  constructor(private page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('http://localhost:5173/enterprise/devices');
    await this.page.waitForLoadState('domcontentloaded');
  }

  async getDeviceRow(deviceId: string) {
    return this.page.locator(`tr:has-text("${deviceId}")`);
  }

  async clickCreate(): Promise<void> {
    await this.page.locator('button:has-text("Create"), button:has-text("New")').first().click();
  }

  async clickEdit(deviceId: string): Promise<void> {
    const row = await this.getDeviceRow(deviceId);
    await row.locator('button:has-text("Edit")').click();
  }

  async clickDelete(deviceId: string): Promise<void> {
    const row = await this.getDeviceRow(deviceId);
    await row.locator('button:has-text("Delete")').click();
  }

  async search(keyword: string): Promise<void> {
    await this.page.locator('input[placeholder*="search"]').fill(keyword);
    await this.page.keyboard.press('Enter');
  }

  async waitForLoading(): Promise<void> {
    await this.page.waitForSelector('.ant-table-row', { timeout: 10000 }).catch(() => {});
  }
}

/**
 * Device Detail Page helper
 */
export class DeviceDetailPage {
  constructor(private page: Page) {}

  async goto(pid: string): Promise<void> {
    await this.page.goto(`http://localhost:5173/enterprise/devices/${pid}`);
    await this.page.waitForLoadState('domcontentloaded');
  }

  async getStatus(): Promise<string | null> {
    const statusElement = this.page.locator('[data-testid="device-status"]');
    return statusElement.textContent();
  }

  async clickActivate(): Promise<void> {
    await this.page.locator('button:has-text("Activate")').click();
  }

  async clickShutdown(): Promise<void> {
    await this.page.locator('button:has-text("Shutdown")').click();
  }

  async clickRepair(): Promise<void> {
    await this.page.locator('button:has-text("Repair")').click();
  }

  async fillRepairNote(note: string): Promise<void> {
    await this.page.locator('[data-testid="repair-note"]').fill(note);
  }

  async confirmRepair(): Promise<void> {
    await this.page.locator('button:has-text("Confirm Repair")').click();
  }
}

// ============================================================================
// Assertion Helpers
// ============================================================================

/**
 * Wait for device status to change
 */
export async function waitForDeviceStatus(
  page: Page,
  expectedStatus: string,
  timeout: number = 5000,
): Promise<boolean> {
  try {
    await page.waitForSelector(`[data-testid="device-status"]:has-text("${expectedStatus}")`, {
      timeout,
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Check if device exists in list
 */
export async function deviceExistsInList(page: Page, deviceId: string): Promise<boolean> {
  const row = page.locator(`tr:has-text("${deviceId}")`);
  return row.isVisible({ timeout: 3000 }).catch(() => false);
}

// ============================================================================
// Export singleton registry
// ============================================================================

export const deviceTestRegistry = new DeviceTestRegistry();
