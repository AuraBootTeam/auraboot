import { hasButtonPermissions } from '~/framework/meta/utils/buttonPermissions';
import type { TranslateFunction } from '~/framework/meta/runtime/expression/i18n-renderer';
import { type PageContentProps } from '@auraboot/runtime-kernel';
import { decodeRouteContextFromSearch } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import type {
  BlockConfig,
  ButtonConfig,
  DataSourceConfig,
  DetailTabConfig,
  UnifiedSchema,
} from '~/framework/meta/schemas/types';
import { mergeDataSources } from '~/framework/meta/utils/extractDataSourcesFromSchema';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from '../utils/visibleWhen';
import type { RecordData } from './detailPageTypes';
import { resolveTextFallback } from './detailFieldDisplay';
import { readDetailRecordField } from './detailRecordRequests';

export function resolveDetailMissingRecordTitle(
  locale: string,
  t: (key: string) => string,
): string {
  return resolveTextFallback(
    t,
    'common.recordNotFound',
    getLocalizedText({ 'zh-CN': '记录不存在', en: 'Record not found' }, locale, t),
  );
}

export function resolveDetailReturnTarget(search: string): string | null {
  const routeContext = decodeRouteContextFromSearch(search);
  const returnTo = String(routeContext?.returnTo || '').trim();
  if (!returnTo.startsWith('/') || returnTo.startsWith('//')) return null;
  const separator = returnTo.includes('?') ? '&' : '?';
  const carriedSearch = search.startsWith('?') ? search.slice(1) : search;
  return carriedSearch ? `${returnTo}${separator}${carriedSearch}` : returnTo;
}

export function shouldRenderDefaultDetailEditAction(
  schema: { extension?: Record<string, any> } | undefined | null,
): boolean {
  return schema?.extension?.showEdit !== false;
}

export function canRenderDetailToolbarButton(
  button: Pick<ButtonConfig, 'permissionCode' | 'permissionCodes'>,
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  return hasButtonPermissions(button, hasPermission);
}

/**
 * Header layout classes for detail pages. The action cluster drops to its own
 * full-width, right-aligned row when title + actions cannot share one line
 * (e.g. 1280px viewports with the sidebar open), instead of wrapping button by
 * button and squeezing the title block.
 */
export function resolveDetailHeaderLayoutClasses(): {
  container: string;
  actions: string;
} {
  return {
    container:
      'flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between sm:gap-x-6',
    actions:
      'print-hide flex min-w-0 max-w-full flex-wrap items-center justify-start gap-2 sm:justify-end',
  };
}

export function resolveHiddenSystemTabKeys(
  schema: { extension?: Record<string, any> } | undefined | null,
): Set<string> {
  const raw = schema?.extension?.hiddenSystemTabs ?? schema?.extension?.hideSystemTabs;
  if (Array.isArray(raw)) {
    return new Set(raw.map((key) => String(key).trim()).filter(Boolean));
  }
  if (typeof raw === 'string') {
    return new Set(
      raw
        .split(',')
        .map((key) => key.trim())
        .filter(Boolean),
    );
  }
  return new Set();
}

export function resolveVisibleDetailTabs(
  allTabs: DetailTabConfig[],
  recordPid: string | undefined,
  schema: { extension?: Record<string, any> } | undefined | null,
): DetailTabConfig[] {
  const hiddenSystemTabKeys = resolveHiddenSystemTabKeys(schema);
  return (recordPid ? allTabs : allTabs.filter((tab) => !tab.system)).filter((tab) => {
    if (!tab.system) return true;
    const key = typeof tab.key === 'string' ? tab.key : '';
    return !hiddenSystemTabKeys.has(key);
  });
}

