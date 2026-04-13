export interface LayoutItem {
  id: string;
  col: number;
  colSpan: number;
  rowSpan: number;
  order: number;
  row?: number;
}

export interface GridLayoutConfig {
  type: 'grid';
  cols: number;
  colGap?: number;
  rowGap?: number;
}

export interface StackLayoutConfig {
  type: 'stack';
  gap?: number;
}

export type PageLayoutConfig = GridLayoutConfig | StackLayoutConfig;

export interface ResolvedLayoutItem extends LayoutItem {
  y: number;
}

export interface LayoutEngine {
  addBlock(id: string, position?: { col?: number; colSpan?: number }): void;
  moveBlock(id: string, col: number, row?: number): void;
  resizeBlock(id: string, colSpan: number, rowSpan?: number): void;
  removeBlock(id: string): void;
  reorder(id: string, newOrder: number): void;
  compact(): void;
  getLayout(): LayoutItem[];
  setLayout(items: LayoutItem[]): void;
}
