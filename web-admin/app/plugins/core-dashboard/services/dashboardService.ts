/**
 * Dashboard Service
 * API client for dashboard operations
 */

import type {
  Dashboard,
  DashboardText,
  Widget,
  WidgetType,
  DashboardCreateRequest,
  DashboardUpdateRequest,
  DashboardQueryRequest,
} from '../types';

const API_BASE = '/api/dashboards';

const KNOWN_LOCALE_KEYS = new Set(['zh-CN', 'zh', 'en-US', 'en', 'ja-JP', 'ja', 'ko-KR', 'ko']);
const REGION_LOCALE_PATTERN = /^[a-z]{2,3}-[A-Z]{2}$/;

function isLocalizedTextRecord(value: unknown): value is Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return false;
  const hasLocaleKey = entries.some(([key]) => KNOWN_LOCALE_KEYS.has(key) || REGION_LOCALE_PATTERN.test(key));
  return hasLocaleKey && entries.every(([, item]) => item === undefined || typeof item === 'string');
}

function normalizeLocalizedText(value: unknown): DashboardText | '' {
  if (typeof value === 'string') return value;
  if (isLocalizedTextRecord(value)) {
    return Object.fromEntries(
      Object.entries(value).filter(([, item]) => typeof item === 'string'),
    ) as DashboardText;
  }
  return '';
}

function normalizeLocalizedTextToString(value: unknown): string {
  const normalized = normalizeLocalizedText(value);
  if (typeof normalized === 'string') return normalized;
  if (normalized) {
    return normalized['zh-CN'] || normalized.zh || normalized['en-US'] || normalized.en || '';
  }
  return '';
}

/**
 * Backend widget type (PascalCase) to frontend WidgetType (kebab-case with smart- prefix)
 */
const WIDGET_TYPE_MAP: Record<string, WidgetType> = {
  NumberCard: 'smart-number-card',
  BarChart: 'smart-bar-chart',
  LineChart: 'smart-line-chart',
  PieChart: 'smart-pie-chart',
  AreaChart: 'smart-area-chart',
  FunnelChart: 'smart-funnel-chart',
  ScatterChart: 'smart-scatter-chart',
  RadarChart: 'smart-radar-chart',
  TableChart: 'smart-table-chart',
  GaugeChart: 'smart-gauge-chart',
  HeatmapChart: 'smart-heatmap-chart',
  TreemapChart: 'smart-treemap-chart',
  Progress: 'smart-progress',
  Leaderboard: 'smart-leaderboard',
  RichText: 'smart-rich-text',
  Countdown: 'smart-countdown',
  MapChart: 'smart-map-chart',
  ParetoChart: 'smart-pareto-chart',
  SPCChart: 'smart-spc-chart',
  GanttChart: 'smart-gantt-chart',
  Calendar: 'smart-calendar',
  Image: 'smart-image',
  Iframe: 'smart-iframe',
  WordCloudChart: 'smart-wordcloud-chart',
  ComboChart: 'smart-combo-chart',
  NpsChart: 'smart-nps-chart',
  Gallery: 'smart-gallery',
  Kanban: 'smart-kanban',
  InboxWidget: 'smart-inbox',
  RecentWidget: 'smart-recent',
  ShortcutsWidget: 'smart-shortcuts',
  StatsRowWidget: 'smart-stats-row',
  StatsCardWidget: 'smart-stats-card',
  PipelineWidget: 'smart-pipeline',
  LeadsWidget: 'smart-leads',
  ActivitiesWidget: 'smart-activities',
  MyProcessWidget: 'smart-my-process',
  ProcessStatsWidget: 'smart-process-stats',
  CalendarWidget: 'smart-calendar',
  AnnouncementWidget: 'smart-announcement',
  QuickNoteWidget: 'smart-quick-note',
};

/**
 * Map CustomChart config.chartType to frontend WidgetType.
 * Used when seed data stores type as 'CustomChart' with chartType in config.
 */