export function resolveVisibleDetailTabsFromBlocks(
  blocks: BlockConfig[],
  isVisible: (visibleWhen: string | undefined) => boolean,
  hasPermission?: (permissionCode: string) => boolean,
): DetailTabConfig[] {
  return (
    blocks
      .map((block, sourceIndex) => ({
        block,
        sourceIndex,
        order: Number((block as BlockConfig & { detailTabOrder?: number }).detailTabOrder),
      }))
      .filter(
        ({ block }) =>
          block.blockType === 'tabs' && (!block.visibleWhen || isVisible(block.visibleWhen)),
      )
      .sort((left, right) => {
        const leftOrder = Number.isFinite(left.order) ? left.order : left.sourceIndex;
        const rightOrder = Number.isFinite(right.order) ? right.order : right.sourceIndex;
        return leftOrder - rightOrder || left.sourceIndex - right.sourceIndex;
      })
      // A permission declared on the group is not sufficient for a tab: every tab is an
      // independently addressable hash target and may expose a different business surface.
      .flatMap(({ block }) =>
        ((block.tabs || []) as DetailTabConfig[]).filter((tab) => {
          if (!tab.permissionCode) return true;
          return Boolean(hasPermission?.(tab.permissionCode));
        }),
      )
  );
}

export function resolvePersistentDetailHeaderBlocks(
  blocks: BlockConfig[],
  isVisible: (visibleWhen: string | undefined) => boolean = () => true,
  hasPermission: (code: string) => boolean = () => true,
): BlockConfig[] {
  return blocks.filter(
    (block) =>
      block.blockType !== 'toolbar' &&
      block.blockType !== 'tabs' &&
      (block as { detailPlacement?: string }).detailPlacement === 'header' &&
      (!block.permissionCode || hasPermission(block.permissionCode)) &&
      (!block.visibleWhen || isVisible(block.visibleWhen)),
  );
}

export function shouldRenderInlineDetailApproval(
  schema: { extension?: Record<string, any> } | null | undefined,
): boolean {
  return schema?.extension?.showInlineApprovalPanel !== false;
}

export function resolveVisibleTopLevelDetailBlocks(
  blocks: BlockConfig[],
  isVisible: (visibleWhen: string | undefined) => boolean,
): BlockConfig[] {
  return blocks.filter((block) => {
    if (
      (block.blockType === 'toolbar' &&
        (block as { detailPlacement?: string }).detailPlacement !== 'body') ||
      block.blockType === 'tabs'
    )
      return false;
    if (block.visibleWhen && !isVisible(block.visibleWhen)) return false;
    return (block as BlockConfig & { detailPlacement?: string }).detailPlacement !== 'header';
  });
}

/**
 * DataSource ids whose only consumers live under permission-hidden tabs.
 *
 * The page runtime registers every schema dataSource eagerly and auto-fetches on
 * registration, so a surface the current user cannot open still fires its named
 * queries and collects designed backend 403s into every session. A datasource is
 * excludable only when it is consumed somewhere in the schema but never by a
 * permission-visible block; ids with no consumer at all (page code, generated
 * field option sources) are always kept.
 *
 * Consumption means the block renders the data (`dataSource`,
 * `contextDataSource`, form/filter field option sources). Refresh instructions
 * such as `poll.reload` deliberately do NOT count: reloading an unregistered id
 * is a safe no-op in DataSourceManager.fetch, and status banners routinely
 * declare cross-surface reload lists that the viewer may not be allowed to read.
 *
 * visibleWhen is deliberately not evaluated here: the exclusion set must stay
 * stable across record loads and state changes, because usePageDataSources
 * clears and re-registers the whole manager whenever its data source set changes.
 */
