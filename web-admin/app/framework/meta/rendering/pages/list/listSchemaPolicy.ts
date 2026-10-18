import type { BlockConfig, ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';

export function buildBulkFieldCommandPayload(field: FieldConfig, value: unknown) {
  return { [field.field]: value };
}

export function resolveInitialListTabKey(blocks: unknown): string {
  if (!Array.isArray(blocks)) return 'all';
  const tabsBlock = blocks.find(
    (block): block is { blockType: string; tabs?: Array<{ key?: unknown }> } =>
      Boolean(block && typeof block === 'object' && (block as any).blockType === 'tabs'),
  );
  const tabs = Array.isArray(tabsBlock?.tabs) ? tabsBlock.tabs : [];
  const allTab = tabs.find((tab) => tab?.key === 'all');
  const initialKey = allTab?.key ?? tabs[0]?.key;
  return typeof initialKey === 'string' && initialKey.length > 0 ? initialKey : 'all';
}

export function resolveTableBlockRowActions(tableBlock: any): ButtonConfig[] {
  const blockRowActions = Array.isArray(tableBlock?.rowActions) ? tableBlock.rowActions : [];
  const tableRowActions = Array.isArray(tableBlock?.table?.rowActions)
    ? tableBlock.table.rowActions
    : [];
  const rowActions = [...blockRowActions];
  for (const action of tableRowActions) {
    if (!rowActions.some((existing: any) => existing.code === action.code)) {
      rowActions.push(action);
    }
  }
  return rowActions;
}

/**
 * Build the expression context exposed to toolbar `visibleWhen` conditions.
 * Adds the loaded list's `recordCount` (length of currently loaded rows) and
 * `total` (server total), so conditions like `visibleWhen: "recordCount == 0"`
 * (e.g. a singleton "新建" button that hides once a record exists) evaluate.
 */
export function buildToolbarConditionContext(
  list: { total: number; records: unknown[] },
  base: Record<string, any>,
): Record<string, any> {
  return { ...base, recordCount: list.records?.length ?? 0, total: list.total ?? 0 };
}

export function shouldSkipListData(
  schema: { blocks?: BlockConfig[]; extension?: Record<string, any> } | null | undefined,
): boolean {
  if (!schema) return false;
  const extension = schema.extension ?? {};
  if (extension.skipListData === true || extension.customOnly === true) {
    return true;
  }
  const blocks = Array.isArray(schema.blocks) ? schema.blocks : [];
  if (blocks.length === 0) return false;
  const hasTableBlock = blocks.some((block: any) => block.blockType === 'table');
  const hasCustomBlock = blocks.some((block: any) => block.blockType === 'custom');
  return hasCustomBlock && !hasTableBlock;
}

export function shouldSkipModelFieldMeta(
  schema:
    | { blocks?: BlockConfig[]; extension?: Record<string, any>; dataSource?: Record<string, any> }
    | null
    | undefined,
  skipListData = shouldSkipListData(schema),
): boolean {
  if (skipListData) return true;
  const extension = schema?.extension ?? {};
  return extension.skipFieldMeta === true || extension.skipDynamicFieldMeta === true;
}

export type ListMiscBlocksPosition = 'beforeTable' | 'afterTable';

export function resolveListMiscBlocksPosition(
  schema: { extension?: Record<string, any> } | null | undefined,
): ListMiscBlocksPosition {
  return schema?.extension?.miscBlocksPosition === 'beforeTable' ? 'beforeTable' : 'afterTable';
}
