import type { PageContentProps } from '@auraboot/runtime-kernel';
import { AsyncTaskModalProvider } from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import { renderListPage } from './list/ListPageLayout';
import { useListBulkActions } from './list/useListBulkActions';
import { useListColumns } from './list/useListColumns';
import { useListDataLoader } from './list/useListDataLoader';
import { useListExport } from './list/useListExport';
import { useListMemberInvite } from './list/useListMemberInvite';
import { useListMetadata } from './list/useListMetadata';
import { useListPageRuntime } from './list/useListPageRuntime';
import { useListSelectionBinding } from './list/useListSelectionBinding';
import { useListPageState } from './list/useListPageState';
import { useListQueryActions } from './list/useListQueryActions';
import { useListQuickFilters } from './list/useListQuickFilters';
import { useListSavedViewState } from './list/useListSavedViewState';
import { useListViewPersistence } from './list/useListViewPersistence';
import { useListWorkspaceTabs } from './list/useListWorkspaceTabs';

export {
  buildListColumnSettingsDefinitions,
  buildListFilterFieldMetadata,
  buildViewManageFieldOptions,
  getListFieldValueWithAlias,
  getSystemFieldI18nKey,
  renderComponentToValueType,
  resolveColumnCapabilityDataType,
  resolveFieldMetaDataType,
  resolveFieldMetaDisplayName,
  resolveFieldMetaRenderComponent,
  type ListFilterFieldMetadata,
  type ViewManageFieldOption,
} from './list/listFieldMetadata';
export {
  collectListBareParamFilters,
  collectListSchemaFieldNames,
  queryConditionToExportCondition,
  resolveSavedViewFilterExpressions,
  resolveUserFilterCondition,
  tabFilterToQueryCondition,
  viewConfigFiltersToRuntimeFilters,
  viewFilterToQueryCondition,
  type ListQueryFilterCondition,
  type SavedViewFilterExpressionContext,
} from './list/listFilterConditions';
export {
  applyLocalSortUpdate,
  beginLatestListRequest,
  useListApiReadRetry,
  useListKeywordSearch,
  useRestoreSavedViewFromUrl,
  useSerializedSearchParamsUpdater,
} from './list/listPageStateUtils';
export {
  buildListReferenceDisplayCacheKey,
  collectListReferenceDisplayConfigs,
  collectListReferenceValues,
  resolveListReferenceFilterLabel,
  resolveListSystemReferenceDisplayConfig,
  shouldResolveListSystemReferenceValue,
  type ListReferenceDisplayConfig,
} from './list/listReferenceDisplay';
export {
  buildBulkFieldCommandPayload,
  buildToolbarConditionContext,
  collectListMiscBlocks,
  resolveInitialListTabKey,
  resolveListMiscBlocksPosition,
  resolveTableBlockRowActions,
  shouldSkipListData,
  shouldSkipModelFieldMeta,
  type ListMiscBlocksPosition,
} from './list/listSchemaPolicy';
export {
  areListSortFilterQueriesEqual,
  findPersonalPresetSavedView,
  isListQuerySettled,
  pruneNoopViewConfigPatch,
  resolveListSavedViewPageKey,
  resolveUrlStateSyncAction,
} from './list/listViewConfig';

/**
 * Keep controllers in the original hook order. The three deferred getters retain
 * forward lexical references from this render: callbacks can read values declared
 * later without evaluating them during render or switching to a newer render.
 */
function ListPageContentInner(props: PageContentProps) {
  const state = useListPageState(props, {
    get loadData() {
      return dataLoader.loadData;
    },
  });
  const pageRuntime = useListPageRuntime({ ...state });
  useListSelectionBinding({ ...state, runtime: pageRuntime.runtime });
  const savedViews = useListSavedViewState({ ...pageRuntime, ...state });
  const metadata = useListMetadata(
    { ...pageRuntime, ...state, ...savedViews },
    {
      get filterBlock() {
        return columns.filterBlock;
      },
    },
  );
  const dataLoader = useListDataLoader({ ...pageRuntime, ...state, ...metadata, ...savedViews });
  const queryActions = useListQueryActions(
    { ...pageRuntime, ...state, ...dataLoader, ...metadata, ...savedViews },
    {
      get autoSave() {
        return viewPersistence.autoSave;
      },
    },
  );
  const bulkActions = useListBulkActions({
    ...pageRuntime,
    ...dataLoader,
    ...state,
    ...queryActions,
  });
  const columns = useListColumns({ ...state, ...metadata, ...pageRuntime, ...savedViews });
  const viewPersistence = useListViewPersistence({
    ...savedViews,
    ...state,
    ...pageRuntime,
    ...columns,
    ...metadata,
    ...dataLoader,
  });
  const exportActions = useListExport({
    ...queryActions,
    ...state,
    ...columns,
    ...metadata,
    ...pageRuntime,
    ...savedViews,
    ...viewPersistence,
  });
  const quickFilters = useListQuickFilters({
    ...state,
    ...pageRuntime,
    ...dataLoader,
    ...savedViews,
    ...metadata,
    ...viewPersistence,
  });
  const memberInvite = useListMemberInvite({ ...pageRuntime, ...state });
  const workspaceTabs = useListWorkspaceTabs({ ...columns, ...state, ...metadata, t: pageRuntime.t, locale: pageRuntime.locale });
  return renderListPage({
    ...state,
    ...pageRuntime,
    ...savedViews,
    ...metadata,
    ...dataLoader,
    ...queryActions,
    ...bulkActions,
    ...columns,
    ...viewPersistence,
    ...exportActions,
    ...quickFilters,
    ...memberInvite,
    ...workspaceTabs,
  });
}

/** Page-level async-task context is shared by every action handler. */
export function ListPageContent(props: PageContentProps) {
  return (
    <AsyncTaskModalProvider>
      <ListPageContentInner {...props} />
    </AsyncTaskModalProvider>
  );
}
