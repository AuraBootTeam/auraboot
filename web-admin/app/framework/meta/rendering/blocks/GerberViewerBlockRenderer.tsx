import React from 'react';
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
type SideFilter = BoardSide | 'all';
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

export interface GerberViewerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const DEFAULT_LINE_INSPECTION_FIELD = 'qo_ql_gerber_inspection';

const SIDE_OPTIONS: Array<[SideFilter, string]> = [
  ['all', 'All'],
  ['top', 'Top'],
  ['bottom', 'Bottom'],
];

const ISSUE_OPTIONS: Array<[IssueFilter, string]> = [
  ['all', 'All'],
  ['error', 'Errors'],
  ['warning', 'Warnings'],
  ['info', 'Info'],
];

const severityClass: Record<IssueSeverity, string> = {
  error: 'border-rose-200 bg-rose-50 text-rose-800',
  warning: 'border-amber-200 bg-amber-50 text-amber-900',
  info: 'border-blue-200 bg-blue-50 text-blue-900',
};

function parseJsonLike(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
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
  const statusFields = [
    'qo_ql_gerber_parse_status',
    'qo_ql_gerber_validation_status',
  ];
  return (
    positiveNumberFields.some((field) => hasPositiveNumberPath(line, field)) ||
    statusFields.some((field) => hasNonBlankPath(line, field)) ||
    normalizeStringList(readPath(line, 'qo_ql_gerber_validation_messages')).length > 0
  );
}

function hasLineInspection(line: Record<string, any> | undefined, lineInspectionField: string): boolean {
  return Boolean(normalizeInspection(readPath(line, lineInspectionField)));
}

function hasLineInspectionOrFacts(line: Record<string, any> | undefined, lineInspectionField: string): boolean {
  return hasLineInspection(line, lineInspectionField) || hasLineGerberFacts(line);
}

function firstLineWithInspectionOrFacts(rows: any[], lineInspectionField: string): Record<string, any> | undefined {
  const records = rows.filter((row): row is Record<string, any> => Boolean(row && typeof row === 'object' && !Array.isArray(row)));
  return (
    records.find((row) => hasLineInspection(row, lineInspectionField)) ||
    records.find((row) => hasLineGerberFacts(row))
  );
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
  if (hasLineInspectionOrFacts(selected, lineInspectionField)) return selected;
  return firstLineWithInspectionOrFacts(rows, lineInspectionField) || selected;
}

function severityFromValidationStatus(status: unknown): IssueSeverity {
  const normalized = String(status || '').toLowerCase();
  if (normalized.includes('error') || normalized.includes('fail')) return 'error';
  if (normalized.includes('warn') || normalized.includes('review')) return 'warning';
  return 'info';
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
  return normalizeStringList(readPath(line, 'qo_ql_gerber_validation_messages')).map((message, index) => ({
    severity,
    code: issueCodeFromMessage(message, index),
    message,
  }));
}

