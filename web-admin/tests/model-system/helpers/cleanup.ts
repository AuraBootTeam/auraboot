/**
 * Cleanup utilities for E2E tests
 * Handles cleanup of test data after tests
 */

import type { APIRequestContext } from '@playwright/test';
import { ApiClient } from './api-client';
import { ErrorCodes } from '~/services/http-client/types';

/**
 * Test data cleanup helper
 */
export class TestCleanup {
  private api: ApiClient;
  private modelPids: string[] = [];
  private fieldPids: string[] = [];
  private dictPids: string[] = [];

  constructor(request: APIRequestContext) {
    this.api = new ApiClient(request);
  }

  /**
   * Register a model for cleanup
   */
  registerModel(pid: string): void {
    if (!this.modelPids.includes(pid)) {
      this.modelPids.push(pid);
    }
  }

  /**
   * Register a field for cleanup
   */
  registerField(pid: string): void {
    if (!this.fieldPids.includes(pid)) {
      this.fieldPids.push(pid);
    }
  }

  /**
   * Register a dictionary for cleanup
   */
  registerDict(pid: string): void {
    if (!this.dictPids.includes(pid)) {
      this.dictPids.push(pid);
    }
  }

  /**
   * Cleanup all registered test data
   */
  async cleanupAll(): Promise<void> {
    const errors: string[] = [];

    // Cleanup models first (may have field bindings)
    for (const pid of this.modelPids) {
      try {
        await this.api.deleteModel(pid);
      } catch (e) {
        errors.push(`Failed to delete model ${pid}: ${e}`);
      }
    }

    // Cleanup fields
    for (const pid of this.fieldPids) {
      try {
        await this.api.deleteField(pid);
      } catch (e) {
        errors.push(`Failed to delete field ${pid}: ${e}`);
      }
    }

    // Cleanup dictionaries
    for (const pid of this.dictPids) {
      try {
        await this.api.deleteDict(pid);
      } catch (e) {
        errors.push(`Failed to delete dict ${pid}: ${e}`);
      }
    }

    // Clear registries
    this.modelPids = [];
    this.fieldPids = [];
    this.dictPids = [];

    if (errors.length > 0) {
      console.warn('Cleanup warnings:', errors);
    }
  }

  /**
   * Cleanup by prefix pattern
   */
  async cleanupByPrefix(request: APIRequestContext, prefix: string = 'e2e_'): Promise<void> {
    // Query and delete models with prefix
    try {
      const modelsResponse = await request.get(`/api/meta/models`, {
        params: { keyword: prefix, size: 100 },
      });
      const modelsData = await modelsResponse.json();

      if (modelsData.code === ErrorCodes.SUCCESS && modelsData.data?.records) {
        for (const model of modelsData.data.records) {
          if (model.code?.startsWith(prefix)) {
            try {
              await request.delete(`/api/meta/models/${model.pid}`);
            } catch (e) {
              // Ignore errors during cleanup
            }
          }
        }
      }
    } catch (e) {
      console.warn('Model cleanup by prefix failed:', e);
    }

    // Query and delete fields with prefix
    try {
      const fieldsResponse = await request.get(`/api/meta/fields`, {
        params: { code: prefix, size: 100 },
      });
      const fieldsData = await fieldsResponse.json();

      if (fieldsData.code === ErrorCodes.SUCCESS && fieldsData.data?.data) {
        for (const field of fieldsData.data.data) {
          if (field.code?.startsWith(prefix)) {
            try {
              await request.delete(`/api/meta/fields/${field.pid}`);
            } catch (e) {
              // Ignore errors during cleanup
            }
          }
        }
      }
    } catch (e) {
      console.warn('Field cleanup by prefix failed:', e);
    }

    // Query and delete dictionaries with prefix
    try {
      const dictsResponse = await request.get(`/api/meta/dict`, {
        params: { code: prefix, pageSize: 100 },
      });
      const dictsData = await dictsResponse.json();

      if (dictsData.success && dictsData.data?.records) {
        for (const dict of dictsData.data.records) {
          if (dict.code?.startsWith(prefix)) {
            try {
              await request.delete(`/api/meta/dict/${dict.pid}`);
            } catch (e) {
              // Ignore errors during cleanup
            }
          }
        }
      }
    } catch (e) {
      console.warn('Dict cleanup by prefix failed:', e);
    }
  }
}

/**
 * Create a cleanup instance for a test
 */
export function createCleanup(request: APIRequestContext): TestCleanup {
  return new TestCleanup(request);
}
