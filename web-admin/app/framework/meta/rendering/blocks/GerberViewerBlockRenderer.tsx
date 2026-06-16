import React from 'react';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  readDataSourceRecord,
  readDataSourceRows,
  readPath,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
} from './workbenchBlockUtils';

type BoardSide = 'top' | 'bottom' | 'unknown';
type SideFilter = Exclude<BoardSide, 'unknown'>;
type IssueSeverity = 'error' | 'warning' | 'info';
type IssueFilter = IssueSeverity | 'all';

interface BoardBounds {
  xMinMm: number;
  yMinMm: number;
  xMaxMm: number;
  yMaxMm: number;
  widthMm: number;
  heightMm: number;
}

interface ValidationIssue {
  severity: IssueSeverity;
  code: string;
  refdes?: string;
  message: string;
}

interface ComponentInspection {
  refdes: string;
  footprint: string;
  xMm: number;
  yMm: number;
  side: BoardSide;
  smd?: boolean;
  pins?: number;
  rotation?: number;
  issues?: ValidationIssue[];
  bomItem?: {
    materialName?: string;
    process?: string;
  };
}

interface LayerManifestItem {
  filename: string;
  role: string;
  side?: BoardSide;
  kind?: string;
  flashCount?: number;
  hitCount?: number;
}

interface DrillFile {
  filename: string;
  plated?: boolean;
  hitCount?: number;
}

interface QuoteInspection {
  project?: {
    code?: string;
    name?: string;
  };
  board?: Partial<BoardBounds>;
  boardSvgUrls?: {
    top?: string;
    bottom?: string;
  };
  summary?: Record<string, number>;
  layerManifest?: LayerManifestItem[];
  drillFiles?: DrillFile[];
  issues?: ValidationIssue[];
  excludedBomRefs?: Array<{ refdes: string; bomItem?: Record<string, unknown> }>;
  components?: ComponentInspection[];
}

interface LoadState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  inspection?: QuoteInspection;
  message?: string;
}

interface BoardImageState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  sourceUrl?: string;
  src?: string;
  message?: string;
}

type GerberPreviewUnavailableKind =
  | 'missing-upload'
  | 'parse-error'
  | 'needs-review'
  | 'artifact-error';

interface GerberPreviewUnavailableState {
  kind: GerberPreviewUnavailableKind;
  title: string;
  detail: string;
  evidence?: string;
}

export interface GerberViewerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const DEFAULT_LINE_INSPECTION_FIELD = 'qo_ql_gerber_inspection';

const SIDE_OPTIONS: Array<[SideFilter, string]> = [
  ['top', 'Top'],
  ['bottom', 'Bottom'],
];

const ISSUE_OPTIONS: Array<[IssueFilter, string]> = [
  ['all', 'All'],
  ['error', 'Errors'],
  ['warning', 'Warnings'],
  ['info', 'Info'],
];

const FILE_PID_URL_PATTERN = /^\/?([0-9A-HJKMNP-TV-Z]{26})(?:\.[A-Za-z0-9]+)?$/;

const severityClass: Record<IssueSeverity, string> = {
  error: 'border-rose-300 border-l-4 bg-rose-50 text-rose-900 shadow-sm',
  warning: 'border-amber-300 border-l-4 bg-amber-50 text-amber-900 shadow-sm',
  info: 'border-blue-200 border-l-4 bg-blue-50 text-blue-900',
};

const severityRank: Record<IssueSeverity, number> = {
  error: 0,
  warning: 1,
  info: 2,
};

function tryParseJsonText(value: string): unknown {
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function isJsonEnvelope(value: unknown): value is { type?: unknown; value: unknown } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const type = String(record.type || '').toLowerCase();
  return (
    (type === 'json' || type === 'jsonb') && Object.prototype.hasOwnProperty.call(record, 'value')
  );
}

function parseJsonLike(value: unknown): unknown {
  let current = value;
  for (let depth = 0; depth < 8; depth += 1) {
    if (isJsonEnvelope(current)) {
      current =
        typeof current.value === 'string' ? tryParseJsonText(current.value) : current.value;
      continue;
    }
    if (typeof current === 'string') {
      const parsed = tryParseJsonText(current);
      if (parsed !== current) {
        current = parsed;
        continue;
      }
    }
    break;
  }
  return current;
}

function normalizeStringList(value: unknown): string[] {
  const parsed = parseJsonLike(value);
  if (Array.isArray(parsed)) {
    return parsed
      .map((item) => (typeof item === 'string' ? item : JSON.stringify(item)))
      .map((item) => item.trim())
      .filter(Boolean);
  }
  if (typeof parsed === 'string' && parsed.trim()) {
    return [parsed.trim()];
  }
  return [];
}

function normalizeInspection(value: unknown): QuoteInspection | undefined {
  const parsed = parseJsonLike(value);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
  return parsed as QuoteInspection;
}

function numberOr(value: unknown, fallback: number): number {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
}

function optionalNumber(value: unknown): number | undefined {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : undefined;
}

function hasNonBlankPath(line: Record<string, any>, field: string): boolean {
  const value = readPath(line, field);
  return value !== undefined && value !== null && String(value).trim() !== '';
}

function hasPositiveNumberPath(line: Record<string, any>, field: string): boolean {
  const numeric = Number(readPath(line, field));
  return Number.isFinite(numeric) && numeric > 0;
}

