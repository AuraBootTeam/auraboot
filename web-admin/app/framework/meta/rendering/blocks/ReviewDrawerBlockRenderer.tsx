import React, { useEffect, useRef, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readPath,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';

export interface ReviewDrawerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

type Tone = 'green' | 'amber' | 'red' | 'blue' | 'purple' | 'gray';

type PointerState = {
  x: number;
  y: number;
  left?: number;
  top?: number;
  width?: number;
  height?: number;
};

type DrawerLayoutState = {
  left: number;
  top: number;
  width: number;
  height: number;
};

const DEFAULT_DRAWER_LAYOUT: DrawerLayoutState = {
  left: 24,
  top: 24,
  width: 1100,
  height: 640,
};

const MIN_DRAWER_WIDTH = 760;
const MIN_DRAWER_HEIGHT = 500;
const DRAWER_STORAGE_PREFIX = 'auraboot:review-drawer-layout';

const toneClass: Record<Tone, string> = {
  green: 'bg-status-green-bg text-status-green border-status-green',
  amber: 'bg-status-amber-bg text-status-amber border-status-amber',
  red: 'bg-status-red-bg text-status-red border-status-red',
  blue: 'bg-status-blue-bg text-status-blue border-status-blue',
  purple: 'bg-status-blue-bg text-status-blue border-status-blue',
  gray: 'bg-status-gray-bg text-status-gray border-status-gray',
};

const buttonClass: Record<string, string> = {
  primary: 'bg-accent text-white hover:opacity-90',
  secondary: 'border border-border bg-panel text-text hover:bg-hover',
  danger: 'bg-status-red text-white hover:opacity-90',
};

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function getViewportSize(): { width: number; height: number } {
  if (typeof window === 'undefined') return { width: 1280, height: 720 };
  return {
    width: window.innerWidth || 1280,
    height: window.innerHeight || 720,
  };
}

function normalizeDrawerLayout(layout: DrawerLayoutState): DrawerLayoutState {
  const viewport = getViewportSize();
  const maxWidth = Math.max(320, viewport.width - 24);
  const maxHeight = Math.max(320, viewport.height - 24);
  const minWidth = Math.min(MIN_DRAWER_WIDTH, maxWidth);
  const minHeight = Math.min(MIN_DRAWER_HEIGHT, maxHeight);
  const width = clamp(layout.width, minWidth, maxWidth);
  const height = clamp(layout.height, minHeight, maxHeight);
  return {
    width,
    height,
    left: clamp(layout.left, 12, Math.max(12, viewport.width - 180)),
    top: clamp(layout.top, 12, Math.max(12, viewport.height - 84)),
  };
}

function isDrawerLayoutState(value: unknown): value is DrawerLayoutState {
  if (!value || typeof value !== 'object') return false;
  const layout = value as Record<string, unknown>;
  return ['left', 'top', 'width', 'height'].every((key) => Number.isFinite(layout[key]));
}

function drawerLayoutStorageKey(
  runtime: SchemaRuntime,
  block: BlockConfig,
  context: Record<string, any>,
): string {
  const schema = (runtime as any).getSchema?.() || {};
  const pageKey =
    context?.$page?.pageKey ||
    context?.$page?.id ||
    schema.pageKey ||
    schema.id ||
    schema.modelCode ||
    'global';
  const modelKey = context?.$page?.modelCode || schema.modelCode || 'model';
  const blockKey = block.id || block.blockType || 'review-drawer';
  return `${DRAWER_STORAGE_PREFIX}:${modelKey}:${pageKey}:${blockKey}`;
}

function readStoredDrawerLayout(storageKey: string): DrawerLayoutState {
  if (typeof window === 'undefined') return DEFAULT_DRAWER_LAYOUT;
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return DEFAULT_DRAWER_LAYOUT;
    const parsed = JSON.parse(raw);
    if (!isDrawerLayoutState(parsed)) return DEFAULT_DRAWER_LAYOUT;
    return normalizeDrawerLayout(parsed);
  } catch {
    return DEFAULT_DRAWER_LAYOUT;
  }
}

function persistDrawerLayout(storageKey: string, layout: DrawerLayoutState): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(normalizeDrawerLayout(layout)));
  } catch {
    // localStorage may be unavailable in private browsing or strict test environments.
  }
}

function parseJsonValue(value: unknown): unknown {
  let current = value;
  for (let depth = 0; depth < 4; depth += 1) {
    if (
      current &&
      typeof current === 'object' &&
      !Array.isArray(current) &&
      ['json', 'jsonb'].includes(
        String((current as Record<string, unknown>).type || '').toLowerCase(),
      ) &&
      Object.prototype.hasOwnProperty.call(current, 'value')
    ) {
      current = (current as Record<string, unknown>).value;
      continue;
    }
    if (typeof current !== 'string') return current;
    const trimmed = current.trim();
    if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return current;
    try {
      current = JSON.parse(trimmed);
    } catch {
      return current;
    }
  }
  return current;
}

