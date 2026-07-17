import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  readDataSourceRecord,
  readPath,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
} from './workbenchBlockUtils';

export interface EvidencePanelBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

function unwrapDynamicJsonEnvelope(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
  const record = value as Record<string, unknown>;
  const type = typeof record.type === 'string' ? record.type.toLowerCase() : '';
  if (
    (type === 'json' || type === 'jsonb') &&
    Object.prototype.hasOwnProperty.call(record, 'value')
  ) {
    return parseJsonValue(record.value);
  }
  return value;
}

function parseJsonValue(value: unknown): unknown {
  const unwrapped = unwrapDynamicJsonEnvelope(value);
  if (unwrapped !== value) return unwrapped;
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed) return '';
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function mappedValue(
  value: unknown,
  valueMap: unknown,
  locale: string,
  t: (key: string) => string,
): string | null {
  if (!valueMap || typeof valueMap !== 'object') return null;
  const key = value === undefined || value === null || value === '' ? '__empty' : String(value);
  const mapped =
    (valueMap as Record<string, unknown>)[key] ?? (valueMap as Record<string, unknown>).__default;
  if (mapped === undefined || mapped === null) return null;
  return typeof mapped === 'string' ? mapped : getLocalizedText(mapped as any, locale, t);
}

function formatValue(
  value: unknown,
  format: string | undefined,
  valueMap: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  const mapped = mappedValue(value, valueMap, locale, t);
  if (mapped !== null) return mapped;
  if (value === undefined || value === null || value === '') return '-';
  if (format === 'percent' && typeof value === 'number') {
    // Accepts both scales: a 0-1 ratio and an already-scaled 0-100 number. Without this, a
    // `format: "percent"` in a plain section was silently ignored (only the semantic-item path
    // honoured it) and the reader got a bare number with no unit — "100" meaning nothing.
    const percent = value <= 1 ? value * 100 : value;
    return `${Number.isInteger(percent) ? percent : percent.toFixed(1)}%`;
  }
  if (format === 'json') {
    return JSON.stringify(parseJsonValue(value), null, 2);
  }
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2);
  }
  return String(value);
}

function isEmptyValue(value: unknown): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

function formatSemanticValue(
  value: unknown,
  item: any,
  locale: string,
  t: (key: string) => string,
): string {
  const emptyText = getLocalizedText(item?.emptyText || '', locale, t);
  if (isEmptyValue(value)) return emptyText || '-';

  if (item?.format === 'percent' && typeof value === 'number') {
    const percent = value <= 1 ? value * 100 : value;
    return `${Number.isInteger(percent) ? percent : percent.toFixed(1)}%`;
  }

  const text = formatValue(value, item?.format, item?.valueMap, locale, t);
  return item?.unit ? `${text} ${getLocalizedText(item.unit, locale, t)}` : text;
}

function getSemanticItemValue(record: unknown, sectionValue: unknown, item: any): unknown {
  if (Object.prototype.hasOwnProperty.call(item, 'value')) {
    return item.value;
  }
  if (typeof item?.valueField === 'string') {
    return readPath(record, item.valueField);
  }
  if (typeof item?.path === 'string') {
    return readPath(sectionValue, item.path);
  }
  if (typeof item?.field === 'string') {
    return readPath(sectionValue, item.field);
  }
  return undefined;
}

function getSemanticItemTone(record: unknown, sectionValue: unknown, item: any, value: unknown) {
  const toneValue =
    typeof item?.toneField === 'string'
      ? getSemanticItemValue(record, sectionValue, { path: item.toneField })
      : undefined;
  if (toneValue !== undefined && toneValue !== null && toneValue !== '') return String(toneValue);
  if (item?.toneMap && typeof item.toneMap === 'object') {
    const mapped = item.toneMap[String(value)] ?? item.toneMap.__default;
    if (mapped) return String(mapped);
  }
  return item?.tone;
}