function hasLineGerberFacts(line: Record<string, any> | undefined): boolean {
  if (!line) return false;
  const positiveNumberFields = [
    'qo_ql_smt_points',
    'qo_ql_tht_points',
    'qo_ql_pin_count',
    'qo_ql_hole_count',
    'qo_ql_positioning_pin_count',
    'qo_ql_function_pin_count',
    'qo_ql_board_width_mm',
    'qo_ql_board_height_mm',
    'qo_ql_board_area_mm2',
  ];
  const statusFields = ['qo_ql_gerber_parse_status', 'qo_ql_gerber_validation_status'];
  return (
    positiveNumberFields.some((field) => hasPositiveNumberPath(line, field)) ||
    statusFields.some((field) => hasNonBlankPath(line, field)) ||
    normalizeStringList(readPath(line, 'qo_ql_gerber_validation_messages')).length > 0
  );
}

function hasLineInspection(
  line: Record<string, any> | undefined,
  lineInspectionField: string,
): boolean {
  return Boolean(normalizeInspection(readPath(line, lineInspectionField)));
}

function hasLineInspectionOrFacts(
  line: Record<string, any> | undefined,
  lineInspectionField: string,
): boolean {
  return hasLineInspection(line, lineInspectionField) || hasLineGerberFacts(line);
}

function firstLineWithInspectionOrFacts(
  rows: any[],
  lineInspectionField: string,
): Record<string, any> | undefined {
  const records = rows.filter((row): row is Record<string, any> =>
    Boolean(row && typeof row === 'object' && !Array.isArray(row)),
  );
  return (
    records.find((row) => hasLineInspection(row, lineInspectionField)) ||
    records.find((row) => hasLineGerberFacts(row))
  );
}

function recordPid(record: Record<string, any> | undefined): string | undefined {
  const value = readPath(record, 'pid') || readPath(record, 'id');
  if (value === undefined || value === null) return undefined;
  const text = String(value).trim();
  return text || undefined;
}

function matchingDataSourceLine(
  selected: Record<string, any> | undefined,
  rows: any[],
): Record<string, any> | undefined {
  const selectedPid = recordPid(selected);
  if (!selectedPid) return undefined;
  return rows.find((row): row is Record<string, any> => {
    if (!row || typeof row !== 'object' || Array.isArray(row)) return false;
    return recordPid(row) === selectedPid;
  });
}

function chooseLineRecord(
  selectedLine: unknown,
  rows: any[],
  lineInspectionField: string,
): Record<string, any> | undefined {
  const selected =
    selectedLine && typeof selectedLine === 'object' && !Array.isArray(selectedLine)
      ? (selectedLine as Record<string, any>)
      : undefined;
  if (hasLineInspection(selected, lineInspectionField)) return selected;
  const matched = matchingDataSourceLine(selected, rows);
  if (hasLineInspection(matched, lineInspectionField)) return matched;
  if (hasLineInspectionOrFacts(selected, lineInspectionField)) return selected;
  return firstLineWithInspectionOrFacts(rows, lineInspectionField) || matched || selected;
}

function severityFromValidationStatus(status: unknown): IssueSeverity {
  const normalized = String(status || '').toLowerCase();
  if (normalized.includes('error') || normalized.includes('fail')) return 'error';
  if (normalized.includes('warn') || normalized.includes('review')) return 'warning';
  return 'info';
}

function actionableSeverityFromValidationStatus(status: unknown): IssueSeverity | undefined {
  const severity = severityFromValidationStatus(status);
  return severity === 'info' ? undefined : severity;
}

function issueCodeFromMessage(message: string, index: number): string {
  const code = message
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 48);
  return code || `LINE_VALIDATION_${index + 1}`;
}

function lineIssues(line: Record<string, any> | undefined): ValidationIssue[] {
  if (!line) return [];
  const severity = severityFromValidationStatus(readPath(line, 'qo_ql_gerber_validation_status'));
  return normalizeStringList(readPath(line, 'qo_ql_gerber_validation_messages')).map(
    (message, index) => ({
      severity,
      code: issueCodeFromMessage(message, index),
      message,
    }),
  );
}

function issueCounts(issues: ValidationIssue[]): { errorCount: number; warningCount: number } {
  return {
    errorCount: issues.filter((issue) => issue.severity === 'error').length,
    warningCount: issues.filter((issue) => issue.severity === 'warning').length,
  };
}

function issueStats(issues: ValidationIssue[]): {
  errorCount: number;
  warningCount: number;
  infoCount: number;
  totalCount: number;
} {
  return {
    errorCount: issues.filter((issue) => issue.severity === 'error').length,
    warningCount: issues.filter((issue) => issue.severity === 'warning').length,
    infoCount: issues.filter((issue) => issue.severity === 'info').length,
    totalCount: issues.length,
  };
}

function localized(locale: string, zh: string, en: string): string {
  return locale.toLowerCase().startsWith('zh') ? zh : en;
}

function layersFromLine(line: Record<string, any> | undefined): LayerManifestItem[] {
  if (!line) return [];
  const smt = optionalNumber(readPath(line, 'qo_ql_smt_points'));
  const tht = optionalNumber(readPath(line, 'qo_ql_tht_points'));
  const layers: LayerManifestItem[] = [];
  if (smt !== undefined) {
    layers.push({
      filename: 'sidecar-gerber-flashes',
      role: 'smt_points',
      side: 'unknown',
      kind: 'gerber',
      flashCount: smt,
    });
  }
  if (tht !== undefined) {
    layers.push({
      filename: 'sidecar-excellon-drills',
      role: 'tht_drill',
      side: 'unknown',
      kind: 'drill',
      hitCount: tht,
    });
  }
  return layers;
}

