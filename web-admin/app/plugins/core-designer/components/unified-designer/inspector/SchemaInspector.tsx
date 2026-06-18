import React, { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { get } from '~/shared/services/http-client';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import { getByPath } from '../utils/dotPath';
import type { DslBlockV3, ModelFieldDefinition } from '../types';
import { getInspectorFields } from './schemas';

/** A selectable model option for `type: 'model'` inspector fields. */
interface ModelOption {
  /** The modelCode persisted into the block (e.g. dataSource.model). */
  value: string;
  /** Human-readable label shown in the dropdown. */
  label: string;
}

interface MetaModelRecord {
  code?: string;
  displayName?: string;
}

interface MetaModelPage {
  records?: MetaModelRecord[];
}

/**
 * Load the published meta-model list once so every `type: 'model'` inspector
 * field can render a real dropdown (modelCode value, displayName label) instead
 * of a free-text box. The list is fetched a single time per inspector mount and
 * shared across all model fields. On failure the dropdown degrades to the
 * manual-entry fallback (the current value is always preserved), so a missing
 * MODEL_READ permission or an offline backend never blocks authoring.
 *
 * GET /api/meta/models returns a MyBatis-Plus page: `data.records[]` (NOT
 * `data.data[]`). Verified against the live OSS backend (2026-06-17).
 */
function useModelOptions(): { options: ModelOption[]; loaded: boolean } {
  const [options, setOptions] = useState<ModelOption[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void get<MetaModelPage>('/api/meta/models', {
      page: 1,
      size: 500,
      currentOnly: 'true',
      status: 'published',
    })
      .then((result) => {
        if (cancelled) return;
        const records = result?.data?.records ?? [];
        const mapped = records
          .filter((record): record is Required<Pick<MetaModelRecord, 'code'>> & MetaModelRecord =>
            Boolean(record.code),
          )
          .map((record) => ({
            value: record.code as string,
            label: record.displayName ? `${record.displayName} (${record.code})` : (record.code as string),
          }));
        setOptions(mapped);
        setLoaded(true);
      })
      .catch(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { options, loaded };
}

// D2 — rich property controls. The inspector turns a free-text box into a real
// dropdown for fields that bind a registry code: dict / named-query / command /
// permission. Each source is a global list endpoint; the response is unwrapped
// robustly (array | data.records | data.list | data.content) and mapped to
// { value: code, label: name|displayName|description }. Same philosophy as the
// model selector: any fetch failure / missing read permission degrades to the
// manual-entry fallback, so it NEVER blocks authoring. Endpoints verified against
// the OSS controllers (CommandController /api/meta/commands listAll,
// DictController /api/meta/dict, named-queries, /api/permissions) 2026-06-18.
const REMOTE_SOURCES: Record<
  string,
  { url: string; params: Record<string, string | number> }
> = {
  'dict-select': { url: '/api/meta/dict', params: { page: 1, size: 500 } },
  namedQuery: { url: '/api/meta/named-queries', params: { status: 'published', pageSize: 200 } },
  'command-select': { url: '/api/meta/commands', params: {} },
  // The permission registry is exposed as a tree (module → resource → action);
  // unwrapRemoteRecords flattens the `children` so every code is selectable.
  'permission-select': { url: '/api/permissions/tree', params: {} },
};

/** Remote-select control types that resolve their options from REMOTE_SOURCES. */
const REMOTE_SELECT_TYPES = new Set(Object.keys(REMOTE_SOURCES));

interface RemoteRecord {
  code?: string;
  permissionCode?: string;
  queryCode?: string;
  name?: string;
  displayName?: string;
  description?: string;
  children?: RemoteRecord[];
}

function unwrapRemoteRecords(data: unknown): RemoteRecord[] {
  let list: RemoteRecord[] = [];
  if (Array.isArray(data)) {
    list = data as RemoteRecord[];
  } else if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>;
    const inner = obj.records ?? obj.list ?? obj.content ?? obj.data;
    if (Array.isArray(inner)) list = inner as RemoteRecord[];
  }
  // Flatten any nested `children` (e.g. the permission tree) so every leaf code
  // surfaces; flat lists (no children) pass through unchanged.
  const flat: RemoteRecord[] = [];
  const walk = (records: RemoteRecord[]): void => {
    for (const record of records) {
      flat.push(record);
      if (Array.isArray(record.children)) walk(record.children);
    }
  };
  walk(list);
  return flat;
}

function mapRemoteOption(record: RemoteRecord): ModelOption | null {
  const value = record.code ?? record.permissionCode ?? record.queryCode;
  if (!value) return null;
  const name = record.displayName ?? record.name ?? record.description;
  return { value, label: name ? `${name} (${value})` : value };
}

/**
 * Fetch each needed remote-select source once and return a map keyed by the
 * control type. Only the sources actually present on the current block's fields
 * are fetched (no wasted calls when no remote field is shown).
 */
function useRemoteOptions(neededTypes: string[]): Record<string, ModelOption[]> {
  const [map, setMap] = useState<Record<string, ModelOption[]>>({});
  const key = Array.from(new Set(neededTypes)).sort().join(',');

  useEffect(() => {
    if (!key) return;
    let cancelled = false;
    for (const type of key.split(',')) {
      const source = REMOTE_SOURCES[type];
      if (!source) continue;
      void get<unknown>(source.url, source.params)
        .then((result) => {
          if (cancelled) return;
          const options = unwrapRemoteRecords((result as { data?: unknown })?.data)
            .map(mapRemoteOption)
            .filter((option): option is ModelOption => option !== null);
          setMap((prev) => ({ ...prev, [type]: options }));
        })
        .catch(() => {
          // Graceful: leave this source unset → the field renders its manual
          // fallback (the current value is always preserved).
        });
    }
    return () => {
      cancelled = true;
    };
  }, [key]);

  return map;
}

interface SchemaInspectorProps {
  block: DslBlockV3 | null;
  modelFields?: ModelFieldDefinition[];
  onChange: (path: string, value: unknown) => void;
}

const INSPECTOR_LABELS: Record<string, Record<string, string>> = DESIGNER_I18N.unified.inspectorLabels;

/** Resolve an inspector field/option label, falling back to its English source. */
function resolveInspectorLabel(label: string, locale: string): string {
  const entry = INSPECTOR_LABELS[label];
  return entry ? resolveDesignerText(entry, locale) : label;
}

export function SchemaInspector({ block, modelFields = [], onChange }: SchemaInspectorProps) {
  const { locale } = useI18n();
  const fields = getInspectorFields(block);
  const { options: modelOptions } = useModelOptions();
  // D2 — fetch only the remote-select sources this block's fields actually need.
  const remoteOptions = useRemoteOptions(
    fields.filter((field) => REMOTE_SELECT_TYPES.has(field.type)).map((field) => field.type),
  );
  const [activeTab, setActiveTab] = useState<'basic' | 'advanced'>('basic');

  useEffect(() => {
    setActiveTab('basic');
  }, [block?.id]);

  if (!block) {
    return (
      <div className="p-4 text-sm text-slate-500" data-testid="inspector-empty">
        {resolveDesignerText(DESIGNER_I18N.unified.selectBlockHint, locale)}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-slate-200 px-4 py-3">
        <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.inspector, locale)}
        </div>
        <div className="mt-1 text-sm font-semibold text-slate-900">{getBlockLabel(block, locale)}</div>
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
            {resolveDesignerText(DESIGNER_I18N.unified.basic, locale)}
          </button>
          <button
            type="button"
            data-testid="inspector-tab-advanced"
            onClick={() => setActiveTab('advanced')}
            className={`px-3 py-2 ${
              activeTab === 'advanced' ? 'font-medium text-blue-700' : 'text-slate-500'
            }`}
          >
            {resolveDesignerText(DESIGNER_I18N.unified.advancedJson, locale)}
          </button>
        </div>

        {activeTab === 'basic' ? (
          <div className="space-y-4">
            {fields.map((field) => (
              <InspectorField
                key={field.key}
                block={block}
                path={field.key}
                label={resolveInspectorLabel(String(field.label), locale)}
                type={field.type}
                defaultValue={field.defaultValue}
                options={field.options?.map((option) => ({
                  label: resolveInspectorLabel(String(option.label), locale),
                  value: option.value,
                }))}
                modelFields={modelFields}
                modelOptions={modelOptions}
                remoteOptions={remoteOptions[field.type] ?? []}
                locale={locale}
                onChange={onChange}
              />
            ))}
          </div>
        ) : (
          <AdvancedJsonInspector block={block} locale={locale} onChange={onChange} />
        )}
      </div>
    </div>
  );
}

const ADVANCED_JSON_FIELDS: Array<{
  key: 'props' | 'layout' | 'dataSource' | 'extension';
  label: Record<string, string>;
}> = [
  { key: 'props', label: DESIGNER_I18N.unified.jsonProps },
  { key: 'layout', label: DESIGNER_I18N.unified.jsonLayout },
  { key: 'dataSource', label: DESIGNER_I18N.unified.jsonDataSource },
  { key: 'extension', label: DESIGNER_I18N.unified.jsonExtension },
];

function AdvancedJsonInspector({
  block,
  locale,
  onChange,
}: {
  block: DslBlockV3;
  locale: string;
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
      setErrors((current) => ({
        ...current,
        [key]: resolveDesignerText(DESIGNER_I18N.unified.invalidJson, locale),
      }));
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
              {resolveDesignerText(field.label, locale)}
            </label>
            <button
              type="button"
              data-testid={`inspector-json-apply-${field.key}`}
              onClick={() => applyDraft(field.key)}
              className="rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.apply, locale)}
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
  modelOptions,
  remoteOptions,
  locale,
  onChange,
}: {
  block: DslBlockV3;
  path: string;
  label: string;
  type: string;
  defaultValue?: unknown;
  options?: { label: string; value: string }[];
  modelFields: ModelFieldDefinition[];
  modelOptions: ModelOption[];
  remoteOptions: ModelOption[];
  locale: string;
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
      setJsonError(resolveDesignerText(DESIGNER_I18N.unified.invalidJson, locale));
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
          <option value="">{resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}</option>
          {options?.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (REMOTE_SELECT_TYPES.has(type)) {
    // D2 — dict / named-query / command / permission selector. Same shape as the
    // model selector: a dropdown over the live registry plus a manual-entry
    // fallback (so a code not yet in the list — draft / cross-plugin / failed
    // fetch — is never silently dropped).
    const currentValue = typeof value === 'string' ? value : '';
    const hasCurrent = currentValue && remoteOptions.some((option) => option.value === currentValue);
    const selectOptions =
      hasCurrent || !currentValue
        ? remoteOptions
        : [{ label: currentValue, value: currentValue }, ...remoteOptions];
    const manualId = `${id}-manual`;

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
          <option value="">{resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}</option>
          {selectOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <input
          data-testid={manualId}
          type="text"
          value={currentValue}
          placeholder={resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}
          onChange={(event) => onChange(path, event.target.value)}
          className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 font-mono text-xs text-slate-500 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        />
      </label>
    );
  }

  if (type === 'model') {
    const currentValue = typeof value === 'string' ? value : '';
    // Forward-compatible: if the bound modelCode is not in the published list
    // (e.g. a draft/external model, or the list failed to load), surface it as a
    // leading option so the dropdown never silently drops the current binding.
    const hasCurrent = currentValue && modelOptions.some((option) => option.value === currentValue);
    const selectOptions = hasCurrent || !currentValue
      ? modelOptions
      : [{ label: currentValue, value: currentValue }, ...modelOptions];
    const manualId = `${id}-manual`;

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
          <option value="">{resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}</option>
          {selectOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {/* Manual-entry fallback: an author can still bind a model code that is
            not in the published list (draft / cross-plugin / not yet loaded). */}
        <input
          data-testid={manualId}
          type="text"
          value={currentValue}
          placeholder={resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}
          onChange={(event) => onChange(path, event.target.value)}
          className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 font-mono text-xs text-slate-500 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        />
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
          <option value="">{resolveDesignerText(DESIGNER_I18N.unified.unset, locale)}</option>
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
            {resolveDesignerText(DESIGNER_I18N.unified.apply, locale)}
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

function getBlockLabel(block: DslBlockV3, locale: string): string {
  const title = block.title;
  if (typeof title === 'string') return title;
  if (title) {
    const resolved = title[locale] || title['en-US'] || title.en || title['zh-CN'];
    if (resolved) return resolved;
  }
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
