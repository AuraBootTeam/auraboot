/**
 * ImportModal Component
 *
 * Modal dialog for importing data from Excel or CSV files.
 * Supports file upload, field mapping preview, and execution.
 */

import React, { useState, useCallback, useRef } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

export interface ImportModalProps {
  /** Whether the modal is open */
  open: boolean;
  /** Callback to close the modal */
  onClose: () => void;
  /** Model code for the import target */
  modelCode: string;
  /** Callback after successful import */
  onImportComplete?: (result: ImportResultData) => void;
}

interface ImportResultData {
  success: boolean;
  imported: number;
  failed: number;
  total: number;
}

interface PreviewRow {
  [key: string]: unknown;
}

type ImportStep = 'upload' | 'preview' | 'importing' | 'result';

/**
 * ImportModal - Modal for importing data from files
 */
export const ImportModal: React.FC<ImportModalProps> = ({
  open,
  onClose,
  modelCode,
  onImportComplete,
}) => {
  const { t } = useI18n();
  const [step, setStep] = useState<ImportStep>('upload');
  const [file, setFile] = useState<File | null>(null);
  const [previewData, setPreviewData] = useState<PreviewRow[]>([]);
  const [previewColumns, setPreviewColumns] = useState<string[]>([]);
  const [importResult, setImportResult] = useState<ImportResultData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const resetState = useCallback(() => {
    setStep('upload');
    setFile(null);
    setPreviewData([]);
    setPreviewColumns([]);
    setImportResult(null);
    setError(null);
    setDragOver(false);
  }, []);

  const handleClose = useCallback(() => {
    resetState();
    onClose();
  }, [resetState, onClose]);

  const parseFileForPreview = useCallback(
    async (selectedFile: File) => {
      setFile(selectedFile);
      setError(null);

      try {
        const fileExt = selectedFile.name.split('.').pop()?.toLowerCase();

        if (fileExt === 'csv') {
          const text = await selectedFile.text();
          const Papa = await import('papaparse');
          const result = Papa.default.parse(text, { header: true, preview: 10 });
          if (result.errors.length > 0) {
            setError(
              t(
                'import.error.csv_parse',
                { message: result.errors[0].message },
                `CSV parsing error: ${result.errors[0].message}`,
              ),
            );
            return;
          }
          const data = result.data as PreviewRow[];
          setPreviewColumns(result.meta.fields || []);
          setPreviewData(data);
        } else if (fileExt === 'xlsx' || fileExt === 'xls') {
          const XLSX = await import('xlsx');
          const buffer = await selectedFile.arrayBuffer();
          const workbook = XLSX.read(buffer, { type: 'array' });
          const firstSheet = workbook.Sheets[workbook.SheetNames[0]];
          const jsonData = XLSX.utils.sheet_to_json<PreviewRow>(firstSheet, { defval: '' });
          const preview = jsonData.slice(0, 10);
          const cols = preview.length > 0 ? Object.keys(preview[0]) : [];
          setPreviewColumns(cols);
          setPreviewData(preview);
        } else {
          setError(
            t(
              'import.error.unsupported_format',
              undefined,
              'Unsupported file format. Please use .xlsx, .xls, or .csv files.',
            ),
          );
          return;
        }

        setStep('preview');
      } catch (err) {
        setError(
          err instanceof Error
            ? err.message
            : t('import.error.parse_failed', undefined, 'Failed to parse file'),
        );
      }
    },
    [t],
  );

  const handleFileSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const selectedFile = e.target.files?.[0];
      if (selectedFile) parseFileForPreview(selectedFile);
    },
    [parseFileForPreview],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragOver(false);
      const droppedFile = e.dataTransfer.files?.[0];
      if (droppedFile) parseFileForPreview(droppedFile);
    },
    [parseFileForPreview],
  );

  const handleImport = useCallback(async () => {
    if (!file) return;

    setStep('importing');
    setError(null);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const response = await fetch(`/api/dynamic/${modelCode}/import`, {
        method: 'post',
        body: formData,
      });

      if (!response.ok) {
        throw new Error(
          t(
            'import.error.failed_status',
            { status: response.statusText },
            `Import failed: ${response.statusText}`,
          ),
        );
      }

      const result = await response.json();
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || t('import.error.failed', undefined, 'Import failed'));
      }

      const importData: ImportResultData = {
        success: result.data?.success ?? true,
        imported: result.data?.imported ?? 0,
        failed: result.data?.failed ?? 0,
        total: result.data?.total ?? 0,
      };

      setImportResult(importData);
      setStep('result');
      onImportComplete?.(importData);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : t('import.error.failed', undefined, 'Import failed'),
      );
      setStep('preview');
    }
  }, [file, modelCode, onImportComplete, t]);

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-50 bg-black/50" onClick={handleClose} />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="bg-panel rounded-card-lg flex max-h-[80vh] w-full max-w-2xl flex-col shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-border flex items-center justify-between border-b px-6 py-4">
            <h2 className="text-text text-lg font-semibold">
              {t('import.title', undefined, 'Import Data')}
            </h2>
            <button
              type="button"
              onClick={handleClose}
              aria-label={t('common.close', undefined, 'Close')}
              className="text-text-3 hover:bg-hover hover:text-text-2 rounded-control focus-visible:shadow-focus p-2 focus:outline-none"
            >
              <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>

          {/* Body */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {/* Upload Step */}
            {step === 'upload' && (
              <div
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={handleDrop}
                className={cn(
                  'rounded-card border-2 border-dashed p-12 text-center transition-colors',
                  dragOver ? 'border-accent bg-accent-weak' : 'border-border-strong bg-subtle',
                )}
              >
                <svg
                  className="text-text-3 mx-auto mb-4 h-12 w-12"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                    d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                  />
                </svg>
                <p className="text-text-2 mb-2 text-sm">
                  {t('import.upload.drag_hint', undefined, 'Drag and drop your file here, or')}
                </p>
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="bg-accent-weak text-accent rounded-control focus-visible:shadow-focus px-4 py-2 text-sm font-medium hover:brightness-95 focus:outline-none"
                >
                  {t('import.upload.browse', undefined, 'Browse Files')}
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".xlsx,.xls,.csv"
                  onChange={handleFileSelect}
                  className="hidden"
                />
                <p className="text-text-2 mt-3 text-xs">
                  {t(
                    'import.upload.supports',
                    undefined,
                    'Supports: Excel (.xlsx, .xls), CSV (.csv)',
                  )}
                </p>
                <a
                  href={`/api/meta/excel/template/${modelCode}`}
                  download
                  className="text-accent hover:text-accent-hover focus-visible:shadow-focus mt-3 inline-flex items-center gap-1 text-sm hover:underline focus:outline-none"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                  </svg>
                  {t('import.upload.download_template', undefined, 'Download Import Template')}
                </a>
              </div>
            )}

            {/* Preview Step */}
            {step === 'preview' && (
              <div>
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-text-2 text-sm font-medium">
                      {t('import.preview.title', undefined, 'Preview')}
                    </h3>
                    <p className="text-text-2 text-xs">
                      {t(
                        'import.preview.summary',
                        { name: file?.name, count: previewData.length },
                        `${file?.name} - Showing first ${previewData.length} rows`,
                      )}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={resetState}
                    className="text-accent hover:text-accent-hover focus-visible:shadow-focus text-sm focus:outline-none"
                  >
                    {t('import.preview.change_file', undefined, 'Change File')}
                  </button>
                </div>

                <div className="border-border rounded-card overflow-x-auto border">
                  <table className="divide-border min-w-full divide-y text-sm">
                    <thead className="bg-subtle">
                      <tr>
                        {previewColumns.map((col) => (
                          <th
                            key={col}
                            className="text-text-2 px-3 py-2 text-left text-xs font-medium whitespace-nowrap uppercase"
                          >
                            {col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-border divide-y">
                      {previewData.map((row, idx) => (
                        <tr key={idx} className="hover:bg-subtle">
                          {previewColumns.map((col) => (
                            <td
                              key={col}
                              className="text-text-2 max-w-[200px] truncate px-3 py-2 whitespace-nowrap"
                            >
                              {String(row[col] ?? '')}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* Importing Step */}
            {step === 'importing' && (
              <div className="py-12 text-center">
                <span className="border-accent-weak border-t-accent rounded-pill mb-4 inline-block h-10 w-10 animate-spin border-3" />
                <p className="text-text-2 text-sm">
                  {t('import.importing', undefined, 'Importing data...')}
                </p>
              </div>
            )}

            {/* Result Step */}
            {step === 'result' && importResult && (
              <div className="py-8 text-center">
                {importResult.success ? (
                  <>
                    <svg
                      className="text-status-green mx-auto mb-4 h-16 w-16"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                      />
                    </svg>
                    <h3 className="text-text mb-2 text-lg font-semibold">
                      {t('import.result.complete', undefined, 'Import Complete')}
                    </h3>
                  </>
                ) : (
                  <>
                    <svg
                      className="text-status-amber mx-auto mb-4 h-16 w-16"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
                      />
                    </svg>
                    <h3 className="text-text mb-2 text-lg font-semibold">
                      {t(
                        'import.result.complete_with_errors',
                        undefined,
                        'Import Completed with Errors',
                      )}
                    </h3>
                  </>
                )}
                <div className="mt-4 flex justify-center gap-8">
                  <div className="text-center">
                    <div className="text-status-green text-2xl font-bold">
                      {importResult.imported}
                    </div>
                    <div className="text-text-2 text-xs">
                      {t('import.result.imported', undefined, 'Imported')}
                    </div>
                  </div>
                  <div className="text-center">
                    <div className="text-status-red text-2xl font-bold">{importResult.failed}</div>
                    <div className="text-text-2 text-xs">
                      {t('import.result.failed', undefined, 'Failed')}
                    </div>
                  </div>
                  <div className="text-center">
                    <div className="text-text-2 text-2xl font-bold">{importResult.total}</div>
                    <div className="text-text-2 text-xs">
                      {t('import.result.total', undefined, 'Total')}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Error Display */}
            {error && (
              <div className="border-status-red bg-status-red-bg rounded-control mt-4 border p-3">
                <p className="text-status-red text-sm">{error}</p>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="border-border flex justify-end gap-3 border-t px-6 py-4">
            <button
              type="button"
              onClick={handleClose}
              className="border-border-strong bg-panel text-text-2 hover:bg-subtle rounded-control focus-visible:shadow-focus border px-4 py-2 text-sm font-medium focus:outline-none"
            >
              {step === 'result'
                ? t('common.close', undefined, 'Close')
                : t('common.cancel', undefined, 'Cancel')}
            </button>
            {step === 'preview' && (
              <button
                type="button"
                onClick={handleImport}
                className="bg-accent hover:bg-accent-hover rounded-control focus-visible:shadow-focus px-4 py-2 text-sm font-medium text-white focus:outline-none"
              >
                {t('import.start', undefined, 'Start Import')}
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );
};

export default ImportModal;
