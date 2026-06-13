import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface SelectionInfoBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

function readStateValue(runtime: SchemaRuntime, key: string): unknown {
  return (runtime.getContext().state as Record<string, unknown> | undefined)?.[key];
}

function summarizeSelection(value: unknown): { count: number; label: string } {
  if (Array.isArray(value)) {
    return {
      count: value.length,
      label: value
        .map((item) => {
          if (item && typeof item === 'object') {
            const row = item as Record<string, unknown>;
            return String(row.title ?? row.name ?? row.pid ?? row.id ?? '');
          }
          return String(item ?? '');
        })
        .filter(Boolean)
        .join(', '),
    };
  }

  if (value && typeof value === 'object') {
    const row = value as Record<string, unknown>;
    return {
      count: 1,
      label: String(row.title ?? row.name ?? row.pid ?? row.id ?? ''),
    };
  }

  if (value !== undefined && value !== null && value !== '') {
    return { count: 1, label: String(value) };
  }

  return { count: 0, label: '' };
}

export const SelectionInfoBlockRenderer: React.FC<SelectionInfoBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const stateKey =
    (block.selection as Record<string, unknown> | undefined)?.bind ||
    (block as Record<string, unknown>).bind ||
    'selectedRows';
  useRuntimeStateSubscription(runtime);

  const selection = readStateValue(runtime, String(stateKey));
  const summary = summarizeSelection(selection);
  const fallbackTitle = t('common.selection') !== 'common.selection' ? t('common.selection') : 'Selection';
  const title = block.title
    ? getLocalizedText(block.title, locale, t)
    : fallbackTitle;

  return (
    <div
      className={`selection-info-block rounded-md border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-900 ${block.className || ''}`}
      data-testid="selection-info-block"
      data-block-type="selection-info"
    >
      <div className="font-medium" data-testid="selection-info-title">
        {title}
      </div>
      <div className="mt-1" data-testid="selection-info-count">
        {summary.count}
      </div>
      {summary.label && (
        <div className="mt-1 truncate text-blue-700" data-testid="selection-info-label">
          {summary.label}
        </div>
      )}
    </div>
  );
};

export default SelectionInfoBlockRenderer;
