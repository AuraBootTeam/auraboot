import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface DraggableWrapperProps {
  component: Component;
  children: React.ReactNode;
  data?: DraggableWrapperData;
  onComponentClick?: (component: Component, event: React.MouseEvent) => void;
}

export interface DraggableWrapperData {
  type: 'existing-component';
  component: Component;
}
