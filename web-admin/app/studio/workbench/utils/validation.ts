/**
 * Validation Utilities
 *
 * Schema 和组件验证相关的工具函数
 */

import type { FormSchema, Block } from '~/studio/domain/schema/types';

/**
 * 验证结果接口
 */
export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
}

/**
 * 验证错误接口
 */
export interface ValidationError {
  code: string;
  message: string;
  path?: string;
  componentId?: string;
}

/**
 * 验证警告接口
 */
export interface ValidationWarning {
  code: string;
  message: string;
  path?: string;
  componentId?: string;
}

/**
 * 验证 Schema
 */
export function validateSchema(schema: FormSchema): ValidationResult {
  const errors: ValidationError[] = [];
  const warnings: ValidationWarning[] = [];

  // 验证基本字段
  if (!schema.id) {
    errors.push({
      code: 'missing_id',
      message: 'Schema ID is required',
    });
  }

  if (!schema.title) {
    errors.push({
      code: 'missing_title',
      message: 'Schema title is required',
    });
  }

  if (!schema.version) {
    errors.push({
      code: 'missing_version',
      message: 'Schema version is required',
    });
  }

  // 验证组件
  if (schema.components) {
    validateComponents(schema.components, errors, warnings);
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
  };
}

/**
 * 验证组件列表
 */
function validateComponents(
  components: Block[],
  errors: ValidationError[],
  warnings: ValidationWarning[],
  path = 'components',
): void {
  components.forEach((component, index) => {
    const componentPath = `${path}[${index}]`;
    validateComponent(component, errors, warnings, componentPath);

    if (component.children) {
      validateComponents(component.children, errors, warnings, `${componentPath}.children`);
    }
  });
}

/**
 * 验证单个组件
 */
function validateComponent(
  component: Block,
  errors: ValidationError[],
  warnings: ValidationWarning[],
  path: string,
): void {
  // 验证必需字段
  if (!component.id) {
    errors.push({
      code: 'missing_component_id',
      message: 'Component ID is required',
      path,
      componentId: component.id,
    });
  }

  if (!component.type) {
    errors.push({
      code: 'missing_component_type',
      message: 'Component type is required',
      path,
      componentId: component.id,
    });
  }

  // 验证 ID 唯一性（这里简化处理，实际应该在更高层级验证）
  if (component.id && !isValidId(component.id)) {
    errors.push({
      code: 'invalid_component_id',
      message: 'Component ID contains invalid characters',
      path,
      componentId: component.id,
    });
  }
}

/**
 * 验证 ID 格式
 */
function isValidId(id: string): boolean {
  return /^[a-zA-Z0-9_-]+$/.test(id);
}

/**
 * 验证布局配置
 */
export function validateLayout(layout: any): ValidationResult {
  const errors: ValidationError[] = [];
  const warnings: ValidationWarning[] = [];

  if (layout.type && !['grid', 'flex', 'absolute'].includes(layout.type)) {
    errors.push({
      code: 'invalid_layout_type',
      message: 'Layout type must be one of: grid, flex, absolute',
    });
  }

  if (layout.columns && (typeof layout.columns !== 'number' || layout.columns < 1)) {
    errors.push({
      code: 'invalid_columns',
      message: 'Columns must be a positive number',
    });
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
  };
}

/**
 * 验证主题配置
 */
export function validateTheme(theme: any): ValidationResult {
  const errors: ValidationError[] = [];
  const warnings: ValidationWarning[] = [];

  if (theme.colors) {
    Object.entries(theme.colors).forEach(([key, value]) => {
      if (typeof value !== 'string' || !isValidColor(value as string)) {
        errors.push({
          code: 'invalid_color',
          message: `Invalid color value for ${key}: ${value}`,
        });
      }
    });
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
  };
}

/**
 * 验证颜色格式
 */
function isValidColor(color: string): boolean {
  return (
    /^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(color) ||
    /^rgb\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*\)$/.test(color) ||
    /^rgba\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*[\d.]+\s*\)$/.test(color)
  );
}

/**
 * 验证元数据
 */
export function validateMetadata(metadata: any): ValidationResult {
  const errors: ValidationError[] = [];
  const warnings: ValidationWarning[] = [];

  if (metadata.createdAt && !isValidDate(metadata.createdAt)) {
    errors.push({
      code: 'invalid_created_at',
      message: 'Invalid createdAt date format',
    });
  }

  if (metadata.updatedAt && !isValidDate(metadata.updatedAt)) {
    errors.push({
      code: 'invalid_updated_at',
      message: 'Invalid updatedAt date format',
    });
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
  };
}

/**
 * 验证日期格式
 */
function isValidDate(dateString: string): boolean {
  const date = new Date(dateString);
  return !isNaN(date.getTime());
}
