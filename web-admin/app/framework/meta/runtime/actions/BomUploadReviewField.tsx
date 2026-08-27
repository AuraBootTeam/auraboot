import { useCallback, useMemo, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

type LocalizedText = string | Record<string, string>;

interface RoleOption {
  value: string;
  label: LocalizedText;
}

interface ColumnChoice {
  index: number;
  header: string;
  role: string;
  selected: boolean;
}

export interface BomUploadReviewValue {
  file: File;
  valid: boolean;
  validationMessage?: string;
  payload: {
    bom_sheet_name: string;
    bom_header_row_index: number;
    bom_selected_columns: Array<{ index: number; header: string; role: string }>;
  };
}

interface ParsedWorkbook {
  workbook: any;
  sheetNames: string[];
}

interface BomUploadReviewFieldProps {
  fieldName: string;
  accept?: string;
  maxBytes?: number;
  roleOptions?: RoleOption[];
  previewRowCount?: number;
  onChange: (value: BomUploadReviewValue | null) => void;
}

const DEFAULT_ROLES: RoleOption[] = [
  { value: 'mpn', label: { 'zh-CN': '型号 / MPN', en: 'MPN' } },
  { value: 'manufacturer', label: { 'zh-CN': '厂商 / 品牌', en: 'Manufacturer' } },
  { value: 'spec', label: { 'zh-CN': '规格描述', en: 'Specification' } },
  { value: 'quantity', label: { 'zh-CN': '单套用量', en: 'Quantity' } },
  { value: 'package', label: { 'zh-CN': '封装', en: 'Package' } },
  { value: 'refdes', label: { 'zh-CN': '位号', en: 'Reference' } },
  { value: 'combined_material', label: { 'zh-CN': '组合物料信息', en: 'Combined material' } },
  { value: 'other', label: { 'zh-CN': '其他识别信息', en: 'Other signal' } },
  { value: 'ignore', label: { 'zh-CN': '忽略', en: 'Ignore' } },
];

function text(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value).trim();
}

function normalize(value: string): string {
  return value.toLocaleLowerCase().replace(/[\s_.\-/\\()[\]（）【】]+/g, '');
}

function suggestedRole(header: string): string {
  const value = normalize(header);
  if (!value) return 'ignore';
  if (/型号.*厂|厂.*型号|物料信息|part.*maker/.test(value)) return 'combined_material';
  if (/数量|用量|qty|quantity|count/.test(value)) return 'quantity';
  if (/制造商|厂商|厂家|品牌|manufacturer|maker|brand|mfr/.test(value)) return 'manufacturer';
  if (/封装|footprint|package|pkg|casesize/.test(value)) return 'package';
  if (/位号|位置|refdes|reference|designator|placement/.test(value)) return 'refdes';
  if (/物料编码|料号|型号|mpn|partno|partnumber|mfrpart/.test(value)) return 'mpn';
  if (/规格|描述|参数|品名|名称|description|spec|value/.test(value)) return 'spec';
  return 'other';
}

function filledCount(row: string[]): number {
  return row.filter((cell) => text(cell) !== '').length;
}

function detectHeaderRow(rows: string[][]): number {
  const limit = Math.min(rows.length, 20);
  let bestIndex = 0;
  let bestScore = -1;
  for (let index = 0; index < limit; index += 1) {
    const row = rows[index] || [];
    const semantic = row.filter(
      (cell) => !['other', 'ignore'].includes(suggestedRole(cell)),
    ).length;
    const score = semantic * 100 + filledCount(row);
    if (score > bestScore) {
      bestIndex = index;
      bestScore = score;
    }
  }
  return bestIndex;
}

function columnsFor(rows: string[][], headerRowIndex: number): ColumnChoice[] {
  const header = rows[headerRowIndex] || [];
  let width = header.length;
  for (const row of rows.slice(headerRowIndex + 1, headerRowIndex + 11)) {
    width = Math.max(width, row?.length || 0);
  }
  return Array.from({ length: width }, (_, index) => {
    const rawHeader = text(header[index]);
    const role = suggestedRole(rawHeader);
    return {
      index,
      header: rawHeader || `Column ${index + 1}`,
      role,
      selected: role !== 'ignore' && role !== 'other',
    };
  });
}

