import type { Component } from '~/studio/domain/schema/types';

export interface GridCellProps {
  row: number;
  column: number;
  occupied: boolean;
  component?: Component;
  onComponentClick?: (component: Component, event?: React.MouseEvent) => void;
  onComponentUpdate?: (id: string, updates: Partial<Component>) => void;
  onComponentDelete?: (id: string) => void;
  onComponentDoubleClick?: (component: Component, event: React.MouseEvent) => void;
  isSelected?: boolean;
}
