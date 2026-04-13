export const DEFAULT_GRID_COLS = 12;
export const DEFAULT_COL_SPAN = 12;
export const DEFAULT_ROW_SPAN = 1;
export const DEFAULT_COL_GAP = 16;
export const DEFAULT_ROW_GAP = 16;
export const MIN_COL_SPAN = 1;
export const MIN_ROW_SPAN = 1;

export const DEFAULT_COL_SPAN_BY_TYPE: Record<string, number> = {
  'stat-card': 4,
  'chart': 6,
  'form-section': 6,
  'rich-text': 6,
  'detail-section': 6,
  'table': 12,
  'sub-table': 12,
  'toolbar': 12,
  'form-buttons': 12,
  'filters': 12,
  'tabs': 12,
  'divider': 12,
  'monthly-grid': 12,
};

const FALLBACK_COL_SPAN = 6;

export function getDefaultColSpan(blockType: string): number {
  return DEFAULT_COL_SPAN_BY_TYPE[blockType] ?? FALLBACK_COL_SPAN;
}
