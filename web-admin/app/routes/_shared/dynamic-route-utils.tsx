/**
 * Shared utilities for dynamic routes
 * Eliminates code duplication across list/new/edit/view pages
 */

import React from 'react';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';

// 导入并重新导出统一的 i18n 实现
import {
  getLocalizedText,
  type LocalizedText,
  type TranslatableText,
  type TranslateFunction,
} from '~/framework/meta/runtime/expression/i18n-renderer';

// 重新导出供外部使用
export { getLocalizedText, type LocalizedText, type TranslatableText, type TranslateFunction };

function parseMemberPickerValue(value: unknown, multiple: boolean): string | string[] | undefined {
  if (value == null || value === '') return undefined;

  if (Array.isArray(value)) {
    const ids = value.map((item) => String(item ?? '').trim()).filter(Boolean);
    return multiple ? ids : ids[0];
  }

  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return undefined;

    if (trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed);
        if (Array.isArray(parsed)) {
          const ids = parsed.map((item) => String(item ?? '').trim()).filter(Boolean);
          return multiple ? ids : ids[0];
        }
      } catch {
        // Ignore malformed persisted payloads and fall back to raw string display.
      }
    }

    return multiple ? [trimmed] : trimmed;
  }

  const normalized = String(value).trim();
  if (!normalized) return undefined;
  return multiple ? [normalized] : normalized;
}

function parseReadonlyValueList(value: unknown, multiple = false): string[] {
  const parsed = parseMemberPickerValue(value, multiple);
  if (Array.isArray(parsed)) return parsed;
  return parsed ? [parsed] : [];
}

function formatAddressValue(value: unknown): string {
  if (value == null || value === '') return '-';

  let address = value;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return '-';
    if (trimmed.startsWith('{')) {
      try {
        address = JSON.parse(trimmed);
      } catch {
        return trimmed;
      }
    } else {
      return trimmed;
    }
  }

  if (address && typeof address === 'object' && !Array.isArray(address)) {
    const data = address as Record<string, unknown>;
    const parts = [data.province, data.city, data.district, data.street, data.detail, data.address]
      .map((part) => String(part ?? '').trim())
      .filter(Boolean);
    return parts.length > 0 ? parts.join(' ') : '-';
  }

  return String(address).trim() || '-';
}

function formatJsonValue(value: unknown): string | null {
  if (value == null || value === '') return null;

  let jsonValue = value;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return null;
    try {
      jsonValue = JSON.parse(trimmed);
    } catch {
      return trimmed;
    }
  }

  try {
    return JSON.stringify(jsonValue, null, 2);
  } catch {
    return String(value);
  }
}

function isJsonFieldCode(fieldCode: string): boolean {
  return /(^|_)json$/i.test(fieldCode.trim());
}

/**
 * Build API endpoint with table name
 */
export function buildApiEndpoint(tableName: string, recordId?: string): string {
  const base = `/api/dynamic/${tableName}`;
  return recordId ? `${base}/${recordId}` : base;
}

export interface RouteLocationLike {
  pathname: string;
  search?: string;
  hash?: string;
}

export type LocalMenuRedirectResolution =
  | { shouldRedirect: true; target: string; error?: undefined }
  | { shouldRedirect: false; target?: undefined; error?: string };

/**
 * Resolve menu.redirect into a same-origin app route.
 * Menu redirects are stored in tenant-scoped DB config, so keep them local-only
 * and reject self-redirects before the catch-all route calls navigate().
 */
