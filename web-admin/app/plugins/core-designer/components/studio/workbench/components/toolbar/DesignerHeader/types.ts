export interface LayoutSettings {
  columns: number;
  rows: number;
  gap: number;
  autoFlow: 'row' | 'column' | 'row dense' | 'column dense';
  densePackingEnabled: boolean;
  densePackingStrategy: 'first-fit' | 'best-fit' | 'worst-fit' | 'next-fit';
  optimizeFor: 'space' | 'readability' | 'balance';
}

export interface DesignerHeaderProps {
  onSave: () => void;
  canUndo: boolean;
  canRedo: boolean;
  onUndo: () => void;
  onRedo: () => void;
  selectedCount: number;
  totalComponents: number;
  layoutSettings: LayoutSettings;
  onLayoutSettingsChange: (settings: LayoutSettings) => void;
  onOptimizeLayout: () => void;
  isOptimizing: boolean;
}
