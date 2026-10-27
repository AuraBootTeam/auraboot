import { resolveRowActionModel } from './rowActionModel';
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
 *   "field": "draftRows",        // optional form array for local card edits
 *   "fields": [{ "field": "quantity", "component": "SmartInput" }],
 *   "fieldsLayout": { "type": "grid", "cols": 12 }, // opt-in, fields use layout.colSpan
 *   "columns": "grid-cols-2",      // optional override; default: responsive 1/2/3
 *   "groupByField": "sourcePid",   // optional stable identity; labels never merge groups
 *   "groupTitleField": "sourceLabel",
 *   "cardActions": [
 *     { "code": "install", "label": "Install", "action": { "type": "command", "command": "template:install" } }
 *   ]
 * }
 */

import { initializeSelectedRows, indexSelectionRows, positiveRowValue, type RowSelectionRule } from '../../runtime/expression/row-selection';
import { readOwnPath } from '../../runtime/expression/row-projection';
import React, { useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router';
import type { BlockConfig, CardImageViewport, FieldConfig, LayoutConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';
import { useToastContext } from '~/contexts/ToastContext';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { GridLayoutRenderer } from '~/framework/meta/rendering/layout/GridLayoutRenderer';
import { resolveErrorLocale, rootT } from '~/error/root-error-view';
import {
  readDataSourceState,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
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

/** Invalid or unknown viewport entries retain the existing image behavior. */
function imageViewport(config: unknown, url: string): CardImageViewport | undefined {
  if (!config || typeof config !== 'object' || !Object.hasOwn(config, url)) return;
  const value = (config as Record<string, unknown>)[url] as Partial<CardImageViewport> | null;
  if (!value || !Array.isArray(value.viewBox) || value.viewBox.length !== 4) return;
  const { width, height, viewBox } = value;
  if (![width, height, ...viewBox].every(n => typeof n === 'number' && Number.isFinite(n))) return;
  if (width! <= 0 || height! <= 0) return;
  const [x, y, w, h] = viewBox;
  if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width! || y + h > height!) return;
  return value as CardImageViewport;
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
  const badgeDictCode: string | undefined = cfg.badgeDictCode;
  // Optional image per card (URL string field, e.g. a cover/asset column).
  // Missing or broken URLs degrade to a neutral placeholder tile, never a
  // broken-image icon.
  const imageField: string | undefined = cfg.imageField;
  const cardActions: Array<{ code: string; label?: any; variant?: string; action?: any }> =
    Array.isArray(cfg.cardActions) ? cfg.cardActions : [];
  const autoFitColumns = cfg.autoFitColumns === true;
  const columnsClass: string = autoFitColumns ? '' : cfg.columns || 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3';
  const columnsStyle = autoFitColumns
    ? { gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 20rem), 1fr))' }
    : undefined;
  // A declared form field stores draft card rows; data-source refreshes never replace edits.
  const formField = typeof cfg.field === 'string' && cfg.field.trim() ? cfg.field : undefined;
  const rowSelection = cfg.rowSelection;
  if (rowSelection && !formField) throw new Error('Card row selection requires a form array');
  const selectionRule: RowSelectionRule | undefined = rowSelection ? {identityField:readOwnPath(rowSelection,'identityField') as string,selectedField:readOwnPath(rowSelection,'selectedField') as string,positiveField:readOwnPath(rowSelection,'positiveField') as string} : undefined;
  const cardFields: FieldConfig[] = Array.isArray(cfg.fields) ? cfg.fields : [];
  const fieldsLayout: LayoutConfig | undefined = cfg.fieldsLayout?.type === 'grid' ? cfg.fieldsLayout : undefined;
  let nextColumn = 0;
  const fieldBlocks = fieldsLayout ? cardFields.map(field => {
    const cols = fieldsLayout.cols ?? 12;
    const colSpan = field.layout?.colSpan ?? field.colSpan ?? Math.max(1, Math.floor(cols / 2));
    const col = field.layout?.col ?? (nextColumn + colSpan > cols ? 0 : nextColumn);
    nextColumn = (col + colSpan) % cols;
    return { ...field, id: field.field, layout: { ...field.layout, col, colSpan } };
  }) : [];
  const stateManager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();
  useRuntimeStateSubscription(runtime);

  // Subscribe to data-source changes so the component re-renders on updates
  useDataSourceSubscription(runtime, dataSourceId);

  const dataSourceState = readDataSourceState(runtime, dataSourceId);

  // Action dispatch — mirror ToolbarBlockRenderer pattern
  const navigate = useNavigate();
  const { token } = useAuth();
  const { loaded: badgeDictLoaded, getDictLabel } = useDictCache({
    dictCodes: badgeDictCode ? [badgeDictCode] : [],
    token: token || undefined,
  });
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const tableName = resolveRowActionModel(block, runtime);
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

  const rawData = dataSourceId
    ? (() => {
        try {
          return dataSourceManager.getData(dataSourceId);
        } catch {
          return undefined;
        }
      })()
    : undefined;
  const formRows = formField ? stateManager?.getFieldValue(scopeId, formField) : undefined;
  const freshRows = normalizeRows(rawData);
  const initialRows = rowSelection ? initializeSelectedRows(freshRows,rowSelection) : freshRows;
  const rows = Array.isArray(formRows) ? formRows : initialRows;
  const currentSelectionRows = selectionRule ? indexSelectionRows(freshRows,selectionRule) : undefined;
  if (selectionRule) {
    for (const row of indexSelectionRows(rows,selectionRule).values()) {
      if (typeof readOwnPath(row,selectionRule.selectedField)!=='boolean') throw new Error('Expected explicit boolean row selection');
    }
  }
  useEffect(() => {
    if (formField && formRows === undefined && dataSourceState?.data !== undefined
        && !dataSourceState.loading && !dataSourceState.error) {
      stateManager.updateField(scopeId, formField, rowSelection ? initializeSelectedRows(normalizeRows(rawData),rowSelection) : normalizeRows(rawData));
    }
  }, [formField, formRows, dataSourceState, rawData, scopeId, stateManager, rowSelection]);

  // ── Error state ────────────────────────────────────────────────────────────
  if (dataSourceState?.error) {
    const sourceError = dataSourceState.error as Error & {status?:unknown;statusCode?:unknown;code?:unknown};
    const status = Number(sourceError.status ?? sourceError.statusCode ?? sourceError.code);
    const message =
      status === 403
        ? rootT('forbidden', resolveErrorLocale(locale))
        : dataSourceState.error instanceof Error
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

  // ── Empty state ────────────────────────────────────────────────────────────
  if (rows.length === 0) {
    const emptyMsg = cfg.empty?.title ? getLocalizedText(cfg.empty.title, locale, t) :
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
        <p className="text-text font-medium">{emptyMsg}</p>
        {cfg.empty?.description && <p className="mt-1">{getLocalizedText(cfg.empty.description, locale, t)}</p>}
      </div>
    );
  }

  const selectionHint = selectionRule && rowSelection.emptyText && !rows.some(row=>readOwnPath(row,selectionRule.selectedField)===true)
    ? <p role="status" className="text-text-2 col-span-full text-sm">{getLocalizedText(rowSelection.emptyText,locale,t)}</p> : null;
  const renderCard = (row: any, idx: number) => {
        const rowKey = selectionRule ? readOwnPath(row,selectionRule.identityField) as string : row.pid || String(idx);
        const title = getLocalizedText(row[titleField], locale, t);
        const description = descriptionField ? getLocalizedText(row[descriptionField], locale, t) : undefined;
        const imageUrl = imageField && row[imageField] ? String(row[imageField]) : undefined;
        const viewport = imageUrl ? imageViewport(cfg.imageViewports, imageUrl) : undefined;
        const category = categoryField ? getLocalizedText(row[categoryField], locale, t) : undefined;
        const rawBadge = badgeField ? row[badgeField] : undefined;
        const badge =
          rawBadge == null
            ? undefined
            : badgeDictCode
              ? badgeDictLoaded
                ? getDictLabel(badgeDictCode, String(rawBadge))
                : undefined
              : getLocalizedText(rawBadge, locale, t);

        const currentSelectionRow = selectionRule ? currentSelectionRows?.get(readOwnPath(row,selectionRule.identityField) as string) : undefined;
        const rowSelectable = !selectionRule || Boolean(currentSelectionRow && positiveRowValue(readOwnPath(currentSelectionRow,selectionRule.positiveField)));
        const rowSelected = selectionRule ? readOwnPath(row,selectionRule.selectedField) === true : false;
        const renderField = (field: FieldConfig) => (
          <FieldRenderer
            key={field.field}
            runtime={runtime}
            field={{ ...field,
              field: `${formField || block.id}.${idx}.${field.field}`,
              readOnly: !formField || !rowSelectable || field.field===selectionRule?.positiveField || Boolean(field.valueWhen) || field.readOnly,
            }}
            value={field.valueWhen ? runtime.getEvaluator().evaluate(field.valueWhen, { ...runtime.getContext(), row } as any) : (currentSelectionRow && (field.readOnly || field.field===selectionRule?.positiveField) ? readOwnPath(currentSelectionRow,field.field) : row[field.field])}
            onChange={(value) => {
              if (!formField || !rowSelectable || field.field===selectionRule?.positiveField || field.readOnly || field.valueWhen) return;
              stateManager.updateField(scopeId, formField,
                rows.map((current, index) => index === idx ? { ...current, [field.field]: value } : current));
            }}
          />
        );
        return (
          <div
            key={rowKey}
            data-testid="card-grid-card"
            className="rounded-card border-border bg-panel flex flex-col border p-4 shadow-sm"
          >
            {selectionRule && (
              <label className="mb-3 flex items-center gap-2 text-sm">
                <input type="checkbox" aria-label={`${getLocalizedText(rowSelection.label || 'Select',locale,t)} ${title}`}
                  checked={rowSelected} disabled={!rowSelectable && !rowSelected}
                  onChange={event=>{
                    if (!formField || (event.target.checked && !rowSelectable)) return;
                    stateManager.updateField(scopeId,formField,rows.map((current,index)=>index===idx ? {...current,[selectionRule.selectedField]:event.target.checked} : current));
                  }}/>
                {getLocalizedText(rowSelection.label || 'Select',locale,t)}
              </label>
            )}
            {/* Category tag */}
            {category && (
              <span className="text-text-2 mb-2 text-xs font-medium">{category}</span>
            )}

            {/* Image tile (imageField) */}
            {imageField && (
              <div
                data-testid="card-grid-image"
                className="bg-subtle border-border mb-3 flex h-24 items-center justify-center overflow-hidden rounded-[var(--radius-card)] border"
              >
                {imageUrl && viewport ? (
                  <svg
                    role="img"
                    aria-label={title || ''}
                    viewBox={viewport.viewBox.join(' ')}
                    preserveAspectRatio="xMidYMid meet"
                    className="h-full w-24 max-w-full overflow-hidden p-1.5"
                    data-testid="card-grid-image-viewport"
                  >
                    <image
                      href={imageUrl}
                      width={viewport.width}
                      height={viewport.height}
                      onError={e => { e.currentTarget.style.visibility = 'hidden'; }}
                    />
                  </svg>
                ) : imageUrl ? (
                  <img
                    src={imageUrl}
                    alt={title || ''}
                    className="h-full w-full object-contain p-1.5"
                    onError={(e) => {
                      e.currentTarget.style.visibility = 'hidden';
                    }}
                  />
                ) : null}
              </div>
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

            {/* Declared fields; without a form array they are display-only. */}
            {cardFields.length > 0 && (
              <fieldset aria-label={title} className={fieldsLayout ? 'mb-3' : 'mb-3 space-y-3'}>
                {fieldsLayout ? <GridLayoutRenderer layout={fieldsLayout}
                  blocks={fieldBlocks} renderBlock={renderField} /> : cardFields.map(renderField)}
              </fieldset>
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
  };

  // Group by stored identity while retaining original indexes for draft edits and actions.
  const groupByField = typeof cfg.groupByField === 'string' ? cfg.groupByField : undefined;
  if (groupByField) {
    const groups = new Map<string, { title: string; entries: Array<{ row: any; index: number }> }>();
    rows.forEach((row: any, index: number) => {
      const identity = row[groupByField];
      const key = (typeof identity === 'string' && identity.trim()) || typeof identity === 'number'
        ? `${typeof identity}:${identity}` : `missing:${index}`;
      const group: { title: string; entries: Array<{ row: any; index: number }> } = groups.get(key) || {
        title: getLocalizedText(row[cfg.groupTitleField || groupByField], locale, t), entries: [],
      };
      group.entries.push({ row, index });
      groups.set(key, group);
    });
    return (
      <div data-testid="card-grid-block" className="space-y-6">
        {selectionHint}
        {Array.from(groups.entries()).map(([key, group], index) => (
          <section key={key} data-testid="card-grid-group"
            aria-labelledby={group.title ? `${block.id}-group-${index}` : undefined}>
            {group.title && <h2 id={`${block.id}-group-${index}`}
              className="text-text mb-3 text-base font-semibold">{group.title}</h2>}
            <div className={`grid gap-4 ${columnsClass}`} style={columnsStyle}>
              {group.entries.map(({ row, index: originalIndex }) => renderCard(row, originalIndex))}
            </div>
          </section>
        ))}
      </div>
    );
  }
  return <div data-testid="card-grid-block" className={`grid gap-4 ${columnsClass}`} style={columnsStyle}>
    {selectionHint}
    {rows.map(renderCard)}
  </div>;
};

export default CardGridBlockRenderer;
