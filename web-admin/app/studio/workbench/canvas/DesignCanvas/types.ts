import type { Component } from '~/studio/domain/schema/types';

export interface DesignCanvasProps {
  columns?: number;
  rows?: number;
  gap?: number;
  components: Component[];
  selectedComponents?: Component[];
  onComponentClick?: (component: Component, event?: React.MouseEvent) => void;
  onComponentUpdate?: (id: string, updates: Partial<Component>) => void;
  onComponentDelete?: (id: string) => void;
  onComponentDoubleClick?: (component: Component, event: React.MouseEvent) => void;
}
