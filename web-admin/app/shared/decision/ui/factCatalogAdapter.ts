import type { DataType, Operator, Scope } from '../ast/conditionAst';
import type {
  DecisionFact,
  DecisionFactCatalog,
  DecisionFactEntity,
  DecisionFactOption,
  DecisionModelField,
} from '../api/decisionApi';
import type { FieldOption } from './ConditionBuilder';

const FIELD_SCOPES = new Set<Scope>([
  'meta',
  'event',
  'record',
  'before',
  'after',
  'process',
  'task',
  'sla',
  'actor',
  'tenant',
  'time',
  'env',
]);

const DATA_TYPES = new Set<DataType>([
  'string',
  'text',
  'integer',
  'decimal',
  'boolean',
  'date',
  'time',
  'datetime',
  'duration',
  'enum',
  'dict',
  'user',
  'role',
  'group',
  'department',
  'collection',
  'object',
]);

const OPERATORS = new Set<Operator>([
  'EQ',
  'NE',
  'GT',
  'GTE',
  'LT',
  'LTE',
  'BETWEEN',
  'IN',
  'NOT_IN',
  'CONTAINS_TEXT',
  'STARTS_WITH',
  'ENDS_WITH',
  'IS_NULL',
  'IS_NOT_NULL',
  'IS_EMPTY',
  'IS_NOT_EMPTY',
  'CONTAINS_ELEMENT',
  'CHANGED',
]);

function normalizeScope(value: unknown): Scope {
  const raw = String(value ?? '').toLowerCase();
  return FIELD_SCOPES.has(raw as Scope) ? (raw as Scope) : 'record';
}

function normalizeReferenceDataType(reference: DecisionFact['reference']): DataType {
  const target = String(reference?.targetEntity ?? '').toLowerCase();
  if (target.includes('user')) return 'user';
  if (target.includes('role')) return 'role';
  if (target.includes('group')) return 'group';
  if (target.includes('department') || target.includes('dept')) return 'department';
  return 'object';
}

function normalizeDataType(value: unknown, reference?: DecisionFact['reference']): DataType {
  const raw = String(value ?? '').toLowerCase();
  if (raw === 'reference') return normalizeReferenceDataType(reference);
  if (raw === 'number' || raw === 'double' || raw === 'float') return 'decimal';
  if (raw === 'int' || raw === 'long') return 'integer';
  if (raw === 'bool') return 'boolean';
  return DATA_TYPES.has(raw as DataType) ? (raw as DataType) : 'string';
}

function splitScopedPath(scopeValue: unknown, rawPath: string): { scope: Scope; path: string } {
  const parts = rawPath.split('.').filter(Boolean);
  const first = parts[0] as Scope;
  const inferredScope = FIELD_SCOPES.has(first) && parts.length > 1 ? first : undefined;
  const scope = normalizeScope(scopeValue ?? inferredScope);
  const path = parts[0] === scope && parts.length > 1 ? parts.slice(1).join('.') : rawPath;
  return { scope, path };
}

function optionValue(option: DecisionFactOption): string {
  return String(option.value);
}

function optionsFromAllowedValues(values?: DecisionFactOption[]): Pick<FieldOption, 'options' | 'valueLabels'> {
  if (!values?.length) return {};
  const options = values.map(optionValue);
  const valueLabels = Object.fromEntries(
    values.map((option) => [optionValue(option), option.label ?? optionValue(option)]),
  );
  return { options, valueLabels };
}

function operatorsFromFact(operators?: string[]): Operator[] | undefined {
  const normalized = (operators ?? []).filter((operator): operator is Operator =>
    OPERATORS.has(operator as Operator),
  );
  return normalized.length > 0 ? normalized : undefined;
}

function factToFieldOption(fact: DecisionFact, entity?: DecisionFactEntity): FieldOption {
  const { scope, path } = splitScopedPath(fact.scope, fact.path);
  const modelCode = fact.modelCode ?? entity?.modelCode ?? entity?.entityCode;
  const modelName = fact.modelName ?? entity?.modelName ?? entity?.label;
  return {
    scope,
    path,
    label: fact.label ?? fact.path,
    dataType: normalizeDataType(fact.dataType, fact.reference),
    modelCode,
    modelName,
    entityCode: fact.entityCode ?? entity?.entityCode,
    operators: operatorsFromFact(fact.operators),
    dictCode: fact.dictCode,
    reference: fact.reference,
    required: fact.required,
    visible: fact.visible,
    editable: fact.editable,
    masked: fact.masked,
    permission: fact.permission,
    sourceType: fact.sourceType ?? entity?.sourceType,
    sourceRef: fact.sourceRef ?? entity?.sourceRef,
    factKey: fact.factKey,
    ...optionsFromAllowedValues(fact.allowedValues),
  };
}

export function factCatalogToFieldOptions(catalog?: DecisionFactCatalog | null): FieldOption[] {
  const fields: FieldOption[] = [];
  catalog?.entities?.forEach((entity) => {
    entity.facts?.forEach((fact) => fields.push(factToFieldOption(fact, entity)));
  });
  catalog?.facts?.forEach((fact) => fields.push(factToFieldOption(fact)));
  return mergeFieldOptions(fields);
}

export function modelFieldToFieldOption(field: DecisionModelField): FieldOption {
  const { scope, path } = splitScopedPath(field.entityCode, field.path);
  return {
    scope,
    path,
    label: field.label,
    dataType: normalizeDataType(field.dataType),
    modelCode: field.modelCode ?? field.entityCode,
    modelName: field.modelName,
    entityCode: field.entityCode,
    masked: field.masked,
    permission: field.permission,
  };
}

export function modelFieldsToFieldOptions(fields?: DecisionModelField[] | null): FieldOption[] {
  return mergeFieldOptions((fields ?? []).map(modelFieldToFieldOption));
}

export function mergeFieldOptions(...groups: FieldOption[][]): FieldOption[] {
  const byKey = new Map<string, FieldOption>();
  groups.flat().forEach((field) => {
    const key = `${field.scope}:${field.path}`;
    if (!byKey.has(key)) byKey.set(key, field);
  });
  return Array.from(byKey.values());
}
