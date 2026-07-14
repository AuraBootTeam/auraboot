import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  readDataSourceRows,
  readDataSourceState,
  readPath,
  useDataSourceSubscription,
} from './workbenchBlockUtils';

export interface ArtifactTimelineBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

function shortHash(value: unknown): string {
  const text = String(value ?? '');
  return text.length > 8 ? text.slice(0, 8) : text;
}

export const ArtifactTimelineBlockRenderer: React.FC<ArtifactTimelineBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const item = (block as any).item || {};

  useDataSourceSubscription(runtime, dataSourceId);

  const rows = readDataSourceRows(runtime, dataSourceId);
  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const title = getLocalizedText(block.title || (block as any).label || 'Artifacts', locale, t);
  const emptyTitle = getLocalizedText((block as any).empty?.title || 'No artifacts', locale, t);

  if (dataSourceState?.loading) {
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="artifact-timeline-loading"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading...'}
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="artifact-timeline-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  return (
    <section className="rounded-card border-border bg-panel border" data-testid="artifact-timeline">
      <div className="border-border border-b px-4 py-3">
        <h3 className="text-text text-sm font-semibold">{title}</h3>
      </div>
      <ol className="divide-border divide-y">
        {rows.map((row: any, index: number) => {
    const rowKey = String(readPath(row, item.keyField) ?? row.pid ?? index);
          const titleValue = readPath(row, item.titleField) ?? rowKey;
          const subtitle = readPath(row, item.subtitleField);
          const revision = readPath(row, item.revisionField);
          const status = readPath(row, item.statusField);
          const hash = readPath(row, item.hashField);
          const fileId = readPath(row, item.fileIdField);
          const downloadUrl = fileId
            ? `/api/file/download/${encodeURIComponent(String(fileId))}`
            : undefined;

          return (
            <li
              key={rowKey}
              className="grid gap-3 px-4 py-3 sm:grid-cols-[auto_1fr_auto]"
              data-testid={`artifact-timeline-item-${rowKey}`}
            >
              <div className="bg-accent mt-1 h-2.5 w-2.5 rounded-full" />
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-text font-mono text-sm font-semibold">
                    {String(titleValue)}
                  </span>
                  {revision !== undefined && revision !== null && (
                    <span className="bg-status-blue-bg text-status-blue rounded-pill px-2 py-0.5 text-xs">
                      Rev {String(revision)}
                    </span>
                  )}
                  {status && (
                    <span className="bg-status-green-bg text-status-green rounded-pill px-2 py-0.5 text-xs">
                      {String(status)}
                    </span>
                  )}
                </div>
                {subtitle && <div className="text-text-2 mt-1 text-xs">{String(subtitle)}</div>}
                {hash && (
                  <div className="text-text-2 mt-1 font-mono text-xs">{shortHash(hash)}</div>
                )}
              </div>
              {downloadUrl && (
                <a
                  href={downloadUrl}
                  data-testid={`artifact-timeline-download-${rowKey}`}
                  className="rounded-control border-border-strong text-text-2 hover:bg-subtle self-start border px-3 py-1.5 text-xs font-medium"
                >
                  {t('common.download') !== 'common.download' ? t('common.download') : 'Download'}
                </a>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
};

export default ArtifactTimelineBlockRenderer;
