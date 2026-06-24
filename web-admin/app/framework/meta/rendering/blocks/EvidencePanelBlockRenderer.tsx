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
  const title = getLocalizedText(block.title || (block as any).label || 'Evidence', locale, t);
  const emptyTitle = getLocalizedText((block as any).empty?.title || 'Select evidence', locale, t);

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
    <section className="rounded-card border-border bg-panel border" data-testid="evidence-panel">
      <div className="border-b border-gray-100 px-4 py-3">
        <h3 className="text-text text-sm font-semibold">{title}</h3>
      </div>
      <div className="space-y-3 p-4">
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
            <div key={key} data-testid={`evidence-panel-section-${key}`}>
              <div className="text-text-2 text-xs font-medium tracking-wide uppercase">{label}</div>
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
    </section>
  );
};

export default EvidencePanelBlockRenderer;
