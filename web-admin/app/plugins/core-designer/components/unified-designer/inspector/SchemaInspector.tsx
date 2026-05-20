import React, { useEffect, useState } from 'react';
import { getByPath } from '../utils/dotPath';
import type { DslBlockV3, ModelFieldDefinition } from '../types';
import { getInspectorFields } from './schemas';

interface SchemaInspectorProps {
  block: DslBlockV3 | null;
  modelFields?: ModelFieldDefinition[];
  onChange: (path: string, value: unknown) => void;
}

export function SchemaInspector({ block, modelFields = [], onChange }: SchemaInspectorProps) {
  const fields = getInspectorFields(block);
  const [activeTab, setActiveTab] = useState<'basic' | 'advanced'>('basic');

  useEffect(() => {
    setActiveTab('basic');
  }, [block?.id]);

  if (!block) {
    return (
      <div className="p-4 text-sm text-slate-500" data-testid="inspector-empty">
        Select a block on the canvas or outline.
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-slate-200 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
          Inspector
        </div>
        <div className="mt-1 text-sm font-semibold text-slate-900">{getBlockLabel(block)}</div>
        <div className="mt-0.5 font-mono text-xs text-slate-400" data-testid="inspector-selected-id">
          {block.id}
        </div>
      </div>

      <div className="flex-1 overflow-auto p-4">
        <div className="mb-4 grid grid-cols-2 rounded-md border border-slate-200 bg-slate-50 text-xs">
          <button
            type="button"
            data-testid="inspector-tab-basic"
            onClick={() => setActiveTab('basic')}
            className={`border-r border-slate-200 px-3 py-2 ${
              activeTab === 'basic' ? 'font-medium text-blue-700' : 'text-slate-500'
            }`}
          >
            Basic
          </button>
          <button
            type="button"
            data-testid="inspector-tab-advanced"
            onClick={() => setActiveTab('advanced')}
            className={`px-3 py-2 ${
              activeTab === 'advanced' ? 'font-medium text-blue-700' : 'text-slate-500'
            }`}
          >
            Advanced JSON
          </button>
        </div>

        {activeTab === 'basic' ? (
          <div className="space-y-4">
            {fields.map((field) => (
              <InspectorField
                key={field.key}
                block={block}
                path={field.key}
                label={String(field.label)}
                type={field.type}
                defaultValue={field.defaultValue}
                options={field.options?.map((option) => ({
                  label: String(option.label),
                  value: option.value,
                }))}
                modelFields={modelFields}
                onChange={onChange}
              />
            ))}
          </div>
        ) : (
          <AdvancedJsonInspector block={block} onChange={onChange} />
        )}
      </div>
    </div>
  );
}

const ADVANCED_JSON_FIELDS: Array<{
  key: 'props' | 'layout' | 'dataSource' | 'extension';
  label: string;
}> = [
  { key: 'props', label: 'Props' },
  { key: 'layout', label: 'Layout' },
  { key: 'dataSource', label: 'Data source' },
  { key: 'extension', label: 'Extension' },
];

function AdvancedJsonInspector({
  block,
  onChange,
}: {
  block: DslBlockV3;
  onChange: (path: string, value: unknown) => void;
}) {
  const [drafts, setDrafts] = useState<Record<string, string>>(() => createAdvancedJsonDrafts(block));
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    setDrafts(createAdvancedJsonDrafts(block));
    setErrors({});
  }, [block]);

  const applyDraft = (key: keyof typeof drafts) => {
    const rawValue = drafts[key].trim();
    if (!rawValue) {
      setErrors((current) => ({ ...current, [key]: '' }));
      onChange(key, undefined);
      return;
    }

    try {
      const parsed = JSON.parse(rawValue);
      setErrors((current) => ({ ...current, [key]: '' }));
      onChange(key, parsed);
    } catch {
      setErrors((current) => ({ ...current, [key]: 'Invalid JSON' }));
    }
  };

  return (
    <div className="space-y-4">
      {ADVANCED_JSON_FIELDS.map((field) => (
        <div key={field.key} className="rounded-md border border-slate-200 bg-white p-3">
          <div className="mb-2 flex items-center justify-between gap-3">
            <label
              htmlFor={`inspector-json-${field.key}`}
              className="text-xs font-semibold uppercase tracking-wide text-slate-500"
            >
              {field.label}
            </label>
            <button
              type="button"
              data-testid={`inspector-json-apply-${field.key}`}
              onClick={() => applyDraft(field.key)}
              className="rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              Apply
            </button>
          </div>
          <textarea
            id={`inspector-json-${field.key}`}
            data-testid={`inspector-json-${field.key}`}
            value={drafts[field.key]}
            onChange={(event) => {
              const nextValue = event.target.value;
              setDrafts((current) => ({ ...current, [field.key]: nextValue }));
              setErrors((current) => ({ ...current, [field.key]: '' }));
            }}
            spellCheck={false}
            className="h-28 w-full resize-y rounded-md border border-slate-300 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-50 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
          />
          {errors[field.key] ? (
            <div
              className="mt-2 text-xs font-medium text-red-600"
              data-testid={`inspector-json-error-${field.key}`}
            >
              {errors[field.key]}
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function createAdvancedJsonDrafts(block: DslBlockV3): Record<string, string> {
  return ADVANCED_JSON_FIELDS.reduce<Record<string, string>>((drafts, field) => {
    drafts[field.key] = stringifyJsonField(block[field.key]);
    return drafts;
  }, {});
}

function stringifyJsonField(value: unknown): string {
  if (value === undefined || value === null) return '';
  return JSON.stringify(value, null, 2);
}

function InspectorField({
  block,
  path,
  label,
  type,
  defaultValue,
  options,
  modelFields,
  onChange,
}: {
  block: DslBlockV3;
  path: string;
  label: string;
  type: string;
  defaultValue?: unknown;
  options?: { label: string; value: string }[];
  modelFields: ModelFieldDefinition[];
  onChange: (path: string, value: unknown) => void;
}) {
  const value = getByPath(block as unknown as Record<string, unknown>, path);
  const id = `inspector-field-${path}`;
  const jsonValue = type === 'json' ? stringifyJsonField(value) : '';
  const [jsonDraft, setJsonDraft] = useState(jsonValue);
  const [jsonError, setJsonError] = useState('');

  useEffect(() => {
    if (type !== 'json') return;
    setJsonDraft(jsonValue);
    setJsonError('');
  }, [jsonValue, path, type]);

  const applyJsonDraft = () => {
    const rawValue = jsonDraft.trim();
    if (!rawValue) {
      setJsonError('');
      onChange(path, undefined);
      return;
    }

    try {
      const parsed = JSON.parse(rawValue);
      setJsonError('');
      onChange(path, parsed);
    } catch {
      setJsonError('Invalid JSON');
    }
  };

  if (type === 'boolean') {
    const checkedValue = value === undefined ? Boolean(defaultValue) : Boolean(value);

    return (
      <label className="flex items-center justify-between gap-3 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm">
        <span className="font-medium text-slate-700">{label}</span>
        <input
          data-testid={id}
          type="checkbox"
          checked={checkedValue}
          onChange={(event) => onChange(path, event.target.checked)}
          className="h-4 w-4 rounded border-slate-300 text-blue-600"
        />
      </label>
    );
  }

  if (type === 'select') {
    return (
      <label className="block">
        <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">
          {label}
        </span>
        <select
          data-testid={id}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(path, event.target.value)}
          className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        >
          <option value="">Unset</option>
          {options?.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (type === 'field-select') {
    const currentValue = typeof value === 'string' ? value : '';
    const fieldOptions = createModelFieldOptions(modelFields, currentValue);

    return (
      <label className="block">
        <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">
          {label}
        </span>
        <select
          data-testid={id}
          value={currentValue}
          onChange={(event) => onChange(path, event.target.value)}
          className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        >
          <option value="">Unset</option>
          {fieldOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (type === 'json') {
    return (
      <div className="rounded-md border border-slate-200 bg-white p-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <label
            htmlFor={id}
            className="text-xs font-semibold uppercase tracking-wide text-slate-500"
          >
            {label}
          </label>
          <button
            type="button"
            data-testid={`inspector-json-field-apply-${path}`}
            onClick={applyJsonDraft}
            className="rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Apply
          </button>
        </div>
        <textarea
          id={id}
          data-testid={id}
          value={jsonDraft}
          onChange={(event) => {
            setJsonDraft(event.target.value);
            setJsonError('');
          }}
          spellCheck={false}
          aria-invalid={Boolean(jsonError)}
          className="h-24 w-full resize-y rounded-md border border-slate-300 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-50 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        />
        {jsonError ? (
          <div
            className="mt-2 text-xs font-medium text-red-600"
            data-testid={`inspector-json-field-error-${path}`}
          >
            {jsonError}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </span>
      <input
        data-testid={id}
        type={type === 'number' ? 'number' : 'text'}
        value={toInspectorInputValue(value)}
        onChange={(event) =>
          onChange(path, type === 'number' ? Number(event.target.value) : event.target.value)
        }
        className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
      />
    </label>
  );
}

function toInspectorInputValue(value: unknown): string | number {
  if (typeof value === 'number') return value;
  if (typeof value === 'string') return value;
  if (isLocalizedTextObject(value)) {
    return value.en || value['zh-CN'] || Object.values(value)[0] || '';
  }
  if (value === undefined || value === null) return '';
  return String(value);
}

function isLocalizedTextObject(value: unknown): value is Record<string, string> {
  return (
    Boolean(value) &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.values(value as Record<string, unknown>).every((item) => typeof item === 'string')
  );
}

function getBlockLabel(block: DslBlockV3): string {
  const title = block.title;
  if (typeof title === 'string') return title;
  if (title?.en) return title.en;
  if (title?.['zh-CN']) return title['zh-CN'];
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function createModelFieldOptions(
  modelFields: ModelFieldDefinition[],
  currentValue: string,
): { label: string; value: string }[] {
  const options = modelFields.map((field) => ({
    label: `${getLocalizedLabel(field.label)} (${field.code})`,
    value: field.code,
  }));

  if (currentValue && !options.some((option) => option.value === currentValue)) {
    return [{ label: currentValue, value: currentValue }, ...options];
  }

  return options;
}

function getLocalizedLabel(label: ModelFieldDefinition['label']): string {
  if (typeof label === 'string') return label;
  return label.en || label['zh-CN'] || Object.values(label)[0] || '';
}