function renderArrayValue(
  values: unknown[],
  item: any,
  locale: string,
  t: (key: string) => string,
) {
  if (values.length === 0) {
    return (
      <span className="text-text-3 text-sm">{formatSemanticValue(values, item, locale, t)}</span>
    );
  }
  return (
    <div className="mt-2 flex flex-wrap gap-1.5">
      {values.map((entry, index) => (
        <span
          key={`${String(entry)}-${index}`}
          className="rounded-pill border border-border bg-panel px-2 py-1 text-xs text-text-2"
        >
          {formatSemanticValue(entry, { ...item, format: undefined, unit: undefined }, locale, t)}
        </span>
      ))}
    </div>
  );
}

function renderObjectValue(
  value: Record<string, unknown>,
  locale: string,
  t: (key: string) => string,
) {
  const entries = Object.entries(value).filter(([, entry]) => !isEmptyValue(entry));
  if (entries.length === 0) return <span className="text-text-3 text-sm">-</span>;
  return (
    <div className="mt-2 space-y-1.5">
      {entries.map(([key, entry]) => (
        <div key={key} className="grid grid-cols-[minmax(7rem,0.45fr)_1fr] gap-2 text-xs">
          <span className="text-text-3">{key}</span>
          <span className="text-text break-words">
            {typeof entry === 'object'
              ? formatValue(entry, 'json', undefined, locale, t)
              : formatValue(entry, undefined, undefined, locale, t)}
          </span>
        </div>
      ))}
    </div>
  );
}

function renderSemanticItemValue(
  value: unknown,
  item: any,
  locale: string,
  t: (key: string) => string,
) {
  const parsed = parseJsonValue(value);
  if (Array.isArray(parsed)) return renderArrayValue(parsed, item, locale, t);
  if (parsed && typeof parsed === 'object') {
    return renderObjectValue(parsed as Record<string, unknown>, locale, t);
  }
  return (
    <div className="text-text mt-1 text-sm leading-5 break-words">
      {formatSemanticValue(parsed, item, locale, t)}
    </div>
  );
}

function renderSemanticItems(
  record: unknown,
  sectionValue: unknown,
  items: any[],
  locale: string,
  t: (key: string) => string,
) {
  return (
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
      {items.map((item, index) => {
        const key = String(item.key || item.path || item.field || item.valueField || index);
        const label = getLocalizedText(item.label || key, locale, t);
        const helper = getLocalizedText(item.helper || '', locale, t);
        const value = getSemanticItemValue(record, sectionValue, item);
        const toneClasses = getToneClasses(getSemanticItemTone(record, sectionValue, item, value));

        return (
          <div
            key={key}
            className={`rounded-card border px-3 py-2.5 ${toneClasses.card}`}
            data-testid={`evidence-panel-item-${key}`}
          >
            <div className="flex items-center justify-between gap-3">
              <div className="text-text-2 text-xs font-medium">{label}</div>
              <span className={`h-2 w-2 rounded-full ${toneClasses.marker}`} />
            </div>
            {renderSemanticItemValue(value, item, locale, t)}
            {helper && <div className="text-text-3 mt-1.5 text-xs leading-5">{helper}</div>}
          </div>
        );
      })}
    </div>
  );
}

function getToneClasses(tone: string | undefined) {
  switch (tone) {
    case 'success':
      return {
        card: 'border-status-green bg-status-green-bg',
        value: 'text-status-green',
        marker: 'bg-status-green',
      };
    case 'warning':
      return {
        card: 'border-status-amber bg-status-amber-bg',
        value: 'text-status-amber',
        marker: 'bg-status-amber',
      };
    case 'danger':
    case 'error':
      return {
        card: 'border-status-red bg-status-red-bg',
        value: 'text-status-red',
        marker: 'bg-status-red',
      };
    case 'info':
      return {
        card: 'border-status-blue bg-status-blue-bg',
        value: 'text-status-blue',
        marker: 'bg-status-blue',
      };
    default:
      return {
        card: 'border-border bg-subtle',
        value: 'text-text',
        marker: 'bg-status-gray',
      };
  }
}

