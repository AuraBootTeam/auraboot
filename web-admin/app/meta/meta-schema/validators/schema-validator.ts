/**
 * Schema Validator - DSL Schema 验证器
 *
 * @deprecated Use ~/meta/validation/DslValidator instead.
 * This file is kept for backward compatibility only.
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import {
  validateAll,
  validateStructure,
  isValidSchema as newIsValidSchema,
} from '~/meta/validation/DslValidator';

export interface ValidationError {
  path: string;
  message: string;
  code: string;
}

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

/**
 * JSON Schema 定义 - UnifiedSchema
 */
const UNIFIED_SCHEMA_JSON_SCHEMA = {
  type: 'object',
  required: ['kind', 'version', 'id', 'title', 'layout', 'areas'],
  properties: {
    kind: {
      type: 'string',
      enum: ['Page', 'List', 'Form', 'PageLayout'],
    },
    version: {
      type: 'string',
      pattern: '^\\d+\\.\\d+\\.\\d+$',
    },
    id: {
      type: 'string',
      minLength: 1,
    },
    title: {
      oneOf: [
        { type: 'string' },
        {
          type: 'object',
          properties: {
            'zh-CN': { type: 'string' },
            'en-US': { type: 'string' },
            'ja-JP': { type: 'string' },
            'ko-KR': { type: 'string' },
          },
        },
      ],
    },
    layout: {
      type: 'object',
      required: ['type', 'areas'],
      properties: {
        type: {
          type: 'string',
          enum: ['grid', 'flex'],
        },
        areas: {
          type: 'array',
          items: {
            type: 'array',
            items: { type: 'string' },
          },
        },
        columns: { type: 'string' },
        rows: { type: 'string' },
        gap: { type: 'string' },
        direction: {
          type: 'string',
          enum: ['row', 'column'],
        },
      },
    },
    areas: {
      type: 'object',
      additionalProperties: {
        type: 'object',
        required: ['blocks'],
        properties: {
          blocks: {
            type: 'array',
            items: {
              type: 'object',
              required: ['type'],
              properties: {
                type: {
                  type: 'string',
                  enum: [
                    'form',
                    'table',
                    'filters',
                    'toolbar',
                    'action',
                    'description',
                    'chart',
                    'tabs',
                    'custom',
                  ],
                },
                visibleWhen: { type: 'string' },
                className: { type: 'string' },
              },
            },
          },
          visibleWhen: { type: 'string' },
          className: { type: 'string' },
        },
      },
    },
    dataSources: {
      type: 'object',
      additionalProperties: {
        type: 'object',
        required: ['type'],
        properties: {
          type: {
            type: 'string',
            enum: ['api', 'dict', 'static'],
          },
        },
      },
    },
    handlers: {
      type: 'object',
      additionalProperties: {
        type: 'object',
        required: ['type'],
      },
    },
  },
};

/**
 * 验证 Schema
 */
