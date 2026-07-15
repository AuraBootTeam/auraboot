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
  readDataSourceRows,
  useDataSourceSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';
import { getLegacyCompatibleRecordPid } from '~/framework/meta/utils/publicRecordId';

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

type StatusPillTone = 'gray' | 'blue' | 'amber' | 'green' | 'red';

const FILE_PID_URL_PATTERN = /^\/?([0-9A-HJKMNP-TV-Z]{26})(?:\.[A-Za-z0-9]+)?$/;

const STATUS_PILL_CLASS: Record<StatusPillTone, string> = {
  gray: 'border-gray-200 bg-gray-100 text-gray-700',
  blue: 'border-blue-200 bg-blue-50 text-blue-700',
  amber: 'border-amber-200 bg-amber-50 text-amber-700',
  green: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  red: 'border-rose-200 bg-rose-50 text-rose-700',
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
      className="text-accent font-medium underline decoration-blue-300 underline-offset-2 hover:text-blue-800"
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
  const rawData = dataSourceId ? readDataSourceRows(runtime, dataSourceId) : [];

  // Tree configuration — enables expandable hierarchical rows
  const treeConfig: TreeConfig | undefined = block.table?.treeConfig || (block as any).treeConfig;
  const { visibleRows, toggleExpand } = useTreeData(rawData, treeConfig);
  const selectionConfig = block.table?.selection || (block as any).selection;
  const selectionMode = selectionConfig?.mode || 'single';
  const isMultipleSelection = selectionMode === 'multiple';
  const defaultFirstSelection = Boolean((selectionConfig as any)?.defaultFirst);
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

    if (data.length > 0 && !currentStillVisible) {
      const firstRow = data[0];
      writeRuntimeState(runtime, selectionConfig.bind, firstRow);
      setLocalSelectedRowKey(getRowIdentity(firstRow, 0));
      return;
    }

    if (data.length === 0 && current) {
      writeRuntimeState(runtime, selectionConfig.bind, null);
      setLocalSelectedRowKey('');
    }
  }, [data, runtime, selectionConfig?.bind, isMultipleSelection, defaultFirstSelection, rowKeyField]);

  const writeMultipleSelection = (rows: any[]) => {
    if (!selectionConfig?.bind) return;
    writeRuntimeState(runtime, selectionConfig.bind, rows);
    if ((selectionConfig as any).idsBind) {
      writeRuntimeState(
        runtime,
        (selectionConfig as any).idsBind,
        rows.map((row) => row?.[selectionIdField]).filter((value) => value !== undefined && value !== null),
      );
    }
    setLocalSelectedRowKeys(rows.map((row, index) => getRowIdentity(row, index)));
  };

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
    const nextRows = effectiveSelectedRowKeySet.has(identity)
      ? currentRows.filter((candidate: any, candidateIndex: number) => {
          const candidateIdentity =
            getRowIdentity(candidate) ||
            getRowIdentity(candidate, candidateIndex);
          return candidateIdentity !== identity;
        })
      : [...currentRows, row];
    writeMultipleSelection(nextRows);
  };

  const allVisibleRowsSelected =
    isMultipleSelection &&
    data.length > 0 &&
    data.every((row: any, index: number) => effectiveSelectedRowKeySet.has(getRowIdentity(row, index)));

  const toggleAllVisibleRows = () => {
    if (!isMultipleSelection) return;
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
      return renderActionButtons(row, Array.isArray((column as any).buttons) ? (column as any).buttons : []);
    }

    const value = row[column.field];

    if (column.valueType === 'link' || column.valueType === 'url') {
      return renderLinkCell(column, row, value, locale, t);
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
    if (column.renderType && column.renderType !== 'status-pill'
        && cellRendererRegistry.has(column.renderType)) {
      return cellRendererRegistry.render(column.renderType, {
        value,
        record: row,
        column: column as any,
      } as any);
    }

    // valueType 渲染
    switch (column.valueType) {
      case 'date':
        return new Date(value).toLocaleDateString();

      case 'datetime':
        return new Date(value).toLocaleString();

      case 'currency':
        return new Intl.NumberFormat(locale, {
          style: 'currency',
          currency: column.currencyCode || 'cny',
        }).format(value);

      case 'tag':
        return (
          <span className="rounded-pill inline-flex bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
            {value}
          </span>
        );

      case 'progress':
        return (
          <div className="rounded-pill h-2.5 w-full bg-gray-200">
            <div className="rounded-pill h-2.5 bg-blue-600" style={{ width: `${value}%` }}></div>
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
    const value = row[column.field];
    if (value === null || value === undefined) return undefined;
    return typeof value === 'string' ? value : String(value);
  };

  // 渲染操作按钮
  const renderActionButtons = (row: any, actions: ButtonConfig[]) => {
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

          return (
            <button
              key={button.code}
              data-testid={`row-action-${button.code}`}
              disabled={disabled}
              onClick={(event) => {
                event.stopPropagation();
                handleAction(button, row);
              }}
              className={`text-sm ${
                button.variant === 'danger' || (button as any).danger
                  ? 'text-status-red hover:text-red-800'
                  : 'text-accent hover:text-blue-800'
              } disabled:cursor-not-allowed disabled:opacity-50`}
            >
              {label}
            </button>
          );
        })}
      </div>
    );
  };

  const renderRowActions = (row: any) => renderActionButtons(row, rowActions);

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

  return (
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
                <input
                  type="checkbox"
                  data-testid="table-select-all"
                  checked={allVisibleRowsSelected}
                  onChange={toggleAllVisibleRows}
                  aria-label="Select all rows"
                />
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
                colSpan={columns.length + (rowActions.length > 0 ? 1 : 0) + (isMultipleSelection ? 1 : 0)}
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
                      {renderCellContent(column, row)}
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
  );
};

export default TableBlockRenderer;
