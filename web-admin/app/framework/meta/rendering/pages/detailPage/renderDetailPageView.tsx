import { isButtonDisabled } from '~/framework/meta/utils/buttonPermissions';
import { WorkspaceTabCount } from '../detail/WorkspaceTabCount';
import { WorkspaceObjectSummary } from '../detail/WorkspaceObjectSummary';
import { Link } from 'react-router';
import { PrintButton } from '~/framework/meta/rendering/components/PrintButton';
import { ExportPdfButton } from '~/framework/smart/components/data-tools/ExportPdfButton';
import { RecordShareDialog } from '~/ui/shared/RecordShareDialog';
import { BlockRenderer } from '@auraboot/runtime-kernel';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { AsyncTaskModalHost } from '~/framework/meta/rendering/components/AsyncTaskModalContext';
import { ReportGenerateButton } from '~/framework/smart/components/report/ReportGenerateButton';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { InlineApprovalPanel } from '~/framework/smart/components/approval/InlineApprovalPanel';
import { NbaSuggestionBar } from '~/framework/meta/rendering/blocks/NbaSuggestionBar';
import { LayoutRenderer } from '~/framework/meta/rendering/layout/LayoutRenderer';
import FormDialog from '~/framework/meta/runtime/actions/FormDialog';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig, ButtonConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { deriveTestId, buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import {
  resolveDetailMissingRecordTitle,
  resolveDetailReturnTarget,
  canRenderDetailToolbarButton,
  shouldRenderInlineDetailApproval,
  resolveDetailPdfFileName,
} from './detailLayoutValues';
import { resolveTextFallback } from './detailFieldDisplay';
import { prepareDetailRuntimeBlock } from './detailBlockValues';
import { DetailBlockRenderer } from './DetailBlockRenderer';
import { FallbackDetailView } from './FallbackDetailView';
import type { useDetailPageState } from './useDetailPageState';
import type { useDetailRuntime } from './useDetailRuntime';
import type { useDetailLayout } from './useDetailLayout';
import type { useDetailMetadata } from './useDetailMetadata';
import type { useDetailSharing } from './useDetailSharing';
import type { useDetailTabs } from './useDetailTabs';

type RenderDetailPageViewArgs = Pick<
  ReturnType<typeof useDetailPageState>,
  | 'recordLoading'
  | 'recordError'
  | 'schema'
  | 'tableName'
  | 'recordAccessDenied'
  | 'recordNotFound'
  | 'recordData'
  | 'location'
  | 'routerNavigate'
  | 'pdfExportTargetRef'
  | 'hasPermission'
  | 'token'
  | 'timezone'
  | 'rawData'
  | 'recordPid'
> &
  Pick<
    ReturnType<typeof useDetailRuntime>,
    | 'locale'
    | 't'
    | 'evaluateVisibleWhen'
    | 'handleAction'
    | 'actionLoading'
    | 'resolveButtonLabel'
    | 'actionError'
    | 'setError'
    | 'runtime'
    | 'refreshDetailAfterChildChange'
    | 'dataSourceManager'
  > &
  Pick<
    ReturnType<typeof useDetailLayout>,
    | 'headerLayout'
    | 'effectiveHeaderToolbar'
    | 'persistentHeaderBlocks'
    | 'renderWorkspaceBlock'
    | 'asideBlockIds'
    | 'DETAIL_SPECIALIZED_BLOCK_TYPES'
    | 'headerMiscBlocks'
    | 'workspaceContentBlocks'
    | 'directMiscBlocks'
    | 'effectiveDirectFormBlocks'
    | 'directSubTableBlocks'
    | 'directMonthlyGridBlocks'
    | 'directEmbeddedListBlocks'
    | 'directSpecializedBlocks'
    | 'showDefaultEditAction'
  > &
  Pick<ReturnType<typeof useDetailMetadata>, 'enrichField' | 'getDictItems'> &
  Pick<
    ReturnType<typeof useDetailSharing>,
    'canManageRecordShares' | 'setShareDialogOpen' | 'shareDialogOpen'
  > &
  Pick<
    ReturnType<typeof useDetailTabs>,
    | 'actionFailedTitle'
    | 'closeLabel'
    | 'tabs'
    | 'activeTab'
    | 'handleTabChange'
    | 'activeDetailTab'
    | 'topLevelDetailBlocks'
  >;

export function renderDetailPageView({
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
}: RenderDetailPageViewArgs) {
  // Show loading while record is being fetched
  if (recordLoading) {
    return <LoadingSpinner />;
  }

  if (recordError) {
    return (
      <div
        className="mx-auto w-full px-2 py-3"
        data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'container')}
      >
        <div className="rounded-card bg-panel p-8 shadow-sm">
          <div className="mx-auto grid max-w-lg gap-3 text-center">
            <h2 className="text-text text-lg font-semibold">
              {recordAccessDenied
                ? getLocalizedText(
                    {
                      'zh-CN': '无法访问此记录',
                      en: 'Cannot access this record',
                    },
                    locale,
                    t,
                  )
                : resolveDetailMissingRecordTitle(locale, t)}
            </h2>
            <p className="text-text-2 text-sm">
              {recordAccessDenied
                ? getLocalizedText(
                    {
                      'zh-CN': '当前账号没有访问权限，请联系记录负责人。',
                      en: 'Your account does not have access. Contact the record owner.',
                    },
                    locale,
                    t,
                  )
                : recordNotFound
                  ? getLocalizedText(
                      {
                        'zh-CN': '请求的记录不存在或已不可用。',
                        en: 'The requested record does not exist or is no longer available.',
                      },
                      locale,
                      t,
                    )
                  : recordError}
            </p>
            <Link
              to={`/p/${tableName}`}
              className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover mx-auto inline-flex border px-3 py-1.5 text-sm font-medium"
            >
              {resolveTextFallback(t, 'action.back', 'Back')}
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const workspace = schema.extension?.presentation === 'workspace';
  const headerTitleField = String(schema?.extension?.headerTitleField || '').trim();
  const headerTitleValue = headerTitleField ? recordData?.[headerTitleField] : undefined;
  const headerTitle =
    headerTitleValue !== undefined && headerTitleValue !== null && headerTitleValue !== ''
      ? String(headerTitleValue)
      : getLocalizedText(schema.title, locale, t);
  const headerSubtitleFields = Array.isArray(schema?.extension?.headerSubtitleFields)
    ? schema.extension.headerSubtitleFields
    : [];
  const headerSubtitle = headerSubtitleFields
    .map((field: unknown) => {
      const key = String(field || '').trim();
      if (!key) return '';
      const value = recordData?.[`${key}_display`] ?? recordData?.[key];
      return value === undefined || value === null || value === '' ? '' : String(value);
    })
    .filter(Boolean)
    .join(' · ');
  const preserveListContext = schema?.extension?.preserveListContext === true;
  const preservedReturnTarget = preserveListContext
    ? resolveDetailReturnTarget(location.search)
    : null;
  const backPath = `/p/${tableName}${location.search || ''}`;
  const backIcon = (
    <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
    </svg>
  );

  return (
    <div
      className={
        workspace
          ? 'min-h-[calc(100vh-56px)] bg-[#F7F7F8] px-6 pt-7 pb-8 lg:px-8'
          : 'mx-auto w-full px-2 py-3'
      }
      data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'container')}
    >
      <div
        className={workspace ? 'min-w-0' : 'rounded-card border-border bg-panel border shadow-sm'}
      >
        {/* Page Header with title + toolbar buttons (hidden in print) */}
        <div
          className={
            workspace ? 'print-hide pb-6' : 'print-hide border-border border-b px-4 py-4 sm:px-6'
          }
          data-print="hide"
        >
          <div className={headerLayout.container}>
            <div className="flex min-w-0 items-start gap-3">
              {schema.extension?.showBack !== false &&
                (preserveListContext && (preservedReturnTarget || location.key !== 'default') ? (
                  <button
                    type="button"
                    aria-label={resolveTextFallback(t, 'action.back', 'Back')}
                    onClick={() =>
                      preservedReturnTarget
                        ? routerNavigate(preservedReturnTarget)
                        : routerNavigate(-1)
                    }
                    className="text-text-3 hover:text-text-2 mt-0.5 shrink-0"
                  >
                    {backIcon}
                  </button>
                ) : (
                  <Link
                    to={backPath}
                    aria-label={resolveTextFallback(t, 'action.back', 'Back')}
                    className="text-text-3 hover:text-text-2 mt-0.5 shrink-0"
                  >
                    {backIcon}
                  </Link>
                ))}
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-3">
                  <h2 className="text-text text-2xl leading-tight font-semibold tracking-tight break-words">
                    {headerTitle}
                  </h2>
                  {workspace && schema.extension?.headerStatusField && (
                    <span className="text-accent bg-accent-weak rounded px-2 py-1 text-xs">
                      {(() => {
                        const field = enrichField({
                          field: schema.extension.headerStatusField,
                        } as FieldConfig);
                        const item = (getDictItems(field.dictCode || '') || []).find(
                          (item) => String(item.value) === String(recordData?.[field.field]),
                        );
                        return item ? getLocalizedText(item.label as any, locale, t) : '—';
                      })()}
                    </span>
                  )}
                </div>
                {headerSubtitle ? (
                  <p className="text-text-3 mt-1 truncate text-xs" title={headerSubtitle}>
                    {headerSubtitle}
                  </p>
                ) : null}
              </div>
            </div>

            {/* Header toolbar buttons */}
            <div className={headerLayout.actions} data-print="hide">
              {schema.extension?.showShare !== false && canManageRecordShares && (
                <button
                  onClick={() => setShareDialogOpen(true)}
                  className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-3 py-1.5 text-sm font-medium"
                  data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'share-btn')}
                >
                  {t('action.share')}
                </button>
              )}
              {schema.extension?.showReport !== false && recordData?.pid && (
                <ReportGenerateButton
                  modelCode={schema?.modelCode || tableName}
                  recordPid={recordData.pid}
                />
              )}
              {schema.extension?.showPrint !== false && (
                <PrintButton title={getLocalizedText(schema.title, locale, t)} />
              )}
              {schema.extension?.showPdfExport !== false && (
                <ExportPdfButton
                  targetRef={pdfExportTargetRef}
                  fileName={resolveDetailPdfFileName(schema, recordData, tableName, locale, t)}
                  orientation="portrait"
                />
              )}
              {effectiveHeaderToolbar?.buttons && effectiveHeaderToolbar.buttons.length > 0 && (
                <>
                  {effectiveHeaderToolbar.buttons
                    .filter((button: ButtonConfig) =>
                      canRenderDetailToolbarButton(button, hasPermission),
                    )
                    .filter((button: ButtonConfig) => evaluateVisibleWhen(button.visibleWhen))
                    .filter((button: ButtonConfig) => !(button as any).overflow)
                    .map((button: ButtonConfig) => (
                      <button
                        key={button.code}
                        data-testid={`toolbar-btn-${button.code}`}
                        data-ab-testid={buttonTestId(
                          'detail',
                          schema?.modelCode || tableName,
                          button.code,
                        )}
                        onClick={() => handleAction(button, recordData)}
                        disabled={isButtonDisabled(button, evaluateVisibleWhen, actionLoading)}
                        className={`rounded-control px-3 py-1.5 text-sm font-medium whitespace-nowrap ${
                          button.primary
                            ? 'bg-accent hover:bg-accent-hover text-white'
                            : button.danger
                              ? 'bg-status-red hover:bg-status-red/90 text-white'
                              : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
                        } disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        {resolveButtonLabel(button)}
                      </button>
                    ))}
                  {effectiveHeaderToolbar.buttons.some(
                    (button: ButtonConfig) =>
                      (button as any).overflow &&
                      canRenderDetailToolbarButton(button, hasPermission) &&
                      evaluateVisibleWhen(button.visibleWhen),
                  ) && (
                    <details className="relative" data-testid="detail-toolbar-more">
                      <summary
                        aria-label={getLocalizedText(
                          { 'zh-CN': '更多操作', en: 'More actions' },
                          locale,
                          t,
                        )}
                        className="text-text-2 hover:bg-hover rounded-control flex h-8 w-8 cursor-pointer list-none items-center justify-center text-xl"
                      >
                        ⋮
                      </summary>
                      <div className="border-border bg-panel rounded-card absolute top-10 right-0 z-40 min-w-40 border p-1 shadow-lg">
                        {effectiveHeaderToolbar.buttons
                          .filter(
                            (button: ButtonConfig) =>
                              (button as any).overflow &&
                              canRenderDetailToolbarButton(button, hasPermission) &&
                              evaluateVisibleWhen(button.visibleWhen),
                          )
                          .map((button: ButtonConfig) => (
                            <button
                              key={button.code}
                              type="button"
                              data-testid={`toolbar-btn-${button.code}`}
                              disabled={isButtonDisabled(button, evaluateVisibleWhen, actionLoading)}
                              className={`hover:bg-hover block w-full rounded px-3 py-2 text-left text-xs ${button.danger ? 'text-status-red' : 'text-text-2'}`}
                              onClick={(event) => {
                                const menu = event.currentTarget.closest('details');
                                if (menu) menu.open = false;
                                void handleAction(button, recordData);
                              }}
                            >
                              {resolveButtonLabel(button)}
                            </button>
                          ))}
                      </div>
                    </details>
                  )}
                </>
              )}
            </div>
          </div>
        </div>

        <div ref={pdfExportTargetRef} data-testid="detail-pdf-target">
          {/* Print-only header — visible only during print */}
          <div className="print-header">{getLocalizedText(schema.title, locale, t)}</div>
          <div className="print-meta">
            {new Date().toLocaleDateString(locale, {
              year: 'numeric',
              month: 'long',
              day: 'numeric',
            })}
          </div>

          {actionError && (
            <div
              className="print-hide rounded-control bg-status-red-bg text-status-red border-status-red mx-6 mt-4 border px-4 py-3 text-sm"
              role="alert"
              data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'action-error')}
              data-print="hide"
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="font-medium">{actionFailedTitle}</div>
                  <div className="mt-1 break-words">{actionError}</div>
                </div>
                <button
                  type="button"
                  className="text-status-red hover:bg-status-red-bg shrink-0 rounded px-2 py-1 text-xs font-medium"
                  onClick={() => setError(null)}
                >
                  {closeLabel}
                </button>
              </div>
            </div>
          )}

          {/* AI Next Best Action suggestions (hidden in print) */}
          <div className="print-hide">
            {recordData?.pid && (
              <NbaSuggestionBar
                modelCode={schema?.modelCode || tableName}
                recordPid={recordData.pid}
                token={token || undefined}
              />
            )}
          </div>

          {workspace && schema.extension?.objectSummary && (
            <WorkspaceObjectSummary
              items={schema.extension.objectSummary}
              record={recordData || {}}
              locale={locale}
              timezone={timezone}
              t={t}
            />
          )}
          {/* Content: Tabs mode or Direct mode */}
          {tabs.length > 0 ? (
            <div>
              {persistentHeaderBlocks.length > 0 && (
                <div
                  className={workspace ? 'mb-5 space-y-3' : 'space-y-3 p-6 pb-0'}
                  data-testid="detail-persistent-header"
                >
                  {persistentHeaderBlocks.map(renderWorkspaceBlock)}
                </div>
              )}

              {/* Tab headers (hidden in print — only active tab content shows) */}
              <div
                className={
                  workspace
                    ? 'print-hide border-border mb-4 overflow-x-auto border-b'
                    : 'print-hide border-border overflow-x-auto border-b px-6'
                }
                data-print="hide"
              >
                <nav
                  className="-mb-px flex w-max min-w-full space-x-8 whitespace-nowrap"
                  role="tablist"
                  aria-label={getLocalizedText({ 'zh-CN': '页签', en: 'Tabs' }, locale, t)}
                >
                  {tabs.map((tab, index) => (
                    <button
                      key={tab.key || index}
                      role="tab"
                      aria-selected={activeTab === index}
                      data-testid={deriveTestId(
                        'detail',
                        schema?.modelCode || tableName,
                        'tab',
                        tab.key || String(index),
                      )}
                      onClick={() => handleTabChange(index)}
                      className={`border-b-2 px-1 py-3 text-sm font-medium ${
                        activeTab === index
                          ? 'border-accent text-accent'
                          : 'text-text-2 hover:border-border-strong hover:text-text-2 border-transparent'
                      }`}
                    >
                      {getLocalizedText(tab.label, locale, t)}
                      {workspace && (tab as any).count && runtime && (
                        <WorkspaceTabCount
                          config={(tab as any).count}
                          record={recordData}
                          runtime={runtime}
                        />
                      )}
                    </button>
                  ))}
                </nav>
              </div>

              {/* Tab content — mount only the active tab. Workbench pages may reuse
                the same row/test ids across tabs; hidden pre-rendered tabs create
                duplicate DOM and unnecessary dataSource requests. */}
              {activeDetailTab && (
                <div
                  key={activeDetailTab.tab.key || activeDetailTab.index}
                  className={workspace ? '' : 'p-5'}
                >
                  {workspace ? (
                    <div
                      className={`grid min-w-0 grid-cols-1 items-start gap-5 ${(activeDetailTab.tab.blocks || []).some((block) => asideBlockIds.has(block.id) && (!block.permissionCode || hasPermission(block.permissionCode))) ? 'xl:grid-cols-[minmax(0,1fr)_290px]' : ''}`}
                      data-testid="detail-workspace-layout"
                    >
                      <div className="min-w-0 space-y-4" data-testid="detail-workspace-main">
                        {(activeDetailTab.tab.blocks || [])
                          .filter((block) => !asideBlockIds.has(block.id))
                          .map(renderWorkspaceBlock)}
                      </div>
                      {(activeDetailTab.tab.blocks || []).some((block) =>
                        asideBlockIds.has(block.id),
                      ) && (
                        <aside className="min-w-0 space-y-4" data-testid="detail-workspace-aside">
                          {(Array.isArray(schema.extension?.detailAsideGroups)
                            ? schema.extension.detailAsideGroups
                            : (activeDetailTab.tab.blocks || [])
                                .filter((block) => asideBlockIds.has(block.id))
                                .map((block) => [block.id])
                          ).map((ids: string[], groupIndex: number) => {
                            const group = (activeDetailTab.tab.blocks || []).filter(
                              (block) =>
                                ids.includes(block.id) &&
                                (!block.permissionCode || hasPermission(block.permissionCode)),
                            );
                            return group.length ? (
                              <section
                                key={groupIndex}
                                className={
                                  group.length === 1 &&
                                  group[0].blockType === 'detail-section' &&
                                  (group[0] as any).extension?.displayVariant === 'object'
                                    ? ''
                                    : 'border-border bg-panel rounded-card border p-5'
                                }
                              >
                                {group.map(renderWorkspaceBlock)}
                              </section>
                            ) : null;
                          })}
                        </aside>
                      )}
                    </div>
                  ) : (
                    <LayoutRenderer
                      layout={(activeDetailTab.tab as any).layout}
                      blocks={activeDetailTab.tab.blocks || []}
                      renderBlock={(block: BlockConfig) => (
                        <DetailBlockRenderer
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
                      )}
                    />
                  )}
                </div>
              )}
              {topLevelDetailBlocks.length > 0 && (
                <div className="space-y-4 border-t p-5">
                  {topLevelDetailBlocks.map((block: BlockConfig, blockIndex: number) =>
                    DETAIL_SPECIALIZED_BLOCK_TYPES.has(block.blockType as string) ? (
                      <DetailBlockRenderer
                        key={block.id || `top-level-${blockIndex}`}
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
                    ) : runtime ? (
                      <BlockRenderer
                        key={block.id || `top-level-${blockIndex}`}
                        block={prepareDetailRuntimeBlock(block, recordData, recordPid!)}
                        runtime={runtime}
                        areaId="detail-tabs-body"
                      />
                    ) : null,
                  )}
                </div>
              )}
              {/* Product-contributed approval panel for records associated with a workflow. */}
              {recordData?.pid && shouldRenderInlineDetailApproval(schema) && (
                <InlineApprovalPanel recordPid={recordData.pid} />
              )}
            </div>
          ) : /* Direct mode: optional workspace layout is configured by DSL block ids. */
          asideBlockIds.size > 0 ? (
            <div className="space-y-5 p-5">
              {runtime &&
                headerMiscBlocks.map((block: BlockConfig) => (
                  <BlockRenderer
                    key={block.id}
                    block={prepareDetailRuntimeBlock(block, recordData, recordPid!)}
                    runtime={runtime}
                    areaId="detail-header"
                  />
                ))}
              <div
                className="grid min-w-0 grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,1fr)_300px]"
                data-testid="detail-workspace-layout"
              >
                <div className="min-w-0 space-y-5" data-testid="detail-workspace-main">
                  {workspaceContentBlocks
                    .filter((block: BlockConfig) => !asideBlockIds.has(block.id))
                    .map(renderWorkspaceBlock)}
                </div>
                <aside
                  className="border-border bg-panel rounded-card min-w-0 border p-4"
                  data-testid="detail-workspace-aside"
                >
                  {workspaceContentBlocks
                    .filter((block: BlockConfig) => asideBlockIds.has(block.id))
                    .map(renderWorkspaceBlock)}
                </aside>
              </div>
            </div>
          ) : (
            <div className="space-y-4 p-5">
              {/* Opt-in: misc blocks with detailPlacement === 'header' render at the
                top (before form-sections / sub-tables). Default placement is
                unchanged (rendered last), so existing pages are unaffected. */}
              {runtime &&
                directMiscBlocks
                  .filter(
                    (b: BlockConfig) =>
                      (b as { detailPlacement?: string }).detailPlacement === 'header',
                  )
                  .map((block: BlockConfig, blockIndex: number) => (
                    <BlockRenderer
                      key={block.id || `misc-header-${blockIndex}`}
                      block={prepareDetailRuntimeBlock(block, recordData, recordPid!)}
                      runtime={runtime}
                      areaId="detail-direct"
                    />
                  ))}
              {effectiveDirectFormBlocks.map((block: BlockConfig, blockIndex: number) => (
                <DetailBlockRenderer
                  key={block.id || `block-${blockIndex}`}
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
              ))}
              {directSubTableBlocks.map((block: BlockConfig, blockIndex: number) => (
                <DetailBlockRenderer
                  key={block.id || `sub-${blockIndex}`}
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
              ))}
              {directMonthlyGridBlocks.map((block: BlockConfig, blockIndex: number) => (
                <DetailBlockRenderer
                  key={block.id || `monthly-${blockIndex}`}
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
              ))}
              {directEmbeddedListBlocks.map((block: BlockConfig, blockIndex: number) => (
                <DetailBlockRenderer
                  key={block.id || `embedded-${blockIndex}`}
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
              ))}
              {directSpecializedBlocks.map((block: BlockConfig, blockIndex: number) => (
                <DetailBlockRenderer
                  key={block.id || `specialized-${blockIndex}`}
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
              ))}

              {/* G7 — unified fallback dispatch for blocks not handled by the
                hardcoded detail switch (chart / description / rich-text /
                divider / stat-card / etc.). Renders via BlockRenderer using
                the fallback renderer registry. Unknown blockTypes surface as
                a visible placeholder + console.warn (not silently dropped). */}
              {runtime &&
                directMiscBlocks
                  .filter(
                    (b: BlockConfig) =>
                      (b as { detailPlacement?: string }).detailPlacement !== 'header',
                  )
                  .map((block: BlockConfig, blockIndex: number) => (
                    <BlockRenderer
                      key={block.id || `misc-${blockIndex}`}
                      block={prepareDetailRuntimeBlock(block, recordData, recordPid!)}
                      runtime={runtime}
                      areaId="detail-direct"
                    />
                  ))}

              {/* Fallback: no structured blocks found, show all fields */}
              {effectiveDirectFormBlocks.length === 0 &&
                directSubTableBlocks.length === 0 &&
                directMonthlyGridBlocks.length === 0 &&
                directEmbeddedListBlocks.length === 0 &&
                directSpecializedBlocks.length === 0 &&
                directMiscBlocks.length === 0 &&
                tabs.length === 0 && (
                  <FallbackDetailView schema={schema} recordData={recordData} locale={locale} />
                )}

              {/* Product-contributed approval panel for records associated with a workflow. */}
              {recordData?.pid && shouldRenderInlineDetailApproval(schema) && (
                <InlineApprovalPanel recordPid={recordData.pid} />
              )}

              {/* Default back/edit buttons (hidden in print) */}
              {!effectiveHeaderToolbar && (
                <div
                  className="print-hide border-border flex justify-end space-x-3 border-t pt-6"
                  data-print="hide"
                >
                  <Link
                    to={`/p/${tableName}${location.search}`}
                    className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2 text-sm font-medium"
                  >
                    {t('action.back')}
                  </Link>
                  {showDefaultEditAction && (
                    <Link
                      to={`/p/${tableName}/edit/${recordPid}`}
                      className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white"
                    >
                      {t('action.update')}
                    </Link>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Record Share Dialog */}
      {shareDialogOpen && recordPid && (
        <RecordShareDialog
          open={shareDialogOpen}
          onClose={() => setShareDialogOpen(false)}
          resourceCode={schema?.modelCode || tableName}
          recordPid={recordPid}
          permissionMode={
            schema?.extension?.recordShare?.mode === 'collaborate-only'
              ? 'collaborate-only'
              : 'standard'
          }
          allowedRoleCodes={
            Array.isArray(schema?.extension?.recordShare?.allowedRoleCodes)
              ? schema.extension.recordShare.allowedRoleCodes
              : undefined
          }
        />
      )}

      <AsyncTaskModalHost />
      <FormDialog />
    </div>
  );
}
