export { InboxWidget } from './InboxWidget';
export { RecentWidget } from './RecentWidget';
export { ShortcutsWidget } from './ShortcutsWidget';
export type { ShortcutItem } from './ShortcutsWidget';
export { getRecentVisits, fetchRecentVisits, recordVisit, type RecentVisit } from './useRecentVisits';
export {
  WORKBENCH_WIDGET_CATALOG,
  WORKBENCH_WIDGET_TYPES,
  getCatalogItem,
  type WidgetCatalogItem,
  type WidgetCategory,
} from './widget-catalog';
