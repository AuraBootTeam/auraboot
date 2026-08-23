import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import {
  resolveImportFieldLabel,
  resolveImportExecutionMessage,
  resolveImportMessageFieldCodes,
  resolveImportReferenceMessage,
} from './importCapability';

export type ImportMode = 'insert' | 'update';

export interface ImportConfiguration {
  enabled?: boolean;
  permissionCode?: string;
  modes?: ImportMode[];
  updateKeys?: string[];
}

export interface ImportModalProps {
  open: boolean;
  onClose: () => void;
  modelCode: string;
  config?: ImportConfiguration;
  onImportComplete?: (result: ImportResultData) => void;
}

export interface ImportResultData {
  success: boolean;
  imported: number;
  failed: number;
  total: number;
  created: number;
  updated: number;
  errors: ImportRowError[];
  taskId?: string | null;
  errorReportUrl?: string | null;
  errorReportFailed?: boolean;
  errorReportExpired?: boolean;
}

interface ImportRowError {
  rowNumber: number;
  fieldCode?: string | null;
  message: string;
  value?: string | null;
}

interface ValidationReport {
  totalRows: number;
  validRows: number;
  valid: boolean;
  errors: ImportRowError[];
  warnings: ImportRowError[];
  taskId?: string | null;
  errorReportUrl?: string | null;
  errorReportFailed?: boolean;
  errorReportExpired?: boolean;
}

interface ApiEnvelope<T> {
  code: string;
  message?: string;
  data?: T;
  context?: unknown;
}

interface BackendImportResult {
  totalRows: number;
  successCount: number;
  errorCount: number;
  createdCount: number;
  updatedCount: number;
  errors?: ImportRowError[];
  hasErrors: boolean;
  taskId?: string | null;
  errorReportUrl?: string | null;
  errorReportFailed?: boolean;
  errorReportExpired?: boolean;
}

interface AsyncImportStatus {
  taskId: string;
  status: string;
  totalRows: number;
  processedRows: number;
  result?: BackendImportResult | null;
}

interface ImportFieldMeta {
  code?: string;
  displayName?: string;
  extension?: { displayName?: string };
}

type ImportStep = 'upload' | 'preview' | 'validating' | 'importing' | 'result';
const MAX_FILE_BYTES = 10 * 1024 * 1024;

function apiError<T>(response: Response, body?: ApiEnvelope<T>): Error {
  return new Error(body?.message || `Request failed (${response.status})`);
}