function mergeLineFactsIntoInspection(
  inspection: QuoteInspection | undefined,
  line: Record<string, any> | undefined,
  options: { preferInspectionIssues?: boolean } = {},
): QuoteInspection | undefined {
  if (!line || !hasLineGerberFacts(line)) return inspection;
  const smt = optionalNumber(readPath(line, 'qo_ql_smt_points'));
  const tht = optionalNumber(readPath(line, 'qo_ql_tht_points'));
  const width = optionalNumber(readPath(line, 'qo_ql_board_width_mm'));
  const height = optionalNumber(readPath(line, 'qo_ql_board_height_mm'));
  const messages = lineIssues(line);
  const shouldUseLineMessages =
    messages.length > 0 &&
    (!options.preferInspectionIssues || !(inspection?.issues && inspection.issues.length > 0));
  const counts = issueCounts(messages);
  const lineLayers = layersFromLine(line);
  const merged: QuoteInspection = {
    ...(inspection || {}),
    project: {
      ...(inspection?.project || {}),
      code: String(
        readPath(line, 'qo_ql_source_ref') ||
          readPath(line, 'qo_ql_mpn') ||
          readPath(line, 'pid') ||
          inspection?.project?.code ||
          'Gerber inspection',
      ),
      name: String(readPath(line, 'qo_ql_description') || inspection?.project?.name || ''),
    },
    board: {
      ...(inspection?.board || {}),
      ...(width !== undefined ? { widthMm: width, xMaxMm: width } : {}),
      ...(height !== undefined ? { heightMm: height, yMaxMm: height } : {}),
    },
    summary: {
      ...(inspection?.summary || {}),
      ...(smt !== undefined ? { smdCount: smt } : {}),
      ...(tht !== undefined ? { thtCount: tht } : {}),
      ...(shouldUseLineMessages ? counts : {}),
    },
    ...(shouldUseLineMessages ? { issues: messages } : {}),
    ...(lineLayers.length > 0 ? { layerManifest: lineLayers } : {}),
    ...(tht !== undefined
      ? { drillFiles: [{ filename: 'sidecar-excellon-drills', plated: true, hitCount: tht }] }
      : {}),
  };
  return merged;
}

function boardBounds(
  inspection: QuoteInspection | undefined,
  line: Record<string, any>,
): BoardBounds {
  const board = inspection?.board || {};
  const width = numberOr(board.widthMm, numberOr(readPath(line, 'qo_ql_board_width_mm'), 100));
  const height = numberOr(board.heightMm, numberOr(readPath(line, 'qo_ql_board_height_mm'), 60));
  const xMin = numberOr(board.xMinMm, 0);
  const yMin = numberOr(board.yMinMm, 0);
  const xMax = numberOr(board.xMaxMm, xMin + width);
  const yMax = numberOr(board.yMaxMm, yMin + height);
  return {
    xMinMm: xMin,
    yMinMm: yMin,
    xMaxMm: xMax,
    yMaxMm: yMax,
    widthMm: Math.max(1, width),
    heightMm: Math.max(1, height),
  };
}

function matchesSearch(component: ComponentInspection, normalizedQuery: string): boolean {
  if (!normalizedQuery) return true;
  return (
    (component.refdes || '').toUpperCase().includes(normalizedQuery) ||
    (component.footprint || '').toUpperCase().includes(normalizedQuery) ||
    (component.bomItem?.materialName || '').toUpperCase().includes(normalizedQuery) ||
    (component.bomItem?.process || '').toUpperCase().includes(normalizedQuery)
  );
}

function filterComponents(
  inspection: QuoteInspection,
  side: SideFilter,
  issueFilter: IssueFilter,
  query: string,
): ComponentInspection[] {
  const normalizedQuery = query.trim().toUpperCase();
  return (inspection.components || []).filter((component) => {
    const sideMatches = component.side === side;
    const issueMatches =
      issueFilter === 'all' ||
      (component.issues || []).some((issue) => issue.severity === issueFilter);
    return sideMatches && issueMatches && matchesSearch(component, normalizedQuery);
  });
}

function filterIssueList(
  issueList: ValidationIssue[],
  issueFilter: IssueFilter,
  query: string,
): ValidationIssue[] {
  const normalizedQuery = query.trim().toUpperCase();
  return issueList
    .filter((issue) => {
      const severityMatches = issueFilter === 'all' || issue.severity === issueFilter;
      const queryMatches =
        !normalizedQuery ||
        issue.refdes?.toUpperCase().includes(normalizedQuery) ||
        issue.code?.toUpperCase().includes(normalizedQuery) ||
        issue.message?.toUpperCase().includes(normalizedQuery);
      return severityMatches && queryMatches;
    })
    .sort((a, b) => severityRank[a.severity] - severityRank[b.severity]);
}

function effectiveValidationIssues(
  inspection: QuoteInspection | undefined,
  line: Record<string, any> | undefined,
): ValidationIssue[] {
  const issues = inspection?.issues || [];
  const lineSeverity = actionableSeverityFromValidationStatus(
    readPath(line, 'qo_ql_gerber_validation_status'),
  );
  if (!lineSeverity || issues.some((issue) => issue.severity !== 'info')) {
    return issues;
  }
  return issues.map((issue) => ({ ...issue, severity: lineSeverity }));
}

function formatNumber(value: number | undefined, digits = 0): string {
  if (value === undefined || !Number.isFinite(value)) return '-';
  return digits > 0 ? value.toFixed(digits) : String(Math.round(value));
}

