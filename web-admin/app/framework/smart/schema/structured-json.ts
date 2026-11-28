import Ajv, { type ErrorObject, type ValidateFunction } from 'ajv';
import type { LocalizedText } from '~/framework/meta/schemas/types';
import type { DataSourceConfig } from './smart-components';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

/** Explicit, bounded JSON-schema form vocabulary. Unknown composition is rejected. */
export interface StructuredJsonSchema {
  type?: 'object' | 'array' | 'string' | 'number' | 'integer' | 'boolean';
  title?: string;
  description?: string;
  properties?: Record<string, StructuredJsonSchema>;
  items?: StructuredJsonSchema;
  required?: string[];
  additionalProperties?: boolean;
  enum?: Array<string | number | boolean>;
  const?: string | number | boolean;
  default?: unknown;
  minItems?: number;
  maxItems?: number;
  uniqueItems?: boolean;
  minLength?: number;
  maxLength?: number;
  pattern?: string;
  minimum?: number;
  maximum?: number;
  readOnly?: boolean;
  writeOnly?: boolean;
  if?: StructuredJsonSchema;
  then?: StructuredJsonSchema;
  else?: StructuredJsonSchema;
  'x-label'?: LocalizedText;
  'x-helpText'?: LocalizedText;
  'x-placeholder'?: LocalizedText;
  'x-enumLabels'?: LocalizedText[];
  'x-dataSource'?: DataSourceConfig;
  'x-hidden'?: boolean;
}

const ajv = new Ajv({ allErrors: true, strict: false, logger: false, ownProperties: true });
const validators = new WeakMap<StructuredJsonSchema, ValidateFunction>();
const conditionValidators = new WeakMap<StructuredJsonSchema, ValidateFunction>();
const KEYS = new Set([
  'type',
  'title',
  'description',
  'properties',
  'items',
  'required',
  'additionalProperties',
  'enum',
  'const',
  'default',
  'minItems',
  'maxItems',
  'uniqueItems',
  'minLength',
  'maxLength',
  'pattern',
  'minimum',
  'maximum',
  'readOnly',
  'writeOnly',
  'if',
  'then',
  'else',
  'x-label',
  'x-helpText',
  'x-placeholder',
  'x-enumLabels',
  'x-dataSource',
  'x-hidden',
]);
const RESERVED_FIELDS = new Set(['__proto__', 'prototype', 'constructor']);

export class StructuredJsonSchemaError extends Error {}

function checkSchema(schema: StructuredJsonSchema, depth = 0, conditional = false): void {
  if (
    !schema ||
    typeof schema !== 'object' ||
    Array.isArray(schema) ||
    depth > 12 ||
    Object.keys(schema).some((key) => !KEYS.has(key))
  )
    throw new StructuredJsonSchemaError('Unsupported structured form schema');
  if (
    !conditional &&
    !['object', 'array', 'string', 'number', 'integer', 'boolean'].includes(schema.type || '')
  )
    throw new StructuredJsonSchemaError('A concrete field type is required');
  if (schema.properties) {
    if (
      typeof schema.properties !== 'object' ||
      Array.isArray(schema.properties) ||
      Object.keys(schema.properties).length > 100 ||
      Object.keys(schema.properties).some((key) => RESERVED_FIELDS.has(key))
    )
      throw new StructuredJsonSchemaError('Invalid object fields');
    for (const child of Object.values(schema.properties))
      checkSchema(child, depth + 1, conditional);
  }
  if (Array.isArray(schema.required) && schema.required.some((key) => RESERVED_FIELDS.has(key)))
    throw new StructuredJsonSchemaError('Invalid object fields');
  if (schema.type === 'array') {
    if (
      !schema.items ||
      (schema.maxItems !== undefined &&
        (!Number.isInteger(schema.maxItems) || schema.maxItems < 0 || schema.maxItems > 100))
    )
      throw new StructuredJsonSchemaError('Invalid bounded collection');
    checkSchema(schema.items, depth + 1);
  }
  if (schema['x-dataSource']) {
    if (schema.type !== 'string')
      throw new StructuredJsonSchemaError('Reference values must be strings');
    if (!['api', 'static'].includes(schema['x-dataSource'].type || 'api'))
      throw new StructuredJsonSchemaError('Unsupported reference data source');
  }
  if (schema['x-enumLabels'] && schema['x-enumLabels'].length !== schema.enum?.length)
    throw new StructuredJsonSchemaError('Choice labels must match values');
  for (const child of [schema.if, schema.then, schema.else])
    if (child) checkSchema(child, depth + 1, true);
}

