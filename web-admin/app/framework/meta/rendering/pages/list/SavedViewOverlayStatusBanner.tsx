import React from 'react';

export type SavedViewOverlayStatus = 'CURRENT' | 'REBASED' | 'STALE' | 'UNTRACKED';

export interface SavedViewOverlayStatusBannerProps {
  status?: SavedViewOverlayStatus;
  reasonCodes?: string[];
  stalePaths?: string[];
  canRepair: boolean;
  repairing?: boolean;
  onRepair: () => void | Promise<void>;
  repairUnavailableReason?: string;
  t: (key: string, fallback: string) => string;
}

/**
 * Explains release-to-overlay compatibility without blocking the business page.
 * CURRENT/legacy overlays stay quiet; only actionable degradation or a successful
 * identity-based rebase is surfaced.
 */
export function SavedViewOverlayStatusBanner({
  status,
  reasonCodes = [],
  stalePaths = [],
  canRepair,
  repairing = false,
  onRepair,
  repairUnavailableReason,
  t,
}: SavedViewOverlayStatusBannerProps) {
  if (status !== 'STALE' && status !== 'REBASED') {
    return null;
  }

  if (status === 'REBASED') {
    return (
      <div
        className="border-accent/30 bg-accent-weak text-accent flex flex-wrap items-center gap-2 border-b px-5 py-2 text-xs"
        role="status"
        data-testid="saved-view-overlay-rebased"
      >
        <span className="font-medium">
          {t('common.saved_view_overlay_rebased_title', '个人视图已适配新版页面')}
        </span>
        <span>
          {t(
            'common.saved_view_overlay_rebased_description',
            '字段、动作身份保持兼容，你的设置已自动重放。',
          )}
        </span>
      </div>
    );
  }

  const affectedCount = stalePaths.length;
  const affectedLabel =
    affectedCount > 0
      ? String(affectedCount)
      : t('common.saved_view_overlay_affected_some', '部分');
  const mandatoryRestored = reasonCodes.includes('MANDATORY_ELEMENT_RESTORED');

  return (
    <div
      className="border-status-amber bg-status-amber-bg text-status-amber flex flex-wrap items-center justify-between gap-3 border-b px-5 py-3 text-sm"
      role="alert"
      data-testid="saved-view-overlay-stale"
    >
      <div className="min-w-0">
        <p className="font-medium">
          {t('common.saved_view_overlay_stale_title', '个人视图已安全降级')}
        </p>
        <p className="mt-0.5 text-xs">
          {t('common.saved_view_overlay_stale_description_prefix', '页面结构已更新，')}
          {affectedLabel}
          {t(
            'common.saved_view_overlay_stale_description_suffix',
            '项失效设置已忽略；业务页面仍可正常使用。',
          )}
          {mandatoryRestored && (
            <span className="ml-1">
              {t('common.saved_view_overlay_mandatory_restored', '必显项已自动恢复。')}
            </span>
          )}
        </p>
      </div>
      {canRepair ? (
        <button
          type="button"
          className="border-status-amber bg-panel focus-visible:shadow-focus rounded-control shrink-0 border px-3 py-1.5 text-xs font-medium hover:brightness-95 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          disabled={repairing}
          onClick={() => void onRepair()}
          data-testid="saved-view-overlay-repair"
        >
          {repairing
            ? t('common.saved_view_overlay_repairing', '修复中…')
            : t('common.saved_view_overlay_repair', '清理失效设置')}
        </button>
      ) : (
        <span className="shrink-0 text-xs">
          {repairUnavailableReason ??
            t('common.saved_view_overlay_read_only', '当前视图只读，请联系视图管理员修复。')}
        </span>
      )}
    </div>
  );
}
