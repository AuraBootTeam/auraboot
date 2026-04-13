import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface GridContainerProps {
  columns: number;
  rows: number;
  gap: number;
  components: Component[];
  selectedComponents: Component[];
  onComponentClick?: (component: Component, event?: React.MouseEvent) => void;
  onComponentUpdate?: (id: string, updates: Partial<Component>) => void;
  onComponentDelete?: (id: string) => void;
  onComponentDoubleClick?: (component: Component, event: React.MouseEvent) => void;
}
