import React, { useState } from 'react';
import {
  getLocalizedText,
  type LocalizedText,
} from '~/framework/meta/runtime/expression/i18n-renderer';
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

export interface AsyncTaskPresentationMetric {
  field: string;
  label: string | LocalizedText;
  tone?: 'default' | 'success' | 'warning' | 'danger';
}

/**
 * Optional, declarative presentation supplied by an async command's
 * `handlerParams.taskPresentation`. It keeps domain labels and result-field
 * selection in DSL while this platform component owns status UX.
 */
export interface AsyncTaskPresentation {
  title?: string | LocalizedText;
  completedMessage?: string | LocalizedText;
  metrics?: AsyncTaskPresentationMetric[];
}

export type AsyncTaskResultData = Record<string, unknown> & Partial<ImportResultData>;

export type AsyncTaskStatus = 'running' | 'pending' | 'completed' | 'failed' | string;

export interface AsyncTask {
  status: AsyncTaskStatus;
  taskCode?: string;
  taskType?: string;
  taskName?: string;
  taskLabel?: string;
  locale?: string;
  progress?: number;
  progressMessage?: string;
  resultData?: AsyncTaskResultData;
  errorMessage?: string;
  presentation?: AsyncTaskPresentation;
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
  return status === 'completed' || status === 'failed' || status === 'cancelled';
}

function fmt(n: number | undefined): string {
  return (n ?? 0).toLocaleString();
}

function isImportResultData(
  data: AsyncTaskResultData | undefined,
): data is AsyncTaskResultData & ImportResultData {
  return Boolean(
    data &&
    typeof data.totalRows === 'number' &&
    typeof data.importedRows === 'number' &&
    typeof data.skippedRows === 'number' &&
    typeof data.failedRows === 'number',
  );
}

function formatMetricValue(value: unknown): string {
  if (typeof value === 'number') return value.toLocaleString();
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return value == null || value === '' ? '-' : String(value);
}

function metricToneClass(tone: AsyncTaskPresentationMetric['tone']): string {
  switch (tone) {
    case 'success':
      return 'text-status-green';
    case 'warning':
      return 'text-status-amber';
    case 'danger':
      return 'text-status-red';
    default:
      return 'text-text';
  }
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
  const locale = task.locale || 'zh-CN';
  const presentation = task.presentation;
  const presentationTitle = getLocalizedText(presentation?.title, locale);
  const taskTitle = presentationTitle || task.taskLabel || '后台任务';
  const completedMessage =
    getLocalizedText(presentation?.completedMessage, locale) || `${taskTitle}已完成`;
  const presentationMetrics = (presentation?.metrics ?? [])
    .map((metric) => ({
      ...metric,
      resolvedLabel: getLocalizedText(metric.label, locale),
      value: task.resultData?.[metric.field],
    }))
    .filter((metric) => metric.resolvedLabel && metric.value !== undefined);

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
    <Modal open title={taskTitle} footer={footer} onCancel={terminal ? onClose : onBackground}>
      {/* Running state: determinate progress bar + live counts */}
      {!terminal && (
        <div className="space-y-4">
          <div>
            <div className="text-text-2 mb-1 flex justify-between text-sm">
              <span>{task.taskLabel ? `${task.taskLabel}进行中…` : '任务执行中…'}</span>
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
      {task.status === 'completed' && (
        <div className="space-y-3">
          <div className="text-text text-base font-semibold">{completedMessage}</div>
          {presentationMetrics.length > 0 ? (
            <div className="text-text-2 grid grid-cols-2 gap-2 text-sm">
              {presentationMetrics.map((metric) => (
                <div key={metric.field}>
                  {metric.resolvedLabel}:{' '}
                  <span className={`font-medium ${metricToneClass(metric.tone)}`}>
                    {formatMetricValue(metric.value)}
                  </span>
                </div>
              ))}
            </div>
          ) : isImportResultData(task.resultData) ? (
            task.resultData.totalRows === 0 ? (
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
            )
          ) : (
            <div className="text-text-2 text-sm">任务已成功完成。</div>
          )}
        </div>
      )}

      {/* Failed state: error message */}
      {task.status === 'failed' && (
        <div className="space-y-2" data-testid="async-task-modal-failed">
          <div className="text-base font-semibold text-red-700">任务执行失败 / Failed</div>
          <div
            className="rounded-control bg-status-red-bg border border-red-200 p-3 text-sm text-red-800"
            data-testid="async-task-modal-error"
          >
            {task.errorMessage || '未知错误 / Unknown error'}
          </div>
        </div>
      )}

      {task.status === 'cancelled' && (
        <div className="space-y-2" data-testid="async-task-modal-cancelled">
          <div className="text-text text-base font-semibold">任务已取消 / Cancelled</div>
          <div className="text-text-2 text-sm">任务未继续执行，未完成的步骤不会再处理。</div>
        </div>
      )}
    </Modal>
  );
}

export default AsyncTaskProgressModal;
