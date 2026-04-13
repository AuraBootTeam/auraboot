import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { LayoutSettings } from '~/plugins/core-designer/components/studio/workbench/components/toolbar/DesignerHeader/types';

export interface ComponentType {
  type: string;
  name: string;
  icon: string;
}

export interface PropertyPanelProps {
  selectedComponents: Component[];
  onComponentUpdate: (id: string, updates: Partial<Component>) => void;
  layoutConfig: any;
  onLayoutConfigChange: (config: any) => void;
  layoutSettings: LayoutSettings;
  onLayoutSettingsChange: (settings: LayoutSettings) => void;
  onClearCanvas?: () => void;
  onCopyLayout?: () => void;
  onPreview?: () => void;
  onExport?: () => void;
}
