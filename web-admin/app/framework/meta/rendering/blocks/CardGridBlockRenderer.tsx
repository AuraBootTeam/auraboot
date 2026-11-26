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
 *   "badgeTones": { "complete": "success" }, // optional semantic badge colors
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
import { resolveStatusTone } from '../../runtime/renderers/statusTone';
import React, { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { hasActiveFormDialog, subscribeFormDialogVisibility } from '../../runtime/actions/formDialogLifecycle';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig, CardImageViewport, FieldConfig, LayoutConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';
import { useToastContext } from '~/contexts/ToastContext';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { Dialog, DialogContent, DialogTitle } from '~/ui/ui/dialog';
import { ToolbarBlockRenderer } from './ToolbarBlockRenderer';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { GridLayoutRenderer } from '~/framework/meta/rendering/layout/GridLayoutRenderer';
import {
  dataSourceErrorMessage,
  dataSourceErrorStatus,
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
  const lineItems = cfg.itemPresentation === 'rows';
  const tableItems = cfg.itemPresentation === 'table';
  const dense = cfg.fieldDensity === 'compact';
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
  if (cfg.childrenField && formField) throw new Error("Nested child cards require a read-only data source");
  if (tableItems && formField && (cfg.groupByField || cfg.groupRows || cardActions.length))
    throw new Error('Editable table rows require a flat form array without card actions');
  const parentConfig = cfg.groupRows;
  if (parentConfig && (!cfg.groupByField || !parentConfig.field || !parentConfig.identityField || !parentConfig.titleField))
    throw new Error('Joined card groups require an explicit parent form array and group identity');
  const [activeDetails, setActiveDetails] = useState<string | null>(null);
  const formDialogOpen = useSyncExternalStore(subscribeFormDialogVisibility, hasActiveFormDialog, () => false);
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
  const freshRows = cfg.childrenField ? normalizeRows(rawData).flatMap(parent => {
    const children = readOwnPath(parent, cfg.childrenField);
    if (!Array.isArray(children)) throw new Error('Card child rows must be an array');
    return children;
  }) : normalizeRows(rawData);
  const initialRows = rowSelection ? initializeSelectedRows(freshRows,rowSelection) : freshRows;
  const rows = Array.isArray(formRows) ? formRows : initialRows;
  const storedParents = parentConfig ? stateManager?.getFieldValue(scopeId, parentConfig.field) : undefined;
  if (parentConfig && !parentConfig.dataSource && !Array.isArray(storedParents))
    throw new Error('Joined card parent form rows must be an array');
  const rawParents = parentConfig?.dataSource ? dataSourceManager.getData(parentConfig.dataSource) : storedParents;
  const parentsReadOnly = Boolean(parentConfig && [...(parentConfig.fields || []), ...(parentConfig.detailsFields || []), ...(parentConfig.asideFields || []), ...(parentConfig.asideDetailsFields || []), ...(parentConfig.footerFields || [])].every((field: FieldConfig) => field.readOnly || field.valueWhen));
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
    const status = dataSourceErrorStatus(dataSourceState?.error || parentState?.error);
    return (
      <div
        role="alert"
        data-testid="card-grid-error"
        className="rounded-control bg-status-red-bg text-status-red border border-status-red p-3 text-sm"
      >
        {dataSourceErrorMessage(status, locale)}
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
  const renderRowField = (row: any, idx: number, field: FieldConfig, table = false) => {
    const currentSelectionRow = selectionRule ? currentSelectionRows?.get(readOwnPath(row,selectionRule.identityField) as string) : undefined;
    const rowSelectable = !selectionRule || Boolean(currentSelectionRow && positiveRowValue(readOwnPath(currentSelectionRow,selectionRule.positiveField)));
    const rowContext = { ...runtime.getContext(), row } as any;
    if (field.visibleWhen && !runtime.getEvaluator().evaluateCondition(field.visibleWhen, rowContext)) return null;
    const conditionalReadOnly = Boolean(field.readOnlyWhen && runtime.getEvaluator().evaluateCondition(field.readOnlyWhen, rowContext));
    const content = (
    <FieldRenderer
      key={field.field}
      runtime={runtime}
      field={{ ...field,
        props: { ...(dense || table ? { density: 'compact' } : {}), ...field.props, ...(table ? {label: '', 'aria-label': `${getLocalizedText(row[titleField],locale,t)} · ${getLocalizedText(field.label || field.field,locale,t)}`} : {}) },
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
    return cfg.separatedFields?.includes(field.field)
      ? <div className="border-border mt-2 border-t pt-3">{content}</div> : content;
  };

  const renderTable = (entries: {row: any; index: number}[]) => <div className="overflow-x-auto" data-testid="card-group-table"><table className="w-full text-xs">
    <thead className="bg-subtle text-text-3"><tr>
      {selectionRule ? <th className="w-12 px-4 py-3"><span className="sr-only">{getLocalizedText(rowSelection.label,locale,t)}</span></th> : null}
      {(cfg.table?.columns || []).map((column: any) => <th key={column.field} style={{width:column.width,minWidth:column.width}} className={`whitespace-nowrap px-4 py-3 font-normal ${column.align === 'right' ? 'text-right' : 'text-left'}`}>{getLocalizedText(column.label || column.field,locale,t)}</th>)}
    </tr></thead><tbody>{entries.map(({row,index}) => {
      const identity = selectionRule ? readOwnPath(row,selectionRule.identityField) as string : row.pid || String(index);
      const authoritative = selectionRule ? currentSelectionRows?.get(identity) : undefined;
      const selectable = !selectionRule || Boolean(authoritative && positiveRowValue(readOwnPath(authoritative,selectionRule.positiveField)));
      const displayRow = authoritative ? {...row,...authoritative} : row;
      return <tr key={identity} data-testid="card-grid-row" className="border-border border-t">
        {selectionRule ? <td className="px-4 py-3 align-top"><input type="checkbox"
          aria-label={`${getLocalizedText(rowSelection.label,locale,t)} · ${getLocalizedText(row[titleField],locale,t)}`}
          checked={readOwnPath(row,selectionRule.selectedField) === true} disabled={!selectable}
          onChange={event => {if (formField && selectable) stateManager.updateField(scopeId,formField,rows.map((current,i) => i === index ? {...current,[selectionRule.selectedField]:event.target.checked} : current));}} /></td> : null}
        {(cfg.table?.columns || []).map((column: any) => {
          const field = formField ? cardFields.find(field => field.field === column.field && !field.readOnly && field.field !== selectionRule?.positiveField) : undefined;
          return <td key={column.field} className={`px-4 py-3 align-top ${column.align === 'right' ? 'text-right tabular-nums whitespace-nowrap' : 'text-left'}`}>
            {field ? renderRowField(row,index,field,true) : cellRendererRegistry.render(column.presentation ? 'workspace' : column.valueType || 'text',{value:readOwnPath(displayRow,column.field),record:displayRow,column,locale,t,rowIndex:index})}
          </td>;
        })}
      </tr>;
    })}</tbody></table></div>;
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
        const configuredTone = rawBadge == null ? undefined : cfg.badgeTones?.[String(rawBadge)];
        const toneClasses = {
          green: 'bg-status-green-bg text-status-green',
          amber: 'bg-status-amber-bg text-status-amber',
          red: 'bg-status-red-bg text-status-red',
          blue: 'bg-accent-weak text-accent',
          gray: 'bg-subtle text-text-2',
        };
        const badgeClass = configuredTone
          ? `rounded px-2 py-0.5 text-xs font-medium ${toneClasses[resolveStatusTone(configuredTone)]}`
          : 'rounded-pill border-border border px-2 py-0.5 text-xs';
        const badgeNode = badge ? <span data-testid="card-grid-badge" className={`${badgeClass} self-start`}>{badge}</span> : null;

        const currentSelectionRow = selectionRule ? currentSelectionRows?.get(readOwnPath(row,selectionRule.identityField) as string) : undefined;
        const rowSelectable = !selectionRule || Boolean(currentSelectionRow && positiveRowValue(readOwnPath(currentSelectionRow,selectionRule.positiveField)));
        const rowSelected = selectionRule ? readOwnPath(row,selectionRule.selectedField) === true : false;
        const visibleActions = cardActions.filter(action => actionAllowed(action, row));
        const rowContext = { ...runtime.getContext(), row } as any;
        const visibleFields = cardFields.filter(field => field.field !== cfg.primaryField && field.field !== cfg.primarySupportingField && (!field.visibleWhen
          || runtime.getEvaluator().evaluateCondition(field.visibleWhen, rowContext)));
        let nextColumn = 0;
        const fieldBlocks = fieldsLayout ? visibleFields.map(field => {
          const cols = fieldsLayout.cols ?? 12;
          const colSpan = field.layout?.colSpan ?? field.colSpan ?? Math.max(1, Math.floor(cols / 2));
          const col = field.layout?.col ?? (nextColumn + colSpan > cols ? 0 : nextColumn);
          nextColumn = (col + colSpan) % cols;
          return { ...field, id: field.field, layout: { ...field.layout, col, colSpan } };
        }) : [];
        const renderField = (field: FieldConfig) => renderRowField(row, idx, field);
        return (
          <div
            key={rowKey}
            data-testid="card-grid-card"
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            className={`${lineItems ? 'border-border border-b last:border-b-0 p-4' : `rounded-card ${cfg.cardEmphasisWhen && runtime.getEvaluator().evaluateCondition(cfg.cardEmphasisWhen, rowContext) ? 'border-accent' : 'border-border'} bg-panel border ${dense ? 'p-3.5' : compact ? 'p-3 shadow-sm' : 'p-4 shadow-sm'}`} flex min-w-0 flex-col ${dense ? '[&_fieldset]:mb-0 [&_label]:text-xs [&_input]:text-xs' : ''}`}
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
            <div className={cfg.headerLayout === 'inline' ? 'mb-2 flex flex-wrap items-center justify-between gap-2' : undefined}>
              <h3 className={`text-text mb-1 break-words font-semibold ${compact || dense ? 'text-sm' : 'text-base'}`}>{title}</h3>
              {cfg.headerLayout === 'inline' ? badgeNode : null}
            </div>

            {/* Description */}
            {description && (
              <p className={`text-text-3 ${lineItems ? 'mb-3 whitespace-pre-wrap text-xs leading-5' : 'mb-2 line-clamp-2 text-xs'}`}>{description}</p>
            )}

            {/* Badge */}
            {badge && cfg.headerLayout !== 'inline' && (
              <span data-testid="card-grid-badge" className={`${badgeClass} mb-3 self-start`}>{badge}</span>
            )}

            {cfg.primaryField && cardFields.find(field => field.field === cfg.primaryField) ? <div className={`${dense ? 'my-1' : 'my-3'} flex flex-wrap items-baseline gap-x-2 gap-y-1`}>
              {renderField(cardFields.find(field => field.field === cfg.primaryField)!)}
              {cfg.primarySupportingField && cardFields.find(field => field.field === cfg.primarySupportingField)
                ? <div className="text-text-3 min-w-0">{renderField(cardFields.find(field => field.field === cfg.primarySupportingField)!)}</div> : null}
            </div> : null}

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
                      className={`${action.variant === 'link' ? 'text-accent inline-flex min-h-8 items-center text-xs hover:underline' : 'rounded-control inline-flex h-8 items-center px-3 text-sm font-medium'} focus:outline-none focus:ring-[0_0_0_3px] focus:ring-accent/30 ${
                        action.variant === 'primary'
                          ? 'bg-accent hover:bg-accent-hover text-white'
                          : action.variant === 'danger' ? 'bg-status-red text-white hover:opacity-90'
                          : action.variant === 'link' ? ''
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
    const parentFields = (group: Group, fields: FieldConfig[], summary: boolean, stacked = false, inline = false) => {
      if (!group.parent || !parentConfig) return null;
      const row = group.parent;
      return <div style={{display: inline ? 'flex' : 'grid', flexWrap: inline ? 'wrap' : undefined, alignItems: inline ? 'center' : undefined, gridTemplateColumns: inline ? undefined : stacked ? '1fr' : compact && summary ? 'repeat(auto-fit, minmax(min(100%, 6rem), 1fr))' : 'repeat(auto-fit, minmax(min(100%, 12rem), 1fr))', gap: '0.75rem', pointerEvents: summary ? 'none' : undefined}} data-testid={summary ? 'card-group-summary' : 'card-group-fields'}>
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
      const details = parentConfig?.detailsFields?.length ? <details data-testid="card-group-details" className="border-border border-t px-4 py-3 text-xs">
        <summary className="text-accent cursor-pointer">{getLocalizedText(parentConfig.detailsLabel || { 'zh-CN': '查看补充信息', en: 'View additional information' }, locale, t)}</summary>
        <div className="mt-3">{parentFields(group, parentConfig.detailsFields, false)}</div>
      </details> : null;
      const items = tableItems ? renderTable(entries) : <div data-testid="card-grid-tracks" className={lineItems ? 'flex flex-col' : `grid gap-3 ${columnsClass}`} style={lineItems ? undefined : {...columnsStyle, alignItems: compact ? 'start' : undefined}}>
        {entries.map(({row, index: originalIndex}) => renderCard(row, originalIndex))}
      </div>;
      return <>
        {parentFields(group, parentConfig?.fields || [], false)}
        {parentConfig?.detailsPlacement !== 'after-items' ? details : null}
        {cfg.searchFields?.length && group.entries.length > 0 ? <div className="mb-3 flex justify-end">
          <input type="search" aria-label={searchLabel} placeholder={searchLabel} value={search}
            className="rounded-control border-border bg-panel text-text max-w-full border px-3 py-1.5 text-xs"
            onChange={event => setGroupSearch(current => ({ ...current, [key]: event.target.value }))} />
        </div> : null}
        {group.entries.length === 0 && parentConfig?.emptyText ? <p className="text-text-2 py-3 text-sm">{getLocalizedText(parentConfig.emptyText, locale, t)}</p> : null}
        {parentConfig?.asideFields?.length ? <div className="grid min-[900px]:grid-cols-[minmax(0,1fr)_220px]">
          <div className="min-w-0">{items}</div><aside className="border-border min-w-0 border-t p-4 min-[900px]:border-t-0 min-[900px]:border-l">{parentFields(group, parentConfig.asideFields, false)}
            {parentConfig.asideDetailsFields?.length ? <><button type="button" className="text-accent mt-3 text-xs hover:underline" onClick={() => setActiveDetails(key)}>{getLocalizedText(parentConfig.asideDetailsLabel || {'zh-CN':'查看完整记录',en:'View full record'},locale,t)}</button>
              <Dialog open={activeDetails === key} onOpenChange={open => {if (!open) setActiveDetails(null);}}><DialogContent className="max-h-[85dvh] w-[calc(100%-2rem)] max-w-[720px] overflow-y-auto" closeLabel={locale.startsWith('zh') ? '关闭' : 'Close'} aria-describedby={undefined}><DialogTitle>{group.title} · {getLocalizedText(parentConfig.asideDetailsLabel,locale,t)}</DialogTitle>{parentFields(group,parentConfig.asideDetailsFields,false,true)}</DialogContent></Dialog>
            </> : null}</aside>
        </div> : items}
        {parentConfig?.footerFields?.length ? <div data-testid="card-group-footer" className={parentConfig.footerLayout === 'inline' ? 'pt-3 pb-2' : 'border-border border-t px-4 py-2'}>{parentFields(group, parentConfig.footerFields, false, false, parentConfig.footerLayout === 'inline')}</div> : null}
        {parentConfig?.detailsPlacement === 'after-items' ? details : null}
      </>;

    };
    const orderedGroups = Array.from(groups.entries());
    if (cfg.groupOrder === 'reverse') orderedGroups.reverse();
    const collection = (
      <div data-testid="card-grid-block" className={cfg.groupLayout === 'divided' ? 'border-border bg-panel overflow-hidden rounded-lg border' : 'space-y-4'}>
        {block.title ? <div className="border-border border-b px-4 py-4"><h2 className="text-text text-base font-semibold">{getLocalizedText(block.title,locale,t)}</h2></div> : null}
        {selectionHint}
        {orderedGroups.map(([key, group], index) => cfg.collapsibleGroups === true ? (
          <details key={key} data-testid="card-grid-group" open={cfg.groupsDefaultOpen === 'first' ? index === 0 : cfg.groupsDefaultOpen !== false}
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            className={`${cfg.groupLayout === 'divided' ? 'border-border border-b last:border-b-0' : 'rounded-lg border border-border'} ${compact ? 'group overflow-hidden' : 'p-3'} ${cfg.groupWarningField && (group.parent?.[cfg.groupWarningField] === true || group.entries.some(({ row }) => row[cfg.groupWarningField] === true)) ? 'bg-amber-50' : 'bg-surface'}`}>
            <summary className={`cursor-pointer select-none text-sm font-semibold text-text ${compact ? 'list-none p-4' : ''}`}>
              <div data-testid="card-group-heading" style={compact ? { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 24rem), 1fr))', gap: '1rem' } : undefined}>
                <div className="min-w-0">
                  <span data-testid="card-group-title">{compact && <span aria-hidden="true" className="mr-2 inline-block transition-transform group-open:rotate-90">›</span>}{group.title}{cfg.groupBadgeField && <span className="bg-accent-weak text-accent ml-2 inline-flex rounded px-2 py-0.5 text-xs font-normal">{getLocalizedText(group.parent?.[cfg.groupBadgeField], locale, t)}</span>}</span>
                  {cfg.groupDescriptionField && <span data-testid="card-group-description" style={cfg.groupDescriptionLines === 1 || cfg.groupDescriptionLines === 2 ? {display: '-webkit-box', WebkitBoxOrient: 'vertical', WebkitLineClamp: cfg.groupDescriptionLines, overflow: 'hidden'} : undefined} className={`${compact ? 'mt-1 block text-xs' : 'ml-3'} font-normal text-text-2 whitespace-pre-wrap break-words`}>
                    {getLocalizedText((group.parent || group.entries[0]?.row)?.[cfg.groupDescriptionField], locale, t)}
                  </span>}
                </div>
                {parentFields(group, parentConfig?.summaryFields || [], true)}
              </div>
            </summary>
            <div className={compact ? `border-t border-border ${tableItems || lineItems ? 'bg-panel' : 'bg-subtle p-4'}` : 'mt-3'}>{groupCards(key, group)}</div>
          </details>
        ) : (
          <section key={key} data-testid="card-grid-group" className={lineItems ? 'border-border bg-panel overflow-hidden rounded-lg border' : undefined}
            style={{ scrollMarginBlockStart: 'calc(var(--workspace-header-height, 3.5rem) + 1rem)' }}
            aria-labelledby={group.title ? `${block.id}-group-${index}` : undefined}>
            {group.title && <h2 id={`${block.id}-group-${index}`}
              className={lineItems ? "border-border bg-accent-weak text-text border-b px-4 py-3 text-sm font-semibold" : "text-text mb-3 text-base font-semibold"}>{group.title}</h2>}
            <div className={lineItems ? "px-4 py-2" : undefined}>{parentFields(group, parentConfig?.summaryFields || [], true)}</div>
            {groupCards(key, group)}
          </section>
        ))}
      </div>
    );
    if (!cfg.collectionDialog) return collection;
    const dialog = cfg.collectionDialog;
    const dialogFields = (fields: FieldConfig[], stacked = false) => <div className={`grid gap-3 ${stacked ? 'grid-cols-1' : 'grid-cols-1 sm:grid-cols-3'}`}>{fields.map(field => <div key={field.field} className="min-w-0"><FieldRenderer runtime={runtime} field={{...field, readOnly: true}} value={field.valueWhen ? runtime.getEvaluator().evaluate(field.valueWhen, runtime.getContext()) : stateManager.getFieldValue(scopeId, field.field)} /></div>)}</div>;
    return <Dialog open modal={!formDialogOpen} onOpenChange={open => { if (!open && !hasActiveFormDialog()) void runCardAction(dialog.dismissAction, runtime.getContext().form || {}, -1); }}><DialogContent onInteractOutside={event => { if (hasActiveFormDialog()) event.preventDefault(); }} onEscapeKeyDown={event => { if (hasActiveFormDialog()) event.preventDefault(); }} className="flex max-h-[90dvh] w-[calc(100%-2rem)] max-w-[900px] flex-col gap-0 overflow-hidden p-0" closeLabel={locale.startsWith('zh') ? '关闭' : 'Close'} aria-describedby={undefined}>
      <div className="border-border border-b px-6 py-5"><DialogTitle>{getLocalizedText(dialog.title, locale, t)}</DialogTitle></div>
      <div className="min-h-0 overflow-y-auto px-6 py-5"><div className="bg-subtle mb-4 rounded-lg p-4">{dialogFields(dialog.headerFields || [])}</div>{collection}
        {dialog.footerFields?.length ? <div className="bg-subtle mt-4 rounded-lg p-4">{dialogFields(dialog.footerFields,true)}</div> : null}</div>
      <div className="border-border flex justify-end border-t px-6 py-4"><ToolbarBlockRenderer block={{id: `${block.id}-dialog-actions`, blockType: 'toolbar', buttons: dialog.buttons}} runtime={runtime} /></div>
    </DialogContent></Dialog>;
  }
  if (tableItems) return <div data-testid="card-grid-block" className="border-border bg-panel overflow-hidden rounded-lg border">
    {block.title ? <h2 className="border-border border-b px-4 py-3 text-sm font-semibold">{getLocalizedText(block.title,locale,t)}</h2> : null}
    {selectionHint}{renderTable(rows.map((row,index) => ({row,index})))}
  </div>;
  return <div data-testid="card-grid-block" className={`grid gap-4 ${columnsClass}`} style={columnsStyle}>
    {selectionHint}
    {rows.map(renderCard)}
  </div>;
};

export default CardGridBlockRenderer;
