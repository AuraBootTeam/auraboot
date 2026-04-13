/**
 * ImportModal Component
 *
 * Modal dialog for importing data from Excel or CSV files.
 * Supports file upload, field mapping preview, and execution.
 */

import React, { useState, useCallback, useRef } from 'react';
import { cn } from '~/utils/cn';
import { ResultHelper } from '~/utils/type';

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

  const parseFileForPreview = useCallback(async (selectedFile: File) => {
    setFile(selectedFile);
    setError(null);

    try {
      const fileExt = selectedFile.name.split('.').pop()?.toLowerCase();

      if (fileExt === 'csv') {
        const text = await selectedFile.text();
        const Papa = await import('papaparse');
        const result = Papa.default.parse(text, { header: true, preview: 10 });
        if (result.errors.length > 0) {
          setError(`CSV parsing error: ${result.errors[0].message}`);
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
        setError('Unsupported file format. Please use .xlsx, .xls, or .csv files.');
        return;
      }

      setStep('preview');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to parse file');
    }
  }, []);

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
        throw new Error(`Import failed: ${response.statusText}`);
      }

      const result = await response.json();
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || 'Import failed');
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
      setError(err instanceof Error ? err.message : 'Import failed');
      setStep('preview');
    }
  }, [file, modelCode, onImportComplete]);

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-50 bg-black/50" onClick={handleClose} />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="flex max-h-[80vh] w-full max-w-2xl flex-col rounded-xl bg-white shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">Import Data</h2>
            <button
              type="button"
              onClick={handleClose}
              className="rounded-md p-2 text-gray-400 hover:bg-gray-100 hover:text-gray-500"
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
                  'rounded-lg border-2 border-dashed p-12 text-center transition-colors',
                  dragOver ? 'border-blue-400 bg-blue-50' : 'border-gray-300 bg-gray-50',
                )}
              >
                <svg
                  className="mx-auto mb-4 h-12 w-12 text-gray-400"
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
                <p className="mb-2 text-sm text-gray-600">Drag and drop your file here, or</p>
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  className="rounded-md bg-blue-50 px-4 py-2 text-sm font-medium text-blue-600 hover:bg-blue-100"
                >
                  Browse Files
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".xlsx,.xls,.csv"
                  onChange={handleFileSelect}
                  className="hidden"
                />
                <p className="mt-3 text-xs text-gray-500">
                  Supports: Excel (.xlsx, .xls), CSV (.csv)
                </p>
                <a
                  href={`/api/meta/excel/template/${modelCode}`}
                  download
                  className="mt-3 inline-flex items-center gap-1 text-sm text-blue-600 hover:text-blue-700 hover:underline"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                  </svg>
                  Download Import Template
                </a>
              </div>
            )}

            {/* Preview Step */}
            {step === 'preview' && (
              <div>
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h3 className="text-sm font-medium text-gray-700">Preview</h3>
                    <p className="text-xs text-gray-500">
                      {file?.name} - Showing first {previewData.length} rows
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={resetState}
                    className="text-sm text-blue-600 hover:text-blue-700"
                  >
                    Change File
                  </button>
                </div>

                <div className="overflow-x-auto rounded-lg border border-gray-200">
                  <table className="min-w-full divide-y divide-gray-200 text-sm">
                    <thead className="bg-gray-50">
                      <tr>
                        {previewColumns.map((col) => (
                          <th
                            key={col}
                            className="px-3 py-2 text-left text-xs font-medium whitespace-nowrap text-gray-500 uppercase"
                          >
                            {col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200">
                      {previewData.map((row, idx) => (
                        <tr key={idx} className="hover:bg-gray-50">
                          {previewColumns.map((col) => (
                            <td
                              key={col}
                              className="max-w-[200px] truncate px-3 py-2 whitespace-nowrap text-gray-700"
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
                <span className="mb-4 inline-block h-10 w-10 animate-spin rounded-full border-3 border-blue-200 border-t-blue-600" />
                <p className="text-sm text-gray-600">Importing data...</p>
              </div>
            )}

            {/* Result Step */}
            {step === 'result' && importResult && (
              <div className="py-8 text-center">
                {importResult.success ? (
                  <>
                    <svg
                      className="mx-auto mb-4 h-16 w-16 text-green-500"
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
                    <h3 className="mb-2 text-lg font-semibold text-gray-900">Import Complete</h3>
                  </>
                ) : (
                  <>
                    <svg
                      className="mx-auto mb-4 h-16 w-16 text-yellow-500"
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
                    <h3 className="mb-2 text-lg font-semibold text-gray-900">
                      Import Completed with Errors
                    </h3>
                  </>
                )}
                <div className="mt-4 flex justify-center gap-8">
                  <div className="text-center">
                    <div className="text-2xl font-bold text-green-600">{importResult.imported}</div>
                    <div className="text-xs text-gray-500">Imported</div>
                  </div>
                  <div className="text-center">
                    <div className="text-2xl font-bold text-red-600">{importResult.failed}</div>
                    <div className="text-xs text-gray-500">Failed</div>
                  </div>
                  <div className="text-center">
                    <div className="text-2xl font-bold text-gray-600">{importResult.total}</div>
                    <div className="text-xs text-gray-500">Total</div>
                  </div>
                </div>
              </div>
            )}

            {/* Error Display */}
            {error && (
              <div className="mt-4 rounded-md border border-red-200 bg-red-50 p-3">
                <p className="text-sm text-red-600">{error}</p>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            <button
              type="button"
              onClick={handleClose}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              {step === 'result' ? 'Close' : 'Cancel'}
            </button>
            {step === 'preview' && (
              <button
                type="button"
                onClick={handleImport}
                className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                Start Import
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );
};

export default ImportModal;
