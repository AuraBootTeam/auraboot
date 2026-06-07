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

function parseJsonValue(value: unknown): unknown {
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

function formatValue(value: unknown, format?: string): string {
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
  const emptyTitle = getLocalizedText(
    (block as any).empty?.title || 'Select evidence',
    locale,
    t,
  );

  if (!record || Object.keys(record).length === 0) {
    return (
      <div
        className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500"
        data-testid="evidence-panel-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  return (
    <section className="rounded-lg border border-gray-200 bg-white" data-testid="evidence-panel">
      <div className="border-b border-gray-100 px-4 py-3">
        <h3 className="text-sm font-semibold text-gray-900">{title}</h3>
      </div>
      <div className="space-y-3 p-4">
        {sections.map((section: any, index: number) => {
          const key = String(section.key || section.field || index);
          const label = getLocalizedText(section.label || key, locale, t);
          const value = formatValue(readPath(record, section.field), section.format);
          const isJson = section.format === 'json' || value.includes('\n');

          return (
            <div key={key} data-testid={`evidence-panel-section-${key}`}>
              <div className="text-xs font-medium uppercase tracking-wide text-gray-500">{label}</div>
              {isJson ? (
                <pre className="mt-1 max-h-48 overflow-auto rounded-md bg-gray-50 p-3 text-xs text-gray-700">
                  {value}
                </pre>
              ) : (
                <div className="mt-1 break-words text-sm text-gray-800">{value}</div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
};

export default EvidencePanelBlockRenderer;
