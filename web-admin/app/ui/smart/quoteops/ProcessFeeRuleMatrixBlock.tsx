import React from 'react';
import { AlertTriangle, CheckCircle2, ClipboardPaste, Plus, Save, Trash2 } from 'lucide-react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import { fetchResult } from '~/shared/services/http-client';
import {
  readDataSourceRows,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
} from '~/framework/meta/rendering/blocks/workbenchBlockUtils';

type RuleField =
  | 'qo_pfrl_process_stage'
  | 'qo_pfrl_component_type'
  | 'qo_pfrl_min_qty'
  | 'qo_pfrl_max_qty'
  | 'qo_pfrl_unit_price'
  | 'qo_pfrl_point_count'
  | 'qo_pfrl_min_charge'
  | 'qo_pfrl_note';

interface MatrixRow {
  __rowId: string;
  pid?: string;
  qo_pfrl_process_stage: string;
  qo_pfrl_component_type: string;
  qo_pfrl_min_qty: string;
  qo_pfrl_max_qty: string;
  qo_pfrl_unit_price: string;
  qo_pfrl_point_count: string;
  qo_pfrl_min_charge: string;
  qo_pfrl_note: string;
}

interface ValidationIssue {
  rowId: string;
  message: string;
}

const COLUMNS: Array<{
  field: RuleField;
  label: string;
  width: string;
  align?: 'right';
}> = [
  { field: 'qo_pfrl_process_stage', label: '工序', width: '140px' },
  { field: 'qo_pfrl_component_type', label: '元件类型', width: '170px' },
  { field: 'qo_pfrl_min_qty', label: '最小数量', width: '110px', align: 'right' },
  { field: 'qo_pfrl_max_qty', label: '最大数量', width: '110px', align: 'right' },
  { field: 'qo_pfrl_unit_price', label: '单价', width: '110px', align: 'right' },
  { field: 'qo_pfrl_point_count', label: '计费点数', width: '110px', align: 'right' },
  { field: 'qo_pfrl_min_charge', label: '最低收费', width: '110px', align: 'right' },
  { field: 'qo_pfrl_note', label: '备注', width: '260px' },
];

const HEADER_ALIASES: Record<string, RuleField> = {
  工序: 'qo_pfrl_process_stage',
  工序段: 'qo_pfrl_process_stage',
  stage: 'qo_pfrl_process_stage',
  processstage: 'qo_pfrl_process_stage',
  元件类型: 'qo_pfrl_component_type',
  component: 'qo_pfrl_component_type',
  componenttype: 'qo_pfrl_component_type',
  最小数量: 'qo_pfrl_min_qty',
  minqty: 'qo_pfrl_min_qty',
  min: 'qo_pfrl_min_qty',
  最大数量: 'qo_pfrl_max_qty',
  maxqty: 'qo_pfrl_max_qty',
  max: 'qo_pfrl_max_qty',
  单价: 'qo_pfrl_unit_price',
  price: 'qo_pfrl_unit_price',
  unitprice: 'qo_pfrl_unit_price',
  计费点数: 'qo_pfrl_point_count',
  pointcount: 'qo_pfrl_point_count',
  points: 'qo_pfrl_point_count',
  最低收费: 'qo_pfrl_min_charge',
  mincharge: 'qo_pfrl_min_charge',
  备注: 'qo_pfrl_note',
  note: 'qo_pfrl_note',
  remark: 'qo_pfrl_note',
};

export interface ProcessFeeRuleMatrixBlockProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export function ProcessFeeRuleMatrixBlock({ block, runtime }: ProcessFeeRuleMatrixBlockProps) {
  const dataSource =
    typeof block.dataSource === 'string' ? block.dataSource : 'processFeeRuleLines';
  useDataSourceSubscription(runtime, dataSource);
  useRuntimeStateSubscription(runtime);

  const sourceRows = readDataSourceRows(runtime, dataSource);
  const selectedRuleSet = resolveRuntimeValue(runtime, (block as any).selectedRuleSet) || {};
  const quoteId = String(resolveRuntimeValue(runtime, (block as any).quoteId) || '');
  const saveCommand = String(
    (block as any).saveCommand || 'qo_quote_common:save_process_fee_rule_matrix',
  );
  const reloadIds = Array.isArray((block as any).reload) ? (block as any).reload : [];
  const [rows, setRows] = React.useState<MatrixRow[]>(() => toMatrixRows(sourceRows));
  const [stageFilter, setStageFilter] = React.useState('');
  const [searchText, setSearchText] = React.useState('');
  const [sortField, setSortField] = React.useState<RuleField | null>(null);
  const [sortDir, setSortDir] = React.useState<'asc' | 'desc'>('asc');
  const [pasteText, setPasteText] = React.useState('');
  const [saveState, setSaveState] = React.useState<{
    status: 'idle' | 'saving' | 'saved' | 'error';
    message: string;
  }>({
    status: 'idle',
    message: '',
  });
  const sourceRowsKey = JSON.stringify(sourceRows);
  const originalRows = React.useMemo(() => toMatrixRows(sourceRows), [sourceRowsKey]);

