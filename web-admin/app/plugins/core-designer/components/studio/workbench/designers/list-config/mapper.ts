/**
 * List ViewModel ↔ PageSchema.blocks mapper
 *
 * Per design §3.3 principle 8: `ListConfigPanel` is a deterministic projection
 * of `PageSchema.blocks`. Round-trip `blocksToViewModel(viewModelToBlocks(vm))`
 * must be identity for any valid VM.
 *
 * VM is an ergonomic editor shape (columns / filters / toolbar / behavior);
 * blocks is the canonical persistence shape (filters + toolbar + table triplet).
 */

import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';

// ---------------------------------------------------------------------------
// ViewModel shapes
// ---------------------------------------------------------------------------

export interface ColumnConfig {
  field: string;
  width?: number;
  align?: 'left' | 'center' | 'right';
  renderer?: string;
  format?: string;
}

export interface FilterConfig {
  field: string;
  operator?: string;
  defaultValue?: unknown;
  displayMode?: 'inline' | 'drawer' | 'top-bar';
}

export type ToolbarPresetKey = 'create' | 'export' | 'bulkDelete';

export interface CustomButton {
  label: string;
  icon?: string;
  command: string;
  requiresSelection?: boolean;
}

export interface BehaviorConfig {
  defaultSortField?: string;
  defaultSortOrder?: 'asc' | 'desc';
  pageSize: number;
  multiSelect: boolean;
  rowClickAction?: 'detail' | 'drawer' | 'none';
  emptyStateText?: string;
}

export interface ListViewModel {
  columns: ColumnConfig[];
  filters: FilterConfig[];
  toolbar: {
    presets: ToolbarPresetKey[];
    customButtons: CustomButton[];
  };
  behavior: BehaviorConfig;
}

export function emptyListViewModel(): ListViewModel {
  return {
    columns: [],
    filters: [],
    toolbar: { presets: [], customButtons: [] },
    behavior: {
      pageSize: 20,
      multiSelect: false,
      defaultSortOrder: 'desc',
      rowClickAction: 'detail',
    },
  };
}

// ---------------------------------------------------------------------------
// VM → blocks
// ---------------------------------------------------------------------------

/** Convert VM → 3-block triplet (filters, toolbar, table). Deterministic ids. */
export function viewModelToBlocks(vm: ListViewModel): DslBlock[] {
  const filters: DslBlock = {
    id: 'filters_generated',
    blockType: 'filters',
    fields: vm.filters.map(serializeFilterField) as DslBlock['fields'],
    actions: ['search', 'reset'],
  };

  const toolbarButtons = [
    ...vm.toolbar.presets.map((p) => ({ preset: p })),
    ...vm.toolbar.customButtons.map(serializeCustomButton),
  ];
  const toolbar: DslBlock = {
    id: 'toolbar_generated',
    blockType: 'toolbar',
    buttons: toolbarButtons as unknown as DslBlock['buttons'],
  };

  const props: Record<string, unknown> = {
    pageSize: vm.behavior.pageSize,
    multiSelect: vm.behavior.multiSelect,
  };
  if (vm.behavior.defaultSortField) props.defaultSortField = vm.behavior.defaultSortField;
  if (vm.behavior.defaultSortOrder) props.defaultSortOrder = vm.behavior.defaultSortOrder;
  if (vm.behavior.rowClickAction) props.rowClickAction = vm.behavior.rowClickAction;
  if (vm.behavior.emptyStateText) props.emptyStateText = vm.behavior.emptyStateText;

  const table: DslBlock = {
    id: 'table_generated',
    blockType: 'table',
    span: 12,
    columns: vm.columns.map(serializeColumn) as DslBlock['columns'],
    dataSource: 'tableData',
    props,
  };

  return [filters, toolbar, table];
}

// ---------------------------------------------------------------------------
// blocks → VM
// ---------------------------------------------------------------------------

