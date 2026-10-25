import type { DslBlockV3 } from '../../types';
import type { RuntimeValidationRule } from './runtimeContexts';
import { getStringProp, isRecord, isRuntimeBlockPermissionAllowed } from './runtimePresentation';





function collectRuntimeFormFields(block: DslBlockV3): DslBlockV3[] {
  const childFields = block.blocks?.flatMap(collectRuntimeFormFields) ?? [];
  return block.blockType === 'field' ? [block, ...childFields] : childFields;
}

export function collectRuntimeFilterFields(
  block: DslBlockV3,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  const childFields =
    block.blocks?.flatMap((child) => collectRuntimeFilterFields(child, hasPermission)) ?? [];
  return block.blockType === 'filter-field' ? [block, ...childFields] : childFields;
}

export function collectRuntimeVisibleFormFields(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (!isRuntimeBlockVisible(block, values)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  if (block.blockType === 'repeater' || block.blockType === 'subform') return [];
  const childFields =
    block.blocks?.flatMap((child) =>
      collectRuntimeVisibleFormFields(child, values, hasPermission),
    ) ?? [];
  return block.blockType === 'field' ? [block, ...childFields] : childFields;
}

export function collectRuntimeNestedFormValidationErrors(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): Array<[string, string]> {
  if (!isRuntimeBlockVisible(block, values)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];

  const ownErrors =
    block.blockType === 'repeater' || block.blockType === 'subform'
      ? collectRuntimeRowContainerValidationErrors(block, values, hasPermission)
      : [];
  const childErrors =
    block.blocks?.flatMap((child) =>
      collectRuntimeNestedFormValidationErrors(child, values, hasPermission),
    ) ?? [];
  return [...ownErrors, ...childErrors];
}

function collectRuntimeRowContainerValidationErrors(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): Array<[string, string]> {
  const containerKey = block.field || block.id;
  const rowsValue = values[containerKey];
  const rows = Array.isArray(rowsValue)
    ? rowsValue.filter(isRecord).map((row) => ({ ...row }))
    : getRuntimeRepeaterRows(block);

  return rows.flatMap((row, rowIndex) => {
    const fieldBlocks =
      block.blockType === 'repeater'
        ? getRuntimeRepeaterFieldBlocks(block).filter((fieldBlock) =>
            isRuntimeRowFieldValidationAllowed(fieldBlock, row, hasPermission),
          )
        : block.blocks?.flatMap((child) =>
            collectRuntimeVisibleSubformRowFields(child, row, hasPermission),
          ) ?? [];

    return fieldBlocks.flatMap((fieldBlock) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      const error = validateRuntimeFormField(fieldBlock, row[fieldKey]);
      return error
        ? [
            [getRuntimeNestedFieldErrorKey(containerKey, rowIndex, fieldKey), error] as [
              string,
              string,
            ],
          ]
        : [];
    });
  });
}

function collectRuntimeVisibleSubformRowFields(
  block: DslBlockV3,
  row: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (!isRuntimeBlockVisible(block, row)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  if (block.blockType === 'field') return [block];
  if (block.blockType === 'repeater' || block.blockType === 'subform') return [];
  return (
    block.blocks?.flatMap((child) =>
      collectRuntimeVisibleSubformRowFields(child, row, hasPermission),
    ) ?? []
  );
}

function isRuntimeRowFieldValidationAllowed(
  block: DslBlockV3,
  row: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): boolean {
  return (
    isRuntimeBlockVisible(block, row) &&
    (!hasPermission || isRuntimeBlockPermissionAllowed(block, hasPermission))
  );
}

export function getRuntimeNestedFieldErrorKey(
  containerKey: string,
  rowIndex: number,
  fieldKey: string,
): string {
  return `${containerKey}.${rowIndex}.${fieldKey}`;
}

export function validateRuntimeFormField(block: DslBlockV3, value: unknown): string | null {
  if (block.props?.required && isRuntimeFieldValueEmpty(value)) return 'Required';

  for (const rule of getRuntimeValidationRules(block.props?.validationRules)) {
    const error = validateRuntimeRule(rule, value);
    if (error) return error;
  }

  return null;
}

function getRuntimeValidationRules(value: unknown): RuntimeValidationRule[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const rule = item as Record<string, unknown>;
    const type = getStringProp(rule.type);
    return type ? [{ type, value: rule.value, message: getStringProp(rule.message) }] : [];
  });
}

function validateRuntimeRule(rule: RuntimeValidationRule, value: unknown): string | null {
  const type = rule.type.toLowerCase();
  if (type === 'required') {
    return isRuntimeFieldValueEmpty(value) ? rule.message || 'Required' : null;
  }
  if (type === 'minlength' || type === 'min_length') {
    const minimumLength = getRuntimeNumber(rule.value);
    if (minimumLength === null || isRuntimeFieldValueEmpty(value)) return null;
    return String(value).length < minimumLength
      ? rule.message || `Minimum length is ${minimumLength}`
      : null;
  }
  if (type === 'maxlength' || type === 'max_length') {
    const maximumLength = getRuntimeNumber(rule.value);
    if (maximumLength === null || isRuntimeFieldValueEmpty(value)) return null;
    return String(value).length > maximumLength
      ? rule.message || `Maximum length is ${maximumLength}`
      : null;
  }
  if (type === 'pattern' || type === 'regex') {
    const pattern = getStringProp(rule.value);
    if (!pattern || isRuntimeFieldValueEmpty(value)) return null;
    try {
      return new RegExp(pattern).test(String(value)) ? null : rule.message || 'Invalid format';
    } catch {
      return rule.message || 'Invalid format';
    }
  }
  return null;
}

