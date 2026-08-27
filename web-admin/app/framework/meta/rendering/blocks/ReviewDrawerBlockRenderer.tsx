import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
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
import {
  collectSemanticResolutionTraces,
  SemanticResolutionEvidence,
} from './SemanticResolutionEvidence';
import {
  comparisonStatusFieldClass,
  comparisonStatusForField,
  normalizeComparisonRecord,
  resolveCandidateFieldColumns,
  resolveProfiledFieldColumns,
  resolveProfiledFieldGroups,
} from './ReviewDrawerCandidateFields';

export { collectSemanticResolutionTraces } from './SemanticResolutionEvidence';

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

type RawColumnItem = {
  index: number;
  header: string;
  normalizedHeader?: string;
  systemField?: string;
  value: unknown;
};

type RawColumnGroup = {
  key: string;
  config: any;
  columns: RawColumnItem[];
  legacyTestId?: boolean;
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

function readStoredDrawerLayout(
  storageKey: string,
  fallbackLayout: DrawerLayoutState = DEFAULT_DRAWER_LAYOUT,
  constrainFallbackToViewport = false,
): DrawerLayoutState {
  const resolvedFallback = constrainFallbackToViewport
    ? normalizeDrawerLayout(fallbackLayout)
    : fallbackLayout;
  if (typeof window === 'undefined') return resolvedFallback;
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return resolvedFallback;
    const parsed = JSON.parse(raw);
    if (!isDrawerLayoutState(parsed)) return resolvedFallback;
    return normalizeDrawerLayout(parsed);
  } catch {
    return resolvedFallback;
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

function readFirstPathValue(source: unknown, paths: string[]): unknown {
  for (const path of paths) {
    const value = readPath(source, path);
    if (!isEmptyValue(value)) return value;
  }
  return undefined;
}

function resolveRawColumnItems(record: any, config: any): RawColumnItem[] {
  if (!config || config.enabled === false) return [];
  const source = config.sourceField ? readPath(record, config.sourceField) : record;
  const parsedSource = parseJsonValue(source);
  const path = config.path || '__raw_columns';
  const rawColumns = path ? readPath(parsedSource, path) : parsedSource;
  if (!Array.isArray(rawColumns)) return [];

  const headerField = String(config.headerField || 'header');
  const normalizedHeaderField = String(config.normalizedHeaderField || 'normalizedHeader');
  const systemFieldField = String(config.systemFieldField || 'systemField');
  const valueField = String(config.valueField || 'value');

  return rawColumns.map((column, index) => {
    if (!column || typeof column !== 'object' || Array.isArray(column)) {
      return { index, header: `#${index + 1}`, value: column };
    }
    const header = readFirstPathValue(column, [headerField, 'name', 'key']);
    const normalizedHeader = readFirstPathValue(column, [normalizedHeaderField, 'normalized']);
    const systemField = readFirstPathValue(column, [systemFieldField, 'field']);
    return {
      index,
      header: formatValue(header, `#${index + 1}`),
      normalizedHeader: isEmptyValue(normalizedHeader) ? undefined : formatValue(normalizedHeader),
      systemField: isEmptyValue(systemField) ? undefined : formatValue(systemField),
      value: readPath(column, valueField),
    };
  });
}

function resolveRawColumnGroups(record: any, compareConfig: any): RawColumnGroup[] {
  const groups: RawColumnGroup[] = [];
  const primaryColumns = resolveRawColumnItems(record, compareConfig?.rawColumns);
  if (primaryColumns.length > 0) {
    groups.push({
      key: 'source_columns',
      config: compareConfig.rawColumns,
      columns: primaryColumns,
      legacyTestId: true,
    });
  }
  const groupConfigs = Array.isArray(compareConfig?.rawColumnGroups)
    ? compareConfig.rawColumnGroups
    : [];
  groupConfigs.forEach((groupConfig: any, index: number) => {
    const columns = resolveRawColumnItems(record, groupConfig);
    if (columns.length === 0) return;
    groups.push({
      key: String(groupConfig.key || groupConfig.id || `group_${index}`),
      config: groupConfig,
      columns,
    });
  });
  return groups;
}

/** One rung of a supplier price ladder, as projected by the named query. */
interface LadderRung {
  qty: string | number;
  price: string | number;
  current?: boolean;
  rangeLabel?: string;
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
  const parsedSource = parseJsonValue(source);
  let value = readPath(parsedSource, config.field || config.valueField);
  if (isEmptyValue(value) && Array.isArray(config.fallbackFields)) {
    for (const fallbackField of config.fallbackFields) {
      value = readPath(parsedSource, fallbackField);
      if (!isEmptyValue(value)) break;
    }
  }
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
    missing: { 'zh-CN': '证据缺失', en: 'Evidence Missing' },
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

const comparisonFieldLabels: Record<string, { 'zh-CN': string; en: string }> = {
  brand: { 'zh-CN': '品牌', en: 'Brand' },
  mpn: { 'zh-CN': 'MPN', en: 'MPN' },
  package: { 'zh-CN': '封装', en: 'Package' },
  packageCode: { 'zh-CN': '封装', en: 'Package' },
  capacitance: { 'zh-CN': '容值', en: 'Capacitance' },
  resistance: { 'zh-CN': '阻值', en: 'Resistance' },
  voltage: { 'zh-CN': '耐压', en: 'Voltage' },
  tolerance: { 'zh-CN': '误差', en: 'Tolerance' },
  dielectric: { 'zh-CN': '介质', en: 'Dielectric' },
  frequency: { 'zh-CN': '频率', en: 'Frequency' },
};

function humanizeComparisonField(
  value: unknown,
  locale: string,
  t: (key: string) => string,
): string {
  const text = String(value ?? '');
  return text ? getLocalizedText(comparisonFieldLabels[text] || text, locale, t) : '';
}

function collectEvidenceComparisons(candidate: any): Record<string, unknown>[] {
  const evidence = parseJsonValue(readPath(candidate, 'bom_me_evidence_json'));
  const groups = readPath(evidence, 'groups');
  const comparisons: Record<string, unknown>[] = [];
  if (groups && typeof groups === 'object' && !Array.isArray(groups)) {
    for (const groupKey of ['brand', 'mpn', 'package', 'parameters', 'other']) {
      const groupComparisons = readPath(groups, `${groupKey}.comparisons`);
      if (Array.isArray(groupComparisons)) comparisons.push(...groupComparisons);
    }
  }
  const fallbackComparisons = readPath(evidence, 'comparisons');
  if (comparisons.length === 0 && Array.isArray(fallbackComparisons)) {
    comparisons.push(...fallbackComparisons);
  }
  return comparisons.map(normalizeComparisonRecord);
}

function buildEvidenceSummary(
  candidate: any,
  locale: string,
  t: (key: string) => string,
): string[] {
  return collectEvidenceComparisons(candidate)
    .filter((comparison) => String(comparison.status || '') !== 'matched')
    .map((comparison) => {
      const field = humanizeComparisonField(comparison.label ?? comparison.key, locale, t);
      const status = String(comparison.status || '');
      const sourceValue = formatValue(comparison.sourceValue, '-');
      const candidateValue = formatValue(comparison.candidateValue, '-');
      if (status === 'mismatch') {
        return localized(
          locale,
          t,
          `${field}不一致（原始 ${sourceValue} / 候选 ${candidateValue}）`,
          `${field} differs (source ${sourceValue} / candidate ${candidateValue})`,
        );
      }
      if (status === 'missing_candidate') {
        return localized(locale, t, `${field}候选缺失`, `${field} missing on candidate`);
      }
      if (status === 'missing_source') {
        return localized(locale, t, `${field}原始缺失`, `${field} missing on source`);
      }
      if (status === 'missing_both' || status === 'missing') {
        return localized(locale, t, `${field}证据缺失`, `${field} evidence missing`);
      }
      return localized(
        locale,
        t,
        `${field}${comparisonStatusLabel(status, locale, t)}`,
        `${field} ${status}`,
      );
    });
}

function EvidenceSummary({
  candidate,
  locale,
  t,
}: {
  candidate: any;
  locale: string;
  t: (key: string) => string;
}) {
  const issues = buildEvidenceSummary(candidate, locale, t);
  const visibleIssues = issues.slice(0, 4);
  const overflowCount = Math.max(issues.length - visibleIssues.length, 0);
  return (
    <div
      data-testid="review-drawer-evidence-summary"
      className="rounded-control border-status-amber bg-status-amber-bg text-status-amber border px-3 py-2 text-xs"
    >
      <span className="font-semibold">{localized(locale, t, '需复核：', 'Review: ')}</span>
      {visibleIssues.length > 0 ? (
        <>
          <span>{visibleIssues.join('；')}</span>
          {overflowCount > 0 && (
            <span>
              {localized(locale, t, `；另有 ${overflowCount} 项`, `; ${overflowCount} more`)}
            </span>
          )}
        </>
      ) : (
        <span>{localized(locale, t, '关键属性一致', 'Key attributes match')}</span>
      )}
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
  return parsed
    .toFixed(6)
    .replace(/(\.\d*?[1-9])0+$/, '$1')
    .replace(/\.0+$/, '');
}

function factoredUnitPrice(value: unknown, factor: unknown): string {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return formatValue(value);
  return formatUnitPrice((parsed * priceFactorPercent(factor)) / 100);
}

function ladderRangeLabel(rungs: LadderRung[], index: number): string {
  const explicit = String(rungs[index]?.rangeLabel ?? '').trim();
  if (explicit) return explicit;
  const current = Number(rungs[index]?.qty);
  if (!Number.isFinite(current)) return `${String(rungs[index]?.qty ?? '')}+`;
  const next = rungs
    .map((rung) => Number(rung.qty))
    .filter((qty) => Number.isFinite(qty) && qty > current)
    .sort((left, right) => left - right)[0];
  return next === undefined ? `${current}+` : `${current}–${next - 1}`;
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
  const displayedFactored = Number.isFinite(Number(original))
    ? factoredUnitPrice(original, factor)
    : formatUnitPrice(factored);
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
          {displayedFactored}
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
  const currentIndex = rungs.findIndex((rung) => rung.current);
  const selectedIndex = currentIndex >= 0 ? currentIndex : 0;
  const selected = rungs[selectedIndex];
  return (
    <div
      className="border-border bg-subtle rounded-control w-full overflow-hidden border text-xs tabular-nums"
      data-testid={`review-drawer-candidate-${rowKey}-ladder`}
    >
      <div
        data-testid={`review-drawer-candidate-${rowKey}-ladder-summary`}
        className="border-border bg-accent-weak text-text flex flex-wrap items-center justify-between gap-2 border-b px-2 py-2"
      >
        <span className="font-medium">
          {localized(locale, t, '当前数量区间', 'Current quantity range')}{' '}
          {ladderRangeLabel(rungs, selectedIndex)}
        </span>
        <span className="font-mono font-semibold">
          {formatUnitPrice(selected.price)} × {factorText} →{' '}
          {factoredUnitPrice(selected.price, factor)}
        </span>
      </div>
      <div className="text-text-2 border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] border-b px-2 py-1.5 text-[11px] font-medium">
        <span>{localized(locale, t, '数量区间', 'Qty range')}</span>
        <span className="text-right">{localized(locale, t, '原始单价', 'Original')}</span>
        <span className="text-right">
          {localized(locale, t, '系数后', 'After factor')} ({factorText})
        </span>
      </div>
      {rungs.map((rung, index) => (
        <div
          key={String(rung.qty)}
          data-testid={rung.current ? `review-drawer-ladder-current-${rowKey}` : undefined}
          className={`border-border grid grid-cols-[minmax(72px,0.75fr)_minmax(92px,1fr)_minmax(112px,1fr)] items-center border-b px-2 py-1.5 last:border-b-0 ${
            rung.current ? 'bg-accent-weak text-text font-semibold' : 'text-text-2'
          }`}
        >
          <span className="flex items-center gap-1.5 whitespace-nowrap">
            {ladderRangeLabel(rungs, index)}
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

function configuredTone(config: any, rawValue: unknown): Tone {
  const rawKey = String(rawValue ?? '');
  const mappedTone = config?.toneMap?.[rawKey];
  return (mappedTone || config?.tone || 'gray') as Tone;
}

function numericValue(value: unknown): number | null {
  if (value === undefined || value === null || value === '') return null;
  const numeric = typeof value === 'number' ? value : Number(String(value).replace(/[%,$]/g, ''));
  return Number.isFinite(numeric) ? numeric : null;
}

function compactNumber(value: number, maximumFractionDigits = 6): string {
  return new Intl.NumberFormat('en-US', {
    maximumFractionDigits,
    useGrouping: true,
  }).format(value);
}

function scoreText(value: unknown): string {
  const numeric = numericValue(value);
  if (numeric === null) return formatValue(value);
  const score = Math.abs(numeric) <= 1 ? numeric * 100 : numeric;
  return compactNumber(score, 2);
}

function tableFieldValue(candidate: any, field: any, referenceRecord?: any): unknown {
  if (field?.referenceField) return readPath(referenceRecord, field.referenceField);
  return readFieldValue(candidate, field, referenceRecord);
}

function suggestedPurchaseQuantity(requestedValue: unknown, moqValue: unknown): number | null {
  const requested = numericValue(requestedValue);
  const moq = numericValue(moqValue);
  if (requested === null && moq === null) return null;
  return Math.max(requested || 0, moq || 0);
}

function ladderUnitPriceAtQuantity(
  rungs: LadderRung[] | null,
  quantity: number | null,
): number | null {
  if (!rungs || rungs.length === 0 || quantity === null) return null;
  const pricedRungs = rungs
    .map((rung) => ({ qty: numericValue(rung.qty), price: numericValue(rung.price) }))
    .filter(
      (rung): rung is { qty: number; price: number } => rung.qty !== null && rung.price !== null,
    )
    .sort((left, right) => left.qty - right.qty);
  if (pricedRungs.length === 0) return null;
  let selected = pricedRungs[0];
  for (const rung of pricedRungs) {
    if (rung.qty > quantity) break;
    selected = rung;
  }
  return selected.price;
}

function CompactPriceLadder({
  testIdPrefix,
  rowKey,
  rungs,
  factor,
  locale,
  t,
}: {
  testIdPrefix: string;
  rowKey: string;
  rungs: LadderRung[];
  factor: unknown;
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div
      className="grid gap-0.5 text-[11px] tabular-nums"
      data-testid={`${testIdPrefix}-${rowKey}-compact-ladder`}
    >
      {rungs.map((rung, index) => (
        <div
          key={`${String(rung.qty)}-${index}`}
          data-testid={
            rung.current ? `${testIdPrefix}-${rowKey}-compact-ladder-current` : undefined
          }
          className={`grid grid-cols-[minmax(74px,0.8fr)_minmax(76px,1fr)_auto] items-center gap-1.5 whitespace-nowrap ${
            rung.current ? 'text-accent font-semibold' : 'text-text-2'
          }`}
        >
          <span>{ladderRangeLabel(rungs, index)}</span>
          <span className="font-mono">{factoredUnitPrice(rung.price, factor)}</span>
          {rung.current ? (
            <span className="rounded-control bg-accent-weak text-accent px-1 py-0.5 text-[10px]">
              {localized(locale, t, '当前', 'Current')}
            </span>
          ) : (
            <span />
          )}
        </div>
      ))}
    </div>
  );
}

function CandidateTableField({
  candidate,
  field,
  referenceRecord,
  testIdPrefix,
  rowKey,
  locale,
  t,
}: {
  candidate: any;
  field: any;
  referenceRecord?: any;
  testIdPrefix: string;
  rowKey: string;
  locale: string;
  t: (key: string) => string;
}) {
  const key = String(field.key || field.field || field.referenceField || field.label);
  const label = getLocalizedText(field.label || key, locale, t);
  const rawValue = tableFieldValue(candidate, field, referenceRecord);
  if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
  const variantClass =
    field.variant === 'primary'
      ? 'text-text font-semibold'
      : field.variant === 'emphasis'
        ? 'text-status-red text-base font-semibold tabular-nums'
        : field.variant === 'success'
          ? 'text-status-green font-medium'
          : field.variant === 'warning'
            ? 'text-status-amber font-medium'
            : 'text-text-2';
  const labelNode =
    field.showLabel === false ? null : <span className="text-text-3 mr-1">{label}：</span>;
  const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
  const externalUrl = field.format === 'link' ? safeExternalUrl(rawValue) : null;

  if (field.format === 'ladder') {
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`}>
        {ladder ? (
          <CompactPriceLadder
            testIdPrefix={testIdPrefix}
            rowKey={rowKey}
            rungs={ladder}
            factor={tableFieldValue(candidate, { field: field.factorField }, referenceRecord)}
            locale={locale}
            t={t}
          />
        ) : (
          <span className="text-text-3">-</span>
        )}
      </div>
    );
  }

  if (field.format === 'purchase-quantity') {
    const requested = field.requestedQtyField
      ? readPath(candidate, field.requestedQtyField)
      : readPath(referenceRecord, field.requestedQtyReferenceField);
    const requestedNumber = numericValue(requested);
    const moq = readPath(candidate, field.moqField);
    const suggested = suggestedPurchaseQuantity(requested, moq);
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`} className="grid gap-0.5">
        <div>
          <span className="text-text-3">{localized(locale, t, '需求', 'Required')}：</span>
          <span className="text-text font-medium tabular-nums">{formatValue(requested)}</span>
        </div>
        <div>
          <span className="text-text-3">
            {localized(locale, t, '建议采购', 'Suggested order')}：
          </span>
          <span className="text-text font-semibold tabular-nums">
            {suggested === null ? '-' : compactNumber(suggested, 3)}
          </span>
        </div>
        {suggested !== null && requestedNumber !== null && suggested > requestedNumber && (
          <div className="text-status-amber text-[11px]">
            {localized(locale, t, '补齐 MOQ', 'Raised to MOQ')}
          </div>
        )}
      </div>
    );
  }

  if (field.format === 'quote-total') {
    const configuredOriginal = numericValue(rawValue);
    const factorValue = tableFieldValue(candidate, { field: field.factorField }, referenceRecord);
    const configuredFactored = numericValue(
      tableFieldValue(candidate, { field: field.factoredField }, referenceRecord),
    );
    const requested = field.requestedQtyField
      ? readPath(candidate, field.requestedQtyField)
      : readPath(referenceRecord, field.requestedQtyReferenceField);
    const suggested = suggestedPurchaseQuantity(requested, readPath(candidate, field.moqField));
    const ladderOriginal = field.ladderField
      ? ladderUnitPriceAtQuantity(
          parseLadderRungs(readPath(candidate, field.ladderField)),
          suggested,
        )
      : null;
    const original = ladderOriginal ?? configuredOriginal;
    // The persisted factored price can be rounded to the model field's scale. Derive it from the
    // supplier price whenever that source value is available so the review surface preserves the
    // same six-decimal precision used by price adoption and workbook generation.
    const factored =
      original !== null ? original * (priceFactorPercent(factorValue) / 100) : configuredFactored;
    const total = factored !== null && suggested !== null ? factored * suggested : null;
    return (
      <div data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`} className="grid gap-0.5">
        <div className="text-text-3 text-[11px] tabular-nums">
          {localized(locale, t, '原始', 'Original')} {formatUnitPrice(original)} ×{' '}
          {compactNumber(priceFactorPercent(factorValue), 2)}%
        </div>
        <div className="text-status-red text-base font-semibold tabular-nums">
          {factored === null ? '-' : formatUnitPrice(factored)}
        </div>
        <div className="text-text-2 text-[11px] tabular-nums">
          {localized(locale, t, '小计', 'Subtotal')}{' '}
          {total === null ? '-' : compactNumber(total, 6)}
        </div>
      </div>
    );
  }

  const formatted =
    field.format === 'score'
      ? scoreText(rawValue)
      : Array.isArray(rawValue)
        ? rawValue.map(String).join(' · ')
        : formatConfiguredValue(rawValue, field, locale, t);

  return (
    <div
      data-testid={`${testIdPrefix}-${rowKey}-table-field-${key}`}
      className={`min-w-0 [overflow-wrap:anywhere] ${variantClass}`}
    >
      {labelNode}
      {externalUrl ? (
        <a
          href={externalUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="text-accent inline-flex items-center gap-1 underline underline-offset-2"
          data-testid={`${testIdPrefix}-${rowKey}-link-${key}`}
          onClick={(event) => event.stopPropagation()}
        >
          {getLocalizedText(
            field.linkLabel || { 'zh-CN': '商品页 ↗', en: 'Product page ↗' },
            locale,
            t,
          )}
        </a>
      ) : (
        <span className={field.format === 'score' ? 'text-accent text-xl font-semibold' : ''}>
          {formatted}
          {field.format === 'score' && field.unit !== false ? (
            <span className="text-text-3 ml-0.5 text-[11px] font-normal">
              {localized(locale, t, '分', 'pts')}
            </span>
          ) : null}
        </span>
      )}
    </div>
  );
}

function CandidateComparisonTable({
  candidates,
  tableConfig,
  selectedKey,
  recommendedKey,
  referenceRecord,
  locale,
  t,
  testIdPrefix,
  keyField,
  confirmableField,
  onSelect,
}: {
  candidates: any[];
  tableConfig: any;
  selectedKey: string;
  recommendedKey?: string;
  referenceRecord?: any;
  locale: string;
  t: (key: string) => string;
  testIdPrefix: string;
  keyField?: string;
  confirmableField?: string;
  onSelect: (candidate: any, key: string) => void;
}) {
  const columns: any[] = Array.isArray(tableConfig?.columns) ? tableConfig.columns : [];
  const minWidth = Math.max(960, Number(tableConfig?.minWidth) || 1560);
  const radioName = `${testIdPrefix}-selection`;
  return (
    <div
      className="min-h-0 max-w-full overflow-x-auto"
      data-testid={`${testIdPrefix}-comparison-table-wrap`}
    >
      <table
        className="w-full table-fixed border-collapse text-xs"
        style={{ minWidth }}
        data-testid={`${testIdPrefix}-comparison-table`}
      >
        <colgroup>
          {columns.map((column) => (
            <col
              key={String(column.key || column.label)}
              style={{ width: Math.max(72, Number(column.width) || 140) }}
            />
          ))}
        </colgroup>
        <thead>
          <tr className="border-border bg-subtle border-b">
            {columns.map((column) => (
              <th
                key={String(column.key || column.label)}
                className="text-text-2 px-3 py-2 text-left text-[11px] font-semibold whitespace-nowrap"
              >
                {getLocalizedText(column.label || column.key, locale, t)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {candidates.map((candidate, index) => {
            const rowKey = keyField
              ? String(readPath(candidate, keyField) ?? index)
              : candidateKey(candidate, index);
            const active = rowKey === selectedKey;
            const confirmable = confirmableField
              ? Boolean(readPath(candidate, confirmableField))
              : true;
            const choose = () => {
              if (confirmable) onSelect(candidate, rowKey);
            };
            return (
              <tr
                key={rowKey}
                data-testid={`${testIdPrefix}-${rowKey}`}
                data-selected={active ? 'true' : 'false'}
                aria-disabled={!confirmable}
                onClick={(event) => {
                  if ((event.target as HTMLElement).closest('a,button,input')) return;
                  if (window.getSelection()?.toString().trim()) return;
                  choose();
                }}
                className={`border-border border-b transition-colors select-text last:border-b-0 ${
                  active ? 'bg-accent-weak' : 'bg-panel hover:bg-hover'
                } ${confirmable ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
              >
                {columns.map((column) => {
                  const columnKey = String(column.key || column.label);
                  if (column.kind === 'selection') {
                    const badges: any[] = Array.isArray(column.badges) ? column.badges : [];
                    return (
                      <td key={columnKey} className="px-3 py-3 align-middle">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <input
                            type="radio"
                            name={radioName}
                            checked={active}
                            disabled={!confirmable}
                            aria-label={localized(
                              locale,
                              t,
                              `选择候选 ${index + 1}`,
                              `Select candidate ${index + 1}`,
                            )}
                            onChange={choose}
                            className="accent-accent h-4 w-4"
                          />
                          {recommendedKey && rowKey === recommendedKey && (
                            <span className="rounded-pill bg-accent-weak text-accent px-1.5 py-0.5 text-[10px] font-semibold">
                              {getLocalizedText(
                                column.recommendedLabel || { 'zh-CN': '推荐', en: 'Recommended' },
                                locale,
                                t,
                              )}
                            </span>
                          )}
                          {badges.map((badge) => {
                            const badgeKey = String(badge.key || badge.field || badge.label);
                            const rawValue = tableFieldValue(candidate, badge, referenceRecord);
                            if (badge.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                            const value = formatConfiguredValue(rawValue, badge, locale, t);
                            const tone = configuredTone(badge, rawValue);
                            return (
                              <span
                                key={badgeKey}
                                className={`rounded-pill border px-1.5 py-0.5 text-[10px] font-semibold ${
                                  toneClass[tone] || toneClass.gray
                                }`}
                              >
                                {value}
                              </span>
                            );
                          })}
                        </div>
                      </td>
                    );
                  }
                  if (column.kind === 'action') {
                    return (
                      <td key={columnKey} className="px-3 py-3 text-center align-middle">
                        <button
                          type="button"
                          disabled={!confirmable}
                          onClick={choose}
                          className={`rounded-control border px-2.5 py-1.5 text-xs font-medium ${
                            active
                              ? 'border-accent bg-accent text-white'
                              : 'border-accent bg-panel text-accent hover:bg-accent-weak'
                          } disabled:border-border disabled:text-text-3 disabled:bg-subtle`}
                        >
                          {active
                            ? getLocalizedText(
                                column.selectedLabel || { 'zh-CN': '已选择', en: 'Selected' },
                                locale,
                                t,
                              )
                            : getLocalizedText(
                                confirmable
                                  ? column.selectLabel || { 'zh-CN': '选择', en: 'Select' }
                                  : column.disabledLabel || {
                                      'zh-CN': '不可采用',
                                      en: 'Unavailable',
                                    },
                                locale,
                                t,
                              )}
                        </button>
                      </td>
                    );
                  }
                  const fields: any[] = Array.isArray(column.fields) ? column.fields : [];
                  return (
                    <td key={columnKey} className="px-3 py-3 align-middle">
                      <div className="grid min-w-0 gap-0.5">
                        {fields.map((field) => (
                          <CandidateTableField
                            key={String(
                              field.key || field.field || field.referenceField || field.label,
                            )}
                            candidate={candidate}
                            field={field}
                            referenceRecord={referenceRecord}
                            testIdPrefix={testIdPrefix}
                            rowKey={rowKey}
                            locale={locale}
                            t={t}
                          />
                        ))}
                      </div>
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function CandidateSelectionSummary({
  candidate,
  config,
  referenceRecord,
  locale,
  t,
}: {
  candidate: any;
  config: any;
  referenceRecord?: any;
  locale: string;
  t: (key: string) => string;
}) {
  if (!candidate || !config) return null;
  const title = formatValue(readPath(candidate, config.titleField));
  const fields: any[] = Array.isArray(config.fields) ? config.fields : [];
  return (
    <div data-testid="review-drawer-selection-summary" className="min-w-0 flex-1">
      <div className="text-text truncate text-sm font-semibold">
        {getLocalizedText(config.label || { 'zh-CN': '已选择', en: 'Selected' }, locale, t)}：
        {title}
      </div>
      {fields.length > 0 && (
        <div className="text-text-2 mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs">
          {fields.map((field) => {
            const key = String(field.key || field.field || field.referenceField || field.label);
            const label = getLocalizedText(field.label || key, locale, t);
            const value = formatConfiguredValue(
              tableFieldValue(candidate, field, referenceRecord),
              field,
              locale,
              t,
            );
            return (
              <span key={key}>
                {label} {value}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}

function CandidateFieldTable({
  rowKey,
  candidate,
  fields,
  badges,
  locale,
  t,
}: {
  rowKey: string;
  candidate: any;
  fields: any[];
  badges: any[];
  locale: string;
  t: (key: string) => string;
}) {
  const hasUsableLadder = fields.some((field: any) => {
    if (field.format !== 'ladder') return false;
    const rungs = parseLadderRungs(readFieldValue(candidate, field));
    return Boolean(rungs && rungs.length >= 2);
  });

  return (
    <div className="mt-2 space-y-2">
      {badges.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {badges.map((badge: any) => {
            const key = String(badge.key || badge.field || badge.label);
            const rawValue = readFieldValue(candidate, badge);
            if (badge.hideWhenEmpty && isEmptyValue(rawValue)) return null;
            const value = formatConfiguredValue(rawValue, badge, locale, t);
            const tone = configuredTone(badge, rawValue);
            return (
              <span
                key={key}
                data-testid={`review-drawer-candidate-${rowKey}-badge-${key}`}
                className={`rounded-pill inline-flex max-w-full border px-2 py-0.5 text-[11px] font-semibold ${
                  toneClass[tone] || toneClass.gray
                }`}
                title={value}
              >
                <span className="truncate">{value}</span>
              </span>
            );
          })}
        </div>
      )}
      <dl className="grid gap-x-3 gap-y-1.5 text-xs sm:grid-cols-2 xl:grid-cols-4">
        {fields.map((field: any) => {
          const key = String(field.key || field.field || field.label);
          const label = getLocalizedText(field.label || key, locale, t);
          const rawValue = readFieldValue(candidate, field);
          const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
          if (field.format === 'price-comparison' && hasUsableLadder) return null;
          if (field.format === 'ladder' && (!ladder || ladder.length < 2)) return null;
          if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
          const value = formatConfiguredValue(rawValue, field, locale, t);
          const comparisonStatus = comparisonStatusForField(candidate, field);
          const comparisonClass = comparisonStatusFieldClass(comparisonStatus);
          return (
            <div
              key={key}
              data-testid={`review-drawer-candidate-${rowKey}-field-${key}`}
              data-comparison-status={comparisonStatus ? String(comparisonStatus) : undefined}
              className={`min-w-0 ${comparisonClass}`}
              title={
                comparisonStatus ? comparisonStatusLabel(comparisonStatus, locale, t) : undefined
              }
            >
              <dt className="text-text-2 min-w-0 truncate" title={label}>
                {label}
              </dt>
              <dd className="text-text min-w-0 font-medium" title={value}>
                {field.format === 'price-comparison' ? (
                  <PriceComparison
                    original={rawValue}
                    factored={readFieldValue(candidate, { field: field.factoredField })}
                    factor={readFieldValue(candidate, { field: field.factorField })}
                    locale={locale}
                    t={t}
                  />
                ) : field.format === 'ladder' && ladder ? (
                  <PriceLadder
                    rowKey={rowKey}
                    rungs={ladder}
                    factor={readFieldValue(candidate, { field: field.factorField })}
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
                  <span className="block truncate">{value}</span>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
    </div>
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
  const hasUsableLadder = fields.some((field) => {
    if (field.format !== 'ladder') return false;
    const rungs = parseLadderRungs(readFieldValue(record, field, fallbackRecord));
    return Boolean(rungs && rungs.length >= 2);
  });

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
        const ladder = field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
        if (field.format === 'price-comparison' && hasUsableLadder) return null;
        if (field.format === 'ladder' && (!ladder || ladder.length < 2)) return null;
        if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
        const mappedValue = applyValueMap(rawValue, field, locale, t);
        const rendersComparisons = isComparisonList(mappedValue);
        const value = rendersComparisons ? '' : formatConfiguredValue(rawValue, field, locale, t);
        const isMultiline = value.includes('\n') || value.length > 86;
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

function RawColumnsPanel({
  columns,
  config,
  panelKey,
  locale,
  t,
}: {
  columns: RawColumnItem[];
  config: any;
  panelKey?: string;
  locale: string;
  t: (key: string) => string;
}) {
  if (columns.length === 0) return null;
  const title = getLocalizedText(
    config?.title || { 'zh-CN': '源 Excel 全列', en: 'Source Excel Columns' },
    locale,
    t,
  );
  const showSystemField = config?.showSystemField !== false;
  return (
    <section
      data-testid={panelKey ? `review-drawer-raw-columns-${panelKey}` : 'review-drawer-raw-columns'}
      className="border-border border-t"
    >
      <header className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
        <h4 className="text-text text-xs font-semibold">{title}</h4>
        <span className="rounded-pill border-border bg-subtle text-text-2 border px-2 py-0.5 text-[11px] font-medium">
          {localized(locale, t, `${columns.length} 列`, `${columns.length} columns`)}
        </span>
      </header>
      <div className="divide-border max-h-80 divide-y overflow-auto">
        {columns.map((column) => {
          const value = formatValue(column.value, config?.emptyText || '-');
          const showNormalizedHeader =
            column.normalizedHeader && column.normalizedHeader !== column.header;
          return (
            <div
              key={`${column.header}-${column.index}`}
              data-testid={
                panelKey
                  ? `review-drawer-raw-column-${panelKey}-${column.index}`
                  : `review-drawer-raw-column-${column.index}`
              }
              className="grid grid-cols-[minmax(118px,0.36fr)_minmax(0,1fr)] gap-3 px-3 py-2.5 text-sm"
            >
              <div className="min-w-0 space-y-1">
                <div className="text-text min-w-0 text-xs font-semibold break-words">
                  {column.header}
                </div>
                {showNormalizedHeader && (
                  <div className="text-text-2 min-w-0 text-[11px] break-words">
                    {column.normalizedHeader}
                  </div>
                )}
                {showSystemField && column.systemField && (
                  <span className="rounded-pill border-status-blue bg-status-blue-bg text-status-blue inline-flex max-w-full border px-1.5 py-0.5 text-[10px] font-medium">
                    <span className="min-w-0 truncate">{column.systemField}</span>
                  </span>
                )}
              </div>
              <div className="text-text min-w-0 overflow-x-auto [overflow-wrap:anywhere] break-words whitespace-pre-wrap">
                {value}
              </div>
            </div>
          );
        })}
      </div>
    </section>
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
  renderTriggerInHost = false,
  triggerHost,
}: {
  config: any;
  record: any;
  runtime: SchemaRuntime;
  locale: string;
  t: (key: string) => string;
  onOpenChange?: (open: boolean) => void;
  renderTriggerInHost?: boolean;
  triggerHost?: HTMLElement | null;
}) {
  const fields: any[] = Array.isArray(config?.fields) ? config.fields : [];
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<Record<string, any> | null>(null);
  const [selectedPreviewId, setSelectedPreviewId] = useState('');

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
    setSelectedPreviewId('');
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
        const resultRecord = result as Record<string, any>;
        setPreview(resultRecord);
        const candidateConfig = config?.preview || {};
        const candidateRows = Array.isArray(readPath(resultRecord, candidateConfig.candidatesField))
          ? readPath(resultRecord, candidateConfig.candidatesField)
          : [];
        const idField = candidateConfig.previewIdField || 'previewId';
        const recommendedId = readPath(
          resultRecord,
          candidateConfig.recommendedIdField || 'recommendedPreviewId',
        );
        const recommended = candidateRows.find(
          (candidate: any) => String(readPath(candidate, idField)) === String(recommendedId || ''),
        );
        const firstConfirmable = candidateRows.find((candidate: any) =>
          Boolean(readPath(candidate, candidateConfig.confirmableField || 'confirmable')),
        );
        const initial = recommended || firstConfirmable || candidateRows[0];
        setSelectedPreviewId(initial ? String(readPath(initial, idField) || '') : '');
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
    const candidateRows = Array.isArray(readPath(preview, config.preview?.candidatesField))
      ? readPath(preview, config.preview?.candidatesField)
      : [];
    const selectedPreview = candidateRows.find(
      (candidate: any) => String(readPath(candidate, previewIdField)) === selectedPreviewId,
    );
    const previewId = readPath(selectedPreview || preview, previewIdField);
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
      setSelectedPreviewId('');
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
    setSelectedPreviewId('');
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
  const previewCandidates: any[] =
    preview && Array.isArray(readPath(preview, previewConfig.candidatesField))
      ? readPath(preview, previewConfig.candidatesField)
      : [];
  const selectedPreviewCandidate = previewCandidates.find(
    (candidate: any) =>
      String(readPath(candidate, previewConfig.previewIdField || 'previewId')) ===
      selectedPreviewId,
  );
  const confirmable = preview
    ? Boolean(
        readPath(
          selectedPreviewCandidate || preview,
          previewConfig.confirmableField || 'confirmable',
        ),
      )
    : false;

  const openButton = (
    <button
      type="button"
      data-testid="review-drawer-edit-open"
      disabled={disabled}
      onClick={begin}
      className={`rounded-control border px-3 py-1.5 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${
        renderTriggerInHost
          ? 'border-accent bg-panel text-accent hover:bg-accent-weak'
          : 'border-border bg-panel text-text hover:bg-hover'
      }`}
    >
      {getLocalizedText(
        config.openLabel || { 'zh-CN': '编辑此行并重新查价', en: 'Edit this row' },
        locale,
        t,
      )}
    </button>
  );

  if (!open && renderTriggerInHost) {
    return triggerHost ? createPortal(openButton, triggerHost) : null;
  }

  return (
    <div
      data-testid="review-drawer-edit-form"
      className={`border-border bg-panel min-h-0 border-b ${
        open ? 'flex h-full flex-col overflow-hidden' : 'px-4 py-2'
      }`}
    >
      {!open ? (
        openButton
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
              {previewCandidates.length > 0 && (
                <div
                  className="border-border border-t p-3"
                  data-testid="review-drawer-edit-preview-candidates"
                >
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <h4 className="text-text text-sm font-semibold">
                      {getLocalizedText(
                        previewConfig.candidatesTitle || {
                          'zh-CN': '产品与报价候选',
                          en: 'Product and price candidates',
                        },
                        locale,
                        t,
                      )}
                    </h4>
                    <span className="text-text-3 text-xs">
                      {previewCandidates.length}{' '}
                      {locale.toLowerCase().startsWith('zh') ? '个候选' : 'candidates'}
                    </span>
                  </div>
                  {previewConfig.candidateLayout === 'comparisonTable' ? (
                    <CandidateComparisonTable
                      candidates={previewCandidates}
                      tableConfig={previewConfig.candidateTable || {}}
                      selectedKey={selectedPreviewId}
                      recommendedKey={String(
                        readPath(
                          preview,
                          previewConfig.recommendedIdField || 'recommendedPreviewId',
                        ) || '',
                      )}
                      referenceRecord={preview}
                      locale={locale}
                      t={t}
                      testIdPrefix="review-drawer-edit-preview-candidate"
                      keyField={previewConfig.previewIdField || 'previewId'}
                      confirmableField={previewConfig.confirmableField || 'confirmable'}
                      onSelect={(_candidate, candidateId) => setSelectedPreviewId(candidateId)}
                    />
                  ) : (
                    <div className="grid gap-2" role="radiogroup">
                      {previewCandidates.map((candidate: any, index: number) => {
                        const candidateId = String(
                          readPath(candidate, previewConfig.previewIdField || 'previewId') || index,
                        );
                        const selected = candidateId === selectedPreviewId;
                        const candidateConfirmable = Boolean(
                          readPath(candidate, previewConfig.confirmableField || 'confirmable'),
                        );
                        const matchScore = readPath(
                          candidate,
                          previewConfig.candidateMatchScoreField || 'matchScore',
                        );
                        const procurementScore = readPath(
                          candidate,
                          previewConfig.candidateProcurementScoreField || 'procurementScore',
                        );
                        const reasons = readPath(
                          candidate,
                          previewConfig.candidateReasonsField || 'matchReasons',
                        );
                        const candidateFields = Array.isArray(previewConfig.candidateFields)
                          ? previewConfig.candidateFields
                          : [];
                        const hasUsableLadder = candidateFields.some((field: any) => {
                          if (field.format !== 'ladder') return false;
                          const rungs = parseLadderRungs(readPath(candidate, field.field));
                          return Boolean(rungs && rungs.length >= 2);
                        });
                        return (
                          <div
                            key={candidateId}
                            role="radio"
                            tabIndex={candidateConfirmable ? 0 : -1}
                            aria-checked={selected}
                            aria-disabled={!candidateConfirmable}
                            onClick={() => {
                              if (candidateConfirmable) setSelectedPreviewId(candidateId);
                            }}
                            onKeyDown={(event) => {
                              if (
                                candidateConfirmable &&
                                (event.key === 'Enter' || event.key === ' ')
                              ) {
                                event.preventDefault();
                                setSelectedPreviewId(candidateId);
                              }
                            }}
                            data-testid={`review-drawer-edit-preview-candidate-${candidateId}`}
                            className={`rounded-card border p-3 text-left transition-colors ${
                              selected
                                ? 'border-accent bg-accent-weak ring-accent ring-1'
                                : 'border-border bg-panel hover:bg-hover'
                            } ${candidateConfirmable ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
                          >
                            <div className="flex flex-wrap items-start justify-between gap-2">
                              <div className="min-w-0">
                                <div className="text-text font-semibold [overflow-wrap:anywhere]">
                                  {String(
                                    readPath(
                                      candidate,
                                      previewConfig.candidateTitleField || 'productModel',
                                    ) || '—',
                                  )}
                                </div>
                                <div className="text-text-2 mt-0.5 text-xs [overflow-wrap:anywhere]">
                                  {String(
                                    readPath(
                                      candidate,
                                      previewConfig.candidateSubtitleField || 'description',
                                    ) || '—',
                                  )}
                                </div>
                              </div>
                              <div className="flex shrink-0 flex-wrap gap-1.5 text-xs">
                                {matchScore !== undefined && matchScore !== null && (
                                  <span className="rounded-full bg-blue-50 px-2 py-1 text-blue-700">
                                    {locale.toLowerCase().startsWith('zh') ? '匹配' : 'Match'}{' '}
                                    {String(matchScore)}
                                  </span>
                                )}
                                {procurementScore !== undefined && procurementScore !== null && (
                                  <span className="rounded-full bg-emerald-50 px-2 py-1 text-emerald-700">
                                    {locale.toLowerCase().startsWith('zh') ? '采购' : 'Buy'}{' '}
                                    {String(procurementScore)}
                                  </span>
                                )}
                              </div>
                            </div>
                            <div className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-2">
                              {candidateFields.map((field: any) => {
                                const key = String(field.key || field.field || field.label);
                                const value = readPath(candidate, field.field);
                                const ladder =
                                  field.format === 'ladder' ? parseLadderRungs(value) : null;
                                if (field.format === 'price-comparison' && hasUsableLadder)
                                  return null;
                                if (field.format === 'ladder' && (!ladder || ladder.length < 2))
                                  return null;
                                if (field.hideWhenEmpty && isEmptyValue(value)) return null;
                                const externalUrl =
                                  field.format === 'link' ? safeExternalUrl(value) : null;
                                const spansFullWidth =
                                  field.format === 'price-comparison' || field.format === 'ladder';
                                return (
                                  <div
                                    key={key}
                                    className={`text-xs ${spansFullWidth ? 'sm:col-span-2' : ''}`}
                                  >
                                    <div className="text-text-3 mb-0.5">
                                      {getLocalizedText(field.label || key, locale, t)}：
                                    </div>
                                    <div className="text-text-2 [overflow-wrap:anywhere]">
                                      {field.format === 'price-comparison' ? (
                                        <PriceComparison
                                          original={value}
                                          factored={readPath(candidate, field.factoredField)}
                                          factor={readPath(candidate, field.factorField)}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : field.format === 'ladder' && ladder ? (
                                        <PriceLadder
                                          rowKey={`preview-${candidateId}-${key}`}
                                          rungs={ladder}
                                          factor={readPath(candidate, field.factorField)}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : externalUrl ? (
                                        <a
                                          href={externalUrl}
                                          target="_blank"
                                          rel="noopener noreferrer"
                                          className="text-accent underline underline-offset-2"
                                          data-testid={`review-drawer-edit-preview-candidate-${candidateId}-link-${key}`}
                                          onClick={(event) => event.stopPropagation()}
                                        >
                                          {getLocalizedText(
                                            field.linkLabel || { 'zh-CN': '查看', en: 'Open' },
                                            locale,
                                            t,
                                          )}
                                        </a>
                                      ) : (
                                        formatConfiguredValue(value, field, locale, t)
                                      )}
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                            {Array.isArray(reasons) && reasons.length > 0 && (
                              <div
                                className="text-text-2 mt-2 text-xs"
                                data-testid={`review-drawer-edit-preview-candidate-${candidateId}-reasons`}
                              >
                                {reasons.map(String).join(' · ')}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
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
            className="border-border bg-panel flex shrink-0 flex-wrap items-center justify-between gap-3 border-t px-4 py-2.5"
          >
            {previewConfig.candidateLayout === 'comparisonTable' && selectedPreviewCandidate ? (
              <CandidateSelectionSummary
                candidate={selectedPreviewCandidate}
                config={previewConfig.candidateTable?.selectionSummary}
                referenceRecord={preview}
                locale={locale}
                t={t}
              />
            ) : (
              <span />
            )}
            <div className="flex flex-wrap justify-end gap-2">
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
                  setSelectedPreviewId('');
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
                        config.confirmLabel || {
                          'zh-CN': '确认并采用',
                          en: 'Confirm and adopt',
                        },
                        locale,
                        t,
                      )}
                </button>
              )}
            </div>
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
  const usesCandidateComparisonTable = candidatesConfig.layout === 'comparisonTable';
  const candidateTableConfig = candidatesConfig.table || {};
  const editFormConfig = (block as any).editForm;
  const renderEditTriggerInCandidatesHeader =
    hasCandidatesConfig && editFormConfig?.openPlacement === 'candidates-header';
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
  const shouldShowDecisionStatus = candidatesConfig.showDecisionStatus !== false;
  const shouldShowSelectedCandidateDetail = candidatesConfig.showSelectedDetail !== false;
  const usesSummaryEvidence = candidatesConfig.selectedEvidenceMode === 'summary';
  const blockLayoutMode = String((block as any).layoutMode || '');
  const compareLayoutMode = String(compareConfig.layoutMode || '');
  const contextSummaryConfig = (block as any).contextSummary;
  const usesLightChrome = (block as any).chrome === 'light';
  const isCompactReview = blockLayoutMode === 'compact-review';
  const isStackedCompare = isCompactReview || compareLayoutMode === 'stacked';
  const layoutStorageKey = drawerLayoutStorageKey(runtime, block, context);
  const configuredDefaultLayout: DrawerLayoutState = {
    ...DEFAULT_DRAWER_LAYOUT,
    ...((block as any).defaultLayout || {}),
  };
  const initialLayoutRef = useRef<DrawerLayoutState | null>(null);
  if (initialLayoutRef.current === null) {
    initialLayoutRef.current = readStoredDrawerLayout(
      layoutStorageKey,
      configuredDefaultLayout,
      Boolean((block as any).defaultLayout),
    );
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
  const [editTriggerHost, setEditTriggerHost] = useState<HTMLDivElement | null>(null);
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
    const key = candidateTableConfig.keyField
      ? String(readPath(row, candidateTableConfig.keyField) ?? index)
      : candidateKey(row, index);
    return key === selectedCandidateKey;
  });

  useEffect(() => {
    setSelectedCandidateKey('');
    setIsEditFormOpen(false);
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
    setIsEditFormOpen(false);
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
  const sourceSummaryItems =
    sourceConfig.showInMain !== false && Array.isArray(sourceConfig.summary?.items)
      ? sourceConfig.summary.items
      : [];
  const rawFields = Array.isArray(compareConfig.rawFields) ? compareConfig.rawFields : [];
  const rawColumnGroups = resolveRawColumnGroups(rawRecord, compareConfig);
  const canonicalFields = Array.isArray(compareConfig.canonicalFields)
    ? compareConfig.canonicalFields
    : [];
  const resolvedCanonicalGroups = compareConfig.canonicalFieldProfiles
    ? resolveProfiledFieldGroups({
        item: {
          fieldColumns: canonicalFields,
          fieldProfiles: compareConfig.canonicalFieldProfiles,
        },
        record: canonicalRecord,
        referenceRecord: record,
      })
    : [];
  const resolvedCanonicalFields =
    resolvedCanonicalGroups.length > 0
      ? resolvedCanonicalGroups.flatMap((group) => group.fields)
      : compareConfig.canonicalFieldProfiles
        ? resolveProfiledFieldColumns({
            item: {
              fieldColumns: canonicalFields,
              fieldProfiles: compareConfig.canonicalFieldProfiles,
            },
            record: canonicalRecord,
            referenceRecord: record,
          })
        : canonicalFields;
  const sourceCards = Array.isArray(sourceConfig.cards) ? sourceConfig.cards : [];
  const sourcePolicies = Array.isArray(sourceConfig.policies) ? sourceConfig.policies : [];
  const sourceResolutions = collectSemanticResolutionTraces(
    sourceRecord,
    sourceConfig.resolutionGroups,
  );
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
  const hasRawComparePanel = rawFields.length > 0 || rawColumnGroups.length > 0;
  const hasComparePanel = hasRawComparePanel || resolvedCanonicalFields.length > 0;
  const hasSourceDetails =
    sourceConfig.showInMain !== false &&
    (sourceResolutions.length > 0 ||
      sourceCards.length > 0 ||
      sourcePolicies.length > 0 ||
      sourceJsonFields.length > 0 ||
      Boolean(sourceConfig.jsonField));
  const hasExportDetails =
    exportConfig.showInMain !== false && (exportFields.length > 0 || exportRows.length > 0);
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
        className={`flex min-h-12 cursor-move items-center justify-between gap-3 overflow-hidden border-b px-4 ${
          usesLightChrome
            ? 'border-border bg-panel text-text'
            : 'border-accent bg-accent text-white'
        }`}
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
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ↑
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '下一行', 'Next row')}
            onClick={() => jumpRow(1)}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ↓
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '切换最大化', 'Toggle maximize')}
            onClick={() => setIsMaximized((value) => !value)}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            □
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '关闭复核浮层', 'Close review drawer')}
            onClick={closeDrawer}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ×
          </button>
        </div>
      </div>

      {contextSummaryConfig ? (
        <div
          data-testid="review-drawer-context-summary"
          className="border-border bg-panel border-b px-4 py-3"
        >
          <div className="rounded-control border-border bg-subtle flex min-w-0 flex-wrap items-center gap-x-6 gap-y-1 border px-3 py-2 text-sm">
            <div className="min-w-[240px] flex-1 [overflow-wrap:anywhere]">
              <span className="text-text-2">
                {getLocalizedText(
                  contextSummaryConfig.label || { 'zh-CN': '原始需求', en: 'Original request' },
                  locale,
                  t,
                )}
                ：
              </span>
              <span className="text-text font-semibold">
                {formatConfiguredValue(
                  readPath(record, contextSummaryConfig.valueField),
                  contextSummaryConfig,
                  locale,
                  t,
                )}
              </span>
            </div>
            {contextSummaryConfig.quantityField && (
              <div className="shrink-0 whitespace-nowrap">
                <span className="text-text-2">
                  {getLocalizedText(
                    contextSummaryConfig.quantityLabel || {
                      'zh-CN': '需求数量',
                      en: 'Required quantity',
                    },
                    locale,
                    t,
                  )}
                  ：
                </span>
                <span className="text-text font-semibold tabular-nums">
                  {formatValue(readPath(record, contextSummaryConfig.quantityField))}
                </span>
              </div>
            )}
          </div>
        </div>
      ) : (
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
      )}

      <DrawerEditForm
        key={selectedRecordKey}
        config={editFormConfig}
        record={record}
        runtime={runtime}
        locale={locale}
        t={t}
        onOpenChange={setIsEditFormOpen}
        renderTriggerInHost={renderEditTriggerInCandidatesHeader}
        triggerHost={editTriggerHost}
      />

      <div
        data-testid="review-drawer-content-grid"
        data-layout-mode={isCompactReview ? 'compact-review' : 'default'}
        className={`bg-subtle min-h-0 max-w-full overflow-hidden p-4 ${
          isEditFormOpen ? 'hidden' : ''
        }`}
      >
        <div
          data-testid="review-drawer-content-layout"
          className={`grid h-full min-h-0 min-w-0 gap-3 ${
            hasLeftRail && hasCandidatesConfig
              ? isCompactReview
                ? 'xl:grid-cols-[minmax(320px,0.34fr)_minmax(0,1fr)]'
                : 'xl:grid-cols-[minmax(0,1fr)_minmax(380px,440px)]'
              : 'grid-cols-1'
          }`}
        >
          {hasLeftRail && (
            <div className="min-h-0 min-w-0 space-y-3 overflow-auto pr-1">
              {hasComparePanel && (
                <div
                  data-testid="review-drawer-tab-compare"
                  data-layout-mode={isStackedCompare ? 'stacked' : 'side-by-side'}
                  className={`grid min-w-0 gap-3 ${isStackedCompare ? '' : 'lg:grid-cols-2'}`}
                >
                  {hasRawComparePanel && (
                    <section
                      data-testid="review-drawer-raw-panel"
                      className="rounded-card border-border bg-panel overflow-hidden border"
                    >
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
                      {rawFields.length > 0 && (
                        <FieldRows fields={rawFields} record={rawRecord} locale={locale} t={t} />
                      )}
                      {rawColumnGroups.map((group) => (
                        <RawColumnsPanel
                          key={group.key}
                          columns={group.columns}
                          config={group.config}
                          panelKey={group.legacyTestId ? undefined : group.key}
                          locale={locale}
                          t={t}
                        />
                      ))}
                    </section>
                  )}
                  {resolvedCanonicalFields.length > 0 && (
                    <section
                      data-testid="review-drawer-canonical-panel"
                      className="rounded-card border-border bg-panel overflow-hidden border"
                    >
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
                      {resolvedCanonicalGroups.length > 0 ? (
                        <FieldGroups
                          groups={resolvedCanonicalGroups}
                          record={canonicalRecord}
                          fallbackRecord={record}
                          locale={locale}
                          t={t}
                        />
                      ) : (
                        <FieldRows
                          fields={resolvedCanonicalFields}
                          record={canonicalRecord}
                          fallbackRecord={record}
                          locale={locale}
                          t={t}
                        />
                      )}
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
                    <SemanticResolutionEvidence
                      traces={sourceResolutions}
                      locale={locale}
                      t={t}
                      title={sourceConfig.resolutionTitle}
                    />
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
              <header
                data-testid="review-drawer-candidates-header"
                className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-3 py-2"
              >
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
                {renderEditTriggerInCandidatesHeader && (
                  <div
                    ref={setEditTriggerHost}
                    data-testid="review-drawer-edit-open-host"
                    className="flex shrink-0"
                  />
                )}
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
                className={`min-h-0 flex-1 overflow-auto ${
                  usesCandidateComparisonTable ? 'p-0' : 'space-y-1.5 p-2'
                }`}
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
                ) : usesCandidateComparisonTable ? (
                  <CandidateComparisonTable
                    candidates={candidates}
                    tableConfig={candidateTableConfig}
                    selectedKey={selectedCandidateKey}
                    recommendedKey={
                      candidateTableConfig.recommendedKeyField
                        ? String(readPath(record, candidateTableConfig.recommendedKeyField) || '')
                        : undefined
                    }
                    referenceRecord={record}
                    locale={locale}
                    t={t}
                    testIdPrefix="review-drawer-candidate"
                    keyField={candidateTableConfig.keyField}
                    confirmableField={candidateTableConfig.confirmableField}
                    onSelect={(candidate, rowKey) => {
                      setSelectedCandidateKey(rowKey);
                      if (candidatesConfig.selection?.bind) {
                        writeRuntimeState(runtime, candidatesConfig.selection.bind, candidate);
                      }
                    }}
                  />
                ) : (
                  candidates.map((candidate: any, index: number) => {
                    const rowKey = candidateKey(candidate, index);
                    const scoreKey = String(
                      candidate?.bom_me_material_code ?? candidate?.materialCode ?? rowKey,
                    );
                    const active = rowKey === selectedCandidateKey;
                    const item = candidatesConfig.item || {};
                    const titleText = formatValue(readPath(candidate, item.titleField), rowKey);
                    const score = item.scoreField
                      ? readPath(candidate, item.scoreField)
                      : undefined;
                    const scoreColor = item.statusColorField
                      ? readPath(candidate, item.statusColorField)
                      : undefined;
                    const fieldColumns = resolveCandidateFieldColumns({
                      item,
                      candidate,
                      referenceRecord: {
                        ...(record || {}),
                        ...(canonicalRecord || {}),
                      },
                    });
                    const hasUsableLadder = fieldColumns.some((field: any) => {
                      if (field.format !== 'ladder') return false;
                      const rungs = parseLadderRungs(readFieldValue(candidate, field));
                      return Boolean(rungs && rungs.length >= 2);
                    });
                    const badgeFields = Array.isArray(item.badgeFields) ? item.badgeFields : [];
                    const usesFieldTable =
                      candidatesConfig.layout === 'fieldTable' || item.layout === 'fieldTable';
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
                        className={`rounded-card block w-full border p-3 text-left select-text ${
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
                            {usesFieldTable ? (
                              <CandidateFieldTable
                                rowKey={rowKey}
                                candidate={candidate}
                                fields={fieldColumns}
                                badges={badgeFields}
                                locale={locale}
                                t={t}
                              />
                            ) : (
                              <dl className="mt-2 grid gap-x-3 gap-y-1.5 text-xs sm:grid-cols-2">
                                {fieldColumns.map((field: any) => {
                                  const key = String(field.key || field.field || field.label);
                                  const label = getLocalizedText(field.label || key, locale, t);
                                  const rawValue = readFieldValue(candidate, field);
                                  const ladder =
                                    field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
                                  if (field.format === 'price-comparison' && hasUsableLadder)
                                    return null;
                                  if (field.format === 'ladder' && (!ladder || ladder.length < 2))
                                    return null;
                                  if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                                  const value = formatConfiguredValue(rawValue, field, locale, t);
                                  const comparisonStatus = comparisonStatusForField(
                                    candidate,
                                    field,
                                  );
                                  const comparisonClass =
                                    comparisonStatusFieldClass(comparisonStatus);
                                  return (
                                    <div
                                      key={key}
                                      data-testid={`review-drawer-candidate-${rowKey}-field-${key}`}
                                      data-comparison-status={
                                        comparisonStatus ? String(comparisonStatus) : undefined
                                      }
                                      className={`min-w-0 ${comparisonClass} ${
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
                                        ) : field.format === 'ladder' && ladder ? (
                                          <PriceLadder
                                            rowKey={rowKey}
                                            rungs={ladder}
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
                            )}
                          </div>
                          {score !== undefined && (
                            <span
                              data-testid={`review-drawer-candidate-${scoreKey}-score`}
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
              {(shouldShowDecisionStatus ||
                (shouldShowSelectedCandidateDetail &&
                  selectedCandidate &&
                  (selectedCandidateFields.length > 0 || selectedCandidateGroups.length > 0))) && (
                <section
                  data-testid="review-drawer-decision-panel"
                  className="bg-subtle border-border max-h-[48%] shrink-0 overflow-auto border-t p-2.5"
                >
                  {shouldShowDecisionStatus && (
                    <section data-testid="review-drawer-decision-status">
                      <h3 className="text-text text-sm font-semibold">
                        {getLocalizedText(
                          candidatesConfig.decisionTitle || {
                            'zh-CN': '当前决策状态',
                            en: 'Decision',
                          },
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
                    </section>
                  )}
                  {shouldShowSelectedCandidateDetail &&
                    selectedCandidate &&
                    (selectedCandidateFields.length > 0 || selectedCandidateGroups.length > 0) && (
                      <section
                        className={`rounded-control border-border bg-panel border ${
                          shouldShowDecisionStatus ? 'mt-3' : ''
                        }`}
                      >
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
                        {usesSummaryEvidence ? (
                          <div className="space-y-2 p-2.5">
                            <EvidenceSummary candidate={selectedCandidate} locale={locale} t={t} />
                          </div>
                        ) : selectedCandidateGroups.length > 0 ? (
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
              )}
              {/*
              The actions live outside the decision panel on purpose. Inside it they were the last
              child of a max-h-[48%] overflow-auto section, so "确认此报价" scrolled out of sight
              behind the evidence it was meant to confirm. As a shrink-0 sibling in the aside's flex
              column they stay on screen however long the candidate detail runs.
            */}
              {(candidatesConfig.actions || []).length > 0 && (
                <footer
                  data-testid="review-drawer-actions"
                  className="border-border bg-panel flex shrink-0 flex-wrap items-center justify-between gap-3 border-t px-3 py-2.5"
                >
                  {usesCandidateComparisonTable && selectedCandidate ? (
                    <CandidateSelectionSummary
                      candidate={selectedCandidate}
                      config={candidateTableConfig.selectionSummary}
                      referenceRecord={record}
                      locale={locale}
                      t={t}
                    />
                  ) : (
                    <span />
                  )}
                  <div className="flex flex-wrap justify-end gap-2">
                    {candidatesConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
                      const code = String(
                        actionConfig.code || actionConfig.id || actionConfig.label,
                      );
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
                  </div>
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
