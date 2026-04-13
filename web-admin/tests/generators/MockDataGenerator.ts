/**
 * MockDataGenerator - generates realistic test data from field metadata.
 * Used by TestGenerator to create mock records for API mocking.
 *
 * @since 4.0.0
 */

import type { TemplateFieldMeta, FieldDataType } from '../../app/meta/templates/types';

export interface MockGeneratorOptions {
  /** Number of records to generate */
  count?: number;
  /** Seed for deterministic generation */
  seed?: number;
  /** Override values for specific fields */
  overrides?: Record<string, any>;
}

/**
 * Generate mock records based on field metadata.
 */
export function generateMockRecords(
  fields: TemplateFieldMeta[],
  options: MockGeneratorOptions = {},
): Record<string, any>[] {
  const { count = 10, seed = 1, overrides = {} } = options;
  const records: Record<string, any>[] = [];

  for (let i = 0; i < count; i++) {
    const record: Record<string, any> = {
      id: `mock-${seed + i}`,
      pid: `pid-${seed + i}`,
    };

    for (const field of fields) {
      if (overrides[field.field] !== undefined) {
        record[field.field] = overrides[field.field];
      } else {
        record[field.field] = generateFieldValue(field, i, seed);
      }
    }

    records.push(record);
  }

  return records;
}

/**
 * Generate a single valid form submission payload.
 */
export function generateFormPayload(
  fields: TemplateFieldMeta[],
  overrides: Record<string, any> = {},
): Record<string, any> {
  const payload: Record<string, any> = {};

  for (const field of fields) {
    if (field.formVisible === false) continue;
    if (overrides[field.field] !== undefined) {
      payload[field.field] = overrides[field.field];
    } else {
      payload[field.field] = generateFieldValue(field, 0, 42);
    }
  }

  return { ...payload, ...overrides };
}

/**
 * Generate an invalid form payload (missing required fields).
 */
export function generateInvalidPayload(fields: TemplateFieldMeta[]): Record<string, any> {
  const payload: Record<string, any> = {};

  for (const field of fields) {
    if (field.formVisible === false) continue;
    if (field.required) {
      // Leave required fields empty to trigger validation
      payload[field.field] = field.type === 'number' ? null : '';
    } else {
      payload[field.field] = generateFieldValue(field, 0, 99);
    }
  }

  return payload;
}

function generateFieldValue(field: TemplateFieldMeta, index: number, seed: number): any {
  // If field has fixed options, pick one
  if (field.options && field.options.length > 0) {
    return field.options[(index + seed) % field.options.length].value;
  }

  switch (field.type) {
    case 'string':
      return generateString(field, index);
    case 'number':
      return generateNumber(field, index, seed);
    case 'boolean':
      return (index + seed) % 2 === 0;
    case 'date':
      return generateDate(index);
    case 'datetime':
      return generateDatetime(index);
    case 'enum':
      return `option_${(index % 3) + 1}`;
    case 'text':
      return generateText(field, index);
    case 'image':
      return `https://picsum.photos/seed/${seed + index}/200/200`;
    case 'file':
      return `file_${index + 1}.pdf`;
    case 'relation':
      return `rel-${(index % 5) + 1}`;
    default:
      return `value_${index + 1}`;
  }
}

function generateString(field: TemplateFieldMeta, index: number): string {
  const label = field.label ?? field.field;
  const suffix = index > 0 ? ` ${index + 1}` : '';
  const value = `${label}${suffix}`;
  if (field.maxLength && value.length > field.maxLength) {
    return value.slice(0, field.maxLength);
  }
  return value;
}

function generateNumber(field: TemplateFieldMeta, index: number, seed: number): number {
  const base = ((seed + index) * 17) % 1000;
  return base + 1;
}

function generateDate(index: number): string {
  const date = new Date(2024, 0, 1 + index);
  return date.toISOString().split('T')[0];
}

function generateDatetime(index: number): string {
  const date = new Date(2024, 0, 1 + index, 10, 30, 0);
  return date.toISOString();
}

function generateText(field: TemplateFieldMeta, index: number): string {
  return `${field.label ?? field.field} 的详细描述内容，第 ${index + 1} 条测试数据。`;
}