  React.useEffect(() => {
    setRows(originalRows);
    setSaveState({ status: 'idle', message: '' });
  }, [originalRows]);

  const issues = React.useMemo(() => validateRows(rows), [rows]);
  const originalSignature = React.useMemo(() => signature(originalRows), [originalRows]);
  const currentSignature = React.useMemo(() => signature(rows), [rows]);
  const dirty = originalSignature !== currentSignature;
  const dirtyRowCount = React.useMemo(
    () => countDirtyRows(originalRows, rows),
    [originalRows, rows],
  );
  const stageOptions = React.useMemo(
    () =>
      Array.from(new Set(rows.map((row) => row.qo_pfrl_process_stage).filter(Boolean))).sort(
        (a, b) => a.localeCompare(b, 'zh-CN'),
      ),
    [rows],
  );

  const visibleRows = React.useMemo(() => {
    const normalizedSearch = searchText.trim().toLowerCase();
    const filtered = rows.filter((row) => {
      const stageOk = !stageFilter || row.qo_pfrl_process_stage === stageFilter;
      const searchOk =
        !normalizedSearch ||
        COLUMNS.some((column) => row[column.field].toLowerCase().includes(normalizedSearch));
      return stageOk && searchOk;
    });
    if (!sortField) return filtered;
    return [...filtered].sort((a, b) => {
      const left = sortableValue(a[sortField]);
      const right = sortableValue(b[sortField]);
      const result =
        typeof left === 'number' && typeof right === 'number'
          ? left - right
          : String(left).localeCompare(String(right), 'zh-CN');
      return sortDir === 'asc' ? result : -result;
    });
  }, [rows, searchText, sortDir, sortField, stageFilter]);

  const issueByRow = React.useMemo(() => {
    const map = new Map<string, string[]>();
    issues.forEach((issue) => {
      const next = map.get(issue.rowId) || [];
      next.push(issue.message);
      map.set(issue.rowId, next);
    });
    return map;
  }, [issues]);

  const updateCell = (rowId: string, field: RuleField, value: string) => {
    setRows((current) =>
      current.map((row) => (row.__rowId === rowId ? { ...row, [field]: value } : row)),
    );
    setSaveState({ status: 'idle', message: '' });
  };

  const applyPaste = () => {
    const parsed = parsePastedRows(pasteText);
    if (parsed.length === 0) return;
    setRows(parsed);
    setPasteText('');
    setSaveState({ status: 'idle', message: `${parsed.length} 行已修改` });
  };

  const addRow = () => {
    setRows((current) => [...current, emptyRow(`new-${Date.now()}`)]);
    setSaveState({ status: 'idle', message: '' });
  };

  const removeRow = (rowId: string) => {
    setRows((current) => current.filter((row) => row.__rowId !== rowId));
    setSaveState({ status: 'idle', message: '' });
  };

  const saveMatrix = async () => {
    if (issues.length > 0 || saveState.status === 'saving') return;
    setSaveState({ status: 'saving', message: '' });
    const result = await fetchResult(`/api/meta/commands/execute/${saveCommand}`, {
      method: 'post',
      params: {
        targetRecordId: quoteId,
        operationType: 'SAVE',
        payload: {
          ruleSetId: selectedRuleSet.pid,
          rows: rows.map(toPayloadRow),
        },
      },
    });
    if (String((result as any).code ?? '') !== '0' && (result as any).success !== true) {
      setSaveState({
        status: 'error',
        message: (result as any).message || (result as any).desc || '保存失败',
      });
      return;
    }
    await runtime.getDataSourceManager?.().reload?.(reloadIds);
    const data = (result as any).data?.data ?? (result as any).data ?? {};
    const version = data.ruleVersion ? String(data.ruleVersion) : 'draft';
    setSaveState({
      status: 'saved',
      message: `已保存 ${data.savedLines ?? rows.length} 行 · ${version}`,
    });
  };

