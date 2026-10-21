import type { DslBlockV3 } from '../../types';
import type { RuntimeExecutionContext, RuntimeWidgetData } from '../runtimeExecution';
import React from 'react';
import type { RuntimeBlockContextOptions, RuntimePageContext } from './runtimeContexts';





// ---------------------------------------------------------------------------
// Workbench-block representative previews (metric-strip / status-banner).
//
// These blocks are fully data-bound by the platform meta-rendering renderers
// (framework/meta/rendering/blocks/MetricStripBlockRenderer +
// StatusBannerBlockRenderer) on the live /p/ page, which read a SchemaRuntime /
// dataSource / polling. The designer's own runtime (this file) is a separate
// model (DslBlockV3) without that runtime, so here we render a CONFIG-DRIVEN
// REPRESENTATIVE PREVIEW only: metric labels + placeholder values, and the tone /
// title status mapping. The complete data binding renders on the published page.
// (Deliberately NOT bridging the platform data-binding renderer into the designer
// runtime — see BlockRegistry / InspectorSchemaRegistry comments.)
// ---------------------------------------------------------------------------

// Token-backed tone utility classes shared with the platform renderers. No raw
// hex — these resolve through the Tailwind theme tokens.
export const WORKBENCH_TONE_CARD_CLASS: Record<string, string> = {
  green: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  amber: 'border-amber-200 bg-amber-50 text-amber-900',
  red: 'border-rose-200 bg-rose-50 text-rose-900',
  blue: 'border-blue-200 bg-blue-50 text-blue-900',
  purple: 'border-violet-200 bg-violet-50 text-violet-900',
  gray: 'border-slate-200 bg-white text-slate-900',
  default: 'border-slate-200 bg-white text-slate-900',
};

export const WORKBENCH_TONE_DOT_CLASS: Record<string, string> = {
  green: 'bg-emerald-500',
  amber: 'bg-amber-500',
  red: 'bg-rose-500',
  blue: 'bg-blue-500',
  purple: 'bg-violet-500',
  gray: 'bg-slate-400',
  default: 'bg-slate-400',
};

export function readLocalizedLabel(value: unknown, locale: string): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const map = value as Record<string, string>;
    return (
      map[locale] || map['en-US'] || map.en || map['zh-CN'] || Object.values(map)[0] || ''
    );
  }
  return '';
}

export function getMetricStripMetrics(block: DslBlockV3): Array<Record<string, unknown>> {
  const metrics = (block as unknown as Record<string, unknown>).metrics;
  if (!Array.isArray(metrics)) return [];
  return metrics.filter((metric): metric is Record<string, unknown> => isRecord(metric));
}

// ---------------------------------------------------------------------------
// Workbench-family batch 2 representative previews. Same architecture as the
// metric-strip / status-banner previews above: read the BARE top-level config
// the platform renderers read, render a config-driven representative placeholder
// (labels + scaffold, no live data), an empty state, and the shared workbench
// preview hint. Live data binding renders on the published /p/ page via the
// platform renderers (framework/meta/rendering/blocks/*). Token-backed utility
// classes only — no raw hex.
// ---------------------------------------------------------------------------

export const WORKBENCH_PREVIEW_BUTTON_CLASS: Record<string, string> = {
  primary: 'border-blue-200 bg-blue-50 text-blue-700',
  secondary: 'border-slate-200 bg-white text-slate-700',
  danger: 'border-rose-200 bg-rose-50 text-rose-700',
  ghost: 'border-transparent bg-slate-50 text-slate-600',
  default: 'border-slate-200 bg-white text-slate-700',
};

export function getRawArray(block: DslBlockV3, key: string): Array<Record<string, unknown>> {
  const value = (block as unknown as Record<string, unknown>)[key];
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => isRecord(item));
}

export function getRawRecord(block: DslBlockV3, key: string): Record<string, unknown> {
  const value = (block as unknown as Record<string, unknown>)[key];
  return isRecord(value) ? value : {};
}

