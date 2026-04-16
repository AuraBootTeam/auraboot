/**
 * ReportDSL Types — independent from PageSchema
 *
 * Report Designer has its own DSL schema, following the
 * "Shell unified, DSL independent" architecture principle.
 */

// ==================== Core DSL ====================

export interface ReportDsl {
  $schema: 'auraboot://schemas/report/v1';
  version: '1.0.0';
  title: string;
  description?: string;
  page: PageConfig;
  dataSources: Record<string, ReportDataSource>;
  parameters?: ReportParameter[];
  header?: ReportBand;
  footer?: ReportBand;
  body: ReportBlock[];
}

export interface PageConfig {
  size: PageSize;
  orientation: PageOrientation;
  margin: PageMargin;
}

export type PageSize = 'A4' | 'A3' | 'letter' | 'legal';
export type PageOrientation = 'portrait' | 'landscape';

export interface PageMargin {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

// ==================== Data Sources ====================

export interface ReportDataSource {
  type: 'model' | 'namedQuery' | 'api';
  modelCode?: string;
  queryCode?: string;
  url?: string;
  filters?: Array<{ field: string; operator: string; value: string }>;
  sortBy?: Array<{ field: string; order: 'asc' | 'desc' }>;
}

// ==================== Parameters ====================

export type ParameterType = 'text' | 'number' | 'date' | 'date-range' | 'select';

export interface ReportParameter {
  name: string;
  type: ParameterType;
  label: string;
  required?: boolean;
  defaultValue?: string;
  /** For 'select' type: list of options */
  options?: Array<{ label: string; value: string }>;
  /** Bind to data source filter field */
  bindTo?: { dataSource: string; field: string; operator: string };
}

// ==================== Bands (Header/Footer) ====================

export interface ReportBand {
  height: number;
  elements: BandElement[];
}

export interface BandElement {
  type: 'text' | 'image' | 'page-number' | 'date';
  content?: string;
  align?: 'left' | 'center' | 'right';
  style?: BandElementStyle;
}

export interface BandElementStyle {
  fontSize?: number;
  fontWeight?: 'normal' | 'bold';
  color?: string;
  fontFamily?: string;
}

// ==================== Blocks ====================

export type ReportBlock =
  | DataTableBlock
  | GroupedTableBlock
  | StatCardBlock
  | RichTextBlock
  | CrossTabBlock
  | ChartBlock
  | BarcodeBlock
  | WatermarkBlock;

export interface DataTableBlock {
  id: string;
  blockType: 'table';
  title?: string;
  dataSource: string;
  columns: ReportColumn[];
  showHeader?: boolean;
  stripe?: boolean;
  border?: boolean;
  summary?: SummaryConfig;
}

export interface SummaryConfig {
  enabled: boolean;
  label?: string;
  columns: SummaryColumnConfig[];
}

export interface SummaryColumnConfig {
  field: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  format?: string;
}

export interface GroupedTableBlock {
  id: string;
  blockType: 'grouped-table';
  title?: string;
  dataSource: string;
  groupByField: string;
  columns: ReportColumn[];
  showHeader?: boolean;
  border?: boolean;
  groupSubtotal?: SummaryConfig;
  grandTotal?: SummaryConfig;
}

export interface StatCardBlock {
  id: string;
  blockType: 'stat-card';
  title?: string;
  dataSource: string;
  valueField: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  label: string;
  format?: string;
  color?: string;
}

export interface RichTextBlock {
  id: string;
  blockType: 'rich-text';
  content: string;
  align?: 'left' | 'center' | 'right';
  style?: {
    fontSize?: number;
    fontWeight?: 'normal' | 'bold';
    color?: string;
  };
}

export interface CrossTabBlock {
  id: string;
  blockType: 'cross-tab';
  title?: string;
  dataSource: string;
  rowField: string;
  columnField: string;
  valueField: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  format?: string;
  showRowTotal?: boolean;
  showColumnTotal?: boolean;
}

export type ChartType = 'bar' | 'horizontal-bar' | 'pie';

export interface ChartBlock {
  id: string;
  blockType: 'chart';
  title?: string;
  dataSource: string;
  chartType: ChartType;
  categoryField: string;
  valueField: string;
  aggregation?: 'sum' | 'avg' | 'count' | 'min' | 'max';
  width?: number;
  height?: number;
  colors?: string[];
}

export type BarcodeFormat = 'code128' | 'code39' | 'ean13' | 'ean8' | 'upc' | 'itf14';

export interface BarcodeBlock {
  id: string;
  blockType: 'barcode';
  title?: string;
  dataSource?: string;
  field?: string;
  staticValue?: string;
  format: BarcodeFormat;
  width?: number;
  height?: number;
  displayValue?: boolean;
  fontSize?: number;
}

export interface WatermarkBlock {
  id: string;
  blockType: 'watermark';
  text: string;
  rotation?: number;
  opacity?: number;
  fontSize?: number;
  color?: string;
  repeat?: boolean;
}

export interface ReportColumn {
  field: string;
  label?: string;
  width?: number;
  align?: 'left' | 'center' | 'right';
  format?: string;
}

// ==================== Block Palette Definition ====================

export interface BlockDefinition {
  type: string;
  label: string;
  icon: string;
  description: string;
}

// ==================== Defaults ====================

export const DEFAULT_PAGE_CONFIG: PageConfig = {
  size: 'A4',
  orientation: 'portrait',
  margin: { top: 20, right: 15, bottom: 20, left: 15 },
};

export const DEFAULT_REPORT_DSL: ReportDsl = {
  $schema: 'auraboot://schemas/report/v1',
  version: '1.0.0',
  title: 'Untitled Report',
  page: { ...DEFAULT_PAGE_CONFIG },
  dataSources: {},
  body: [],
};

export function createEmptyReport(title: string): ReportDsl {
  return {
    ...DEFAULT_REPORT_DSL,
    title,
    page: { ...DEFAULT_PAGE_CONFIG },
    dataSources: {},
    body: [],
  };
}

export function generateBlockId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return `block_${crypto.randomUUID()}`;
  }
  return `block_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}
