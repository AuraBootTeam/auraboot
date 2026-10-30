import { useLayoutEffect, useRef } from 'react';
import RecordReferencePicker, {
  type ReferenceConfig,
  validReferenceConfig,
} from './RecordReferencePicker';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

type Localized = string | Record<string, string>;
type RecordValue = Record<string, unknown>;
export interface RecordListColumn {
  field: string;
  label: Localized;
  group?: Localized;
  helpText?: Localized;
  placeholder?: Localized;
  type: 'text' | 'integer' | 'checkbox' | 'select' | 'multiselect' | 'reference';
  reference?: ReferenceConfig;
  required?: boolean;
  min?: number;
  max?: number;
  maxLength?: number;
  emptyValue?: null;
  options?: Array<{ value: string; label: Localized; disabled?: boolean }>;
}
export interface RecordListConfig {
  fields: RecordListColumn[];
  defaults?: RecordValue;
  identityField?: string;
  minItems?: number;
  maxItems?: number;
  itemLabel?: Localized;
  addLabel?: Localized;
  removeLabel?: Localized;
}

const unsafeKeys = new Set(['__proto__', 'constructor', 'prototype']);
function pathParts(path: string): string[] {
  const parts = path.split('.');
  if (!parts.every((part) => /^[a-zA-Z][a-zA-Z0-9_]*$/.test(part) && !unsafeKeys.has(part))) {
    throw new Error('Invalid record-list field path');
  }
  return parts;
}
function isRecord(value: unknown): value is RecordValue {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    [Object.prototype, null].includes(Object.getPrototypeOf(value))
  );
}
function readPath(record: RecordValue, path: string): unknown {
  return pathParts(path).reduce<unknown>(
    (value, key) => (isRecord(value) ? value[key] : undefined),
    record,
  );
}
function hasEditablePaths(record: RecordValue, fields: RecordListColumn[]): boolean {
  return fields.every((field) => {
    let current: unknown = record;
    for (const key of pathParts(field.field).slice(0, -1)) {
      if (current === undefined || current === null) return true;
      if (!isRecord(current)) return false;
      current = current[key];
    }
    return current === undefined || current === null || isRecord(current);
  });
}
function writePath(record: RecordValue, parts: string[], value: unknown): RecordValue {
  const [key, ...rest] = parts;
  return {
    ...record,
    [key]: rest.length ? writePath(isRecord(record[key]) ? record[key] : {}, rest, value) : value,
  };
}
function limits(config: RecordListConfig) {
  const minimum = config.minItems ?? 0,
    maximum = config.maxItems ?? 256;
  if (
    !Number.isSafeInteger(minimum) ||
    !Number.isSafeInteger(maximum) ||
    minimum < 0 ||
    maximum < minimum ||
    maximum > 1000
  ) {
    throw new Error('Invalid record-list item limits');
  }
  return { minimum, maximum };
}
function requireConfig(config: RecordListConfig) {
  limits(config);
  if (config.identityField) pathParts(config.identityField);
  if (
    !Array.isArray(config.fields) ||
    !config.fields.length ||
    config.fields.length > 64 ||
    new Set(config.fields.map((field) => field.field)).size !== config.fields.length ||
    (config.defaults !== undefined && !isRecord(config.defaults))
  )
    throw new Error('Invalid record-list configuration');
  for (const field of config.fields) {
    pathParts(field.field);
    if (
      field.maxLength !== undefined &&
      (!Number.isSafeInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > 10000)
    )
      throw new Error('Invalid record-list text limit');
    if (
      config.identityField &&
      (field.field === config.identityField ||
        field.reference?.selectionFields?.includes(config.identityField))
    )
      throw new Error('Editable record identity');
    if (config.fields.some((other) => other !== field && other.field.startsWith(field.field + '.')))
      throw new Error('Overlapping record-list field paths');
    if (
      field.type === 'integer' &&
      ((field.min !== undefined && !Number.isSafeInteger(field.min)) ||
        (field.max !== undefined && !Number.isSafeInteger(field.max)) ||
        (field.min !== undefined && field.max !== undefined && field.min > field.max))
    )
      throw new Error('Invalid record-list integer limits');
    if (!['text', 'integer', 'checkbox', 'select', 'multiselect', 'reference'].includes(field.type))
      throw new Error('Unsupported record-list input');
    if (field.type === 'reference') {
      validReferenceConfig(field.reference);
      for (const selected of field.reference!.selectionFields ?? []) pathParts(selected);
    }
    if (
      ['select', 'multiselect'].includes(field.type) &&
      (!Array.isArray(field.options) ||
        new Set(field.options.map((option) => option.value)).size !== field.options.length ||
        field.options.some((option) => typeof option.value !== 'string' || !option.label))
    )
      throw new Error('Invalid record-list choices');
  }
}
function validCell(field: RecordListColumn, value: unknown): boolean {
  if (value === undefined || value === null || value === '') return !field.required;
  switch (field.type) {
    case 'reference':
      return field.reference?.multiple
        ? Array.isArray(value) &&
            new Set(value).size === value.length &&
            value.every((item) => typeof item === 'string' && item.length > 0)
        : typeof value === 'string'
          ? value.length > 0
          : isRecord(value);
    case 'text':
      return (
        typeof value === 'string' &&
        (!field.required || value.trim().length > 0) &&
        (field.maxLength === undefined || value.length <= field.maxLength)
      );
    case 'integer':
      return (
        typeof value === 'number' &&
        Number.isSafeInteger(value) &&
        (field.min === undefined || value >= field.min) &&
        (field.max === undefined || value <= field.max)
      );
    case 'checkbox':
      return typeof value === 'boolean';
    case 'select':
      return (
        typeof value === 'string' &&
        Boolean(field.options?.some((option) => option.value === value && !option.disabled))
      );
    case 'multiselect':
      return (
        Array.isArray(value) &&
        (!field.required || value.length > 0) &&
        new Set(value).size === value.length &&
        value.every((item) =>
          field.options?.some((option) => option.value === item && !option.disabled),
        )
      );
  }
}
/** Malformed input never becomes an empty list; hidden metadata stays in the submitted record. */
export function validRecordList(value: unknown, config: RecordListConfig): boolean {
  requireConfig(config);
  const { minimum, maximum } = limits(config);
  return (
    Array.isArray(value) &&
    value.length >= minimum &&
    value.length <= maximum &&
    value.every(
      (row) =>
        isRecord(row) &&
        hasEditablePaths(row, config.fields) &&
        config.fields.every((field) => validCell(field, readPath(row, field.field))),
    )
  );
}

