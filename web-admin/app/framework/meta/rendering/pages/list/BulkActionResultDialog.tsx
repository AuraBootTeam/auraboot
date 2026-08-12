import React from 'react';

export interface BulkActionFailure {
  recordPid: string;
  recordLabel: string;
  reason: string;
}

export interface BulkActionResult {
  actionLabel: string;
  successCount: number;
  failures: BulkActionFailure[];
}

export interface BulkActionResultDialogProps {
  result: BulkActionResult | null;
  onClose: () => void;
  locale?: string;
  t: (key: string, params?: Record<string, unknown>, fallback?: string) => string;
}

/** Actionable, record-level evidence for mixed-result business bulk actions. */
export function BulkActionResultDialog({
  result,
  onClose,
  locale = 'zh-CN',
  t,
}: BulkActionResultDialogProps) {
  if (!result) return null;
  const failureCount = result.failures.length;
  const isZh = locale.toLowerCase().startsWith('zh');
  const tr = (key: string, params: Record<string, unknown> | undefined, zh: string, en: string) => {
    const translated = t(key, params, isZh ? zh : en);
    return translated && translated !== key ? translated : isZh ? zh : en;
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="bulk-action-result-title"
      data-testid="bulk-action-result-dialog"
      className="fixed inset-0 z-[1200] flex items-center justify-center bg-black/45 p-4"
    >
      <div className="rounded-card border-border bg-panel w-full max-w-xl border shadow-xl">
        <div className="border-border flex items-start justify-between gap-4 border-b px-5 py-4">
          <div>
            <h2 id="bulk-action-result-title" className="text-text text-base font-semibold">
              {tr('list.bulkAction.resultTitle', undefined, '批量操作结果', 'Bulk action result')}
            </h2>
            <p className="text-text-2 mt-1 text-sm">{result.actionLabel}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-text-3 hover:bg-hover hover:text-text rounded-control px-2 py-1 text-sm"
            aria-label={tr('common.close', undefined, '关闭', 'Close')}
          >
            ×
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          <div className="flex flex-wrap gap-2 text-sm">
            <span className="bg-status-green-bg text-status-green rounded-full px-2.5 py-1 font-medium">
              {tr(
                'list.bulkAction.successCount',
                { count: result.successCount },
                `成功 ${result.successCount} 条`,
                `${result.successCount} succeeded`,
              )}
            </span>
            <span className="bg-status-red-bg text-status-red rounded-full px-2.5 py-1 font-medium">
              {tr(
                'list.bulkAction.failureCount',
                { count: failureCount },
                `失败 ${failureCount} 条`,
                `${failureCount} failed`,
              )}
            </span>
          </div>

          {failureCount > 0 && (
            <div>
              <p className="text-text mb-2 text-sm font-medium">
                {tr('list.bulkAction.failureDetails', undefined, '失败明细', 'Failure details')}
              </p>
              <div className="border-border max-h-64 overflow-y-auto rounded-md border">
                <table className="w-full table-fixed text-left text-sm">
                  <thead className="bg-subtle text-text-2 sticky top-0">
                    <tr>
                      <th className="w-2/5 px-3 py-2 font-medium">
                        {tr('list.bulkAction.record', undefined, '记录', 'Record')}
                      </th>
                      <th className="px-3 py-2 font-medium">
                        {tr('list.bulkAction.reason', undefined, '原因', 'Reason')}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-border divide-y">
                    {result.failures.map((failure) => (
                      <tr key={`${failure.recordPid}:${failure.reason}`}>
                        <td className="text-text truncate px-3 py-2" title={failure.recordLabel}>
                          {failure.recordLabel}
                        </td>
                        <td className="text-status-red px-3 py-2 break-words">
                          {failure.reason === 'Bad parameter'
                            ? tr(
                                'list.bulkAction.invalidState',
                                undefined,
                                '当前记录状态不满足操作条件',
                                'The current record state does not allow this action',
                              )
                            : failure.reason}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>

        <div className="border-border flex justify-end border-t px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="bg-accent hover:bg-accent-hover rounded-control px-4 py-2 text-sm font-medium text-white"
          >
            {tr('common.close', undefined, '关闭', 'Close')}
          </button>
        </div>
      </div>
    </div>
  );
}
