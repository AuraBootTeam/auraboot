import React, { useState } from 'react';
import {
  getLocalizedText,
  type LocalizedText,
} from '~/framework/meta/runtime/expression/i18n-renderer';
import { Modal } from '~/ui/smart/ui/Modal';
import ASYNC_TASK_TEXT from './AsyncTask.i18n.json';

export function asyncTaskText(
  key: keyof typeof ASYNC_TASK_TEXT,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(ASYNC_TASK_TEXT[key], locale).replace(
    /\{(\w+)\}/g,
    (placeholder, name: string) =>
      params[name] === undefined ? placeholder : String(params[name]),
  );
}

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

function formatMetricValue(value: unknown, locale: string): string {
  if (typeof value === 'number') return value.toLocaleString();
  if (typeof value === 'boolean') return asyncTaskText(value ? 'yes' : 'no', locale);
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
  const taskTitle = presentationTitle || task.taskLabel || asyncTaskText('backgroundTask', locale);
  const completedMessage =
    getLocalizedText(presentation?.completedMessage, locale) ||
    asyncTaskText('completed', locale, { task: taskTitle });
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
        {asyncTaskText('close', locale)}
      </button>
    </div>
  ) : (
    <div className="flex justify-end">
      <button
        type="button"
        className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-subtle border px-4 py-2 text-sm font-medium"
        onClick={onBackground}
      >
        {asyncTaskText('background', locale)}
      </button>
    </div>
  );

  const handleCopyFailures = () => {
    const failures = task.resultData?.failures ?? [];
    const text = failures
      .map((f) => asyncTaskText('failureRow', locale, { row: f.row, reason: f.reason }))
      .join('\n');
    void navigator.clipboard?.writeText(text);
  };

  const failureDetails =
    isImportResultData(task.resultData) && task.resultData.failedRows > 0 ? (
      <div className="rounded-control bg-status-red-bg border border-red-200 p-3">
        <div className="mb-2 flex items-center justify-between">
          <button
            type="button"
            className="text-sm font-medium text-red-700 hover:underline"
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded
              ? asyncTaskText('collapseFailures', locale)
              : asyncTaskText('showFailures', locale, { count: task.resultData.failedRows })}
          </button>
          <button
            type="button"
            data-testid="copy-failures"
            className="border-status-red bg-panel rounded border px-2 py-1 text-xs text-red-700 hover:bg-red-100"
            onClick={handleCopyFailures}
          >
            {asyncTaskText('copy', locale)}
          </button>
        </div>
        {expanded ? (
          <ul className="max-h-48 space-y-1 overflow-y-auto text-xs text-red-800">
            {(task.resultData.failures ?? []).map((failure, index) => (
              <li key={`${failure.row}-${index}`}>
                {asyncTaskText('failureRow', locale, { row: failure.row, reason: failure.reason })}
              </li>
            ))}
          </ul>
        ) : (
          <ul className="space-y-1 text-xs text-red-800">
            {(task.resultData.failures ?? []).slice(0, 1).map((failure, index) => (
              <li key={`${failure.row}-${index}`}>
                {asyncTaskText('failureRow', locale, { row: failure.row, reason: failure.reason })}
              </li>
            ))}
          </ul>
        )}
      </div>
    ) : null;

  return (
    <Modal open title={taskTitle} footer={footer} onCancel={terminal ? onClose : onBackground}>
      {/* Running state: determinate progress bar + live counts */}
      {!terminal && (
        <div className="space-y-4">
          <div>
            <div className="text-text-2 mb-1 flex justify-between text-sm">
              <span>{asyncTaskText('running', locale, { task: taskTitle })}</span>
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
                {asyncTaskText('total', locale)}:{' '}
                <span className="font-medium">{fmt(live.total)}</span>
              </div>
              <div>
                {asyncTaskText('processed', locale)}:{' '}
                <span className="font-medium">{fmt(live.processed)}</span>
              </div>
              <div>
                {asyncTaskText('success', locale)}:{' '}
                <span className="text-status-green font-medium">{fmt(live.ok)}</span>
              </div>
              <div>
                {asyncTaskText('failed', locale)}:{' '}
                <span className="text-status-red font-medium">{fmt(live.failed)}</span>
              </div>
              <div>
                {asyncTaskText('skipped', locale)}:{' '}
                <span className="text-status-amber font-medium">{fmt(live.skipped)}</span>
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
            <>
              <div className="text-text-2 grid grid-cols-2 gap-2 text-sm">
                {presentationMetrics.map((metric) => (
                  <div key={metric.field}>
                    {metric.resolvedLabel}:{' '}
                    <span className={`font-medium ${metricToneClass(metric.tone)}`}>
                      {formatMetricValue(metric.value, locale)}
                    </span>
                  </div>
                ))}
              </div>
              {failureDetails}
            </>
          ) : isImportResultData(task.resultData) ? (
            task.resultData.totalRows === 0 ? (
              <div className="text-text-2 text-sm">{asyncTaskText('noRows', locale)}</div>
            ) : (
              <>
                <div className="text-text-2 grid grid-cols-2 gap-2 text-sm">
                  <div>
                    {asyncTaskText('totalRows', locale)}:{' '}
                    <span className="font-medium">{fmt(task.resultData.totalRows)}</span>
                  </div>
                  <div>
                    {asyncTaskText('success', locale)}:{' '}
                    <span className="text-status-green font-medium">
                      {fmt(task.resultData.importedRows)}
                    </span>
                  </div>
                  <div>
                    {asyncTaskText('skipped', locale)}:{' '}
                    <span className="text-status-amber font-medium">
                      {fmt(task.resultData.skippedRows)}
                    </span>
                  </div>
                  <div>
                    {asyncTaskText('failed', locale)}:{' '}
                    <span className="text-status-red font-medium">
                      {fmt(task.resultData.failedRows)}
                    </span>
                  </div>
                </div>
                {failureDetails}
              </>
            )
          ) : (
            <div className="text-text-2 text-sm">{asyncTaskText('successMessage', locale)}</div>
          )}
        </div>
      )}

      {/* Failed state: error message */}
      {task.status === 'failed' && (
        <div className="space-y-2" data-testid="async-task-modal-failed">
          <div className="text-base font-semibold text-red-700">
            {asyncTaskText('failureMessage', locale)}
          </div>
          <div
            className="rounded-control bg-status-red-bg border border-red-200 p-3 text-sm text-red-800"
            data-testid="async-task-modal-error"
          >
            {task.errorMessage || asyncTaskText('unknownError', locale)}
          </div>
        </div>
      )}

      {task.status === 'cancelled' && (
        <div className="space-y-2" data-testid="async-task-modal-cancelled">
          <div className="text-text text-base font-semibold">
            {asyncTaskText('cancelled', locale)}
          </div>
          <div className="text-text-2 text-sm">{asyncTaskText('cancelledMessage', locale)}</div>
        </div>
      )}
    </Modal>
  );
}

export default AsyncTaskProgressModal;