export function formatRuntimeCell(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

export function getRuntimeBlockContext(
  block: DslBlockV3,
  pageContext: RuntimePageContext,
  blockPath: string[],
  options: RuntimeBlockContextOptions = {},
): RuntimeExecutionContext {
  return {
    ...pageContext,
    blockId: block.id,
    blockType: block.blockType,
    blockPath,
    actionType: block.actionType,
    permissionCode: options.permissionCode,
    widgetType: block.widgetType,
    formValues: options.formValues,
    selectedRows: options.selectedRows,
    selectedRowIds: options.selectedRowIds,
    currentRow: options.currentRow,
    currentRowId: options.currentRowId,
    pickerSearch: options.pickerSearch,
  };
}

export function getCurrentRouteQuery(): Record<string, string | string[]> {
  if (typeof window === 'undefined') return {};

  const params = new URLSearchParams(window.location.search);
  const query = new Map<string, string | string[]>();
  params.forEach((value, key) => {
    const existing = query.get(key);
    if (Array.isArray(existing)) {
      query.set(key, [...existing, value]);
      return;
    }
    if (typeof existing === 'string') {
      query.set(key, [existing, value]);
      return;
    }
    query.set(key, value);
  });
  return Object.fromEntries(query);
}

function normalizeSpan(block: DslBlockV3): number {
  return typeof block.layout?.span === 'number' ? Math.max(1, Math.min(12, block.layout.span)) : 12;
}

export function getSpanGridStyle(block: DslBlockV3): React.CSSProperties {
  const span = normalizeSpan(block);
  return {
    gridColumn: `span ${span} / span ${span}`,
  };
}

export function getWidgetGridStyle(block: DslBlockV3): React.CSSProperties {
  const width = clampGrid(getGridNumber(block.layout?.w, 3), 1, 12);
  const height = clampGrid(getGridNumber(block.layout?.h, 2), 1, 12);
  const x = clampGrid(getGridNumber(block.layout?.x, 0), 0, Math.max(0, 12 - width));
  const y = Math.max(0, getGridNumber(block.layout?.y, 0));

  return {
    gridColumn: `${x + 1} / span ${width}`,
    gridRow: `${y + 1} / span ${height}`,
  };
}

export function getGridNumber(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function clampGrid(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

export function getBlockLabel(block: DslBlockV3, locale = 'en-US'): string {
  const title = block.title;
  if (typeof title === 'string') return title;
  if (title) {
    const resolved = title[locale] || title['en-US'] || title.en || title['zh-CN'];
    if (resolved) return resolved;
  }
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

export function getRuntimePermissionCode(block: DslBlockV3): string {
  return getStringProp(block.props?.permissionCode) || getStringProp(block.props?.permission);
}

export function isRuntimeBlockPermissionAllowed(
  block: DslBlockV3,
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  const permissionCode = getRuntimePermissionCode(block);
  return permissionCode ? hasPermission(permissionCode) : true;
}

export function getRuntimeActionConditionValues({
  formValues,
  currentRow,
  currentRowId,
}: {
  formValues?: Record<string, unknown>;
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
}): Record<string, unknown> | undefined {
  if (currentRow) {
    return {
      ...currentRow,
      current: {
        row: currentRow,
        rowId: currentRowId,
      },
    };
  }
  return formValues;
}

export function shouldExecuteLiveAction(block: DslBlockV3): boolean {
  return block.props?.executionMode === 'live' || block.props?.executeLive === true;
}

export function hasExecutableWidgetDataSource(block: DslBlockV3): boolean {
  const dataSource = block.dataSource;
  // An explicit static preview must not execute even a configured named query.
  if (!dataSource || dataSource.executionMode === 'preview') return false;
  const type = getStringProp(dataSource.type) || getStringProp(dataSource.mode);
  const live = dataSource.executionMode === 'live' || dataSource.executeLive === true;
  const hasQueryBuilderQuery = isRecord(dataSource.query);
  const hasNamedQueryCode = Boolean(getNamedQueryCode(dataSource));
  const isNamedQuery = type === 'namedQuery' || type === 'named-query';

  return (
    (hasQueryBuilderQuery && (live || type === 'query-builder')) ||
    ((isNamedQuery || hasNamedQueryCode) && (live || isNamedQuery))
  );
}

export function hasExecutableHelperDataSource(block: DslBlockV3): boolean {
  const dataSource = block.dataSource;
  // An explicit static preview must not execute even a configured named query.
  if (!dataSource || dataSource.executionMode === 'preview') return false;
  const type = getStringProp(dataSource.type) || getStringProp(dataSource.mode);
  const live = dataSource.executionMode === 'live' || dataSource.executeLive === true;
  const hasQueryBuilderQuery = isRecord(dataSource.query);
  const hasNamedQueryCode = Boolean(getNamedQueryCode(dataSource));
  const isNamedQuery = type === 'namedQuery' || type === 'named-query';

  return (
    (hasQueryBuilderQuery && (live || type === 'query-builder')) ||
    ((isNamedQuery || hasNamedQueryCode) && (live || isNamedQuery))
  );
}

function getNamedQueryCode(dataSource: Record<string, unknown>): string {
  return (
    getStringProp(dataSource.queryCode) ||
    getStringProp(dataSource.namedQueryCode) ||
    getStringProp(dataSource.namedQuery) ||
    getStringProp(dataSource.code)
  );
}

export function mergeWidgetData(block: DslBlockV3, data: RuntimeWidgetData | null): DslBlockV3 {
  if (!data) return block;

  return {
    ...block,
    props: {
      ...block.props,
      ...(typeof data.value === 'string' ? { value: data.value } : {}),
      ...(data.series ? { series: data.series } : {}),
      ...(data.columns ? { columns: data.columns } : {}),
      ...(data.rows ? { rows: data.rows } : {}),
      ...(data.emptyText ? { emptyText: data.emptyText } : {}),
    },
  };
}

export function getActionStatus(block: DslBlockV3): string {
  const feedback = getStringProp(block.props?.feedback);
  if (feedback) return feedback;

  const actionType = block.actionType || 'command';
  if (actionType === 'navigate') {
    return `Navigate to ${getStringProp(block.props?.to) || '#'}`;
  }
  if (actionType === 'workflow') {
    return `Workflow started: ${getStringProp(block.props?.workflowKey) || 'workflow'}`;
  }
  if (actionType === 'modal' || actionType === 'drawer') {
    return `${actionType === 'modal' ? 'Modal' : 'Drawer'} opened`;
  }
  if (actionType === 'submit') {
    return getStringProp(block.props?.successMessage) || 'Submitted';
  }
  if (actionType === 'create') {
    return `Create via ${getStringProp(block.props?.openMode) || 'drawer'}`;
  }
  return `Command queued: ${getStringProp(block.props?.command) || 'command'}`;
}

export function getStringProp(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

export function getBooleanProp(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.toLowerCase() === 'true';
  return false;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

export function getAiSuggestedFields(value: unknown): Array<{ field: string; label: string; value: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const field =
      getStringProp(item.field) || getStringProp(item.fieldcode) || getStringProp(item.code);
    const label = getStringProp(item.label) || getStringProp(item.fieldlabel) || field;
    const rawValue =
      item.value ??
      item.suggestion ??
      item.suggestedValue ??
      item.suggestedvalue ??
      item.generatedValue ??
      item.generatedvalue;
    const displayValue = rawValue == null ? '' : String(rawValue);
    if (!field && !label && !displayValue) return [];
    return [{ field: field || label || `field_${displayValue}`, label, value: displayValue }];
  });
}

export function getBpmActions(value: unknown): Array<{ label: string; actionType: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const label = getStringProp(item.label) || getStringProp(item.name);
    const actionType =
      getStringProp(item.actionType) || getStringProp(item.actiontype) || getStringProp(item.type);
    if (!label) return [];
    return [{ label, actionType }];
  });
}

export function getTimelineItems(
  value: unknown,
): Array<{ actor: string; action: string; time: string; description: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const action = getStringProp(item.action) || getStringProp(item.title);
    if (!action) return [];
    return [
      {
        actor: getStringProp(item.actor) || getStringProp(item.user),
        action,
        time:
          getStringProp(item.time) ||
          getStringProp(item.createdAt) ||
          getStringProp(item.createdat),
        description: getStringProp(item.description) || getStringProp(item.detail),
      },
    ];
  });
}

