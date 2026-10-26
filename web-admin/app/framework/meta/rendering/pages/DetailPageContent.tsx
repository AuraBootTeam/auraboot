import { useState, useMemo, useEffect } from 'react';
import { type PageContentProps } from '@auraboot/runtime-kernel';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { AsyncTaskModalProvider } from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { AdditionalPermissionsProvider } from '~/contexts/AuthContext';
import { useDetailPageState } from './detailPage/useDetailPageState';
import { useRenderedReceipt } from './detailPage/useRenderedReceipt';
import { useDetailRecord } from './detailPage/useDetailRecord';
import { useDetailMetadata } from './detailPage/useDetailMetadata';
import { useDetailSharing } from './detailPage/useDetailSharing';
import { useDetailRuntime } from './detailPage/useDetailRuntime';
import { useDetailLayout } from './detailPage/useDetailLayout';
import { useDetailTabs } from './detailPage/useDetailTabs';
import { renderDetailPageView } from './detailPage/renderDetailPageView';

export type { DetailRecordRefreshSnapshot } from './detailPage/detailPageTypes';
export {
  isDetailRecordAccessDenied,
  isDetailRecordNotFound,
  buildDetailRecordEndpoint,
  shouldSkipDetailModelFieldMeta,
  hasDetailApiDataSource,
  getByDataPath,
  resolveDetailRecordEndpoint,
  isSuccessfulDetailResult,
  fetchDetailRecord,
  unwrapDetailRecord,
  mergeDetailDisplayFields,
  loadDetailRecordRefreshSnapshot,
  assertStrictDetailRefreshParent,
  readDetailRecordField,
} from './detailPage/detailRecordRequests';
export {
  resolveDetailFieldComponent,
  enrichDetailField,
  resolveSettingsCardField,
  resolveSettingsCardDisplayValue,
  collectDetailDictCodes,
} from './detailPage/detailFieldDisplay';
export {
  resolveDetailMissingRecordTitle,
  resolveDetailReturnTarget,
  shouldRenderDefaultDetailEditAction,
  canRenderDetailToolbarButton,
  resolveDetailHeaderLayoutClasses,
  resolveHiddenSystemTabKeys,
  resolveVisibleDetailTabs,
  resolveVisibleDetailTabsFromBlocks,
  resolvePersistentDetailHeaderBlocks,
  shouldRenderInlineDetailApproval,
  resolveVisibleTopLevelDetailBlocks,
  resolvePermissionHiddenDataSourceIds,
  resolveDirectSpecializedDetailBlocks,
  evaluateDetailVisibleWhen,
  resolveSubTableDataSourceConfig,
  resolveActiveDetailTab,
  resolveDetailPdfFileName,
} from './detailPage/detailLayoutValues';
export {
  extractBlockDataRows,
  injectDetailRecordValueIntoCustomBlock,
} from './detailPage/detailBlockValues';
export { DataPathTable } from './detailPage/DataPathTable';