export const ImportModal: React.FC<ImportModalProps> = ({
  open,
  onClose,
  modelCode,
  config,
  onImportComplete,
}) => {
  const { t, locale } = useI18n();
  const modes = useMemo<ImportMode[]>(
    () => (config?.modes?.length ? config.modes : ['insert']),
    [config?.modes],
  );
  const [mode, setMode] = useState<ImportMode>(modes[0]);
  const [matchKey, setMatchKey] = useState(config?.updateKeys?.[0] || '');
  const [step, setStep] = useState<ImportStep>('upload');
  const [file, setFile] = useState<File | null>(null);
  const [previewData, setPreviewData] = useState<Record<string, unknown>[]>([]);
  const [previewColumns, setPreviewColumns] = useState<string[]>([]);
  const [fieldLabels, setFieldLabels] = useState<Record<string, string>>({});
  const [validation, setValidation] = useState<ValidationReport | null>(null);
  const [importResult, setImportResult] = useState<ImportResultData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [taskNotice, setTaskNotice] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [downloadingTemplate, setDownloadingTemplate] = useState(false);
  const [downloadingErrorReport, setDownloadingErrorReport] = useState(false);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isBusy =
    step === 'validating' || step === 'importing' || downloadingTemplate || downloadingErrorReport;

  const resetState = useCallback(() => {
    if (fileInputRef.current) fileInputRef.current.value = '';
    setStep('upload');
    setFile(null);
    setPreviewData([]);
    setPreviewColumns([]);
    setValidation(null);
    setImportResult(null);
    setError(null);
    setTaskNotice(null);
    setActiveTaskId(null);
    setRetrying(false);
    setDragOver(false);
    setMode(modes[0]);
    setMatchKey(config?.updateKeys?.[0] || '');
  }, [config?.updateKeys, modes]);

  const handleClose = useCallback(() => {
    if (isBusy) return;
    if (importResult) onImportComplete?.(importResult);
    resetState();
    onClose();
  }, [importResult, isBusy, onClose, onImportComplete, resetState]);

  useEffect(() => {
    if (!open) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') handleClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleClose, open]);

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    void fetch(`/api/dynamic/${encodeURIComponent(modelCode)}/field-meta`)
      .then(async (response) => {
        const body = (await response.json().catch(() => undefined)) as
          | ApiEnvelope<ImportFieldMeta[]>
          | undefined;
        if (!response.ok || !body || !ResultHelper.isSuccess(body) || !Array.isArray(body.data)) {
          return;
        }
        const labels: Record<string, string> = {};
        for (const field of body.data) {
          const code = field.code?.trim();
          const displayName = (field.displayName || field.extension?.displayName)?.trim();
          if (code && displayName && displayName !== code) labels[code] = displayName;
        }
        if (!cancelled) setFieldLabels(labels);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [modelCode, open]);

  const fieldLabel = useCallback(
    (field: string) => resolveImportFieldLabel(field, fieldLabels),
    [fieldLabels],
  );

  const requestParams = useCallback(
    (nextMode: ImportMode, nextMatchKey: string) => {
      const params = new URLSearchParams({ mode: nextMode, locale });
      if (nextMode === 'update' && nextMatchKey) params.set('matchKey', nextMatchKey);
      return params;
    },
    [locale],
  );

  const downloadTemplate = useCallback(async () => {
    setDownloadingTemplate(true);
    setError(null);
    try {
      const response = await fetch(`/api/meta/excel/template/${modelCode}?mode=${mode}`);
      if (!response.ok) {
        const body = (await response.json().catch(() => undefined)) as
          | ApiEnvelope<unknown>
          | undefined;
        throw apiError(response, body);
      }
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${modelCode}-${mode}-import-template.xlsx`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      // The download event fires before Chromium has necessarily consumed the blob.
      // Revoking on the next tick leaves a download stuck forever with no bytes.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : t('import.error.template', undefined, '模板下载失败，请重试。'),
      );
    } finally {
      setDownloadingTemplate(false);
    }
  }, [mode, modelCode, t]);

  const downloadErrorReport = useCallback(
    async (url: string) => {
      setDownloadingErrorReport(true);
      setError(null);
      try {
        const response = await fetch(url);
        if (!response.ok) {
          const body = (await response.json().catch(() => undefined)) as
            | ApiEnvelope<unknown>
            | undefined;
          throw apiError(response, body);
        }
        const blobUrl = URL.createObjectURL(await response.blob());
        const anchor = document.createElement('a');
        anchor.href = blobUrl;
        anchor.download = `${modelCode}-${mode}-import-errors.xlsx`;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000);
      } catch (cause) {
        setError(
          cause instanceof Error
            ? cause.message
            : t('import.error.error_report', undefined, '修正工作簿下载失败，请重试。'),
        );
      } finally {
        setDownloadingErrorReport(false);
      }
    },
    [mode, modelCode, t],
  );

  const chooseCorrectionFile = useCallback(() => {
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
      fileInputRef.current.click();
    }
  }, []);

  const validateFile = useCallback(
    async (selectedFile: File, nextMode: ImportMode, nextMatchKey: string) => {
      setStep('validating');
      const formData = new FormData();
      formData.append('file', selectedFile);
      const response = await fetch(
        `/api/meta/excel/validate/${modelCode}?${requestParams(nextMode, nextMatchKey)}`,
        { method: 'post', body: formData },
      );
      const body = (await response.json().catch(() => undefined)) as
        | ApiEnvelope<ValidationReport>
        | undefined;
      if (!response.ok || !body || !ResultHelper.isSuccess(body) || !body.data) {
        throw apiError(response, body);
      }
      setValidation(body.data);
      setStep('preview');
    },
    [modelCode, requestParams],
  );

  const parseAndValidate = useCallback(
    async (selectedFile: File, nextMode = mode, nextMatchKey = matchKey) => {
      setError(null);
      setValidation(null);
      if (!selectedFile.name.toLowerCase().endsWith('.xlsx')) {
        setError(t('import.error.xlsx_only', undefined, 'Only Excel .xlsx files are supported.'));
        return;
      }
      if (selectedFile.size > MAX_FILE_BYTES) {
        setError(t('import.error.too_large', undefined, 'The file must be 10 MB or smaller.'));
        return;
      }
      if (nextMode === 'update' && !nextMatchKey) {
        setError(t('import.error.match_key', undefined, 'Choose a match field for update mode.'));
        return;
      }

      try {
        const XLSX = await import('xlsx');
        const workbook = XLSX.read(await selectedFile.arrayBuffer(), { type: 'array' });
        const firstSheet = workbook.Sheets[workbook.SheetNames[0]];
        const rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(firstSheet, { defval: '' });
        const preview = rows.slice(0, 10);
        setFile(selectedFile);
        setPreviewData(preview);
        setPreviewColumns(preview.length ? Object.keys(preview[0]) : []);
        await validateFile(selectedFile, nextMode, nextMatchKey);
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : 'Failed to parse or validate the file');
        setStep('upload');
      }
    },
    [matchKey, mode, t, validateFile],
  );

  const handleModeChange = useCallback(
    async (nextMode: ImportMode) => {
      const nextMatchKey = nextMode === 'update' ? config?.updateKeys?.[0] || '' : '';
      setMode(nextMode);
      setMatchKey(nextMatchKey);
      if (file) await parseAndValidate(file, nextMode, nextMatchKey);
    },
    [config?.updateKeys, file, parseAndValidate],
  );

  const pollImport = useCallback(
    async (taskId: string): Promise<BackendImportResult> => {
      for (let attempt = 0; attempt < 300; attempt += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
        const response = await fetch(`/api/meta/excel/import/${modelCode}/status/${taskId}`);
        const body = (await response.json().catch(() => undefined)) as
          | ApiEnvelope<AsyncImportStatus>
          | undefined;
        if (!response.ok || !body || !ResultHelper.isSuccess(body) || !body.data) {
          throw apiError(response, body);
        }
        const status = body.data.status.toLowerCase();
        if (status === 'completed' && body.data.result) return body.data.result;
        if (status === 'cancelled') {
          throw new Error(
            t(
              'import.error.cancelled',
              undefined,
              '导入已取消。已提交的行保持不变，可使用同一文件重试。',
            ),
          );
        }
        if (status === 'failed') {
          throw new Error(body.data.result?.errors?.[0]?.message || 'Import task failed');
        }
      }
      throw new Error('Import is still running. Check the task status later.');
    },
    [modelCode, t],
  );

  const presentResult = useCallback((result: BackendImportResult) => {
    const data: ImportResultData = {
      success: !result.hasErrors,
      imported: result.successCount,
      failed: result.errorCount,
      total: result.totalRows,
      created: result.createdCount,
      updated: result.updatedCount,
      errors: result.errors || [],
      taskId: result.taskId,
      errorReportUrl: result.errorReportUrl,
      errorReportFailed: result.errorReportFailed,
      errorReportExpired: result.errorReportExpired,
    };
    setImportResult(data);
    setStep('result');
  }, []);

  const handleImport = useCallback(async () => {
    if (!file || !validation?.valid) return;
    setStep('importing');
    setError(null);
    setTaskNotice(null);
    setRetrying(false);
    try {
      const formData = new FormData();
      formData.append('file', file);
      const params = requestParams(mode, matchKey);
      params.set('skipErrors', 'true');
      const response = await fetch(`/api/meta/excel/import/${modelCode}?${params}`, {
        method: 'post',
        body: formData,
      });
      const body = (await response.json().catch(() => undefined)) as
        | ApiEnvelope<BackendImportResult>
        | undefined;
      if (!response.ok || !body || !ResultHelper.isSuccess(body) || !body.data) {
        throw apiError(response, body);
      }
      if (body.data.taskId) setActiveTaskId(body.data.taskId);
      const result = body.data.taskId ? await pollImport(body.data.taskId) : body.data;
      presentResult(result);
      setActiveTaskId(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Import failed');
      setRetrying(true);
      setActiveTaskId(null);
      setStep('preview');
    }
  }, [
    file,
    matchKey,
    mode,
    modelCode,
    pollImport,
    presentResult,
    requestParams,
    validation?.valid,
  ]);

  const handleCancelImport = useCallback(async () => {
    if (!activeTaskId) return;
    setError(null);
    try {
      const response = await fetch(
        `/api/meta/excel/import/${modelCode}/cancel/${activeTaskId}`,
        { method: 'post' },
      );
      const body = (await response.json().catch(() => undefined)) as
        | ApiEnvelope<AsyncImportStatus>
        | undefined;
      if (!response.ok || !body || !ResultHelper.isSuccess(body)) {
        throw apiError(response, body);
      }
      setTaskNotice(
        t('import.cancelling', undefined, '正在取消导入，等待当前行安全结束…'),
      );
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : t('import.error.cancel_request', undefined, '取消导入失败，请重试。'),
      );
    }
  }, [activeTaskId, modelCode, t]);

  const validationMessage = useCallback(
    (message: string) => {
      const namedRequired = /^Field '([^']+)' is required$/.exec(message);
      if (namedRequired) {
        const field = fieldLabel(namedRequired[1]);
        return t('import.validation.named_required', { field }, `字段“${field}”为必填项`);
      }
      if (message === 'Required field is missing') {
        return t('import.validation.required', undefined, '必填字段缺失');
      }
      if (message.startsWith('Value cannot be parsed as ')) {
        return t(
          'import.validation.type',
          { type: message.slice('Value cannot be parsed as '.length) },
          `值无法解析为 ${message.slice('Value cannot be parsed as '.length)}`,
        );
      }
      if (message.startsWith('Field is not allowed for ')) {
        return t('import.validation.field_not_allowed', undefined, '当前导入方式不允许此字段');
      }
      if (message.startsWith('Duplicate value on unique field')) {
        return t('import.validation.duplicate', undefined, '唯一字段存在重复值');
      }
      if (message === 'Duplicate column header') {
        return t('import.validation.duplicate_header', undefined, '存在重复列头');
      }
      const referenceMessage = resolveImportReferenceMessage(message);
      if (referenceMessage === '关联记录不存在或无权访问') {
        return t('import.validation.reference', undefined, referenceMessage);
      }
      if (referenceMessage === '关联值不唯一，请改用唯一业务编码或 PID') {
        return t('import.validation.reference_ambiguous', undefined, referenceMessage);
      }
      const executionMessage = resolveImportExecutionMessage(message, fieldLabels);
      if (executionMessage) {
        return t(executionMessage.key, executionMessage.params, executionMessage.fallback);
      }
      return resolveImportMessageFieldCodes(message, fieldLabels);
    },
    [fieldLabel, fieldLabels, t],
  );

  if (!open) return null;

  const modeLabel =
    mode === 'insert'
      ? t('import.mode.insert', undefined, '新增导入')
      : t('import.mode.update', undefined, '更新导入');
  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/50" />
      <div
        data-testid="excel-import-backdrop"
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
        onClick={handleClose}
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-busy={isBusy}
          aria-labelledby="excel-import-title"
          data-testid="excel-import-dialog"
          className="bg-panel rounded-card-lg flex max-h-[88vh] w-full max-w-4xl flex-col shadow-2xl"
          onClick={(event) => event.stopPropagation()}
        >
          <header className="border-border flex items-center justify-between border-b px-6 py-4">
            <div>
              <h2 id="excel-import-title" className="text-text text-lg font-semibold">
                {t('import.title', undefined, 'Excel 数据导入')}
              </h2>
              <p className="text-text-3 mt-1 text-xs">
                {t(
                  'import.subtitle',
                  undefined,
                  '下载模板、上传预检，再执行导入。更新导入不会新增记录。',
                )}
              </p>
            </div>
            <button
              type="button"
              aria-label={t('action.close', undefined, '关闭')}
              disabled={isBusy}
              onClick={handleClose}
              className="text-text-3 hover:bg-hover focus-visible:ring-accent rounded-control p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-40"
            >
              <span aria-hidden>✕</span>
            </button>
          </header>

          <div className="border-border bg-subtle flex flex-wrap items-center gap-3 border-b px-6 py-3">
            <span className="text-text-2 text-sm font-medium">
              {t('import.mode.label', undefined, '导入方式')}
            </span>
            <div className="border-border bg-panel inline-flex rounded-lg border p-1">
              {modes.map((item) => (
                <button
                  key={item}
                  type="button"
                  data-testid={`import-mode-${item}`}
                  disabled={isBusy}
                  onClick={() => void handleModeChange(item)}
                  className={cn(
                    'rounded-md px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-40',
                    mode === item ? 'bg-accent text-white' : 'text-text-2 hover:bg-hover',
                  )}
                >
                  {item === 'insert'
                    ? t('import.mode.insert', undefined, '新增导入')
                    : t('import.mode.update', undefined, '更新导入')}
                </button>
              ))}
            </div>
            {mode === 'update' && (
              <label className="text-text-2 flex items-center gap-2 text-sm">
                {t('import.match_key', undefined, '匹配字段')}
                <select
                  data-testid="import-match-key"
                  disabled={isBusy}
                  value={matchKey}
                  onChange={(event) => {
                    const key = event.target.value;
                    setMatchKey(key);
                    if (file) void parseAndValidate(file, mode, key);
                  }}
                  className="border-border bg-panel rounded-control border px-2 py-1.5"
                >
                  {(config?.updateKeys || []).map((key) => (
                    <option key={key} value={key}>
                      {fieldLabel(key)}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <button
              type="button"
              data-testid="import-download-template"
              disabled={isBusy}
              onClick={() => void downloadTemplate()}
              className="text-accent ml-auto text-sm hover:underline disabled:cursor-not-allowed disabled:opacity-40"
            >
              {downloadingTemplate
                ? t('import.downloading_template', undefined, '正在下载模板…')
                : t('import.download_template', { mode: modeLabel }, `下载${modeLabel}模板`)}
            </button>
          </div>

          <main className="flex-1 overflow-y-auto px-6 py-5">
            <input
              ref={fileInputRef}
              type="file"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={(event) => {
                const selected = event.target.files?.[0];
                if (selected) void parseAndValidate(selected);
              }}
              className="hidden"
            />
            {step === 'upload' && (
              <div
                onDragOver={(event) => {
                  event.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={(event) => {
                  event.preventDefault();
                  setDragOver(false);
                  const dropped = event.dataTransfer.files?.[0];
                  if (dropped) void parseAndValidate(dropped);
                }}
                className={cn(
                  'rounded-card border-2 border-dashed p-12 text-center transition-colors',
                  dragOver ? 'border-accent bg-accent-weak' : 'border-border-strong bg-subtle',
                )}
              >
                <div className="text-text-3 mb-4 text-4xl">⇧</div>
                <p className="text-text-2 mb-3 text-sm">
                  {t('import.drop_hint', undefined, '拖放 .xlsx 文件到这里，或选择文件')}
                </p>
                <button
                  type="button"
                  data-testid="import-browse-file"
                  onClick={() => fileInputRef.current?.click()}
                  className="bg-accent rounded-control px-4 py-2 text-sm font-medium text-white"
                >
                  {t('import.choose_file', undefined, '选择文件')}
                </button>
                <p className="text-text-3 mt-3 text-xs">
                  {t('import.file_limit', undefined, '仅支持 .xlsx，最大 10 MB')}
                </p>
              </div>
            )}

            {(step === 'validating' || step === 'importing') && (
              <div className="py-16 text-center">
                <span className="border-accent-weak border-t-accent rounded-pill mb-4 inline-block h-10 w-10 animate-spin border-3" />
                <p className="text-text-2 text-sm">
                  {step === 'validating'
                    ? t('import.validating', undefined, '正在预检字段、类型和重复值…')
                    : t('import.importing', undefined, '正在执行导入，请勿关闭窗口…')}
                </p>
              </div>
            )}

            {step === 'preview' && (
              <div className="space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h3 className="text-text font-medium">{file?.name}</h3>
                    <p className="text-text-3 text-xs">
                      {t(
                        'import.preview_rows',
                        { count: previewData.length },
                        `预览前 ${previewData.length} 行`,
                      )}
                    </p>
                  </div>
                  <button type="button" onClick={resetState} className="text-accent text-sm">
                    {t('import.change_file', undefined, '更换文件')}
                  </button>
                </div>

                {validation && (
                  <div
                    data-testid="import-validation-summary"
                    className={cn(
                      'rounded-card border p-4',
                      validation.valid
                        ? 'border-status-green bg-status-green-bg'
                        : 'border-status-red bg-status-red-bg',
                    )}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <strong
                        className={validation.valid ? 'text-status-green' : 'text-status-red'}
                      >
                        {validation.valid
                          ? t('import.validation.passed', undefined, '预检通过，可以导入')
                          : t('import.validation.failed', undefined, '预检未通过，请修正文件')}
                      </strong>
                      <span className="text-text-2 text-sm">
                        {t(
                          'import.validation.summary',
                          {
                            total: validation.totalRows,
                            valid: validation.validRows,
                            errors: validation.errors.length,
                          },
                          `总计 ${validation.totalRows} · 有效 ${validation.validRows} · 错误 ${validation.errors.length}`,
                        )}
                      </span>
                    </div>
                    {validation.errors.length > 0 && (
                      <div className="mt-3 max-h-40 overflow-y-auto">
                        {validation.errors.slice(0, 50).map((item, index) => (
                          <p
                            key={`${item.rowNumber}-${item.fieldCode}-${index}`}
                            className="text-status-red py-0.5 text-xs"
                          >
                            {t('import.row', { row: item.rowNumber }, `第 ${item.rowNumber} 行`)}
                            {item.fieldCode ? ` · ${fieldLabel(item.fieldCode)}` : ''}：
                            {validationMessage(item.message)}
                          </p>
                        ))}
                      </div>
                    )}
                    {!validation.valid && validation.errorReportUrl && (
                      <div className="border-status-red/30 mt-4 flex flex-wrap items-center gap-3 border-t pt-3">
                        <p className="text-text-2 mr-auto text-xs">
                          {t(
                            'import.correction.pending_hint',
                            undefined,
                            '下载包含全部待导入行的修正工作簿，修正标记单元格后直接重新上传。',
                          )}
                        </p>
                        <button
                          type="button"
                          data-testid="import-download-error-report"
                          disabled={isBusy}
                          onClick={() => void downloadErrorReport(validation.errorReportUrl!)}
                          className="border-accent text-accent hover:bg-accent-weak rounded-control border px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-40"
                        >
                          {downloadingErrorReport
                            ? t('import.correction.downloading', undefined, '正在下载修正工作簿…')
                            : t('import.correction.download', undefined, '下载修正工作簿')}
                        </button>
                        <button
                          type="button"
                          data-testid="import-upload-correction"
                          disabled={isBusy}
                          onClick={chooseCorrectionFile}
                          className="bg-accent rounded-control px-3 py-1.5 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
                        >
                          {t('import.correction.upload', undefined, '上传修正工作簿')}
                        </button>
                      </div>
                    )}
                    {!validation.valid && validation.errorReportFailed && (
                      <p
                        data-testid="import-error-report-unavailable"
                        className="text-status-red mt-3 text-xs"
                      >
                        {t(
                          'import.error.error_report_unavailable',
                          undefined,
                          '错误明细已保留在当前页面，但修正工作簿暂时无法生成。请修正原文件后重新上传。',
                        )}
                      </p>
                    )}
                  </div>
                )}

                <div className="border-border rounded-card overflow-x-auto border">
                  <table className="divide-border min-w-full divide-y text-sm">
                    <thead className="bg-subtle">
                      <tr>
                        {previewColumns.map((column) => (
                          <th
                            key={column}
                            className="text-text-2 px-3 py-2 text-left text-xs whitespace-nowrap"
                          >
                            {fieldLabel(column)}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-border divide-y">
                      {previewData.map((row, rowIndex) => (
                        <tr key={rowIndex}>
                          {previewColumns.map((column) => (
                            <td key={column} className="text-text-2 max-w-48 truncate px-3 py-2">
                              {String(row[column] ?? '')}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {step === 'result' && importResult && (
              <div data-testid="import-result" className="py-8 text-center">
                <div
                  className={cn(
                    'mb-3 text-5xl',
                    importResult.success ? 'text-status-green' : 'text-status-amber',
                  )}
                >
                  {importResult.success ? '✓' : '!'}
                </div>
                <h3 className="text-text text-lg font-semibold">
                  {importResult.success
                    ? t('import.result.completed', undefined, '导入完成')
                    : t('import.result.partial', undefined, '导入完成，部分行失败')}
                </h3>
                <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  {[
                    [
                      'created',
                      t('import.result.created', undefined, '新增'),
                      importResult.created,
                    ],
                    [
                      'updated',
                      t('import.result.updated', undefined, '更新'),
                      importResult.updated,
                    ],
                    ['failed', t('import.result.failed', undefined, '失败'), importResult.failed],
                    ['total', t('import.result.total', undefined, '总计'), importResult.total],
                  ].map(([key, label, value]) => (
                    <div key={String(key)} className="bg-subtle rounded-card p-3">
                      <div
                        data-testid={`import-result-${key}`}
                        className="text-text text-2xl font-bold"
                      >
                        {value}
                      </div>
                      <div className="text-text-3 text-xs">{label}</div>
                    </div>
                  ))}
                </div>
                {importResult.errors.length > 0 && (
                  <div className="border-status-red bg-status-red-bg mt-5 max-h-44 overflow-y-auto rounded-lg border p-3 text-left">
                    {importResult.errors.slice(0, 100).map((item, index) => (
                      <p key={index} className="text-status-red text-xs">
                        {t('import.row', { row: item.rowNumber }, `第 ${item.rowNumber} 行`)}：
                        {validationMessage(item.message)}
                      </p>
                    ))}
                  </div>
                )}
                {!importResult.success && importResult.errorReportUrl && (
                  <div className="border-border bg-subtle mt-5 rounded-lg border p-4 text-left">
                    <p className="text-text-2 text-sm">
                      {t(
                        'import.correction.partial_hint',
                        undefined,
                        '已成功的记录不会出现在修正工作簿中。修正失败行后可直接再次上传。',
                      )}
                    </p>
                    <div className="mt-3 flex flex-wrap gap-3">
                      <button
                        type="button"
                        data-testid="import-result-download-error-report"
                        disabled={isBusy}
                        onClick={() => void downloadErrorReport(importResult.errorReportUrl!)}
                        className="border-accent text-accent hover:bg-accent-weak rounded-control border px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {downloadingErrorReport
                          ? t('import.correction.downloading', undefined, '正在下载修正工作簿…')
                          : t('import.correction.download', undefined, '下载修正工作簿')}
                      </button>
                      <button
                        type="button"
                        data-testid="import-result-upload-correction"
                        disabled={isBusy}
                        onClick={chooseCorrectionFile}
                        className="bg-accent rounded-control px-3 py-1.5 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {t('import.correction.upload', undefined, '上传修正工作簿')}
                      </button>
                    </div>
                  </div>
                )}
                {!importResult.success && importResult.errorReportFailed && (
                  <p
                    data-testid="import-result-error-report-unavailable"
                    className="text-status-red mt-4 text-sm"
                  >
                    {t(
                      'import.error.error_report_unavailable',
                      undefined,
                      '错误明细已保留在当前页面，但修正工作簿暂时无法生成。请修正原文件后重新上传。',
                    )}
                  </p>
                )}
                {!importResult.success && importResult.errorReportExpired && (
                  <p
                    data-testid="import-result-error-report-expired"
                    className="text-text-3 mt-4 text-sm"
                  >
                    {t(
                      'import.error.error_report_expired',
                      undefined,
                      '修正工作簿已超过保留期。请基于原文件重新执行预检。',
                    )}
                  </p>
                )}
              </div>
            )}

            {error && (
              <div
                role="alert"
                className="border-status-red bg-status-red-bg text-status-red rounded-control mt-4 border p-3 text-sm"
              >
                {error}
              </div>
            )}
            {taskNotice && (
              <div
                role="status"
                className="border-accent bg-accent-weak text-accent rounded-control mt-4 border p-3 text-sm"
              >
                {taskNotice}
              </div>
            )}
          </main>

          <footer className="border-border flex justify-end gap-3 border-t px-6 py-4">
            {step === 'importing' && activeTaskId && (
              <button
                type="button"
                data-testid="import-cancel-task"
                onClick={() => void handleCancelImport()}
                className="border-status-red text-status-red rounded-control border px-4 py-2 text-sm"
              >
                {t('import.cancel_task', undefined, '取消导入任务')}
              </button>
            )}
            <button
              type="button"
              disabled={isBusy}
              onClick={handleClose}
              className="border-border bg-panel text-text-2 rounded-control border px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-40"
            >
              {step === 'result'
                ? t('action.close', undefined, '关闭')
                : t('action.cancel', undefined, '取消')}
            </button>
            {step === 'preview' && (
              <button
                type="button"
                data-testid="import-submit"
                disabled={!validation?.valid}
                onClick={() => void handleImport()}
                className="bg-accent rounded-control px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
              >
                {retrying
                  ? t('import.retry', undefined, '重试导入')
                  : t('import.start', { mode: modeLabel }, `开始${modeLabel}`)}
              </button>
            )}
          </footer>
        </div>
      </div>
    </>
  );
};

export default ImportModal;