function validationOf(columns: ColumnChoice[], locale: string): string {
  const selected = columns.filter((column) => column.selected && column.role !== 'ignore');
  const quantityCount = selected.filter((column) => column.role === 'quantity').length;
  const identityCount = selected.filter((column) =>
    ['mpn', 'spec', 'combined_material'].includes(column.role),
  ).length;
  if (quantityCount !== 1) {
    return locale.startsWith('zh')
      ? '必须且只能选择一个“单套用量”列。'
      : 'Select exactly one quantity column.';
  }
  if (identityCount < 1) {
    return locale.startsWith('zh')
      ? '至少选择一个型号、规格描述或组合物料信息列。'
      : 'Select at least one MPN, specification, or combined-material column.';
  }
  return '';
}

export default function BomUploadReviewField({
  fieldName,
  accept = '.xlsx,.xls,.csv',
  maxBytes,
  roleOptions = DEFAULT_ROLES,
  previewRowCount = 10,
  onChange,
}: BomUploadReviewFieldProps) {
  const { locale, t } = useI18n();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [parsed, setParsed] = useState<ParsedWorkbook | null>(null);
  const [sheetName, setSheetName] = useState('');
  const [rows, setRows] = useState<string[][]>([]);
  const [headerRowIndex, setHeaderRowIndex] = useState(0);
  const [columns, setColumns] = useState<ColumnChoice[]>([]);
  const [parseError, setParseError] = useState('');

  const publish = useCallback(
    (nextFile: File | null, nextSheet: string, nextHeader: number, nextColumns: ColumnChoice[]) => {
      if (!nextFile) {
        onChange(null);
        return;
      }
      const validationMessage = validationOf(nextColumns, locale);
      onChange({
        file: nextFile,
        valid: validationMessage === '',
        validationMessage,
        payload: {
          bom_sheet_name: nextSheet,
          bom_header_row_index: nextHeader,
          bom_selected_columns: nextColumns
            .filter((column) => column.selected && column.role !== 'ignore')
            .map(({ index, header, role }) => ({ index, header, role })),
        },
      });
    },
    [locale, onChange],
  );

  const selectSheet = useCallback(
    (workbook: any, nextSheet: string, nextFile: File) => {
      const XLSX = workbook.__xlsx;
      const rawRows = XLSX.utils.sheet_to_json(workbook.Sheets[nextSheet], {
        header: 1,
        defval: '',
        raw: false,
        blankrows: true,
      }) as unknown[][];
      const stringRows = rawRows.map((row) =>
        (Array.isArray(row) ? row : []).map((cell) => text(cell)),
      );
      const detectedHeader = detectHeaderRow(stringRows);
      const detectedColumns = columnsFor(stringRows, detectedHeader);
      setSheetName(nextSheet);
      setRows(stringRows);
      setHeaderRowIndex(detectedHeader);
      setColumns(detectedColumns);
      publish(nextFile, nextSheet, detectedHeader, detectedColumns);
    },
    [publish],
  );

  const parseFile = useCallback(
    async (nextFile?: File) => {
      if (!nextFile) return;
      setParseError('');
      if (maxBytes && nextFile.size > maxBytes) {
        setParseError(
          locale.startsWith('zh')
            ? `文件不能超过 ${Math.ceil(maxBytes / 1024 / 1024)} MB。`
            : `File must not exceed ${Math.ceil(maxBytes / 1024 / 1024)} MB.`,
        );
        return;
      }
      try {
        const XLSX = await import('xlsx');
        const workbook = XLSX.read(await nextFile.arrayBuffer(), {
          type: 'array',
          cellDates: true,
        });
        if (workbook.SheetNames.length === 0) throw new Error('Workbook has no worksheets');
        (workbook as any).__xlsx = XLSX;
        const nextParsed = { workbook, sheetNames: [...workbook.SheetNames] };
        setFile(nextFile);
        setParsed(nextParsed);
        selectSheet(workbook, workbook.SheetNames[0], nextFile);
      } catch (cause: any) {
        setParseError(
          locale.startsWith('zh')
            ? `无法读取 BOM：${cause?.message || '文件格式错误'}`
            : `Unable to read BOM: ${cause?.message || 'invalid file format'}`,
        );
        onChange(null);
      }
    },
    [locale, maxBytes, onChange, selectSheet],
  );

  const updateHeader = (nextHeader: number) => {
    const nextColumns = columnsFor(rows, nextHeader);
    setHeaderRowIndex(nextHeader);
    setColumns(nextColumns);
    publish(file, sheetName, nextHeader, nextColumns);
  };

  const updateColumn = (index: number, patch: Partial<ColumnChoice>) => {
    const nextColumns = columns.map((column) =>
      column.index === index ? { ...column, ...patch } : column,
    );
    setColumns(nextColumns);
    publish(file, sheetName, headerRowIndex, nextColumns);
  };

  const selectedColumns = columns.filter((column) => column.selected && column.role !== 'ignore');
  const validationMessage = file ? validationOf(columns, locale) : '';
  const previewRows = useMemo(
    () =>
      rows
        .slice(headerRowIndex + 1)
        .filter((row) => filledCount(row) > 0)
        .slice(0, previewRowCount),
    [headerRowIndex, previewRowCount, rows],
  );

  return (
    <div className="space-y-4" data-testid={`bom-upload-review-${fieldName}`}>
      <div
        className="rounded-card border-border-strong bg-subtle hover:border-accent border-2 border-dashed p-4 text-center"
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          void parseFile(event.dataTransfer.files?.[0]);
        }}
      >
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          data-testid={`bom-upload-review-file-${fieldName}`}
          className="sr-only"
          onChange={(event) => {
            void parseFile(event.target.files?.[0]);
            event.target.value = '';
          }}
        />
        <button
          type="button"
          className="rounded-control bg-accent px-4 py-2 text-sm font-medium text-white"
          onClick={() => inputRef.current?.click()}
        >
          {file
            ? locale.startsWith('zh')
              ? '重新选择文件'
              : 'Choose another file'
            : locale.startsWith('zh')
              ? '选择 BOM 文件'
              : 'Choose BOM file'}
        </button>
        <div className="text-text-2 mt-2 text-sm" data-testid="bom-upload-review-filename">
          {file?.name ||
            (locale.startsWith('zh') ? '也可将文件拖到这里' : 'or drag and drop it here')}
        </div>
      </div>

      {parseError && <p className="text-status-red text-sm">{parseError}</p>}

      {parsed && file && (
        <>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="text-text-2 text-sm">
              <span className="mb-1 block">{locale.startsWith('zh') ? '工作表' : 'Worksheet'}</span>
              <div
                data-testid="bom-upload-review-sheet"
                className="rounded-control border-border bg-subtle text-text w-full border px-3 py-2"
              >
                {sheetName}
                {parsed.sheetNames.length > 1 && (
                  <span className="text-text-3 ml-2 text-xs">
                    {locale.startsWith('zh')
                      ? '本期读取第一个工作表'
                      : 'The first worksheet is used'}
                  </span>
                )}
              </div>
            </div>
            <label className="text-text-2 text-sm">
              <span className="mb-1 block">
                {locale.startsWith('zh') ? '表头所在行' : 'Header row'}
              </span>
              <select
                value={headerRowIndex}
                data-testid="bom-upload-review-header-row"
                className="rounded-control border-border bg-panel text-text w-full border px-3 py-2"
                onChange={(event) => updateHeader(Number(event.target.value))}
              >
                {rows.slice(0, 20).map((row, index) => (
                  <option key={index} value={index}>
                    {locale.startsWith('zh') ? `第 ${index + 1} 行` : `Row ${index + 1}`} ·{' '}
                    {row.filter(Boolean).slice(0, 3).join(' / ') || '—'}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div>
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <div>
                <h4 className="text-text text-sm font-semibold">
                  {locale.startsWith('zh')
                    ? `原始 BOM 前 ${previewRowCount} 行`
                    : `First ${previewRowCount} raw BOM rows`}
                </h4>
                <p className="text-text-3 text-xs">
                  {locale.startsWith('zh')
                    ? '勾选需要发送给渠道的列，并明确每列语义。未勾选列仍保留在原文件中。'
                    : 'Choose the columns sent to the provider and assign their meaning. Unselected columns remain in the original file.'}
                </p>
              </div>
              <span className="rounded-full bg-blue-50 px-2.5 py-1 text-xs font-medium text-blue-700">
                {locale.startsWith('zh')
                  ? `已选 ${selectedColumns.length} 列`
                  : `${selectedColumns.length} selected`}
              </span>
            </div>
            <div
              className="border-border max-h-[42vh] overflow-auto rounded-lg border"
              data-testid="bom-upload-review-grid"
            >
              <table className="min-w-max border-collapse text-xs">
                <thead className="bg-subtle sticky top-0 z-10">
                  <tr>
                    <th className="border-border text-text-3 sticky left-0 z-20 w-12 border-r border-b bg-inherit px-2 py-2">
                      #
                    </th>
                    {columns.map((column) => (
                      <th
                        key={column.index}
                        className="border-border min-w-44 border-r border-b p-2 align-top last:border-r-0"
                      >
                        <label className="text-text flex items-center gap-2 font-semibold">
                          <input
                            type="checkbox"
                            checked={column.selected}
                            data-testid={`bom-upload-review-column-${column.index}`}
                            onChange={(event) =>
                              updateColumn(column.index, { selected: event.target.checked })
                            }
                          />
                          <span className="max-w-32 truncate" title={column.header}>
                            {column.header}
                          </span>
                        </label>
                        <select
                          value={column.role}
                          disabled={!column.selected}
                          data-testid={`bom-upload-review-role-${column.index}`}
                          className="rounded-control border-border bg-panel text-text mt-2 w-full border px-2 py-1 disabled:opacity-50"
                          onChange={(event) =>
                            updateColumn(column.index, {
                              role: event.target.value,
                              selected: event.target.value !== 'ignore',
                            })
                          }
                        >
                          {roleOptions.map((option) => (
                            <option key={option.value} value={option.value}>
                              {getLocalizedText(option.label, locale, t)}
                            </option>
                          ))}
                        </select>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {previewRows.map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      <td className="border-border bg-panel text-text-3 sticky left-0 border-r border-b px-2 py-2 text-center">
                        {headerRowIndex + rowIndex + 2}
                      </td>
                      {columns.map((column) => (
                        <td
                          key={column.index}
                          className={`border-border text-text max-w-64 border-r border-b px-2 py-2 align-top [overflow-wrap:anywhere] last:border-r-0 ${column.selected ? 'bg-blue-50/40' : 'bg-panel opacity-60'}`}
                        >
                          {text(row[column.index]) || '—'}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <section
            className="rounded-card border-border bg-subtle border p-3"
            data-testid="bom-upload-review-projection"
          >
            <h4 className="text-text text-sm font-semibold">
              {locale.startsWith('zh') ? '将发送给云汉的列' : 'Columns sent to Yunhan'}
            </h4>
            <div className="mt-2 flex flex-wrap gap-2">
              {selectedColumns.map((column) => (
                <span
                  key={column.index}
                  className="rounded-full border border-blue-200 bg-blue-50 px-2.5 py-1 text-xs text-blue-800"
                >
                  {column.header} ·{' '}
                  {getLocalizedText(
                    roleOptions.find((role) => role.value === column.role)?.label || column.role,
                    locale,
                    t,
                  )}
                </span>
              ))}
              {selectedColumns.length === 0 && <span className="text-status-red text-xs">—</span>}
            </div>
          </section>

          {validationMessage && (
            <p className="text-status-red text-sm" data-testid="bom-upload-review-validation">
              {validationMessage}
            </p>
          )}
        </>
      )}
    </div>
  );
}
