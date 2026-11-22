import { resolveRowActionModel } from './rowActionModel';
import { hasButtonPermissions, isButtonDisabled } from '~/framework/meta/utils/buttonPermissions';
import { useActionTriggerFocus } from '~/hooks/useActionTriggerFocus';
import { reconcileDraftRows } from '../../runtime/state/reconcile-draft-rows';
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
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig, CardImageViewport, FieldConfig, LayoutConfig } from '~/framework/meta/schemas/types';
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
  const cardActions: ButtonConfig[] =
    Array.isArray(cfg.cardActions) ? cfg.cardActions : [];
  const compact = cfg.compact === true;
  const initialOpenCount = cfg.groupsDefaultOpenCount;
  if (initialOpenCount !== undefined && (!Number.isInteger(initialOpenCount) || initialOpenCount < 0)) {
    throw new Error('Card group initial open count must be a nonnegative integer');
  }
  const blockHeading = block.title ? <h2 className="col-span-full text-text text-base font-semibold">{getLocalizedText(block.title, locale, t)}</h2> : null;
  const autoFitColumns = cfg.autoFitColumns === true;
  const autoFillColumns = cfg.autoFillColumns === true;
  const minCardWidth = Number.isFinite(cfg.minCardWidth) && cfg.minCardWidth >= 180 && cfg.minCardWidth <= 640
    ? `${cfg.minCardWidth}px` : '20rem';
  const columnsClass: string = autoFitColumns || autoFillColumns ? '' : cfg.columns || 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3';
  const columnsStyle = autoFitColumns || autoFillColumns
    ? { gridTemplateColumns: `repeat(${autoFillColumns ? 'auto-fill' : 'auto-fit'}, minmax(min(100%, ${minCardWidth}), 1fr))` }
    : undefined;
  // A declared form field stores draft card rows; data-source refreshes never replace edits.
  const formField = typeof cfg.field === 'string' && cfg.field.trim() ? cfg.field : undefined;
  const parentConfig = cfg.groupRows;
  if (parentConfig && (!cfg.groupByField || !parentConfig.field || !parentConfig.identityField || !parentConfig.titleField))
    throw new Error('Joined card groups require an explicit parent form array and group identity');
  const [groupSearch, setGroupSearch] = useState<Record<string, string>>({});
  const rowSelection = cfg.rowSelection;
  if (rowSelection && !formField) throw new Error('Card row selection requires a form array');
  const selectionRule: RowSelectionRule | undefined = rowSelection ? {identityField:readOwnPath(rowSelection,'identityField') as string,selectedField:readOwnPath(rowSelection,'selectedField') as string,positiveField:readOwnPath(rowSelection,'positiveField') as string} : undefined;
  const cardFields: FieldConfig[] = Array.isArray(cfg.fields) ? cfg.fields : [];
  const fieldsLayout: LayoutConfig | undefined = cfg.fieldsLayout?.type === 'grid' ? cfg.fieldsLayout : undefined;
  const stateManager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();
  useRuntimeStateSubscription(runtime);

  // Subscribe to data-source changes so the component re-renders on updates
  useDataSourceSubscription(runtime, dataSourceId);
  useDataSourceSubscription(runtime, parentConfig?.dataSource);

  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const parentState = readDataSourceState(runtime, parentConfig?.dataSource);

  // Action dispatch — mirror ToolbarBlockRenderer pattern
  const navigate = useNavigate();
  const { token, hasPermission } = useAuth();
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

  const { handleAction, loading } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context,
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const actionInFlight = useRef(false);
  const actionBusy = Boolean(loading) || runningAction !== null;
  const captureTriggerFocus = useActionTriggerFocus(actionBusy);
  const actionContext = (row: any) => ({ ...runtime.getContext(), record: row, row });
  const actionAllowed = (action: ButtonConfig, row: any) => hasButtonPermissions(action, hasPermission)
    && (!action.visibleWhen || runtime.getEvaluator().evaluateCondition(action.visibleWhen, actionContext(row)));
  const actionDisabled = (action: ButtonConfig, row: any) => isButtonDisabled(action,
    expression => runtime.getEvaluator().evaluateCondition(expression, actionContext(row)), actionBusy);
  const runCardAction = async (action: ButtonConfig, row: any, index: number) => {
    if (actionInFlight.current || !actionAllowed(action, row) || actionDisabled(action, row)) return;
    captureTriggerFocus();
    actionInFlight.current = true;
    setRunningAction(`${index}:${action.code}`);
    try { await handleAction(action, row); }
    finally { actionInFlight.current = false; setRunningAction(null); }
  };

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
  const storedParents = parentConfig ? stateManager?.getFieldValue(scopeId, parentConfig.field) : undefined;
  if (parentConfig && !parentConfig.dataSource && !Array.isArray(storedParents))
    throw new Error('Joined card parent form rows must be an array');
  const rawParents = parentConfig?.dataSource ? dataSourceManager.getData(parentConfig.dataSource) : storedParents;
  const parentsReadOnly = Boolean(parentConfig && (parentConfig.fields || []).every((field: FieldConfig) => field.readOnly || field.valueWhen));
  const parentRows = parentsReadOnly ? normalizeRows(rawParents) : Array.isArray(storedParents) ? storedParents : normalizeRows(rawParents);
  const previousParents = useRef<unknown>(undefined);
  const previousRows = useRef<unknown>(undefined);
  const preserveDraft = (condition: string | undefined) => (row: Record<string, unknown>) => !condition
    || runtime.getEvaluator().evaluateCondition(condition, actionContext(row));
  useEffect(() => {
    if (!parentConfig?.dataSource || parentState?.data == null || parentState.loading || parentState.error) return;
    if (storedParents === undefined) {
      previousParents.current = rawParents;
      stateManager.updateField(scopeId, parentConfig.field, normalizeRows(rawParents));
    } else if ((parentConfig.draftRows || parentsReadOnly) && previousParents.current !== rawParents) {
      if (!Array.isArray(storedParents)) throw new Error('Parent draft rows must be an array');
      previousParents.current = rawParents;
      stateManager.updateField(scopeId, parentConfig.field, parentsReadOnly ? normalizeRows(rawParents) : reconcileDraftRows(normalizeRows(rawParents), storedParents,
        parentConfig.draftRows!, preserveDraft(parentConfig.draftRows!.preserveWhen)));
    }
  }, [parentConfig, parentsReadOnly, storedParents, parentState, rawParents, scopeId, stateManager]);
  const currentSelectionRows = selectionRule ? indexSelectionRows(freshRows,selectionRule) : undefined;
  if (selectionRule) {
    for (const row of indexSelectionRows(rows,selectionRule).values()) {
      if (typeof readOwnPath(row,selectionRule.selectedField)!=='boolean') throw new Error('Expected explicit boolean row selection');
    }
  }
  useEffect(() => {
    if (formField && formRows === undefined && dataSourceState?.data !== undefined
        && !dataSourceState.loading && !dataSourceState.error) {
      previousRows.current = rawData;
      stateManager.updateField(scopeId, formField, rowSelection ? initializeSelectedRows(normalizeRows(rawData),rowSelection) : normalizeRows(rawData));
    } else if (formField && cfg.draftRows && dataSourceState?.data !== undefined
        && !dataSourceState.loading && !dataSourceState.error && previousRows.current !== rawData) {
      if (!Array.isArray(formRows)) throw new Error('Card draft rows must be an array');
      previousRows.current = rawData;
      stateManager.updateField(scopeId, formField, reconcileDraftRows(normalizeRows(rawData), formRows,
        cfg.draftRows, preserveDraft(cfg.draftRows.preserveWhen)));
    }
  }, [formField, formRows, dataSourceState, rawData, scopeId, stateManager, rowSelection, cfg.draftRows]);

  // ── Error state ────────────────────────────────────────────────────────────
  if (dataSourceState?.error || parentState?.error) {
    const sourceError = (dataSourceState?.error || parentState?.error) as Error & {status?:unknown;statusCode?:unknown;code?:unknown};
    const status = Number(sourceError.status ?? sourceError.statusCode ?? sourceError.code);
    const stateConfig = cfg.states?.[String(status)] ?? cfg.states?.error;
    const failedSourceId = parentState?.error ? parentConfig?.dataSource : dataSourceId;
    const message =
      status === 403
        ? rootT('forbidden', resolveErrorLocale(locale))
        : sourceError instanceof Error
        ? sourceError.message
        : String(sourceError);
    return (
      <div
        role="alert"
        data-testid="card-grid-error"
        className="rounded-control bg-status-red-bg text-status-red border border-status-red p-3 text-sm"
      >
        {stateConfig ? <>
          <p className="font-semibold">{getLocalizedText(stateConfig.title, locale, t)}</p>
          {stateConfig.description && <p className="mt-1">{getLocalizedText(stateConfig.description, locale, t)}</p>}
          {failedSourceId && <button type="button" data-testid="card-grid-error-retry"
            disabled={Boolean(dataSourceState?.loading || parentState?.loading)}
            className="border-border bg-panel text-text mt-3 rounded-md border px-3 py-1.5 text-sm font-medium"
            onClick={() => { void dataSourceManager.reload(failedSourceId).catch((failure: unknown) => console.error('Card grid read retry failed', failure)); }}>
            {cfg.states?.retry?.title ? getLocalizedText(cfg.states.retry.title, locale, t) : t('common.retry')}
          </button>}
        </> : message || t('common.loadError')}
      </div>
    );
  }

  // ── Loading state ──────────────────────────────────────────────────────────
  if ((dataSourceState?.loading && !dataSourceState.data)
      || (parentConfig?.dataSource && (parentState?.data == null || parentState.loading && !parentState.data))) {
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
  if (rows.length === 0 && (!parentConfig || parentRows.length === 0)) {
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
        const visibleActions = cardActions.filter(action => actionAllowed(action, row));
        const rowContext = { ...runtime.getContext(), row } as any;
        const visibleFields = cardFields.filter(field => !field.visibleWhen
          || runtime.getEvaluator().evaluateCondition(field.visibleWhen, rowContext));
        let nextColumn = 0;
        const fieldBlocks = fieldsLayout ? visibleFields.map(field => {
          const cols = fieldsLayout.cols ?? 12;
          const colSpan = field.layout?.colSpan ?? field.colSpan ?? Math.max(1, Math.floor(cols / 2));
          const col = field.layout?.col ?? (nextColumn + colSpan > cols ? 0 : nextColumn);
          nextColumn = (col + colSpan) % cols;
          return { ...field, id: field.field, layout: { ...field.layout, col, colSpan } };
        }) : [];
        const renderField = (field: FieldConfig) => {
          const rowContext = { ...runtime.getContext(), row } as any;
          if (field.visibleWhen && !runtime.getEvaluator().evaluateCondition(field.visibleWhen, rowContext)) return null;
          const conditionalReadOnly = Boolean(field.readOnlyWhen && runtime.getEvaluator().evaluateCondition(field.readOnlyWhen, rowContext));
          return (
          <FieldRenderer
            key={field.field}
            runtime={runtime}
            field={{ ...field,
              field: `${formField || block.id}.${idx}.${field.field}`,
              visibleWhen: undefined, readOnlyWhen: undefined,
              readOnly: !formField || !rowSelectable || field.field===selectionRule?.positiveField || Boolean(field.valueWhen) || conditionalReadOnly || field.readOnly,
            }}
            value={field.valueWhen ? runtime.getEvaluator().evaluate(field.valueWhen, { ...runtime.getContext(), row } as any) : (currentSelectionRow && (field.readOnly || field.field===selectionRule?.positiveField) ? readOwnPath(currentSelectionRow,field.field) : row[field.field])}
            onChange={(value) => {
              if (!formField || !rowSelectable || field.field===selectionRule?.positiveField || conditionalReadOnly || field.readOnly || field.valueWhen) return;
              stateManager.updateField(scopeId, formField,
                rows.map((current, index) => index === idx ? { ...current, [field.field]: value } : current));
            }}
          />
          );
        };
        return (
          <div
            key={rowKey}
            data-testid="card-grid-card"
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            className={`card-grid-card rounded-card border-border bg-panel flex flex-col border shadow-sm ${compact ? 'p-3' : 'p-4'}`}
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

            <div className={`flex items-start justify-between gap-3 ${compact ? 'mb-3' : 'mb-1'}`}>
              <h3 className={`text-text min-w-0 break-words font-semibold ${compact ? 'text-sm' : 'text-base'}`}>{title}</h3>
              {compact && badge && <span className="rounded-pill border-border bg-subtle shrink-0 border px-2 py-0.5 text-xs text-text-2">{badge}</span>}
            </div>
            {description && <p className="text-text-2 mb-3 line-clamp-2 text-sm">{description}</p>}
            {!compact && badge && <span className="rounded-pill border-border mb-3 self-start border px-2 py-0.5 text-xs">{badge}</span>}

            {/* Declared fields; without a form array they are display-only. */}
            {visibleFields.length > 0 && (
              <fieldset aria-label={title} className={fieldsLayout ? 'mb-3' : 'mb-3 space-y-3'}>
                {fieldsLayout ? <GridLayoutRenderer layout={fieldsLayout}
                  blocks={fieldBlocks} renderBlock={renderField} /> : visibleFields.map(renderField)}
              </fieldset>
            )}

            {/* Actions */}
            {visibleActions.length > 0 && (
              <div className="mt-auto flex flex-wrap gap-2 pt-2">
                {visibleActions.map((action) => {
                  const label = getLocalizedText(action.label || action.code, locale, t);
                  const disabled = actionDisabled(action, row);
                  const running = runningAction === `${idx}:${action.code}`;
                  return (
                    <button
                      key={action.code}
                      type="button"
                      data-testid={`card-grid-action-${action.code}`}
                      onClick={() => runCardAction(action, row, idx)}
                      disabled={disabled}
                      aria-busy={running}
                      className={`rounded-control inline-flex h-8 items-center px-3 text-sm font-medium focus:outline-none focus:ring-[0_0_0_3px] focus:ring-accent/30 ${
                        action.variant === 'primary'
                          ? 'bg-accent hover:bg-accent-hover text-white'
                          : action.variant === 'danger' ? 'bg-status-red text-white hover:opacity-90'
                          : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
                      } ${disabled ? 'cursor-not-allowed opacity-50' : ''}`}
                    >
                      {running ? t('common.loading') : label}
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
    type Group = { title: string; parent?: any; parentIndex?: number; entries: Array<{ row: any; index: number }> };
    const groups = new Map<string, Group>();
    const identityKey = (identity: unknown) => (typeof identity === 'string' && identity.trim()) || typeof identity === 'number' && Number.isFinite(identity)
      ? `${typeof identity}:${identity}` : undefined;
    if (parentConfig) parentRows.forEach((parent: any, index: number) => {
      const key = identityKey(readOwnPath(parent, parentConfig.identityField));
      if (!key || groups.has(key)) throw new Error('Missing or duplicate parent group identity');
      groups.set(key, { title: getLocalizedText(parent[parentConfig.titleField], locale, t), parent, parentIndex: index, entries: [] });
    });
    rows.forEach((row: any, index: number) => {
      const identity = row[groupByField];
      const key = identityKey(identity) || `missing:${index}`;
      if (parentConfig && !groups.has(key)) throw new Error('Child card has no matching parent group identity');
      const group: Group = groups.get(key) || {
        title: getLocalizedText(row[cfg.groupTitleField || groupByField], locale, t), entries: [],
      };
      group.entries.push({ row, index });
      groups.set(key, group);
    });
    const parentFields = (group: Group, fields: FieldConfig[], summary: boolean) => {
      if (!group.parent || !parentConfig) return null;
      const row = group.parent;
      return <div style={{display: 'grid', gridTemplateColumns: compact ? 'repeat(auto-fit, minmax(min(100%, 6rem), 1fr))' : 'repeat(auto-fit, minmax(min(100%, 12rem), 1fr))', gap: '0.75rem', pointerEvents: summary ? 'none' : undefined}} data-testid={summary ? 'card-group-summary' : 'card-group-fields'}>
        {fields.map(field => {
          const ctx = { ...runtime.getContext(), row } as any;
          if (field.visibleWhen && !runtime.getEvaluator().evaluateCondition(field.visibleWhen, ctx)) return null;
          const readOnly = summary || field.readOnly || Boolean(field.valueWhen)
            || Boolean(field.readOnlyWhen && runtime.getEvaluator().evaluateCondition(field.readOnlyWhen, ctx));
          return <div key={field.field} style={{minWidth: 0}}><FieldRenderer runtime={runtime}
            field={{ ...field, field: `${parentConfig.field}.${group.parentIndex}.${field.field}`, visibleWhen: undefined, readOnlyWhen: undefined, readOnly }}
            value={field.valueWhen ? runtime.getEvaluator().evaluate(field.valueWhen, ctx) : row[field.field]}
            onChange={value => {
              if (readOnly) return;
              stateManager.updateField(scopeId, parentConfig.field, parentRows.map((parent: any, index: number) => index === group.parentIndex ? { ...parent, [field.field]: value } : parent));
            }} /></div>;
        })}
      </div>;
    };
    const groupCards = (key: string, group: Group) => {
      const search = groupSearch[key] || '';
      const entries = cfg.searchFields?.length && search.trim()
        ? group.entries.filter(({ row }) => cfg.searchFields.some((field: string) => String(readOwnPath(row, field) ?? '').toLocaleLowerCase(locale).includes(search.trim().toLocaleLowerCase(locale))))
        : group.entries;
      const searchLabel = getLocalizedText(cfg.searchPlaceholder || { 'zh-CN': '搜索', en: 'Search' }, locale, t);
      return <>
        <div className={compact ? 'flex flex-wrap items-end justify-between gap-3' : undefined}>
        <div className={compact ? 'min-w-0 flex-1' : undefined}>{parentFields(group, parentConfig?.fields || [], false)}</div>
        {cfg.searchFields?.length && group.entries.length > 0 ? <div className={compact ? 'shrink-0' : 'my-3 flex justify-end'}>
          <input type="search" aria-label={searchLabel} placeholder={searchLabel} value={search}
            className="rounded-control border-border bg-panel text-text max-w-full border px-3 py-2 text-sm"
            onChange={event => setGroupSearch(current => ({ ...current, [key]: event.target.value }))} />
        </div> : null}
        </div>
        {group.entries.length === 0 && parentConfig?.emptyText ? <p className="text-text-2 py-3 text-sm">{getLocalizedText(parentConfig.emptyText, locale, t)}</p> : null}
        {group.entries.length > 0 && entries.length === 0 ? <p role="status" data-testid="card-group-search-empty" className="text-text-2 py-3 text-sm">{getLocalizedText({ 'zh-CN': '没有匹配结果，请调整搜索条件。', en: 'No matching results. Adjust your search.' }, locale, t)}</p> : null}
        <div data-testid="card-grid-tracks" className={`card-grid-tracks mt-3 grid gap-3 ${columnsClass}`} style={{ ...columnsStyle, alignItems: compact ? 'start' : undefined }}>
          {entries.map(({ row, index: originalIndex }) => renderCard(row, originalIndex))}
        </div>
      </>;
    };
    return (
      <div data-testid="card-grid-block" className={`card-grid-groups ${compact ? "space-y-4" : "space-y-6"}`}>
        {blockHeading}
        {selectionHint}
        {Array.from(groups.entries()).map(([key, group], index) => cfg.collapsibleGroups === true ? (
          <details key={key} data-testid="card-grid-group" open={cfg.groupsDefaultOpen !== false && (initialOpenCount === undefined || index < initialOpenCount)}
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            data-warning={Boolean(cfg.groupWarningField && (group.parent?.[cfg.groupWarningField] === true || group.entries.some(({ row }) => row[cfg.groupWarningField] === true)))}
            className={`card-grid-group rounded-lg border border-border ${compact ? 'group overflow-hidden' : 'p-3'} ${cfg.groupWarningField && (group.parent?.[cfg.groupWarningField] === true || group.entries.some(({ row }) => row[cfg.groupWarningField] === true)) ? 'bg-amber-50' : 'bg-panel'}`}>
            <summary className={`card-grid-group__summary cursor-pointer select-none text-sm font-semibold text-text ${compact ? 'list-none p-3' : ''}`}>
              <div data-testid="card-group-heading" style={compact ? { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 24rem), 1fr))', gap: '1rem' } : undefined}>
                <div className="min-w-0">
                  <span data-testid="card-group-title">{compact && <span aria-hidden="true" className="mr-2 inline-block transition-transform group-open:rotate-90">›</span>}{group.title}</span>
                  {cfg.groupDescriptionField && <span data-testid="card-group-description" className={`${compact ? 'mt-1 block text-xs' : 'ml-3'} font-normal text-text-2 whitespace-pre-wrap break-words`}>
                    {getLocalizedText((group.parent || group.entries[0]?.row)?.[cfg.groupDescriptionField], locale, t)}
                  </span>}
                </div>
                {parentFields(group, parentConfig?.summaryFields || [], true)}
              </div>
            </summary>
            <div className={compact ? 'card-grid-group__body border-t border-border bg-subtle p-3' : 'card-grid-group__body mt-3'}>{groupCards(key, group)}</div>
          </details>
        ) : (
          <section key={key} className="card-grid-group" data-testid="card-grid-group"
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            aria-labelledby={group.title ? `${block.id}-group-${index}` : undefined}>
            {group.title && <h2 id={`${block.id}-group-${index}`}
              className="text-text mb-3 text-base font-semibold">{group.title}</h2>}
            {parentFields(group, parentConfig?.summaryFields || [], true)}
            {groupCards(key, group)}
          </section>
        ))}
      </div>
    );
  }
  return <div data-testid="card-grid-block" className={`grid gap-4 ${columnsClass}`} style={columnsStyle}>
    {blockHeading}
    {selectionHint}
    {rows.map(renderCard)}
  </div>;
};

export default CardGridBlockRenderer;
