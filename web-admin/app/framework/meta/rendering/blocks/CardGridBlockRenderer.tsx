/**
 * CardGridBlockRenderer - Generic responsive card-grid block.
 *
 * DSL config shape (all optional except dataSource):
 * {
 *   "blockType": "card-grid",
 *   "dataSource": "<id>",
 *   "titleField": "name",          // default: "name"
 *   "descriptionField": "description",
 *   "categoryField": "category",
 *   "iconField": "icon",           // value = lucide icon name (renders nothing if unresolvable)
 *   "badgeField": "badge",
 *   "columns": "grid-cols-2",      // optional override; default: responsive 1/2/3
 *   "cardActions": [
 *     { "code": "install", "label": "Install", "action": { "type": "command", "command": "template:install" } }
 *   ]
 * }
 */

import React, { useCallback } from 'react';
import { useNavigate } from 'react-router';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';
import { useToastContext } from '~/contexts/ToastContext';
import {
  readDataSourceState,
  useDataSourceSubscription,
} from './workbenchBlockUtils';

export interface CardGridBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

/**
 * Normalize data-source payload to a plain array.
 * Handles: raw array, { records: [] }, { list: [] }.
 */
function normalizeRows(data: unknown): any[] {
  if (Array.isArray(data)) return data;
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>;
    if (Array.isArray(d.records)) return d.records as any[];
    if (Array.isArray(d.list)) return d.list as any[];
  }
  return [];
}

export const CardGridBlockRenderer: React.FC<CardGridBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // Bare-path config — same pattern as StatCardBlockRenderer
  const cfg = { ...((block as any).props || {}), ...(block as any) };

  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const titleField: string = cfg.titleField || 'name';
  const descriptionField: string | undefined = cfg.descriptionField;
  const categoryField: string | undefined = cfg.categoryField;
  const badgeField: string | undefined = cfg.badgeField;
  const cardActions: Array<{ code: string; label?: any; variant?: string; action?: any }> =
    Array.isArray(cfg.cardActions) ? cfg.cardActions : [];
  const columnsClass: string = cfg.columns || 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3';

  // Subscribe to data-source changes so the component re-renders on updates
  useDataSourceSubscription(runtime, dataSourceId);

  const dataSourceState = readDataSourceState(runtime, dataSourceId);

  // Action dispatch — mirror ToolbarBlockRenderer pattern
  const navigate = useNavigate();
  const { token } = useAuth();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const schema = runtime.getSchema();
  const tableName = (schema as any).modelCode || (schema as any).id || '';
  const dataSourceManager = runtime.getDataSourceManager();

  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        default:
          showInfoToast(message);
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {},
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  // ── Error state ────────────────────────────────────────────────────────────
  if (dataSourceState?.error) {
    const message =
      dataSourceState.error instanceof Error
        ? dataSourceState.error.message
        : String(dataSourceState.error);
    return (
      <div
        role="alert"
        data-testid="card-grid-error"
        className="rounded-control bg-status-red-bg text-status-red border border-status-red p-3 text-sm"
      >
        {message || t('common.loadError')}
      </div>
    );
  }

  // ── Loading state ──────────────────────────────────────────────────────────
  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid="card-grid-loading"
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
      >
        {t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading...'}
      </div>
    );
  }

  // ── Data ───────────────────────────────────────────────────────────────────
  const rawData = dataSourceId
    ? (() => {
        try {
          return runtime.getDataSourceManager().getData(dataSourceId);
        } catch {
          return undefined;
        }
      })()
    : undefined;

  const rows = normalizeRows(rawData);

  // ── Empty state ────────────────────────────────────────────────────────────
  if (rows.length === 0) {
    const emptyMsg =
      t('template.gallery.empty') !== 'template.gallery.empty'
        ? t('template.gallery.empty')
        : t('common.noData') !== 'common.noData'
          ? t('common.noData')
          : 'No data';
    return (
      <div
        data-testid="card-grid-empty"
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
      >
        {emptyMsg}
      </div>
    );
  }

  // ── Card grid ──────────────────────────────────────────────────────────────
  return (
    <div
      data-testid="card-grid-block"
      className={`grid gap-4 ${columnsClass}`}
    >
      {rows.map((row: any, idx: number) => {
    const rowKey = row.pid || String(idx);
        const title = getLocalizedText(row[titleField], locale, t);
        const description = descriptionField ? getLocalizedText(row[descriptionField], locale, t) : undefined;
        const category = categoryField ? getLocalizedText(row[categoryField], locale, t) : undefined;
        const badge = badgeField ? getLocalizedText(row[badgeField], locale, t) : undefined;

        return (
          <div
            key={rowKey}
            data-testid="card-grid-card"
            className="rounded-card border-border bg-panel flex flex-col border p-4 shadow-sm"
          >
            {/* Category tag */}
            {category && (
              <span className="text-text-2 mb-2 text-xs font-medium">{category}</span>
            )}

            {/* Title */}
            <h3 className="text-text mb-1 text-base font-semibold">{title}</h3>

            {/* Description */}
            {description && (
              <p className="text-text-2 mb-3 line-clamp-2 text-sm">{description}</p>
            )}

            {/* Badge */}
            {badge && (
              <span className="rounded-pill border-border mb-3 self-start border px-2 py-0.5 text-xs">
                {badge}
              </span>
            )}

            {/* Actions */}
            {cardActions.length > 0 && (
              <div className="mt-auto flex flex-wrap gap-2 pt-2">
                {cardActions.map((action) => {
                  const label = getLocalizedText(action.label || action.code, locale, t);
                  return (
                    <button
                      key={action.code}
                      type="button"
                      data-testid={`card-grid-action-${action.code}`}
                      onClick={() =>
                        handleAction(
                          {
                            code: action.code,
                            label: action.label,
                            variant: action.variant as any,
                            action: action.action,
                          },
                          row,
                        )
                      }
                      className={`rounded-control inline-flex h-8 items-center px-3 text-sm font-medium focus:outline-none focus:ring-[0_0_0_3px] focus:ring-accent/30 ${
                        action.variant === 'primary'
                          ? 'bg-accent hover:bg-accent-hover text-white'
                          : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
                      }`}
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default CardGridBlockRenderer;
