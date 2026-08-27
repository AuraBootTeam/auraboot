import type {
  DslBlockV3,
  LegacyColumnRefV2,
  LegacyDashboardResource,
  LegacyDashboardWidget,
  LegacyDslBlockV2,
  LegacyFieldRefV2,
  LegacyPageSchemaV2,
  PageSchemaV3,
  PageSchemaV3Kind,
} from '../types';
import { createUniqueBlockId, toStableBlockId } from '../utils/blockIds';

interface NormalizedRef {
  id?: string;
  field: string;
  props: Record<string, unknown>;
  layout: Record<string, unknown>;
}

export function migratePageSchemaV2ToV3(
  schema: LegacyPageSchemaV2,
  options?: { rootBlockId?: string },
): PageSchemaV3 {
  const kind = normalizeKind(schema.kind);
  const rootBlock: DslBlockV3 = {
    id: options?.rootBlockId || toStableBlockId(kind, schema.id),
    blockType: kind,
    title: schema.title,
    dataSource: schema.modelCode ? { model: schema.modelCode } : undefined,
    layout: schema.layout ? { ...schema.layout } : undefined,
    blocks: migratePageBlocks(kind, schema.blocks ?? []),
  };

  return {
    schemaVersion: 3,
    kind,
    id: schema.id,
    pageKey: schema.pageKey,
    modelCode: schema.modelCode,
    title: schema.title,
    layout: schema.layout ? { ...schema.layout } : undefined,
    blocks: [rootBlock],
    extension: schema.extension,
  };
}

export function migrateDashboardResourceToV3(resource: LegacyDashboardResource): PageSchemaV3 {
  const dashboardId = normalizeDashboardId(resource);
  const dashboardBlockId = toStableBlockId('dashboard', dashboardId);

  const dashboardBlock: DslBlockV3 = {
    id: dashboardBlockId,
    blockType: 'dashboard',
    title: resource.title,
    layout: {
      type: 'dashboard-grid',
      cols: resource.layoutConfig?.columns ?? 12,
      rowHeight: resource.layoutConfig?.rowHeight ?? 80,
      gap: resource.layoutConfig?.gap ?? 16,
    },
    blocks: (resource.widgets ?? []).map((widget, index) =>
      migrateWidget(widget, dashboardBlockId, index),
    ),
    extension: resource.extension,
  };

  return {
    schemaVersion: 3,
    kind: 'dashboard',
    id: dashboardId,
    title: resource.title,
    layout: dashboardBlock.layout,
    blocks: [dashboardBlock],
    extension: resource.extension,
  };
}

function normalizeDashboardId(resource: LegacyDashboardResource): string {
  return String(resource.code || resource.pid || resource.id || 'dashboard');
}

function normalizeKind(kind: string): PageSchemaV3Kind {
  if (kind === 'list' || kind === 'detail' || kind === 'form' || kind === 'dashboard') return kind;
  return 'composite';
}

function migratePageBlocks(kind: PageSchemaV3Kind, blocks: LegacyDslBlockV2[]): DslBlockV3[] {
  if (kind === 'form') return migrateFormBlocks(blocks);
  if (kind === 'detail') return migrateDetailBlocks(blocks);
  if (kind === 'list') return migrateListBlocks(blocks);
  return blocks.map((block) => migrateGenericBlock(block, kind));
}

function migrateFormBlocks(blocks: LegacyDslBlockV2[]): DslBlockV3[] {
  return blocks.flatMap((block) => {
    if (block.blockType === 'form-section') return [migrateSectionBlock(block, 'form-section')];
    if (block.blockType === 'form-buttons') return [migrateActionBarBlock(block, 'footer')];
    return [migrateGenericBlock(block, 'form')];
  });
}

function migrateDetailBlocks(blocks: LegacyDslBlockV2[]): DslBlockV3[] {
  return blocks.flatMap((block) => {
    if (block.blockType === 'form-section') return [migrateSectionBlock(block, 'detail-section')];
    if (block.blockType === 'detail-section') return [migrateSectionBlock(block, 'detail-section')];
    if (block.blockType === 'toolbar') return [migrateActionBarBlock(block, 'header')];
    if (block.blockType === 'form-buttons') return [migrateActionBarBlock(block, 'footer')];
    return [migrateGenericBlock(block, 'detail')];
  });
}

function migrateListBlocks(blocks: LegacyDslBlockV2[]): DslBlockV3[] {
  return blocks.flatMap((block) => {
    if (block.blockType === 'filters') return [migrateFilterBarBlock(block)];
    if (block.blockType === 'toolbar') return [migrateActionBarBlock(block, 'toolbar')];
    if (block.blockType === 'form-buttons') return [migrateActionBarBlock(block, 'toolbar')];
    if (block.blockType === 'table') return [migrateTableBlock(block)];
    return [migrateGenericBlock(block, 'list')];
  });
}