export function resolveLocalMenuRedirect(
  redirect: unknown,
  location: RouteLocationLike,
): LocalMenuRedirectResolution {
  const raw = typeof redirect === 'string' ? redirect.trim() : '';
  if (!raw) return { shouldRedirect: false };

  if (!raw.startsWith('/') || raw.startsWith('//')) {
    return { shouldRedirect: false, error: 'Menu redirect must be an app-local path' };
  }

  let targetUrl: URL;
  try {
    targetUrl = new URL(raw, 'http://auraboot.local');
  } catch {
    return { shouldRedirect: false, error: 'Menu redirect is not a valid route' };
  }

  if (targetUrl.origin !== 'http://auraboot.local') {
    return { shouldRedirect: false, error: 'Menu redirect must stay within the current app' };
  }

  const targetSearch = targetUrl.search || location.search || '';
  const targetHash = targetUrl.hash || location.hash || '';
  const target = `${targetUrl.pathname}${targetSearch}${targetHash}`;
  const current = `${location.pathname}${location.search || ''}${location.hash || ''}`;
  if (target === current) {
    return { shouldRedirect: false, error: 'Menu redirect points to the current route' };
  }

  return { shouldRedirect: true, target };
}

// ============================================
// DynamicField Component for legacy pages
// ============================================

interface FieldConfig {
  field: string;
  label?: string | LocalizedText;
  component?: string;
  dataType?: string;
  dictCode?: string;
  refTarget?: Record<string, any>;
  referenceModelCode?: string;
  props?: Record<string, any>;
  validation?: ValidationRule[];
  span?: number;
  layout?: { colSpan?: number };
}

interface ValidationRule {
  type: string;
  message: string | LocalizedText;
  pattern?: string;
  min?: number;
  max?: number;
}

interface DynamicFieldProps {
  field: FieldConfig;
  value: any;
  onChange: (value: any) => void;
  readOnly?: boolean;
  locale?: string;
  getDictItems?: (
    code: string,
  ) => Array<{ value: string; label: string; extension?: Record<string, any> }>;
}

function getReferenceModel(field: FieldConfig): string {
  const refTarget = {
    ...(field.props?.refTarget || {}),
    ...(field.refTarget || {}),
  };
  return String(
    refTarget.targetModel ||
      refTarget.modelCode ||
      field.referenceModelCode ||
      field.props?.referenceModelCode ||
      '',
  ).toLowerCase();
}

/**
 * Field code on the referenced record that should be shown as its display name.
 * Mirrors RuntimeFieldRenderer's `displayField || labelField || targetField` precedence
 * so authored DSL (`refTarget.displayField` / `refTarget.targetField`) renders consistently
 * in editable pickers and read-only detail views. Unlike the model code this is a column
 * name, so it must NOT be lowercased.
 */
function getReferenceDisplayField(field: FieldConfig): string | undefined {
  const refTarget = {
    ...(field.props?.refTarget || {}),
    ...(field.refTarget || {}),
  };
  const candidate = refTarget.displayField || refTarget.labelField || refTarget.targetField;
  return candidate ? String(candidate) : undefined;
}

/**
 * Module-level cache of resolved reference labels, keyed by `<model>:<id>`. Reference
 * detail fields are read-only and the referenced record's display name is effectively
 * immutable within a session, so caching avoids re-fetching when the same record is
 * referenced by several fields / rows or across remounts (N+1 avoidance). Inflight
 * promises are deduped so concurrent fields pointing at the same record share one request.
 */
const referenceLabelCache = new Map<string, string>();
const referenceLabelInflight = new Map<string, Promise<string>>();

const REFERENCE_DISPLAY_CANDIDATE_FIELDS = ['name', 'title', 'displayName', 'label', 'code'];

function pickReferenceLabel(
  record: Record<string, unknown>,
  displayField: string | undefined,
  fallbackId: string,
): string {
  if (displayField && record[displayField] != null && record[displayField] !== '') {
    return String(record[displayField]);
  }
  for (const key of REFERENCE_DISPLAY_CANDIDATE_FIELDS) {
    if (record[key] != null && record[key] !== '') {
      return String(record[key]);
    }
  }
  return fallbackId;
}

