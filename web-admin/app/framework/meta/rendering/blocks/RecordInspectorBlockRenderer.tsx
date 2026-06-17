import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { BlockRenderer } from '~/framework/meta/rendering/BlockRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readPath, resolveRuntimeValue, useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface RecordInspectorBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const RecordInspectorBlockRenderer: React.FC<RecordInspectorBlockRendererProps> = ({
  block,
  runtime,
}) => {
  useRuntimeStateSubscription(runtime);
  const contextRecord = resolveRuntimeValue(runtime, (block as any).context);
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const empty = (block as any).empty || {};
  const fields = Array.isArray((block as any).fields) ? (block as any).fields : [];
  const childBlocks = Array.isArray((block as any).blocks) ? (block as any).blocks : [];

  if (!contextRecord) {
    const emptyTitle = getLocalizedText(empty.title || 'Select a record', locale, t);
    return (
      <aside
        className="rounded-card border-border bg-panel text-text-2 min-h-48 border p-4 text-sm"
        data-testid="record-inspector-empty"
      >
        {emptyTitle}
      </aside>
    );
  }

  return (
    <aside className="rounded-card border-border bg-panel border" data-testid="record-inspector">
      {fields.length > 0 && (
        <dl className="grid gap-3 p-3 sm:grid-cols-2">
          {fields.map((field: any) => {
            const fieldCode = String(field.field || field.path || '');
            const label = getLocalizedText(field.label || fieldCode, locale, t);
            const value = readPath(contextRecord, field.path || field.field);
            return (
              <div key={fieldCode} className={field.span === 2 ? 'sm:col-span-2' : undefined}>
                <dt className="text-text-2 text-xs font-medium">{label}</dt>
                <dd className="text-text mt-1 min-h-5 text-sm break-words">
                  {value === null || value === undefined || value === '' ? '-' : String(value)}
                </dd>
              </div>
            );
          })}
        </dl>
      )}
      {childBlocks.length > 0 && (
        <div className="space-y-3 p-3">
          {childBlocks.map((child: BlockConfig) => (
            <BlockRenderer key={child.id} block={child} runtime={runtime} areaId={block.id} />
          ))}
        </div>
      )}
    </aside>
  );
};

export default RecordInspectorBlockRenderer;
