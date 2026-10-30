import type { BlockConfig, ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';

export function buildBulkFieldCommandPayload(field: FieldConfig, value: unknown) {
  return { [field.field]: value };
}

/** Nested blocks identify content tabs; filter tabs retain their legacy query semantics. */
export function isContentTabsBlock(block: any): boolean {
  if (block?.blockType !== 'tabs' || !Array.isArray(block.tabs)) return false;
  const hasContent = block.tabs.some((tab: any) => tab && Object.hasOwn(tab, 'blocks'));
  if (!hasContent) return false;
  if (block.tabs.some((tab: any) => !Array.isArray(tab?.blocks) || tab.filter != null)) {
    throw new Error(`Tabs block ${block.id ?? ''} mixes content and filter tabs`);
  }
  return true;
}

export function findListFilterTabsBlock(blocks: any): any {
  if (!Array.isArray(blocks)) return undefined;
  return blocks.find((block: any) => block?.blockType === 'tabs' && !isContentTabsBlock(block));
}

export function resolveInitialListTabKey(blocks: unknown): string {
  const tabsBlock = findListFilterTabsBlock(blocks);
  const tabs = Array.isArray(tabsBlock?.tabs) ? tabsBlock.tabs : [];
  const allTab = tabs.find((tab: any) => tab?.key === 'all');
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
  runtimeContext: Record<string, any> = {},
): Record<string, any> {
  return {
    ...base,
    ...runtimeContext,
    state: { ...runtimeContext.state, ...base.state },
    recordCount: list.records?.length ?? 0,
    total: list.total ?? 0,
  };
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

/** Custom-only lists still render declared external tables, without enabling native model reads. */
export function collectListMiscBlocks(
  schema: any,
  skipListData = shouldSkipListData(schema),
): any[] {
  if (!Array.isArray(schema?.blocks)) return [];
  const specialized = new Set([
    'table',
    'filters',
    'toolbar',
    'tabs',
    'form-buttons',
    'form-section',
  ]);
  return schema.blocks.filter((block: any) => {
    if (isContentTabsBlock(block)) return true;
    if (skipListData && block.blockType === 'table' && typeof block.dataSource === 'string') {
      const source = schema.dataSources?.[block.dataSource];
      return (
        source?.type === 'api' &&
        typeof source.endpoint === 'string' &&
        source.endpoint.trim() !== ''
      );
    }
    return !specialized.has(block.blockType);
  });
}
