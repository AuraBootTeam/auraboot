import type { Dashboard, LayoutConfig, Widget } from '../types';
import { widgetRegistry } from '../widgets/widgetRegistry';

export const DASHBOARD_DOCUMENT_MAX_BYTES = 2 * 1024 * 1024;
export const DASHBOARD_DOCUMENT_MAX_WIDGETS = 100;

/** Portable authoring data only. Tenant, ownership and publishing are never portable. */
export interface DashboardDocument {
  format: 'auraboot.dashboard';
  version: 1;
  dashboard: {
    title: string;
    description?: string;
    layoutConfig: LayoutConfig;
    widgets: Widget[];
  };
}

export class DashboardDocumentError extends Error {
  constructor(public readonly reason: 'size' | 'version' | 'invalid') {
    super(`Invalid dashboard document: ${reason}`);
  }
}

function invalid(): never {
  throw new DashboardDocumentError('invalid');
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function safeTree(value: unknown, depth = 0): void {
  if (depth > 40) invalid();
  if (Array.isArray(value)) {
    for (const child of value) safeTree(child, depth + 1);
  } else if (object(value)) {
    for (const [key, child] of Object.entries(value)) {
      if (['__proto__', 'constructor', 'prototype'].includes(key)) invalid();
      safeTree(child, depth + 1);
    }
  }
}
function integer(value: unknown, min: number, max: number): boolean {
  return (
    Number.isInteger(value) && Number(value) >= min && Number(value) <= max
  );
}

export function parseDashboardDocument(text: string): DashboardDocument {
  if (
    new TextEncoder().encode(text).byteLength > DASHBOARD_DOCUMENT_MAX_BYTES
  ) {
    throw new DashboardDocumentError('size');
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return invalid();
  }
  safeTree(value);
  if (!object(value) || value.format !== 'auraboot.dashboard') invalid();
  if (value.version !== 1) throw new DashboardDocumentError('version');
  const draft = value.dashboard;
  if (
    !object(draft) ||
    typeof draft.title !== 'string' ||
    !draft.title.trim() ||
    draft.title.length > 200
  )
    invalid();
  if (
    draft.description !== undefined &&
    (typeof draft.description !== 'string' || draft.description.length > 5000)
  )
    invalid();
  const layout = draft.layoutConfig;
  if (
    !object(layout) ||
    ![12, 24].includes(layout.columns as number) ||
    !integer(layout.rowHeight, 1, 1000) ||
    !integer(layout.gap, 0, 100) ||
    ![undefined, null, 'vertical', 'horizontal'].includes(
      layout.compactType as never,
    )
  )
    invalid();
  if (
    !Array.isArray(draft.widgets) ||
    draft.widgets.length > DASHBOARD_DOCUMENT_MAX_WIDGETS
  )
    invalid();
  const ids = new Set<string>();
  for (const widget of draft.widgets) {
    if (
      !object(widget) ||
      typeof widget.id !== 'string' ||
      !widget.id.trim() ||
      ids.has(widget.id) ||
      typeof widget.type !== 'string' ||
      !widgetRegistry.get(widget.type) ||
      !object(widget.config) ||
      !object(widget.props) ||
      widget.componentType !== widget.type ||
      !integer(widget.x, 0, 23) ||
      !integer(widget.y, 0, 100000) ||
      !integer(widget.w, 1, Number(layout.columns)) ||
      !integer(widget.h, 1, 10000) ||
      Number(widget.x) + Number(widget.w) > Number(layout.columns)
    )
      invalid();
    for (const key of ['minW', 'maxW', 'minH', 'maxH']) {
      if (
        widget[key] !== undefined &&
        !integer(
          widget[key],
          1,
          key.endsWith('W') ? Number(layout.columns) : 10000,
        )
      )
        invalid();
    }
    const title = widget.config.title;
    if (
      !(
        typeof title === 'string' ||
        (object(title) &&
          Object.keys(title).length > 0 &&
          Object.values(title).every((value) => typeof value === 'string'))
      )
    )
      invalid();
    const source = widget.config.dataSource;
    if (
      source !== undefined &&
      (!object(source) ||
        !['aggregate', 'namedQuery', 'static'].includes(source.type as string))
    )
      invalid();
    if (
      Number(widget.minW ?? 1) > Number(widget.w) ||
      Number(widget.maxW ?? layout.columns) < Number(widget.w) ||
      Number(widget.minH ?? 1) > Number(widget.h) ||
      Number(widget.maxH ?? 10000) < Number(widget.h)
    )
      invalid();
    ids.add(widget.id);
  }
  // Explicit allow-list prevents imported identity/entitlement/analyticsOrigin from entering the store.
  return {
    format: 'auraboot.dashboard',
    version: 1,
    dashboard: {
      title: draft.title.trim(),
      ...(draft.description === undefined
        ? {}
        : { description: draft.description as string }),
      layoutConfig: layout as unknown as LayoutConfig,
      widgets: draft.widgets as Widget[],
    },
  };
}

export function exportDashboardDocument(
  dashboard: Dashboard,
  widgets: Widget[],
  layoutConfig: LayoutConfig,
): DashboardDocument {
  return parseDashboardDocument(
    JSON.stringify({
      format: 'auraboot.dashboard',
      version: 1,
      dashboard: {
        title: dashboard.title,
        description: dashboard.description,
        layoutConfig,
        widgets,
      },
    }),
  );
}