function boardMetric(board: BoardBounds): string {
  return `${formatNumber(board.widthMm)} x ${formatNumber(board.heightMm)} mm`;
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 50;
  return Math.max(0, Math.min(100, value));
}

function markerStyle(component: ComponentInspection, board: BoardBounds): React.CSSProperties {
  const left = ((component.xMm - board.xMinMm) / board.widthMm) * 100;
  const top = 100 - ((component.yMm - board.yMinMm) / board.heightMm) * 100;
  return {
    left: `${clampPercent(left)}%`,
    top: `${clampPercent(top)}%`,
  };
}

function markerTone(component: ComponentInspection): string {
  if ((component.issues || []).some((issue) => issue.severity === 'error')) {
    return 'border-rose-50 bg-rose-500 shadow-rose-500/30';
  }
  if ((component.issues || []).some((issue) => issue.severity === 'warning')) {
    return 'border-amber-50 bg-amber-500 shadow-amber-500/30';
  }
  return component.smd
    ? 'border-sky-50 bg-sky-400 shadow-sky-400/30'
    : 'border-emerald-50 bg-emerald-500 shadow-emerald-500/30';
}

function componentStatus(component: ComponentInspection): { label: string; className: string } {
  if ((component.issues || []).some((issue) => issue.severity === 'error')) {
    return { label: 'Error', className: 'border-rose-200 bg-rose-50 text-rose-700' };
  }
  if ((component.issues || []).some((issue) => issue.severity === 'warning')) {
    return { label: 'Warning', className: 'border-amber-200 bg-amber-50 text-amber-800' };
  }
  return { label: 'Matched', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' };
}

function layerCount(layer: LayerManifestItem): number {
  return numberOr(layer.flashCount, numberOr(layer.hitCount, 0));
}

function selectedParseStatus(line: Record<string, any>): string {
  const parse = readPath(line, 'qo_ql_gerber_parse_status') || '-';
  const validation = readPath(line, 'qo_ql_gerber_validation_status') || '-';
  return `${parse} / ${validation}`;
}

function normalizedStatus(value: unknown): string {
  const status = String(value || '').trim();
  return status && status !== '-' ? status : '';
}

function lineParseStatus(line: Record<string, any> | undefined): string {
  return normalizedStatus(readPath(line, 'qo_ql_gerber_parse_status'));
}

function lineValidationStatus(line: Record<string, any> | undefined): string {
  return normalizedStatus(readPath(line, 'qo_ql_gerber_validation_status'));
}

function hasGerberStatus(line: Record<string, any> | undefined): boolean {
  return Boolean(lineParseStatus(line) || lineValidationStatus(line));
}

function hasFailedGerberStatus(line: Record<string, any> | undefined): boolean {
  const status = `${lineParseStatus(line)} ${lineValidationStatus(line)}`.toLowerCase();
  return /(fail|error|invalid|exception|rejected)/.test(status);
}

function firstValidationMessage(issues: ValidationIssue[]): string | undefined {
  return issues.map((issue) => issue.message.trim()).find(Boolean);
}

function boardSvgUrl(
  inspection: QuoteInspection | undefined,
  side: SideFilter,
): string | undefined {
  if (!inspection) return undefined;
  const value = inspection.boardSvgUrls?.[side];
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const pidMatch = trimmed.match(FILE_PID_URL_PATTERN);
  if (pidMatch) {
    return `/api/file/download/${pidMatch[1]}`;
  }
  return trimmed;
}

function preferredBoardSvgSide(inspection: QuoteInspection | undefined): SideFilter | undefined {
  if (boardSvgUrl(inspection, 'top')) return 'top';
  if (boardSvgUrl(inspection, 'bottom')) return 'bottom';
  return undefined;
}

function shouldFetchAuthenticatedBoardImage(sourceUrl: string): boolean {
  const trimmed = sourceUrl.trim();
  if (trimmed.startsWith('/api/file/download/')) return true;
  if (!/^https?:\/\//i.test(trimmed) || typeof window === 'undefined') return false;
  try {
    const parsed = new URL(trimmed);
    return parsed.origin === window.location.origin && parsed.pathname.startsWith('/api/file/download/');
  } catch {
    return false;
  }
}

function browserJwtToken(): string | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return (
      window.sessionStorage.getItem(JWT_TOKEN_KEY) ||
      window.localStorage.getItem(JWT_TOKEN_KEY) ||
      undefined
    );
  } catch {
    return undefined;
  }
}

function authenticatedBoardImageInit(): RequestInit {
  const token = browserJwtToken();
  return {
    credentials: 'include',
    ...(token
      ? {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        }
      : {}),
  };
}

function formatUnknownError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function boardImageErrorMessage(sourceUrl: string, reason: string): string {
  return `Could not load the parsed Gerber SVG artifact from ${sourceUrl}: ${reason}.`;
}

