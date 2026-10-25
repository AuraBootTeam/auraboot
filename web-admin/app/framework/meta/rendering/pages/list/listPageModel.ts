import type { useListBulkActions } from './useListBulkActions';
import type { useListColumns } from './useListColumns';
import type { useListDataLoader } from './useListDataLoader';
import type { useListExport } from './useListExport';
import type { useListMemberInvite } from './useListMemberInvite';
import type { useListMetadata } from './useListMetadata';
import type { useListPageRuntime } from './useListPageRuntime';
import type { useListPageState } from './useListPageState';
import type { useListQueryActions } from './useListQueryActions';
import type { useListQuickFilters } from './useListQuickFilters';
import type { useListSavedViewState } from './useListSavedViewState';
import type { useListViewPersistence } from './useListViewPersistence';
import type { useListWorkspaceTabs } from './useListWorkspaceTabs';

/** Rendering contract assembled from the domain controllers. */
export type ListPageModel = ReturnType<typeof useListPageState> &
  ReturnType<typeof useListPageRuntime> &
  ReturnType<typeof useListSavedViewState> &
  ReturnType<typeof useListMetadata> &
  ReturnType<typeof useListDataLoader> &
  ReturnType<typeof useListQueryActions> &
  ReturnType<typeof useListBulkActions> &
  ReturnType<typeof useListColumns> &
  ReturnType<typeof useListViewPersistence> &
  ReturnType<typeof useListExport> &
  ReturnType<typeof useListQuickFilters> &
  ReturnType<typeof useListMemberInvite> &
  ReturnType<typeof useListWorkspaceTabs>;
