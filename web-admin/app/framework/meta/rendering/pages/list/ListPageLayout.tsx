import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { AsyncTaskModalHost } from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { ListErrorState } from './ListErrorState';
import { BulkActionResultDialog } from './BulkActionResultDialog';
import { renderListPageFeedback } from './ListPageFeedback';
import { renderListPageFilters } from './ListPageFilters';
import { ListPageHeader } from './ListPageHeader';
import { renderListPageModals } from './ListPageModals';
import type { ListPageModel } from './listPageModel';
import { renderListPageResults } from './ListPageResults';

/** Compose header, feedback, filters, results and overlays in their original order. */
export function renderListPage(model: ListPageModel) {
  const {
    schema,
    tableName,
    t,
    locale,
    workspace,
    schemaExtension,
    modelCode,
    savedViews,
    currentView,
    viewsLoading,
    activeViewType,
    handleSelectDefaultView,
    handleSelectView,
    setActiveViewType,
    setStartCreateViewMode,
    setViewManageOpen,
    availableViewTypes,
    actionBlock,
    effectiveViewConfig,
    handleAction,
    handleToolbarConfigChange,
    resolveButtonLabel,
    evaluateButtonVisible,
    setImportOpen,
    handleExport,
    exportFilterConditions,
    isTenantMemberPage,
    canInviteMembers,
    inviteTriggerRef,
    setInviteDialogOpen,
    canManageMemberAccounts,
    setMemberImportDialogOpen,
    hideSavedViews,
    skipListData,
    listExtensions,
    canImport,
    error,
    setError,
    listPageTitle,
    navigateAwayFromList,
    getFailedApiRequest,
    loadData,
    pagination,
    filters,
    dataSourceManager,
    dataReady,
    loading,
    listQuerySettled,
    bulkActionResult,
    setBulkActionResult,
  } = model;

  const pageHeader = (
    <ListPageHeader
      title={(() => {
        // Import writes page titles into the tenant i18n bundle as
        // page.<pageKey>.title (en + zh-CN). Prefer that key so the title
        // follows the UI locale; schema.title/schema.name are stored as
        // plain zh strings and would otherwise pin the title to zh.
        const listPageKey = schema.pageKey || tableName;
        const keyedTitle = t(`page.${listPageKey}.title`);
        if (keyedTitle && keyedTitle !== `page.${listPageKey}.title`) {
          return keyedTitle;
        }
        if (schema.title) return getLocalizedText(schema.title, locale, t);
        if (schema.name && schema.name.trim()) {
          return getLocalizedText(schema.name, locale, t);
        }
        return tableName;
      })()}
      workspace={workspace}
      description={getLocalizedText(schemaExtension.pageDescription || {}, locale, t)}
      modelCode={modelCode}
      savedViews={savedViews}
      currentView={currentView}
      viewsLoading={viewsLoading}
      activeViewType={activeViewType}
      onSelectDefaultView={handleSelectDefaultView}
      onSelectView={handleSelectView}
      onCreateView={(viewType) => {
        if (viewType) {
          setActiveViewType(viewType);
        }
        setStartCreateViewMode(true);
        setViewManageOpen(true);
      }}
      onManageViews={() => {
        setStartCreateViewMode(false);
        setViewManageOpen(true);
      }}
      onViewTypeChange={(vt) => {
        setActiveViewType(vt);
      }}
      enableMultiView={Boolean(schemaExtension.enableMultiView)}
      availableViewTypes={availableViewTypes}
      buttons={actionBlock?.buttons || []}
      toolbarActions={effectiveViewConfig?.toolbarActions}
      onAction={handleAction}
      onToolbarConfigChange={handleToolbarConfigChange}
      resolveLabel={resolveButtonLabel}
      t={t}
      evaluateVisible={evaluateButtonVisible}
      onImport={() => setImportOpen(true)}
      onExport={handleExport}
      exportFilters={exportFilterConditions}
      isTenantMemberPage={isTenantMemberPage}
      onInvite={
        canInviteMembers
          ? () => {
              inviteTriggerRef.current =
                document.activeElement instanceof HTMLElement ? document.activeElement : null;
              setInviteDialogOpen(true);
            }
          : undefined
      }
      onImportMembers={canManageMemberAccounts ? () => setMemberImportDialogOpen(true) : undefined}
      hideSavedViews={hideSavedViews}
      hideBuiltInImport={skipListData ? true : (listExtensions?.hideBuiltInImport ?? !canImport)}
      hideBuiltInExport={
        skipListData
          ? true
          : (listExtensions?.hideBuiltInExport ??
            (schema as any)?.extension?.hideBuiltInExport ??
            (schema as any)?.extension?.hideToolbarMore)
      }
      hideBuiltInPrint={
        skipListData
          ? true
          : (listExtensions?.hideBuiltInPrint ??
            (schema as any)?.extension?.hideBuiltInPrint ??
            (schema as any)?.extension?.hideToolbarMore)
      }
    />
  );

  // Error handling — local errors only (schema loading errors handled by DynamicPageRenderer)
  if (error) {
    return (
      <ListErrorState
        title={listPageTitle}
        buttons={actionBlock?.buttons || []}
        evaluateVisible={evaluateButtonVisible}
        resolveLabel={resolveButtonLabel}
        onNavigate={to => navigateAwayFromList(to)}
        error={error}
        errorTitle={getLocalizedText({ 'zh-CN': '加载失败', en: 'Unable to load' }, locale, t)}
        retryLabel={getLocalizedText({ 'zh-CN': '重试', en: 'Retry' }, locale, t)}
        onRetry={() => {
          setError(null);
          const retryRequest = getFailedApiRequest();
          void loadData(retryRequest
            ? { retryRequest }
            : { page: pagination.current - 1, size: pagination.pageSize, filters });
        }}
      />
    );
  }

  return (
    <DataSourceProvider manager={dataSourceManager}>
      <div
        className="bg-subtle min-h-[calc(100vh-3.5rem)] w-full px-4 py-5 sm:px-6 lg:px-8"
        style={workspace ? { backgroundColor: '#F7F7F8' } : undefined}
        data-testid="dynamic-list"
        data-model-code={modelCode}
        data-ready={dataReady && !loading && !viewsLoading && listQuerySettled}
        aria-busy={!dataReady || loading || viewsLoading || !listQuerySettled}
        data-ab-testid={deriveTestId('list', modelCode, 'container')}
      >
        {pageHeader}
        <div
          className={`rounded-card border-border bg-panel relative overflow-hidden border ${workspace ? '' : 'shadow-sm'}`}
        >
          {renderListPageFeedback(model, pageHeader)}

          {renderListPageFilters(model)}

          {renderListPageResults(model)}

          {renderListPageModals(model)}
        </div>
      </div>
      <AsyncTaskModalHost />
      <BulkActionResultDialog
        result={bulkActionResult}
        onClose={() => setBulkActionResult(null)}
        locale={locale}
        t={t}
      />
    </DataSourceProvider>
  );
}