export const EvidencePanelBlockRenderer: React.FC<EvidencePanelBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;

  useRuntimeStateSubscription(runtime);
  useDataSourceSubscription(runtime, dataSourceId);

  const record =
    resolveRuntimeValue(runtime, (block as any).context) ||
    readDataSourceRecord(runtime, dataSourceId);
  const sections = Array.isArray((block as any).sections) ? (block as any).sections : [];
  const summaryCards = Array.isArray((block as any).summaryCards)
    ? (block as any).summaryCards
    : [];
  const title = getLocalizedText(block.title || (block as any).label || 'Evidence', locale, t);
  const description = getLocalizedText((block as any).description || '', locale, t);
  const emptyTitle = getLocalizedText((block as any).empty?.title || 'Select evidence', locale, t);
  const noteField = (block as any).noteField;
  const note = noteField
    ? formatValue(readPath(record, noteField), undefined, undefined, locale, t)
    : '';

  if (!record || Object.keys(record).length === 0) {
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="evidence-panel-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  return (
    <section className="rounded-card border border-border bg-panel" data-testid="evidence-panel">
      <div className="border-b border-border px-5 py-4">
        <h3 className="text-text text-base font-semibold">{title}</h3>
        {description && <p className="text-text-2 mt-1 text-sm">{description}</p>}
      </div>
      <div className="space-y-4 p-5">
        {note && note !== '-' && (
          <div
            className="rounded-card border border-status-blue bg-status-blue-bg px-4 py-3 text-sm leading-6 text-status-blue"
            data-testid="evidence-panel-note"
          >
            {note}
          </div>
        )}
        {summaryCards.length > 0 && (
          <div
            className="grid gap-3 md:grid-cols-2 xl:grid-cols-4"
            data-testid="evidence-panel-summary"
          >
            {summaryCards.map((card: any, index: number) => {
              const key = String(card.key || card.valueField || index);
              const label = getLocalizedText(card.label || key, locale, t);
              const helper = getLocalizedText(card.helper || '', locale, t);
              const value = formatValue(
                readPath(record, card.valueField),
                card.format,
                card.valueMap,
                locale,
                t,
              );
              const tone =
                formatValue(readPath(record, card.toneField), undefined, undefined, locale, t) !==
                '-'
                  ? formatValue(readPath(record, card.toneField), undefined, undefined, locale, t)
                  : card.tone;
              const toneClasses = getToneClasses(tone);

              return (
                <div
                  key={key}
                  className={`rounded-card border px-4 py-3 ${toneClasses.card}`}
                  data-testid={`evidence-panel-summary-${key}`}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="text-text-2 text-xs font-medium">{label}</div>
                    <span className={`h-2 w-2 rounded-full ${toneClasses.marker}`} />
                  </div>
                  <div className={`mt-2 text-2xl leading-8 font-semibold ${toneClasses.value}`}>
                    {value}
                  </div>
                  {helper && <div className="text-text-3 mt-1 text-xs">{helper}</div>}
                </div>
              );
            })}
          </div>
        )}
        <div className="grid gap-3 md:grid-cols-2">
          {sections.map((section: any, index: number) => {
            const key = String(section.key || section.field || index);
            const label = getLocalizedText(section.label || key, locale, t);
            const rawValue = readPath(record, section.field);
            const parsedValue = parseJsonValue(rawValue);
            const value = formatValue(rawValue, section.format, section.valueMap, locale, t);
            const isJson = section.format === 'json' || value.includes('\n');
            const semanticItems = Array.isArray(section.items) ? section.items : [];

            return (
              <div
                key={key}
                className="rounded-card border border-border bg-subtle p-3"
                data-testid={`evidence-panel-section-${key}`}
              >
                <div className="text-text-2 text-xs font-medium">{label}</div>
                {semanticItems.length > 0 ? (
                  <div className="mt-2">
                    {renderSemanticItems(record, parsedValue, semanticItems, locale, t)}
                  </div>
                ) : isJson ? (
                  <pre className="rounded-control bg-subtle text-text-2 mt-1 max-h-48 overflow-auto p-3 text-xs">
                    {value}
                  </pre>
                ) : (
                  <div className="text-text mt-1 text-sm break-words">{value}</div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
};

export default EvidencePanelBlockRenderer;