function useBoardImageSource(sourceUrl: string | undefined): BoardImageState {
  const [state, setState] = React.useState<BoardImageState>({ status: 'idle' });

  React.useEffect(() => {
    if (!sourceUrl) {
      setState({ status: 'idle' });
      return;
    }

    if (!shouldFetchAuthenticatedBoardImage(sourceUrl)) {
      setState({ status: 'ready', sourceUrl, src: sourceUrl });
      return;
    }

    let cancelled = false;
    let objectUrl: string | undefined;
    setState({ status: 'loading', sourceUrl });

    fetch(sourceUrl, authenticatedBoardImageInit())
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const blob = await response.blob();
        if (blob.type && !blob.type.startsWith('image/')) {
          throw new Error(`unexpected content type ${blob.type}`);
        }
        if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
          throw new Error('object URL API unavailable');
        }
        return blob;
      })
      .then((blob) => {
        const nextObjectUrl = URL.createObjectURL(blob);
        if (cancelled) {
          URL.revokeObjectURL(nextObjectUrl);
          return;
        }
        objectUrl = nextObjectUrl;
        setState({ status: 'ready', sourceUrl, src: nextObjectUrl });
      })
      .catch((error) => {
        if (!cancelled) {
          setState({
            status: 'error',
            sourceUrl,
            message: boardImageErrorMessage(sourceUrl, formatUnknownError(error)),
          });
        }
      });

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [sourceUrl]);

  return state;
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: Array<[T, string]>;
  onChange: (value: T) => void;
}) {
  return (
    <div className="inline-flex items-center gap-1 rounded-md border border-gray-200 bg-white p-1">
      {options.map(([optionValue, label]) => (
        <button
          key={optionValue}
          type="button"
          aria-pressed={value === optionValue}
          onClick={() => onChange(optionValue)}
          className={`min-h-8 rounded px-2.5 text-xs font-medium ${
            value === optionValue
              ? 'bg-blue-600 text-white'
              : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

function Metric({
  id,
  label,
  value,
  tone,
}: {
  id: string;
  label: string;
  value: string | number;
  tone?: string;
}) {
  return (
    <div
      data-testid={`gerber-metric-${id}`}
      className={`min-h-16 rounded-lg border bg-white p-3 ${tone || 'border-gray-200'}`}
    >
      <div className="text-xs font-medium text-gray-500">{label}</div>
      <div className="mt-1 text-lg font-semibold text-gray-900">{value}</div>
    </div>
  );
}

function gerberPreviewUnavailableState({
  locale,
  line,
  issues,
  message,
}: {
  locale: string;
  line: Record<string, any> | undefined;
  issues: ValidationIssue[];
  message?: string;
}): GerberPreviewUnavailableState {
  const evidence = firstValidationMessage(issues);
  if (message) {
    return {
      kind: 'artifact-error',
      title: localized(
        locale,
        '板图文件暂时无法加载',
        'Board preview file could not be loaded',
      ),
      detail: `${message} ${localized(
        locale,
        '不会显示前端生成的替代预览。',
        'No generated preview is shown.',
      )}`,
    };
  }

  if (!hasGerberStatus(line) && issues.length === 0) {
    return {
      kind: 'missing-upload',
      title: localized(locale, '未上传 Gerber 文件', 'No Gerber file uploaded'),
      detail: localized(
        locale,
        '上传 Gerber 压缩包后，系统才会生成真实板图预览和层清单。',
        'Upload a Gerber package to generate the real board preview and layer manifest.',
      ),
    };
  }

  if (hasFailedGerberStatus(line)) {
    return {
      kind: 'parse-error',
      title: localized(locale, 'Gerber 解析需要处理', 'Gerber parsing needs review'),
      detail: localized(
        locale,
        '解析未生成真实板图预览。请查看解析与校验信息后重新上传正确的 Gerber 文件。',
        'No real board preview was generated. Review the parsing and validation messages, then upload a corrected Gerber package.',
      ),
      evidence,
    };
  }

  return {
    kind: 'needs-review',
    title: localized(locale, 'Gerber 预览需要处理', 'Gerber preview needs attention'),
    detail: localized(
      locale,
      '当前层没有生成真实板图预览。请查看解析输出，或重新上传正确的 Gerber 文件。',
      'No real board preview was generated for this side. Review parser output or upload a corrected Gerber package.',
    ),
    evidence,
  };
}

function GerberSvgUnavailable({
  state,
}: {
  state: GerberPreviewUnavailableState;
}) {
  const tone =
    state.kind === 'missing-upload'
      ? 'border-gray-200 bg-gray-50 text-gray-700'
      : state.kind === 'artifact-error'
        ? 'border-amber-200 bg-amber-50 text-amber-900'
        : state.kind === 'parse-error'
          ? 'border-rose-200 bg-rose-50 text-rose-900'
          : 'border-blue-200 bg-blue-50 text-blue-900';
  return (
    <div
      role="status"
      data-testid="gerber-svg-unavailable"
      className={`flex h-full min-h-[220px] w-full items-center justify-center px-6 py-8 text-center ${tone}`}
    >
      <div className="max-w-md">
        <div className="text-sm font-semibold">{state.title}</div>
        <div className="mt-2 text-xs leading-5 opacity-85">{state.detail}</div>
        {state.evidence ? (
          <div className="mt-3 rounded-md border border-current/15 bg-white/60 px-3 py-2 text-left text-xs leading-5">
            {state.evidence}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function ValidationSummary({
  stats,
  locale,
}: {
  stats: ReturnType<typeof issueStats>;
  locale: string;
}) {
  const hasErrors = stats.errorCount > 0;
  const hasWarnings = stats.warningCount > 0;
  const requiresReview = hasErrors || hasWarnings;
  const title = hasErrors
    ? localized(locale, '发现校验错误', 'Validation errors found')
    : hasWarnings
      ? localized(locale, '发现校验警告', 'Validation warnings found')
      : localized(locale, '校验未发现阻断项', 'No blocking validation issues');
  const description = requiresReview
    ? localized(
        locale,
        '请优先处理错误和警告，再进入加工费与报价 Excel。',
        'Review errors and warnings before process fee and quote Excel.',
      )
    : localized(
        locale,
        '当前 Gerber / CPL 校验没有错误或警告。',
        'The current Gerber / CPL validation has no errors or warnings.',
      );
  const tone = hasErrors
    ? 'border-rose-300 bg-rose-50 text-rose-900'
    : hasWarnings
      ? 'border-amber-300 bg-amber-50 text-amber-900'
      : 'border-emerald-200 bg-emerald-50 text-emerald-900';

  return (
    <div
      role={requiresReview ? 'alert' : 'status'}
      data-testid="gerber-validation-summary"
      className={`rounded-lg border p-3 ${tone}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="text-sm font-semibold">{title}</div>
          <div className="mt-1 text-xs opacity-85">{description}</div>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs font-semibold">
          <span className="rounded-full border border-rose-200 bg-white/70 px-2 py-1 text-rose-700">
            {localized(locale, '错误', 'Errors')} {stats.errorCount}
          </span>
          <span className="rounded-full border border-amber-200 bg-white/70 px-2 py-1 text-amber-700">
            {localized(locale, '警告', 'Warnings')} {stats.warningCount}
          </span>
          <span className="rounded-full border border-blue-200 bg-white/70 px-2 py-1 text-blue-700">
            {localized(locale, '信息', 'Info')} {stats.infoCount}
          </span>
        </div>
      </div>
    </div>
  );
}

function GerberSvgLoading() {
  return (
    <div
      role="status"
      data-testid="gerber-svg-loading"
      className="flex h-full min-h-[220px] w-full items-center justify-center bg-gray-50 px-6 py-8 text-center text-gray-700"
    >
      <div>
        <div className="text-sm font-semibold">Loading real Gerber preview...</div>
        <div className="mt-2 text-xs leading-5 text-gray-500">
          Fetching the parser artifact with the active session credentials.
        </div>
      </div>
    </div>
  );
}

export const GerberViewerBlockRenderer: React.FC<GerberViewerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const title = getLocalizedText(
    block.title || (block as any).label || 'Gerber / CPL Viewer',
    locale,
    t,
  );
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const configuredInspection = normalizeInspection(
    resolveRuntimeValue(runtime, (block as any).inspection),
  );
  const hasConfiguredInspection = Boolean(configuredInspection);
  const inspectionUrl = resolveRuntimeValue(runtime, (block as any).inspectionUrl);
  const selectedLineRecord = resolveRuntimeValue(runtime, (block as any).lineContext);
  const lineInspectionField =
    typeof (block as any).lineInspectionField === 'string'
      ? (block as any).lineInspectionField
      : DEFAULT_LINE_INSPECTION_FIELD;
  const dataSourceRows = readDataSourceRows(runtime, dataSourceId);
  const lineRecord =
    chooseLineRecord(selectedLineRecord, dataSourceRows, lineInspectionField) ||
    readDataSourceRecord(runtime, dataSourceId);
  const lineInspection = normalizeInspection(readPath(lineRecord, lineInspectionField));

  useRuntimeStateSubscription(runtime);
  useDataSourceSubscription(runtime, dataSourceId);

  const [remote, setRemote] = React.useState<LoadState>({ status: 'idle' });
  const [issueFilter, setIssueFilter] = React.useState<IssueFilter>('all');
  const [query, setQuery] = React.useState('');
  const [selectedRefdes, setSelectedRefdes] = React.useState('');

  React.useEffect(() => {
    if (hasConfiguredInspection || !inspectionUrl || typeof inspectionUrl !== 'string') {
      setRemote((current) => (current.status === 'idle' ? current : { status: 'idle' }));
      return;
    }
    let cancelled = false;
    setRemote({ status: 'loading' });
    fetch(inspectionUrl)
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
      })
      .then((payload) => {
        if (!cancelled) setRemote({ status: 'ready', inspection: normalizeInspection(payload) });
      })
      .catch((error) => {
        if (!cancelled) setRemote({ status: 'error', message: String(error) });
      });
    return () => {
      cancelled = true;
    };
  }, [hasConfiguredInspection, inspectionUrl]);

  const inspection = mergeLineFactsIntoInspection(
    lineInspection ||
      configuredInspection ||
      remote.inspection ||
      normalizeInspection(readDataSourceRecord(runtime, dataSourceId)),
    lineRecord,
    { preferInspectionIssues: Boolean(lineInspection) },
  );
  const initialSide = preferredBoardSvgSide(inspection) || 'top';
  const [side, setSide] = React.useState<SideFilter>(initialSide);
  const board = boardBounds(inspection, lineRecord || {});
  const components = React.useMemo(
    () => (inspection ? filterComponents(inspection, side, issueFilter, query) : []),
    [inspection, side, issueFilter, query],
  );
  const issues = React.useMemo(
    () =>
      inspection
        ? filterIssueList(effectiveValidationIssues(inspection, lineRecord), issueFilter, query)
        : [],
    [inspection, lineRecord, issueFilter, query],
  );
  const allIssues = effectiveValidationIssues(inspection, lineRecord);
  const validationStats = issueStats(allIssues);
  const layers = inspection?.layerManifest || [];
  const drillHits = (inspection?.drillFiles || []).reduce(
    (sum, file) => sum + numberOr(file.hitCount, 0),
    0,
  );
  const preferredSvgSide = preferredBoardSvgSide(inspection);
  const activeBoardSvgUrl = boardSvgUrl(inspection, side);
  const boardImage = useBoardImageSource(activeBoardSvgUrl);
  const [boardImageRenderError, setBoardImageRenderError] = React.useState('');

  React.useEffect(() => {
    if (selectedRefdes && components.some((component) => component.refdes === selectedRefdes))
      return;
    setSelectedRefdes(components[0]?.refdes || '');
  }, [components, selectedRefdes]);

  React.useEffect(() => {
    if (preferredSvgSide && side !== preferredSvgSide && !boardSvgUrl(inspection, side)) {
      setSide(preferredSvgSide);
    }
  }, [inspection, preferredSvgSide, side]);

  React.useEffect(() => {
    setBoardImageRenderError('');
  }, [activeBoardSvgUrl, boardImage.src]);

  if (remote.status === 'loading' && !inspection) {
    return (
      <div
        data-testid="gerber-viewer-loading"
        className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500"
      >
        Loading Gerber inspection...
      </div>
    );
  }

  if (remote.status === 'error' && !inspection) {
    return (
      <div
        role="alert"
        data-testid="gerber-viewer-error"
        className="rounded-md border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700"
      >
        {remote.message || 'Failed to load Gerber inspection'}
      </div>
    );
  }

  if (!inspection) {
    const emptyTitle = getLocalizedText(
      (block as any).empty?.title || 'No Gerber inspection data',
      locale,
      t,
    );
    return (
      <div
        data-testid="gerber-viewer-empty"
        className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500"
      >
        {emptyTitle}
      </div>
    );
  }

  const selected = (inspection.components || []).find(
    (component) => component.refdes === selectedRefdes,
  );
  const projectCode =
    inspection.project?.code || readPath(lineRecord, 'qo_ql_description') || 'Gerber inspection';
  const projectName = inspection.project?.name || readPath(lineRecord, 'qo_ql_mpn') || '';
  const summary = inspection.summary || {};
  const boardImageError = boardImageRenderError || boardImage.message;
  const canRenderBoardImage = boardImage.status === 'ready' && Boolean(boardImage.src) && !boardImageError;
  const unavailablePreview = gerberPreviewUnavailableState({
    locale,
    line: lineRecord,
    issues: allIssues,
    message: boardImageError,
  });
  const boardShellTone = canRenderBoardImage
    ? 'border-gray-300 bg-gray-950'
    : 'border-gray-200 bg-gray-50';

  return (
    <section className="space-y-3" data-testid="gerber-viewer">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-gray-900">{title}</h3>
          <div className="mt-1 text-xs text-gray-500">
            <span className="font-medium text-gray-700">{projectCode}</span>
            {projectName ? <span> · {projectName}</span> : null}
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          <Segmented value={side} options={SIDE_OPTIONS} onChange={setSide} />
          <Segmented value={issueFilter} options={ISSUE_OPTIONS} onChange={setIssueFilter} />
          <input
            aria-label="Gerber viewer search"
            className="h-9 w-44 rounded-md border border-gray-200 bg-white px-3 text-xs outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="RefDes / footprint"
          />
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
        <Metric id="board" label="Board" value={boardMetric(board)} />
        <Metric id="bom" label="BOM refs" value={formatNumber(summary.bomRefCount)} />
        <Metric id="cpl" label="CPL refs" value={formatNumber(summary.cplRefCount)} />
        <Metric
          id="smt-tht"
          label="SMT / THT"
          value={`${formatNumber(summary.smdCount)} / ${formatNumber(summary.thtCount)}`}
        />
        <Metric id="drill" label="Drill hits" value={formatNumber(drillHits)} />
        <Metric
          id="parse"
          label="Parse / validation"
          value={selectedParseStatus(lineRecord || {})}
          tone={
            severityFromValidationStatus(readPath(lineRecord, 'qo_ql_gerber_validation_status')) ===
            'error'
              ? 'border-rose-200'
              : severityFromValidationStatus(readPath(lineRecord, 'qo_ql_gerber_validation_status')) ===
                  'warning'
                ? 'border-amber-200 bg-amber-50'
                : 'border-gray-200'
          }
        />
      </div>

      <ValidationSummary stats={validationStats} locale={locale} />

      <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="min-w-0">
          <div
            data-testid="gerber-viewer-board"
            className={`relative overflow-hidden rounded-lg border shadow-sm ${boardShellTone}`}
            style={{ aspectRatio: `${board.widthMm} / ${board.heightMm}` }}
          >
            {canRenderBoardImage ? (
              <img
                src={boardImage.src}
                alt={`${side === 'top' ? 'Top' : 'Bottom'} Gerber board render`}
                className="h-full w-full object-contain"
                onError={() =>
                  setBoardImageRenderError(
                    boardImageErrorMessage(
                      activeBoardSvgUrl || boardImage.sourceUrl || 'Gerber artifact',
                      'the browser could not decode the image',
                    ),
                  )
                }
              />
            ) : activeBoardSvgUrl && boardImage.status === 'loading' ? (
              <GerberSvgLoading />
            ) : (
              <GerberSvgUnavailable state={unavailablePreview} />
            )}
            {canRenderBoardImage ? (
              <div className="absolute inset-0">
                {components.map((component, index) => {
                  const active = component.refdes === selectedRefdes;
                  return (
                    <button
                      key={`${component.refdes}-${index}`}
                      type="button"
                      data-testid={`gerber-marker-${component.refdes}`}
                      title={`${component.refdes} ${component.footprint}`}
                      onClick={() => setSelectedRefdes(component.refdes)}
                      className={`absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 shadow-lg transition ${
                        active ? 'z-10 h-5 w-5 ring-4 ring-white/70' : ''
                      } ${markerTone(component)}`}
                      style={markerStyle(component, board)}
                    >
                      <span className="sr-only">{component.refdes}</span>
                      {active && (
                        <span className="absolute top-[-9px] left-4 rounded bg-gray-950/90 px-1.5 py-0.5 text-[10px] font-semibold text-white">
                          {component.refdes}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            ) : null}
          </div>
          <div
            data-testid="gerber-selected-component"
            className="mt-2 flex min-h-10 flex-wrap items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs text-gray-600"
          >
            {selected ? (
              <>
                <strong className="text-sm text-gray-900">{selected.refdes}</strong>
                <span>{selected.footprint}</span>
                <span>{selected.bomItem?.materialName || 'Missing BOM'}</span>
                <span>
                  {formatNumber(selected.xMm, 2)}, {formatNumber(selected.yMm, 2)} mm
                </span>
                <span>
                  {selected.smd ? 'SMT' : 'THT'} · {selected.pins ?? '-'} pins ·{' '}
                  {selected.rotation ?? 0} deg
                </span>
              </>
            ) : (
              <span>No selected component</span>
            )}
          </div>
        </div>

        <aside className="min-w-0 space-y-3">
          <section className="rounded-lg border border-gray-200 bg-white">
            <div className="border-b border-gray-100 px-3 py-2 text-sm font-semibold text-gray-900">
              {localized(locale, '校验问题', 'Validation Issues')}
            </div>
            <div className="max-h-48 space-y-2 overflow-auto p-3">
              {issues.length === 0 ? (
                <div data-testid="gerber-issues-empty" className="text-xs text-gray-500">
                  No matching issues
                </div>
              ) : (
                issues.map((issue, index) => (
                  <div
                    key={`${issue.code}-${issue.refdes || 'global'}-${index}`}
                    data-testid={`gerber-issue-${issue.code}-${issue.refdes || index}`}
                    className={`rounded-md border px-2.5 py-2 text-xs ${severityClass[issue.severity] || severityClass.info}`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="font-semibold">{issue.refdes || issue.code}</div>
                      <div className="max-w-[180px] truncate font-mono text-[10px] uppercase opacity-80">
                        {issue.code}
                      </div>
                    </div>
                    <div className="mt-0.5">{issue.message}</div>
                  </div>
                ))
              )}
            </div>
          </section>

          <section className="rounded-lg border border-gray-200 bg-white">
            <div className="border-b border-gray-100 px-3 py-2 text-sm font-semibold text-gray-900">
              Layer Manifest
            </div>
            <div className="max-h-44 overflow-auto p-2">
              {layers.slice(0, 12).map((layer) => (
                <div
                  key={`${layer.role}-${layer.filename}`}
                  data-testid={`gerber-layer-${layer.role}`}
                  className="flex items-center justify-between gap-2 rounded px-2 py-1.5 text-xs"
                >
                  <span className="truncate text-gray-700">{layer.role}</span>
                  <span className="font-semibold text-gray-900">{layerCount(layer)}</span>
                </div>
              ))}
            </div>
          </section>
        </aside>
      </div>

      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <div className="border-b border-gray-100 px-3 py-2">
          <div className="text-sm font-semibold text-gray-900">
            {localized(locale, '当前层器件匹配明细', 'Current Side Component Matches')}
          </div>
          <div className="mt-1 text-xs text-gray-500">
            {localized(locale, '当前层', 'Side')}: {side === 'top' ? 'Top' : 'Bottom'} ·{' '}
            {localized(locale, '匹配器件', 'Components')} {components.length}
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-full text-left text-xs">
            <thead className="border-b border-gray-100 bg-gray-50 text-gray-500">
              <tr>
                <th className="px-3 py-2 font-medium">RefDes</th>
                <th className="px-3 py-2 font-medium">Footprint</th>
                <th className="px-3 py-2 font-medium">BOM item</th>
                <th className="px-3 py-2 font-medium">Process</th>
                <th className="px-3 py-2 text-right font-medium">X</th>
                <th className="px-3 py-2 text-right font-medium">Y</th>
                <th className="px-3 py-2 font-medium">Side</th>
                <th className="px-3 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {components.map((component, index) => {
                const status = componentStatus(component);
                return (
                  <tr
                    key={`${component.refdes}-${index}`}
                    data-testid={`gerber-component-row-${component.refdes}`}
                    className={`cursor-pointer hover:bg-blue-50/50 ${component.refdes === selectedRefdes ? 'bg-blue-50' : ''}`}
                    onClick={() => setSelectedRefdes(component.refdes)}
                  >
                    <td className="px-3 py-2 font-semibold text-gray-900">{component.refdes}</td>
                    <td className="px-3 py-2 text-gray-700">{component.footprint}</td>
                    <td className="px-3 py-2 text-gray-700">
                      {component.bomItem?.materialName || 'Missing BOM'}
                    </td>
                    <td className="px-3 py-2 text-gray-700">{component.bomItem?.process || '-'}</td>
                    <td className="px-3 py-2 text-right text-gray-700">
                      {formatNumber(component.xMm, 3)}
                    </td>
                    <td className="px-3 py-2 text-right text-gray-700">
                      {formatNumber(component.yMm, 3)}
                    </td>
                    <td className="px-3 py-2 text-gray-700">{component.side}</td>
                    <td className="px-3 py-2">
                      <span className={`rounded-full border px-2 py-0.5 ${status.className}`}>
                        {status.label}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
};

export default GerberViewerBlockRenderer;