const CUSTOM_CHART_TYPE_MAP: Record<string, WidgetType> = {
  funnel: 'smart-funnel-chart',
  radar: 'smart-radar-chart',
  area: 'smart-area-chart',
  scatter: 'smart-scatter-chart',
  gauge: 'smart-gauge-chart',
  heatmap: 'smart-heatmap-chart',
  treemap: 'smart-treemap-chart',
  progress: 'smart-progress',
  table: 'smart-table-chart',
  leaderboard: 'smart-leaderboard',
  richtext: 'smart-rich-text',
  countdown: 'smart-countdown',
  map: 'smart-map-chart',
  pareto: 'smart-pareto-chart',
  spc: 'smart-spc-chart',
  gantt: 'smart-gantt-chart',
  calendar: 'smart-calendar',
  bar: 'smart-bar-chart',
  line: 'smart-line-chart',
  pie: 'smart-pie-chart',
  wordcloud: 'smart-wordcloud-chart',
  combo: 'smart-combo-chart',
  nps: 'smart-nps-chart',
  gallery: 'smart-gallery',
  kanban: 'smart-kanban',
};

/**
 * Normalize a raw backend widget to the frontend Widget type.
 * Handles:
 * - `i` → `id` mapping
 * - PascalCase type → kebab-case with smart- prefix
 * - Inline chart data → static ChartDataSource
 */
function normalizeWidget(raw: Record<string, unknown>, index: number): Widget {
  const id = (raw.id as string) || (raw.i as string) || `widget-${index}`;
  const rawType = (raw.type as string) || '';
  const rawConfig = (raw.config as Record<string, unknown>) || {};

  // Resolve type: PascalCase → smart-kebab-case, with CustomChart fallback
  let type: WidgetType;
  if (rawType === 'CustomChart' && rawConfig.chartType) {
    type = CUSTOM_CHART_TYPE_MAP[rawConfig.chartType as string] || (rawType as WidgetType);
  } else {
    type = WIDGET_TYPE_MAP[rawType] || (rawType as WidgetType);
  }

  // Extract position/size
  const x = (raw.x as number) ?? 0;
  const y = (raw.y as number) ?? 0;
  const w = (raw.w as number) ?? 6;
  const h = (raw.h as number) ?? 3;

  // Title can be at root or nested in config
  const title = normalizeLocalizedText(rawConfig.title) || normalizeLocalizedText(raw.title);

  // Base fields required by EnhancedGridCellConfig
  const base = {
    id,
    type,
    x,
    y,
    w,
    h,
    componentType: type,
    props: {} as Record<string, unknown>,
  };

  // Preserve fields that are not part of the legacy WidgetConfig whitelist
  // but that platform widgets may opt in to (e.g. SmartTableChart uses
  // `modelCode` + `table` as a model-table shorthand when no full
  // dataSource is authored — see backlog 2026-05-08 Gap 1).
  const passthrough: Partial<Widget['config']> = {};
  if (rawConfig.modelCode !== undefined) {
    passthrough.modelCode = rawConfig.modelCode as string;
  }
  if (rawConfig.table !== undefined) {
    passthrough.table = rawConfig.table as Record<string, unknown>;
  }
  if (rawConfig.defaultSort !== undefined) {
    passthrough.defaultSort = rawConfig.defaultSort as Widget['config']['defaultSort'];
  }

  const isModelTableShorthand =
    type === 'smart-table-chart' &&
    rawConfig.modelCode !== undefined &&
    rawConfig.table !== undefined &&
    rawConfig.dataSource === undefined &&
    rawConfig.data === undefined &&
    rawConfig.columns === undefined;

  // If config already has dataSource, treat as normalized format
  if (rawConfig.dataSource) {
    return {
      ...base,
      config: {
        title,
        dataSource: rawConfig.dataSource as Widget['config']['dataSource'],
        visualization: rawConfig.visualization as Record<string, unknown> | undefined,
        linkage: rawConfig.linkage as Widget['config']['linkage'],
        drillDown: rawConfig.drillDown as Widget['config']['drillDown'],
        style: rawConfig.style as Widget['config']['style'],
        refreshInterval: rawConfig.refreshInterval as number | undefined,
        ...passthrough,
      },
    };
  }

  if (isModelTableShorthand) {
    return {
      ...base,
      config: {
        title,
        ...passthrough,
      },
    };
  }

  // Convert inline chart data to static dataSource + extract visualization props
  const { dataSource, visualization } = buildStaticDataSource(type, rawConfig);

  return {
    ...base,
    config: {
      title,
      dataSource,
      ...(visualization && Object.keys(visualization).length > 0 ? { visualization } : {}),
      ...passthrough,
    },
  };
}