/** Convert blocks → VM. Tolerates missing blocks with empty defaults. */
export function blocksToViewModel(blocks: DslBlock[] | undefined): ListViewModel {
  const list = blocks ?? [];
  const f = list.find((b) => b.blockType === 'filters');
  const t = list.find((b) => b.blockType === 'toolbar');
  const tbl = list.find((b) => b.blockType === 'table');

  const filters: FilterConfig[] = ((f?.fields ?? []) as unknown[]).map(parseFilterField);

  const toolbarButtons = ((t?.buttons ?? []) as unknown[]) as Array<
    { preset?: ToolbarPresetKey } & Partial<CustomButton>
  >;
  const presets: ToolbarPresetKey[] = toolbarButtons
    .filter((b) => b.preset)
    .map((b) => b.preset as ToolbarPresetKey);
  const customButtons: CustomButton[] = toolbarButtons
    .filter((b) => !b.preset)
    .map(parseCustomButton);

  const columns: ColumnConfig[] = ((tbl?.columns ?? []) as unknown[]).map(parseColumn);
  const props = (tbl?.props ?? {}) as Record<string, unknown>;

  return {
    columns,
    filters,
    toolbar: { presets, customButtons },
    behavior: {
      defaultSortField: props.defaultSortField as string | undefined,
      defaultSortOrder: (props.defaultSortOrder as 'asc' | 'desc' | undefined) ?? 'desc',
      pageSize: (props.pageSize as number | undefined) ?? 20,
      multiSelect: !!props.multiSelect,
      rowClickAction:
        (props.rowClickAction as BehaviorConfig['rowClickAction']) ?? 'detail',
      emptyStateText: props.emptyStateText as string | undefined,
    },
  };
}

// ---------------------------------------------------------------------------
// Column serialize/parse
// ---------------------------------------------------------------------------

function serializeColumn(c: ColumnConfig): string | Record<string, unknown> {
  const extras: Record<string, unknown> = {};
  if (c.width !== undefined) extras.width = c.width;
  if (c.align !== undefined) extras.align = c.align;
  if (c.renderer !== undefined) extras.renderer = c.renderer;
  if (c.format !== undefined) extras.format = c.format;
  if (Object.keys(extras).length === 0) return c.field;
  return { field: c.field, ...extras };
}

function parseColumn(col: unknown): ColumnConfig {
  if (typeof col === 'string') return { field: col };
  if (col && typeof col === 'object' && 'field' in col) {
    const obj = col as Record<string, unknown>;
    const result: ColumnConfig = { field: String(obj.field) };
    if (typeof obj.width === 'number') result.width = obj.width;
    if (obj.align) result.align = obj.align as ColumnConfig['align'];
    if (typeof obj.renderer === 'string') result.renderer = obj.renderer;
    if (typeof obj.format === 'string') result.format = obj.format;
    return result;
  }
  return { field: String(col) };
}

// ---------------------------------------------------------------------------
// Filter field serialize/parse
// ---------------------------------------------------------------------------

function serializeFilterField(f: FilterConfig): string | Record<string, unknown> {
  const hasExtras =
    f.operator !== undefined || f.defaultValue !== undefined || f.displayMode !== undefined;
  if (!hasExtras) return f.field;
  const out: Record<string, unknown> = { field: f.field };
  if (f.operator !== undefined) out.operator = f.operator;
  if (f.defaultValue !== undefined) out.defaultValue = f.defaultValue;
  if (f.displayMode !== undefined) out.displayMode = f.displayMode;
  return out;
}

function parseFilterField(f: unknown): FilterConfig {
  if (typeof f === 'string') return { field: f };
  if (f && typeof f === 'object' && 'field' in f) {
    const obj = f as Record<string, unknown>;
    const result: FilterConfig = { field: String(obj.field) };
    if (obj.operator !== undefined) result.operator = String(obj.operator);
    if (obj.defaultValue !== undefined) result.defaultValue = obj.defaultValue;
    if (obj.displayMode !== undefined)
      result.displayMode = obj.displayMode as FilterConfig['displayMode'];
    return result;
  }
  return { field: String(f) };
}

// ---------------------------------------------------------------------------
// Custom button serialize/parse
// ---------------------------------------------------------------------------

function serializeCustomButton(b: CustomButton): Record<string, unknown> {
  const out: Record<string, unknown> = { label: b.label, command: b.command };
  if (b.icon !== undefined) out.icon = b.icon;
  if (b.requiresSelection) out.requiresSelection = true;
  return out;
}

function parseCustomButton(b: unknown): CustomButton {
  if (b && typeof b === 'object') {
    const obj = b as Record<string, unknown>;
    const result: CustomButton = {
      label: String(obj.label ?? ''),
      command: String(obj.command ?? ''),
    };
    if (obj.icon !== undefined) result.icon = String(obj.icon);
    if (obj.requiresSelection) result.requiresSelection = true;
    return result;
  }
  return { label: String(b), command: '' };
}