function migrateSectionBlock(block: LegacyDslBlockV2, blockType: string): DslBlockV3 {
  const blockId = block.id || toStableBlockId(blockType);
  // `normalizeProps` strips the structured `columns` key; preserve it so the
  // flat serializer can restore section column counts on save.
  const sectionColumns = typeof block.columns === 'number' ? { columns: block.columns } : {};
  return {
    id: blockId,
    blockType,
    region: block.region,
    title: block.title,
    layout: normalizeLayout(block),
    props: { ...normalizeProps(block), ...sectionColumns },
    dataSource: normalizeDataSource(block.dataSource),
    blocks: (block.fields ?? []).map((fieldRef) => migrateFieldRef(blockId, fieldRef, 'field')),
    extension: normalizeExtension(block),
  };
}

function migrateFilterBarBlock(block: LegacyDslBlockV2): DslBlockV3 {
  const blockId = block.id || 'filters';
  // `normalizeProps` strips the structured array keys, which would silently
  // drop the filters block's own `actions` / `buttons` shorthand (search /
  // reset / embedded buttons) — the flat serializer hoists them back from
  // these keys, so they must survive the migration.
  const shorthandActions = Array.isArray(block.actions) ? { actions: block.actions } : {};
  const shorthandButtons = Array.isArray(block.buttons) ? { buttons: block.buttons } : {};
  return {
    id: blockId,
    blockType: 'filter-bar',
    region: 'filters',
    title: block.title,
    layout: normalizeLayout(block),
    props: { ...normalizeProps(block), ...shorthandActions, ...shorthandButtons },
    blocks: (block.fields ?? []).map((fieldRef) => migrateFieldRef(blockId, fieldRef, 'filter-field')),
    extension: normalizeExtension(block),
  };
}

function migrateTableBlock(block: LegacyDslBlockV2): DslBlockV3 {
  const blockId = block.id || 'table';
  // Row actions persist in the flat table's `rowActions` array; restore them as
  // attached action children so the outline keeps them editable under the table.
  const rowActionRefs = Array.isArray(block.rowActions)
    ? (block.rowActions as Array<string | Record<string, unknown>>)
    : [];
  return {
    id: blockId,
    blockType: 'table',
    region: block.region,
    title: block.title,
    layout: normalizeLayout(block),
    props: { ...normalizeProps(block), selection: block.selection },
    dataSource: normalizeDataSource(block.dataSource),
    blocks: [
      ...(Array.isArray(block.columns) ? block.columns : []).map((columnRef) =>
        migrateColumnRef(blockId, columnRef),
      ),
      ...migrateActionRefs(blockId, rowActionRefs).map((action) => ({
        ...action,
        region: action.region ?? 'row-actions',
      })),
    ],
    extension: normalizeExtension(block),
  };
}

function migrateActionBarBlock(block: LegacyDslBlockV2, region: string): DslBlockV3 {
  const blockId = block.id || toStableBlockId(region, 'actions');
  const actionRefs = [...(block.buttons ?? []), ...(block.actions ?? [])];
  return {
    id: blockId,
    blockType: 'action-bar',
    region,
    title: block.title,
    layout: normalizeLayout(block),
    props: normalizeProps(block),
    blocks: migrateActionRefs(blockId, actionRefs),
    extension: normalizeExtension(block),
  };
}

function migrateGenericBlock(block: LegacyDslBlockV2, kind?: PageSchemaV3Kind): DslBlockV3 {
  if (block.blockType === 'tabs') return migrateTabsBlock(block, kind);
  if (block.blockType === 'toolbar') return migrateActionBarBlock(block, 'toolbar');
  if (block.blockType === 'form-buttons') return migrateActionBarBlock(block, 'footer');
  // Sections / filter bars nested outside the kind roots (e.g. inside tabs)
  // still need their `fields[]` shorthand expanded — the generic passthrough
  // would strip it.
  if (block.blockType === 'filter-bar' || block.blockType === 'filters') {
    return migrateFilterBarBlock(block);
  }
  if (block.blockType === 'table') return migrateTableBlock(block);
  if (block.blockType === 'form-section' && kind === 'detail') {
    return migrateSectionBlock(block, 'detail-section');
  }
  if (block.blockType === 'form-section') return migrateSectionBlock(block, 'form-section');
  if (block.blockType === 'detail-section') return migrateSectionBlock(block, 'detail-section');
  if (block.blockType === 'chart' || block.blockType === 'stat-card') {
    return migrateWidgetLikeBlock(block);
  }

  const blockId = block.id || toStableBlockId(block.blockType);
  return {
    id: blockId,
    blockType: block.blockType,
    region: block.region,
    title: block.title,
    dataSource: normalizeDataSource(block.dataSource),
    layout: normalizeLayout(block),
    props: normalizeProps(block),
    blocks: block.blocks?.map((child) => migrateGenericBlock(child, kind)),
    extension: normalizeExtension(block),
  };
}