export function structuredJsonValidator(schema: StructuredJsonSchema): ValidateFunction {
  let validator = validators.get(schema);
  if (!validator) {
    if (!schema || !['object', 'array'].includes(schema.type || ''))
      throw new StructuredJsonSchemaError('A JSON object or array form is required');
    checkSchema(schema);
    try {
      validator = ajv.compile(schema);
    } catch {
      throw new StructuredJsonSchemaError('Invalid structured form schema');
    }
    validators.set(schema, validator);
  }
  return validator;
}

export function structuredJsonConfigurationError(
  schema: StructuredJsonSchema,
  locale = 'en',
): string | undefined {
  try {
    structuredJsonValidator(schema);
    return undefined;
  } catch {
    return locale.startsWith('zh')
      ? '结构化表单配置不可用，请联系管理员。'
      : 'The structured form configuration is unavailable. Contact an administrator.';
  }
}

export function resolveStructuredSchema(
  schema: StructuredJsonSchema,
  value: unknown,
): StructuredJsonSchema {
  if (!schema.if) return schema;
  let validate = conditionValidators.get(schema.if);
  if (!validate) {
    validate = ajv.compile(schema.if);
    conditionValidators.set(schema.if, validate);
  }
  const branch = validate(value) ? schema.then : schema.else;
  return branch
    ? {
        ...schema,
        ...branch,
        properties: { ...schema.properties, ...branch.properties },
        required: [...new Set([...(schema.required || []), ...(branch.required || [])])],
      }
    : schema;
}

export function structuredJsonDefault(schema: StructuredJsonSchema): unknown {
  if (schema.default !== undefined) return structuredClone(schema.default);
  if (schema.const !== undefined) return schema.const;
  if (schema.type === 'object') {
    const value: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(schema.properties || {})) {
      if (child.default !== undefined || child.const !== undefined)
        value[key] = structuredJsonDefault(child);
    }
    return value;
  }
  if (schema.type === 'array') return [];
  if (schema.type === 'boolean') return false;
  return undefined;
}

export function structuredJsonLabel(schema: StructuredJsonSchema, locale: string): string {
  return getLocalizedText(schema['x-label'] || schema.title || '', locale);
}

function schemaAtPath(schema: StructuredJsonSchema, path: string): StructuredJsonSchema {
  let result = schema;
  for (const segment of path
    .split('/')
    .slice(1)
    .map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'))) {
    result =
      result.type === 'array' ? result.items || result : result.properties?.[segment] || result;
  }
  return result;
}

export function structuredJsonValueWithinLimits(value: unknown, depth = 0): boolean {
  if (depth > 32) return false;
  if (typeof value === 'number')
    return Number.isFinite(value) && (!Number.isInteger(value) || Number.isSafeInteger(value));
  if (Array.isArray(value))
    return (
      value.length <= 100 && value.every((item) => structuredJsonValueWithinLimits(item, depth + 1))
    );
  return (
    !value ||
    typeof value !== 'object' ||
    Object.values(value).every((item) => structuredJsonValueWithinLimits(item, depth + 1))
  );
}

export function structuredJsonError(
  value: unknown,
  schema: StructuredJsonSchema,
  locale = 'en',
): string | undefined {
  const zh = locale.startsWith('zh');
  let validate: ValidateFunction;
  try {
    validate = structuredJsonValidator(schema);
  } catch {
    return zh
      ? '结构化表单配置不可用，请联系管理员。'
      : 'The structured form configuration is unavailable. Contact an administrator.';
  }
  let parsed = value;
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value);
    } catch {
      return zh
        ? '结构化内容无效，请重新加载后重试。'
        : 'The structured value is invalid. Reload and try again.';
    }
  }
  if (!structuredJsonValueWithinLimits(parsed))
    return zh
      ? '内容超出可编辑范围或数字精度限制。'
      : 'The value exceeds the editable size or numeric precision limit.';
  if (validate(parsed)) return undefined;
  const error = (validate.errors || []).find((item) => item.keyword !== 'if') as
    | ErrorObject
    | undefined;
  const target =
    error?.keyword === 'required'
      ? `${error.instancePath}/${error.params.missingProperty}`
      : error?.instancePath || '';
  const label =
    structuredJsonLabel(schemaAtPath(schema, target), locale) ||
    structuredJsonLabel(schema, locale) ||
    (zh ? '此项' : 'This field');
  return error?.keyword === 'required'
    ? zh
      ? `请填写${label}。`
      : `${label} is required.`
    : zh
      ? `请检查${label}的格式或取值范围。`
      : `Check the format or allowed range for ${label}.`;
}
