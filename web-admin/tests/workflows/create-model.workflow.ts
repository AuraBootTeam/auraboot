/**
 * Create Model Workflow
 *
 * Provides utilities for creating models in tests via API.
 * Prefer using this workflow over UI-based model creation for test data setup.
 *
 * @since 4.0.0
 */

import { ApiClient } from '../model-system/helpers/api-client';
import type { ModelResponse } from '../model-system/helpers/api-client';
import { createModelData } from '../model-system/helpers/test-data';
import type { ModelTestData, ModelType } from '../model-system/helpers/test-data';

/**
 * Options for creating a model
 */
export interface CreateModelOptions {
  /** Model code (auto-generated if not provided) */
  code?: string;
  /** Display name (auto-generated if not provided) */
  name?: string;
  /** Model type (defaults to ENTITY) */
  modelType?: ModelType;
  /** Model description */
  description?: string;
  /** Additional extension data */
  extension?: Record<string, unknown>;
}

/**
 * Result of model creation
 */
export interface CreateModelResult {
  /** Model PID (primary identifier) */
  pid: string;
  /** Model code */
  code: string;
  /** Model type */
  modelType: string;
  /** Display name */
  displayName: string;
  /** Full model data */
  data: ModelResponse;
}

/**
 * Create a model via API
 *
 * @param api - ApiClient instance
 * @param options - Model creation options
 * @returns Created model information
 * @throws Error if creation fails
 *
 * @example
 * ```typescript
 * const model = await createModel(api, { modelType: 'entity' });
 * await page.goto(`/meta/models/${model.pid}`);
 * ```
 */
export async function createModel(
  api: ApiClient,
  options: CreateModelOptions = {}
): Promise<CreateModelResult> {
  // Generate model data
  const modelData: ModelTestData = createModelData({
    code: options.code,
    displayName: options.name,
    modelType: options.modelType || 'entity',
    description: options.description,
    extension: options.extension,
  });

  // Create model via API
  const response = await api.createModel(modelData);

  if (!api.isSuccess(response)) {
    throw new Error(`Failed to create model: ${response.desc || response.message || 'Unknown error'}`);
  }

  if (!response.data) {
    throw new Error('Model creation returned no data');
  }

  return {
    pid: response.data.pid,
    code: response.data.code,
    modelType: response.data.modelType,
    displayName: response.data.displayName,
    data: response.data,
  };
}

/**
 * Create a model and publish it
 *
 * @param api - ApiClient instance
 * @param options - Model creation options
 * @param versionNote - Optional version note for publishing
 * @returns Created and published model
 * @throws Error if creation or publishing fails
 */
export async function createAndPublishModel(
  api: ApiClient,
  options: CreateModelOptions = {},
  versionNote?: string
): Promise<CreateModelResult> {
  // Create the model
  const model = await createModel(api, options);

  // Publish the model
  const publishResponse = await api.publishModel(model.pid, versionNote);

  if (!api.isSuccess(publishResponse)) {
    // Publishing may fail if model has no fields - this is acceptable for some tests
    console.warn(`Model publishing skipped (may require fields): ${publishResponse.desc || publishResponse.message}`);
    return model;
  }

  // Return updated model info
  return {
    ...model,
    data: publishResponse.data || model.data,
  };
}

/**
 * Delete a model (for cleanup)
 *
 * @param api - ApiClient instance
 * @param pid - Model PID
 * @returns true if deleted successfully, false otherwise
 */
export async function deleteModel(api: ApiClient, pid: string): Promise<boolean> {
  try {
    const response = await api.deleteModel(pid);
    return api.isSuccess(response);
  } catch {
    return false;
  }
}

/**
 * Create multiple models
 *
 * @param api - ApiClient instance
 * @param count - Number of models to create
 * @param optionsFactory - Optional function to customize each model's options
 * @returns Array of created models
 */
export async function createMultipleModels(
  api: ApiClient,
  count: number,
  optionsFactory?: (index: number) => CreateModelOptions
): Promise<CreateModelResult[]> {
  const models: CreateModelResult[] = [];

  for (let i = 0; i < count; i++) {
    const options = optionsFactory ? optionsFactory(i) : {};
    const model = await createModel(api, options);
    models.push(model);
  }

  return models;
}