export function resolvePermissionHiddenDataSourceIds(
  schema: UnifiedSchema | null,
  hasPermission?: (permissionCode: string) => boolean,
): Set<string> {
  const hidden = new Set<string>();
  const allBlocks = schema?.blocks;
  if (!allBlocks || allBlocks.length === 0) return hidden;

  const candidates = mergeDataSources(schema);
  const candidateIds = Object.keys(candidates);
  if (candidateIds.length === 0) return hidden;

  const consumedByVisible = new Set<string>();
  const consumedByAny = new Set<string>();
  const collectDirectConsumption = (block: BlockConfig, into: Set<string>): void => {
    const record = block as BlockConfig & Record<string, unknown>;
    for (const key of ['dataSource', 'contextDataSource'] as const) {
      const value = record[key];
      if (typeof value === 'string' && candidates[value]) into.add(value);
    }
    for (const field of Array.isArray(record.fields) ? record.fields : []) {
      const value = (field as { dataSource?: unknown }).dataSource;
      if (typeof value === 'string' && candidates[value]) into.add(value);
    }
  };
  const allowed = (code: string | undefined): boolean => !code || Boolean(hasPermission?.(code));
  const walk = (blocks: BlockConfig[], inheritedVisible: boolean): void => {
    for (const block of blocks) {
      const visible = inheritedVisible && allowed(block.permissionCode);
      collectDirectConsumption(block, consumedByAny);
      if (visible) collectDirectConsumption(block, consumedByVisible);
      if (block.blockType === 'tabs') {
        for (const tab of (block as BlockConfig & { tabs?: DetailTabConfig[] }).tabs ?? []) {
          walk(tab.blocks ?? [], visible && allowed(tab.permissionCode));
        }
      }
      const children = (block as BlockConfig & { blocks?: BlockConfig[] }).blocks;
      if (Array.isArray(children)) walk(children, visible);
    }
  };
  walk(allBlocks, true);
  for (const id of candidateIds) {
    if (consumedByAny.has(id) && !consumedByVisible.has(id)) hidden.add(id);
  }
  return hidden;
}

export const DIRECT_DETAIL_SPECIALIZED_BLOCK_TYPES = new Set([
  'activity-timeline',
  'record-comments',
  'field-history',
  'workflow-panel',
]);

/**
 * Specialized blocks rendered beside field sections on detail pages without tabs.
 * These block types are intentionally excluded from the generic BlockRenderer, so
 * they need an explicit direct-mode dispatch just like sub-table and embedded-list.
 */
export function resolveDirectSpecializedDetailBlocks(blocks: BlockConfig[]): BlockConfig[] {
  return blocks.filter((block) =>
    DIRECT_DETAIL_SPECIALIZED_BLOCK_TYPES.has(block.blockType as string),
  );
}

export function evaluateDetailVisibleWhen(
  visibleWhen: string | undefined,
  recordData: Record<string, unknown> | null | undefined,
  runtimeContext?: Partial<ExpressionContext> | null,
): boolean {
  const record = recordData || {};
  return evaluateVisibleWhenExpression(visibleWhen, {
    context: runtimeContext ?? undefined,
    record,
    row: record,
    // Preserve scoped UI inputs, while the loaded record owns business fields.
    form: { ...runtimeContext?.form, ...record },
    state: runtimeContext?.state,
  });
}

export function resolveSubTableDataSourceConfig(
  dataSource: BlockConfig['dataSource'] | undefined,
  schemaDataSources?: Record<string, DataSourceConfig>,
  dataSourceManager?: {
    getConfig: (id: string) => DataSourceConfig | undefined;
  } | null,
): BlockConfig['dataSource'] | undefined {
  if (typeof dataSource !== 'string') {
    return dataSource;
  }

  return schemaDataSources?.[dataSource] ?? dataSourceManager?.getConfig(dataSource) ?? dataSource;
}

export function resolveActiveDetailTab(
  tabs: DetailTabConfig[],
  activeTab: number,
): { tab: DetailTabConfig; index: number } | null {
  if (tabs.length === 0) return null;
  if (activeTab >= 0 && activeTab < tabs.length) {
    return { tab: tabs[activeTab], index: activeTab };
  }
  return { tab: tabs[0], index: 0 };
}

export function resolveDetailPdfFileName(
  schema: PageContentProps['schema'],
  recordData: RecordData,
  tableName: string,
  locale: string,
  t: TranslateFunction,
): string {
  const title = getLocalizedText(schema.title, locale, t) || tableName;
  const code =
    readDetailRecordField(recordData, 'sl_ctr_code') ??
    readDetailRecordField(recordData, 'sl_so_code');
  const recordCode = code === undefined || code === null || code === '' ? '' : String(code);
  return recordCode ? `${title}-${recordCode}` : title;
}