  const selectedVersion = String(selectedRuleSet.qo_pfrs_version || '-');
  const selectedStatus = String(selectedRuleSet.qo_pfrs_status || '-');
  const isPublished = selectedStatus === 'published';

  return (
    <section
      className="rounded-control bg-panel border border-slate-200"
      data-testid="process-fee-rule-matrix"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-4 py-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-base font-semibold text-slate-900">规则矩阵</h3>
            <StatusPill tone={isPublished ? 'blue' : 'amber'}>{selectedStatus}</StatusPill>
            {isPublished ? <StatusPill tone="slate">保存生成 draft</StatusPill> : null}
          </div>
          <div className="mt-1 flex flex-wrap gap-3 text-xs text-slate-500">
            <span>版本 {selectedVersion}</span>
            <span>{rows.length} 行</span>
            <span>{dirty ? `${dirtyRowCount} 行已修改` : '无变更'}</span>
            <span>{issues.length === 0 ? '校验通过' : `${issues.length} 个问题`}</span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="rounded-control inline-flex items-center border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            onClick={addRow}
          >
            <Plus className="mr-1.5 h-4 w-4" />
            新增行
          </button>
          <button
            type="button"
            data-testid="process-fee-save-matrix"
            disabled={issues.length > 0 || saveState.status === 'saving'}
            className="rounded-control bg-accent hover:bg-accent-hover inline-flex items-center px-3 py-1.5 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
            onClick={saveMatrix}
          >
            <Save className="mr-1.5 h-4 w-4" />
            {saveState.status === 'saving' ? '保存中' : '保存草稿'}
          </button>
        </div>
      </div>

      <div className="grid gap-3 border-b border-slate-200 px-4 py-3 lg:grid-cols-[180px_minmax(220px,1fr)_minmax(280px,1.2fr)_auto]">
        <label className="text-xs font-medium text-slate-600">
          工序筛选
          <select
            aria-label="工序筛选"
            value={stageFilter}
            onChange={(event) => setStageFilter(event.target.value)}
            className="rounded-control bg-panel focus-visible:shadow-focus mt-1 h-9 w-full border border-slate-200 px-2 text-sm text-slate-900 focus:outline-none"
          >
            <option value="">全部工序</option>
            {stageOptions.map((stage) => (
              <option value={stage} key={stage}>
                {stage}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs font-medium text-slate-600">
          关键字
          <input
            aria-label="关键字"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            className="rounded-control focus-visible:shadow-focus mt-1 h-9 w-full border border-slate-200 px-2 text-sm text-slate-900 focus:outline-none"
          />
        </label>
        <label className="text-xs font-medium text-slate-600">
          粘贴矩阵
          <textarea
            aria-label="粘贴矩阵"
            value={pasteText}
            onChange={(event) => setPasteText(event.target.value)}
            className="rounded-control focus-visible:shadow-focus mt-1 h-16 w-full resize-y border border-slate-200 px-2 py-1.5 font-mono text-xs text-slate-900 focus:outline-none"
          />
        </label>
        <button
          type="button"
          data-testid="process-fee-apply-paste"
          onClick={applyPaste}
          className="rounded-control mt-5 inline-flex h-9 items-center justify-center border border-slate-200 px-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
        >
          <ClipboardPaste className="mr-1.5 h-4 w-4" />
          应用粘贴
        </button>
      </div>

      {issues.length > 0 ? (
        <div className="flex items-start gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-800">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-1">
            {issues.slice(0, 4).map((issue, index) => (
              <div key={`${issue.rowId}-${index}`}>{issue.message}</div>
            ))}
            {issues.length > 4 ? <div>另有 {issues.length - 4} 个问题</div> : null}
          </div>
        </div>
      ) : (
        <div className="flex items-center gap-2 border-b border-emerald-100 bg-emerald-50 px-4 py-2 text-sm text-emerald-700">
          <CheckCircle2 className="h-4 w-4" />
          校验通过
        </div>
      )}

      <div className="max-h-[460px] overflow-auto">
        <table className="w-max min-w-full border-separate border-spacing-0 text-sm">
          <thead>
            <tr>
              {COLUMNS.map((column) => (
                <th
                  key={column.field}
                  className="sticky top-0 z-10 border-b border-slate-200 bg-slate-50 px-2 py-2 text-left text-xs font-semibold text-slate-600"
                  style={{ width: column.width, minWidth: column.width }}
                >
                  <button
                    type="button"
                    data-testid={
                      column.field === 'qo_pfrl_unit_price'
                        ? 'process-fee-sort-unit-price'
                        : undefined
                    }
                    className="hover:text-accent inline-flex items-center gap-1"
                    onClick={() => {
                      if (sortField === column.field) {
                        setSortDir((current) => (current === 'asc' ? 'desc' : 'asc'));
                      } else {
                        setSortField(column.field);
                        setSortDir('asc');
                      }
                    }}
                  >
                    {column.label}
                    {sortField === column.field ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}
                  </button>
                </th>
              ))}
              <th className="sticky top-0 z-10 w-[72px] border-b border-slate-200 bg-slate-50 px-2 py-2 text-left text-xs font-semibold text-slate-600">
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            {visibleRows.length === 0 ? (
              <tr>
                <td colSpan={COLUMNS.length + 1} className="px-4 py-8 text-center text-slate-500">
                  No data
                </td>
              </tr>
            ) : (
              visibleRows.map((row) => (
                <tr
                  key={row.__rowId}
                  className={
                    issueByRow.has(row.__rowId)
                      ? 'bg-status-amber-bg'
                      : 'odd:bg-panel even:bg-slate-50/60'
                  }
                >
                  {COLUMNS.map((column) => (
                    <td key={column.field} className="border-b border-slate-100 px-1 py-1">
                      <input
                        data-testid={`process-fee-cell-${column.field}`}
                        value={row[column.field]}
                        onChange={(event) =>
                          updateCell(row.__rowId, column.field, event.target.value)
                        }
                        className={`focus:bg-panel focus-visible:shadow-focus h-8 w-full rounded border border-transparent bg-transparent px-2 text-sm text-slate-900 focus:outline-none ${
                          column.align === 'right' ? 'text-right font-mono' : ''
                        }`}
                      />
                    </td>
                  ))}
                  <td className="border-b border-slate-100 px-2 py-1">
                    <button
                      type="button"
                      aria-label="删除规则行"
                      onClick={() => removeRow(row.__rowId)}
                      className="rounded-control inline-flex h-8 w-8 items-center justify-center text-slate-500 hover:bg-rose-50 hover:text-rose-700"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {saveState.message ? (
        <div
          className={`border-t px-4 py-2 text-sm ${
            saveState.status === 'error'
              ? 'border-rose-200 bg-rose-50 text-rose-700'
              : 'border-slate-200 bg-slate-50 text-slate-700'
          }`}
        >
          {saveState.message}
        </div>
      ) : null}
    </section>
  );
}

function StatusPill({
  children,
  tone,
}: {
  children: React.ReactNode;
  tone: 'amber' | 'blue' | 'slate';
}) {
  const cls = {
    amber: 'border-amber-200 bg-amber-50 text-amber-700',
    blue: 'border-blue-200 bg-blue-50 text-blue-700',
    slate: 'border-slate-200 bg-slate-50 text-slate-600',
  }[tone];
  return (
    <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${cls}`}>{children}</span>
  );
}

function toMatrixRows(rows: Array<Record<string, unknown>>): MatrixRow[] {
  return rows.map((row, index) => ({
    __rowId: String(row.pid || row.id || `row-${index}`),
    pid: row.pid == null ? undefined : String(row.pid),
    qo_pfrl_process_stage: value(row.qo_pfrl_process_stage),
    qo_pfrl_component_type: value(row.qo_pfrl_component_type),
    qo_pfrl_min_qty: value(row.qo_pfrl_min_qty),
    qo_pfrl_max_qty: value(row.qo_pfrl_max_qty),
    qo_pfrl_unit_price: value(row.qo_pfrl_unit_price),
    qo_pfrl_point_count: value(row.qo_pfrl_point_count),
    qo_pfrl_min_charge: value(row.qo_pfrl_min_charge),
    qo_pfrl_note: value(row.qo_pfrl_note),
  }));
}

function emptyRow(rowId: string): MatrixRow {
  return {
    __rowId: rowId,
    qo_pfrl_process_stage: '',
    qo_pfrl_component_type: '',
    qo_pfrl_min_qty: '',
    qo_pfrl_max_qty: '',
    qo_pfrl_unit_price: '',
    qo_pfrl_point_count: '',
    qo_pfrl_min_charge: '',
    qo_pfrl_note: '',
  };
}

function validateRows(rows: MatrixRow[]): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const keys = new Map<string, string>();
  rows.forEach((row, index) => {
    const rowNo = index + 1;
    if (!row.qo_pfrl_process_stage.trim()) {
      issues.push({ rowId: row.__rowId, message: `row ${rowNo} process stage is required` });
    }
    const unitPrice = numeric(row.qo_pfrl_unit_price);
    if (unitPrice == null || unitPrice <= 0) {
      issues.push({
        rowId: row.__rowId,
        message: `row ${rowNo} unit price must be greater than 0`,
      });
    }
    const minQty = integer(row.qo_pfrl_min_qty);
    const maxQty = integer(row.qo_pfrl_max_qty);
    if (minQty != null && maxQty != null && minQty > maxQty) {
      issues.push({ rowId: row.__rowId, message: `row ${rowNo} min qty must be <= max qty` });
    }
    const minCharge = numeric(row.qo_pfrl_min_charge);
    if (minCharge != null && minCharge < 0) {
      issues.push({ rowId: row.__rowId, message: `row ${rowNo} min charge must be >= 0` });
    }
    const pointCount = numeric(row.qo_pfrl_point_count);
    if (pointCount != null && pointCount < 0) {
      issues.push({ rowId: row.__rowId, message: `row ${rowNo} point count must be >= 0` });
    }
    const key = [
      row.qo_pfrl_process_stage.trim().toUpperCase(),
      row.qo_pfrl_component_type.trim().toUpperCase(),
      row.qo_pfrl_min_qty.trim(),
      row.qo_pfrl_max_qty.trim(),
    ].join('|');
    const duplicate = keys.get(key);
    if (duplicate) {
      issues.push({ rowId: row.__rowId, message: `row ${rowNo} duplicates ${duplicate}` });
    } else {
      keys.set(key, `row ${rowNo}`);
    }
  });
  return issues;
}

function parsePastedRows(text: string): MatrixRow[] {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.trim().length > 0);
  if (lines.length === 0) return [];
  const split = (line: string) => line.split('\t');
  const first = split(lines[0]);
  const headerFields = first.map((cell) => HEADER_ALIASES[normalizeHeader(cell)]).filter(Boolean);
  const hasHeader = headerFields.length >= 2;
  const fields = hasHeader
    ? first.map((cell) => HEADER_ALIASES[normalizeHeader(cell)])
    : COLUMNS.map((column) => column.field);
  const body = hasHeader ? lines.slice(1) : lines;
  return body.map((line, index) => {
    const row = emptyRow(`paste-${Date.now()}-${index}`);
    split(line).forEach((cell, cellIndex) => {
      const field = fields[cellIndex];
      if (field) row[field] = cell.trim();
    });
    return row;
  });
}

function toPayloadRow(row: MatrixRow): Record<string, string | undefined> {
  return {
    pid: row.pid,
    qo_pfrl_process_stage: row.qo_pfrl_process_stage,
    qo_pfrl_component_type: row.qo_pfrl_component_type,
    qo_pfrl_min_qty: row.qo_pfrl_min_qty,
    qo_pfrl_max_qty: row.qo_pfrl_max_qty,
    qo_pfrl_unit_price: row.qo_pfrl_unit_price,
    qo_pfrl_point_count: row.qo_pfrl_point_count,
    qo_pfrl_min_charge: row.qo_pfrl_min_charge,
    qo_pfrl_note: row.qo_pfrl_note,
  };
}

function signature(rows: MatrixRow[]): string {
  return JSON.stringify(rows.map((row) => toPayloadRow(row)));
}

function rowSignature(row: MatrixRow): string {
  return JSON.stringify(toPayloadRow(row));
}

function countDirtyRows(originalRows: MatrixRow[], currentRows: MatrixRow[]): number {
  const originalById = new Map(originalRows.map((row) => [row.__rowId, rowSignature(row)]));
  const currentIds = new Set(currentRows.map((row) => row.__rowId));
  let count = 0;
  for (const row of currentRows) {
    if (originalById.get(row.__rowId) !== rowSignature(row)) count += 1;
  }
  for (const row of originalRows) {
    if (!currentIds.has(row.__rowId)) count += 1;
  }
  return count;
}

function sortableValue(raw: string): string | number {
  const n = numeric(raw);
  return n == null ? raw : n;
}

function normalizeHeader(value: string): string {
  return value.toLowerCase().replace(/[\s_\-/（）()]+/g, '');
}

function numeric(raw: string): number | null {
  if (!raw.trim()) return null;
  const parsed = Number(raw.replace(/,/g, ''));
  return Number.isFinite(parsed) ? parsed : null;
}

function integer(raw: string): number | null {
  const parsed = numeric(raw);
  return parsed == null ? null : Math.trunc(parsed);
}

function value(raw: unknown): string {
  if (raw == null) return '';
  return String(raw);
}

export default ProcessFeeRuleMatrixBlock;
