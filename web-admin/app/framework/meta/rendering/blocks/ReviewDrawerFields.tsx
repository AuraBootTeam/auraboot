import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { ComparisonList, isComparisonList } from './ReviewDrawerEvidence';
import { PriceComparison, PriceLadder } from './ReviewDrawerPrices';
import {
  applyValueMap,
  fillTemplate,
  formatConfiguredValue,
  formatValue,
  isEmptyValue,
  localized,
  parseLadderRungs,
  type RawColumnItem,
  readFieldValue,
  safeExternalUrl,
  type Tone,
  toneClass,
} from './reviewDrawerValues';

export function Badge({
  badge,
  record,
  runtime,
  relatedRecords,
  locale,
  t,
}: {
  badge: any;
  record: any;
  runtime: SchemaRuntime;
  relatedRecords: Record<string, unknown>;
  locale: string;
  t: (key: string) => string;
}) {
  const key = String(badge.key || badge.valueField || badge.label);
  const label = getLocalizedText(badge.label || key, locale, t);
  const value = badge.template
    ? fillTemplate(String(badge.template), runtime, record, new Map(), locale, t, relatedRecords)
    : readFieldValue(record, badge);
  const text = `${label} ${formatConfiguredValue(value, badge, locale, t)}${badge.unit ? String(badge.unit) : ''}`;
  const tone = (badge.tone || 'gray') as Tone;

  return (
    <span
      data-testid={`review-drawer-badge-${key}`}
      title={text}
      className={`rounded-pill inline-flex max-w-full truncate border px-2.5 py-1 text-xs font-semibold ${
        toneClass[tone] || toneClass.gray
      }`}
    >
      {text}
    </span>
  );
}

