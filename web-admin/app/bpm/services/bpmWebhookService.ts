/**
 * BPM Webhook Service - Webhook configuration management
 */

import { get, post, put, del, ErrorCodes } from '~/services/http-client';

const API_BASE = '/api/bpm/webhooks';

// ==================== Types ====================

export interface WebhookConfig {
  pid?: string;
  name: string;
  url: string;
  secret?: string;
  eventTypes: string[];
  headers?: Record<string, string>;
  retryCount?: number;
  enabled?: boolean;
}

// ==================== Helper Functions ====================

function isSuccess(code: string): boolean {
  return code === ErrorCodes.SUCCESS;
}

// ==================== API Functions ====================

/**
 * Create a new webhook configuration
 */
export async function createWebhook(config: WebhookConfig): Promise<WebhookConfig> {
  const result = await post<WebhookConfig>(API_BASE, config);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to create webhook');
  }
  return result.data;
}

/**
 * List all webhook configurations
 */
export async function listWebhooks(): Promise<WebhookConfig[]> {
  const result = await get<WebhookConfig[]>(API_BASE);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to list webhooks');
  }
  return result.data || [];
}

/**
 * Update a webhook configuration
 */
export async function updateWebhook(
  pid: string,
  config: Partial<WebhookConfig>,
): Promise<WebhookConfig> {
  const result = await put<WebhookConfig>(`${API_BASE}/${pid}`, config);
  if (!isSuccess(result.code) || !result.data) {
    throw new Error(result.desc || 'Failed to update webhook');
  }
  return result.data;
}

/**
 * Delete a webhook configuration
 */
export async function deleteWebhook(pid: string): Promise<void> {
  const result = await del(`${API_BASE}/${pid}`);
  if (!isSuccess(result.code)) {
    throw new Error(result.desc || 'Failed to delete webhook');
  }
}
