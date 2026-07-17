/**
 * StatCardBlockRenderer - Renders a single key-metric card.
 *
 * DSL config:
 *   {
 *     "blockType": "stat-card",
 *     "title": "Orders today",
 *     "statCard": {
 *       "value": 42,
 *       "unit": "orders",
 *       "trend": "+12%",
 *       "trendDirection": "up"
 *     }
 *   }
 *
 * Optional dataSource integration: when `block.dataSource` is set, reads the
 * first row from the named data source and picks `valueField` from it. For
 * runtime pages without a configured data source, the inline `statCard.value`
 * is used so the block still renders.
 */

import React, { useEffect } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface StatCardBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const StatCardBlockRenderer: React.FC<StatCardBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const cfg = { ...((block as any).props || {}), ...((block as any).statCard || {}) };
  const title = block.title ? getLocalizedText(block.title, locale, t) : '';
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const refreshInterval = Number((block as any).refreshInterval ?? cfg.refreshInterval ?? 0);

  useEffect(() => {
    if (!dataSourceId || !Number.isFinite(refreshInterval) || refreshInterval <= 0) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      void runtime.getDataSourceManager().reload(dataSourceId);
    }, refreshInterval);
    return () => window.clearInterval(timer);
  }, [dataSourceId, refreshInterval, runtime]);

  // Try to pull value from data source (first row, valueField column). Fallback
  // to the inline value declared in `statCard.value`.
  const value = (() => {
    if (dataSourceId) {
      try {
        const data: any = runtime.getDataSourceManager().getData(dataSourceId);
        const rows = Array.isArray(data) ? data : data?.records;
        if (Array.isArray(rows) && rows.length > 0) {
          const field = cfg.valueField || 'value';
          const v = rows[0]?.[field];
          if (v !== undefined && v !== null) return v;
        }
      } catch {
        // swallow and fall through to inline value
      }
    }
    return cfg.value ?? cfg.number ?? '—';
  })();

  const unit = cfg.unit || cfg.suffix || '';
  const trend: string | undefined = cfg.trend ?? cfg.change ?? cfg.changeField;
  const trendDirection: 'up' | 'down' | 'flat' = cfg.trendDirection || 'flat';
  const trendClass =
    trendDirection === 'up'
      ? 'text-status-green'
      : trendDirection === 'down'
        ? 'text-status-red'
        : 'text-status-gray';

  return (
    <div
      className={`stat-card-block rounded-card border-border bg-panel border p-4 shadow-card ${block.className || ''}`}
      data-testid="stat-card-block"
      data-block-type="stat-card"
    >
      {title && (
        <div className="text-text-2 text-xs font-medium tracking-wider uppercase">{title}</div>
      )}
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-text text-2xl font-semibold" data-testid="stat-card-value">
          {String(value)}
        </span>
        {unit && <span className="text-text-2 text-sm">{unit}</span>}
      </div>
      {trend && (
        <div className={`mt-1 text-xs ${trendClass}`} data-testid="stat-card-trend">
          {trend}
        </div>
      )}
    </div>
  );
};

export default StatCardBlockRenderer;