function formatValue(value: unknown, emptyText = '-'): string {
  if (value === undefined || value === null || value === '') return emptyText;
  if (Array.isArray(value) && value.length === 0) return emptyText;
  if (Array.isArray(value) && value.every((item) => typeof item !== 'object' || item === null)) {
    return value.map((item) => formatValue(item, emptyText)).join(', ');
  }
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

function applyValueMap(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): unknown {
  const valueMap = config?.valueMap;
  if (!valueMap || typeof valueMap !== 'object') return value;
  const key = String(value ?? '');
  if (!Object.prototype.hasOwnProperty.call(valueMap, key)) return value;
  return getLocalizedText(valueMap[key], locale, t);
}

function formatConfiguredValue(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): string {
  const mappedValue = applyValueMap(value, config, locale, t);
  if (config?.format === 'percent' && !isEmptyValue(mappedValue)) {
    const numeric = typeof mappedValue === 'number' ? mappedValue : Number(mappedValue);
    if (Number.isFinite(numeric)) {
      const percent = Math.abs(numeric) <= 1 ? numeric * 100 : numeric;
      return `${Number(percent.toFixed(2)).toString()}%`;
    }
  }
  return formatValue(mappedValue, config?.emptyText);
}

function isEmptyValue(value: unknown): boolean {
  return value === undefined || value === null || value === '';
}

/** One rung of a supplier price ladder, as projected by the named query. */
interface LadderRung {
  qty: string | number;
  price: string | number;
  current?: boolean;
}

/**
 * Parses the ladder array off a field value. Depending on the NamedQuery driver it can arrive as
 * an array, a JSON string, or the platform's typed JSONB envelope.
 */
export function parseLadderRungs(value: unknown): LadderRung[] | null {
  const raw = parseJsonValue(value);
  if (!Array.isArray(raw) || raw.length === 0) return null;
  const rungs = raw.filter(
    (item): item is LadderRung =>
      !!item && typeof item === 'object' && 'qty' in item && 'price' in item,
  );
  return rungs.length > 0 ? rungs : null;
}

/**
 * An http(s) URL safe to put in an href, or null. Evidence snapshots carry supplier detail links
 * copied verbatim from an upstream API, so the scheme is checked rather than assumed — javascript:
 * and data: never reach the DOM.
 */
export function safeExternalUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    const url = new URL(trimmed);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

function readFieldValue(record: any, config: any, fallbackRecord?: any): unknown {
  if (Object.prototype.hasOwnProperty.call(config, 'value')) return config.value;
  const source = config.sourceField ? readPath(record, config.sourceField) : record;
  const value = readPath(parseJsonValue(source), config.field || config.valueField);
  if (!isEmptyValue(value) || !config.fallbackField || !fallbackRecord) return value;
  const fallbackSource = config.fallbackSourceField
    ? readPath(fallbackRecord, config.fallbackSourceField)
    : fallbackRecord;
  return readPath(parseJsonValue(fallbackSource), config.fallbackField);
}

// Reference/lookup fields carry a resolved `<field>_display` sibling on the record (the backend's
// generic GAP-124 reference enrichment, applied on both list and getById reads). When a detail field
// has no explicit valueMap and is read directly (no sourceField), prefer that display name so the
// drawer shows names instead of raw pids/ULIDs. Purely additive: falls back to the configured value
// when no `_display` sibling exists, so non-reference fields are unchanged.
function resolveDisplayValue(
  record: any,
  config: any,
  locale: string,
  t: (key: string) => string,
): string {
  const field = config?.field || config?.valueField;
  if (field && !config?.valueMap && !config?.sourceField && record && typeof record === 'object') {
    const display = record[`${field}_display`];
    if (display !== undefined && display !== null && display !== '') {
      return formatValue(display, config?.emptyText);
    }
  }
  return formatConfiguredValue(readFieldValue(record, config), config, locale, t);
}

function isComparisonRecord(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  return (
    Object.prototype.hasOwnProperty.call(value, 'status') ||
    Object.prototype.hasOwnProperty.call(value, 'sourceValue') ||
    Object.prototype.hasOwnProperty.call(value, 'candidateValue') ||
    Object.prototype.hasOwnProperty.call(value, 'reason')
  );
}

function isComparisonList(value: unknown): value is Record<string, unknown>[] {
  return Array.isArray(value) && value.length > 0 && value.every(isComparisonRecord);
}

function comparisonStatusLabel(
  status: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  const text = String(status ?? '');
  const labels: Record<string, { 'zh-CN': string; en: string }> = {
    matched: { 'zh-CN': '一致', en: 'Matched' },
    mismatch: { 'zh-CN': '不一致', en: 'Mismatch' },
    missing_source: { 'zh-CN': '原始缺失', en: 'Source Missing' },
    missing_candidate: { 'zh-CN': '候选缺失', en: 'Candidate Missing' },
    missing_both: { 'zh-CN': '双方缺失', en: 'Both Missing' },
  };
  return getLocalizedText(labels[text] || text, locale, t);
}

function comparisonStatusClass(status: unknown): string {
  switch (String(status ?? '')) {
    case 'matched':
      return 'border-status-green bg-status-green-bg text-status-green';
    case 'mismatch':
      return 'border-status-red bg-status-red-bg text-status-red';
    case 'missing_source':
    case 'missing_candidate':
    case 'missing_both':
      return 'border-status-amber bg-status-amber-bg text-status-amber';
    default:
      return 'border-status-gray bg-status-gray-bg text-status-gray';
  }
}

function ComparisonList({
  value,
  locale,
  t,
}: {
  value: Record<string, unknown>[];
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div className="space-y-2">
      {value.map((comparison, index) => {
        const key = formatValue(comparison.label ?? comparison.key, `#${index + 1}`);
        const sourceValue = formatValue(comparison.sourceValue, '');
        const candidateValue = formatValue(comparison.candidateValue, '');
        const reason = formatValue(comparison.reason, '');
        return (
          <section
            key={`${key}-${index}`}
            data-testid={`review-drawer-comparison-${index}`}
            className="rounded-control border-border bg-panel overflow-hidden border"
          >
            <div className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-2.5 py-1.5">
              <span className="text-text min-w-0 text-xs font-semibold break-words">{key}</span>
              <span
                className={`rounded-pill shrink-0 border px-2 py-0.5 text-[11px] font-semibold ${comparisonStatusClass(
                  comparison.status,
                )}`}
              >
                {comparisonStatusLabel(comparison.status, locale, t)}
              </span>
            </div>
            <div className="grid gap-2 px-2.5 py-2 text-xs md:grid-cols-2">
              <div className="min-w-0">
                <div className="text-text-2 font-medium">
                  {localized(locale, t, '原始', 'Source')}
                </div>
                <div className="text-text mt-0.5 break-words">{sourceValue || '-'}</div>
              </div>
              <div className="min-w-0">
                <div className="text-text-2 font-medium">
                  {localized(locale, t, '候选', 'Candidate')}
                </div>
                <div className="text-text mt-0.5 break-words">{candidateValue || '-'}</div>
              </div>
              {reason && (
                <div className="text-text-2 min-w-0 break-words md:col-span-2">
                  {localized(locale, t, '原因', 'Reason')}: {reason}
                </div>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function findRelatedRecord(runtime: SchemaRuntime, config: any, selectedRecord: any): any {
  if (!config?.dataSource) return selectedRecord;
  const rows = readDataSourceRows(runtime, config.dataSource);
  if (rows.length === 0) return {};
  const recordField = config.recordField || config.selectedField;
  const matchField = config.matchField;
  if (!recordField || !matchField) return rows[0] || {};
  const expected = readPath(selectedRecord, recordField);
  if (expected === undefined || expected === null || String(expected) === '') return rows[0] || {};
  return rows.find((row: any) => String(readPath(row, matchField)) === String(expected)) || {};
}

// Collects the block's own field configs (summaryBadges + source.summary.items) keyed by their
// field/valueField path, so titleTemplate substitutions can resolve dict/valueMap labels instead of
// leaking raw enum codes. Purely additive: paths without a configured valueMap fall back to the raw
// value, matching the pre-existing behaviour.
function buildTemplateFieldConfigs(block: any): Map<string, any> {
  const map = new Map<string, any>();
  const add = (field: unknown, config: any) => {
    if (typeof field === 'string' && field && !map.has(field)) map.set(field, config);
  };
  const badges = Array.isArray(block?.summaryBadges) ? block.summaryBadges : [];
  for (const badge of badges) add(badge?.valueField, badge);
  const items = Array.isArray(block?.source?.summary?.items) ? block.source.summary.items : [];
  for (const item of items) add(item?.field, item);
  return map;
}

function fillTemplate(
  template: string,
  runtime: SchemaRuntime,
  record: any,
  fieldConfigs: Map<string, any>,
  locale: string,
  t: (key: string) => string,
): string {
  return template.replace(/\$\{([^}]+)\}/g, (_match, expression: string) => {
    const path = String(expression).trim();
    if (path.startsWith('record.')) {
      const fieldPath = path.slice(7);
      const value = readPath(record, fieldPath);
      const config = fieldConfigs.get(fieldPath);
      return config ? formatConfiguredValue(value, config, locale, t) : formatValue(value, '');
    }
    return formatValue(readPath(runtime.getContext(), path), '');
  });
}

function stateBindingFromExpression(expression: unknown): string | undefined {
  if (typeof expression !== 'string') return undefined;
  const match = expression.trim().match(/^\$\{state\.([A-Za-z0-9_]+)\}$/);
  return match?.[1];
}

function sectionLabel(config: any, locale: string, t: (key: string) => string, fallback: string) {
  return getLocalizedText(config?.label || config?.title || fallback, locale, t);
}

function localized(locale: string, t: (key: string) => string, zh: string, en: string) {
  return getLocalizedText({ 'zh-CN': zh, en }, locale, t);
}

function priceFactorPercent(value: unknown): number {
  const normalized = String(value ?? '')
    .trim()
    .replace(/%$/, '');
  const parsed = Number(normalized);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 100;
}

function formatUnitPrice(value: unknown): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return formatValue(value);
  return parsed.toFixed(4);
}

function factoredUnitPrice(value: unknown, factor: unknown): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return formatValue(value);
  return ((parsed * priceFactorPercent(factor)) / 100).toFixed(4);
}

function PriceComparison({
  original,
  factored,
  factor,
  locale,
  t,
}: {
  original: unknown;
  factored: unknown;
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  const factorText = `${Number(priceFactorPercent(factor).toFixed(2))}%`;
  return (
    <div
      data-testid="review-drawer-price-comparison"
      className="rounded-control border-border bg-subtle grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-stretch gap-2 border p-2"
    >
      <div className="min-w-0 px-1">
        <div className="text-text-2 text-[11px]">
          {localized(locale, t, '原始单价', 'Supplier price')}
        </div>
        <div className="text-text mt-1 font-mono text-sm font-medium tabular-nums">
          {formatUnitPrice(original)}
        </div>
      </div>
      <div className="text-text-2 flex flex-col items-center justify-center gap-1 px-1">
        <span className="rounded-pill border-border bg-panel border px-2 py-0.5 text-[10px] font-semibold whitespace-nowrap">
          × {factorText}
        </span>
        <span aria-hidden="true">→</span>
      </div>
      <div className="border-accent bg-accent-weak min-w-0 rounded border px-2 py-1.5">
        <div className="text-accent text-[11px] font-medium">
          {localized(locale, t, '系数后单价', 'After factor')}
        </div>
        <div className="text-text mt-1 font-mono text-base font-semibold tabular-nums">
          {formatUnitPrice(factored)}
        </div>
      </div>
    </div>
  );
}

function PriceLadder({
  rowKey,
  rungs,
  factor,
  locale,
  t,
}: {
  rowKey: string;
  rungs: LadderRung[];
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  const factorText = `${Number(priceFactorPercent(factor).toFixed(2))}%`;
  return (
    <div
      className="border-border bg-subtle rounded-control w-full overflow-hidden border text-xs tabular-nums"
      data-testid={`review-drawer-candidate-${rowKey}-ladder`}
    >
      <div className="text-text-2 border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] border-b px-2 py-1.5 text-[11px] font-medium">
        <span>{localized(locale, t, '数量', 'Qty')}</span>
        <span className="text-right">{localized(locale, t, '原始单价', 'Original')}</span>
        <span className="text-right">
          {localized(locale, t, '系数后', 'After factor')} ({factorText})
        </span>
      </div>
      {rungs.map((rung) => (
        <div
          key={String(rung.qty)}
          data-testid={rung.current ? `review-drawer-ladder-current-${rowKey}` : undefined}
          className={`border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] items-center border-b px-2 py-1.5 last:border-b-0 ${
            rung.current ? 'bg-accent-weak text-text font-semibold' : 'text-text-2'
          }`}
        >
          <span className="flex items-center gap-1.5 whitespace-nowrap">
            {String(rung.qty)}+
            {rung.current && (
              <span className="rounded-pill bg-accent px-1.5 py-0.5 text-[10px] font-semibold text-white">
                {localized(locale, t, '当前', 'Current')}
              </span>
            )}
          </span>
          <span className="text-right font-mono whitespace-nowrap">
            {formatUnitPrice(rung.price)}
          </span>
          <span className="text-text text-right font-mono whitespace-nowrap">
            {factoredUnitPrice(rung.price, factor)}
          </span>
        </div>
      ))}
    </div>
  );
}

function candidateKey(candidate: any, index: number): string {
  return String(candidate?.pid ?? candidate?.id ?? index);
}

function scoreToneClass(color: unknown): string {
  switch (String(color ?? '').toLowerCase()) {
    case 'green':
      return 'bg-status-green-bg text-status-green';
    case 'yellow':
    case 'amber':
      return 'bg-status-amber-bg text-status-amber';
    case 'red':
      return 'bg-status-red-bg text-status-red';
    default:
      return 'bg-status-green-bg text-status-green';
  }
}

function Badge({
  badge,
  record,
  locale,
  t,
}: {
  badge: any;
  record: any;
  locale: string;
  t: (key: string) => string;
}) {
  const key = String(badge.key || badge.valueField || badge.label);
  const label = getLocalizedText(badge.label || key, locale, t);
  const value = readFieldValue(record, badge);
  const text = `${label} ${formatConfiguredValue(value, badge, locale, t)}${badge.unit ? String(badge.unit) : ''}`;
  const tone = (badge.tone || 'gray') as Tone;

  return (
    <span
      data-testid={`review-drawer-badge-${key}`}
      title={text}
      className={`rounded-pill inline-flex max-w-full truncate border px-2.5 py-1 text-xs font-semibold ${
        toneClass[tone] || toneClass.gray
      }`}
    >
      {text}
    </span>
  );
}

function FieldRows({
  fields,
  record,
  fallbackRecord,
  locale,
  t,
  layout = 'rows',
}: {
  fields: any[];
  record: any;
  fallbackRecord?: any;
  locale: string;
  t: (key: string) => string;
  layout?: 'rows' | 'compact-grid';
}) {
  const isCompactGrid = layout === 'compact-grid';

  return (
    <div
      data-testid={isCompactGrid ? 'review-drawer-preview-field-grid' : undefined}
      className={
        isCompactGrid
          ? 'bg-border grid grid-cols-1 gap-px sm:grid-cols-2 xl:grid-cols-4'
          : 'divide-border divide-y'
      }
    >
      {fields.map((field) => {
        const key = String(field.key || field.field || field.label);
        const label = getLocalizedText(field.label || key, locale, t);
        const rawValue = readFieldValue(record, field, fallbackRecord);
        if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
        const mappedValue = applyValueMap(rawValue, field, locale, t);
        const rendersComparisons = isComparisonList(mappedValue);
        const value = rendersComparisons ? '' : formatConfiguredValue(rawValue, field, locale, t);
        const isMultiline = value.includes('\n') || value.length > 86;
        const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
        const externalUrl = field.format === 'link' ? safeExternalUrl(rawValue) : null;
        const fieldValue =
          field.format === 'price-comparison' ? (
            <PriceComparison
              original={rawValue}
              factored={readFieldValue(record, { field: field.factoredField })}
              factor={readFieldValue(record, { field: field.factorField })}
              locale={locale}
              t={t}
            />
          ) : ladder ? (
            <PriceLadder
              rowKey={String(field.rowKey || key)}
              rungs={ladder}
              factor={readFieldValue(record, { field: field.factorField })}
              locale={locale}
              t={t}
            />
          ) : externalUrl ? (
            <a
              href={externalUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-accent underline underline-offset-2"
              data-testid={`review-drawer-field-link-${key}`}
            >
              {getLocalizedText(field.linkLabel || { 'zh-CN': '查看', en: 'Open' }, locale, t)}
            </a>
          ) : rendersComparisons ? (
            <ComparisonList value={mappedValue} locale={locale} t={t} />
          ) : (
            value
          );
        if (isCompactGrid) {
          const requestedSpan = Number(field.gridSpan);
          const spanClass =
            requestedSpan === 4 || field.format === 'price-comparison' || field.format === 'ladder'
              ? 'sm:col-span-2 xl:col-span-4'
              : requestedSpan === 3
                ? 'sm:col-span-2 xl:col-span-3'
                : requestedSpan === 2
                  ? 'sm:col-span-2 xl:col-span-2'
                  : '';
          return (
            <div
              key={key}
              data-testid={`review-drawer-preview-field-${key}`}
              className={`bg-panel min-w-0 px-3 py-2 text-sm ${spanClass}`}
            >
              <dt className="text-text-2 mb-1 text-[11px] font-medium">{label}</dt>
              <dd
                className={`text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words ${
                  isMultiline ? 'whitespace-pre-wrap' : ''
                }`}
              >
                {fieldValue}
              </dd>
            </div>
          );
        }
        return (
          <div key={key} className="grid grid-cols-[118px_minmax(0,1fr)] gap-3 px-3 py-2.5 text-sm">
            <dt className="text-text-2 text-xs">{label}</dt>
            <dd
              className={`text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words ${
                isMultiline ? 'whitespace-pre-wrap' : ''
              }`}
            >
              {fieldValue}
            </dd>
          </div>
        );
      })}
    </div>
  );
}

function FieldGroups({
  groups,
  record,
  fallbackRecord,
  locale,
  t,
}: {
  groups: any[];
  record: any;
  fallbackRecord?: any;
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div className="space-y-3 p-3">
      {groups.map((group, index) => {
        const key = String(group.key || group.code || group.id || index);
        const label = getLocalizedText(group.label || group.title || key, locale, t);
        const fields = Array.isArray(group.fields) ? group.fields : [];
        if (fields.length === 0) return null;
        return (
          <section
            key={key}
            data-testid={`review-drawer-selected-group-${key}`}
            className="rounded-control border-border bg-subtle overflow-hidden border"
          >
            <header className="border-border bg-panel text-text border-b px-3 py-2 text-xs font-semibold">
              {label}
            </header>
            <FieldRows
              fields={fields}
              record={record}
              fallbackRecord={fallbackRecord}
              locale={locale}
              t={t}
            />
          </section>
        );
      })}
    </div>
  );
}

/**
 * An optional inline edit form at the top of the drawer.
 *
 * This table opens the drawer on a single row click, so double-click-to-edit in the grid fights it
 * — a dblclick is two clicks, each of which opens the drawer first. Editing lives here instead,
 * beside the line's own context, which is also where a reviewer who spotted a wrong value is
 * already looking. Fields are declared on the block; submitting runs a command with the collected
 * values, so a corrected part number or per-set usage re-prices without leaving the panel.
 */
function DrawerEditForm({
  config,
  record,
  runtime,
  locale,
  t,
  onOpenChange,
}: {
  config: any;
  record: any;
  runtime: SchemaRuntime;
  locale: string;
  t: (key: string) => string;
  onOpenChange?: (open: boolean) => void;
}) {
  const fields: any[] = Array.isArray(config?.fields) ? config.fields : [];
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<Record<string, any> | null>(null);

  const isTwoPhase = Boolean(config?.previewCommand && config?.confirmCommand);
  if (fields.length === 0 || (!config?.command && !isTwoPhase))
    return <div data-testid="review-drawer-edit-form-empty" />;
  const recordPid = record ? String(record.pid ?? '') : '';
  const disabled = !recordPid;

  function begin() {
    const seed: Record<string, string> = {};
    for (const f of fields) {
      const raw = Object.prototype.hasOwnProperty.call(f, 'defaultValue')
        ? f.defaultValue
        : readPath(record, f.valueField || f.field);
      seed[f.field] = raw === undefined || raw === null ? '' : String(raw);
    }
    if (isTwoPhase && config.modeField && config.searchField && config.modeValueFields) {
      let mode =
        seed[config.modeField] ||
        String(fields.find((field: any) => field.field === config.modeField)?.defaultValue || '');
      const exactValue = readPath(record, config.modeValueFields.exact);
      if (
        mode === 'exact' &&
        (exactValue === undefined || exactValue === null || exactValue === '')
      ) {
        mode = 'spec';
      }
      seed[config.modeField] = mode;
      const sourceField = config.modeValueFields[mode];
      const searchValue = sourceField ? readPath(record, sourceField) : '';
      seed[config.searchField] =
        searchValue === undefined || searchValue === null ? '' : String(searchValue);
    }
    setValues(seed);
    setError(null);
    setPreview(null);
    setOpen(true);
    onOpenChange?.(true);
  }

  async function submit() {
    // A field marked required must not be cleared: a non-standard BOM's description is Yunhan's
    // search key, so submitting it blank would re-price against nothing.
    const missing = fields.find((f: any) => f.required && (values[f.field] ?? '').trim() === '');
    if (missing) {
      setError(
        getLocalizedText(
          missing.requiredMessage || { 'zh-CN': '规格描述不能为空', en: 'Description is required' },
          locale,
          t,
        ),
      );
      return;
    }
    setSaving(true);
    setError(null);
    const payload: Record<string, string> = {};
    for (const f of fields) {
      const v = (values[f.field] ?? '').trim();
      if (v !== '') payload[f.field] = v;
    }
    try {
      const result = await executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: isTwoPhase ? config.previewCommand : config.command,
          targetRecordPid: recordPid,
          payload,
          reload: isTwoPhase ? [] : Array.isArray(config.reload) ? config.reload : [],
        },
      });
      if (isTwoPhase) {
        if (!result || typeof result !== 'object') {
          throw new Error(
            getLocalizedText(
              { 'zh-CN': '查价未返回可预览结果', en: 'The price search returned no preview' },
              locale,
              t,
            ),
          );
        }
        setPreview(result as Record<string, any>);
      } else {
        setOpen(false);
        onOpenChange?.(false);
      }
    } catch (e: any) {
      setError(e?.message || String(e));
    } finally {
      setSaving(false);
    }
  }

  async function confirmPreview() {
    if (!preview || !isTwoPhase) return;
    const previewIdField = config.preview?.previewIdField || 'previewId';
    const previewId = readPath(preview, previewIdField);
    if (!previewId) {
      setError(
        getLocalizedText(
          { 'zh-CN': '查价预览缺少编号，请重新查价', en: 'Preview id is missing; search again' },
          locale,
          t,
        ),
      );
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const result = await executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: config.confirmCommand,
          targetRecordPid: recordPid,
          payload: { previewId },
          reload: Array.isArray(config.reload) ? config.reload : [],
        },
      });
      for (const selection of Array.isArray(config.afterConfirmSelections)
        ? config.afterConfirmSelections
        : []) {
        const resultKey = readPath(result, selection.resultField);
        const row = readDataSourceRows(runtime, selection.dataSource).find(
          (candidate: any) =>
            String(readPath(candidate, selection.keyField || 'pid')) === String(resultKey),
        );
        if (row && selection.bind) writeRuntimeState(runtime, selection.bind, row);
      }
      setPreview(null);
      setOpen(false);
      onOpenChange?.(false);
    } catch (e: any) {
      setError(e?.message || String(e));
    } finally {
      setSaving(false);
    }
  }

  function cancel() {
    setPreview(null);
    setError(null);
    setOpen(false);
    onOpenChange?.(false);
  }

  function updateValue(key: string, value: string) {
    setValues((previous) => {
      const next = { ...previous, [key]: value };
      if (
        isTwoPhase &&
        key === config.modeField &&
        config.searchField &&
        config.modeValueFields?.[value]
      ) {
        const raw = readPath(record, config.modeValueFields[value]);
        next[config.searchField] = raw === undefined || raw === null ? '' : String(raw);
      }
      return next;
    });
    if (error) setError(null);
  }

  const previewConfig = config?.preview || {};
  const previewFields: any[] = Array.isArray(previewConfig.fields) ? previewConfig.fields : [];
  const confirmable = preview
    ? Boolean(readPath(preview, previewConfig.confirmableField || 'confirmable'))
    : false;

  return (
    <div
      data-testid="review-drawer-edit-form"
      className={`border-border bg-panel min-h-0 border-b ${
        open ? 'flex h-full flex-col overflow-hidden' : 'px-4 py-2'
      }`}
    >
      {!open ? (
        <button
          type="button"
          data-testid="review-drawer-edit-open"
          disabled={disabled}
          onClick={begin}
          className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50"
        >
          {getLocalizedText(
            config.openLabel || { 'zh-CN': '编辑此行并重新查价', en: 'Edit this row' },
            locale,
            t,
          )}
        </button>
      ) : preview ? (
        <div
          data-testid="review-drawer-edit-preview"
          className="flex h-full min-h-0 flex-col overflow-hidden"
        >
          <div
            data-testid="review-drawer-edit-scroll"
            className="min-h-0 flex-1 overflow-y-auto p-3"
          >
            <section className="rounded-control border-border bg-subtle overflow-hidden border">
              <header className="border-border bg-panel text-text border-b px-3 py-2 text-sm font-semibold">
                {getLocalizedText(
                  previewConfig.title || { 'zh-CN': '查价预览', en: 'Price Preview' },
                  locale,
                  t,
                )}
              </header>
              {previewConfig.notice && (
                <div
                  data-testid="review-drawer-edit-preview-notice"
                  className="border-border bg-accent-weak text-text-2 border-b px-3 py-2 text-xs"
                >
                  {getLocalizedText(previewConfig.notice, locale, t)}
                </div>
              )}
              {previewFields.length > 0 && (
                <FieldRows
                  fields={previewFields}
                  record={preview}
                  locale={locale}
                  t={t}
                  layout={previewConfig.layout}
                />
              )}
              {readPath(preview, previewConfig.messageField || 'message') && (
                <div
                  data-testid="review-drawer-edit-preview-message"
                  className="border-border text-text-2 border-t px-3 py-2 text-sm"
                >
                  {String(readPath(preview, previewConfig.messageField || 'message'))}
                </div>
              )}
            </section>
            {error && (
              <div data-testid="review-drawer-edit-error" className="text-status-red mt-2 text-xs">
                {error}
              </div>
            )}
          </div>
          <div
            data-testid="review-drawer-edit-actions"
            className="border-border bg-panel flex shrink-0 flex-wrap justify-end gap-2 border-t px-4 py-2.5"
          >
            <button
              type="button"
              data-testid="review-drawer-edit-cancel"
              onClick={cancel}
              className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
            >
              {t('common.cancel') || 'Cancel'}
            </button>
            <button
              type="button"
              data-testid="review-drawer-edit-back"
              onClick={() => {
                setPreview(null);
                setError(null);
              }}
              className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
            >
              {getLocalizedText(
                config.backLabel || { 'zh-CN': '返回修改', en: 'Edit search' },
                locale,
                t,
              )}
            </button>
            {confirmable && (
              <button
                type="button"
                data-testid="review-drawer-edit-confirm"
                disabled={saving}
                onClick={() => void confirmPreview()}
                className="rounded-control bg-accent px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
              >
                {saving
                  ? t('common.loading')
                  : getLocalizedText(
                      config.confirmLabel || { 'zh-CN': '确认并采用', en: 'Confirm and adopt' },
                      locale,
                      t,
                    )}
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className="flex h-full min-h-0 flex-col overflow-hidden">
          <div
            data-testid="review-drawer-edit-scroll"
            className="min-h-0 flex-1 overflow-y-auto px-4 py-3"
          >
            <div className="flex flex-wrap gap-3">
              {fields.map((f: any) => {
                const key = String(f.field);
                const label = getLocalizedText(f.label || key, locale, t);
                if (f.type === 'radio') {
                  return (
                    <fieldset
                      key={key}
                      data-testid={`review-drawer-edit-field-${key}`}
                      className="text-text-2 min-w-[220px] text-xs"
                    >
                      <legend>
                        {label}
                        {f.required && <span className="text-status-red ml-0.5">*</span>}
                      </legend>
                      <div className="mt-1 flex flex-wrap gap-3">
                        {(Array.isArray(f.options) ? f.options : []).map((option: any) => (
                          <label
                            key={String(option.value)}
                            className="text-text flex items-center gap-1.5"
                          >
                            <input
                              type="radio"
                              name={`review-drawer-edit-${key}`}
                              value={String(option.value)}
                              checked={values[key] === String(option.value)}
                              onChange={(event) => updateValue(key, event.target.value)}
                            />
                            <span>{getLocalizedText(option.label || option.value, locale, t)}</span>
                          </label>
                        ))}
                      </div>
                    </fieldset>
                  );
                }
                return (
                  <label
                    key={key}
                    data-testid={`review-drawer-edit-field-${key}`}
                    className="text-text-2 flex min-w-[160px] flex-1 flex-col gap-1 text-xs"
                  >
                    <span>
                      {label}
                      {f.required && <span className="text-status-red ml-0.5">*</span>}
                    </span>
                    <input
                      className="rounded-control border-border bg-panel text-text border px-2 py-1 text-sm"
                      value={values[key] ?? ''}
                      inputMode={f.type === 'number' ? 'numeric' : undefined}
                      placeholder={
                        f.placeholder ? getLocalizedText(f.placeholder, locale, t) : undefined
                      }
                      onChange={(e) => updateValue(key, e.target.value)}
                    />
                  </label>
                );
              })}
            </div>
            {error && (
              <div data-testid="review-drawer-edit-error" className="text-status-red mt-2 text-xs">
                {error}
              </div>
            )}
          </div>
          <div
            data-testid="review-drawer-edit-actions"
            className="border-border bg-panel flex shrink-0 justify-end gap-2 border-t px-4 py-2.5"
          >
            <button
              type="button"
              data-testid="review-drawer-edit-cancel"
              onClick={cancel}
              className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
            >
              {t('common.cancel') || 'Cancel'}
            </button>
            <button
              type="button"
              data-testid="review-drawer-edit-submit"
              disabled={saving}
              onClick={() => void submit()}
              className="rounded-control bg-accent px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              {saving
                ? t('common.loading')
                : getLocalizedText(
                    config.submitLabel || { 'zh-CN': '保存并重新查价', en: 'Save and re-price' },
                    locale,
                    t,
                  )}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export const ReviewDrawerBlockRenderer: React.FC<ReviewDrawerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const contextExpression = (block as any).context;
  const contextRecord = resolveRuntimeValue(runtime, contextExpression);
  const compareConfig = (block as any).compare || {};
  const candidatesConfig = (block as any).candidates || {};
  const hasCandidatesConfig = Boolean((block as any).candidates);
  const exportConfig = (block as any).exportImpact || {};
  const sourceConfig = (block as any).source || {};
  const sourceRecordConfig = sourceConfig.record || {};
  const contextDataSource = (block as any).contextDataSource;
  const contextKeyField = (block as any).contextKeyField || 'pid';
  const closeClearsContext = (block as any).closeClearsContext !== false;
  const rawRecordConfig = compareConfig.rawRecord || {};
  const canonicalRecordConfig = compareConfig.canonicalRecord || {};
  const rawDataSource = rawRecordConfig.dataSource;
  const canonicalDataSource = canonicalRecordConfig.dataSource;
  const sourceDataSource = sourceRecordConfig.dataSource;
  const candidateDataSource = candidatesConfig.dataSource;
  const exportDataSource = exportConfig.dataSource;
  const contextStateBinding = stateBindingFromExpression(contextExpression);
  const selectedCandidateFields = Array.isArray(candidatesConfig.selectedFields)
    ? candidatesConfig.selectedFields
    : [];
  const selectedCandidateGroups = Array.isArray(candidatesConfig.selectedGroups)
    ? candidatesConfig.selectedGroups
    : Array.isArray(candidatesConfig.groups)
      ? candidatesConfig.groups
      : [];
  const layoutStorageKey = drawerLayoutStorageKey(runtime, block, context);
  const initialLayoutRef = useRef<DrawerLayoutState | null>(null);
  if (initialLayoutRef.current === null) {
    initialLayoutRef.current = readStoredDrawerLayout(layoutStorageKey);
  }
  const initialLayout = initialLayoutRef.current;

  const [selectedCandidateKey, setSelectedCandidateKey] = useState('');
  const [dismissedRecordKey, setDismissedRecordKey] = useState('');
  const [isMaximized, setIsMaximized] = useState(false);
  const [isEditFormOpen, setIsEditFormOpen] = useState(false);
  const [position, setPosition] = useState({
    left: initialLayout.left,
    top: initialLayout.top,
  });
  const [size, setSize] = useState({
    width: initialLayout.width,
    height: initialLayout.height,
  });
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const dragRef = useRef<PointerState | null>(null);
  const resizeRef = useRef<PointerState | null>(null);
  const layoutRef = useRef<DrawerLayoutState>({
    left: initialLayout.left,
    top: initialLayout.top,
    width: initialLayout.width,
    height: initialLayout.height,
  });

  useRuntimeStateSubscription(runtime);
  useDataSourceSubscription(runtime, contextDataSource);
  useDataSourceSubscription(runtime, rawDataSource);
  useDataSourceSubscription(runtime, canonicalDataSource);
  useDataSourceSubscription(runtime, sourceDataSource);
  useDataSourceSubscription(runtime, candidateDataSource);
  useDataSourceSubscription(runtime, exportDataSource);

  const contextRows = readDataSourceRows(runtime, contextDataSource);
  const contextRecordKey = readPath(contextRecord, contextKeyField);
  const record =
    contextDataSource && contextRecordKey !== undefined && contextRecordKey !== null
      ? contextRows.find(
          (row: any) => String(readPath(row, contextKeyField)) === String(contextRecordKey),
        ) || contextRecord
      : contextRecord;
  const selectedRecordKey = record ? String(record.pid ?? record.bom_std_row_no ?? '') : '';
  const candidates = readDataSourceRows(runtime, candidateDataSource);
  const exportRows = readDataSourceRows(runtime, exportDataSource);
  const selectedCandidate = candidates.find((row: any, index: number) => {
    return candidateKey(row, index) === selectedCandidateKey;
  });

  useEffect(() => {
    setSelectedCandidateKey('');
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
  }, [selectedRecordKey, candidatesConfig.selection?.bind, runtime]);

  useEffect(() => {
    if (!selectedRecordKey) {
      setDismissedRecordKey('');
    }
  }, [selectedRecordKey]);

  const runAction = async (actionConfig: any, source: 'candidate' | 'export') => {
    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
    setRunningAction(`${source}:${code}`);
    try {
      await executeSimpleWorkbenchAction(runtime, actionConfig?.onClick);
    } catch (error) {
      console.error('[ReviewDrawerBlockRenderer] action failed:', error);
    } finally {
      setRunningAction(null);
    }
  };

  // Fully close the review drawer by clearing the selected context row, so it returns to the
  // inline empty state instead of collapsing to a floating "展开行级复核" pill.
  const closeDrawer = () => {
    setSelectedCandidateKey('');
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
    if (!closeClearsContext) {
      setDismissedRecordKey(selectedRecordKey);
      return;
    }
    if (contextStateBinding) {
      writeRuntimeState(runtime, contextStateBinding, {});
    }
  };

  useEffect(() => {
    layoutRef.current = {
      left: position.left,
      top: position.top,
      width: size.width,
      height: size.height,
    };
  }, [position.left, position.top, size.height, size.width]);

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (dragRef.current) {
        const nextLeft = (dragRef.current.left || 0) + event.clientX - dragRef.current.x;
        const nextTop = (dragRef.current.top || 0) + event.clientY - dragRef.current.y;
        const normalized = normalizeDrawerLayout({
          left: nextLeft,
          top: nextTop,
          width: layoutRef.current.width,
          height: layoutRef.current.height,
        });
        setPosition({
          left: normalized.left,
          top: normalized.top,
        });
      }
      if (resizeRef.current) {
        const nextWidth = (resizeRef.current.width || 0) + event.clientX - resizeRef.current.x;
        const nextHeight = (resizeRef.current.height || 0) + event.clientY - resizeRef.current.y;
        const normalized = normalizeDrawerLayout({
          left: layoutRef.current.left,
          top: layoutRef.current.top,
          width: nextWidth,
          height: nextHeight,
        });
        setSize({
          width: normalized.width,
          height: normalized.height,
        });
      }
    };
    const handleMouseUp = () => {
      dragRef.current = null;
      resizeRef.current = null;
    };
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  useEffect(() => {
    if (isMaximized) return;
    persistDrawerLayout(layoutStorageKey, {
      left: position.left,
      top: position.top,
      width: size.width,
      height: size.height,
    });
  }, [isMaximized, layoutStorageKey, position.left, position.top, size.height, size.width]);

  if (!record || Object.keys(record).length === 0) {
    const emptyTitle = getLocalizedText((block as any).empty?.title || 'Select a row', locale, t);
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="review-drawer-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  if (!closeClearsContext && dismissedRecordKey && dismissedRecordKey === selectedRecordKey) {
    return (
      <button
        type="button"
        className="rounded-control bg-panel text-text shadow-pop border-border hover:bg-hover fixed right-4 bottom-4 z-50 border px-4 py-2 text-sm font-medium"
        data-testid="review-drawer-minimized"
        onClick={() => setDismissedRecordKey('')}
      >
        {localized(locale, t, '展开行级复核', 'Open row review')}
      </button>
    );
  }

  const titleTemplate = (block as any).titleTemplate;
  const title = titleTemplate
    ? fillTemplate(
        String(titleTemplate),
        runtime,
        record,
        buildTemplateFieldConfigs(block),
        locale,
        t,
      )
    : getLocalizedText(block.title || 'Review', locale, t);
  const rawRecord = findRelatedRecord(runtime, rawRecordConfig, record);
  const canonicalRecord = findRelatedRecord(runtime, canonicalRecordConfig, record);
  const sourceRecord = sourceRecordConfig.dataSource
    ? findRelatedRecord(runtime, sourceRecordConfig, record)
    : record;
  const sourceSummaryItems = Array.isArray(sourceConfig.summary?.items)
    ? sourceConfig.summary.items
    : [];
  const rawFields = Array.isArray(compareConfig.rawFields) ? compareConfig.rawFields : [];
  const canonicalFields = Array.isArray(compareConfig.canonicalFields)
    ? compareConfig.canonicalFields
    : [];
  const sourceCards = Array.isArray(sourceConfig.cards) ? sourceConfig.cards : [];
  const sourcePolicies = Array.isArray(sourceConfig.policies) ? sourceConfig.policies : [];
  // Labeled JSON evidence blocks (e.g. handover snapshots). Structured/JSONB data belongs here as a
  // collapsible, formatted, labeled <pre> — not crammed into scalar summaryBadges where it renders as
  // raw inline JSON. Additive alongside the singular sourceConfig.jsonField.
  const sourceJsonFields = Array.isArray(sourceConfig.jsonFields) ? sourceConfig.jsonFields : [];
  const exportFields = Array.isArray(exportConfig.fields) ? exportConfig.fields : [];
  const decisionFields = Array.isArray(candidatesConfig.decisionFields)
    ? candidatesConfig.decisionFields
    : [];
  const summaryBadges = Array.isArray((block as any).summaryBadges)
    ? (block as any).summaryBadges
    : [];
  const hasComparePanel = rawFields.length > 0 || canonicalFields.length > 0;
  const hasSourceDetails =
    sourceCards.length > 0 ||
    sourcePolicies.length > 0 ||
    sourceJsonFields.length > 0 ||
    Boolean(sourceConfig.jsonField);
  const hasExportDetails = exportFields.length > 0 || exportRows.length > 0;
  const hasLeftRail =
    hasComparePanel || sourceSummaryItems.length > 0 || hasSourceDetails || hasExportDetails;

  const actionContext = {
    ...context,
    record,
    row: record,
    selectedRecord: record,
    selectedCandidate,
  };
  const isActionVisible = (actionConfig: any) =>
    !actionConfig.visibleWhen ||
    evaluator.evaluateCondition(actionConfig.visibleWhen, actionContext);
  const isActionDisabledByCondition = (actionConfig: any) =>
    actionConfig.disabledWhen
      ? evaluator.evaluateCondition(actionConfig.disabledWhen, actionContext)
      : false;

  const jumpRow = (offset: number) => {
    if (!contextStateBinding || contextRows.length <= 1) return;
    const currentIndex = contextRows.findIndex((row: any) => {
      const key = readPath(row, contextKeyField);
      return String(key) === String(contextRecordKey);
    });
    if (currentIndex < 0) return;
    const nextIndex = (currentIndex + offset + contextRows.length) % contextRows.length;
    writeRuntimeState(runtime, contextStateBinding, contextRows[nextIndex]);
  };

  const drawerStyle: React.CSSProperties = isMaximized
    ? {
        left: 16,
        top: 16,
        width: 'calc(100vw - 32px)',
        height: 'calc(100vh - 32px)',
        minWidth: 0,
      }
    : {
        left: position.left,
        top: position.top,
        width: size.width,
        height: size.height,
        maxWidth: 'calc(100vw - 24px)',
        maxHeight: 'calc(100vh - 24px)',
        minWidth: 'min(760px, calc(100vw - 24px))',
      };

  return (
    <section
      data-testid="review-drawer"
      style={drawerStyle}
      className={`rounded-card bg-panel shadow-pop border-border fixed z-50 grid min-h-[500px] max-w-[calc(100vw-24px)] overflow-hidden border ${
        isEditFormOpen
          ? 'grid-rows-[auto_auto_minmax(0,1fr)]'
          : 'grid-rows-[auto_auto_auto_minmax(0,1fr)]'
      }`}
    >
      <div
        className="bg-accent flex min-h-12 cursor-move items-center justify-between gap-3 overflow-hidden px-4 text-white"
        onMouseDown={(event) => {
          if ((event.target as HTMLElement).closest('button') || isMaximized) return;
          dragRef.current = {
            x: event.clientX,
            y: event.clientY,
            left: position.left,
            top: position.top,
          };
          event.preventDefault();
        }}
      >
        <h2 className="min-w-0 flex-1 truncate text-base font-semibold" title={title}>
          {title}
        </h2>
        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            aria-label={localized(locale, t, '上一行', 'Previous row')}
            onClick={() => jumpRow(-1)}
            className="rounded-control inline-flex h-7 w-7 items-center justify-center text-sm text-white hover:bg-white/15"
          >
            ↑
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '下一行', 'Next row')}
            onClick={() => jumpRow(1)}
            className="rounded-control inline-flex h-7 w-7 items-center justify-center text-sm text-white hover:bg-white/15"
          >
            ↓
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '切换最大化', 'Toggle maximize')}
            onClick={() => setIsMaximized((value) => !value)}
            className="rounded-control inline-flex h-7 w-7 items-center justify-center text-sm text-white hover:bg-white/15"
          >
            □
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '关闭复核浮层', 'Close review drawer')}
            onClick={closeDrawer}
            className="rounded-control inline-flex h-7 w-7 items-center justify-center text-sm text-white hover:bg-white/15"
          >
            ×
          </button>
        </div>
      </div>

      <div className="border-border bg-panel flex max-w-full flex-wrap items-center gap-2 overflow-x-auto border-b px-4 py-3">
        {summaryBadges.map((badge: any) => (
          <Badge
            key={String(badge.key || badge.valueField || badge.label)}
            badge={badge}
            record={record}
            locale={locale}
            t={t}
          />
        ))}
      </div>

      <DrawerEditForm
        config={(block as any).editForm}
        record={record}
        runtime={runtime}
        locale={locale}
        t={t}
        onOpenChange={setIsEditFormOpen}
      />

      <div
        data-testid="review-drawer-content-grid"
        className={`bg-subtle min-h-0 max-w-full overflow-hidden p-4 ${
          isEditFormOpen ? 'hidden' : ''
        }`}
      >
        <div
          className={`grid h-full min-h-0 min-w-0 gap-3 ${
            hasLeftRail ? 'xl:grid-cols-[minmax(0,1fr)_minmax(380px,440px)]' : 'grid-cols-1'
          }`}
        >
          {hasLeftRail && (
            <div className="min-h-0 min-w-0 space-y-3 overflow-auto pr-1">
              {hasComparePanel && (
                <div
                  data-testid="review-drawer-tab-compare"
                  className="grid min-w-0 gap-3 lg:grid-cols-2"
                >
                  {rawFields.length > 0 && (
                    <section className="rounded-card border-border bg-panel overflow-hidden border">
                      <header className="border-border bg-panel text-text-2 flex items-center justify-between gap-3 border-b px-3 py-2 text-sm font-semibold">
                        {sectionLabel(
                          compareConfig.rawTitle ? { title: compareConfig.rawTitle } : null,
                          locale,
                          t,
                          'Raw',
                        )}
                        <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue border px-2 py-0.5 text-xs font-medium">
                          {localized(locale, t, '只读证据', 'Read-only evidence')}
                        </span>
                      </header>
                      <FieldRows fields={rawFields} record={rawRecord} locale={locale} t={t} />
                    </section>
                  )}
                  {canonicalFields.length > 0 && (
                    <section className="rounded-card border-border bg-panel overflow-hidden border">
                      <header className="border-border bg-panel text-text-2 flex items-center justify-between gap-3 border-b px-3 py-2 text-sm font-semibold">
                        {sectionLabel(
                          compareConfig.canonicalTitle
                            ? { title: compareConfig.canonicalTitle }
                            : null,
                          locale,
                          t,
                          'Canonical',
                        )}
                        <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue border px-2 py-0.5 text-xs font-medium">
                          {localized(locale, t, '转换结果', 'Canonical result')}
                        </span>
                      </header>
                      <FieldRows
                        fields={canonicalFields}
                        record={canonicalRecord}
                        fallbackRecord={record}
                        locale={locale}
                        t={t}
                      />
                    </section>
                  )}
                </div>
              )}

              {sourceSummaryItems.length > 0 && (
                <section className="rounded-card border-border bg-panel border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h3 className="text-text text-sm font-semibold">
                      {getLocalizedText(
                        sourceConfig.summary?.title || {
                          'zh-CN': '解析状态摘要',
                          en: 'Parse status summary',
                        },
                        locale,
                        t,
                      )}
                    </h3>
                    {sourceConfig.summary?.description && (
                      <span className="text-text-2 max-w-full min-w-0 truncate text-xs">
                        {getLocalizedText(sourceConfig.summary.description, locale, t)}
                      </span>
                    )}
                  </div>
                  <div
                    data-testid="review-drawer-parse-summary"
                    className="mt-3 flex flex-wrap gap-2"
                  >
                    {sourceSummaryItems.map((item: any) => {
                      const key = String(item.key || item.field || item.label);
                      const label = getLocalizedText(item.label || key, locale, t);
                      const value = resolveDisplayValue(sourceRecord, item, locale, t);
                      return (
                        <span
                          key={key}
                          className="rounded-control text-text-2 border-border bg-subtle inline-flex min-h-8 max-w-full items-center gap-1.5 border px-2.5 py-1 text-xs"
                        >
                          <span className="text-text-2 shrink-0 font-medium">{label}</span>
                          <span
                            className="text-text max-w-[260px] min-w-0 truncate font-semibold"
                            title={value}
                          >
                            {value}
                          </span>
                        </span>
                      );
                    })}
                  </div>
                </section>
              )}

              {hasSourceDetails && (
                <details
                  open={sourceConfig.openByDefault === true}
                  data-testid="review-drawer-tab-source"
                  className="rounded-card border-border bg-panel overflow-hidden border"
                >
                  <summary className="bg-panel text-text cursor-pointer px-3 py-2 text-sm font-semibold">
                    {localized(
                      locale,
                      t,
                      '解析证据与 Profile / LLM Policy',
                      'Parse evidence and Profile / LLM policy',
                    )}
                  </summary>
                  <div className="border-border space-y-3 border-t p-3">
                    {sourceCards.length > 0 && (
                      <div
                        data-testid="review-drawer-source-cards"
                        className="grid gap-3 md:grid-cols-2"
                      >
                        {sourceCards.map((card: any) => {
                          const key = String(card.key || card.title || card.valueField);
                          const value = `${formatValue(readFieldValue(sourceRecord, card), card.emptyText)}${
                            card.unit ? String(card.unit) : ''
                          }`;
                          return (
                            <section
                              key={key}
                              data-testid={`review-drawer-source-card-${key}`}
                              className="rounded-card border-border bg-subtle border p-3"
                            >
                              <h3 className="text-text-2 text-xs font-medium">
                                {getLocalizedText(card.title || key, locale, t)}
                              </h3>
                              <div
                                data-testid={`review-drawer-source-card-${key}-value`}
                                title={value}
                                className="text-text mt-2 text-sm font-semibold [overflow-wrap:anywhere]"
                              >
                                {value}
                              </div>
                              {card.description && (
                                <p className="text-text-2 mt-1 text-xs">
                                  {getLocalizedText(card.description, locale, t)}
                                </p>
                              )}
                            </section>
                          );
                        })}
                      </div>
                    )}
                    {sourcePolicies.length > 0 && (
                      <section className="rounded-card border-border bg-panel border p-3">
                        <h3 className="text-text mb-3 text-sm font-semibold">
                          {getLocalizedText(
                            sourceConfig.policyTitle || {
                              'zh-CN': 'LLM 行为由 Profile Policy 控制',
                              en: 'LLM behavior is controlled by Profile Policy',
                            },
                            locale,
                            t,
                          )}
                        </h3>
                        <div className="grid gap-3 md:grid-cols-3">
                          {sourcePolicies.map((policy: any) => (
                            <div
                              key={String(policy.key || policy.title)}
                              className="rounded-control border-border bg-subtle border p-3"
                            >
                              <h4 className="text-text text-sm font-medium">
                                {getLocalizedText(policy.title || policy.key, locale, t)}
                              </h4>
                              <ul className="text-text-2 mt-2 list-disc space-y-1 pl-5 text-xs">
                                {(policy.items || []).map((item: any) => (
                                  <li key={String(item)}>{String(item)}</li>
                                ))}
                              </ul>
                            </div>
                          ))}
                        </div>
                      </section>
                    )}
                    {sourceConfig.jsonField && (
                      <pre
                        data-testid="review-drawer-source-json"
                        className="rounded-card border-inverse-border bg-inverse text-inverse-muted max-h-64 overflow-auto border p-3 text-xs"
                      >
                        {JSON.stringify(
                          parseJsonValue(readPath(sourceRecord, sourceConfig.jsonField)),
                          null,
                          2,
                        )}
                      </pre>
                    )}
                    {sourceJsonFields.map((item: any) => {
                      const key = String(item.key || item.field || item.label);
                      return (
                        <section key={key} data-testid={`review-drawer-source-json-${key}`}>
                          <div className="text-text-2 mb-1 text-xs font-medium">
                            {getLocalizedText(item.label || key, locale, t)}
                          </div>
                          <pre className="rounded-card border-inverse-border bg-inverse text-inverse-muted max-h-64 overflow-auto border p-3 text-xs">
                            {JSON.stringify(
                              parseJsonValue(readPath(sourceRecord, item.field)),
                              null,
                              2,
                            )}
                          </pre>
                        </section>
                      );
                    })}
                  </div>
                </details>
              )}

              {hasExportDetails && (
                <details
                  data-testid="review-drawer-tab-export"
                  className="rounded-card border-border bg-panel overflow-hidden border"
                >
                  <summary className="bg-panel text-text cursor-pointer px-3 py-2 text-sm font-semibold">
                    {localized(
                      locale,
                      t,
                      '决策历史与导出影响',
                      'Decision history and export impact',
                    )}
                  </summary>
                  <div className="border-border space-y-3 border-t p-3">
                    {exportFields.length > 0 && (
                      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                        {exportFields.map((field: any) => {
                          const key = String(field.key || field.field || field.label);
                          return (
                            <section
                              key={key}
                              className="rounded-card border-border bg-subtle border p-3"
                            >
                              <div className="text-text-2 text-xs">
                                {getLocalizedText(field.label || key, locale, t)}
                              </div>
                              <div className="text-text mt-1 text-sm font-semibold break-words">
                                {formatConfiguredValue(
                                  readFieldValue(record, field),
                                  field,
                                  locale,
                                  t,
                                )}
                              </div>
                            </section>
                          );
                        })}
                      </div>
                    )}
                    {exportRows.length > 0 && (
                      <ol className="rounded-card border-border divide-border bg-panel divide-y border">
                        {exportRows.map((row: any, index: number) => (
                          <li
                            key={String(row.pid ?? index)}
                            className="text-text-2 px-3 py-2 text-sm"
                          >
                            <span className="font-mono font-semibold">
                              {formatValue(
                                readPath(row, 'bom_er_filename'),
                                String(row.pid ?? index),
                              )}
                            </span>
                            <span className="text-text-2 ml-2 text-xs">
                              Rev {formatValue(readPath(row, 'bom_er_revision_no'))}
                            </span>
                          </li>
                        ))}
                      </ol>
                    )}
                  </div>
                </details>
              )}
            </div>
          )}

          {/* The candidates panel is BOM's: it talks about 候选物料 and 标准编码 and writing a
              chosen code back. Rendering it for a page that never configured `candidates` puts one
              domain's vocabulary in front of another domain's users — a FAQ reviewer has no idea
              what a 标准编码 is, and nothing on the panel does anything. Show it when it is asked for. */}
          {hasCandidatesConfig && (
            <aside
              data-testid="review-drawer-tab-candidates"
              className="rounded-card border-border bg-panel flex h-full min-h-0 min-w-0 flex-col overflow-hidden border"
            >
              <header className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-3 py-2">
                <h3 className="text-text min-w-0 flex-1 truncate text-sm font-semibold">
                  {getLocalizedText(
                    candidatesConfig.title || {
                      'zh-CN': '候选物料与用户决策',
                      en: 'Candidates and decision',
                    },
                    locale,
                    t,
                  )}
                </h3>
                {(exportConfig.actions || []).length > 0 && (
                  <div className="flex shrink-0 flex-wrap justify-end gap-2">
                    {exportConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
                      const code = String(
                        actionConfig.code || actionConfig.id || actionConfig.label,
                      );
                      const disabled = Boolean(
                        isActionDisabledByCondition(actionConfig) || runningAction,
                      );
                      return (
                        <button
                          key={code}
                          type="button"
                          data-testid={`review-drawer-export-action-${code}`}
                          disabled={disabled}
                          onClick={() => {
                            void runAction(actionConfig, 'export');
                          }}
                          className={`rounded-control px-3 py-2 text-sm font-medium ${
                            buttonClass[actionConfig.variant || 'secondary'] ||
                            buttonClass.secondary
                          } disabled:cursor-not-allowed disabled:opacity-50`}
                        >
                          {runningAction === `export:${code}`
                            ? t('common.loading')
                            : getLocalizedText(actionConfig.label || code, locale, t)}
                        </button>
                      );
                    })}
                  </div>
                )}
              </header>
              <div
                data-testid="review-drawer-candidate-list"
                className="min-h-0 flex-1 space-y-1.5 overflow-auto p-2"
              >
                {candidates.length === 0 ? (
                  <div
                    data-testid="review-drawer-candidates-empty"
                    className="rounded-control border-border-strong text-text-2 border border-dashed p-3 text-sm"
                  >
                    {getLocalizedText(
                      candidatesConfig.empty?.title || { 'zh-CN': '暂无候选', en: 'No candidates' },
                      locale,
                      t,
                    )}
                  </div>
                ) : (
                  candidates.map((candidate: any, index: number) => {
                    const rowKey = candidateKey(candidate, index);
                    const active = rowKey === selectedCandidateKey;
                    const item = candidatesConfig.item || {};
                    const titleText = formatValue(readPath(candidate, item.titleField), rowKey);
                    const score = item.scoreField
                      ? readPath(candidate, item.scoreField)
                      : undefined;
                    const scoreColor = item.statusColorField
                      ? readPath(candidate, item.statusColorField)
                      : undefined;
                    return (
                      <button
                        key={rowKey}
                        type="button"
                        data-testid={`review-drawer-candidate-${rowKey}`}
                        onClick={() => {
                          setSelectedCandidateKey(rowKey);
                          if (candidatesConfig.selection?.bind) {
                            writeRuntimeState(runtime, candidatesConfig.selection.bind, candidate);
                          }
                        }}
                        className={`rounded-card block w-full border p-3 text-left ${
                          active
                            ? 'bg-accent-weak border-accent'
                            : 'border-border bg-panel hover:bg-hover'
                        }`}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0 flex-1">
                            <div
                              className="text-text font-mono text-xs font-semibold break-words whitespace-normal"
                              title={titleText}
                            >
                              {titleText}
                            </div>
                            <dl className="mt-2 grid gap-x-3 gap-y-1.5 text-xs sm:grid-cols-2">
                              {(item.detailFields || []).map((field: any) => {
                                const key = String(field.key || field.field || field.label);
                                const label = getLocalizedText(field.label || key, locale, t);
                                const rawValue = readFieldValue(candidate, field);
                                if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                                const value = formatConfiguredValue(rawValue, field, locale, t);
                                return (
                                  <div
                                    key={key}
                                    data-testid={`review-drawer-candidate-${rowKey}-field-${key}`}
                                    className={`min-w-0 ${
                                      field.span === 2 ? 'sm:col-span-2' : ''
                                    } grid grid-cols-[72px_minmax(0,1fr)] gap-2 ${
                                      // A ladder is a multi-row card, so align its label to the top of
                                      // the card and give the card room below it instead of letting the
                                      // next field sit tight against it.
                                      field.format === 'ladder'
                                        ? 'items-start pb-1'
                                        : 'items-baseline'
                                    }`}
                                  >
                                    <dt className="text-text-2 min-w-0 break-words" title={label}>
                                      {label}
                                    </dt>
                                    <dd
                                      className="text-text min-w-0 break-words whitespace-normal"
                                      title={value}
                                    >
                                      {field.format === 'price-comparison' ? (
                                        <PriceComparison
                                          original={rawValue}
                                          factored={readFieldValue(candidate, {
                                            field: field.factoredField,
                                          })}
                                          factor={readFieldValue(candidate, {
                                            field: field.factorField,
                                          })}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : field.format === 'ladder' &&
                                        parseLadderRungs(rawValue) ? (
                                        <PriceLadder
                                          rowKey={rowKey}
                                          rungs={parseLadderRungs(rawValue)!}
                                          factor={readFieldValue(candidate, {
                                            field: field.factorField,
                                          })}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : field.format === 'link' && safeExternalUrl(rawValue) ? (
                                        <a
                                          href={safeExternalUrl(rawValue) as string}
                                          target="_blank"
                                          rel="noopener noreferrer"
                                          className="text-accent underline underline-offset-2"
                                          data-testid={`review-drawer-candidate-${rowKey}-link-${key}`}
                                        >
                                          {getLocalizedText(
                                            field.linkLabel || { 'zh-CN': '查看', en: 'Open' },
                                            locale,
                                            t,
                                          )}
                                        </a>
                                      ) : (
                                        value
                                      )}
                                    </dd>
                                  </div>
                                );
                              })}
                            </dl>
                          </div>
                          {score !== undefined && (
                            <span
                              className={`rounded-pill px-1.5 py-0.5 text-xs font-semibold ${scoreToneClass(
                                scoreColor,
                              )}`}
                            >
                              {String(score)}
                            </span>
                          )}
                        </div>
                      </button>
                    );
                  })
                )}
              </div>
              <section
                data-testid="review-drawer-decision-panel"
                className="bg-subtle border-border max-h-[48%] shrink-0 overflow-auto border-t p-2.5"
              >
                <h3 className="text-text text-sm font-semibold">
                  {getLocalizedText(
                    candidatesConfig.decisionTitle || { 'zh-CN': '当前决策状态', en: 'Decision' },
                    locale,
                    t,
                  )}
                </h3>
                <dl className="mt-2 space-y-1.5 text-sm">
                  {decisionFields.length > 0 ? (
                    decisionFields.map((field: any) => {
                      const key = String(field.key || field.field || field.label);
                      const label = getLocalizedText(field.label || key, locale, t);
                      const rawValue = readFieldValue(record, field);
                      if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                      const value = formatConfiguredValue(rawValue, field, locale, t);
                      return (
                        <div key={key} className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                          <dt className="text-text-2 text-xs">{label}</dt>
                          <dd className="text-text break-words">{value}</dd>
                        </div>
                      );
                    })
                  ) : (
                    <>
                      <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                        <dt className="text-text-2 text-xs">
                          {localized(locale, t, '标准编码', 'Standard Code')}
                        </dt>
                        <dd className="text-text font-mono">
                          {formatValue(
                            readPath(record, 'bom_std_material_code'),
                            localized(locale, t, '确认候选后写入', 'Pending confirmation'),
                          )}
                        </dd>
                      </div>
                      <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                        <dt className="text-text-2 text-xs">
                          {localized(locale, t, '当前状态', 'Reason')}
                        </dt>
                        <dd className="text-text break-words">
                          {formatConfiguredValue(
                            readPath(record, 'bom_std_reason_code'),
                            candidatesConfig.reasonField || {},
                            locale,
                            t,
                          )}
                        </dd>
                      </div>
                    </>
                  )}
                </dl>
                {selectedCandidate &&
                  (selectedCandidateFields.length > 0 || selectedCandidateGroups.length > 0) && (
                    <section className="rounded-control border-border bg-panel mt-3 border">
                      <header className="border-border text-text-2 border-b px-3 py-1.5 text-xs font-semibold">
                        {getLocalizedText(
                          candidatesConfig.selectedTitle || {
                            'zh-CN': '匹配证据',
                            en: 'Match Evidence',
                          },
                          locale,
                          t,
                        )}
                      </header>
                      {selectedCandidateGroups.length > 0 ? (
                        <FieldGroups
                          groups={selectedCandidateGroups}
                          record={selectedCandidate}
                          locale={locale}
                          t={t}
                        />
                      ) : (
                        <FieldRows
                          fields={selectedCandidateFields}
                          record={selectedCandidate}
                          locale={locale}
                          t={t}
                        />
                      )}
                    </section>
                  )}
              </section>
              {/*
              The actions live outside the decision panel on purpose. Inside it they were the last
              child of a max-h-[48%] overflow-auto section, so "确认此报价" scrolled out of sight
              behind the evidence it was meant to confirm. As a shrink-0 sibling in the aside's flex
              column they stay on screen however long the candidate detail runs.
            */}
              {(candidatesConfig.actions || []).length > 0 && (
                <footer
                  data-testid="review-drawer-actions"
                  className="border-border bg-panel flex shrink-0 flex-wrap justify-end gap-2 border-t px-2.5 py-2"
                >
                  {candidatesConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
                    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
                    const requiresSelection =
                      actionConfig.requiresSelection !== false &&
                      actionConfig.code !== 'undo_decision';
                    const disabled = Boolean(
                      (requiresSelection && !selectedCandidate) ||
                      isActionDisabledByCondition(actionConfig) ||
                      runningAction,
                    );
                    return (
                      <button
                        key={code}
                        type="button"
                        data-testid={`review-drawer-candidate-action-${code}`}
                        disabled={disabled}
                        onClick={() => {
                          void runAction(actionConfig, 'candidate');
                        }}
                        className={`rounded-control px-3 py-2 text-sm font-medium ${
                          buttonClass[actionConfig.variant || 'primary'] || buttonClass.primary
                        } disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        {runningAction === `candidate:${code}`
                          ? t('common.loading')
                          : getLocalizedText(actionConfig.label || code, locale, t)}
                      </button>
                    );
                  })}
                </footer>
              )}
            </aside>
          )}
        </div>
      </div>

      {!isMaximized && (
        <button
          type="button"
          aria-label={localized(locale, t, '调整复核浮层大小', 'Resize review drawer')}
          className="absolute right-0 bottom-0 h-5 w-5 cursor-nwse-resize"
          onMouseDown={(event) => {
            resizeRef.current = {
              x: event.clientX,
              y: event.clientY,
              width: size.width,
              height: size.height,
            };
            event.preventDefault();
          }}
        >
          <span className="border-accent absolute right-1 bottom-1 h-2 w-2 border-r-2 border-b-2" />
        </button>
      )}
    </section>
  );
};

export default ReviewDrawerBlockRenderer;
