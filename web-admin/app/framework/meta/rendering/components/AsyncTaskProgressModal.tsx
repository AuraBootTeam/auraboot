import React, { useState } from 'react';
import { Modal } from '~/ui/smart/ui/Modal';

/**
 * Live progress payload carried by an async task's `progressMessage` field.
 * Matches the backend `progressJson` shape emitted by the import service.
 */
export interface ProgressMessage {
  processed: number;
  total: number;
  ok: number;
  failed: number;
  skipped: number;
}

/** Per-row failure captured during import. */
export interface ImportFailure {
  row: number;
  reason: string;
}

/** Terminal result payload carried by an async task's `resultData` field. */
export interface ImportResultData {
  totalRows: number;
  importedRows: number;
  skippedRows: number;
  failedRows: number;
  failures?: ImportFailure[];
  deletedPreviousMaterials?: number;
}

export type AsyncTaskStatus = 'running' | 'pending' | 'completed' | 'failed' | string;

export interface AsyncTask {
  status: AsyncTaskStatus;
  progress?: number;
  progressMessage?: string;
  resultData?: ImportResultData;
  errorMessage?: string;
}

export interface AsyncTaskProgressModalProps {
  task: AsyncTask;
  onClose: () => void;
  onBackground: () => void;
}

/**
 * Parse an async task's `progressMessage` JSON into live counts.
 * Returns the object only when it is valid JSON carrying a numeric `total`,
 * otherwise null (e.g. for plain status strings like "Starting").
 */
export function parseProgressMessage(msg: string | undefined | null): ProgressMessage | null {
  if (!msg || typeof msg !== 'string') return null;
  try {
    const parsed = JSON.parse(msg);
    if (parsed && typeof parsed === 'object' && typeof parsed.total === 'number') {
      return parsed as ProgressMessage;
    }
    return null;
  } catch {
    return null;
  }
}

function isTerminal(status: AsyncTaskStatus): boolean {
  return status === 'completed' || status === 'failed';
}

function fmt(n: number | undefined): string {
  return (n ?? 0).toLocaleString();
}

export function AsyncTaskProgressModal({
  task,
  onClose,
  onBackground,
}: AsyncTaskProgressModalProps) {
  const [expanded, setExpanded] = useState(false);
  const terminal = isTerminal(task.status);
  const progress = Math.max(0, Math.min(100, task.progress ?? 0));
  const live = parseProgressMessage(task.progressMessage);

  const footer = terminal ? (
    <div className="flex justify-end">
      <button
        type="button"
        className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-sm font-medium text-white"
        onClick={onClose}
      >
        关闭
      </button>
    </div>
  ) : (
    <div className="flex justify-end">
      <button
        type="button"
        className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-subtle border px-4 py-2 text-sm font-medium"
        onClick={onBackground}
      >
        后台运行
      </button>
    </div>
  );

  const handleCopyFailures = () => {
    const failures = task.resultData?.failures ?? [];
    const text = failures.map((f) => `第${f.row}行 — ${f.reason}`).join('\n');
    void navigator.clipboard?.writeText(text);
  };

  return (
    <Modal open title="数据导入" footer={footer} onCancel={terminal ? onClose : onBackground}>
      {/* Running state: determinate progress bar + live counts */}
      {!terminal && (
        <div className="space-y-4">
          <div>
            <div className="text-text-2 mb-1 flex justify-between text-sm">
              <span>导入进行中…</span>
              <span>{progress}%</span>
            </div>
            <div className="rounded-pill h-2 w-full overflow-hidden bg-gray-200">
              <div
                role="progressbar"
                aria-valuenow={progress}
                aria-valuemin={0}
                aria-valuemax={100}
                className="rounded-pill bg-accent h-2 transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>
          {live && (
            <div className="text-text-2 grid grid-cols-2 gap-2 text-sm">
              <div>
                总计 / Total: <span className="font-medium">{fmt(live.total)}</span>
              </div>
              <div>
                已处理: <span className="font-medium">{fmt(live.processed)}</span>
              </div>
              <div>
                成功: <span className="text-status-green font-medium">{fmt(live.ok)}</span>
              </div>
              <div>
                失败: <span className="text-status-red font-medium">{fmt(live.failed)}</span>
              </div>
              <div>
                跳过: <span className="text-status-amber font-medium">{fmt(live.skipped)}</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Completed state: summary + optional failures list */}
      {task.status === 'completed' && task.resultData && (
        <div className="space-y-3">
          <div className="text-text text-base font-semibold">导入完成 / Completed</div>
          {task.resultData.totalRows === 0 ? (
            <div className="text-text-2 text-sm">未导入任何数据 / No rows</div>
          ) : (
            <>
              <div className="text-text-2 grid grid-cols-2 gap-2 text-sm">
                <div>
                  总行数: <span className="font-medium">{fmt(task.resultData.totalRows)}</span>
                </div>
                <div>
                  成功:{' '}
                  <span className="text-status-green font-medium">
                    {fmt(task.resultData.importedRows)}
                  </span>
                </div>
                <div>
                  跳过:{' '}
                  <span className="text-status-amber font-medium">
                    {fmt(task.resultData.skippedRows)}
                  </span>
                </div>
                <div>
                  失败:{' '}
                  <span className="text-status-red font-medium">
                    {fmt(task.resultData.failedRows)}
                  </span>
                </div>
              </div>
              {task.resultData.failedRows > 0 && (
                <div className="rounded-control bg-status-red-bg border border-red-200 p-3">
                  <div className="mb-2 flex items-center justify-between">
                    <button
                      type="button"
                      className="text-sm font-medium text-red-700 hover:underline"
                      onClick={() => setExpanded((v) => !v)}
                    >
                      {expanded ? '收起失败明细' : `查看失败明细 (${task.resultData.failedRows})`}
                    </button>
                    <button
                      type="button"
                      data-testid="copy-failures"
                      className="border-status-red bg-panel rounded border px-2 py-1 text-xs text-red-700 hover:bg-red-100"
                      onClick={handleCopyFailures}
                    >
                      复制
                    </button>
                  </div>
                  {expanded && (
                    <ul className="max-h-48 space-y-1 overflow-y-auto text-xs text-red-800">
                      {(task.resultData.failures ?? []).map((f, i) => (
                        <li key={`${f.row}-${i}`}>
                          第{f.row}行 — {f.reason}
                        </li>
                      ))}
                    </ul>
                  )}
                  {!expanded && (
                    <ul className="space-y-1 text-xs text-red-800">
                      {(task.resultData.failures ?? []).slice(0, 1).map((f, i) => (
                        <li key={`${f.row}-${i}`}>
                          第{f.row}行 — {f.reason}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Failed state: error message */}
      {task.status === 'failed' && (
        <div className="space-y-2" data-testid="async-task-modal-failed">
          <div className="text-base font-semibold text-red-700">导入失败 / Failed</div>
          <div
            className="rounded-control bg-status-red-bg border border-red-200 p-3 text-sm text-red-800"
            data-testid="async-task-modal-error"
          >
            {task.errorMessage || '未知错误 / Unknown error'}
          </div>
        </div>
      )}
    </Modal>
  );
}

export default AsyncTaskProgressModal;