/**
 * Result of building a static data source from inline config.
 */
interface BuildResult {
  dataSource: Widget['config']['dataSource'];
  visualization?: Record<string, unknown>;
}

/**
 * Build a static ChartDataSource from inline chart configuration.
 * Also extracts visualization props that should be passed to the component.
 */
function buildStaticDataSource(type: WidgetType, config: Record<string, unknown>): BuildResult {
  switch (type) {
    case 'smart-number-card': {
      const value = (config.value as number) ?? 0;
      const vis: Record<string, unknown> = {};
      if (config.suffix) vis.suffix = config.suffix;
      if (config.color) vis.color = config.color;
      if (config.label) vis.label = config.label;
      if (config.icon) vis.icon = config.icon;
      if (config.format) vis.format = config.format;
      return {
        dataSource: {
          type: 'static' as const,
          staticData: [{ count: value }],
          metrics: [{ field: 'count', aggregation: 'count' as const, alias: 'count' }],
        },
        visualization: vis,
      };
    }
    case 'smart-bar-chart':
    case 'smart-line-chart':
    case 'smart-area-chart': {
      const xAxis = (config.xAxis as string[]) || [];
      const series = (config.series as Array<{ name: string; data: number[] }>) || [];
      const rows = xAxis.map((cat, i) => {
        const row: Record<string, unknown> = { category: cat };
        for (const s of series) {
          row[s.name] = s.data?.[i] ?? 0;
        }
        return row;
      });
      return {
        dataSource: {
          type: 'static' as const,
          staticData: rows,
          dimensions: ['category'],
          metrics: series.map((s) => ({
            field: s.name,
            aggregation: 'sum' as const,
            alias: s.name,
          })),
        },
      };
    }
    case 'smart-pie-chart':
    case 'smart-funnel-chart':
    case 'smart-treemap-chart': {
      const data = (config.data as Array<{ name: string; value: number }>) || [];
      return {
        dataSource: {
          type: 'static' as const,
          staticData: data.map((d) => ({ category: d.name, value: d.value })),
          dimensions: ['category'],
          metrics: [{ field: 'value', aggregation: 'sum' as const, alias: 'value' }],
        },
      };
    }
    case 'smart-radar-chart': {
      // Radar: categories[] + series[{ name, data[] }]
      const categories = (config.categories as string[]) || [];
      const series = (config.series as Array<{ name: string; data: number[] }>) || [];
      // Each row = one person/entity, columns = category values
      const rows = series.map((s) => {
        const row: Record<string, unknown> = { name: s.name };
        categories.forEach((cat, i) => {
          row[cat] = s.data?.[i] ?? 0;
        });
        return row;
      });
      return {
        dataSource: {
          type: 'static' as const,
          staticData: rows,
          dimensions: ['name'],
          metrics: categories.map((cat) => ({
            field: cat,
            aggregation: 'sum' as const,
            alias: cat,
          })),
        },
      };
    }
    case 'smart-scatter-chart': {
      const data = (config.data as Array<{ x: number; y: number }>) || [];
      return {
        dataSource: {
          type: 'static' as const,
          staticData: data.map((d) => ({ x: d.x, y: d.y })),
          metrics: [
            { field: 'x', aggregation: 'sum' as const, alias: 'x' },
            { field: 'y', aggregation: 'sum' as const, alias: 'y' },
          ],
        },
      };
    }
    case 'smart-gauge-chart': {
      const value = (config.value as number) ?? 0;
      const vis: Record<string, unknown> = {};
      if (config.max != null) vis.max = config.max;
      if (config.min != null) vis.min = config.min;
      return {
        dataSource: {
          type: 'static' as const,
          staticData: [{ value }],
          metrics: [{ field: 'value', aggregation: 'sum' as const, alias: 'value' }],
        },
        visualization: vis,
      };
    }
    case 'smart-heatmap-chart': {
      const xAxis = (config.xAxis as string[]) || [];
      const yAxis = (config.yAxis as string[]) || [];
      const data = (config.data as number[][]) || [];
      // Heatmap data: [xIndex, yIndex, value] → { x, y, value }
      const rows = data.map((d) => ({
        x: xAxis[d[0]] || String(d[0]),
        y: yAxis[d[1]] || String(d[1]),
        value: d[2],
      }));
      return {
        dataSource: {
          type: 'static' as const,
          staticData: rows,
          dimensions: ['x', 'y'],
          metrics: [{ field: 'value', aggregation: 'sum' as const, alias: 'value' }],
        },
      };
    }
    case 'smart-table-chart': {
      const columns = (config.columns as string[]) || [];
      const data = (config.data as string[][]) || [];
      const rows = data.map((row) => {
        const obj: Record<string, unknown> = {};
        columns.forEach((col, i) => {
          obj[col] = row[i] ?? '';
        });
        return obj;
      });
      return {
        dataSource: {
          type: 'static' as const,
          staticData: rows,
          dimensions: columns.length > 0 ? [columns[0]] : [],
          metrics: columns.slice(1).map((col) => ({
            field: col,
            aggregation: 'sum' as const,
            alias: col,
          })),
        },
      };
    }
    case 'smart-progress': {
      const value = (config.value as number) ?? 0;
      const vis: Record<string, unknown> = {};
      if (config.target != null) vis.target = config.target;
      if (config.label) vis.label = config.label;
      return {
        dataSource: {
          type: 'static' as const,
          staticData: [{ value }],
          metrics: [{ field: 'value', aggregation: 'sum' as const, alias: 'value' }],
        },
        visualization: vis,
      };
    }
    case 'smart-leaderboard': {
      const items =
        (config.items as Array<{ rank: number; name: string; value: number | string }>) || [];
      return {
        dataSource: {
          type: 'static' as const,
          staticData: items.map((item) => ({
            name: item.name,
            value:
              typeof item.value === 'number'
                ? item.value
                : parseFloat(String(item.value).replace(/[^\d.]/g, '')) || 0,
          })),
          dimensions: ['name'],
          metrics: [{ field: 'value', aggregation: 'sum' as const, alias: 'value' }],
        },
      };
    }
    case 'smart-rich-text': {
      // RichText doesn't need dataSource — pass content via visualization
      return {
        dataSource: { type: 'static' as const, staticData: [] },
        visualization: {
          content: config.content,
          format: config.format,
        },
      };
    }
    case 'smart-countdown': {
      // Countdown doesn't need dataSource — pass targetDate via visualization
      return {
        dataSource: { type: 'static' as const, staticData: [] },
        visualization: {
          targetDate: config.targetDate,
          label: config.label,
        },
      };
    }
    case 'smart-wordcloud-chart': {
      const data = (config.data as Array<{ name: string; value: number }>) || [];
      const wordField = (config.wordField as string) || 'name';
      const weightField = (config.weightField as string) || 'value';
      return {
        dataSource: {
          type: 'static' as const,
          staticData: data,
          dimensions: [wordField],
          metrics: [{ field: weightField, aggregation: 'sum' as const, alias: weightField }],
        },
        visualization: { wordField, weightField },
      };
    }
    case 'smart-nps-chart': {
      const data = (config.data as Array<Record<string, unknown>>) || [];
      const scoreField = (config.scoreField as string) || 'score';
      const countField = (config.countField as string) || 'count';
      return {
        dataSource: {
          type: 'static' as const,
          staticData: data,
          dimensions: [scoreField],
          metrics: [{ field: countField, aggregation: 'sum' as const, alias: countField }],
        },
        visualization: {
          scoreField,
          countField,
          promoterThreshold: config.promoterThreshold,
          passiveThreshold: config.passiveThreshold,
          scoreRange: config.scoreRange,
        },
      };
    }
    case 'smart-combo-chart': {
      const xAxis = (config.xAxis as string[]) || [];
      const seriesConfig = (config.seriesConfig as Array<{ field: string; type: string; yAxisIndex?: number; label?: string; data: number[] }>) || [];
      const rows = xAxis.map((cat, i) => {
        const row: Record<string, unknown> = { category: cat };
        for (const s of seriesConfig) {
          row[s.field] = s.data?.[i] ?? 0;
        }
        return row;
      });
      return {
        dataSource: {
          type: 'static' as const,
          staticData: rows,
          dimensions: ['category'],
          metrics: seriesConfig.map((s) => ({ field: s.field, aggregation: 'sum' as const, alias: s.field })),
        },
        visualization: {
          seriesConfig: seriesConfig.map(({ data: _d, ...rest }) => rest),
          xField: 'category',
          yAxis: config.yAxis,
        },
      };
    }
    case 'smart-kanban': {
      // Kanban: items[] with column assignment → static rows grouped by groupField
      const items = (config.items as Array<{ id: string; title: string; column: string; [k: string]: unknown }>) || [];
      const cols = (config.columns as Array<{ value: string; label: string; color?: string }>) || [];
      return {
        dataSource: {
          type: 'static' as const,
          staticData: items.map((item) => ({
            id: item.id,
            title: item.title,
            status: item.column,
          })),
          dimensions: ['status'],
          metrics: [{ field: 'id', aggregation: 'count' as const, alias: 'count' }],
        },
        visualization: {
          groupField: 'status',
          titleField: 'title',
          columnOrder: cols.map((c) => c.value),
          columnColors: Object.fromEntries(cols.map((c) => [c.value, c.color || ''])),
        },
      };
    }
    case 'smart-gallery': {
      // Gallery: static items with image/title/description
      const items = (config.items as Array<{ id?: string; title?: string; imageUrl?: string; description?: string }>) || [];
      return {
        dataSource: { type: 'static' as const, staticData: [] },
        visualization: {
          staticItems: items.map((item) => ({
            image: item.imageUrl || '',
            title: item.title,
            description: item.description,
          })),
          columns: config.colCount || config.columns || 3,
        },
      };
    }
    default:
      // Fallback: pass all config props as visualization so the component can use them
      return {
        dataSource: { type: 'static' as const, staticData: [] },
        visualization: { ...config },
      };
  }
}