export function getFieldHistoryEntries(
  value: unknown,
): Array<{ field: string; from: string; to: string; changedBy: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const field =
      getStringProp(item.field) || getStringProp(item.fieldcode) || getStringProp(item.label);
    if (!field) return [];
    return [
      {
        field,
        from: formatRuntimeCell(item.from ?? item.oldValue ?? item.oldvalue),
        to: formatRuntimeCell(item.to ?? item.newValue ?? item.newvalue),
        changedBy:
          getStringProp(item.changedBy) || getStringProp(item.changedby) || getStringProp(item.actor),
      },
    ];
  });
}

export function getSeries(value: unknown): Array<{ label: string; value: number }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const label = getStringProp(record.label);
    const rawValue = record.value;
    const numericValue =
      typeof rawValue === 'number' && Number.isFinite(rawValue) ? rawValue : Number(rawValue);
    if (!label || !Number.isFinite(numericValue)) return [];
    return [{ label, value: numericValue }];
  });
}

export function getNumberProp(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

// Maps a threshold band color name to an on-token bar fill class. Unknown / absent
// colors fall through to the accent blue used by the other mini-renderers.
const THRESHOLD_BAR_COLORS: Record<string, string> = {
  green: 'bg-emerald-500',
  emerald: 'bg-emerald-500',
  amber: 'bg-amber-500',
  yellow: 'bg-amber-500',
  red: 'bg-rose-500',
  rose: 'bg-rose-500',
  blue: 'bg-blue-500',
};

/**
 * Pick the progress bar fill from threshold bands. `thresholds` is the same
 * [{ value, color }] shape the number-card widget already authors; bands are
 * evaluated high→low and the first one the percentage meets (>=) wins. Returns
 * the accent blue when no band matches or thresholds are absent/invalid.
 */
export function pickThresholdColor(thresholds: unknown, percent: number): string {
  const fallback = 'bg-blue-500';
  if (!Array.isArray(thresholds)) return fallback;
  const bands = thresholds
    .flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const record = item as Record<string, unknown>;
      const at = getNumberProp(record.value);
      const color = getStringProp(record.color).toLowerCase();
      if (at === null || !color) return [];
      return [{ at, color }];
    })
    .sort((a, b) => b.at - a.at);
  for (const band of bands) {
    if (percent >= band.at) {
      return THRESHOLD_BAR_COLORS[band.color] ?? fallback;
    }
  }
  return fallback;
}

export function getStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => (typeof item === 'string' ? [item] : []));
}

export function getRows(value: unknown): string[][] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((row) => {
    if (!Array.isArray(row)) return [];
    return [row.map((cell) => String(cell))];
  });
}
