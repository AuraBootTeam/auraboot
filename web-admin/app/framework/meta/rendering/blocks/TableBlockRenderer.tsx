/**
 * TableBlockRenderer - 表格块渲染器
 * 支持新的列配置特性: valueType, render, ellipsis 等
 * 支持字典字段自动翻译显示
 */

import React, { useEffect, useRef, useState } from 'react';
import type { BlockConfig, ColumnConfig, ButtonConfig, TreeConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';

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

export const TableBlockRenderer: React.FC<TableBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  const evaluator = runtime.getEvaluator();
  const dataSourceManager = runtime.getDataSourceManager();

  const columns: ColumnConfig[] = Array.isArray(block.columns)
    ? (block.columns as ColumnConfig[])
    : block.table?.columns || [];
  const rowActions: ButtonConfig[] = Array.isArray(block.rowActions)
    ? (block.rowActions as ButtonConfig[])
    : [];

  // 字典数据缓存
  const dictDataCache = useRef<Map<string, DictItem[]>>(new Map());
  const [dictLoaded, setDictLoaded] = useState(false);

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
  const dataSourceId = block.dataSource;
  const rawData = dataSourceId ? dataSourceManager.getData(dataSourceId) : [];

  // Tree configuration — enables expandable hierarchical rows
  const treeConfig: TreeConfig | undefined = block.table?.treeConfig || (block as any).treeConfig;
  const { visibleRows, toggleExpand } = useTreeData(rawData, treeConfig);

  // Use tree-processed rows when treeConfig is set, otherwise flat data
  const data = treeConfig ? visibleRows : rawData;

  // 渲染列头
  const renderColumnHeader = (column: ColumnConfig) => {
    const label = getLocalizedText(column.label, locale, t);
    return (
      <th
        key={column.field}
        data-testid={`table-th-${column.field}`}
        className={`px-6 py-3 text-${column.align || 'left'} text-xs font-medium tracking-wider text-gray-500 uppercase`}
        style={{ width: column.width }}
      >
        {label}
        {column.sortable && <span className="ml-1 text-gray-400">⇅</span>}
      </th>
    );
  };

  // 渲染单元格内容
  const renderCellContent = (column: ColumnConfig, row: any) => {
    const value = row[column.field];

    // Null/undefined 处理
    if (value === null || value === undefined) {
      return <span className="text-gray-400">-</span>;
    }

    // 如果有 dictCode，尝试翻译值为标签
    if (column.dictCode) {
      const dictItems = dictDataCache.current.get(column.dictCode);
      if (dictItems) {
        const item = dictItems.find((i) => String(i.value) === String(value));
        if (item) {
          // 使用 tag 样式显示字典标签, 颜色从 extension.color 读取
          const tagColor = item.extension?.color || 'blue';
          const tagColorMap: Record<string, string> = {
            gray: 'bg-gray-100 text-gray-800',
            red: 'bg-red-100 text-red-800',
            orange: 'bg-orange-100 text-orange-800',
            yellow: 'bg-yellow-100 text-yellow-800',
            green: 'bg-green-100 text-green-800',
            blue: 'bg-blue-100 text-blue-800',
            indigo: 'bg-indigo-100 text-indigo-800',
            purple: 'bg-purple-100 text-purple-800',
            pink: 'bg-pink-100 text-pink-800',
            cyan: 'bg-cyan-100 text-cyan-800',
          };
          const tagCls = tagColorMap[tagColor] || tagColorMap.blue;
          return (
            <span className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${tagCls}`}>
              {item.label}
            </span>
          );
        }
      }
      // 字典未加载或未找到匹配项时显示原始值
      return String(value);
    }

    // 自定义 render 表达式
    if (column.render) {
      try {
        const rendered = evaluator.evaluateTemplate(column.render, {
          ...context,
          row,
        });
        return <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(rendered) }} />;
      } catch (err) {
        console.error('Column render failed:', err);
        return String(value);
      }
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
          <span className="inline-flex rounded-full bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
            {value}
          </span>
        );

      case 'progress':
        return (
          <div className="h-2.5 w-full rounded-full bg-gray-200">
            <div className="h-2.5 rounded-full bg-blue-600" style={{ width: `${value}%` }}></div>
          </div>
        );

      case 'image':
        return <img src={value} alt="" className="h-8 w-8 rounded object-cover" />;

      default:
        return String(value);
    }
  };

  // 渲染操作按钮
  const renderRowActions = (row: any) => {
    return (
      <div className="flex space-x-2">
        {rowActions.map((button) => {
          // 条件渲染
          if (button.visibleWhen) {
            const visible = evaluator.evaluateCondition(button.visibleWhen, {
              ...context,
              row,
            });
            if (!visible) return null;
          }

          const label = getLocalizedText(button.label || button.content || button.code, locale, t);

          return (
            <button
              key={button.code}
              data-testid={`row-action-${button.code}`}
              onClick={() => handleAction(button, row)}
              className={`text-sm ${
                button.variant === 'danger'
                  ? 'text-red-600 hover:text-red-800'
                  : 'text-blue-600 hover:text-blue-800'
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>
    );
  };

  // 处理操作按钮点击
  const handleAction = async (button: ButtonConfig, row: any) => {
    if (button.handler) {
      try {
        await runtime.executeHandler(button.handler, { row });
      } catch (err) {
        console.error('Action handler failed:', err);
      }
    }
  };

  return (
    <div className="table-block overflow-x-auto" data-testid="table-block">
      <table className="min-w-full divide-y divide-gray-200">
        <thead className="bg-gray-50">
          <tr>
            {columns.map(renderColumnHeader)}
            {rowActions.length > 0 && (
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {t('common.actions') !== 'common.actions' ? t('common.actions') : 'Actions'}
              </th>
            )}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200 bg-white">
          {data.length === 0 ? (
            <tr>
              <td
                colSpan={columns.length + (rowActions.length > 0 ? 1 : 0)}
                className="px-6 py-4 text-center text-gray-500"
              >
                {t('common.noData') !== 'common.noData' ? t('common.noData') : 'No data'}
              </td>
            </tr>
          ) : (
            data.map((row: any, index: number) => (
              <tr
                key={row.id || row.pid || index}
                data-testid={`table-row-${row.id || row.pid || index}`}
                className="hover:bg-gray-50"
              >
                {columns.map((column, colIdx) => (
                  <td
                    key={column.field}
                    className={`px-6 py-4 text-sm text-gray-900 ${
                      column.ellipsis ? 'truncate' : ''
                    }`}
                    style={{
                      maxWidth: column.ellipsis ? column.width : undefined,
                      // Tree indent: apply padding to first column
                      paddingLeft:
                        treeConfig && colIdx === 0 ? `${(row._depth || 0) * 24 + 24}px` : undefined,
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
                              toggleExpand(row.pid || row.id);
                            }}
                            className="flex h-4 w-4 items-center justify-center text-gray-400 hover:text-gray-600"
                            data-testid={`tree-toggle-${row.pid || row.id}`}
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
                  <td className="px-6 py-4 text-sm text-gray-900">{renderRowActions(row)}</td>
                )}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
};

export default TableBlockRenderer;