async function resolveReferenceLabel(
  model: string,
  id: string,
  displayField: string | undefined,
): Promise<string> {
  const cacheKey = `${model}:${id}`;
  const cached = referenceLabelCache.get(cacheKey);
  if (cached !== undefined) return cached;

  const inflight = referenceLabelInflight.get(cacheKey);
  if (inflight) return inflight;

  const request = (async () => {
    try {
      const resp = await fetch(`/api/dynamic/${model}/${encodeURIComponent(id)}`);
      if (!resp.ok) return id;
      const body = await resp.json();
      const record = (body?.data ?? {}) as Record<string, unknown>;
      const label = pickReferenceLabel(record, displayField, id);
      referenceLabelCache.set(cacheKey, label);
      return label;
    } catch {
      return id;
    } finally {
      referenceLabelInflight.delete(cacheKey);
    }
  })();

  referenceLabelInflight.set(cacheKey, request);
  return request;
}

/**
 * Read-only renderer for generic reference fields (a record pointing at another model).
 * Resolves each referenced id to its display name via `/api/dynamic/<model>/<id>` instead of
 * leaking the raw ULID. System pickers (sys_user / org_department) are handled by their own
 * dedicated read-only renderers above; this covers every other `refTarget.targetModel`.
 */
const ReadonlyReferenceValue: React.FC<{
  value: unknown;
  model: string;
  displayField?: string;
  multiple?: boolean;
}> = ({ value, model, displayField, multiple = false }) => {
  const ids = React.useMemo(() => parseReadonlyValueList(value, multiple), [value, multiple]);
  const idsKey = ids.join('|');
  const [labels, setLabels] = React.useState<string[]>(() =>
    ids.map((id) => referenceLabelCache.get(`${model}:${id}`) ?? id),
  );
  const [resolved, setResolved] = React.useState(() =>
    ids.every((id) => referenceLabelCache.has(`${model}:${id}`)),
  );

  React.useEffect(() => {
    let active = true;
    if (ids.length === 0) {
      setLabels([]);
      setResolved(true);
      return () => {
        active = false;
      };
    }

    const allCached = ids.every((id) => referenceLabelCache.has(`${model}:${id}`));
    if (allCached) {
      setLabels(ids.map((id) => referenceLabelCache.get(`${model}:${id}`) as string));
      setResolved(true);
      return () => {
        active = false;
      };
    }

    setResolved(false);
    Promise.all(ids.map((id) => resolveReferenceLabel(model, id, displayField))).then(
      (nextLabels) => {
        if (!active) return;
        setLabels(nextLabels);
        setResolved(true);
      },
    );

    return () => {
      active = false;
    };
  }, [idsKey, model, displayField]);

  if (ids.length === 0) {
    return <span className="py-1 text-sm text-gray-400">&mdash;</span>;
  }

  if (!resolved) {
    // Loading placeholder — never flash the raw ULID before the display name resolves.
    return (
      <div className="py-1" aria-label="Loading" data-testid="reference-readonly-loading">
        <div className="h-4 w-24 animate-pulse rounded bg-gray-200/80" />
      </div>
    );
  }

  return (
    <div className="py-1 text-sm text-gray-900" data-testid="reference-readonly">
      {labels.join('、')}
    </div>
  );
};

const ReadonlyOrganizationValue: React.FC<{ value: unknown; multiple?: boolean }> = ({
  value,
  multiple = false,
}) => {
  const ids = React.useMemo(() => parseReadonlyValueList(value, multiple), [value, multiple]);
  const [labels, setLabels] = React.useState<string[]>([]);
  const [resolved, setResolved] = React.useState(false);

  React.useEffect(() => {
    let active = true;
    if (ids.length === 0) {
      setLabels([]);
      setResolved(true);
      return () => {
        active = false;
      };
    }

    setResolved(false);
    Promise.all(
      ids.map(async (id) => {
        try {
          const resp = await fetch(`/api/dynamic/org_department/${encodeURIComponent(id)}`);
          if (!resp.ok) return id;
          const body = await resp.json();
          const record = body?.data ?? {};
          return String(
            record.org_dept_name ||
              record.name ||
              record.displayName ||
              record.org_dept_code ||
              record.code ||
              id,
          );
        } catch {
          return id;
        }
      }),
    ).then((nextLabels) => {
      if (!active) return;
      setLabels(nextLabels.filter(Boolean));
      setResolved(true);
    });

    return () => {
      active = false;
    };
  }, [ids.join('|')]);

  if (ids.length === 0 || (resolved && labels.length === 0)) {
    return <span className="py-1 text-sm text-gray-400">&mdash;</span>;
  }

  return <div className="py-1 text-sm text-gray-900">{resolved ? labels.join('、') : ''}</div>;
};

