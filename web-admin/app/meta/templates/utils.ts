/**
 * Template utility functions
 *
 * Shared helpers for mapping field metadata to component types,
 * validation rules, and display formats.
 *
 * @since 3.8.0
 */

import type { ValidationRule } from '~/meta/schemas/types';
import type { TemplateFieldMeta, FieldDataType } from './types';

/**
 * Map a field's data type to the appropriate Smart component name.
 */
export function mapFieldToComponent(field: TemplateFieldMeta): string {
  if (field.dataSourceId || field.options) {
    return 'SmartSelect';
  }

  switch (field.type) {
    case 'string':
      return 'SmartInput';
    case 'number':
      return 'SmartNumber';
    case 'boolean':
      return 'SmartSwitch';
    case 'date':
      return 'SmartDatePicker';
    case 'datetime':
      return 'SmartDateTimePicker';
    case 'enum':
      return 'SmartSelect';
    case 'text':
      return 'SmartTextarea';
    case 'image':
      return 'SmartImageUpload';
    case 'file':
      return 'SmartFileUpload';
    case 'relation':
      return 'SmartSelect';
    default:
      return 'SmartInput';
  }
}

type ColumnValueType = 'text' | 'date' | 'datetime' | 'currency' | 'tag' | 'progress' | 'image';

/**
 * Map a field's data type to a column valueType for display formatting.
 */
export function mapFieldToValueType(type: FieldDataType): ColumnValueType | undefined {
  switch (type) {
    case 'date':
      return 'date';
    case 'datetime':
      return 'datetime';
    case 'number':
      return 'text';
    case 'enum':
      return 'tag';
    case 'image':
      return 'image';
    default:
      return undefined;
  }
}

/**
 * Build validation rules from field metadata.
 */
export function buildValidationRules(field: TemplateFieldMeta): ValidationRule[] | undefined {
  const rules: ValidationRule[] = [];

  if (field.required) {
    rules.push({
      type: 'required',
      message: `${field.label}不能为空`,
    });
  }

  if (field.maxLength) {
    rules.push({
      type: 'maxLength',
      max: field.maxLength,
      message: `${field.label}不能超过${field.maxLength}个字符`,
    });
  }

  if (field.type === 'number') {
    rules.push({
      type: 'number',
      message: `${field.label}必须为数字`,
    });
  }

  return rules.length > 0 ? rules : undefined;
}