/**
 * Normalize a dashboard response from the backend.
 * Ensures all widgets conform to the frontend Widget type.
 */
export function normalizeDashboard(raw: Dashboard): Dashboard {
  return {
    ...raw,
    title: normalizeLocalizedTextToString(raw.title),
    widgets: (raw.widgets || []).map((w, i) => normalizeWidget(w as unknown as Record<string, unknown>, i)),
  };
}

interface ApiResponse<T> {
  code: number | string;
  message: string;
  desc?: string;
  data: T;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.desc || error.message || `Request failed: ${response.status}`);
  }

  const result: ApiResponse<T> = await response.json();

  // Backend returns code as string "0" for success
  const code = typeof result.code === 'string' ? parseInt(result.code, 10) : result.code;
  if (code !== 0 && code !== 200) {
    throw new Error(result.desc || result.message || 'Request failed');
  }

  return result.data;
}

export const dashboardService = {
  /**
   * Create a new dashboard
   */
  async create(data: DashboardCreateRequest): Promise<Dashboard> {
    return request('', {
      method: 'post',
      body: JSON.stringify(data),
    });
  },

  /**
   * Get dashboard by PID
   */
  async findByPid(pid: string): Promise<Dashboard> {
    const raw = await request<Dashboard>(`/${pid}`);
    return normalizeDashboard(raw);
  },

  /**
   * Get dashboard by code
   */
  async findByCode(code: string): Promise<Dashboard> {
    const raw = await request<Dashboard>(`/code/${code}`);
    return normalizeDashboard(raw);
  },

  /**
   * Update dashboard
   */
  async update(pid: string, data: DashboardUpdateRequest): Promise<Dashboard> {
    return request(`/${pid}`, {
      method: 'put',
      body: JSON.stringify(data),
    });
  },

  /**
   * Delete dashboard
   */
  async delete(pid: string): Promise<void> {
    return request(`/${pid}`, {
      method: 'delete',
    });
  },

  /**
   * List dashboards
   */
  async list(query?: DashboardQueryRequest): Promise<Dashboard[]> {
    const params = new URLSearchParams();
    if (query?.title) params.set('title', query.title);
    if (query?.scope) params.set('scope', query.scope);
    if (query?.status) params.set('status', query.status);

    const queryString = params.toString();
    const raw = await request<any>(queryString ? `?${queryString}` : '');
    // Backend returns PageResult { records, total, current } not a flat array
    const items: Dashboard[] = Array.isArray(raw) ? raw : (raw?.records ?? []);
    return items.map(normalizeDashboard);
  },

  /**
   * Get personal dashboards
   */
  async getPersonalDashboards(): Promise<Dashboard[]> {
    return request('/personal');
  },

  /**
   * Get global dashboards
   */
  async getGlobalDashboards(): Promise<Dashboard[]> {
    return request('/global');
  },

  /**
   * Get default dashboard
   */
  async getDefaultDashboard(): Promise<Dashboard | null> {
    const raw = await request<Dashboard | null>('/default');
    return raw ? normalizeDashboard(raw) : null;
  },

  /**
   * Set dashboard as default
   */
  async setAsDefault(pid: string): Promise<Dashboard> {
    return request(`/${pid}/set-default`, {
      method: 'post',
    });
  },

  /**
   * Publish dashboard
   */
  async publish(pid: string): Promise<Dashboard> {
    return request(`/${pid}/publish`, {
      method: 'post',
    });
  },

  /**
   * Unpublish dashboard
   */
  async unpublish(pid: string): Promise<Dashboard> {
    return request(`/${pid}/unpublish`, {
      method: 'post',
    });
  },

  /**
   * Duplicate dashboard
   */
  async duplicate(pid: string, newTitle: string): Promise<Dashboard> {
    return request(`/${pid}/duplicate`, {
      method: 'post',
      body: JSON.stringify({ title: newTitle }),
    });
  },

  /**
   * Get or create the current user's personal workbench dashboard.
   */
  async getWorkbench(): Promise<Dashboard | null> {
    // GET /api/dashboards/workbench — available in OSS core (DashboardController).
    // Creates a default workbench from WorkbenchTemplateProvider on first access.
    // Return null on any failure so home page renders the empty-state CTA instead
    // of getting stuck on a loading spinner or error screen.
    try {
      const raw = await request<Dashboard>('/workbench');
      return normalizeDashboard(raw);
    } catch {
      return null;
    }
  },

  /**
   * Check code uniqueness
   */
  async checkCodeUnique(code: string, excludePid?: string): Promise<boolean> {
    const params = new URLSearchParams({ code });
    if (excludePid) params.set('excludePid', excludePid);
    return request(`/check-code?${params.toString()}`);
  },
};
