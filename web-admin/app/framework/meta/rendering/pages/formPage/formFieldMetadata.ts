import { buildRequiredFieldMessage } from '~/framework/meta/utils/validationMessages';
import type { SubTableColumn } from '~/framework/meta/components/types';
import type { FieldMetaInfo, NormalizedValidationRule } from './formPageTypes';

/**
 * Map field dataType to Smart component name.
 * Used to infer component when DSL field does not specify `component`.
 */
export const DATA_TYPE_TO_COMPONENT: Record<string, string> = {
  string: 'SmartInput',
  text: 'SmartTextarea',
  decimal: 'SmartNumberInput',
  integer: 'SmartNumberInput',
  number: 'SmartNumberInput',
  enum: 'SmartSelect',
  date: 'SmartDatePicker',
  datetime: 'SmartDatePicker',
  timestamp: 'SmartDatePicker',
  boolean: 'SmartSwitch',
  reference: 'SmartSelect',
  json: 'SmartJsonEditor',
  jsonb: 'SmartJsonEditor',
  file: 'SmartUpload',
  money: 'SmartMoneyInput',
};

export function mergeRefTarget(
  primary?: Record<string, any> | null,
  secondary?: Record<string, any> | null,
): Record<string, any> | undefined {
  const merged = {
    ...(secondary || {}),
    ...(primary || {}),
  };
  return Object.keys(merged).length > 0 ? merged : undefined;
}

export function resolveComponentByFieldMeta(
  dataType?: string,
  extension?: Record<string, any>,
): string | undefined {
  // Plugin import may store under nested extension.extension (legacy shape),
  // while runtime may also have the properties hoisted at the top level.
  const nested = (extension as any)?.extension as Record<string, any> | undefined;
  const preferred = String(
    extension?.renderComponent ??
      extension?.component ??
      extension?.uiComponent ??
      nested?.renderComponent ??
      nested?.component ??
      nested?.uiComponent ??
      '',
  )
    .trim()
    .toLowerCase();

  if (preferred) {
    if (
      preferred === 'richtext' ||
      preferred === 'rich_text' ||
      preferred === 'smartrichtexteditor'
    ) {
      return 'richtext';
    }
    return preferred;
  }

  if (!dataType) return undefined;
  return DATA_TYPE_TO_COMPONENT[String(dataType).toLowerCase()];
}

/**
 * Map field dataType to SubTableColumn type for sub-table cell editors.
 */
export function mapDataTypeToColumnType(dataType: string): SubTableColumn['type'] {
  switch (dataType?.toLowerCase()) {
    case 'integer':
    case 'decimal':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'date':
    case 'datetime':
      return 'date';
    case 'enum':
      return 'select';
    default:
      return 'text';
  }
}

export function mergeFieldValidationRules(
  rawField: any,
  meta?: FieldMetaInfo,
  t?: (key: string) => string,
  locale?: string,
): NormalizedValidationRule[] {
  const existingRules: NormalizedValidationRule[] = Array.isArray(rawField?.validation)
    ? [...rawField.validation]
    : [];
  const byType = new Set(existingRules.map((r) => r?.type));

  // Read-only fields are often populated by backend auto-set/default logic in create mode.
  // Enforcing them as user-entered required inputs blocks valid submissions before command execution.
  const required = !rawField?.readOnly && Boolean(rawField?.required ?? meta?.required);
  if (required && !byType.has('required')) {
    const label = rawField?.label || meta?.displayName || rawField?.field;
    existingRules.push({
      type: 'required',
      message: buildRequiredFieldMessage(label, {
        dataType: meta?.dataType,
        component: rawField?.component ?? meta?.component,
        locale,
        t,
      }),
    });
    byType.add('required');
  }

  const validationConfig = meta?.feature?.validation || {};
  const maxLength = Number(validationConfig?.maxLength);
  const minLength = Number(validationConfig?.minLength);
  const pattern =
    typeof validationConfig?.pattern === 'string' ? validationConfig.pattern : undefined;

  if (Number.isFinite(maxLength) && maxLength > 0 && !byType.has('maxLength')) {
    existingRules.push({ type: 'maxLength', maxLength });
    byType.add('maxLength');
  }
  if (Number.isFinite(minLength) && minLength > 0 && !byType.has('minLength')) {
    existingRules.push({ type: 'minLength', minLength });
    byType.add('minLength');
  }
  if (pattern && !byType.has('pattern')) {
    existingRules.push({ type: 'pattern', pattern });
  }

  // constraints use "min"/"max", feature.validation uses "minValue"/"maxValue"
  const minValue = Number(validationConfig?.minValue ?? validationConfig?.min);
  const maxValue = Number(validationConfig?.maxValue ?? validationConfig?.max);
  if (Number.isFinite(minValue) && !byType.has('minValue')) {
    existingRules.push({ type: 'minValue', minValue });
    byType.add('minValue');
  }
  if (Number.isFinite(maxValue) && !byType.has('maxValue')) {
    existingRules.push({ type: 'maxValue', maxValue });
    byType.add('maxValue');
  }

  return existingRules;
}
