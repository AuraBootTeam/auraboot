/**
 * Create Record Workflow
 *
 * Provides utilities for creating dynamic records in tests.
 * Useful for testing CRUD operations on model instances.
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { ApiClient } from '../model-system/helpers/api-client';
import { ErrorCodes } from '~/services/http-client/types';

/**
 * Options for creating a record
 */
export interface CreateRecordOptions {
  /** Model code */
  modelCode: string;
  /** Record data (field code -> value) */
  data: Record<string, unknown>;
}

/**
 * Result of record creation
 */
export interface CreateRecordResult {
  /** Record ID */
  id: string | number;
  /** Record PID (if applicable) */
  pid?: string;
  /** Full record data */
  data: Record<string, unknown>;
}

/**
 * Create a record via API
 *
 * NOTE: The exact API endpoint depends on your backend implementation.
 * This is a template - adjust the endpoint and request format as needed.
 *
 * @param api - ApiClient instance
 * @param options - Record creation options
 * @returns Created record information
 */
export async function createRecord(
  api: ApiClient,
  options: CreateRecordOptions,
): Promise<CreateRecordResult> {
  // This is a placeholder implementation.
  // Adjust the endpoint and request format based on your actual API.

  const endpoint = `/api/dynamic/${options.modelCode}`;

  const response = await api['request'].post(endpoint, {
    data: options.data,
  });

  const result = await response.json();

  if (result.code !== ErrorCodes.SUCCESS && !result.success) {
    throw new Error(`Failed to create record: ${result.desc || result.message || 'Unknown error'}`);
  }

  return {
    id: result.data?.id,
    pid: result.data?.pid,
    data: result.data,
  };
}

/**
 * Navigate to create record page via UI
 *
 * @param page - Playwright page
 * @param modelCode - Model code
 */
export async function navigateToCreateRecordPage(page: Page, modelCode: string): Promise<void> {
  await page.goto(`/p/${modelCode}/new`);
  await page.waitForLoadState('domcontentloaded');
}

/**
 * Navigate to record list page
 *
 * @param page - Playwright page
 * @param modelCode - Model code
 */
export async function navigateToRecordList(page: Page, modelCode: string): Promise<void> {
  await page.goto(`/p/${modelCode}`);
  await page.waitForLoadState('domcontentloaded');
}

/**
 * Navigate to record detail page
 *
 * @param page - Playwright page
 * @param modelCode - Model code
 * @param recordId - Record ID
 */
export async function navigateToRecordDetail(
  page: Page,
  modelCode: string,
  recordId: string | number,
): Promise<void> {
  await page.goto(`/p/${modelCode}/${recordId}`);
  await page.waitForLoadState('domcontentloaded');
}

/**
 * Delete a record (for cleanup)
 *
 * @param api - ApiClient instance
 * @param modelCode - Model code
 * @param recordId - Record ID
 * @returns true if deleted successfully, false otherwise
 */
export async function deleteRecord(
  api: ApiClient,
  modelCode: string,
  recordId: string | number,
): Promise<boolean> {
  try {
    const endpoint = `/api/dynamic/${modelCode}/${recordId}`;

    const response = await api['request'].delete(endpoint);
    const result = await response.json();

    return result.code === ErrorCodes.SUCCESS || result.success;
  } catch {
    return false;
  }
}
