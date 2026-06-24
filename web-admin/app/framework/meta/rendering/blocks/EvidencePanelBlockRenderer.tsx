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
  if ((type === 'json' || type === 'jsonb') && Object.prototype.hasOwnProperty.call(record, 'value')) {
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
  if (format === 'json') {
    return JSON.stringify(parseJsonValue(value), null, 2);
  }
  if (typeof value === 'object') {
    return JSON.stringify(value, null, 2);
  }
  return String(value);
}

function getToneClasses(tone: string | undefined) {
  switch (tone) {
    case 'success':
      return {
        card: 'border-emerald-200 bg-emerald-50/70',
        value: 'text-emerald-700',
        marker: 'bg-emerald-500',
      };
    case 'warning':
      return {
        card: 'border-amber-200 bg-amber-50/70',
        value: 'text-amber-700',
        marker: 'bg-amber-500',
      };
    case 'danger':
    case 'error':
      return {
        card: 'border-red-200 bg-red-50/70',
        value: 'text-red-700',
        marker: 'bg-red-500',
      };
    case 'info':
      return {
        card: 'border-blue-200 bg-blue-50/70',
        value: 'text-blue-700',
        marker: 'bg-blue-500',
      };
    default:
      return {
        card: 'border-border bg-subtle/40',
        value: 'text-text',
        marker: 'bg-gray-300',
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
  const note = noteField ? formatValue(readPath(record, noteField)) : '';

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
    <section className="rounded-lg border border-gray-200 bg-white" data-testid="evidence-panel">
      <div className="border-b border-gray-100 px-5 py-4">
        <h3 className="text-text text-base font-semibold">{title}</h3>
        {description && <p className="text-text-2 mt-1 text-sm">{description}</p>}
      </div>
      <div className="space-y-4 p-5">
        {note && note !== '-' && (
          <div
            className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm leading-6 text-blue-900"
            data-testid="evidence-panel-note"
          >
            {note}
          </div>
        )}
        {summaryCards.length > 0 && (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4" data-testid="evidence-panel-summary">
            {summaryCards.map((card: any, index: number) => {
              const key = String(card.key || card.valueField || index);
              const label = getLocalizedText(card.label || key, locale, t);
              const helper = getLocalizedText(card.helper || '', locale, t);
              const value = formatValue(readPath(record, card.valueField), card.format);
              const tone =
                formatValue(readPath(record, card.toneField)) !== '-'
                  ? formatValue(readPath(record, card.toneField))
                  : card.tone;
              const toneClasses = getToneClasses(tone);

              return (
                <div
                  key={key}
                  className={`rounded-lg border px-4 py-3 ${toneClasses.card}`}
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
          const value = formatValue(
            readPath(record, section.field),
            section.format,
            section.valueMap,
            locale,
            t,
          );
          const isJson = section.format === 'json' || value.includes('\n');

          return (
            <div
              key={key}
              className="rounded-lg border border-gray-100 bg-gray-50/60 p-3"
              data-testid={`evidence-panel-section-${key}`}
            >
              <div className="text-text-2 text-xs font-medium">{label}</div>
              {isJson ? (
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