function migrateTabsBlock(block: LegacyDslBlockV2, kind?: PageSchemaV3Kind): DslBlockV3 {
  const blockId = block.id || toStableBlockId('tabs');
  const tabRefs = Array.isArray(block.tabs) ? block.tabs : [];

  return {
    id: blockId,
    blockType: 'tabs',
    region: block.region,
    title: block.title,
    layout: normalizeLayout(block),
    props: normalizeProps(block),
    blocks: tabRefs.map((tabRef, index) => migrateTabRef(blockId, tabRef, index, kind)),
    extension: normalizeExtension(block),
  };
}

function migrateTabRef(
  parentId: string,
  ref: unknown,
  index: number,
  kind?: PageSchemaV3Kind,
): DslBlockV3 {
  const tab = ref && typeof ref === 'object' ? (ref as Record<string, unknown>) : {};
  const key = String(tab.key || tab.id || `tab_${index + 1}`);
  const label = tab.label as DslBlockV3['title'];
  const tabBlocks = Array.isArray(tab.blocks) ? (tab.blocks as LegacyDslBlockV2[]) : [];
  const { blocks: _blocks, key: _key, id: _id, label: _label, ...props } = tab;

  return {
    // Honor a persisted tab id so the outline node survives a save/reload cycle.
    id: typeof _id === 'string' && _id ? _id : toStableBlockId(parentId, key),
    blockType: 'tab',
    title: label,
    props,
    blocks: tabBlocks.map((child) => migrateGenericBlock(child, kind)),
  };
}

function migrateWidgetLikeBlock(block: LegacyDslBlockV2): DslBlockV3 {
  const blockId = block.id || toStableBlockId('widget', block.blockType);
  return {
    id: blockId,
    blockType: 'widget',
    region: block.region,
    title: block.title,
    widgetType: block.blockType,
    dataSource: normalizeDataSource(block.dataSource),
    layout: normalizeLayout(block),
    props: normalizeProps(block),
    blocks: block.blocks?.map((child) => migrateGenericBlock(child)),
    extension: normalizeExtension(block),
  };
}

function migrateFieldRef(parentId: string, ref: LegacyFieldRefV2, blockType: 'field' | 'filter-field'): DslBlockV3 {
  const parsed = parseFieldLikeRef(ref);
  return {
    // Object refs may carry the stable id persisted by the flat serializer;
    // honoring it keeps block identity stable across a load → save cycle.
    id: parsed.id ?? toStableBlockId(parentId, parsed.field),
    blockType,
    field: parsed.field,
    layout: Object.keys(parsed.layout).length ? parsed.layout : undefined,
    props: Object.keys(parsed.props).length ? parsed.props : undefined,
  };
}

function migrateColumnRef(parentId: string, ref: LegacyColumnRefV2): DslBlockV3 {
  const parsed = parseFieldLikeRef(ref);
  return {
    id: parsed.id ?? toStableBlockId(parentId, parsed.field),
    blockType: 'column',
    field: parsed.field,
    layout: Object.keys(parsed.layout).length ? parsed.layout : undefined,
    props: Object.keys(parsed.props).length ? parsed.props : undefined,
  };
}

function migrateActionRefs(parentId: string, refs: Array<string | Record<string, unknown>>): DslBlockV3[] {
  const usedIds = new Set<string>();
  return refs.map((ref) => {
    const action = migrateActionRef(parentId, ref);
    const id = createUniqueBlockId(action.id, usedIds);
    usedIds.add(id);
    return { ...action, id };
  });
}

function migrateActionRef(parentId: string, ref: string | Record<string, unknown>): DslBlockV3 {
  if (typeof ref === 'string') {
    return {
      id: toStableBlockId(parentId, ref),
      blockType: 'action',
      actionType: ref,
    };
  }
  const actionType = normalizeActionType(ref);
  const actionKey =
    typeof ref.code === 'string'
      ? ref.code
      : typeof ref.name === 'string'
        ? ref.name
        : actionType;
  const id =
    typeof ref.id === 'string'
      ? ref.id
      : toStableBlockId(parentId, actionKey);
  const { id: _id, actionType: _actionType, type: _type, name: _name, span, ...props } = ref;
  const layout = typeof span === 'number' ? { span } : undefined;
  return {
    id,
    blockType: 'action',
    actionType,
    title: ref.title as DslBlockV3['title'],
    layout,
    props,
  };
}

