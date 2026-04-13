import React, { useState, useCallback, useRef, useMemo, useEffect } from 'react';
import {
  autoMapColumns,
  getMissingRequiredFields,
  BOM_LINE_FIELDS,
  FIELD_CODE_TO_API,
  type ColumnMapping,
  type BomTargetField,
} from './BomColumnMapper';
import { post } from '~/shared/services/http-client';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BomImportWizardProps {
  /** Called with the created BOM record PID on successful import */
  onComplete?: (bomId: string) => void;
  /** Called when the user cancels the wizard */
  onCancel?: () => void;
  className?: string;
}

interface ParsedRow {
  /** Original row index in the file (1-based, skipping header) */
  rowIndex: number;
  /** Raw cell values keyed by original column header */
  raw: Record<string, string>;
  /** Mapped values keyed by target field code */
  mapped: Record<string, string>;
  /** Validation errors on this row */
  errors: string[];
  /** Validation warnings on this row */
  warnings: string[];
  /** Whether this row is excluded from import */
  excluded: boolean;
}

interface ImportProgress {
  total: number;
  completed: number;
  failed: number;
  errors: string[];
}

type WizardStep = 'upload' | 'mapping' | 'preview' | 'import';

const STEPS: { key: WizardStep; label: string; icon: string }[] = [
  { key: 'upload', label: 'Upload File', icon: '1' },
  { key: 'mapping', label: 'Column Mapping', icon: '2' },
  { key: 'preview', label: 'Preview & Validate', icon: '3' },
  { key: 'import', label: 'Import', icon: '4' },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function remapRows(rawRows: Record<string, string>[], mappings: ColumnMapping[]): ParsedRow[] {
  return rawRows.map((raw, idx) => {
    const mapped: Record<string, string> = {};
    for (const m of mappings) {
      if (m.targetField) {
        mapped[m.targetField] = raw[m.sourceColumn] ?? '';
      }
    }
    return {
      rowIndex: idx + 1,
      raw,
      mapped,
      errors: [],
      warnings: [],
      excluded: false,
    };
  });
}

function validateRows(rows: ParsedRow[]): ParsedRow[] {
  const refDesSet = new Map<string, number[]>();

  return rows.map((row) => {
    const errors: string[] = [];
    const warnings: string[] = [];

    // Required: product_code
    const partNumber = (row.mapped.product_code ?? '').trim();
    if (!partNumber) {
      errors.push('Part number is required');
    }

    // Required: quantity must be a positive number
    const qtyStr = (row.mapped.quantity ?? '').trim();
    if (!qtyStr) {
      errors.push('Quantity is required');
    } else {
      const qty = Number(qtyStr);
      if (isNaN(qty)) {
        errors.push(`Quantity "${qtyStr}" is not a valid number`);
      } else if (qty <= 0) {
        errors.push(`Quantity must be positive (got ${qty})`);
      }
    }

    // Optional: loss_rate validation
    const lossStr = (row.mapped.loss_rate ?? '').trim();
    if (lossStr) {
      const loss = Number(lossStr);
      if (isNaN(loss)) {
        warnings.push(`Loss rate "${lossStr}" is not a valid number`);
      } else if (loss < 0 || loss > 100) {
        warnings.push(`Loss rate should be 0-100 (got ${loss})`);
      }
    }

    // Track reference designators for duplicate detection
    const ref = (row.mapped.reference ?? '').trim();
    if (ref) {
      // Split by comma for individual designators like "R1,R2,C3"
      const parts = ref.split(/[,;\s]+/).filter(Boolean);
      for (const part of parts) {
        const existing = refDesSet.get(part) ?? [];
        existing.push(row.rowIndex);
        refDesSet.set(part, existing);
      }
    }

    return { ...row, errors, warnings };
  });
}

/**
 * Post-process: flag duplicate reference designators across all rows.
 */
function flagDuplicateRefs(rows: ParsedRow[]): ParsedRow[] {
  // Build a set of all ref designators that appear more than once
  const refCount = new Map<string, number>();
  for (const row of rows) {
    const ref = (row.mapped.reference ?? '').trim();
    if (!ref) continue;
    const parts = ref.split(/[,;\s]+/).filter(Boolean);
    for (const part of parts) {
      refCount.set(part, (refCount.get(part) ?? 0) + 1);
    }
  }

  const duplicates = new Set<string>();
  for (const [key, count] of refCount) {
    if (count > 1) duplicates.add(key);
  }

  if (duplicates.size === 0) return rows;

  return rows.map((row) => {
    const ref = (row.mapped.reference ?? '').trim();
    if (!ref) return row;
    const parts = ref.split(/[,;\s]+/).filter(Boolean);
    const dups = parts.filter((p) => duplicates.has(p));
    if (dups.length === 0) return row;
    return {
      ...row,
      warnings: [...row.warnings, `Duplicate ref designator(s): ${dups.join(', ')}`],
    };
  });
}

// ---------------------------------------------------------------------------
// Main Component
// ---------------------------------------------------------------------------

/**
 * BomImportWizard - Multi-step wizard for importing BOM data from
 * Excel (.xlsx/.xls) or CSV files into the pe_bom / pe_bom_line models.
 *
 * Steps:
 *  1. Upload - drag & drop or click to select file
 *  2. Column Mapping - auto-map with manual override
 *  3. Preview & Validate - review rows, fix issues, exclude rows
 *  4. Import - create BOM header + lines via Command Engine API
 *
 * @since 3.9.0
 */
export const BomImportWizard: React.FC<BomImportWizardProps> = ({
  onComplete,
  onCancel,
  className = '',
}) => {
  // -- Wizard state --
  const [step, setStep] = useState<WizardStep>('upload');

  // -- Step 1: Upload --
  const [file, setFile] = useState<File | null>(null);
  const [headers, setHeaders] = useState<string[]>([]);
  const [rawRows, setRawRows] = useState<Record<string, string>[]>([]);
  const [parseError, setParseError] = useState<string | null>(null);

  // -- Step 2: Mapping --
  const [mappings, setMappings] = useState<ColumnMapping[]>([]);

  // -- Step 3: Preview --
  const [rows, setRows] = useState<ParsedRow[]>([]);

  // -- Step 4: Import --
  const [bomName, setBomName] = useState('');
  const [bomVersion, setBomVersion] = useState('1.0');
  const [bomOutputQty, setBomOutputQty] = useState('1');
  const [bomRemark, setBomRemark] = useState('');
  const [importing, setImporting] = useState(false);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [importDone, setImportDone] = useState(false);
  const [createdBomId, setCreatedBomId] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  // -------------------------------------------------------------------------
  // Step 1: File parsing
  // -------------------------------------------------------------------------

  const parseFile = useCallback(
    async (f: File) => {
      setFile(f);
      setParseError(null);

      try {
        const XLSX = await import('xlsx');
        const data = await f.arrayBuffer();
        const workbook = XLSX.read(data, { type: 'array', cellDates: true });

        if (workbook.SheetNames.length === 0) {
          setParseError('The file contains no worksheets.');
          return;
        }

        const sheet = workbook.Sheets[workbook.SheetNames[0]];
        const json: Record<string, any>[] = XLSX.utils.sheet_to_json(sheet, {
          defval: '',
          raw: false,
        });

        if (json.length === 0) {
          setParseError('The file contains no data rows.');
          return;
        }

        // Extract headers from the first row keys
        const hdrs = Object.keys(json[0]);
        if (hdrs.length === 0) {
          setParseError('Could not detect column headers.');
          return;
        }

        // Convert all values to strings
        const stringRows = json.map((row) => {
          const out: Record<string, string> = {};
          for (const key of hdrs) {
            const v = row[key];
            out[key] = v == null ? '' : String(v);
          }
          return out;
        });

        setHeaders(hdrs);
        setRawRows(stringRows);

        // Auto-map columns
        const autoMapped = autoMapColumns(hdrs);
        setMappings(autoMapped);

        // Suggest BOM name from filename
        const baseName = f.name.replace(/\.(xlsx?|csv)$/i, '').trim();
        if (baseName && !bomName) {
          setBomName(baseName);
        }

        // Advance to mapping step
        setStep('mapping');
      } catch (err: any) {
        setParseError(
          `Failed to parse file: ${err.message || 'Unknown error'}. ` +
            'Ensure it is a valid Excel (.xlsx/.xls) or CSV file.',
        );
      }
    },
    [bomName],
  );

  const handleFileSelect = useCallback(
    (files: FileList | null) => {
      if (!files || files.length === 0) return;
      const f = files[0];
      const ext = f.name.split('.').pop()?.toLowerCase();
      if (!['xlsx', 'xls', 'csv'].includes(ext ?? '')) {
        setParseError('Unsupported file format. Please upload .xlsx, .xls, or .csv files.');
        return;
      }
      parseFile(f);
    },
    [parseFile],
  );

  // -------------------------------------------------------------------------
  // Step 2: Mapping changes
  // -------------------------------------------------------------------------

  const updateMapping = useCallback((sourceColumn: string, newTarget: string) => {
    setMappings((prev) =>
      prev.map((m) => {
        if (m.sourceColumn !== sourceColumn) return m;
        return {
          ...m,
          targetField: newTarget,
          confidence: newTarget ? 1.0 : 0,
        };
      }),
    );
  }, []);

  const missingRequired = useMemo(() => getMissingRequiredFields(mappings), [mappings]);

  const proceedToPreview = useCallback(() => {
    const mapped = remapRows(rawRows, mappings);
    const validated = validateRows(mapped);
    const withDupCheck = flagDuplicateRefs(validated);
    setRows(withDupCheck);
    setStep('preview');
  }, [rawRows, mappings]);

  // -------------------------------------------------------------------------
  // Step 3: Preview actions
  // -------------------------------------------------------------------------

  const toggleRowExclude = useCallback((rowIndex: number) => {
    setRows((prev) =>
      prev.map((r) => (r.rowIndex === rowIndex ? { ...r, excluded: !r.excluded } : r)),
    );
  }, []);

  const validRows = useMemo(() => rows.filter((r) => !r.excluded && r.errors.length === 0), [rows]);

  const errorRowCount = useMemo(
    () => rows.filter((r) => !r.excluded && r.errors.length > 0).length,
    [rows],
  );

  // -------------------------------------------------------------------------
  // Step 4: Import execution
  // -------------------------------------------------------------------------

  const executeImport = useCallback(async () => {
    if (!bomName.trim()) return;
    setImporting(true);
    setImportDone(false);
    setCreatedBomId(null);

    const rowsToImport = validRows;
    const prog: ImportProgress = {
      total: rowsToImport.length,
      completed: 0,
      failed: 0,
      errors: [],
    };
    setProgress({ ...prog });

    try {
      // Step 1: Create BOM header via command engine
      const bomResult = await post<any>('/api/meta/commands/execute/pe:create_bom', {
        operationType: 'create',
        payload: {
          pe_bom_name: bomName.trim(),
          pe_bom_version: bomVersion.trim() || '1.0',
          pe_bom_output_qty: Number(bomOutputQty) || 1,
          pe_bom_remark: bomRemark.trim() || undefined,
        },
      });

      const resultData = bomResult?.data?.data;
      const bomId = resultData?.pid ?? resultData?.id ?? resultData?.recordId;
      if (!bomId) {
        throw new Error(
          bomResult?.data?.errorMessage ?? bomResult?.message ?? 'Failed to create BOM header',
        );
      }

      setCreatedBomId(bomId);

      // Step 2: Create BOM lines sequentially (to preserve order)
      for (const row of rowsToImport) {
        try {
          const linePayload: Record<string, any> = {
            pe_bom_line_bom_id: bomId,
          };

          // Map logical fields to API field codes
          for (const [logicalField, value] of Object.entries(row.mapped)) {
            const apiField = FIELD_CODE_TO_API[logicalField];
            if (apiField && value.trim()) {
              // Convert numeric fields
              if (logicalField === 'quantity' || logicalField === 'loss_rate') {
                linePayload[apiField] = Number(value);
              } else {
                linePayload[apiField] = value.trim();
              }
            }
          }

          await post<any>('/api/meta/commands/execute/pe:add_bom_line', {
            operationType: 'create',
            payload: linePayload,
          });

          prog.completed++;
        } catch (err: any) {
          prog.failed++;
          prog.errors.push(`Row ${row.rowIndex}: ${err.message || 'Unknown error'}`);
        }

        setProgress({ ...prog });
      }

      setImportDone(true);
    } catch (err: any) {
      prog.errors.unshift(`BOM creation failed: ${err.message || 'Unknown error'}`);
      prog.failed = prog.total;
      setProgress({ ...prog });
      setImportDone(true);
    } finally {
      setImporting(false);
    }
  }, [bomName, bomVersion, bomOutputQty, bomRemark, validRows]);

  // -------------------------------------------------------------------------
  // Navigation helpers
  // -------------------------------------------------------------------------

  const stepIndex = STEPS.findIndex((s) => s.key === step);

  const goBack = useCallback(() => {
    const idx = STEPS.findIndex((s) => s.key === step);
    if (idx > 0) setStep(STEPS[idx - 1].key);
  }, [step]);

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  return (
    <div
      className={`bom-import-wizard rounded-lg border border-gray-200 bg-white shadow-sm ${className}`}
      data-testid="bom-import-wizard"
    >
      {/* Stepper header */}
      <div className="border-b border-gray-100 px-6 pt-5 pb-4">
        <div className="mx-auto flex max-w-2xl items-center justify-between">
          {STEPS.map((s, idx) => (
            <React.Fragment key={s.key}>
              <div className="flex flex-col items-center">
                <div
                  className={`flex h-8 w-8 items-center justify-center rounded-full text-sm font-semibold transition-colors duration-200 ${
                    idx < stepIndex
                      ? 'bg-emerald-500 text-white'
                      : idx === stepIndex
                        ? 'bg-blue-600 text-white ring-2 ring-blue-200'
                        : 'bg-gray-100 text-gray-400'
                  } `}
                >
                  {idx < stepIndex ? (
                    <svg
                      className="h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                      strokeWidth={3}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  ) : (
                    s.icon
                  )}
                </div>
                <span
                  className={`mt-1.5 text-xs font-medium ${
                    idx <= stepIndex ? 'text-gray-700' : 'text-gray-400'
                  }`}
                >
                  {s.label}
                </span>
              </div>
              {idx < STEPS.length - 1 && (
                <div
                  className={`mx-3 mt-4 h-0.5 flex-1 ${
                    idx < stepIndex ? 'bg-emerald-400' : 'bg-gray-200'
                  }`}
                />
              )}
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* Content area */}
      <div className="p-6">
        {step === 'upload' && (
          <UploadStep
            file={file}
            parseError={parseError}
            dragging={dragging}
            fileInputRef={fileInputRef}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              handleFileSelect(e.dataTransfer.files);
            }}
            onFileSelect={handleFileSelect}
          />
        )}

        {step === 'mapping' && (
          <MappingStep
            mappings={mappings}
            missingRequired={missingRequired}
            onUpdateMapping={updateMapping}
            onProceed={proceedToPreview}
            onBack={goBack}
          />
        )}

        {step === 'preview' && (
          <PreviewStep
            rows={rows}
            mappings={mappings}
            validRowCount={validRows.length}
            errorRowCount={errorRowCount}
            onToggleExclude={toggleRowExclude}
            onProceed={() => setStep('import')}
            onBack={goBack}
          />
        )}

        {step === 'import' && (
          <ImportStep
            bomName={bomName}
            bomVersion={bomVersion}
            bomOutputQty={bomOutputQty}
            bomRemark={bomRemark}
            onBomNameChange={setBomName}
            onBomVersionChange={setBomVersion}
            onBomOutputQtyChange={setBomOutputQty}
            onBomRemarkChange={setBomRemark}
            validRowCount={validRows.length}
            importing={importing}
            importDone={importDone}
            progress={progress}
            createdBomId={createdBomId}
            onExecute={executeImport}
            onBack={goBack}
            onComplete={() => createdBomId && onComplete?.(createdBomId)}
          />
        )}
      </div>

      {/* Cancel button (always visible, bottom-right) */}
      {!importDone && (
        <div className="flex justify-end border-t border-gray-50 px-6 pt-3 pb-4">
          <button
            type="button"
            onClick={onCancel}
            className="text-sm text-gray-500 transition-colors hover:text-gray-700"
            data-testid="bom-import-cancel"
          >
            Cancel
          </button>
        </div>
      )}
    </div>
  );
};

// ---------------------------------------------------------------------------
// Step 1: Upload
// ---------------------------------------------------------------------------

function UploadStep({
  file,
  parseError,
  dragging,
  fileInputRef,
  onDragOver,
  onDragLeave,
  onDrop,
  onFileSelect,
}: {
  file: File | null;
  parseError: string | null;
  dragging: boolean;
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  onDragOver: (e: React.DragEvent) => void;
  onDragLeave: () => void;
  onDrop: (e: React.DragEvent) => void;
  onFileSelect: (files: FileList | null) => void;
}) {
  return (
    <div className="space-y-4" data-testid="bom-import-step-upload">
      <div className="mb-2 text-center">
        <h3 className="text-lg font-semibold text-gray-800">Upload BOM File</h3>
        <p className="mt-1 text-sm text-gray-500">
          Upload an Excel (.xlsx, .xls) or CSV file containing your BOM data.
        </p>
      </div>

      {/* Dropzone */}
      <div
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
        onClick={() => fileInputRef.current?.click()}
        className={`flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-8 py-12 transition-all duration-200 ${
          dragging
            ? 'scale-[1.01] border-blue-400 bg-blue-50'
            : 'border-gray-300 hover:border-blue-300 hover:bg-gray-50'
        } `}
        data-testid="bom-import-dropzone"
      >
        <div className={`mb-3 rounded-full p-3 ${dragging ? 'bg-blue-100' : 'bg-gray-100'}`}>
          <svg
            className={`h-10 w-10 ${dragging ? 'text-blue-500' : 'text-gray-400'}`}
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
        </div>
        <p className="text-sm font-medium text-gray-600">
          {dragging ? 'Drop your file here' : 'Click to browse or drag & drop'}
        </p>
        <p className="mt-1 text-xs text-gray-400">Supported: .xlsx, .xls, .csv (max 10 MB)</p>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept=".xlsx,.xls,.csv"
        onChange={(e) => onFileSelect(e.target.files)}
        className="hidden"
        data-testid="bom-import-file-input"
      />

      {/* File info */}
      {file && !parseError && (
        <div className="flex items-center gap-3 rounded-lg border border-blue-100 bg-blue-50 p-3">
          <svg
            className="h-5 w-5 shrink-0 text-blue-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
            />
          </svg>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-blue-700">{file.name}</p>
            <p className="text-xs text-blue-500">{formatSize(file.size)}</p>
          </div>
          <svg
            className="h-5 w-5 shrink-0 text-blue-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
          </svg>
        </div>
      )}

      {/* Parse error */}
      {parseError && (
        <div className="flex items-start gap-2 rounded-lg border border-red-100 bg-red-50 p-3">
          <svg
            className="mt-0.5 h-5 w-5 shrink-0 text-red-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <p className="text-sm text-red-700">{parseError}</p>
        </div>
      )}

      {/* Tips */}
      <div className="mt-4 rounded-lg bg-gray-50 p-4">
        <h4 className="mb-2 text-xs font-semibold tracking-wide text-gray-600 uppercase">
          Tips for best results
        </h4>
        <ul className="space-y-1 text-xs text-gray-500">
          <li className="flex items-start gap-1.5">
            <span className="mt-0.5 text-emerald-500">&#x2713;</span>
            First row should contain column headers (e.g., Part Number, Qty, Reference)
          </li>
          <li className="flex items-start gap-1.5">
            <span className="mt-0.5 text-emerald-500">&#x2713;</span>
            At minimum, include Part Number and Quantity columns
          </li>
          <li className="flex items-start gap-1.5">
            <span className="mt-0.5 text-emerald-500">&#x2713;</span>
            Chinese and English column headers are both supported
          </li>
          <li className="flex items-start gap-1.5">
            <span className="mt-0.5 text-emerald-500">&#x2713;</span>
            Reference designators can be comma-separated (e.g., R1,R2,R3)
          </li>
        </ul>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2: Column Mapping
// ---------------------------------------------------------------------------

function MappingStep({
  mappings,
  missingRequired,
  onUpdateMapping,
  onProceed,
  onBack,
}: {
  mappings: ColumnMapping[];
  missingRequired: BomTargetField[];
  onUpdateMapping: (sourceColumn: string, targetField: string) => void;
  onProceed: () => void;
  onBack: () => void;
}) {
  // Check for duplicate target assignments
  const targetCounts = new Map<string, number>();
  for (const m of mappings) {
    if (m.targetField) {
      targetCounts.set(m.targetField, (targetCounts.get(m.targetField) ?? 0) + 1);
    }
  }
  const hasDuplicateTargets = Array.from(targetCounts.values()).some((c) => c > 1);

  return (
    <div className="space-y-4" data-testid="bom-import-step-mapping">
      <div className="mb-2 text-center">
        <h3 className="text-lg font-semibold text-gray-800">Map Columns</h3>
        <p className="mt-1 text-sm text-gray-500">
          We auto-detected column mappings. Review and adjust if needed.
        </p>
      </div>

      {/* Missing required fields warning */}
      {missingRequired.length > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-100 bg-amber-50 p-3">
          <svg
            className="mt-0.5 h-5 w-5 shrink-0 text-amber-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <div>
            <p className="text-sm font-medium text-amber-700">Required fields not mapped</p>
            <p className="mt-0.5 text-xs text-amber-600">
              {missingRequired.map((f) => f.label).join(', ')}
            </p>
          </div>
        </div>
      )}

      {/* Duplicate target warning */}
      {hasDuplicateTargets && (
        <div className="flex items-start gap-2 rounded-lg border border-red-100 bg-red-50 p-3">
          <svg
            className="mt-0.5 h-5 w-5 shrink-0 text-red-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <p className="text-sm text-red-700">
            Multiple source columns are mapped to the same target field. Each target field should
            only be used once.
          </p>
        </div>
      )}

      {/* Mapping table */}
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50">
              <th className="px-4 py-2.5 text-left text-xs font-semibold tracking-wider text-gray-600 uppercase">
                Source Column
              </th>
              <th className="w-16 px-4 py-2.5 text-center text-xs font-semibold tracking-wider text-gray-600 uppercase">
                &rarr;
              </th>
              <th className="px-4 py-2.5 text-left text-xs font-semibold tracking-wider text-gray-600 uppercase">
                Maps To
              </th>
              <th className="w-24 px-4 py-2.5 text-center text-xs font-semibold tracking-wider text-gray-600 uppercase">
                Confidence
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {mappings.map((m) => {
              const isDuplicate = m.targetField && (targetCounts.get(m.targetField) ?? 0) > 1;
              return (
                <tr
                  key={m.sourceColumn}
                  className={
                    isDuplicate
                      ? 'bg-red-50'
                      : m.targetField
                        ? 'bg-emerald-50/40'
                        : 'bg-amber-50/40'
                  }
                >
                  <td className="px-4 py-2.5">
                    <span className="font-mono text-xs text-gray-700">{m.sourceColumn}</span>
                  </td>
                  <td className="px-4 py-2.5 text-center text-gray-400">
                    <svg
                      className="inline h-4 w-4"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M14 5l7 7m0 0l-7 7m7-7H3"
                      />
                    </svg>
                  </td>
                  <td className="px-4 py-2.5">
                    <select
                      value={m.targetField}
                      onChange={(e) => onUpdateMapping(m.sourceColumn, e.target.value)}
                      className={`w-full rounded-md border px-2 py-1.5 text-xs outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-300 ${m.targetField ? 'border-emerald-300 bg-white' : 'border-amber-300 bg-white'} ${isDuplicate ? 'border-red-400 bg-red-50' : ''} `}
                      data-testid={`bom-mapping-select-${m.sourceColumn}`}
                    >
                      <option value="">(skip - do not import)</option>
                      {BOM_LINE_FIELDS.map((f) => (
                        <option key={f.code} value={f.code}>
                          {f.label} {f.required ? '*' : ''}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="px-4 py-2.5 text-center">
                    {m.targetField ? (
                      <ConfidenceBadge confidence={m.confidence} />
                    ) : (
                      <span className="text-xs text-gray-400">-</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Legend */}
      <div className="flex items-center gap-4 text-xs text-gray-500">
        <span className="flex items-center gap-1">
          <span className="inline-block h-3 w-3 rounded border border-emerald-300 bg-emerald-100" />
          Mapped
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block h-3 w-3 rounded border border-amber-300 bg-amber-100" />
          Unmapped (will be skipped)
        </span>
        <span className="flex items-center gap-1">
          <span className="font-bold text-red-500">*</span>
          Required
        </span>
      </div>

      {/* Actions */}
      <div className="flex justify-between pt-2">
        <button
          type="button"
          onClick={onBack}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 hover:text-gray-800"
        >
          &larr; Back
        </button>
        <button
          type="button"
          onClick={onProceed}
          disabled={missingRequired.length > 0 || hasDuplicateTargets}
          className={`rounded-lg px-5 py-2 text-sm font-medium transition-colors ${
            missingRequired.length > 0 || hasDuplicateTargets
              ? 'cursor-not-allowed bg-gray-200 text-gray-400'
              : 'bg-blue-600 text-white shadow-sm hover:bg-blue-700'
          } `}
          data-testid="bom-mapping-next"
        >
          Next: Preview &rarr;
        </button>
      </div>
    </div>
  );
}

function ConfidenceBadge({ confidence }: { confidence: number }) {
  const pct = Math.round(confidence * 100);
  let color = 'bg-gray-100 text-gray-600';
  if (pct >= 90) color = 'bg-emerald-100 text-emerald-700';
  else if (pct >= 70) color = 'bg-blue-100 text-blue-700';
  else if (pct >= 50) color = 'bg-amber-100 text-amber-700';

  return (
    <span
      className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium ${color}`}
    >
      {pct}%
    </span>
  );
}

// ---------------------------------------------------------------------------
// Step 3: Preview & Validate
// ---------------------------------------------------------------------------

const PREVIEW_PAGE_SIZE = 20;

function PreviewStep({
  rows,
  mappings,
  validRowCount,
  errorRowCount,
  onToggleExclude,
  onProceed,
  onBack,
}: {
  rows: ParsedRow[];
  mappings: ColumnMapping[];
  validRowCount: number;
  errorRowCount: number;
  onToggleExclude: (rowIndex: number) => void;
  onProceed: () => void;
  onBack: () => void;
}) {
  const [page, setPage] = useState(0);
  const [filterMode, setFilterMode] = useState<'all' | 'errors' | 'warnings'>('all');

  const mappedFields = useMemo(
    () =>
      mappings
        .filter((m) => m.targetField)
        .map((m) => ({
          sourceColumn: m.sourceColumn,
          targetField: m.targetField,
          label: BOM_LINE_FIELDS.find((f) => f.code === m.targetField)?.label ?? m.targetField,
        })),
    [mappings],
  );

  const filteredRows = useMemo(() => {
    if (filterMode === 'errors') return rows.filter((r) => r.errors.length > 0);
    if (filterMode === 'warnings') return rows.filter((r) => r.warnings.length > 0);
    return rows;
  }, [rows, filterMode]);

  const totalPages = Math.ceil(filteredRows.length / PREVIEW_PAGE_SIZE);
  const pageRows = filteredRows.slice(page * PREVIEW_PAGE_SIZE, (page + 1) * PREVIEW_PAGE_SIZE);

  // Reset page when filter changes
  useEffect(() => setPage(0), [filterMode]);

  return (
    <div className="space-y-4" data-testid="bom-import-step-preview">
      <div className="mb-2 text-center">
        <h3 className="text-lg font-semibold text-gray-800">Preview & Validate</h3>
        <p className="mt-1 text-sm text-gray-500">Review the parsed data before importing.</p>
      </div>

      {/* Summary bar */}
      <div className="flex flex-wrap items-center gap-4">
        <SummaryBadge label="Total rows" count={rows.length} color="bg-gray-100 text-gray-700" />
        <SummaryBadge label="Valid" count={validRowCount} color="bg-emerald-100 text-emerald-700" />
        {errorRowCount > 0 && (
          <SummaryBadge label="Errors" count={errorRowCount} color="bg-red-100 text-red-700" />
        )}
        <SummaryBadge
          label="Excluded"
          count={rows.filter((r) => r.excluded).length}
          color="bg-gray-100 text-gray-500"
        />

        {/* Filter buttons */}
        <div className="ml-auto flex items-center gap-1">
          {(['all', 'errors', 'warnings'] as const).map((mode) => (
            <button
              key={mode}
              type="button"
              onClick={() => setFilterMode(mode)}
              className={`rounded-md px-2.5 py-1 text-xs transition-colors ${
                filterMode === mode
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {mode === 'all' ? 'All' : mode === 'errors' ? 'Errors only' : 'Warnings only'}
            </button>
          ))}
        </div>
      </div>

      {/* Data table */}
      <div className="overflow-x-auto rounded-lg border border-gray-200">
        <table className="w-full text-xs">
          <thead>
            <tr className="bg-gray-50">
              <th className="w-10 px-2 py-2 text-center text-[10px] font-semibold text-gray-500 uppercase">
                #
              </th>
              <th className="w-12 px-2 py-2 text-center text-[10px] font-semibold text-gray-500 uppercase">
                Include
              </th>
              {mappedFields.map((f) => (
                <th
                  key={f.targetField}
                  className="px-3 py-2 text-left text-[10px] font-semibold whitespace-nowrap text-gray-500 uppercase"
                >
                  {f.label}
                </th>
              ))}
              <th className="w-48 px-3 py-2 text-left text-[10px] font-semibold text-gray-500 uppercase">
                Status
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {pageRows.map((row) => (
              <tr
                key={row.rowIndex}
                className={` ${
                  row.excluded
                    ? 'bg-gray-50 opacity-50'
                    : row.errors.length > 0
                      ? 'bg-red-50/50'
                      : row.warnings.length > 0
                        ? 'bg-amber-50/50'
                        : 'hover:bg-gray-50'
                } `}
              >
                <td className="px-2 py-1.5 text-center font-mono text-gray-400">{row.rowIndex}</td>
                <td className="px-2 py-1.5 text-center">
                  <input
                    type="checkbox"
                    checked={!row.excluded}
                    onChange={() => onToggleExclude(row.rowIndex)}
                    className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    data-testid={`bom-preview-toggle-${row.rowIndex}`}
                  />
                </td>
                {mappedFields.map((f) => (
                  <td
                    key={f.targetField}
                    className="max-w-[200px] truncate px-3 py-1.5 text-gray-700"
                    title={row.mapped[f.targetField] ?? ''}
                  >
                    {row.mapped[f.targetField] || <span className="text-gray-300">-</span>}
                  </td>
                ))}
                <td className="px-3 py-1.5">
                  {row.errors.length > 0 && (
                    <div className="space-y-0.5">
                      {row.errors.map((e, i) => (
                        <span
                          key={i}
                          className="mr-1 inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-[10px] font-medium text-red-700"
                        >
                          {e}
                        </span>
                      ))}
                    </div>
                  )}
                  {row.warnings.length > 0 && (
                    <div className="mt-0.5 space-y-0.5">
                      {row.warnings.map((w, i) => (
                        <span
                          key={i}
                          className="mr-1 inline-flex items-center rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium text-amber-700"
                        >
                          {w}
                        </span>
                      ))}
                    </div>
                  )}
                  {row.errors.length === 0 && row.warnings.length === 0 && (
                    <span className="inline-flex items-center rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700">
                      OK
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="rounded border border-gray-200 px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
          >
            Prev
          </button>
          <span className="text-xs text-gray-500">
            Page {page + 1} of {totalPages}
          </span>
          <button
            type="button"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
            className="rounded border border-gray-200 px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}

      {/* Actions */}
      <div className="flex justify-between pt-2">
        <button
          type="button"
          onClick={onBack}
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 hover:text-gray-800"
        >
          &larr; Back
        </button>
        <button
          type="button"
          onClick={onProceed}
          disabled={validRowCount === 0}
          className={`rounded-lg px-5 py-2 text-sm font-medium transition-colors ${
            validRowCount === 0
              ? 'cursor-not-allowed bg-gray-200 text-gray-400'
              : 'bg-blue-600 text-white shadow-sm hover:bg-blue-700'
          } `}
          data-testid="bom-preview-next"
        >
          Next: Import ({validRowCount} rows) &rarr;
        </button>
      </div>
    </div>
  );
}

function SummaryBadge({ label, count, color }: { label: string; count: number; color: string }) {
  return (
    <div
      className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium ${color}`}
    >
      <span className="font-bold">{count}</span>
      <span>{label}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 4: Import
// ---------------------------------------------------------------------------

function ImportStep({
  bomName,
  bomVersion,
  bomOutputQty,
  bomRemark,
  onBomNameChange,
  onBomVersionChange,
  onBomOutputQtyChange,
  onBomRemarkChange,
  validRowCount,
  importing,
  importDone,
  progress,
  createdBomId,
  onExecute,
  onBack,
  onComplete,
}: {
  bomName: string;
  bomVersion: string;
  bomOutputQty: string;
  bomRemark: string;
  onBomNameChange: (v: string) => void;
  onBomVersionChange: (v: string) => void;
  onBomOutputQtyChange: (v: string) => void;
  onBomRemarkChange: (v: string) => void;
  validRowCount: number;
  importing: boolean;
  importDone: boolean;
  progress: ImportProgress | null;
  createdBomId: string | null;
  onExecute: () => void;
  onBack: () => void;
  onComplete: () => void;
}) {
  const progressPct = progress
    ? Math.round(((progress.completed + progress.failed) / Math.max(progress.total, 1)) * 100)
    : 0;

  const isSuccess = importDone && progress && progress.failed === 0;
  const isPartial = importDone && progress && progress.failed > 0 && progress.completed > 0;

  return (
    <div className="space-y-5" data-testid="bom-import-step-import">
      {!importDone && !importing && (
        <>
          <div className="mb-2 text-center">
            <h3 className="text-lg font-semibold text-gray-800">BOM Details & Import</h3>
            <p className="mt-1 text-sm text-gray-500">
              Fill in the BOM header information and start the import.
            </p>
          </div>

          {/* BOM header form */}
          <div className="grid grid-cols-1 gap-4 rounded-lg border border-gray-200 bg-gray-50 p-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">
                BOM Name <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={bomName}
                onChange={(e) => onBomNameChange(e.target.value)}
                placeholder="e.g., Main Board BOM v1.0"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-300"
                data-testid="bom-import-name"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">Version</label>
              <input
                type="text"
                value={bomVersion}
                onChange={(e) => onBomVersionChange(e.target.value)}
                placeholder="e.g., 1.0"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-300"
                data-testid="bom-import-version"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">
                Output Quantity
              </label>
              <input
                type="number"
                value={bomOutputQty}
                onChange={(e) => onBomOutputQtyChange(e.target.value)}
                min="1"
                placeholder="1"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-300"
                data-testid="bom-import-output-qty"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600">Remark</label>
              <input
                type="text"
                value={bomRemark}
                onChange={(e) => onBomRemarkChange(e.target.value)}
                placeholder="Optional notes"
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-300"
                data-testid="bom-import-remark"
              />
            </div>
          </div>

          {/* Import summary */}
          <div className="rounded-lg border border-blue-100 bg-blue-50 p-3 text-sm text-blue-700">
            Ready to import <strong>{validRowCount}</strong> BOM line(s) into a new BOM.
          </div>

          {/* Actions */}
          <div className="flex justify-between">
            <button
              type="button"
              onClick={onBack}
              className="rounded-lg border border-gray-300 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 hover:text-gray-800"
            >
              &larr; Back
            </button>
            <button
              type="button"
              onClick={onExecute}
              disabled={!bomName.trim() || validRowCount === 0}
              className={`rounded-lg px-6 py-2.5 text-sm font-semibold transition-all ${
                !bomName.trim() || validRowCount === 0
                  ? 'cursor-not-allowed bg-gray-200 text-gray-400'
                  : 'bg-emerald-600 text-white shadow-md hover:bg-emerald-700 hover:shadow-lg'
              } `}
              data-testid="bom-import-execute"
            >
              Import BOM
            </button>
          </div>
        </>
      )}

      {/* Progress (during import) */}
      {(importing || importDone) && progress && (
        <div className="space-y-4">
          <div className="text-center">
            <h3 className="text-lg font-semibold text-gray-800">
              {importing ? 'Importing...' : isSuccess ? 'Import Complete!' : 'Import Finished'}
            </h3>
          </div>

          {/* Progress bar */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs text-gray-500">
              <span>
                {progress.completed + progress.failed} / {progress.total} rows processed
              </span>
              <span>{progressPct}%</span>
            </div>
            <div className="h-3 overflow-hidden rounded-full bg-gray-200">
              <div
                className={`h-full rounded-full transition-all duration-300 ${
                  progress.failed > 0 ? 'bg-amber-500' : 'bg-emerald-500'
                }`}
                style={{ width: `${progressPct}%` }}
              />
            </div>
          </div>

          {/* Stats */}
          <div className="flex items-center justify-center gap-4">
            <SummaryBadge
              label="Succeeded"
              count={progress.completed}
              color="bg-emerald-100 text-emerald-700"
            />
            {progress.failed > 0 && (
              <SummaryBadge
                label="Failed"
                count={progress.failed}
                color="bg-red-100 text-red-700"
              />
            )}
          </div>

          {/* Error details */}
          {progress.errors.length > 0 && (
            <div className="max-h-40 overflow-y-auto rounded-lg border border-red-100 bg-red-50 p-3">
              <p className="mb-1 text-xs font-semibold text-red-700">Errors:</p>
              <ul className="space-y-0.5">
                {progress.errors.map((err, i) => (
                  <li key={i} className="text-xs text-red-600">
                    {err}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {/* Success message */}
          {isSuccess && (
            <div className="flex items-center gap-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4">
              <svg
                className="h-8 w-8 shrink-0 text-emerald-500"
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
              <div>
                <p className="text-sm font-semibold text-emerald-700">
                  BOM created successfully with {progress.completed} line(s)!
                </p>
                {createdBomId && (
                  <p className="mt-0.5 text-xs text-emerald-600">BOM ID: {createdBomId}</p>
                )}
              </div>
            </div>
          )}

          {/* Partial success */}
          {isPartial && (
            <div className="flex items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 p-4">
              <svg
                className="h-8 w-8 shrink-0 text-amber-500"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <div>
                <p className="text-sm font-semibold text-amber-700">
                  BOM created with {progress.completed} of {progress.total} lines.
                  {progress.failed} line(s) failed.
                </p>
              </div>
            </div>
          )}

          {/* Done actions */}
          {importDone && (
            <div className="flex justify-center pt-2">
              <button
                type="button"
                onClick={onComplete}
                className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-semibold text-white shadow-md transition-all hover:bg-blue-700"
                data-testid="bom-import-done"
              >
                {isSuccess ? 'View BOM' : 'Close'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
