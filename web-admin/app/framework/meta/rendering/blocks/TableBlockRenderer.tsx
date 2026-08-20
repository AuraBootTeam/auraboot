/**
 * TableBlockRenderer - 表格块渲染器
 * 支持新的列配置特性: valueType, render, ellipsis 等
 * 支持字典字段自动翻译显示
 */

import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type {
  BlockConfig,
  ColumnConfig,
  ButtonConfig,
  TreeConfig,
} from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { useAuth } from '~/contexts/AuthContext';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readDataSourceState,
  useDataSourceSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';
import { InlineEditCell } from '~/framework/meta/rendering/components/InlineEditCell';
import { useMediaQuery } from '~/framework/meta/rendering/components/ResponsiveBlockLayout';
import { getLegacyCompatibleRecordPid } from '~/framework/meta/utils/publicRecordId';
import { RowActionButtons } from '~/framework/meta/rendering/pages/list/RowActionButtons';

export interface TableBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

// 字典数据项类型
interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

interface GroupedSelectionItem {
  row: any;
  dataIndex: number;
}

interface GroupedSelectionGroup {
  key: string;
  hasExplicitKey: boolean;
  items: GroupedSelectionItem[];
}

type StatusPillTone = 'gray' | 'blue' | 'amber' | 'green' | 'red';

const FILE_PID_URL_PATTERN = /^\/?([0-9A-HJKMNP-TV-Z]{26})(?:\.[A-Za-z0-9]+)?$/;

// Opt-in status pill (renderType:'status-pill'). Dict-coded status renders as a
// color dot + text by default (§50); the pill is the deliberate emphasis case.
const STATUS_PILL_CLASS: Record<StatusPillTone, string> = {
  gray: 'border-status-gray bg-status-gray-bg text-status-gray',
  blue: 'border-status-blue bg-status-blue-bg text-status-blue',
  amber: 'border-status-amber bg-status-amber-bg text-status-amber',
  green: 'border-status-green bg-status-green-bg text-status-green',
  red: 'border-status-red bg-status-red-bg text-status-red',
};

function renderStatusPill(tone: StatusPillTone, label: React.ReactNode): React.ReactNode {
  return (
    <span
      data-testid="table-status-pill"
      className={`rounded-pill inline-flex max-w-full items-center border px-3 py-1 text-sm leading-5 font-semibold ${STATUS_PILL_CLASS[tone]}`}
    >
      <span className="truncate">{label}</span>
    </span>
  );
}

function renderConfig(column: ColumnConfig): Record<string, any> {
  return column.render && typeof column.render === 'object' && !Array.isArray(column.render)
    ? (column.render as Record<string, any>)
    : {};
}

function firstNonBlank(source: Record<string, any>, fields: string[]): unknown {
  for (const field of fields) {
    const value = source[field];
    if (value !== undefined && value !== null && String(value).trim() !== '') return value;
  }
  return undefined;
}