/**
 * DynamicField - Legacy field renderer for dynamic pages
 * Supports common field types with read-only mode
 */
export const DynamicField: React.FC<DynamicFieldProps> = ({
  field,
  value,
  onChange,
  readOnly = false,
  locale = 'zh-CN',
  getDictItems,
}) => {
  const label =
    typeof field.label === 'string'
      ? field.label
      : getLocalizedText(field.label, locale) || field.field;

  const isRequired = field.validation?.some((v) => v.type === 'required');
  const componentType = field.component?.toLowerCase() || 'smartinput';
  const effectiveDictCode =
    field.dictCode ||
    (typeof field.props?.dictCode === 'string' && field.props.dictCode.trim()
      ? field.props.dictCode
      : undefined);

  // Common input classes
  const inputClasses = `w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500 ${
    readOnly ? 'bg-gray-50 cursor-not-allowed' : ''
  }`;

  const renderField = () => {
    // Read-only display
    if (readOnly) {
      const referenceModel = getReferenceModel(field);
      const isUserSelect =
        componentType === 'userselect' ||
        componentType === 'smartuserselect' ||
        (componentType === 'reference' && referenceModel === 'sys_user');
      const isOrganizationSelect =
        componentType === 'organizationselect' ||
        componentType === 'smartorganizationselect' ||
        (componentType === 'reference' && referenceModel === 'org_department');

      if (componentType === 'memberpicker' || isUserSelect) {
        const memberValue = parseMemberPickerValue(value, Boolean(field.props?.multiple));
        return (
          <MemberPicker
            value={memberValue}
            multiple={Boolean(field.props?.multiple)}
            readOnly
            className="py-1"
          />
        );
      }

      if (isOrganizationSelect) {
        return (
          <ReadonlyOrganizationValue value={value} multiple={Boolean(field.props?.multiple)} />
        );
      }

      // Generic reference field — resolve the target record's display name instead of
      // leaking the raw ULID. sys_user / org_department are handled by their dedicated
      // renderers above; this covers every other refTarget.targetModel / referenceModelCode.
      const isReferenceComponent =
        componentType === 'reference' ||
        componentType === 'referenceselect' ||
        componentType === 'smartreference' ||
        componentType === 'relationfield' ||
        componentType === 'relationselect';
      if (
        (isReferenceComponent || (referenceModel && !effectiveDictCode)) &&
        referenceModel &&
        value != null &&
        value !== ''
      ) {
        return (
          <ReadonlyReferenceValue
            value={value}
            model={referenceModel}
            displayField={getReferenceDisplayField(field)}
            multiple={Boolean(field.props?.multiple)}
          />
        );
      }

      if (componentType === 'addressfield') {
        const displayValue = formatAddressValue(value);
        return (
          <div className="py-1 text-sm text-gray-900">
            {displayValue === '-' ? <span className="text-gray-400">&mdash;</span> : displayValue}
          </div>
        );
      }

      if (['jsonviewer', 'json', 'jsonb'].includes(componentType) || isJsonFieldCode(field.field)) {
        const formattedJson = formatJsonValue(value);
        if (!formattedJson) {
          return <span className="py-1 text-sm text-gray-400">&mdash;</span>;
        }
        return (
          <pre
            data-testid={`readonly-json-${field.field}`}
            className="max-h-80 overflow-auto rounded-md border border-slate-200 bg-slate-50 p-3 font-mono text-xs leading-relaxed break-words whitespace-pre-wrap text-slate-900"
          >
            {formattedJson}
          </pre>
        );
      }

      // 1. Dict field with color tag
      if (effectiveDictCode && getDictItems) {
        const items = getDictItems(effectiveDictCode);
        const values = parseReadonlyValueList(value, Boolean(field.props?.multiple));
        const labels = values
          .map((currentValue) => items.find((i) => String(i.value) === currentValue)?.label)
          .filter((currentLabel): currentLabel is string => Boolean(currentLabel));
        if (labels.length > 1 || (labels.length === 1 && values.length > 1)) {
          return <div className="py-1 text-sm text-gray-900">{labels.join('、')}</div>;
        }
        const item = items.find((i) => String(i.value) === String(value));
        if (item) {
          // §3 / §1.3: dict-coded status renders as 色点 + 文字, not a filled pill.
          return <StatusDot tone={resolveStatusTone(item.extension?.color)} label={item.label} />;
        }
      }

      // 2. Boolean as visual toggle
      if (['smartswitch', 'switch', 'smartcheckbox', 'checkbox'].includes(componentType)) {
        return (
          <div
            className={`relative inline-flex h-6 w-11 items-center rounded-full ${value ? 'bg-blue-600' : 'bg-gray-200'}`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${value ? 'translate-x-6' : 'translate-x-1'}`}
            />
          </div>
        );
      }

      // 3. Progress bar
      if (['progress', 'progressfield'].includes(componentType)) {
        const pct = Number(value) || 0;
        return (
          <div className="flex items-center gap-2">
            <div className="h-2 flex-1 rounded-full bg-gray-200">
              <div
                className="h-2 rounded-full bg-blue-600"
                style={{ width: `${Math.min(pct, 100)}%` }}
              />
            </div>
            <span className="text-sm text-gray-600">{pct}%</span>
          </div>
        );
      }

      // 4. Rating stars
      if (['rating', 'ratingfield'].includes(componentType)) {
        const stars = Number(value) || 0;
        return (
          <div className="flex gap-0.5">
            {[1, 2, 3, 4, 5].map((i) => (
              <svg
                key={i}
                className={`h-5 w-5 ${i <= stars ? 'text-yellow-400' : 'text-gray-300'}`}
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
              </svg>
            ))}
          </div>
        );
      }

      // 5. Color picker - show color swatch
      if (['colorpicker', 'color_picker', 'color'].includes(componentType) && value) {
        return (
          <div className="flex items-center gap-2 py-1">
            <div
              className="h-6 w-6 rounded border border-gray-300"
              style={{ backgroundColor: String(value) }}
            />
            <span className="text-sm text-gray-700">{String(value)}</span>
          </div>
        );
      }

      // 6. Rich text - render HTML content
      if (['richtext', 'richtexteditor', 'rich_text'].includes(componentType) && value) {
        return (
          <div
            className="prose prose-sm max-w-none rounded-md border border-gray-200 bg-gray-50 p-3 text-gray-900"
            dangerouslySetInnerHTML={{ __html: sanitizeHtml(String(value)) }}
          />
        );
      }

      // 7. File attachment - render download links
      if (['fileattachment', 'file_attachment', 'attachment'].includes(componentType) && value) {
        let files: Array<{ name: string; url: string; size?: number }> = [];
        try {
          files = typeof value === 'string' ? JSON.parse(value) : Array.isArray(value) ? value : [];
        } catch {
          /* ignore parse errors */
        }
        if (files.length === 0) {
          return <span className="py-1 text-sm text-gray-400">&mdash;</span>;
        }
        return (
          <div className="space-y-1 py-1">
            {files.map((f, i) => (
              <a
                key={i}
                href={f.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 text-sm text-blue-600 hover:underline"
              >
                <svg
                  className="h-4 w-4 flex-shrink-0"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                  />
                </svg>
                {f.name}
                {f.size
                  ? ` (${f.size < 1024 ? f.size + ' B' : f.size < 1048576 ? (f.size / 1024).toFixed(1) + ' KB' : (f.size / 1048576).toFixed(1) + ' MB'})`
                  : ''}
              </a>
            ))}
          </div>
        );
      }

      // 8. Date/time formatting, options lookup, null handling
      let displayValue = value;
      const options = field.props?.options || [];

      if (['smartdate', 'date'].includes(componentType) && value) {
        displayValue = new Date(value).toLocaleDateString(locale);
      } else if (['smartdatetime', 'datetime'].includes(componentType) && value) {
        displayValue = new Date(value).toLocaleString(locale);
      } else if (options.length > 0) {
        const matchedOption = options.find((opt: any) => String(opt?.value) === String(value));
        if (matchedOption) {
          displayValue =
            typeof matchedOption.label === 'string'
              ? matchedOption.label
              : getLocalizedText(matchedOption.label, locale) || matchedOption.value;
        }
      } else if (value === null || value === undefined) {
        displayValue = '-';
      }

      // 9. URL detection - render as clickable link
      if (
        displayValue &&
        typeof displayValue === 'string' &&
        /^https?:\/\/.+/i.test(displayValue)
      ) {
        return (
          <div className="py-1 text-sm">
            <a
              href={displayValue}
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-600 hover:text-blue-800 hover:underline"
            >
              {displayValue}
            </a>
          </div>
        );
      }

      // 10. Email detection - render as mailto link
      if (
        displayValue &&
        typeof displayValue === 'string' &&
        /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(displayValue)
      ) {
        return (
          <div className="py-1 text-sm">
            <a
              href={`mailto:${displayValue}`}
              className="text-blue-600 hover:text-blue-800 hover:underline"
            >
              {displayValue}
            </a>
          </div>
        );
      }

      // 11. Object / jsonb values would stringify to "[object Object]" — render
      // formatted JSON instead (e.g. cr_cd_metadata spec_table on detail pages).
      // Unwraps the { type:'jsonb', value:'<json string>' } envelope some APIs return.
      if (displayValue != null && typeof displayValue === 'object') {
        const envelope =
          !Array.isArray(displayValue) &&
          typeof (displayValue as { value?: unknown }).value === 'string' &&
          ['json', 'jsonb'].includes(
            String((displayValue as { type?: unknown }).type ?? '').toLowerCase(),
          )
            ? ((displayValue as { value: string }).value as string)
            : null;
        let pretty: string;
        try {
          pretty = JSON.stringify(envelope != null ? JSON.parse(envelope) : displayValue, null, 2);
        } catch {
          pretty = envelope != null ? envelope : String(displayValue);
        }
        return (
          <pre className="max-h-80 overflow-auto rounded-md border border-gray-200 bg-gray-50 p-3 font-mono text-xs leading-relaxed break-words whitespace-pre-wrap text-gray-900">
            {pretty}
          </pre>
        );
      }

      return (
        <div className="py-1 text-sm text-gray-900">
          {displayValue === '-' ? (
            <span className="text-gray-400">&mdash;</span>
          ) : (
            String(displayValue)
          )}
        </div>
      );
    }

    // Editable fields
    switch (componentType) {
      case 'smarttextarea':
      case 'textarea':
        return (
          <textarea
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            rows={field.props?.rows || 3}
            className={inputClasses}
            placeholder={field.props?.placeholder}
          />
        );

      case 'smartnumber':
      case 'number':
        return (
          <input
            type="number"
            value={value ?? ''}
            onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
            className={inputClasses}
            min={field.props?.min}
            max={field.props?.max}
            step={field.props?.step}
            placeholder={field.props?.placeholder}
          />
        );

      case 'smartdate':
      case 'date':
        return (
          <input
            type="date"
            value={value ? value.substring(0, 10) : ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          />
        );

      case 'smartdatetime':
      case 'datetime':
        return (
          <input
            type="datetime-local"
            value={value ? value.substring(0, 16) : ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          />
        );

      case 'smartcheckbox':
      case 'checkbox':
        return (
          <input
            type="checkbox"
            checked={!!value}
            onChange={(e) => onChange(e.target.checked)}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        );

      case 'smartswitch':
      case 'switch':
        return (
          <button
            type="button"
            onClick={() => onChange(!value)}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
              value ? 'bg-blue-600' : 'bg-gray-200'
            }`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                value ? 'translate-x-6' : 'translate-x-1'
              }`}
            />
          </button>
        );

      case 'smartselect':
      case 'select':
        const options = field.props?.options || [];
        return (
          <select
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          >
            <option value="">{field.props?.placeholder || 'Please select'}</option>
            {options.map((opt: any) => (
              <option key={opt.value} value={opt.value}>
                {typeof opt.label === 'string' ? opt.label : getLocalizedText(opt.label, locale)}
              </option>
            ))}
          </select>
        );

      case 'smartinput':
      case 'text':
      default:
        return (
          <input
            type="text"
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
            placeholder={field.props?.placeholder}
            maxLength={field.props?.maxLength}
          />
        );
    }
  };

  return (
    <div className="mb-4">
      <label className="mb-0.5 block text-xs font-medium tracking-wide text-gray-500 uppercase">
        {label}
        {isRequired && <span className="ml-1 text-red-500">*</span>}
      </label>
      {renderField()}
    </div>
  );
};

// ============================================
// Form validation utilities
// ============================================

interface ValidationResult {
  isValid: boolean;
  errors: Record<string, string>;
}

/**
 * Validate form data against field definitions
 */
export function validateForm(
  formData: Record<string, any>,
  fields: FieldConfig[],
  locale: string = 'zh-CN',
): ValidationResult {
  const errors: Record<string, string> = {};

  for (const field of fields) {
    const value = formData[field.field];
    const rules = field.validation || [];

    for (const rule of rules) {
      const message =
        typeof rule.message === 'string'
          ? rule.message
          : getLocalizedText(rule.message, locale) || `${field.field} validation failed`;

      switch (rule.type) {
        case 'required':
          if (value === undefined || value === null || value === '') {
            errors[field.field] = message;
          }
          break;

        case 'min':
          if (typeof value === 'number' && rule.min !== undefined && value < rule.min) {
            errors[field.field] = message;
          }
          if (typeof value === 'string' && rule.min !== undefined && value.length < rule.min) {
            errors[field.field] = message;
          }
          break;

        case 'max':
          if (typeof value === 'number' && rule.max !== undefined && value > rule.max) {
            errors[field.field] = message;
          }
          if (typeof value === 'string' && rule.max !== undefined && value.length > rule.max) {
            errors[field.field] = message;
          }
          break;

        case 'pattern':
          if (rule.pattern && typeof value === 'string') {
            const regex = new RegExp(rule.pattern);
            if (!regex.test(value)) {
              errors[field.field] = message;
            }
          }
          break;

        case 'email':
          if (value && typeof value === 'string') {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!emailRegex.test(value)) {
              errors[field.field] = message;
            }
          }
          break;
      }

      // Stop at first error for this field
      if (errors[field.field]) break;
    }
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

/**
 * Extract form fields from a UnifiedSchema
 */
export function getFormFields(schema: any): FieldConfig[] {
  const fields: FieldConfig[] = [];

  if (!schema?.blocks) return fields;

  // Iterate through all blocks to find fields
  for (const block of schema.blocks) {
    // Check various block types that contain fields
    if (block.fields && Array.isArray(block.fields)) {
      fields.push(...block.fields);
    }
  }

  return fields;
}
