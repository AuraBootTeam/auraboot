import type { DataType } from '../ast/conditionAst';

export interface DecisionOutputSchemaField {
  id: string;
  label: string;
  dataType?: DataType | string;
  allowedValues?: unknown[];
  valueLabels?: Record<string, string>;
}

export type DecisionOutputSchemaSource =
  | string
  | {
      id?: string;
      code?: string;
      output?: string;
      label?: string;
      name?: string;
      dataType?: DataType | string;
      type?: string;
      allowedValues?: unknown[];
      valueLabels?: Record<string, string>;
      options?: unknown[];
      enum?: unknown[];
    };

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function arrayValue(value: unknown): unknown[] | undefined {
  return Array.isArray(value) ? value : undefined;
}

function valueLabels(value: unknown): Record<string, string> | undefined {
  const record = recordOf(value);
  if (!record) return undefined;
  return Object.fromEntries(
    Object.entries(record).filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
  );
}

function fieldFromSource(value: unknown): DecisionOutputSchemaField | null {
  if (typeof value === 'string') {
    const id = value.trim();
    return id ? { id, label: id } : null;
  }
  const record = recordOf(value);
  if (!record) return null;
  const id =
    stringValue(record.id) ??
    stringValue(record.code) ??
    stringValue(record.output) ??
    stringValue(record.name);
  if (!id) return null;
  return {
    id,
    label: stringValue(record.label) ?? stringValue(record.name) ?? id,
    dataType: stringValue(record.dataType) ?? stringValue(record.type),
    allowedValues:
      arrayValue(record.allowedValues) ?? arrayValue(record.options) ?? arrayValue(record.enum),
    valueLabels: valueLabels(record.valueLabels),
  };
}

function fieldsFromJsonSchemaProperties(properties: unknown): DecisionOutputSchemaField[] {
  const record = recordOf(properties);
  if (!record) return [];
  return Object.entries(record)
    .map(([id, definition]) => {
      const property = recordOf(definition) ?? {};
      return fieldFromSource({
        id,
        label: stringValue(property.title) ?? stringValue(property.label) ?? id,
        dataType: stringValue(property.dataType) ?? stringValue(property.type),
        allowedValues: arrayValue(property.enum) ?? arrayValue(property.allowedValues),
        valueLabels: valueLabels(property.valueLabels),
      });
    })
    .filter((field): field is DecisionOutputSchemaField => Boolean(field));
}

function fieldsFromSchema(schema: unknown): DecisionOutputSchemaField[] {
  if (!schema) return [];
  if (Array.isArray(schema)) {
    return schema
      .map((item) => fieldFromSource(item))
      .filter((field): field is DecisionOutputSchemaField => Boolean(field));
  }
  const record = recordOf(schema);
  if (!record) return [];
  return [
    ...fieldsFromSchema(record.outputs),
    ...fieldsFromSchema(record.fields),
    ...fieldsFromJsonSchemaProperties(record.properties),
  ];
}

export function normalizeDecisionOutputFields(
  ...sources: unknown[]
): DecisionOutputSchemaField[] {
  const seen = new Set<string>();
  return sources
    .flatMap((source) => fieldsFromSchema(source))
    .filter((field) => {
      if (!field.id || seen.has(field.id)) return false;
      seen.add(field.id);
      return true;
    });
}