function normalizeActionType(ref: Record<string, unknown>): string {
  if (typeof ref.actionType === 'string') return ref.actionType;
  if (typeof ref.type === 'string') return ref.type;
  if (typeof ref.action === 'string') return ref.action;
  if (ref.action && typeof ref.action === 'object') {
    const action = ref.action as Record<string, unknown>;
    if (typeof action.type === 'string') return action.type;
  }
  if (typeof ref.commandCode === 'string') return 'command';
  if (typeof ref.code === 'string') return ref.code;
  if (typeof ref.name === 'string') return ref.name;
  return 'custom';
}

function migrateWidget(widget: LegacyDashboardWidget, parentId: string, index: number): DslBlockV3 {
  return {
    id: normalizeWidgetId(widget, parentId, index),
    blockType: 'widget',
    widgetType: widget.type,
    layout: {
      x: widget.x ?? 0,
      y: widget.y ?? 0,
      w: widget.w ?? 3,
      h: widget.h ?? 2,
    },
    props: widget.config ?? widget.props ?? {},
  };
}

function normalizeWidgetId(widget: LegacyDashboardWidget, parentId: string, index: number): string {
  if (widget.id !== undefined && widget.id !== null && String(widget.id).trim()) {
    return String(widget.id);
  }

  const title = widget.config?.title ?? widget.props?.title;
  return toStableBlockId(
    parentId,
    'widget',
    widget.type,
    typeof title === 'string' ? title : undefined,
    index + 1,
  );
}

function parseFieldLikeRef(ref: LegacyFieldRefV2 | LegacyColumnRefV2): NormalizedRef {
  if (typeof ref !== 'string') {
    const field = String(ref.field || ref.code || ref.name || 'field');
    const { span, colSpan, width, field: _field, code: _code, name: _name, id, ...rest } = ref;
    const layout: Record<string, unknown> = {};
    if (typeof span === 'number') layout.span = span;
    if (typeof colSpan === 'number') layout.span = colSpan;
    if (typeof width === 'number') layout.width = width;
    return {
      id: typeof id === 'string' && id ? id : undefined,
      field,
      props: rest,
      layout,
    };
  }

  const [field, ...segments] = ref.split('|');
  const props: Record<string, unknown> = {};
  const layout: Record<string, unknown> = {};

  for (const segment of segments) {
    if (!segment) continue;
    if (!segment.includes(':')) {
      props[segment] = true;
      continue;
    }
    const [key, rawValue] = segment.split(':');
    const value = parseShorthandValue(rawValue);
    if (key === 'span' || key === 'width') {
      layout[key] = value;
    } else {
      props[key] = value;
    }
  }

  return { field, props, layout };
}

function parseShorthandValue(value: string): string | number | boolean {
  if (value === 'true') return true;
  if (value === 'false') return false;
  const numeric = Number(value);
  return Number.isNaN(numeric) ? value : numeric;
}

function normalizeDataSource(value: unknown): Record<string, unknown> | undefined {
  if (!value) return undefined;
  if (typeof value === 'string') return { ref: value };
  if (typeof value === 'object') return value as Record<string, unknown>;
  return { value };
}

function normalizeLayout(block: LegacyDslBlockV2): Record<string, unknown> | undefined {
  const layout =
    block.layout && typeof block.layout === 'object' && !Array.isArray(block.layout)
      ? { ...(block.layout as Record<string, unknown>) }
      : {};

  if (typeof block.span === 'number') layout.span = block.span;
  if (typeof block.colSpan === 'number') layout.span = block.colSpan;
  if (typeof block.rowSpan === 'number') layout.rowSpan = block.rowSpan;

  return Object.keys(layout).length ? layout : undefined;
}

function normalizeProps(block: LegacyDslBlockV2): Record<string, unknown> | undefined {
  const {
    id: _id,
    blockType: _blockType,
    region: _region,
    title: _title,
    fields: _fields,
    columns: _columns,
    buttons: _buttons,
    actions: _actions,
    span: _span,
    colSpan: _colSpan,
    rowSpan: _rowSpan,
    layout: _layout,
    dataSource: _dataSource,
    selection: _selection,
    blocks: _blocks,
    tabs: _tabs,
    extension: _extension,
    props,
    ...rest
  } = block;
  const next = { ...(props ?? {}), ...rest };
  return Object.keys(next).length ? next : undefined;
}

function normalizeExtension(block: LegacyDslBlockV2): Record<string, unknown> | undefined {
  const extension = block.extension;
  return extension && typeof extension === 'object' ? (extension as Record<string, unknown>) : undefined;
}