function issueCounts(issues: ValidationIssue[]): { errorCount: number; warningCount: number } {
  return {
    errorCount: issues.filter((issue) => issue.severity === 'error').length,
    warningCount: issues.filter((issue) => issue.severity === 'warning').length,
  };
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
    messages.length > 0 && (!options.preferInspectionIssues || !(inspection?.issues && inspection.issues.length > 0));
  const counts = issueCounts(messages);
  const lineLayers = layersFromLine(line);
  const merged: QuoteInspection = {
    ...(inspection || {}),
    project: {
      ...(inspection?.project || {}),
      code:
        String(readPath(line, 'qo_ql_source_ref') || readPath(line, 'qo_ql_mpn') || readPath(line, 'pid') || inspection?.project?.code || 'Gerber inspection'),
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

function boardBounds(inspection: QuoteInspection | undefined, line: Record<string, any>): BoardBounds {
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
    const sideMatches = side === 'all' || component.side === side;
    const issueMatches =
      issueFilter === 'all' ||
      (component.issues || []).some((issue) => issue.severity === issueFilter);
    return sideMatches && issueMatches && matchesSearch(component, normalizedQuery);
  });
}

function filterIssues(
  inspection: QuoteInspection,
  issueFilter: IssueFilter,
  query: string,
): ValidationIssue[] {
  const normalizedQuery = query.trim().toUpperCase();
  return (inspection.issues || []).filter((issue) => {
    const severityMatches = issueFilter === 'all' || issue.severity === issueFilter;
    const queryMatches =
      !normalizedQuery ||
      issue.refdes?.toUpperCase().includes(normalizedQuery) ||
      issue.code?.toUpperCase().includes(normalizedQuery) ||
      issue.message?.toUpperCase().includes(normalizedQuery);
    return severityMatches && queryMatches;
  });
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

function boardSvgUrl(inspection: QuoteInspection | undefined, side: SideFilter): string | undefined {
  if (!inspection || side === 'all' || side === 'unknown') return undefined;
  const value = inspection.boardSvgUrls?.[side];
  return typeof value === 'string' && value.trim() ? value : undefined;
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

function Metric({ id, label, value, tone }: { id: string; label: string; value: string | number; tone?: string }) {
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

function BoardLayerSvg({
  board,
  layers,
  side,
}: {
  board: BoardBounds;
  layers: LayerManifestItem[];
  side: SideFilter;
}) {
  const shownLayers = layers.filter((layer) => side === 'all' || layer.side === side || layer.side === 'unknown');
  const laneCount = Math.max(1, shownLayers.length);
  return (
    <svg viewBox={`0 0 ${board.widthMm} ${board.heightMm}`} className="h-full w-full" role="img" aria-label="PCB layer render">
      <defs>
        <linearGradient id="gerber-board-bg" x1="0" x2="1" y1="0" y2="1">
          <stop offset="0%" stopColor="#172033" />
          <stop offset="52%" stopColor="#0f766e" />
          <stop offset="100%" stopColor="#111827" />
        </linearGradient>
        <pattern id="gerber-copper-pattern" width="7" height="7" patternUnits="userSpaceOnUse">
          <path d="M0 3.5H7M3.5 0V7" stroke="#67e8f9" strokeOpacity="0.2" strokeWidth="0.5" />
        </pattern>
      </defs>
      <rect x="0.4" y="0.4" width={board.widthMm - 0.8} height={board.heightMm - 0.8} rx="2.2" fill="url(#gerber-board-bg)" stroke="#a7f3d0" strokeWidth="0.8" />
      <rect x="2" y="2" width={Math.max(1, board.widthMm - 4)} height={Math.max(1, board.heightMm - 4)} rx="1.5" fill="url(#gerber-copper-pattern)" opacity="0.75" />
      {shownLayers.slice(0, 12).map((layer, index) => {
        const y = 3 + (index * (board.heightMm - 6)) / laneCount;
        const width = Math.max(8, Math.min(board.widthMm - 8, 8 + (layerCount(layer) % Math.max(12, board.widthMm - 8))));
        const color = layer.kind === 'drill' ? '#fbbf24' : layer.side === 'bottom' ? '#60a5fa' : '#34d399';
        return (
          <g key={`${layer.role}-${layer.filename}-${index}`} opacity="0.72">
            <rect x="4" y={y} width={width} height="0.7" rx="0.35" fill={color} />
            <circle cx={Math.min(board.widthMm - 5, 5 + width)} cy={y + 0.35} r="0.65" fill={color} />
          </g>
        );
      })}
      <g opacity="0.32">
        {Array.from({ length: 10 }).map((_, index) => {
          const x = 7 + ((index * 17) % Math.max(10, board.widthMm - 14));
          const y = 5 + ((index * 11) % Math.max(8, board.heightMm - 10));
          return <circle key={index} cx={x} cy={y} r="0.55" fill="#f8fafc" />;
        })}
      </g>
    </svg>
  );
}

export const GerberViewerBlockRenderer: React.FC<GerberViewerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const title = getLocalizedText(block.title || (block as any).label || 'Gerber / CPL Viewer', locale, t);
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  const configuredInspection = normalizeInspection(resolveRuntimeValue(runtime, (block as any).inspection));
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
  const [side, setSide] = React.useState<SideFilter>('all');
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
  const board = boardBounds(inspection, lineRecord || {});
  const components = React.useMemo(
    () => (inspection ? filterComponents(inspection, side, issueFilter, query) : []),
    [inspection, side, issueFilter, query],
  );
  const issues = React.useMemo(
    () => (inspection ? filterIssues(inspection, issueFilter, query) : []),
    [inspection, issueFilter, query],
  );
  const layers = inspection?.layerManifest || [];
  const drillHits = (inspection?.drillFiles || []).reduce((sum, file) => sum + numberOr(file.hitCount, 0), 0);

  React.useEffect(() => {
    if (selectedRefdes && components.some((component) => component.refdes === selectedRefdes)) return;
    setSelectedRefdes(components[0]?.refdes || '');
  }, [components, selectedRefdes]);

  if (remote.status === 'loading' && !inspection) {
    return (
      <div data-testid="gerber-viewer-loading" className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500">
        Loading Gerber inspection...
      </div>
    );
  }

  if (remote.status === 'error' && !inspection) {
    return (
      <div role="alert" data-testid="gerber-viewer-error" className="rounded-md border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">
        {remote.message || 'Failed to load Gerber inspection'}
      </div>
    );
  }

  if (!inspection) {
    const emptyTitle = getLocalizedText((block as any).empty?.title || 'No Gerber inspection data', locale, t);
    return (
      <div data-testid="gerber-viewer-empty" className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500">
        {emptyTitle}
      </div>
    );
  }

  const selected = (inspection.components || []).find((component) => component.refdes === selectedRefdes);
  const projectCode = inspection.project?.code || readPath(lineRecord, 'qo_ql_description') || 'Gerber inspection';
  const projectName = inspection.project?.name || readPath(lineRecord, 'qo_ql_mpn') || '';
  const summary = inspection.summary || {};
  const activeBoardSvgUrl = boardSvgUrl(inspection, side);

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
        <Metric id="smt-tht" label="SMT / THT" value={`${formatNumber(summary.smdCount)} / ${formatNumber(summary.thtCount)}`} />
        <Metric id="drill" label="Drill hits" value={formatNumber(drillHits)} />
        <Metric
          id="parse"
          label="Parse / validation"
          value={selectedParseStatus(lineRecord || {})}
          tone={String(readPath(lineRecord, 'qo_ql_gerber_validation_status')).includes('error') ? 'border-rose-200' : 'border-gray-200'}
        />
      </div>

      <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="min-w-0">
          <div
            data-testid="gerber-viewer-board"
            className="relative overflow-hidden rounded-lg border border-gray-300 bg-gray-950 shadow-sm"
            style={{ aspectRatio: `${board.widthMm} / ${board.heightMm}` }}
          >
            {activeBoardSvgUrl ? (
              <img
                src={activeBoardSvgUrl}
                alt={`${side === 'top' ? 'Top' : 'Bottom'} Gerber board render`}
                className="h-full w-full object-contain"
              />
            ) : (
              <BoardLayerSvg board={board} layers={layers} side={side} />
            )}
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
                      <span className="absolute left-4 top-[-9px] rounded bg-gray-950/90 px-1.5 py-0.5 text-[10px] font-semibold text-white">
                        {component.refdes}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
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
                <span>{selected.smd ? 'SMT' : 'THT'} · {selected.pins ?? '-'} pins · {selected.rotation ?? 0} deg</span>
              </>
            ) : (
              <span>No selected component</span>
            )}
          </div>
        </div>

        <aside className="min-w-0 space-y-3">
          <section className="rounded-lg border border-gray-200 bg-white">
            <div className="border-b border-gray-100 px-3 py-2 text-sm font-semibold text-gray-900">BOM / CPL Issues</div>
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
                      <div className="max-w-[180px] truncate font-mono text-[10px] uppercase opacity-80">{issue.code}</div>
                    </div>
                    <div className="mt-0.5">{issue.message}</div>
                  </div>
                ))
              )}
            </div>
          </section>

          <section className="rounded-lg border border-gray-200 bg-white">
            <div className="border-b border-gray-100 px-3 py-2 text-sm font-semibold text-gray-900">Layer Manifest</div>
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
                    <td className="px-3 py-2 text-gray-700">{component.bomItem?.materialName || 'Missing BOM'}</td>
                    <td className="px-3 py-2 text-gray-700">{component.bomItem?.process || '-'}</td>
                    <td className="px-3 py-2 text-right text-gray-700">{formatNumber(component.xMm, 3)}</td>
                    <td className="px-3 py-2 text-right text-gray-700">{formatNumber(component.yMm, 3)}</td>
                    <td className="px-3 py-2 text-gray-700">{component.side}</td>
                    <td className="px-3 py-2">
                      <span className={`rounded-full border px-2 py-0.5 ${status.className}`}>{status.label}</span>
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
