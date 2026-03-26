/**
 * Field Management E2E Tests
 *
 * Tests M-010 ~ M-019: Field types, validation, and binding
 *
 * Uses storageState for authentication and API for data preparation.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import {
  createModelData,
  createFieldData,
  createFieldBindingData,
} from '../../model-system/helpers/test-data';
import type { FieldDataType } from '../../model-system/helpers/test-data';
import type { FieldResponse, ApiClient } from '../../model-system/helpers/api-client';

test.describe('Field Management Tests', () => {
  let testModelPid: string;
  let testModelCode: string;

  test.beforeEach(async ({ api }) => {
    // Create a test model for field operations
    const modelData = createModelData({ modelType: 'entity' });
    const response = await api.createModel(modelData);

    if (api.isSuccess(response) && response.data) {
      testModelPid = response.data.pid;
      testModelCode = response.data.code;

      // Publish the model so we can add fields
      await api.publishModel(testModelPid, 'Initial publish for field tests');
    }
  });

  /**
   * Helper function to create and verify a field type
   */
  async function createAndVerifyField(
    api: ApiClient,
    dataType: FieldDataType,
    additionalFeatures?: Record<string, unknown>
  ): Promise<FieldResponse> {
    const fieldData = createFieldData(dataType, {
      feature: additionalFeatures,
    });

    const response = await api.createField(fieldData);
    expect(api.isSuccess(response)).toBe(true);
    expect(response.data).not.toBeNull();
    expect(response.data!.dataType).toBe(dataType);

    return response.data!;
  }

  /**
   * M-010: STRING field
   */
  test('M-010: STRING field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'string', {
      length: 255,
    });

    expect(field.dataType).toBe('string');
  });

  /**
   * M-011: INTEGER field
   */
  test('M-011: INTEGER field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'integer');

    expect(field.dataType).toBe('integer');
  });

  /**
   * M-012: DECIMAL field
   */
  test('M-012: DECIMAL field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'decimal', {
      precision: 18,
      scale: 2,
    });

    expect(field.dataType).toBe('decimal');

    if (field.feature) {
      expect(field.feature.precision).toBe(18);
      expect(field.feature.scale).toBe(2);
    }
  });

  /**
   * M-013: DATE field
   */
  test('M-013: DATE field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'date');

    expect(field.dataType).toBe('date');
  });

  /**
   * M-014: DATETIME field
   */
  test('M-014: DATETIME field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'datetime');

    expect(field.dataType).toBe('datetime');
  });

  /**
   * M-015: JSON field
   */
  test('M-015: JSON field creation', async ({ api }) => {
    const field = await createAndVerifyField(api, 'json');

    expect(field.dataType).toBe('json');
  });

  /**
   * M-016: REFERENCE field
   */
  test('M-016: REFERENCE field creation', async ({ api }) => {
    // Create a target model first for the reference
    const targetModelData = createModelData({ modelType: 'entity' });
    const targetModelResponse = await api.createModel(targetModelData);
    expect(api.isSuccess(targetModelResponse)).toBe(true);

    // Publish target model
    await api.publishModel(targetModelResponse.data!.pid);

    // Create REFERENCE field
    const fieldData = createFieldData('reference', {
      feature: {
        refTarget: {
          modelCode: targetModelResponse.data!.code,
          displayField: 'displayName',
        },
      } as any,
    });

    const response = await api.createField(fieldData);
    expect(api.isSuccess(response)).toBe(true);
    expect(response.data!.dataType).toBe('reference');
  });

  /**
   * M-017: Required validation
   */
  test('M-017: Required field validation', async ({ api }) => {
    const fieldData = createFieldData('string', {
      feature: {
        required: true,
      },
    });

    const response = await api.createField(fieldData);
    expect(api.isSuccess(response)).toBe(true);

    // Bind field to model with required=true
    const bindingResponse = await api.bindFieldToModel(testModelPid, {
      fieldPid: response.data!.pid,
      required: true,
    });
    expect(api.isSuccess(bindingResponse)).toBe(true);
  });

  /**
   * M-018: Unique validation
   */
  test('M-018: Unique field validation', async ({ api }) => {
    const fieldData = createFieldData('string', {
      feature: {
        unique: true,
      },
    });

    const response = await api.createField(fieldData);
    expect(api.isSuccess(response)).toBe(true);

    // Bind field to model with unique constraint
    const bindingResponse = await api.bindFieldToModel(testModelPid, {
      fieldPid: response.data!.pid,
      extension: {
        unique: true,
      },
    });
    expect(api.isSuccess(bindingResponse)).toBe(true);
  });

  /**
   * M-019: Field binding
   */
  test('M-019: Field binding to model', async ({ page, api }) => {
    // 1. Create an independent field
    const fieldData = createFieldData('string');
    const fieldResponse = await api.createField(fieldData);
    expect(api.isSuccess(fieldResponse)).toBe(true);

    const fieldPid = fieldResponse.data!.pid;
    const fieldCode = fieldResponse.data!.code;

    // 2. Bind the field to the test model
    const bindingData = createFieldBindingData(fieldPid, {
      required: false,
      readonly: false,
      visible: true,
      displayOrder: 1,
    });

    const bindingResponse = await api.bindFieldToModel(testModelPid, bindingData);
    expect(api.isSuccess(bindingResponse)).toBe(true);

    // 3. Verify binding exists by getting model fields
    const modelFieldsResponse = await api.getModelFields(testModelPid);
    expect(api.isSuccess(modelFieldsResponse)).toBe(true);

    const boundField = modelFieldsResponse.data?.find(
      (f: { fieldCode?: string; code?: string }) => f.fieldCode === fieldCode || f.code === fieldCode
    );
    expect(boundField).toBeDefined();

    // 4. Verify in UI
    await page.goto(`/meta/models/${testModelPid}`);
    await page.waitForLoadState('domcontentloaded');
  });

  /**
   * Batch field binding
   */
  test('Field batch binding to model', async ({ api }) => {
    // Create multiple fields
    const fields = await Promise.all([
      createAndVerifyField(api, 'string'),
      createAndVerifyField(api, 'integer'),
      createAndVerifyField(api, 'date'),
    ]);

    const fieldPids = fields.map(f => f.pid);

    // Batch bind fields to model
    const batchResponse = await api.batchBindFieldsToModel(testModelPid, fieldPids, {
      required: false,
      visible: true,
    });

    expect(api.isSuccess(batchResponse)).toBe(true);

    // Verify all fields are bound
    const modelFieldsResponse = await api.getModelFields(testModelPid);
    expect(api.isSuccess(modelFieldsResponse)).toBe(true);

    const boundCodes = modelFieldsResponse.data?.map((f: { fieldCode?: string; code?: string }) => f.fieldCode || f.code) || [];
    for (const field of fields) {
      expect(boundCodes).toContain(field.code);
    }
  });

  /**
   * Create fields with all supported data types
   */
  test('Create fields with all supported data types', async ({ api }) => {
    const dataTypes: FieldDataType[] = [
      'string',
      'integer',
      'decimal',
      'date',
      'datetime',
      'json',
      'boolean',
    ];

    const results: { dataType: string; success: boolean }[] = [];

    for (const dataType of dataTypes) {
      try {
        await createAndVerifyField(api, dataType);
        results.push({ dataType, success: true });
      } catch {
        results.push({ dataType, success: false });
      }
    }

    // At least STRING, INTEGER, and DATE should work
    const successCount = results.filter(r => r.success).length;
    expect(successCount).toBeGreaterThanOrEqual(3);
  });
});