export default function RecordListField({
  name,
  label,
  value,
  config,
  submitted,
  onChange,
  autoFocus,
  fetchResult,
}: {
  name: string;
  label: string;
  value: unknown;
  config: RecordListConfig;
  submitted: boolean;
  autoFocus?: boolean;
  fetchResult?: (url: string, options: any) => Promise<any>;
  onChange: (value: unknown) => void;
}) {
  const { t, locale } = useI18n();
  const container = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    if (autoFocus)
      (
        container.current?.querySelector<HTMLElement>(
          '[data-record-reference-trigger], input, select',
        ) ?? container.current?.querySelector<HTMLElement>('button:not(:disabled)')
      )?.focus();
  }, [autoFocus]);
  requireConfig(config);
  const { minimum, maximum } = limits(config);
  const local = (text: Localized | undefined, zh: string, en: string) =>
    text ? getLocalizedText(text, locale, t) : locale.startsWith('zh') ? zh : en;
  if (
    !Array.isArray(value) ||
    value.some((row) => !isRecord(row) || !hasEditablePaths(row, config.fields))
  )
    return (
      <p role="alert" className="text-sm text-red-600">
        {local(
          undefined,
          '记录数据不可编辑，请刷新后重试。',
          'Record data cannot be edited. Refresh and try again.',
        )}
      </p>
    );
  const groups = config.fields.reduce<Array<{ label?: Localized; fields: RecordListColumn[] }>>(
    (result, field) => {
      const previous = result.at(-1);
      if (previous && JSON.stringify(previous.label) === JSON.stringify(field.group))
        previous.fields.push(field);
      else result.push({ label: field.group, fields: [field] });
      return result;
    },
    [],
  );
  const update = (index: number, field: RecordListColumn, next: unknown) =>
    onChange(
      value.map((row, i) => (i === index ? writePath(row, pathParts(field.field), next) : row)),
    );
  return (
    <section
      ref={container}
      aria-label={label}
      data-testid={`form-dialog-field-${name}`}
      className="space-y-3"
    >
      {value.map((row, index) => (
        <fieldset
          key={
            config.identityField && typeof readPath(row, config.identityField) === 'string'
              ? String(readPath(row, config.identityField))
              : index
          }
          className="space-y-3 rounded-md border border-gray-200 p-3 dark:border-gray-600"
        >
          <legend className="px-1 text-sm font-medium">
            {local(config.itemLabel, '记录', 'Record')} {index + 1}
          </legend>
          <div className="flex justify-end">
            <button
              type="button"
              disabled={value.length <= minimum}
              aria-label={`${local(config.removeLabel, '移除记录', 'Remove record')} ${index + 1}`}
              onClick={() => onChange(value.filter((_, i) => i !== index))}
              className="rounded px-2 py-1 text-sm text-red-600 disabled:opacity-40"
            >
              {local(config.removeLabel, '移除记录', 'Remove record')}
            </button>
          </div>
          {groups.map((group, groupIndex) => (
            <fieldset key={groupIndex} className="space-y-3">
              {group.label && (
                <legend className="mb-2 text-sm font-medium">
                  {getLocalizedText(group.label, locale, t)}
                </legend>
              )}
              {group.fields.map((field, fieldIndex) => {
                const current = readPath(row, field.field),
                  id = `record-list-${name}-${index}-${groupIndex}-${fieldIndex}`;
                const title = getLocalizedText(field.label, locale, t),
                  error = submitted && !validCell(field, current);
                const classes = `w-full rounded-md border px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 dark:bg-gray-700 ${error ? 'border-red-500' : 'border-gray-300 dark:border-gray-600'}`;
                return (
                  <div key={field.field}>
                    <label htmlFor={id} className="mb-1 block text-sm">
                      {title}
                      {field.required && <span className="ml-1 text-red-500">*</span>}
                    </label>
                    {field.type === 'reference' ? (
                      <RecordReferencePicker
                        id={id}
                        label={title}
                        value={row}
                        field={field.field}
                        config={field.reference!}
                        fetchResult={fetchResult}
                        onChange={(changes) =>
                          onChange(
                            value.map((item, i) =>
                              i === index
                                ? Object.entries(changes).reduce(
                                    (next, [path, selected]) =>
                                      writePath(next, pathParts(path), selected),
                                    item,
                                  )
                                : item,
                            ),
                          )
                        }
                      />
                    ) : field.type === 'checkbox' ? (
                      <input
                        id={id}
                        type="checkbox"
                        checked={current === true}
                        aria-invalid={error}
                        onChange={(event) => update(index, field, event.target.checked)}
                      />
                    ) : field.type === 'select' || field.type === 'multiselect' ? (
                      <select
                        id={id}
                        multiple={field.type === 'multiselect'}
                        value={
                          (current ?? (field.type === 'multiselect' ? [] : '')) as string | string[]
                        }
                        aria-invalid={error}
                        className={classes}
                        onChange={(event) =>
                          update(
                            index,
                            field,
                            field.type === 'multiselect'
                              ? Array.from(event.target.selectedOptions, (option) => option.value)
                              : event.target.value,
                          )
                        }
                      >
                        {field.type === 'select' && (
                          <option value="">{local(field.placeholder, '请选择', 'Select')}</option>
                        )}
                        {field.options?.map((option) => (
                          <option
                            key={option.value}
                            value={option.value}
                            disabled={option.disabled}
                          >
                            {getLocalizedText(option.label, locale, t)}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <input
                        id={id}
                        type="text"
                        maxLength={field.maxLength}
                        inputMode={field.type === 'integer' ? 'numeric' : undefined}
                        value={
                          typeof current === 'string' || typeof current === 'number' ? current : ''
                        }
                        placeholder={
                          field.placeholder ? getLocalizedText(field.placeholder, locale, t) : ''
                        }
                        aria-invalid={error}
                        className={classes}
                        onChange={(event) => {
                          const text = event.target.value;
                          update(
                            index,
                            field,
                            field.type === 'integer' &&
                              /^-?[0-9]+$/.test(text) &&
                              Number.isSafeInteger(Number(text))
                              ? Number(text)
                              : text === '' && field.emptyValue === null
                                ? null
                                : text,
                          );
                        }}
                      />
                    )}
                    {field.helpText && (
                      <p className="mt-1 text-xs text-gray-500">
                        {getLocalizedText(field.helpText, locale, t)}
                      </p>
                    )}
                    {error && (
                      <p role="alert" className="mt-1 text-xs text-red-600">
                        {local(undefined, '请检查此项的值。', 'Check this value.')}
                      </p>
                    )}
                  </div>
                );
              })}
            </fieldset>
          ))}
        </fieldset>
      ))}
      {value.length === 0 && (
        <p className="text-sm text-gray-500">
          {local(undefined, '尚未添加记录。', 'No records added.')}
        </p>
      )}
      <button
        type="button"
        disabled={value.length >= maximum}
        onClick={() => {
          let row = structuredClone(config.defaults ?? {});
          if (config.identityField)
            row = writePath(
              row,
              pathParts(config.identityField),
              'r_' + crypto.randomUUID().replaceAll('-', ''),
            );
          onChange([...value, row]);
        }}
        className="rounded-md border border-blue-200 px-3 py-2 text-sm text-blue-600 disabled:opacity-40"
      >
        {local(config.addLabel, '添加记录', 'Add record')}
      </button>
    </section>
  );
}