export function validateSchema(schema: UnifiedSchema): ValidationResult {
  const errors: ValidationError[] = [];

  // 1. 基础结构验证
  if (!schema.kind) {
    errors.push({
      path: 'kind',
      message: 'Missing required field: kind',
      code: 'required_field',
    });
  } else if (!['Page', 'List', 'Form', 'PageLayout'].includes(schema.kind)) {
    errors.push({
      path: 'kind',
      message: `Invalid kind: ${schema.kind}`,
      code: 'invalid_value',
    });
  }

  if (!schema.version) {
    errors.push({
      path: 'version',
      message: 'Missing required field: version',
      code: 'required_field',
    });
  } else if (!/^\d+\.\d+\.\d+$/.test(schema.version)) {
    errors.push({
      path: 'version',
      message: `Invalid version format: ${schema.version}`,
      code: 'invalid_format',
    });
  }

  if (!schema.id) {
    errors.push({
      path: 'id',
      message: 'Missing required field: id',
      code: 'required_field',
    });
  }

  if (!schema.title) {
    errors.push({
      path: 'title',
      message: 'Missing required field: title',
      code: 'required_field',
    });
  }

  // 2. Layout 验证
  if (!schema.layout) {
    errors.push({
      path: 'layout',
      message: 'Missing required field: layout',
      code: 'required_field',
    });
  } else {
    if (!schema.layout.areas || schema.layout.areas.length === 0) {
      errors.push({
        path: 'layout.areas',
        message: 'Layout areas must not be empty',
        code: 'required_field',
      });
    }

    // Validate areasConfig if present
    if (schema.layout.areasConfig) {
      Object.entries(schema.layout.areasConfig).forEach(([areaId, config]) => {
        if (config.type && !['grid', 'flex'].includes(config.type)) {
          errors.push({
            path: `layout.areasConfig.${areaId}.type`,
            message: `Invalid area layout type: ${config.type}`,
            code: 'invalid_value',
          });
        }
      });
    }
  }

  // 3. Areas 验证
  if (!schema.areas) {
    errors.push({
      path: 'areas',
      message: 'Missing required field: areas',
      code: 'required_field',
    });
  } else {
    // 验证 layout.areas 中引用的区域都存在
    const layoutAreaIds = new Set(schema.layout.areas);
    const areaIds = new Set(Object.keys(schema.areas));

    layoutAreaIds.forEach((areaId) => {
      if (!areaIds.has(areaId)) {
        errors.push({
          path: `areas.${areaId}`,
          message: `Area referenced in layout but not defined: ${areaId}`,
          code: 'missing_area',
        });
      }
    });

    // 验证每个 area 的 blocks
    Object.entries(schema.areas).forEach(([areaId, areaConfig]) => {
      if (!areaConfig.blocks || areaConfig.blocks.length === 0) {
        errors.push({
          path: `areas.${areaId}.blocks`,
          message: `Area blocks must not be empty: ${areaId}`,
          code: 'required_field',
        });
      } else {
        areaConfig.blocks.forEach((block, index) => {
          if (!block.type) {
            errors.push({
              path: `areas.${areaId}.blocks[${index}].type`,
              message: 'Block type is required',
              code: 'required_field',
            });
          }
        });
      }
    });
  }

  // 4. DataSource 引用验证
  if (schema.areas) {
    Object.entries(schema.areas).forEach(([areaId, areaConfig]) => {
      areaConfig.blocks.forEach((block, index) => {
        if (block.dataSource) {
          if (!schema.dataSources || !schema.dataSources[block.dataSource]) {
            errors.push({
              path: `areas.${areaId}.blocks[${index}].dataSource`,
              message: `DataSource not found: ${block.dataSource}`,
              code: 'missing_data_source',
            });
          }
        }
      });
    });
  }

  return {
    valid: errors.length === 0,
    errors,
  };
}

/**
 * 验证 Schema 版本兼容性
 */
export function validateVersion(schema: UnifiedSchema, minVersion: string): ValidationResult {
  const errors: ValidationError[] = [];

  const schemaVersion = parseVersion(schema.version);
  const minRequiredVersion = parseVersion(minVersion);

  if (compareVersions(schemaVersion, minRequiredVersion) < 0) {
    errors.push({
      path: 'version',
      message: `Schema version ${schema.version} is older than required ${minVersion}`,
      code: 'version_too_old',
    });
  }

  return {
    valid: errors.length === 0,
    errors,
  };
}

/**
 * 解析版本号
 */
function parseVersion(version: string): [number, number, number] {
  const parts = version.split('.').map(Number);
  return [parts[0] || 0, parts[1] || 0, parts[2] || 0];
}

/**
 * 比较版本号
 * 返回: -1 (a < b), 0 (a = b), 1 (a > b)
 */
function compareVersions(a: [number, number, number], b: [number, number, number]): number {
  for (let i = 0; i < 3; i++) {
    if (a[i] < b[i]) return -1;
    if (a[i] > b[i]) return 1;
  }
  return 0;
}

/**
 * 快速验证 - 只返回是否有效
 */
export function isValidSchema(schema: UnifiedSchema): boolean {
  return validateSchema(schema).valid;
}
