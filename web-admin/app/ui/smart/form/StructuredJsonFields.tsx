import React from 'react';
import Input from './Input';
import NumberInput from './NumberInput';
import Select from './Select';
import Switch from './Switch';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  resolveStructuredSchema,
  structuredJsonDefault,
  structuredJsonError,
  structuredJsonLabel,
  structuredJsonLocalOptions,
  structuredJsonConfigurationError,
  structuredJsonValueWithinLimits,
  type StructuredJsonSchema,
} from '~/framework/smart/schema/structured-json';
import type { JsonEditorProps } from '~/framework/smart/schema/smart-components';

interface Props {
  schema: StructuredJsonSchema;
  value: unknown;
  onChange: (value: unknown) => void;
  name: string;
  context?: JsonEditorProps['context'];
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  showErrors?: boolean;
  rootValue?: unknown;
}

/** Only schema-described controls are editable; partial schemas preserve sibling data. */
function Node({
  schema: source,
  value,
  onChange,
  name,
  context,
  disabled,
  readOnly,
  required,
  rootValue,
}: Props) {
  const schema = resolveStructuredSchema(source, value);
  const locale = context?.locale || 'en';
  const zh = locale.startsWith('zh');
  const text = (cn: string, en: string) => (zh ? cn : en);
  const label = structuredJsonLabel(schema, locale);
  const locked = Boolean(readOnly || schema.readOnly || schema.const !== undefined);
  const inactive = Boolean(disabled || locked);
  if (schema['x-hidden']) return null;
  if (
    value !== undefined &&
    value !== null &&
    ((schema.type === 'object' && (typeof value !== 'object' || Array.isArray(value))) ||
      (schema.type === 'array' && !Array.isArray(value)))
  ) {
    return (
      <p role="alert" className="text-status-red text-sm">
        {text(
          '内容类型不匹配，请重新加载。',
          'The value type does not match this form. Reload the form.',
        )}
      </p>
    );
  }
  if (schema.type === 'object') {
    const exists = value !== undefined && value !== null;
    const object =
      exists && typeof value === 'object' && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : {};
    if (!exists && !required) {
      return (
        <div className="border-border rounded-control border p-3">
          {locked ? (
            <p className="text-text-3 text-sm">
              {label} · {text('未填写', 'Not provided')}
            </p>
          ) : (
            <button
              type="button"
              disabled={disabled}
              className="text-accent rounded-control px-2 py-1 text-sm"
              onClick={() => onChange(structuredJsonDefault(schema))}
            >
              {text(`添加${label}`, `Add ${label}`)}
            </button>
          )}
        </div>
      );
    }
    return (
      <fieldset className="border-border rounded-control min-w-0 space-y-3 border p-3">
        {label && (
          <legend className="text-text px-1 text-sm font-medium">
            {label}
            {required ? ' *' : ''}
          </legend>
        )}
        {schema['x-helpText'] && (
          <p className="text-text-3 text-sm">{getLocalizedText(schema['x-helpText'], locale)}</p>
        )}
        <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
          {Object.entries(schema.properties || {})
            .filter(([, child]) => !child['x-hidden'])
            .map(([key, child]) => (
              <div
                key={key}
                className={
                  ['object', 'array'].includes(child.type || '')
                    ? 'min-w-0 sm:col-span-2'
                    : 'min-w-0'
                }
              >
                <Node
                  schema={child}
                  rootValue={rootValue}
                  value={object[key]}
                  name={`${name}.${key}`}
                  context={context}
                  required={schema.required?.includes(key)}
                  disabled={disabled}
                  readOnly={locked}
                  onChange={(next) => {
                    const updated = { ...object };
                    if (next === undefined) delete updated[key];
                    else updated[key] = next;
                    onChange(updated);
                  }}
                />
              </div>
            ))}
        </div>
        {!locked && !required && (
          <button
            type="button"
            disabled={disabled}
            className="text-status-red text-sm"
            onClick={() => onChange(undefined)}
          >
            {text(`移除${label}`, `Remove ${label}`)}
          </button>
        )}
      </fieldset>
    );
  }
  if (schema.type === 'array') {
    const rows = Array.isArray(value) ? value : [];
    const max = Math.min(schema.maxItems ?? 100, 100);
    return (
      <fieldset className="border-border rounded-control min-w-0 space-y-3 border p-3">
        <legend className="text-text px-1 text-sm font-medium">
          {label}
          {required ? ' *' : ''}
        </legend>
        {schema['x-helpText'] && (
          <p className="text-text-3 text-sm">{getLocalizedText(schema['x-helpText'], locale)}</p>
        )}
        {!rows.length && (
          <p className="text-text-3 text-sm">{text('尚未添加条目。', 'No entries yet.')}</p>
        )}
        {rows.map((row, index) => (
          <div key={index} className="border-border min-w-0 space-y-2 border-b pb-3 last:border-0">
            <div className="flex items-center justify-between gap-2 text-sm">
              <span className="text-text-3">
                {label} {index + 1}
              </span>
              {!locked && (
                <button
                  type="button"
                  disabled={disabled}
                  className="text-status-red px-2 py-1"
                  aria-label={text(`移除${label}第${index + 1}项`, `Remove ${label} ${index + 1}`)}
                  onClick={() => onChange(rows.filter((_, i) => i !== index))}
                >
                  {text('移除', 'Remove')}
                </button>
              )}
            </div>
            <Node
              schema={schema.items!}
              rootValue={rootValue}
              value={row}
              name={`${name}.${index}`}
              context={context}
              required
              disabled={disabled}
              readOnly={locked}
              onChange={(next) => onChange(rows.map((item, i) => (i === index ? next : item)))}
            />
          </div>
        ))}
        {!locked && (
          <button
            type="button"
            disabled={disabled || rows.length >= max}
            className="text-accent bg-accent-weak rounded-control px-3 py-2 text-sm disabled:opacity-50"
            onClick={() => onChange([...rows, structuredJsonDefault(schema.items!) ?? ''])}
          >
            {text(`添加${label}`, `Add ${label}`)}
          </button>
        )}
      </fieldset>
    );
  }
  const shared = {
    name,
    label,
    required,
    disabled,
    readOnly: locked,
    context,
    helpText: schema['x-helpText'] || schema.description,
    placeholder: schema['x-placeholder']
      ? getLocalizedText(schema['x-placeholder'], locale)
      : undefined,
  };
  if (schema.enum) {
    const choices = schema.enum;
    const selectedIndex = choices.findIndex((item) => Object.is(item, value));
    return (
      <Select
        {...shared}
        value={selectedIndex < 0 ? '' : String(selectedIndex)}
        options={choices.map((item, index) => ({
          value: String(index),
          label: getLocalizedText(schema['x-enumLabels']?.[index] || String(item), locale),
        }))}
        onChange={(selected) => {
          if (selected === '' || selected == null) return onChange(undefined);
          const index = Number(selected);
          if (!Number.isInteger(index) || index < 0 || index >= choices.length)
            throw new Error('Invalid structured choice');
          onChange(choices[index]);
        }}
      />
    );
  }
  if (schema['x-optionsFrom']) {
    const options = structuredJsonLocalOptions(schema, rootValue);
    return (
      <Select
        {...shared}
        value={value as string | undefined}
        options={options}
        clearable={!required}
        disabled={disabled || options.length === 0}
        onChange={(next) => onChange(next || undefined)}
      />
    );
  }
  if (schema['x-dataSource'])
    return (
      <Select
        {...shared}
        value={value as string | undefined}
        searchable
        clearable={!required}
        dataSource={schema['x-dataSource']}
        onChange={(next) => onChange(next || undefined)}
      />
    );
  if (schema.type === 'boolean')
    return <Switch {...shared} disabled={inactive} value={value === true} onChange={onChange} />;
  if (schema.type === 'number' || schema.type === 'integer')
    return (
      <NumberInput
        {...shared}
        value={value as number | undefined}
        min={schema.minimum}
        max={schema.maximum}
        onChange={(next) => {
          if (next === '' || next === undefined || next === null) return onChange(undefined);
          const numeric = Number(next);
          onChange(
            Number.isFinite(numeric) &&
              (!Number.isInteger(numeric) || Number.isSafeInteger(numeric))
              ? numeric
              : String(next),
          );
        }}
      />
    );
  return (
    <Input
      {...shared}
      value={schema.writeOnly && locked ? '' : (value as string | undefined)}
      type={schema.writeOnly ? 'password' : 'text'}
      minLength={schema.minLength}
      maxLength={schema.maxLength}
      pattern={schema.pattern}
      onChange={(next) =>
        onChange(next === '' && !required && (schema.minLength ?? 0) > 0 ? undefined : next)
      }
    />
  );
}

export function StructuredJsonFields(props: Props) {
  const locale = props.context?.locale || 'en';
  const configurationError = structuredJsonConfigurationError(props.schema, locale);
  if (configurationError)
    return (
      <p role="alert" className="text-status-red text-sm">
        {configurationError}
      </p>
    );
  let value = props.value;
  try {
    if (typeof value === 'string') value = value.trim() ? JSON.parse(value) : undefined;
  } catch {
    return (
      <p role="alert" className="text-status-red text-sm">
        {locale.startsWith('zh')
          ? '结构化内容无效，请重新加载。'
          : 'The structured value is invalid. Reload the form.'}
      </p>
    );
  }
  const error = structuredJsonError(value, props.schema, locale);
  if (!structuredJsonValueWithinLimits(value))
    return (
      <p role="alert" className="text-status-red text-sm">
        {error}
      </p>
    );
  return (
    <div className="min-w-0 space-y-2" data-testid={`structured-json-${props.name}`}>
      <Node {...props} value={value} rootValue={value} />
      {error && props.showErrors && (
        <p role="alert" className="text-status-red text-sm">
          {error}
        </p>
      )}
    </div>
  );
}
