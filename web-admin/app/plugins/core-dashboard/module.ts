/**
 * Dashboard Designer Module
 */

export { DashboardDesigner } from './DashboardDesigner';
export { DashboardViewer } from './components/DashboardViewer';
export { useDashboardStore } from './store/useDashboardStore';
export { dashboardService } from './services/dashboardService';
export { widgetRegistry } from './widgets/widgetRegistry';

// Types
export type {
  Dashboard,
  DashboardCreateRequest,
  DashboardUpdateRequest,
  DashboardQueryRequest,
  Widget,
  WidgetType,
  WidgetConfig,
  WidgetDefinition,
  LayoutConfig,
  DataSourceConfig,
  MetricConfig,
  FilterConfig,
  LinkageConfig,
  PropertySchema,
  ValidationResult,
  ValidationError,
} from './types';