function comparableCellValue(row: any, field: string): string {
  const value = row?.[`${field}_display`] ?? row?.[field];
  if (value === undefined || value === null) return '';
  if (typeof value !== 'object') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function domSafeValue(value: string): string {
  return value.replace(/[^A-Za-z0-9_-]/g, '-');
}

function resolveLinkHref(column: ColumnConfig, row: any, value: unknown): string | undefined {
  const config = renderConfig(column);
  const url = value === undefined || value === null ? '' : String(value).trim();
  const derivedFileIdField = column.field.endsWith('_url')
    ? `${column.field.slice(0, -4)}_file_id`
    : '';
  const fileId = firstNonBlank(
    row || {},
    [
      String(config.fileIdField || ''),
      derivedFileIdField,
      'fileId',
      'file_id',
      'qo_qd_file_id',
    ].filter(Boolean),
  );
  if (fileId && (!url || FILE_PID_URL_PATTERN.test(url))) {
    return `/api/file/download/${encodeURIComponent(String(fileId))}`;
  }
  return url || (fileId ? `/api/file/download/${encodeURIComponent(String(fileId))}` : undefined);
}

function renderLinkCell(
  column: ColumnConfig,
  row: any,
  value: unknown,
  locale: string,
  t: (key: string) => string,
): React.ReactNode {
  const href = resolveLinkHref(column, row, value);
  if (!href) return <span className="text-text-3">-</span>;
  const config = renderConfig(column);
  const label = config.text
    ? getLocalizedText(config.text, locale, t)
    : value === undefined || value === null || value === ''
      ? 'Download'
      : String(value);
  const target = config.target || (href.startsWith('http') ? '_blank' : undefined);
  return (
    <a
      href={href}
      target={target}
      rel={target === '_blank' ? 'noreferrer' : undefined}
      onClick={(event) => event.stopPropagation()}
      className="text-accent decoration-border hover:text-accent-hover font-medium underline underline-offset-2"
    >
      {label}
    </a>
  );
}

export const TableBlockRenderer: React.FC<TableBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const evaluator = runtime.getEvaluator();
  const dataSourceManager = runtime.getDataSourceManager();

  // 路由 / 鉴权上下文 — useActionHandler hook 要求
  const navigate = useNavigate();
  const { token, hasPermission } = useAuth();
  const schema = runtime.getSchema();
  const tableName = (schema as any).modelCode || schema.id || '';

  const { handleAction: dispatchAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {},
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
  });

  const columns: ColumnConfig[] = Array.isArray(block.columns)
    ? (block.columns as ColumnConfig[])
    : block.table?.columns || [];
  const rowActions: ButtonConfig[] = Array.isArray(block.rowActions)
    ? (block.rowActions as ButtonConfig[])
    : [];
  const mobileCardConfig = ((block as any).mobileCard || {}) as Record<string, any>;
  const useMobileCards = useMediaQuery('(max-width: 640px)') && mobileCardConfig.enabled !== false;
  const rowClassRules: Array<{ when?: string; className?: string }> = Array.isArray(
    (block.table as any)?.rowClassRules,
  )
    ? ((block.table as any).rowClassRules as Array<{ when?: string; className?: string }>)
    : Array.isArray((block as any).rowClassRules)
      ? ((block as any).rowClassRules as Array<{ when?: string; className?: string }>)
      : [];

  // 字典数据缓存
  const dictDataCache = useRef<Map<string, DictItem[]>>(new Map());
  const [_dictLoaded, setDictLoaded] = useState(false);

  // 加载字典数据
  useEffect(() => {
    const dictCodes = columns.filter((col) => col.dictCode).map((col) => col.dictCode!);

    if (dictCodes.length === 0) {
      setDictLoaded(true);
      return;
    }

    // 获取尚未加载的字典
    const unloadedCodes = dictCodes.filter((code) => !dictDataCache.current.has(code));

    if (unloadedCodes.length === 0) {
      setDictLoaded(true);
      return;
    }

    // Track if this effect is still active
    let cancelled = false;

    // 并行加载所有字典数据
    const loadDictData = async () => {
      const promises = unloadedCodes.map(async (code) => {
        try {
          const result = await fetchResult(`/api/meta/dict/by-code/${code}/data`, {
            method: 'get',
          });
          if (ResultHelper.isSuccess(result) && result.data) {
            // 适配字典数据格式
            const data = result.data as { items?: DictItem[] } | DictItem[];
            const items: DictItem[] = Array.isArray(data) ? data : data.items || [];
            dictDataCache.current.set(code, items);
          }
        } catch (error) {
          console.error(`[TableBlockRenderer] Failed to load dict: ${code}`, error);
        }
      });

      await Promise.all(promises);
      if (!cancelled) {
        setDictLoaded(true);
      }
    };

    loadDictData();

    return () => {
      cancelled = true;
    };
  }, [columns]);

  // 获取表格数据 - 从 DataSource
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;
  useDataSourceSubscription(runtime, dataSourceId);

  /**
   * Inline cell editing, opt-in per table.
   *
   * ListTable has offered this for kind:list pages all along, through the same InlineEditCell;
   * a blockType:table on a workbench page renders here instead and had no way to reach it. Same
   * JSON shape, different renderer.
   *
   * The write goes through command.execute, not a REST PUT: on a workbench block a backend write
   * is a command, and these tables usually read a namedQuery whose projected column names are not
   * model fields — so a column names the field it writes with `editField`.
   *
   *   "inlineEdit": { "command": "qo_quote_line_common:update", "reload": ["bomPriceWaterfall"] }
   *   "columns": [{ "field": "qty_per_set", "editField": "qo_ql_qty_per_set", "editable": true }]
   */
  const inlineEditConfig = (block as any).table?.inlineEdit || (block as any).inlineEdit;
  const inlineEditCommand = inlineEditConfig?.command || inlineEditConfig?.commandCode;
  const handleInlineSave = React.useCallback(
    async (field: string, value: any, record: Record<string, any>) => {
      if (!inlineEditCommand) return;
      const pid = getLegacyCompatibleRecordPid(record);
      if (!pid) throw new Error('Row has no public record id; inline edit cannot target it');
      const column = columns.find((col) => col.field === field);
      const writeField = (column as any)?.editField || field;
      await executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: inlineEditCommand,
          targetRecordPid: pid,
          payload: { [writeField]: value },
          reload: inlineEditConfig?.reload ?? (dataSourceId ? [dataSourceId] : []),
        },
      });
    },
    [runtime, inlineEditCommand, inlineEditConfig?.reload, dataSourceId, columns],
  );
  const rawData = dataSourceId ? readDataSourceRows(runtime, dataSourceId) : [];
  const dataSourceState = readDataSourceState(runtime, dataSourceId);
  const dataSourceError = dataSourceState?.error as any;
  const errorStatus = Number(
    dataSourceError?.status ?? dataSourceError?.statusCode ?? dataSourceError?.response?.status,
  );
  const hasStaleRows = Boolean(dataSourceError) && rawData.length > 0;
  const stateConfig = ((block as any).states || {}) as Record<string, any>;
  const stateTitle = (key: string, fallback: string): string => {
    const configured = stateConfig[key]?.title;
    return configured ? getLocalizedText(configured, locale, t) : fallback;
  };
  const errorStateKey = Number.isFinite(errorStatus) ? String(errorStatus) : 'error';

  // Tree configuration — enables expandable hierarchical rows
  const treeConfig: TreeConfig | undefined = block.table?.treeConfig || (block as any).treeConfig;
  const { visibleRows, toggleExpand } = useTreeData(rawData, treeConfig);
  const selectionConfig = block.table?.selection || (block as any).selection;
  const selectionMode = selectionConfig?.mode || 'single';
  const isMultipleSelection = selectionMode === 'multiple';
  const defaultFirstSelection = Boolean((selectionConfig as any)?.defaultFirst);
  const exclusiveBy = String((selectionConfig as any)?.exclusiveBy || '').trim();
  const selectionPresentation = String((selectionConfig as any)?.presentation || 'table');
  const optionLabelField = String((selectionConfig as any)?.optionLabelField || '').trim();
  const recommendedField = String((selectionConfig as any)?.recommendedField || '').trim();
  const safeField = String((selectionConfig as any)?.safeField || '').trim();
  const hasStableSelectionIdentity = Boolean(
    block.table?.rowKey || (selectionConfig as any)?.keyField,
  );
  const hasSafeRecommendationContract = Boolean(
    hasStableSelectionIdentity && recommendedField && safeField && recommendedField !== safeField,
  );
  const groupedRadioPresentation =
    isMultipleSelection &&
    selectionPresentation === 'grouped-radio' &&
    Boolean(exclusiveBy) &&
    Boolean(optionLabelField);
  const groupedRadioDomPrefix = React.useId().replace(/:/g, '');
  const rowKeyField = block.table?.rowKey || (selectionConfig as any)?.keyField || 'pid';
  const selectionIdField = (selectionConfig as any)?.idField || rowKeyField;
  const [localSelectedRowKey, setLocalSelectedRowKey] = useState('');
  const [localSelectedRowKeys, setLocalSelectedRowKeys] = useState<string[]>([]);
  const getRowIdentity = (row: any, index?: number): string =>
    String(row?.[rowKeyField] ?? row?.id ?? row?.pid ?? index ?? '');
  const selectedStateValue = selectionConfig?.bind
    ? (runtime.getContext().state as Record<string, any> | undefined)?.[selectionConfig.bind]
    : undefined;
  const selectedRowsFromState = Array.isArray(selectedStateValue) ? selectedStateValue : [];
  const selectedRow = !Array.isArray(selectedStateValue) ? selectedStateValue : undefined;
  const selectedRowKey =
    selectedRow && typeof selectedRow === 'object' ? getRowIdentity(selectedRow) : '';
  const effectiveSelectedRowKey = localSelectedRowKey || selectedRowKey;
  const effectiveSelectedRowKeys = localSelectedRowKeys.length
    ? localSelectedRowKeys
    : selectedRowsFromState.map((row: any, index: number) => getRowIdentity(row, index));
  const effectiveSelectedRowKeySet = new Set(effectiveSelectedRowKeys);

  // Use tree-processed rows when treeConfig is set, otherwise flat data
  const data = treeConfig ? visibleRows : rawData;
  const selectionGroups: GroupedSelectionGroup[] = groupedRadioPresentation
    ? Array.from(
        data
          .reduce((groups: Map<string, GroupedSelectionGroup>, row: any, dataIndex: number) => {
            const rawGroupValue = row?.[exclusiveBy];
            const hasGroupValue =
              rawGroupValue !== undefined &&
              rawGroupValue !== null &&
              String(rawGroupValue).trim() !== '';
            // Missing group keys are isolated instead of being silently merged into
            // one unrelated decision. Validated business projections should always
            // provide the configured exclusiveBy field.
            const key = hasGroupValue ? String(rawGroupValue) : `__ungrouped_${dataIndex}`;
            const current = groups.get(key) || { key, hasExplicitKey: hasGroupValue, items: [] };
            current.items.push({ row, dataIndex });
            groups.set(key, current);
            return groups;
          }, new Map<string, GroupedSelectionGroup>())
          .values(),
      )
    : [];
  const density = block.table?.density || (block as any).density || 'default';
  const isCompact = density === 'compact';
  const headerCellClass = isCompact ? 'px-3 py-2' : 'px-6 py-3';
  const bodyCellClass = isCompact ? 'px-3 py-2' : 'px-6 py-4';
  const maxHeight = block.table?.maxHeight || (block as any).maxHeight;
  const tableContainerStyle =
    maxHeight === undefined
      ? {
          width: '100%',
          maxWidth: '100%',
        }
      : {
          maxHeight: typeof maxHeight === 'number' ? `${maxHeight}px` : String(maxHeight),
          width: '100%',
          maxWidth: '100%',
        };

  // 渲染列头
  useEffect(() => {
    if (!selectionConfig?.bind || isMultipleSelection || !defaultFirstSelection) return;
    const current = (runtime.getContext().state as Record<string, any> | undefined)?.[
      selectionConfig.bind
    ];
    const currentKey = current && typeof current === 'object' ? getRowIdentity(current) : '';
    const currentStillVisible =
      Boolean(currentKey) &&
      data.some((row: any, index: number) => getRowIdentity(row, index) === currentKey);

    // A data-source reload can replace the selected row with a newer snapshot while
    // preserving the same public id. Keep the bound workbench context current so
    // sibling status banners and lifecycle actions do not evaluate stale fields.
    if (currentStillVisible) {
      const refreshedCurrent = data.find(
        (row: any, index: number) => getRowIdentity(row, index) === currentKey,
      );
      if (refreshedCurrent && refreshedCurrent !== current) {
        writeRuntimeState(runtime, selectionConfig.bind, refreshedCurrent);
        setLocalSelectedRowKey(currentKey);
      }
      return;
    }

    if (data.length > 0) {
      const firstRow = data[0];
      writeRuntimeState(runtime, selectionConfig.bind, firstRow);
      setLocalSelectedRowKey(getRowIdentity(firstRow, 0));
      return;
    }

    if (data.length === 0 && current) {
      writeRuntimeState(runtime, selectionConfig.bind, null);
      setLocalSelectedRowKey('');
    }
  }, [
    data,
    runtime,
    selectionConfig?.bind,
    isMultipleSelection,
    defaultFirstSelection,
    rowKeyField,
  ]);

  const writeMultipleSelection = (rows: any[]) => {
    if (!selectionConfig?.bind) return;
    writeRuntimeState(runtime, selectionConfig.bind, rows);
    if ((selectionConfig as any).idsBind) {
      writeRuntimeState(
        runtime,
        (selectionConfig as any).idsBind,
        rows
          .map((row) => row?.[selectionIdField])
          .filter((value) => value !== undefined && value !== null),
      );
    }
    setLocalSelectedRowKeys(rows.map((row, index) => getRowIdentity(row, index)));
  };

  useEffect(() => {
    if (!groupedRadioPresentation || !selectionConfig?.bind) return;

    const currentRows = Array.isArray(selectedStateValue) ? selectedStateValue : [];
    const visibleItemsByIdentity = new Map<string, GroupedSelectionItem>();
    const groupKeyByIdentity = new Map<string, string>();
    selectionGroups.forEach((group) => {
      group.items.forEach((item) => {
        const identity = getRowIdentity(item.row, item.dataIndex);
        visibleItemsByIdentity.set(identity, item);
        groupKeyByIdentity.set(identity, group.key);
      });
    });

    // Existing user choices always win. At the same time, normalize stale rows,
    // refreshed row snapshots, and malformed duplicate choices within one group.
    const selectedByGroup = new Map<string, any>();
    currentRows.forEach((row: any, index: number) => {
      const identity = getRowIdentity(row, index);
      const item = visibleItemsByIdentity.get(identity);
      const groupKey = groupKeyByIdentity.get(identity);
      if (item && groupKey && !selectedByGroup.has(groupKey)) {
        selectedByGroup.set(groupKey, item.row);
      }
    });

    const nextRows: any[] = [];
    selectionGroups.forEach((group) => {
      const existing = selectedByGroup.get(group.key);
      if (existing) {
        nextRows.push(existing);
        return;
      }
      if (!hasSafeRecommendationContract || !group.hasExplicitKey) return;

      const safeRecommendations = group.items.filter(
        (item) => item.row?.[recommendedField] === true && item.row?.[safeField] === true,
      );
      // A duplicate recommendation is a broken producer contract, not a tie to
      // guess through. Fail closed and leave that group for explicit user choice.
      if (safeRecommendations.length === 1) {
        nextRows.push(safeRecommendations[0].row);
      }
    });

    const unchanged =
      currentRows.length === nextRows.length &&
      currentRows.every(
        (row: any, index: number) =>
          getRowIdentity(row, index) === getRowIdentity(nextRows[index], index) &&
          row === nextRows[index],
      );
    const nextRowKeys = nextRows.map((row: any, index: number) => getRowIdentity(row, index));
    const localSelectionUnchanged =
      localSelectedRowKeys.length === nextRowKeys.length &&
      localSelectedRowKeys.every((key, index) => key === nextRowKeys[index]);
    if (unchanged || (selectedStateValue === undefined && nextRows.length === 0)) {
      if (!localSelectionUnchanged) setLocalSelectedRowKeys(nextRowKeys);
      return;
    }

    writeRuntimeState(runtime, selectionConfig.bind, nextRows);
    if ((selectionConfig as any).idsBind) {
      writeRuntimeState(
        runtime,
        (selectionConfig as any).idsBind,
        nextRows
          .map((row: any) => row?.[selectionIdField])
          .filter((value: any) => value !== undefined && value !== null),
      );
    }
    if (!localSelectionUnchanged) setLocalSelectedRowKeys(nextRowKeys);
  }, [
    data,
    groupedRadioPresentation,
    hasSafeRecommendationContract,
    localSelectedRowKeys,
    recommendedField,
    rowKeyField,
    runtime,
    safeField,
    selectedStateValue,
    selectionConfig?.bind,
    selectionIdField,
  ]);

  const toggleMultipleSelection = (row: any, index: number) => {
    if (!selectionConfig?.bind) return;
    if ((selectionConfig as any).detailBind) {
      writeRuntimeState(runtime, (selectionConfig as any).detailBind, row);
    }
    const identity = getRowIdentity(row, index);
    const currentRows = selectedRowsFromState.length
      ? selectedRowsFromState
      : data.filter((candidate: any, candidateIndex: number) =>
          effectiveSelectedRowKeySet.has(getRowIdentity(candidate, candidateIndex)),
        );
    const exclusiveValue = exclusiveBy ? row?.[exclusiveBy] : undefined;
    const rowsOutsideExclusiveGroup =
      exclusiveBy &&
      exclusiveValue !== undefined &&
      exclusiveValue !== null &&
      exclusiveValue !== ''
        ? currentRows.filter((candidate: any) => candidate?.[exclusiveBy] !== exclusiveValue)
        : currentRows;
    const nextRows = effectiveSelectedRowKeySet.has(identity)
      ? currentRows.filter((candidate: any, candidateIndex: number) => {
          const candidateIdentity =
            getRowIdentity(candidate) || getRowIdentity(candidate, candidateIndex);
          return candidateIdentity !== identity;
        })
      : [...rowsOutsideExclusiveGroup, row];
    writeMultipleSelection(nextRows);
  };

  const chooseGroupedSelection = (row: any, groupItems: GroupedSelectionItem[]) => {
    if (!selectionConfig?.bind) return;
    if ((selectionConfig as any).detailBind) {
      writeRuntimeState(runtime, (selectionConfig as any).detailBind, row);
    }
    const boundRows = (runtime.getContext().state as Record<string, any> | undefined)?.[
      selectionConfig.bind
    ];
    const currentRows = Array.isArray(boundRows)
      ? boundRows
      : data.filter((candidate: any, candidateIndex: number) =>
          effectiveSelectedRowKeySet.has(getRowIdentity(candidate, candidateIndex)),
        );
    const groupIdentities = new Set(
      groupItems.map((item) => getRowIdentity(item.row, item.dataIndex)),
    );
    const nextRows = [
      ...currentRows.filter(
        (candidate: any, candidateIndex: number) =>
          !groupIdentities.has(getRowIdentity(candidate, candidateIndex)),
      ),
      row,
    ].sort((left, right) => {
      const leftIndex = data.findIndex(
        (candidate: any, candidateIndex: number) =>
          getRowIdentity(candidate, candidateIndex) === getRowIdentity(left),
      );
      const rightIndex = data.findIndex(
        (candidate: any, candidateIndex: number) =>
          getRowIdentity(candidate, candidateIndex) === getRowIdentity(right),
      );
      return leftIndex - rightIndex;
    });
    // Radio choices are not toggleable: re-choosing the current option keeps it
    // selected, while another option replaces only the row from this group.
    writeMultipleSelection(nextRows);
  };

  // Choosing every action in an exclusive group would invent a business decision.
  // Keep the selection column, but require an explicit row choice per group.
  const supportsSelectAll =
    isMultipleSelection && !exclusiveBy && selectionPresentation !== 'grouped-radio';
  const allVisibleRowsSelected =
    supportsSelectAll &&
    data.length > 0 &&
    data.every((row: any, index: number) =>
      effectiveSelectedRowKeySet.has(getRowIdentity(row, index)),
    );

  const toggleAllVisibleRows = () => {
    if (!supportsSelectAll) return;
    writeMultipleSelection(allVisibleRowsSelected ? [] : data);
  };

  const renderColumnHeader = (column: ColumnConfig) => {
    const label = getLocalizedText(column.label, locale, t);
    const key = String(column.field || (column as any).code || label || 'column');
    return (
      <th
        key={key}
        data-testid={`table-th-${key}`}
        className={`${headerCellClass} text-${column.align || 'left'} text-text-2 text-xs font-medium tracking-wider uppercase`}
        style={{ width: column.width }}
      >
        {label}
        {column.sortable && <span className="text-text-3 ml-1">⇅</span>}
      </th>
    );
  };

  // 渲染单元格内容
  const renderCellContent = (column: ColumnConfig, row: any) => {
    if (column.isActionColumn) {
      return renderActionButtons(
        row,
        Array.isArray((column as any).buttons) ? (column as any).buttons : [],
      );
    }

    const value = row[column.field];
    const enrichedDisplayValue = row?.[`${column.field}_display`];

    if (column.valueType === 'link' || column.valueType === 'url') {
      return renderLinkCell(column, row, value, locale, t);
    }

    // Dynamic list APIs enrich reference fields as `<field>_display`. A table
    // block must prefer that business label; rendering the raw pid leaks an
    // implementation identifier even though the backend already supplied the
    // user-facing value.
    if (
      enrichedDisplayValue !== undefined &&
      enrichedDisplayValue !== null &&
      String(enrichedDisplayValue).trim() !== ''
    ) {
      return String(enrichedDisplayValue);
    }

    // Null/undefined 处理
    if (value === null || value === undefined) {
      return <span className="text-text-3">-</span>;
    }

    // 如果有 dictCode，尝试翻译值为标签
    if (column.dictCode) {
      const dictItems = dictDataCache.current.get(column.dictCode);
      if (dictItems) {
        const item = dictItems.find((i) => String(i.value) === String(value));
        if (item) {
          const tone = resolveStatusTone(item.extension?.color);
          if (column.renderType === 'status-pill') {
            return renderStatusPill(tone, item.label);
          }
          // §3 / §1.3: dict-coded status renders as 色点 + 文字 (semantic dot + label),
          // not a filled pill. Color from extension.color → canonical tone.
          return <StatusDot tone={tone} label={item.label} />;
        }
      }
      // 字典未加载或未找到匹配项时显示原始值
      return String(value);
    }

    // 自定义 render 表达式
    if (typeof column.render === 'string') {
      try {
        const rendered = evaluator.evaluateTemplate(column.render, {
          ...context,
          row,
          // Alias the current row as `record` to match the list-page / sub-table
          // convention (ListPageContent / SubTableViewer set record = row), so a
          // table block's `record.<field>` resolves to the row, not the page record.
          record: row,
        });
        return <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(rendered) }} />;
      } catch (err) {
        console.error('Column render failed:', err);
        return String(value);
      }
    }

    // renderType 渲染 —— 交给共享的 cell renderer registry。
    //
    // Without this, a table block understands exactly one renderType (status-pill) and drops every
    // other on the floor: `progress`, `currency`, `rating`, `owner` and the rest render as bare
    // text. The same column config, on a kind:list page, goes through ListTable and renders
    // properly — so the same DSL means two different things depending on which page kind it
    // happens to sit in, and the version that does nothing does it silently.
    // has() first: an unknown renderType must fall through to the existing valueType handling
    // rather than be silently swallowed by the registry's `default` renderer.
    if (
      column.renderType &&
      column.renderType !== 'status-pill' &&
      cellRendererRegistry.has(column.renderType)
    ) {
      return cellRendererRegistry.render(column.renderType, {
        value,
        record: row,
        column: column as any,
      } as any);
    }

    // valueType 渲染
    switch (column.valueType) {
      case 'date':
        return new Date(value).toLocaleDateString(locale);

      case 'datetime':
        return new Date(value).toLocaleString(locale);

      case 'currency':
        return new Intl.NumberFormat(locale, {
          style: 'currency',
          currency: column.currencyCode || 'cny',
        }).format(value);

      case 'tag':
        return (
          <span className="rounded-pill bg-status-blue-bg text-status-blue inline-flex px-2 py-1 text-xs font-medium">
            {value}
          </span>
        );

      case 'progress':
        return (
          <div className="rounded-pill bg-subtle h-2.5 w-full">
            <div className="rounded-pill bg-accent h-2.5" style={{ width: `${value}%` }}></div>
          </div>
        );

      case 'image':
        return <img src={value} alt="" className="h-8 w-8 rounded object-cover" />;

      default:
        return String(value);
    }
  };

  const getCellTitle = (column: ColumnConfig, row: any): string | undefined => {
    if (!column.ellipsis) return undefined;
    const value = row[`${column.field}_display`] ?? row[column.field];
    if (value === null || value === undefined) return undefined;
    return typeof value === 'string' ? value : String(value);
  };

  // 渲染操作按钮
  const renderActionButtons = (
    row: any,
    actions: ButtonConfig[],
    presentation: 'links' | 'buttons' = 'links',
  ) => {
    return (
      <div className="flex flex-wrap gap-2">
        {actions.map((button) => {
          if (button.permissionCode && !hasPermission(button.permissionCode)) {
            return null;
          }
          // 条件渲染
          if (button.visibleWhen) {
            const visible = evaluator.evaluateCondition(button.visibleWhen, {
              ...context,
              row,
              // `record` aliases the row — matches the list-page / sub-table
              // row-action convention so `record.<field>` gates per-row here too.
              record: row,
            });
            if (!visible) return null;
          }
          const disabledWhen = (button as any).disabledWhen || button.disableWhen;
          const disabled = disabledWhen
            ? evaluator.evaluateCondition(disabledWhen, {
                ...context,
                row,
                record: row,
              })
            : false;

          const label = getLocalizedText(button.label || button.content || button.code, locale, t);
          const buttonClass =
            presentation === 'buttons'
              ? button.variant === 'primary'
                ? 'bg-accent text-white hover:bg-accent-hover border-accent min-h-11 flex-1 rounded-md border px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50'
                : button.variant === 'danger' || (button as any).danger
                  ? 'border-status-red/40 text-status-red hover:bg-status-red-bg min-h-11 flex-1 rounded-md border bg-panel px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50'
                  : 'border-border bg-panel text-text hover:bg-hover min-h-11 flex-1 rounded-md border px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50'
              : `text-sm ${
                  button.variant === 'danger' || (button as any).danger
                    ? 'text-status-red hover:opacity-80'
                    : 'text-accent hover:text-accent-hover'
                } disabled:cursor-not-allowed disabled:opacity-50`;

          return (
            <button
              key={button.code}
              data-testid={`row-action-${button.code}`}
              disabled={disabled}
              onClick={(event) => {
                event.stopPropagation();
                handleAction(button, row);
              }}
              className={buttonClass}
            >
              {label}
            </button>
          );
        })}
      </div>
    );
  };

  const renderRowActions = (row: any) => (
    <RowActionButtons
      buttons={rowActions.filter((action) => !(action as any).mobileOnly)}
      record={row}
      evaluateVisibleWhen={(expression, record) =>
        !expression ||
        evaluator.evaluateCondition(expression, {
          ...context,
          row: record,
          record,
        })
      }
      canUseButton={(button) => !button.permissionCode || hasPermission(button.permissionCode)}
      resolveButtonLabel={(button) =>
        getLocalizedText(button.label || button.content || button.code, locale, t)
      }
      handleAction={handleAction}
    />
  );

  const mobileActions = (() => {
    const configuredCodes = Array.isArray(mobileCardConfig.actionCodes)
      ? new Set(mobileCardConfig.actionCodes.map(String))
      : null;
    return rowActions.filter(
      (action) =>
        !(action as any).desktopOnly && (!configuredCodes || configuredCodes.has(action.code)),
    );
  })();

  const resolveMobileColumn = (entry: string | Record<string, any>): ColumnConfig => {
    const config = typeof entry === 'string' ? { field: entry } : entry;
    const base = columns.find((column) => column.field === config.field);
    return { ...(base || {}), ...config } as ColumnConfig;
  };

  const renderMobileCards = () => {
    const titleColumn = resolveMobileColumn(mobileCardConfig.titleField || columns[0]?.field || '');
    const eyebrowColumn = mobileCardConfig.eyebrowField
      ? resolveMobileColumn(mobileCardConfig.eyebrowField)
      : null;
    const statusColumn = mobileCardConfig.statusField
      ? resolveMobileColumn(mobileCardConfig.statusField)
      : null;
    const summaryColumns = (
      Array.isArray(mobileCardConfig.fields) ? mobileCardConfig.fields : columns.slice(1, 5)
    ).map(resolveMobileColumn);

    if (data.length === 0) {
      return (
        <div
          data-testid="table-mobile-empty"
          className="border-border bg-panel text-text-2 rounded-card border px-5 py-8 text-center text-sm"
        >
          {(block as any).empty?.title
            ? getLocalizedText((block as any).empty.title, locale, t)
            : t('common.noData') !== 'common.noData'
              ? t('common.noData')
              : 'No data'}
        </div>
      );
    }

    return (
      <div data-testid="table-mobile-cards" className="grid gap-3">
        {data.map((row: any, index: number) => {
          const rowIdentity = getRowIdentity(row, index);
          const isSelected = isMultipleSelection
            ? effectiveSelectedRowKeySet.has(rowIdentity)
            : Boolean(effectiveSelectedRowKey) && effectiveSelectedRowKey === rowIdentity;

          return (
            <article
              key={rowIdentity}
              data-testid={`table-mobile-card-${rowIdentity}`}
              onClick={() => handleRowClick(row, index)}
              className={`rounded-card bg-panel border p-4 shadow-sm transition-colors ${
                isSelected ? 'border-accent ring-accent/15 ring-2' : 'border-border'
              } ${selectionConfig?.bind ? 'cursor-pointer' : ''} ${rowClassName(row)}`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  {eyebrowColumn && (
                    <div className="text-text-2 mb-1 truncate text-xs font-medium">
                      {renderCellContent(eyebrowColumn, row)}
                    </div>
                  )}
                  <h3 className="text-text text-base leading-6 font-semibold">
                    {renderCellContent(titleColumn, row)}
                  </h3>
                </div>
                {statusColumn && (
                  <div className="shrink-0">{renderCellContent(statusColumn, row)}</div>
                )}
              </div>

              {summaryColumns.length > 0 && (
                <dl className="border-border mt-3 grid grid-cols-2 gap-x-4 gap-y-3 border-t pt-3">
                  {summaryColumns.map((column) => (
                    <div key={column.field} className="min-w-0">
                      <dt className="text-text-3 text-xs font-medium">
                        {getLocalizedText(column.label || column.field, locale, t)}
                      </dt>
                      <dd className="text-text mt-1 min-w-0 text-sm font-medium break-words">
                        {renderCellContent(column, row)}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}

              {mobileActions.length > 0 && (
                <div className="border-border mt-4 flex flex-wrap gap-2 border-t pt-3">
                  {renderActionButtons(row, mobileActions, 'buttons')}
                </div>
              )}
            </article>
          );
        })}
      </div>
    );
  };

  const rowClassName = (row: any): string =>
    rowClassRules
      .filter((rule) => {
        if (!rule.when) return false;
        return evaluator.evaluateCondition(rule.when, {
          ...context,
          row,
          record: row,
        });
      })
      .map((rule) => rule.className || '')
      .filter(Boolean)
      .join(' ');

  const renderGroupedRadioSelection = () => {
    if (selectionGroups.length === 0) {
      return (
        <div className="text-text-2 px-6 py-4 text-center">
          {(block as any).empty?.title
            ? getLocalizedText((block as any).empty.title, locale, t)
            : t('common.noData') !== 'common.noData'
              ? t('common.noData')
              : 'No data'}
        </div>
      );
    }

    const optionLabelColumn = columns.find((column) => column.field === optionLabelField);
    if (!optionLabelColumn) return null;
    const optionColumnLabel = getLocalizedText(optionLabelColumn.label, locale, t);

    return (
      <div className="grid gap-4" data-testid="table-grouped-radio">
        {selectionGroups.map((group, groupIndex) => {
          const firstRow = group.items[0].row;
          const sharedColumns = columns.filter(
            (column) =>
              column.field !== optionLabelField &&
              group.items.every(
                (item) =>
                  comparableCellValue(item.row, column.field) ===
                  comparableCellValue(firstRow, column.field),
              ),
          );
          const varyingColumns = columns.filter(
            (column) => column.field !== optionLabelField && !sharedColumns.includes(column),
          );
          const safeGroupKey = `${groupIndex}-${domSafeValue(group.key)}`;
          const groupLabelId = `${groupedRadioDomPrefix}-${safeGroupKey}-label`;
          const groupLabel = `${optionColumnLabel} ${groupIndex + 1}`;

          return (
            <section
              key={group.key}
              data-testid={`table-selection-group-${safeGroupKey}`}
              className="border-border bg-panel rounded-lg border p-4"
            >
              <h3 id={groupLabelId} className="sr-only">
                {groupLabel}
              </h3>
              {sharedColumns.length > 0 && (
                <dl className="border-border mb-4 grid gap-x-6 gap-y-3 border-b pb-4 sm:grid-cols-2 lg:grid-cols-3">
                  {sharedColumns.map((column) => (
                    <div
                      key={column.field}
                      data-testid={`table-selection-group-${safeGroupKey}-field-${domSafeValue(column.field)}`}
                      className="min-w-0"
                    >
                      <dt className="text-text-2 text-xs font-medium">
                        {getLocalizedText(column.label, locale, t)}
                      </dt>
                      <dd className="text-text mt-1 text-sm">
                        {renderCellContent(column, firstRow)}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              <div
                role="radiogroup"
                aria-labelledby={groupLabelId}
                data-testid={`table-selection-radiogroup-${safeGroupKey}`}
                className="grid gap-2"
              >
                {group.items.map((item, optionIndex) => {
                  const rowIdentity = getRowIdentity(item.row, item.dataIndex);
                  const isSelected = effectiveSelectedRowKeySet.has(rowIdentity);
                  const optionAccessibleLabel =
                    comparableCellValue(item.row, optionLabelField) ||
                    `${optionColumnLabel} ${optionIndex + 1}`;

                  return (
                    <div
                      key={rowIdentity}
                      className={`border-border rounded-md border px-3 py-3 ${
                        isSelected ? 'border-accent bg-accent-weak' : 'hover:bg-hover'
                      } ${rowClassName(item.row)}`}
                    >
                      <div
                        className="flex cursor-pointer items-start gap-3"
                        onClick={() => chooseGroupedSelection(item.row, group.items)}
                      >
                        <input
                          type="radio"
                          name={`${groupedRadioDomPrefix}-${safeGroupKey}`}
                          data-testid={`table-select-row-${rowIdentity}`}
                          checked={isSelected}
                          onChange={() => chooseGroupedSelection(item.row, group.items)}
                          onClick={(event) => event.stopPropagation()}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter') {
                              event.preventDefault();
                              chooseGroupedSelection(item.row, group.items);
                              return;
                            }
                            const movesForward =
                              event.key === 'ArrowDown' || event.key === 'ArrowRight';
                            const movesBackward =
                              event.key === 'ArrowUp' || event.key === 'ArrowLeft';
                            if (
                              !movesForward &&
                              !movesBackward &&
                              event.key !== 'Home' &&
                              event.key !== 'End'
                            ) {
                              return;
                            }
                            event.preventDefault();
                            const nextOptionIndex =
                              event.key === 'Home'
                                ? 0
                                : event.key === 'End'
                                  ? group.items.length - 1
                                  : (optionIndex + (movesForward ? 1 : -1) + group.items.length) %
                                    group.items.length;
                            const nextItem = group.items[nextOptionIndex];
                            chooseGroupedSelection(nextItem.row, group.items);
                            const groupElement = event.currentTarget.closest('[role="radiogroup"]');
                            const radios =
                              groupElement?.querySelectorAll<HTMLInputElement>(
                                'input[type="radio"]',
                              );
                            radios?.[nextOptionIndex]?.focus();
                          }}
                          aria-label={optionAccessibleLabel}
                          className="border-border text-accent focus:ring-accent mt-0.5 h-4 w-4"
                        />
                        <div className="min-w-0 flex-1">
                          <div className="text-text text-sm font-medium">
                            {renderCellContent(optionLabelColumn, item.row)}
                          </div>
                          {varyingColumns.length > 0 && (
                            <div className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-2">
                              {varyingColumns.map((column) => (
                                <div key={column.field} className="text-text-2 text-xs">
                                  <span className="font-medium">
                                    {getLocalizedText(column.label, locale, t)}:
                                  </span>{' '}
                                  {renderCellContent(column, item.row)}
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                      {rowActions.length > 0 && (
                        <div className="border-border mt-3 border-t pt-3">
                          {renderRowActions(item.row)}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </section>
          );
        })}
      </div>
    );
  };

  // 处理操作按钮点击 - 委托给 useActionHandler
  // Legacy compatibility: bare `button.handler` (not wrapped in events.onClick) is
  // not recognized by normalizeAction — normalize it here to preserve original
  // behavior where `button.handler` on a row action was fire-able.
  const handleAction = (button: ButtonConfig, row: any) => {
    const normalized: ButtonConfig =
      button.handler && !button.events?.onClick && !button.action
        ? { ...button, events: { ...(button.events || {}), onClick: { handler: button.handler } } }
        : button;

    // Preserve original gate: only fire for buttons with a recognized action source.
    if (
      !normalized.events?.onClick &&
      !normalized.action &&
      !normalized.commandCode &&
      !normalized.navigateTo &&
      !normalized.apiAction
    ) {
      return;
    }
    dispatchAction(normalized, row);
  };

  const handleRowClick = (row: any, index: number) => {
    if (!selectionConfig?.bind) return;
    if (isMultipleSelection) {
      toggleMultipleSelection(row, index);
      return;
    }
    writeRuntimeState(runtime, selectionConfig.bind, row);
    setLocalSelectedRowKey(getRowIdentity(row));
  };

  if (dataSourceState?.loading && rawData.length === 0) {
    return (
      <div
        data-testid="table-loading-state"
        role="status"
        className="text-text-2 px-6 py-8 text-center"
      >
        {stateTitle(
          'loading',
          t('common.loading') !== 'common.loading' ? t('common.loading') : 'Loading…',
        )}
      </div>
    );
  }

  if (dataSourceError && rawData.length === 0) {
    return (
      <div
        data-testid={`table-error-state-${errorStateKey}`}
        role="alert"
        className="border-danger/30 bg-danger/5 rounded-lg border px-6 py-5"
      >
        <div className="text-text font-semibold">
          {stateTitle(
            errorStateKey,
            stateTitle('error', errorStatus ? `Request failed (${errorStatus})` : 'Request failed'),
          )}
        </div>
        <div className="text-text-2 mt-1 text-sm">{String(dataSourceError?.message || '')}</div>
        {dataSourceId && (
          <button
            type="button"
            data-testid="table-error-retry"
            className="border-border bg-panel text-text mt-3 rounded-md border px-3 py-1.5 text-sm font-medium"
            onClick={() => void dataSourceManager.reload(dataSourceId)}
          >
            {stateTitle(
              'retry',
              t('common.retry') !== 'common.retry' ? t('common.retry') : 'Retry',
            )}
          </button>
        )}
      </div>
    );
  }

  const staleState = hasStaleRows ? (
    <div
      data-testid="table-stale-state"
      role="status"
      className="border-warning/30 bg-warning/5 text-text mb-2 rounded-lg border px-4 py-2 text-sm"
    >
      {stateTitle('stale', 'Showing preserved data because refresh failed. Retry before acting.')}
    </div>
  ) : null;

  if (groupedRadioPresentation) {
    return (
      <>
        {staleState}
        <div
          className={`table-block w-full max-w-full overflow-x-auto ${maxHeight ? 'overflow-y-auto' : ''}`}
          data-testid="table-block"
          style={tableContainerStyle}
        >
          {renderGroupedRadioSelection()}
        </div>
      </>
    );
  }

  if (useMobileCards && mobileCardConfig.titleField) {
    return (
      <>
        {staleState}
        {renderMobileCards()}
      </>
    );
  }

  return (
    <>
      {staleState}
      <div
        className={`table-block w-full max-w-full overflow-x-auto ${maxHeight ? 'overflow-y-auto' : ''}`}
        data-testid="table-block"
        style={tableContainerStyle}
      >
        <table className="divide-border w-max min-w-full divide-y">
          <thead className={maxHeight ? 'bg-subtle sticky top-0 z-10' : 'bg-subtle'}>
            <tr>
              {isMultipleSelection && (
                <th className={`${headerCellClass} w-12 text-left`}>
                  {supportsSelectAll && (
                    <input
                      type="checkbox"
                      data-testid="table-select-all"
                      checked={allVisibleRowsSelected}
                      onChange={toggleAllVisibleRows}
                      aria-label={getLocalizedText(
                        { 'zh-CN': '选择全部行', en: 'Select all rows' },
                        locale,
                        t,
                      )}
                    />
                  )}
                </th>
              )}
              {columns.map(renderColumnHeader)}
              {rowActions.length > 0 && (
                <th
                  className={`${headerCellClass} text-text-2 text-left text-xs font-medium tracking-wider uppercase`}
                >
                  {t('common.actions') !== 'common.actions' ? t('common.actions') : 'Actions'}
                </th>
              )}
            </tr>
          </thead>
          <tbody className="divide-border bg-panel divide-y">
            {data.length === 0 ? (
              <tr>
                <td
                  colSpan={
                    columns.length + (rowActions.length > 0 ? 1 : 0) + (isMultipleSelection ? 1 : 0)
                  }
                  className="text-text-2 px-6 py-4 text-center"
                >
                  {/* A table that only fills in once you select something upstream should say so.
                    "No data" on an empty transcript reads as "this conversation has no messages"
                    when what it means is "you have not picked one yet" — the same two words for
                    two different situations, and the user cannot tell which they are in. */}
                  {(block as any).empty?.title
                    ? getLocalizedText((block as any).empty.title, locale, t)
                    : t('common.noData') !== 'common.noData'
                      ? t('common.noData')
                      : 'No data'}
                </td>
              </tr>
            ) : (
              data.map((row: any, index: number) => {
                const rowIdentity = getRowIdentity(row, index);
                const isSelected = isMultipleSelection
                  ? effectiveSelectedRowKeySet.has(rowIdentity)
                  : Boolean(effectiveSelectedRowKey) && effectiveSelectedRowKey === rowIdentity;

                return (
                  <tr
                    key={rowIdentity}
                    data-testid={`table-row-${rowIdentity}`}
                    onClick={() => handleRowClick(row, index)}
                    className={`hover:bg-hover ${isSelected ? 'bg-accent-weak' : ''} ${rowClassName(row)} ${
                      selectionConfig?.bind ? 'cursor-pointer' : ''
                    }`}
                  >
                    {isMultipleSelection && (
                      <td className={`${bodyCellClass} w-12`}>
                        <input
                          type="checkbox"
                          data-testid={`table-select-row-${rowIdentity}`}
                          checked={isSelected}
                          onClick={(event) => event.stopPropagation()}
                          onChange={() => toggleMultipleSelection(row, index)}
                          aria-label={`Select row ${rowIdentity}`}
                        />
                      </td>
                    )}
                    {columns.map((column, colIdx) => (
                      <td
                        key={column.field}
                        className={`${bodyCellClass} text-text text-sm ${
                          column.ellipsis ? 'truncate' : ''
                        } text-${column.align || 'left'}`}
                        title={getCellTitle(column, row)}
                        style={{
                          maxWidth: column.ellipsis ? column.width : undefined,
                          // Tree indent: apply padding to first column
                          paddingLeft:
                            treeConfig && colIdx === 0
                              ? `${(row._depth || 0) * 24 + 24}px`
                              : undefined,
                        }}
                      >
                        {/* Tree expand toggle on first column */}
                        {treeConfig && colIdx === 0 && (
                          <span className="mr-1 inline-flex items-center">
                            {row._hasChildren ? (
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  toggleExpand(getLegacyCompatibleRecordPid(row) || '');
                                }}
                                className="text-text-3 hover:text-text-2 flex h-4 w-4 items-center justify-center"
                                data-testid={`tree-toggle-${getLegacyCompatibleRecordPid(row)}`}
                              >
                                {row._expanded ? '▼' : '▶'}
                              </button>
                            ) : (
                              <span className="inline-block h-4 w-4" />
                            )}
                          </span>
                        )}
                        {(column as any).editable && inlineEditCommand ? (
                          <InlineEditCell
                            column={column}
                            value={row[column.field]}
                            record={row}
                            onSave={handleInlineSave}
                            editable
                            dictItems={
                              column.dictCode
                                ? (dictDataCache.current.get(column.dictCode) ?? [])
                                : undefined
                            }
                          >
                            {renderCellContent(column, row)}
                          </InlineEditCell>
                        ) : (
                          renderCellContent(column, row)
                        )}
                      </td>
                    ))}
                    {rowActions.length > 0 && (
                      <td className={`${bodyCellClass} text-text text-sm`}>
                        {renderRowActions(row)}
                      </td>
                    )}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </>
  );
};

export default TableBlockRenderer;
