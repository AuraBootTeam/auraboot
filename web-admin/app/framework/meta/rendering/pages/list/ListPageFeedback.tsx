import type { ReactNode } from 'react';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '~/ui/ui/dialog';
import type { ListPageModel } from './listPageModel';
import { SavedViewOverlayStatusBanner } from './SavedViewOverlayStatusBanner';
import { TenantMemberAccountImportDialog } from './TenantMemberAccountImportDialog';

/** list page feedback — preserves the original element and event-handler expressions. */
export function renderListPageFeedback(
  model: Pick<
    ListPageModel,
    | 'viewSavedHintOn'
    | 't'
    | 'hasPendingViewConfig'
    | 'currentView'
    | 'hasPendingPersonalViewConfig'
    | 'translateCommon'
    | 'pendingViewSummary'
    | 'handleSaveCurrentViewDraft'
    | 'handleSaveSharedDraft'
    | 'hasPendingSharedViewConfig'
    | 'canSaveSharedView'
    | 'savingViewDraft'
    | 'isCurrentViewLockedPreset'
    | 'handleSaveDraftAsPersonalView'
    | 'copyingViewDraft'
    | 'canCopyCurrentView'
    | 'handleDiscardViewDraft'
    | 'overlayMeta'
    | 'canRepairViewOverlay'
    | 'repairingViewOverlay'
    | 'handleRepairViewOverlay'
    | 'repairViewOverlayUnavailableReason'
    | 'workspace'
    | 'actionError'
    | 'modelCode'
    | 'setActionError'
    | 'canManageMemberAccounts'
    | 'memberImportDialogOpen'
    | 'token'
    | 'setMemberImportDialogOpen'
    | 'loadDataRef'
    | 'pagination'
    | 'filters'
    | 'canInviteMembers'
    | 'inviteDialogOpen'
    | 'setInviteDialogOpen'
    | 'inviteText'
    | 'inviteTriggerRef'
    | 'inviteReadLoading'
    | 'inviteReadError'
    | 'loadCurrentInviteCode'
    | 'inviteCodeData'
    | 'locale'
    | 'handleGenerateInviteCode'
    | 'inviteLoading'
    | 'handleRevokeInviteCode'
  >,
  pageHeader: ReactNode,
) {
  const {
    viewSavedHintOn,
    t,
    hasPendingViewConfig,
    currentView,
    hasPendingPersonalViewConfig,
    translateCommon,
    pendingViewSummary,
    handleSaveCurrentViewDraft,
    handleSaveSharedDraft,
    hasPendingSharedViewConfig,
    canSaveSharedView,
    savingViewDraft,
    isCurrentViewLockedPreset,
    handleSaveDraftAsPersonalView,
    copyingViewDraft,
    canCopyCurrentView,
    handleDiscardViewDraft,
    overlayMeta,
    canRepairViewOverlay,
    repairingViewOverlay,
    handleRepairViewOverlay,
    repairViewOverlayUnavailableReason,
    workspace,
    actionError,
    modelCode,
    setActionError,
    canManageMemberAccounts,
    memberImportDialogOpen,
    token,
    setMemberImportDialogOpen,
    loadDataRef,
    pagination,
    filters,
    canInviteMembers,
    inviteDialogOpen,
    setInviteDialogOpen,
    inviteText,
    inviteTriggerRef,
    inviteReadLoading,
    inviteReadError,
    loadCurrentInviteCode,
    inviteCodeData,
    locale,
    handleGenerateInviteCode,
    inviteLoading,
    handleRevokeInviteCode,
  } = model;
  return (
    <>
      {/* §3: quiet auto-save confirmation — appears briefly after a
              sort/filter/column/row-height change is persisted to the view. */}
      {viewSavedHintOn && (
        <div
          className="bg-accent-weak text-accent rounded-pill text-aux pointer-events-none absolute top-2 right-3 z-10 flex items-center gap-1.5 px-2.5 py-1 shadow-sm transition-opacity"
          role="status"
          data-testid="view-saved-hint"
        >
          <span className="bg-status-green rounded-pill h-1.5 w-1.5" aria-hidden="true" />
          {/* usePageRuntime's t() returns the key itself when missing, so a
                  plain `|| fallback` never fires — guard against the raw key leaking. */}
          {((s) => (s && s !== 'common.view_saved' ? s : '已保存到当前视图'))(
            t('common.view_saved'),
          )}
        </div>
      )}
      {hasPendingViewConfig && currentView && (
        <div
          className="m-3 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900"
          role="status"
          data-testid={
            hasPendingPersonalViewConfig ? 'personal-view-draft-banner' : 'shared-view-draft-banner'
          }
        >
          <span>
            {hasPendingPersonalViewConfig
              ? translateCommon('common.saved_view_personal_draft', '当前个人视图有本地变更')
              : translateCommon('common.saved_view_shared_draft', '共享视图有本地变更')}
            {pendingViewSummary.length > 0 && (
              <span className="ml-1 text-amber-700">{pendingViewSummary.join(', ')}</span>
            )}
          </span>
          <button
            type="button"
            className="rounded px-2 py-1 font-medium text-amber-900 hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-60"
            onClick={
              hasPendingPersonalViewConfig ? handleSaveCurrentViewDraft : handleSaveSharedDraft
            }
            disabled={(hasPendingSharedViewConfig && !canSaveSharedView) || savingViewDraft}
            title={
              hasPendingPersonalViewConfig
                ? translateCommon('common.saved_view_save_current', '保存当前视图')
                : canSaveSharedView
                  ? translateCommon(
                      'common.saved_view_save_shared_confirm_title',
                      '保存到共享视图？',
                    )
                  : translateCommon(
                      isCurrentViewLockedPreset
                        ? 'common.saved_view_locked_preset_reason'
                        : 'common.saved_view_save_shared_disabled_reason',
                      isCurrentViewLockedPreset
                        ? '插件预置视图不能直接保存，请先复制为个人视图。'
                        : '你可以调整当前视图，但暂不能保存给团队或全员。',
                    )
            }
            data-testid={
              hasPendingPersonalViewConfig
                ? 'personal-view-save-current'
                : canSaveSharedView
                  ? 'shared-view-save'
                  : 'shared-view-save-disabled'
            }
          >
            {savingViewDraft
              ? translateCommon('common.saving', '保存中...')
              : hasPendingPersonalViewConfig
                ? translateCommon('common.saved_view_save_current', '保存当前视图')
                : translateCommon('common.saved_view_save_shared', '保存到共享视图')}
          </button>
          {hasPendingSharedViewConfig && !canSaveSharedView && (
            <span className="text-amber-700">
              {translateCommon(
                isCurrentViewLockedPreset
                  ? 'common.saved_view_locked_preset_reason'
                  : 'common.saved_view_save_shared_disabled_reason',
                isCurrentViewLockedPreset
                  ? '插件预置视图不能直接保存，请先复制为个人视图。'
                  : '你可以调整当前视图，但暂不能保存给团队或全员。',
              )}
            </span>
          )}
          <button
            type="button"
            className="rounded px-2 py-1 font-medium text-blue-700 hover:bg-blue-50 disabled:opacity-60"
            onClick={handleSaveDraftAsPersonalView}
            disabled={copyingViewDraft || !canCopyCurrentView}
            title={
              canCopyCurrentView
                ? translateCommon('common.saved_view_save_as_personal', '另存为新视图')
                : translateCommon('common.saved_view_copy_disabled_reason', '当前视图不能复制')
            }
            data-testid="personal-view-save-as-new"
          >
            {copyingViewDraft
              ? translateCommon('common.saving', '保存中...')
              : translateCommon('common.saved_view_save_as_personal', '另存为新视图')}
          </button>
          <button
            type="button"
            className="rounded px-2 py-1 text-amber-800 hover:bg-amber-100"
            onClick={handleDiscardViewDraft}
            data-testid={
              hasPendingPersonalViewConfig
                ? 'personal-view-discard-draft'
                : 'shared-view-dismiss-draft'
            }
          >
            {translateCommon('common.saved_view_dismiss_draft', '放弃')}
          </button>
        </div>
      )}
      <SavedViewOverlayStatusBanner
        status={overlayMeta?.overlayStatus}
        reasonCodes={overlayMeta?.overlayReasonCodes}
        stalePaths={overlayMeta?.overlayStalePaths}
        canRepair={canRepairViewOverlay}
        repairing={repairingViewOverlay}
        onRepair={handleRepairViewOverlay}
        repairUnavailableReason={repairViewOverlayUnavailableReason}
        t={translateCommon}
      />
      {/* Page title, view selector, and action buttons */}

      {actionError && (
        <div
          className="print-hide rounded-control bg-status-red-bg text-status-red border-status-red mx-6 mt-4 border px-4 py-3 text-sm"
          role="alert"
          data-testid={deriveTestId('list', modelCode, 'action-error')}
          data-print="hide"
        >
          <div className="flex items-start justify-between gap-4">
            <div>
              <div className="font-medium">
                {translateCommon('common.actionFailed', 'Action failed')}
              </div>
              <div className="mt-1 break-words">{actionError}</div>
            </div>
            <button
              type="button"
              className="text-status-red hover:bg-status-red-bg shrink-0 rounded px-2 py-1 text-xs font-medium"
              onClick={() => setActionError(null)}
            >
              {translateCommon('common.close', 'Close')}
            </button>
          </div>
        </div>
      )}

      {canManageMemberAccounts && (
        <TenantMemberAccountImportDialog
          open={memberImportDialogOpen}
          token={token}
          onClose={() => setMemberImportDialogOpen(false)}
          onImported={async () => {
            await loadDataRef.current?.({
              page: pagination.current - 1,
              size: pagination.pageSize,
              filters,
            });
          }}
        />
      )}

      {canInviteMembers && (
        <Dialog open={inviteDialogOpen} onOpenChange={setInviteDialogOpen}>
          <DialogContent
            data-testid="invite-dialog"
            closeLabel={inviteText('close')}
            className="max-h-[calc(100dvh_-_2rem)] w-[calc(100%_-_2rem)] max-w-md overflow-y-auto"
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              if (inviteTriggerRef.current?.isConnected) inviteTriggerRef.current.focus();
            }}
          >
            <DialogTitle>{inviteText('title')}</DialogTitle>
            <DialogDescription>{inviteText('policy')}</DialogDescription>
            <div className="space-y-4">
              {inviteReadLoading ? (
                <div role="status" data-testid="invite-loading" className="text-text-2 text-sm">
                  {inviteText('loading')}
                </div>
              ) : inviteReadError ? (
                <div className="space-y-3">
                  <p role="alert" data-testid="invite-read-error" className="text-sm text-red-600">
                    {inviteReadError}
                  </p>
                  <button
                    type="button"
                    onClick={() => void loadCurrentInviteCode()}
                    className="rounded-control bg-accent hover:bg-accent-hover px-3 py-2 text-sm font-medium text-white"
                  >
                    {inviteText('retry')}
                  </button>
                </div>
              ) : inviteCodeData?.code ? (
                <>
                  <div className="rounded-control bg-subtle p-3">
                    <div className="text-text-2 mb-1 text-xs font-medium">
                      {inviteText('current')}
                    </div>
                    <div
                      data-testid="invite-code-value"
                      className="text-text font-mono text-lg tracking-wider"
                    >
                      {inviteCodeData.code}
                    </div>
                    {inviteCodeData.expiredAt && (
                      <div className="text-text-2 mt-1 text-xs">
                        {inviteText('expires')}{' '}
                        {new Date(inviteCodeData.expiredAt).toLocaleString(locale)}
                      </div>
                    )}
                  </div>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={handleGenerateInviteCode}
                      disabled={inviteLoading}
                      className="rounded-control bg-accent hover:bg-accent-hover flex-1 px-3 py-2 text-sm font-medium text-white disabled:opacity-60"
                    >
                      {inviteText('newCode')}
                    </button>
                    <button
                      type="button"
                      onClick={handleRevokeInviteCode}
                      disabled={inviteLoading}
                      className="rounded-control flex-1 bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
                    >
                      {inviteText('revoke')}
                    </button>
                  </div>
                </>
              ) : (
                <div className="space-y-3">
                  <div className="rounded-control bg-status-amber-bg p-3 text-sm text-amber-800">
                    {inviteText('empty')}
                  </div>
                  <button
                    type="button"
                    onClick={handleGenerateInviteCode}
                    disabled={inviteLoading}
                    className="rounded-control bg-accent hover:bg-accent-hover w-full px-3 py-2 text-sm font-medium text-white disabled:opacity-60"
                  >
                    {inviteText('generate')}
                  </button>
                </div>
              )}
            </div>
          </DialogContent>
        </Dialog>
      )}
    </>
  );
}
