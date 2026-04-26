/**
 * ViewRegistry — runtime lookup table for SavedView.viewType → component.
 *
 * Currently only the lookup is centralized here; SmartViewRenderer still
 * passes view-specific callbacks (kanban onCardMove vs gantt onTaskDateChange
 * vs calendar onEventMove) at the call site because the heterogeneous prop
 * shape does not collapse cleanly into a single `ViewSpec`. Tracked for
 * follow-up (see design doc § "Out of scope").
 */

import React from 'react';
import { createRegistry } from './createRegistry';

export interface ViewSpec {
  component: React.ComponentType<any>;
}

export const ViewRegistry = createRegistry<ViewSpec>('ViewRegistry');

let initialized = false;

export function initViewRegistry(): void {
  if (initialized) return;
  initialized = true;

  const lazy = (loader: () => Promise<{ [k: string]: React.ComponentType<any> }>, exportName: string) =>
    React.lazy(async () => {
      const mod = await loader();
      return { default: mod[exportName] };
    });

  // 'table' delegates to the parent's renderTableView callback because table
  // rendering varies per page (smart-table vs schema-table). The placeholder
  // exists so size() === 8 and consumers can detect "table is a known view".
  const TableViewPlaceholder: React.FC = () => null;
  TableViewPlaceholder.displayName = 'TableViewPlaceholder';

  ViewRegistry.register('table', { component: TableViewPlaceholder });
  ViewRegistry.register('kanban', {
    component: lazy(() => import('~/framework/smart/components/view/KanbanView'), 'KanbanView'),
  });
  ViewRegistry.register('calendar', {
    component: lazy(() => import('~/framework/smart/components/view/CalendarView'), 'CalendarView'),
  });
  ViewRegistry.register('gallery', {
    component: lazy(() => import('~/framework/smart/components/view/GalleryView'), 'GalleryView'),
  });
  ViewRegistry.register('gantt', {
    component: lazy(() => import('~/framework/smart/components/view/GanttView'), 'GanttView'),
  });
  ViewRegistry.register('tree', {
    component: lazy(() => import('~/framework/smart/components/view/TreeView'), 'TreeView'),
  });
  ViewRegistry.register('timeline', {
    component: lazy(() => import('~/framework/smart/components/view/TimelineView'), 'TimelineView'),
  });
  ViewRegistry.register('form', {
    component: lazy(() => import('~/framework/smart/components/view/FormView'), 'FormView'),
  });
}
