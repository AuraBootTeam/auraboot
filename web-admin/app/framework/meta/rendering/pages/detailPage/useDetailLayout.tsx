import { useMemo } from 'react';
import { BlockRenderer } from '@auraboot/runtime-kernel';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import {
  shouldRenderDefaultDetailEditAction,
  resolveDetailHeaderLayoutClasses,
  resolvePersistentDetailHeaderBlocks,
  resolveDirectSpecializedDetailBlocks,
} from './detailLayoutValues';
import { prepareDetailRuntimeBlock } from './detailBlockValues';
import { DetailBlockRenderer } from './DetailBlockRenderer';
import type { useDetailPageState } from './useDetailPageState';
import type { useDetailRuntime } from './useDetailRuntime';
import type { useDetailMetadata } from './useDetailMetadata';

type UseDetailLayoutArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  'schema' | 'hasPermission' | 'recordData' | 'recordPid' | 'rawData' | 'token' | 'tableName'
> &
  Pick<
    ReturnType<typeof useDetailRuntime>,
    | 'evaluateVisibleWhen'
    | 'detailRefreshVersion'
    | 'runtime'
    | 'locale'
    | 't'
    | 'refreshDetailAfterChildChange'
    | 'dataSourceManager'
  > &
  Pick<ReturnType<typeof useDetailMetadata>, 'getDictItems' | 'enrichField'>;

export function useDetailLayout({
  schema,
  hasPermission,
  evaluateVisibleWhen,
  detailRefreshVersion,
  runtime,
  recordData,
  recordPid,
  rawData,
  token,
  locale,
  t,
  tableName,
  refreshDetailAfterChildChange,
  getDictItems,
  enrichField,
  dataSourceManager,
}: UseDetailLayoutArgs) {
  // Extract blocks from schema
  const allBlocks = useMemo(() => {
    return schema?.blocks || [];
  }, [schema]);
  const showDefaultEditAction = shouldRenderDefaultDetailEditAction(schema);

  const headerToolbar = useMemo(
    () => allBlocks.find((b: BlockConfig) => b.blockType === 'toolbar'),
    [allBlocks],
  );
  const effectiveHeaderToolbar = headerToolbar || null;
  const headerLayout = resolveDetailHeaderLayoutClasses();

  // For simple detail pages without tabs, find field-display blocks directly.
  // Page Designer exposes both form-section and detail-section; both render
  // as read-only field groups on detail pages.
  const directFormBlocks = useMemo(
    () =>
      allBlocks.filter(
        (b: BlockConfig) => b.blockType === 'form-section' || b.blockType === 'detail-section',
      ),
    [allBlocks],
  );
  const effectiveDirectFormBlocks = directFormBlocks;

  const directSubTableBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'sub-table'),
    [allBlocks],
  );

  const directMonthlyGridBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'monthly-grid'),
    [allBlocks],
  );

  const directEmbeddedListBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'embedded-list'),
    [allBlocks],
  );

  const directSpecializedBlocks = useMemo(
    () => resolveDirectSpecializedDetailBlocks(allBlocks),
    [allBlocks],
  );

  // G7 dispatch — blocks not handled by the hardcoded switch above still need
  // to render. Collect anything that is NOT a known detail-page block type and
  // delegate to BlockRenderer (unified block dispatcher). This lets chart,
  // description, rich-text, divider, stat-card etc. render on detail pages
  // without every detail page having to opt in explicitly.
  const DETAIL_SPECIALIZED_BLOCK_TYPES = new Set<string>([
    'toolbar',
    'tabs',
    'form-section',
    'detail-section',
    'sub-table',
    'embedded-list',
    'monthly-grid',
    'activity-timeline',
    'record-comments',
    'field-history',
    'workflow-panel',
  ]);
  const directMiscBlocks = useMemo(
    () =>
      allBlocks.filter(
        (b: BlockConfig) => !DETAIL_SPECIALIZED_BLOCK_TYPES.has(b.blockType as string),
      ),
    [allBlocks],
  );
  const asideBlockIds = new Set<string>(
    Array.isArray(schema?.extension?.detailAsideBlockIds)
      ? schema.extension.detailAsideBlockIds.filter((id: unknown) => typeof id === 'string')
      : [],
  );
  const workspaceContentBlocks = allBlocks.filter(
    (block: BlockConfig) =>
      block.blockType !== 'toolbar' &&
      block.blockType !== 'tabs' &&
      (block as { detailPlacement?: string }).detailPlacement !== 'header',
  );
  const renderWorkspaceBlock = (block: BlockConfig) => {
    if (block.permissionCode && !hasPermission(block.permissionCode)) return null;
    if (block.visibleWhen && !evaluateVisibleWhen(block.visibleWhen)) return null;
    const key = `${block.id}:${['activity-timeline', 'field-history'].includes(block.blockType) ? detailRefreshVersion : 0}`;
    if (!DETAIL_SPECIALIZED_BLOCK_TYPES.has(block.blockType as string)) {
      return runtime ? (
        <BlockRenderer
          key={key}
          block={prepareDetailRuntimeBlock(block, recordData, recordPid!)}
          runtime={runtime}
          areaId="detail-workspace"
        />
      ) : null;
    }
    return (
      <DetailBlockRenderer
        key={key}
        block={block}
        recordData={recordData}
        rawData={rawData}
        recordPid={recordPid!}
        token={token || undefined}
        locale={locale}
        t={t}
        modelCode={schema?.modelCode || tableName}
        evaluateEditableWhen={evaluateVisibleWhen}
        onDataChange={refreshDetailAfterChildChange}
        getDictItems={getDictItems}
        enrichField={enrichField}
        runtime={runtime as SchemaRuntime}
        schemaDataSources={schema?.dataSources}
        dataSourceManager={dataSourceManager}
      />
    );
  };
  const persistentHeaderBlocks = resolvePersistentDetailHeaderBlocks(
    allBlocks,
    evaluateVisibleWhen,
    hasPermission,
  );
  const headerMiscBlocks = directMiscBlocks.filter(
    (block: BlockConfig) => (block as { detailPlacement?: string }).detailPlacement === 'header',
  );
  return {
    allBlocks,
    showDefaultEditAction,
    effectiveHeaderToolbar,
    headerLayout,
    effectiveDirectFormBlocks,
    directSubTableBlocks,
    directMonthlyGridBlocks,
    directEmbeddedListBlocks,
    directSpecializedBlocks,
    DETAIL_SPECIALIZED_BLOCK_TYPES,
    directMiscBlocks,
    asideBlockIds,
    workspaceContentBlocks,
    renderWorkspaceBlock,
    persistentHeaderBlocks,
    headerMiscBlocks,
  };
}