function DetailPageContentInner(props: PageContentProps) {
  const {
    timezone,
    schema,
    tableName,
    recordPid,
    token,
    pdfExportTargetRef,
    hasPermission,
    location,
    routerNavigate,
    recordModelCode,
    routeRecordPid,
    recordData,
    setRecordData,
    rawData,
    setRawData,
    recordLoading,
    setRecordLoading,
    recordError,
    setRecordError,
    recordAccessDenied,
    setRecordAccessDenied,
    recordNotFound,
    setRecordNotFound,
    modelFieldMap,
    setModelFieldMap,
    hasApiSingletonRecordSource,
  } = useDetailPageState({ props });
  const { reloadRecordStrict, reloadRecord } = useDetailRecord({
    routeRecordPid,
    hasApiSingletonRecordSource,
    recordModelCode,
    setRecordLoading,
    setRecordError,
    schema,
    token,
    setModelFieldMap,
    location,
    setRawData,
    setRecordData,
    setRecordAccessDenied,
    setRecordNotFound,
    tableName,
    rawData,
  });
  const { enrichField, getDictItems, showToast } = useDetailMetadata({
    modelFieldMap,
    schema,
    token,
  });
  const { shareDialogOpen, setShareDialogOpen, canManageRecordShares } = useDetailSharing({
    schema,
    recordModelCode,
    routeRecordPid,
    token,
  });
  const {
    runtime,
    dataSourceManager,
    t,
    locale,
    detailRefreshVersion,
    refreshDetailAfterChildChange,
    handleAction,
    actionLoading,
    actionError,
    setError,
    evaluateVisibleWhen,
    resolveButtonLabel,
  } = useDetailRuntime({
    recordData,
    schema,
    recordPid,
    hasPermission,
    token,
    showToast,
    reloadRecordStrict,
    reloadRecord,
    tableName,
  });
  const {
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
  } = useDetailLayout({
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
  });
  const {
    tabs,
    topLevelDetailBlocks,
    activeTab,
    handleTabChange,
    activeDetailTab,
    actionFailedTitle,
    closeLabel,
  } = useDetailTabs({
    allBlocks,
    evaluateVisibleWhen,
    hasPermission,
    recordPid,
    schema,
    location,
    routerNavigate,
    t,
  });
  const receipt = useRenderedReceipt({
    config: schema?.extension?.renderedReceipt,
    root: pdfExportTargetRef,
    record: recordData,
    loading: recordLoading,
    failed: Boolean(recordError || recordAccessDenied || recordNotFound),
    recordPid: routeRecordPid,
    onReceipt: setRecordData,
  });
  const view = renderDetailPageView({
    recordLoading,
    recordError,
    schema,
    tableName,
    recordAccessDenied,
    locale,
    t,
    recordNotFound,
    recordData,
    location,
    headerLayout,
    routerNavigate,
    enrichField,
    getDictItems,
    canManageRecordShares,
    setShareDialogOpen,
    pdfExportTargetRef,
    effectiveHeaderToolbar,
    hasPermission,
    evaluateVisibleWhen,
    handleAction,
    actionLoading,
    resolveButtonLabel,
    actionError,
    actionFailedTitle,
    setError,
    closeLabel,
    token,
    timezone,
    tabs,
    persistentHeaderBlocks,
    renderWorkspaceBlock,
    activeTab,
    handleTabChange,
    runtime,
    activeDetailTab,
    asideBlockIds,
    rawData,
    recordPid,
    refreshDetailAfterChildChange,
    dataSourceManager,
    topLevelDetailBlocks,
    DETAIL_SPECIALIZED_BLOCK_TYPES,
    headerMiscBlocks,
    workspaceContentBlocks,
    directMiscBlocks,
    effectiveDirectFormBlocks,
    directSubTableBlocks,
    directMonthlyGridBlocks,
    directEmbeddedListBlocks,
    directSpecializedBlocks,
    showDefaultEditAction,
    shareDialogOpen,
  });
  return <>
    {receipt.error && <div role="alert" className="mx-6 my-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm">
      <span>{receipt.error}</span>
      <button type="button" onClick={receipt.retry} className="ms-3 underline">{t('common.retry')}</button>
    </div>}
    {view}
  </>;

}

export function DetailPageContent(props: PageContentProps) {
  const { schema, tableName, recordPid, token } = props;
  const resourceCode = schema?.modelCode || tableName;
  const sharePolicy = schema?.extension?.recordShare;
  const collaborationPermissions = useMemo(
    () =>
      Array.isArray(sharePolicy?.collaborationPermissions)
        ? sharePolicy.collaborationPermissions
            .map((code: unknown) => String(code || '').trim())
            .filter(Boolean)
        : [],
    [sharePolicy?.collaborationPermissions],
  );
  const [sharedPermissions, setSharedPermissions] = useState<string[]>([]);
  const [shareAccessResolved, setShareAccessResolved] = useState(
    sharePolicy?.mode !== 'collaborate-only' || collaborationPermissions.length === 0,
  );

  useEffect(() => {
    if (
      sharePolicy?.mode !== 'collaborate-only' ||
      collaborationPermissions.length === 0 ||
      !resourceCode ||
      !recordPid
    ) {
      setSharedPermissions([]);
      setShareAccessResolved(true);
      return;
    }
    let cancelled = false;
    setShareAccessResolved(false);
    const params = new URLSearchParams({ resourceCode, recordPid });
    fetchResult<{ canUpdate?: boolean }>(
      `/api/record-share/access-capability?${params.toString()}`,
      { method: 'get', token: token || undefined },
    )
      .then((result) => {
        if (cancelled) return;
        setSharedPermissions(
          ResultHelper.isSuccess(result) && result.data?.canUpdate === true
            ? collaborationPermissions
            : [],
        );
      })
      .catch(() => {
        if (!cancelled) setSharedPermissions([]);
      })
      .finally(() => {
        if (!cancelled) setShareAccessResolved(true);
      });
    return () => {
      cancelled = true;
    };
  }, [collaborationPermissions, recordPid, resourceCode, sharePolicy?.mode, token]);

  if (!shareAccessResolved) return <LoadingSpinner />;
  return (
    <AdditionalPermissionsProvider permissions={sharedPermissions}>
      <AsyncTaskModalProvider>
        <DetailPageContentInner {...props} />
      </AsyncTaskModalProvider>
    </AdditionalPermissionsProvider>
  );
}