export function isRuntimeBlockVisible(
  block: DslBlockV3,
  values: Record<string, unknown> | undefined,
): boolean {
  const rule = block.props?.visibleWhen;
  if (rule === undefined || rule === null) return true;
  if (!values) return true;
  return evaluateRuntimeVisibleWhen(rule, values);
}

export function isRuntimeBlockDisabled(
  block: DslBlockV3,
  values: Record<string, unknown> | undefined,
): boolean {
  const rule = block.props?.disabledWhen;
  if (rule === undefined || rule === null) return false;
  if (!values) return false;
  return evaluateRuntimeVisibleWhen(rule, values);
}

function evaluateRuntimeVisibleWhen(rule: unknown, values: Record<string, unknown>): boolean {
  if (Array.isArray(rule)) return rule.every((item) => evaluateRuntimeVisibleWhen(item, values));
  if (!isRecord(rule)) return Boolean(rule);

  if (Array.isArray(rule.all)) {
    return rule.all.every((item) => evaluateRuntimeVisibleWhen(item, values));
  }
  if (Array.isArray(rule.any)) {
    return rule.any.some((item) => evaluateRuntimeVisibleWhen(item, values));
  }

  const field = getStringProp(rule.field);
  if (!field) return true;

  const actualValue = getRuntimeConditionValue(values, field);
  const expectedValue = rule.value;
  const operator = normalizeRuntimeVisibleOperator(rule.operator);

  if (operator === 'empty') return isRuntimeFieldValueEmpty(actualValue);
  if (operator === 'notempty') return !isRuntimeFieldValueEmpty(actualValue);
  if (operator === 'notequals') return !runtimeValuesEqual(actualValue, expectedValue);
  if (operator === 'contains') return runtimeValueContains(actualValue, expectedValue);
  if (operator === 'in') return runtimeValueContains(expectedValue, actualValue);
  if (operator === 'notin') return !runtimeValueContains(expectedValue, actualValue);
  if (operator === 'gt') return compareRuntimeNumbers(actualValue, expectedValue) > 0;
  if (operator === 'gte') return compareRuntimeNumbers(actualValue, expectedValue) >= 0;
  if (operator === 'lt') return compareRuntimeNumbers(actualValue, expectedValue) < 0;
  if (operator === 'lte') return compareRuntimeNumbers(actualValue, expectedValue) <= 0;

  return runtimeValuesEqual(actualValue, expectedValue);
}

function getRuntimeConditionValue(values: Record<string, unknown>, field: string): unknown {
  if (Object.prototype.hasOwnProperty.call(values, field)) return values[field];
  return field.split('.').reduce<unknown>((current, part) => {
    if (!isRecord(current)) return undefined;
    return current[part];
  }, values);
}

function normalizeRuntimeVisibleOperator(value: unknown): string {
  const operator = getStringProp(value).replace(/[_\s-]/g, '').toLowerCase();
  if (!operator) return 'equals';
  if (operator === 'neq' || operator === 'ne' || operator === 'notequal') return 'notequals';
  if (operator === 'notempty' || operator === 'exists') return 'notempty';
  if (operator === 'isempty' || operator === 'blank') return 'empty';
  if (operator === 'greaterthan') return 'gt';
  if (operator === 'greaterthanorequal') return 'gte';
  if (operator === 'lessthan') return 'lt';
  if (operator === 'lessthanorequal') return 'lte';
  return operator;
}

function runtimeValuesEqual(actualValue: unknown, expectedValue: unknown): boolean {
  if (Array.isArray(actualValue)) {
    return actualValue.some((item) => runtimeValuesEqual(item, expectedValue));
  }
  if (Array.isArray(expectedValue)) {
    return expectedValue.some((item) => runtimeValuesEqual(actualValue, item));
  }
  return String(actualValue ?? '') === String(expectedValue ?? '');
}

function runtimeValueContains(container: unknown, item: unknown): boolean {
  if (Array.isArray(container)) {
    return container.some((value) => runtimeValuesEqual(value, item));
  }
  return String(container ?? '').includes(String(item ?? ''));
}

function compareRuntimeNumbers(actualValue: unknown, expectedValue: unknown): number {
  const actualNumber = getRuntimeNumber(actualValue);
  const expectedNumber = getRuntimeNumber(expectedValue);
  if (actualNumber === null || expectedNumber === null) return Number.NaN;
  return actualNumber - expectedNumber;
}

export function getRuntimeNumber(value: unknown): number | null {
  const nextValue = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(nextValue) ? nextValue : null;
}

export function isRuntimeFieldValueEmpty(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (typeof value === 'boolean') return !value;
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

export function getRuntimeRepeaterRows(block: DslBlockV3): Array<Record<string, unknown>> {
  const rows = Array.isArray(block.props?.rows)
    ? block.props.rows
    : Array.isArray(block.dataSource?.rows)
      ? block.dataSource.rows
      : [{}];
  const normalizedRows = rows.filter(isRecord).map((row) => ({ ...row }));
  return normalizedRows.length ? normalizedRows : [{}];
}

export function getRuntimeRepeaterFieldBlocks(block: DslBlockV3): DslBlockV3[] {
  return block.blocks?.filter((child) => child.blockType === 'field') ?? [];
}

export function getRuntimeRepeaterFieldKey(block: DslBlockV3): string {
  return block.field || block.id;
}
