/** A draft-only contract. Applying a patch never executes a business command. */
export interface FormFillField {
  code: string;
  label: string;
  type: 'string' | 'number' | 'integer' | 'boolean';
  format?: 'date' | 'date-time';
  enum?: Array<string | number>;
  locked?: boolean;
}

export interface FormFillSnapshot {
  targetId: string;
  modelCode: string;
  fields: FormFillField[];
  currentDate: string;
  timeZone: string;
}

export interface FormFillReview {
  status: 'supported' | 'ambiguous';
  quote: string;
  reason?: 'multiple_values' | 'unclear_mapping' | 'uncertain_date';
}

export interface FormFillResult {
  reviews?: Record<string, FormFillReview>;
  values: Record<string, unknown>;
  applied: Record<string, { before: unknown; after: unknown }>;
  conflicts: string[];
  suggestions: Record<string, unknown>;
  rejected: string[];
}

export interface FormFillTarget {
  snapshot: () => FormFillSnapshot;
  apply: (
    fields: Record<string, unknown>,
    reviews?: Record<string, FormFillReview>,
  ) => FormFillResult;
}

type FieldNode = Record<string, any>;

/** Only actual form fields participate; unsupported/conditional fields fail closed. */
export function collectFormFillFields(
  schema: unknown,
  metadata: Record<string, FieldNode>,
  permissions: Record<string, string> | undefined,
  label: (value: unknown) => string,
): FormFillField[] {
  const fields = new Map<string, FormFillField>();
  const visit = (node: unknown, ancestorLocked = false): void => {
    if (Array.isArray(node)) {
      node.forEach((child) => visit(child, ancestorLocked));
      return;
    }
    if (!node || typeof node !== 'object') return;
    const field = node as FieldNode;
    const props = field.props ?? {};
    const locked =
      ancestorLocked ||
      field.hidden === true ||
      field.visible === false ||
      field.visibleWhen != null ||
      props.visibleWhen != null ||
      field.readOnlyWhen != null ||
      props.readOnlyWhen != null ||
      field.disabledWhen != null ||
      props.disabledWhen != null ||
      field.readOnly === true ||
      props.readOnly === true ||
      field.disabled === true ||
      props.disabled === true ||
      field.aiLocked === true ||
      props.aiLocked === true ||
      ['sub-table', 'subform', 'repeater'].includes(field.blockType);
    if (typeof field.field === 'string' && field.field) {
      const meta = metadata[field.field];
      const dataType = field.dataType ?? props.dataType ?? meta?.dataType;
      const types: Record<string, FormFillField['type']> = {
        string: 'string',
        text: 'string',
        enum: 'string',
        date: 'string',
        datetime: 'string',
        integer: 'integer',
        decimal: 'number',
        number: 'number',
        money: 'number',
        boolean: 'boolean',
      };
      const type = types[dataType];
      const options = field.options ?? props.options;
      const values = Array.isArray(options)
        ? options
            .map((option) => (typeof option === 'object' ? option.value : option))
            .filter((value) => typeof value === 'string' || typeof value === 'number')
        : undefined;
      const hasDictionary =
        field.dictCode || props.dictCode || meta?.dictCode || meta?.extensionProps?.dictCode;
      const blocked =
        locked ||
        !type ||
        ['hidden', 'readonly'].includes(permissions?.[field.field] ?? '') ||
        meta?.readOnly === true ||
        meta?.extension?.readOnly === true ||
        meta?.extension?.extension?.readOnly === true ||
        meta?.extensionProps?.readOnly === true ||
        meta?.extensionProps?.computed === true ||
        !!field.formula ||
        !!props.formula ||
        !!field.referenceModelCode ||
        !!meta?.referenceModelCode ||
        ((dataType === 'enum' || hasDictionary) && !values?.length);
      const previous = fields.get(field.field);
      fields.set(field.field, {
        code: field.field,
        label: label(field.label ?? props.label ?? meta?.displayName) || label(undefined),
        type: type ?? 'string',
        ...(dataType === 'date' ? { format: 'date' as const } : {}),
        ...(dataType === 'datetime' ? { format: 'date-time' as const } : {}),
        ...(values?.length ? { enum: values } : {}),
        locked: blocked || previous?.locked === true,
      });
    }
    for (const key of ['blocks', 'fields', 'children', 'tabs', 'items', 'sections']) {
      if (field[key]) visit(field[key], locked);
    }
  };
  visit(schema);
  return [...fields.values()];
}

function validValue(field: FormFillField, value: unknown): boolean {
  if (value == null) return false;
  if (field.type === 'integer') {
    if (typeof value !== 'number' || !Number.isSafeInteger(value)) return false;
  } else if (typeof value !== field.type) return false;
  if (typeof value === 'number' && !Number.isFinite(value)) return false;
  if (field.enum && !field.enum.includes(value as string | number)) return false;
  if (field.format) {
    if (typeof value !== 'string') return false;
    const match = /^(\d{4}-\d{2}-\d{2})(.*)$/.exec(value);
    if (!match) return false;
    const date = new Date(`${match[1]}T00:00:00Z`);
    if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== match[1])
      return false;
    if (field.format === 'date' && match[2] !== '') return false;
    if (
      field.format === 'date-time' &&
      (!/^T\d{2}:\d{2}/.test(match[2]) || !Number.isFinite(Date.parse(value)))
    )
      return false;
  }
  return true;
}

export function applyFormFill(
  current: Record<string, unknown>,
  fields: FormFillField[],
  patch: Record<string, unknown>,
  reviews?: Record<string, FormFillReview>,
): FormFillResult {
  const result: FormFillResult = {
    values: { ...current },
    applied: {},
    conflicts: [],
    suggestions: {},
    rejected: [],
  };
  const byCode = new Map(fields.map((field) => [field.code, field]));
  if (reviews) {
    result.reviews = Object.fromEntries(
      Object.entries(reviews).filter(([code, review]) => {
        const field = byCode.get(code);
        return (
          field &&
          !field.locked &&
          !['__proto__', 'constructor', 'prototype'].includes(code) &&
          review &&
          typeof review.quote === 'string' &&
          review.quote.trim().length > 0 &&
          (review.status === 'ambiguous' ||
            (review.status === 'supported' && validValue(field, patch[code])))
        );
      }),
    );
  }
  for (const [code, value] of Object.entries(patch)) {
    const field = byCode.get(code);
    if (
      ['__proto__', 'constructor', 'prototype'].includes(code) ||
      !field ||
      field.locked ||
      !validValue(field, value) ||
      (reviews !== undefined && result.reviews?.[code]?.status !== 'supported')
    ) {
      result.rejected.push(code);
      continue;
    }
    const before = current[code];
    if (Object.is(before, value)) continue;
    if (before !== undefined && before !== null && before !== '') {
      result.conflicts.push(code);
      result.suggestions[code] = value;
      continue;
    }
    result.values[code] = value;
    result.applied[code] = { before, after: value };
  }
  return result;
}

export function undoFormFill(current: Record<string, unknown>, applied: FormFillResult['applied']) {
  const values = { ...current };
  for (const [code, change] of Object.entries(applied)) {
    if (!Object.is(values[code], change.after)) continue;
    if (change.before === undefined) delete values[code];
    else values[code] = change.before;
  }
  return values;
}
