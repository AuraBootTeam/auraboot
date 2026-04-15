/**
 * Workbench Widget Catalog
 *
 * Categorized metadata for all workbench widgets available in the Dashboard Designer.
 * This catalog is used by the WidgetPalette to display widgets in organized categories,
 * and by the widget registry to register workbench widget definitions.
 */

import type { WidgetType } from '../../types';

export interface WidgetCatalogItem {
  type: WidgetType;
  name: string; // i18n key
  icon: string;
  description: string; // i18n key
  defaultSize: { w: number; h: number };
  minSize?: { w: number; h: number };
  maxSize?: { w: number; h: number };
}

export interface WidgetCategory {
  key: string;
  label: string; // i18n key
  items: WidgetCatalogItem[];
}

export const WORKBENCH_WIDGET_CATALOG: WidgetCategory[] = [
  {
    key: 'stats',
    label: 'workbench.category.stats',
    items: [
      {
        type: 'smart-stats-row',
        name: 'workbench.widget.stats_row',
        icon: '📊',
        description: 'workbench.widget.stats_row_desc',
        defaultSize: { w: 12, h: 2 },
        minSize: { w: 6, h: 2 },
      },
      {
        type: 'smart-stats-card',
        name: 'workbench.widget.stats_card',
        icon: '🔢',
        description: 'workbench.widget.stats_card_desc',
        defaultSize: { w: 3, h: 2 },
        minSize: { w: 3, h: 2 },
        maxSize: { w: 6, h: 4 },
      },
    ],
  },
  {
    key: 'tasks',
    label: 'workbench.category.tasks',
    items: [
      {
        type: 'smart-inbox',
        name: 'workbench.widget.inbox',
        icon: '📋',
        description: 'workbench.widget.inbox_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
      {
        type: 'smart-calendar',
        name: 'workbench.widget.calendar',
        icon: '📅',
        description: 'workbench.widget.calendar_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
    ],
  },
  {
    key: 'crm',
    label: 'workbench.category.crm',
    items: [
      {
        type: 'smart-pipeline',
        name: 'workbench.widget.pipeline',
        icon: '🔄',
        description: 'workbench.widget.pipeline_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
      {
        type: 'smart-leads',
        name: 'workbench.widget.leads',
        icon: '🎯',
        description: 'workbench.widget.leads_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
      {
        type: 'smart-activities',
        name: 'workbench.widget.activities',
        icon: '📝',
        description: 'workbench.widget.activities_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
    ],
  },
  {
    key: 'bpm',
    label: 'workbench.category.bpm',
    items: [
      {
        type: 'smart-my-process',
        name: 'workbench.widget.my_process',
        icon: '🚀',
        description: 'workbench.widget.my_process_desc',
        defaultSize: { w: 6, h: 4 },
        minSize: { w: 4, h: 3 },
        maxSize: { w: 12, h: 8 },
      },
      {
        type: 'smart-process-stats',
        name: 'workbench.widget.process_stats',
        icon: '📊',
        description: 'workbench.widget.process_stats_desc',
        defaultSize: { w: 6, h: 3 },
        minSize: { w: 4, h: 2 },
        maxSize: { w: 12, h: 6 },
      },
    ],
  },
  {
    key: 'general',
    label: 'workbench.category.general',
    items: [
      {
        type: 'smart-shortcuts',
        name: 'workbench.widget.shortcuts',
        icon: '⚡',
        description: 'workbench.widget.shortcuts_desc',
        defaultSize: { w: 6, h: 2 },
        minSize: { w: 3, h: 2 },
        maxSize: { w: 12, h: 4 },
      },
      {
        type: 'smart-recent',
        name: 'workbench.widget.recent',
        icon: '🕐',
        description: 'workbench.widget.recent_desc',
        defaultSize: { w: 6, h: 3 },
        minSize: { w: 3, h: 2 },
        maxSize: { w: 12, h: 6 },
      },
      {
        type: 'smart-announcement',
        name: 'workbench.widget.announcement',
        icon: '📢',
        description: 'workbench.widget.announcement_desc',
        defaultSize: { w: 6, h: 3 },
        minSize: { w: 3, h: 2 },
        maxSize: { w: 12, h: 6 },
      },
      {
        type: 'smart-quick-note',
        name: 'workbench.widget.quick_note',
        icon: '📝',
        description: 'workbench.widget.quick_note_desc',
        defaultSize: { w: 4, h: 3 },
        minSize: { w: 3, h: 2 },
        maxSize: { w: 8, h: 6 },
      },
    ],
  },
];

/**
 * Flat list of all workbench widget types for quick lookup
 */
export const WORKBENCH_WIDGET_TYPES: Set<WidgetType> = new Set(
  WORKBENCH_WIDGET_CATALOG.flatMap((cat) => cat.items.map((item) => item.type)),
);

/**
 * Get catalog item by widget type
 */
export function getCatalogItem(type: WidgetType): WidgetCatalogItem | undefined {
  for (const category of WORKBENCH_WIDGET_CATALOG) {
    const item = category.items.find((i) => i.type === type);
    if (item) return item;
  }
  return undefined;
}
