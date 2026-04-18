/**
 * List ViewModel ↔ PageSchema.blocks mapper
 *
 * Per design §3.3 principle 8: `ListConfigPanel` is a deterministic projection
 * of `PageSchema.blocks`. Round-trip `blocksToViewModel(viewModelToBlocks(vm))`
 * must be identity for any valid VM.
 *
 * VM is an ergonomic editor shape (columns / filters / toolbar / behavior);
 * blocks is the canonical persistence shape (filters + toolbar + table triplet).
 *
 * Preservation note (2026-04-17): toolbar buttons in real plugin pages use a
 * richer schema than what this VM surfaces (e.g. `code`, `variant`, `action`,
 * `confirm`, `visible`, `disabled`). To avoid data loss across a VM round-trip
 * when a user opens a page in the list-config designer and saves, both preset
 * and custom button entries carry a `raw` payload that is written back verbatim
 * during serialization. New buttons created by the designer have `raw` absent
 * and are serialized from structured VM fields.
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
  /**
   * Original button JSON from the source blocks. Preserved verbatim across a
   * VM round-trip so that richer fields (code/variant/action/confirm/etc.) are
   * not dropped when the designer re-serializes. Absent for buttons created in
   * the designer UI.
   */
  raw?: Record<string, unknown>;
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
    /**
     * Original button JSON for each recognized preset, preserved across a VM
     * round-trip to avoid losing custom `action` / `variant` / `label` set in
     * the underlying page config.
     */
    presetRaw?: Partial<Record<ToolbarPresetKey, Record<string, unknown>>>;
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

// Known preset keys — must match ToolbarPresetKey.
const PRESET_KEYS: readonly ToolbarPresetKey[] = ['create', 'export', 'bulkDelete'];
const PRESET_KEY_SET = new Set<string>(PRESET_KEYS);

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
    ...vm.toolbar.presets.map((p) => serializePresetButton(p, vm.toolbar.presetRaw?.[p])),
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

  const toolbarButtons = ((t?.buttons ?? []) as unknown[]) as Array<Record<string, unknown>>;
  const presets: ToolbarPresetKey[] = [];
  const presetRaw: Partial<Record<ToolbarPresetKey, Record<string, unknown>>> = {};
  const customButtons: CustomButton[] = [];

  for (const b of toolbarButtons) {
    const presetKey = detectPresetKey(b);
    if (presetKey && !presets.includes(presetKey)) {
      presets.push(presetKey);
      // Only retain raw when it carries information beyond the trivial
      // `{preset: key}` shorthand, so round-trips of VM-created presets
      // remain identity.
      if (hasExtraPresetFields(b)) {
        presetRaw[presetKey] = { ...b };
      }
    } else if (!presetKey) {
      customButtons.push(parseCustomButton(b));
    }
  }

  const columns: ColumnConfig[] = ((tbl?.columns ?? []) as unknown[]).map(parseColumn);
  const props = (tbl?.props ?? {}) as Record<string, unknown>;

  const vm: ListViewModel = {
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
  if (Object.keys(presetRaw).length > 0) {
    vm.toolbar.presetRaw = presetRaw;
  }
  return vm;
}

/**
 * A button is a preset if it explicitly carries `preset: <known>` OR its `code`
 * field matches a known preset key. Real plugin pages use the latter.
 */
function detectPresetKey(b: Record<string, unknown>): ToolbarPresetKey | null {
  if (!b || typeof b !== 'object') return null;
  const preset = b.preset;
  if (typeof preset === 'string' && PRESET_KEY_SET.has(preset)) {
    return preset as ToolbarPresetKey;
  }
  const code = b.code;
  if (typeof code === 'string' && PRESET_KEY_SET.has(code)) {
    return code as ToolbarPresetKey;
  }
  return null;
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
// Toolbar button serialize/parse
// ---------------------------------------------------------------------------

/**
 * Serialize a preset button. Prefers the preserved `raw` payload (so richer
 * fields like action/variant/confirm survive a round-trip) and falls back to
 * the minimal `{preset}` shorthand for presets created in the designer.
 */
function serializePresetButton(
  key: ToolbarPresetKey,
  raw: Record<string, unknown> | undefined,
): Record<string, unknown> {
  if (raw && typeof raw === 'object') {
    // Shallow clone to prevent downstream mutation.
    return { ...raw };
  }
  return { preset: key };
}

function serializeCustomButton(b: CustomButton): Record<string, unknown> {
  if (b.raw && typeof b.raw === 'object') {
    // Merge editable VM fields on top of the preserved raw payload so in-panel
    // edits win, while unknown fields (code/variant/action/confirm/...) are
    // retained verbatim.
    const merged: Record<string, unknown> = { ...b.raw };
    merged.label = b.label;
    merged.command = b.command;
    if (b.icon !== undefined) merged.icon = b.icon;
    else delete merged.icon;
    if (b.requiresSelection) merged.requiresSelection = true;
    else delete merged.requiresSelection;
    return merged;
  }
  const out: Record<string, unknown> = { label: b.label, command: b.command };
  if (b.icon !== undefined) out.icon = b.icon;
  if (b.requiresSelection) out.requiresSelection = true;
  return out;
}

const CUSTOM_BUTTON_KNOWN_KEYS = new Set([
  'label',
  'command',
  'icon',
  'requiresSelection',
]);

function parseCustomButton(b: unknown): CustomButton {
  if (b && typeof b === 'object') {
    const obj = b as Record<string, unknown>;
    const result: CustomButton = {
      label: String(obj.label ?? ''),
      command: String(obj.command ?? ''),
    };
    if (obj.icon !== undefined) result.icon = String(obj.icon);
    if (obj.requiresSelection) result.requiresSelection = true;
    // Preserve the full source payload only when it carries fields beyond the
    // VM-editable surface (e.g. code/variant/action/confirm). This keeps
    // VM-created buttons round-trip identity.
    const hasExtra = Object.keys(obj).some((k) => !CUSTOM_BUTTON_KNOWN_KEYS.has(k));
    if (hasExtra) {
      result.raw = { ...obj };
    }
    return result;
  }
  return { label: String(b), command: '' };
}

function hasExtraPresetFields(b: Record<string, unknown>): boolean {
  for (const key of Object.keys(b)) {
    if (key === 'preset') continue;
    // `code` alone matching a preset is not extra info — it is the detection
    // hint itself. Treat pure `{code: presetKey}` as trivial so a VM-only
    // round-trip does not gain a presetRaw entry unnecessarily.
    if (key === 'code' && PRESET_KEY_SET.has(String(b[key]))) continue;
    return true;
  }
  return false;
}