export function FieldRows({
  fields,
  record,
  fallbackRecord,
  locale,
  t,
  layout = 'rows',
}: {
  fields: any[];
  record: any;
  fallbackRecord?: any;
  locale: string;
  t: (key: string) => string;
  layout?: 'rows' | 'compact-grid';
}) {
  const isCompactGrid = layout === 'compact-grid';
  const hasUsableLadder = fields.some((field) => {
    if (field.format !== 'ladder') return false;
    const rungs = parseLadderRungs(readFieldValue(record, field, fallbackRecord));
    return Boolean(rungs && rungs.length >= 2);
  });

  return (
    <div
      data-testid={isCompactGrid ? 'review-drawer-preview-field-grid' : undefined}
      className={
        isCompactGrid
          ? 'bg-border grid grid-cols-1 gap-px sm:grid-cols-2 xl:grid-cols-4'
          : 'divide-border divide-y'
      }
    >
      {fields.map((field) => {
        const key = String(field.key || field.field || field.label);
        const label = getLocalizedText(field.label || key, locale, t);
        const rawValue = readFieldValue(record, field, fallbackRecord);
        const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
        if (field.format === 'price-comparison' && hasUsableLadder) return null;
        if (field.format === 'ladder' && (!ladder || ladder.length < 2)) return null;
        if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
        const mappedValue = applyValueMap(rawValue, field, locale, t);
        const rendersComparisons = isComparisonList(mappedValue);
        const value = rendersComparisons ? '' : formatConfiguredValue(rawValue, field, locale, t);
        const isMultiline = value.includes('\n') || value.length > 86;
        const externalUrl = field.format === 'link' ? safeExternalUrl(rawValue) : null;
        const fieldValue =
          field.format === 'price-comparison' ? (
            <PriceComparison
              original={rawValue}
              factored={readFieldValue(record, { field: field.factoredField })}
              factor={readFieldValue(record, { field: field.factorField })}
              locale={locale}
              t={t}
            />
          ) : ladder ? (
            <PriceLadder
              rowKey={String(field.rowKey || key)}
              rungs={ladder}
              factor={readFieldValue(record, { field: field.factorField })}
              locale={locale}
              t={t}
            />
          ) : externalUrl ? (
            <a
              href={externalUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-accent underline underline-offset-2"
              data-testid={`review-drawer-field-link-${key}`}
            >
              {getLocalizedText(field.linkLabel || { 'zh-CN': '查看', en: 'Open' }, locale, t)}
            </a>
          ) : rendersComparisons ? (
            <ComparisonList value={mappedValue} locale={locale} t={t} />
          ) : (
            value
          );
        if (isCompactGrid) {
          const requestedSpan = Number(field.gridSpan);
          const spanClass =
            requestedSpan === 4 || field.format === 'price-comparison' || field.format === 'ladder'
              ? 'sm:col-span-2 xl:col-span-4'
              : requestedSpan === 3
                ? 'sm:col-span-2 xl:col-span-3'
                : requestedSpan === 2
                  ? 'sm:col-span-2 xl:col-span-2'
                  : '';
          return (
            <div
              key={key}
              data-testid={`review-drawer-preview-field-${key}`}
              className={`bg-panel min-w-0 px-3 py-2 text-sm ${spanClass}`}
            >
              <dt className="text-text-2 mb-1 text-[11px] font-medium">{label}</dt>
              <dd
                className={`text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words ${
                  isMultiline ? 'whitespace-pre-wrap' : ''
                }`}
              >
                {fieldValue}
              </dd>
            </div>
          );
        }
        return (
          <div key={key} className="grid grid-cols-[118px_minmax(0,1fr)] gap-3 px-3 py-2.5 text-sm">
            <dt className="text-text-2 text-xs">{label}</dt>
            <dd
              className={`text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words ${
                isMultiline ? 'whitespace-pre-wrap' : ''
              }`}
            >
              {fieldValue}
            </dd>
          </div>
        );
      })}
    </div>
  );
}

export function RawColumnsPanel({
  columns,
  config,
  panelKey,
  locale,
  t,
}: {
  columns: RawColumnItem[];
  config: any;
  panelKey?: string;
  locale: string;
  t: (key: string) => string;
}) {
  if (columns.length === 0) return null;
  const title = getLocalizedText(
    config?.title || { 'zh-CN': '源 Excel 全列', en: 'Source Excel Columns' },
    locale,
    t,
  );
  const showSystemField = config?.showSystemField !== false;
  return (
    <section
      data-testid={panelKey ? `review-drawer-raw-columns-${panelKey}` : 'review-drawer-raw-columns'}
      className="border-border border-t"
    >
      <header className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
        <h4 className="text-text text-xs font-semibold">{title}</h4>
        <span className="rounded-pill border-border bg-subtle text-text-2 border px-2 py-0.5 text-[11px] font-medium">
          {localized(locale, t, `${columns.length} 列`, `${columns.length} columns`)}
        </span>
      </header>
      <div className="divide-border max-h-80 divide-y overflow-auto">
        {columns.map((column) => {
          const value = formatValue(column.value, config?.emptyText || '-');
          const showNormalizedHeader =
            column.normalizedHeader && column.normalizedHeader !== column.header;
          return (
            <div
              key={`${column.header}-${column.index}`}
              data-testid={
                panelKey
                  ? `review-drawer-raw-column-${panelKey}-${column.index}`
                  : `review-drawer-raw-column-${column.index}`
              }
              className="grid grid-cols-[minmax(118px,0.36fr)_minmax(0,1fr)] gap-3 px-3 py-2.5 text-sm"
            >
              <div className="min-w-0 space-y-1">
                <div className="text-text min-w-0 text-xs font-semibold break-words">
                  {column.header}
                </div>
                {showNormalizedHeader && (
                  <div className="text-text-2 min-w-0 text-[11px] break-words">
                    {column.normalizedHeader}
                  </div>
                )}
                {showSystemField && column.systemField && (
                  <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue inline-flex max-w-full border px-1.5 py-0.5 text-[10px] font-medium">
                    <span className="min-w-0 truncate">{column.systemField}</span>
                  </span>
                )}
              </div>
              <div className="text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words whitespace-pre-wrap">
                {value}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

export function FieldGroups({
  groups,
  record,
  fallbackRecord,
  locale,
  t,
}: {
  groups: any[];
  record: any;
  fallbackRecord?: any;
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div className="space-y-3 p-3">
      {groups.map((group, index) => {
        const key = String(group.key || group.code || group.id || index);
        const label = getLocalizedText(group.label || group.title || key, locale, t);
        const fields = Array.isArray(group.fields) ? group.fields : [];
        if (fields.length === 0) return null;
        return (
          <section
            key={key}
            data-testid={`review-drawer-selected-group-${key}`}
            className="rounded-control border-border bg-subtle overflow-hidden border"
          >
            <header className="border-border bg-panel text-text border-b px-3 py-2 text-xs font-semibold">
              {label}
            </header>
            <FieldRows
              fields={fields}
              record={record}
              fallbackRecord={fallbackRecord}
              locale={locale}
              t={t}
            />
          </section>
        );
      })}
    </div>
  );
}
