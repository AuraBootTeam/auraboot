import React, { useState, useEffect, useMemo, useCallback } from 'react';
import clsx from 'clsx';
import { ChevronUpIcon, ChevronDownIcon, FilterIcon, SearchIcon } from 'lucide-react';
import type { ButtonProps } from '~/studio/domain/schema/smart-components';
import {
  useSmartComponentState,
  useValidation,
  useConditionalRender,
  useDataSource,
  useExpressionValue,
} from '~/studio/hooks/runtime/useSmartComponent';
import { ExpressionParser } from '~/studio/services/runtime/expression/expression-parser';
import { Button } from '~/components/smart/interaction/Button';
import { confirmDialog } from '~/utils/confirmDialog';

type SortDirection = 'asc' | 'desc';

interface SortConfig {
  column: string;
  direction: SortDirection;
}

interface PaginationConfig {
  pageSize?: number;
  current?: number;
  total?: number;
  showTotal?: boolean;
  pageSizeOptions?: number[];
  showSizeChanger?: boolean;
  showQuickJumper?: boolean;
  [key: string]: any;
}

interface TableActionConfig {
  key: string;
  label: string;
  type?:
    | 'default'
    | 'primary'
    | 'secondary'
    | 'danger'
    | 'ghost'
    | 'link'
    | 'outline'
    | 'success'
    | 'warning';
  variant?: ButtonProps['variant'];
  confirm?: boolean | { title: string; content?: string };
  confirmMessage?: string;
  visible?: boolean;
  disabled?: boolean;
  onClick?: string;
  [key: string]: any;
}

interface TableColumnConfig {
  key: string;
  title: string;
  dataIndex?: string;
  width?: number | string;
  align?: 'left' | 'center' | 'right';
  fixed?: 'left' | 'right';
  sortable?: boolean;
  filterable?: boolean;
  render?: string;
  valueType?: string;
  valueEnum?: Record<
    string,
    { text?: string; status?: 'success' | 'warning' | 'error' | 'default' | 'processing' }
  >;
  actions?: TableActionConfig[];
  ellipsis?: boolean;
  copyable?: boolean;
  visible?: boolean | string;
  [key: string]: any;
}

interface TableSchema {
  columns: TableColumnConfig[];
  actions?: TableActionConfig[];
  pagination?: PaginationConfig & { pageSize?: number };
  rowSelection?: boolean | Record<string, any>;
  selection?: boolean | Record<string, any>;
  expandable?:
    | boolean
    | {
        title?: string;
        content?: string;
        rowExpandable?: (record: any) => boolean;
        expandedRowRender?: (record: any) => React.ReactNode;
      };
  summary?: boolean;
  filters?: Array<{
    key: string;
    label: string;
    options?: Array<{ label: string; value: any }>;
    multiple?: boolean;
    type?: string;
    placeholder?: string;
    condition?: string;
  }>;
  scroll?: { x?: number | string; y?: number | string };
  size?: 'small' | 'medium' | 'large';
  bordered?: boolean;
  striped?: boolean;
  showHeader?: boolean;
  [key: string]: any;
}

interface TableProps {
  name?: string;
  schema: TableSchema;
  data?: any[];
  dataSource?: {
    type?: 'api' | 'static' | 'expression';
    url?: string;
    expression?: string;
    params?: Record<string, any>;
  };
  selectedRowKeys?: (string | number)[];
  expandedRowKeys?: (string | number)[];
  loading?: boolean;
  context?: any;
  className?: string;
  visible?: boolean | string;
  onAction?: (action: TableActionConfig, record: any, index: number) => void;
  onSelectionChange?: (keys: (string | number)[], rows: any[]) => void;
  onExpand?: (expanded: boolean, record: any) => void;
  onSort?: (config: SortConfig) => void;
  onFilter?: (filters: Record<string, any>) => void;
  onPageChange?: (page: number, size: number) => void;
}

/**
 * Table 智能表格组件
 *
 * 功能特性：
 * - 支持 TableSchema 驱动的动态表格配置
 * - 支持排序、过滤、分页功能
 * - 支持行选择和批量操作
 * - 支持条件渲染和权限控制
 * - 支持表达式驱动的动态行为
 * - 支持异步数据源加载
 */
export const Table: React.FC<TableProps> = ({
  name,
  schema,
  data: propData = [],
  dataSource,
  selectedRowKeys = [],
  expandedRowKeys = [],
  loading = false,
  context,
  className,
  onAction,
  onSelectionChange,
  onExpand,
  onSort,
  onFilter,
  onPageChange,
  ...props
}) => {
  // 使用智能组件状态管理
  const { value: tableData, handleChange } = useSmartComponentState({
    name: name ?? 'smart-table',
    value: propData,
    onChange: (data) => {
      // 处理数据变化
    },
  });

  // 确保使用正确的数据源
  const actualTableData = useMemo(() => {
    // 如果有表达式数据源，优先使用表达式解析的结果
    if (dataSource?.type === 'expression' && dataSource.expression && context) {
      try {
        const result = ExpressionParser.evaluate(dataSource.expression, context);
        return Array.isArray(result) ? result : [];
      } catch (error) {
        console.error('Failed to evaluate expression data source:', error);
        return [];
      }
    }

    // 否则使用状态管理的数据或传入的数据
    return tableData || propData || [];
  }, [dataSource, context, tableData, propData]);

  // 条件渲染控制
  const isVisible = useConditionalRender(props.visible, context);

  // 状态管理
  const [sortConfig, setSortConfig] = useState<SortConfig | null>(null);
  const [filters, setFilters] = useState<Record<string, any>>({});
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(schema.pagination?.pageSize || 20);
  const [selectedKeys, setSelectedKeys] = useState<(string | number)[]>(selectedRowKeys);
  const [expandedKeys, setExpandedKeys] = useState<(string | number)[]>(expandedRowKeys);
  const [filterVisible, setFilterVisible] = useState<Record<string, boolean>>({});

  // 异步数据加载
  useEffect(() => {
    if (dataSource) {
      loadTableData();
    }
  }, [dataSource, currentPage, pageSize, sortConfig, filters, context]);

  const loadTableData = async () => {
    try {
      if (dataSource?.type === 'api' && dataSource.url) {
        const params = {
          page: currentPage,
          pageSize,
          sort: sortConfig ? `${sortConfig.column},${sortConfig.direction}` : undefined,
          ...filters,
          ...dataSource.params,
        };

        const searchParams = new URLSearchParams();
        Object.entries(params).forEach(([key, value]) => {
          if (value !== undefined && value !== null) {
            searchParams.set(key, String(value));
          }
        });
        const response = await fetch(`${dataSource.url}?${searchParams.toString()}`);
        const result = await response.json();

        if (result.success) {
          handleChange(result.data.content || result.data);
        }
      } else if (dataSource?.type === 'expression' && dataSource.expression) {
        const result = ExpressionParser.evaluate(dataSource.expression, context);
        if (Array.isArray(result)) {
          handleChange(result);
        }
      }
    } catch (error) {
      console.error('Failed to load table data:', error);
    }
  };

  // 初始化时加载表达式数据源
  useEffect(() => {
    if (dataSource?.type === 'expression' && dataSource.expression && context) {
      const result = ExpressionParser.evaluate(dataSource.expression, context);
      if (Array.isArray(result)) {
        handleChange(result);
      }
    }
  }, [dataSource, context, handleChange]);

  // 处理排序
  const handleSort = useCallback(
    (column: string) => {
      const newSortConfig: SortConfig = {
        column,
        direction: sortConfig?.column === column && sortConfig.direction === 'asc' ? 'desc' : 'asc',
      };
      setSortConfig(newSortConfig);
      onSort?.(newSortConfig);
    },
    [sortConfig, onSort],
  );

  // 处理过滤
  const handleFilter = useCallback(
    (key: string, value: any) => {
      const newFilters = { ...filters, [key]: value };
      setFilters(newFilters);
      setCurrentPage(1); // 重置到第一页
      onFilter?.(newFilters);
    },
    [filters, onFilter],
  );

  // 处理行选择
  const handleRowSelection = useCallback(
    (rowKey: string | number, selected: boolean) => {
      const newSelectedKeys = selected
        ? [...selectedKeys, rowKey]
        : selectedKeys.filter((key) => key !== rowKey);

      setSelectedKeys(newSelectedKeys);

      const selectedRows = actualTableData.filter((_record: any, index: number) =>
        newSelectedKeys.includes(getRowKey(actualTableData[index], index)),
      );

      onSelectionChange?.(newSelectedKeys, selectedRows);
    },
    [selectedKeys, actualTableData, onSelectionChange],
  );

  // 处理全选
  const handleSelectAll = useCallback(
    (selected: boolean) => {
      const allKeys = actualTableData.map((record: any, index: number) => getRowKey(record, index));
      const newSelectedKeys = selected ? allKeys : [];

      setSelectedKeys(newSelectedKeys);

      const selectedRows = selected ? actualTableData : [];
      onSelectionChange?.(newSelectedKeys, selectedRows);
    },
    [actualTableData, onSelectionChange],
  );

  // 处理行展开
  const handleRowExpand = useCallback(
    (record: any, expanded: boolean) => {
      const rowKey = getRowKey(record, actualTableData.indexOf(record));
      const newExpandedKeys = expanded
        ? [...expandedKeys, rowKey]
        : expandedKeys.filter((key) => key !== rowKey);

      setExpandedKeys(newExpandedKeys);
      onExpand?.(expanded, record);
    },
    [expandedKeys, actualTableData, onExpand],
  );

  // 处理分页
  const handlePageChange = useCallback(
    (page: number, size?: number) => {
      setCurrentPage(page);
      if (size && size !== pageSize) {
        setPageSize(size);
      }
      onPageChange?.(page, size || pageSize);
    },
    [pageSize, onPageChange],
  );

  // 获取行键值
  const getRowKey = (record: any, index: number): string | number => {
    return record.id || record.key || index;
  };

  // 渲染单元格内容
  const renderCell = (value: any, column: TableColumnConfig, record: any, index: number) => {
    // 1. 优先使用自定义 render 表达式
    if (column.render) {
      try {
        const cellContext = { ...context, $value: value, $record: record, $index: index };
        const result = ExpressionParser.evaluate(column.render, cellContext);
        return result !== undefined ? result : value;
      } catch (error) {
        console.error('Failed to render cell:', error);
        return value;
      }
    }

    // 2. 根据 valueType 渲染
    let renderedValue: React.ReactNode = value;

    if (column.valueType && value !== null && value !== undefined) {
      switch (column.valueType) {
        case 'date':
          renderedValue = new Date(value).toLocaleDateString();
          break;

        case 'datetime':
          renderedValue = new Date(value).toLocaleString();
          break;

        case 'time':
          renderedValue = new Date(value).toLocaleTimeString();
          break;

        case 'currency':
          renderedValue = new Intl.NumberFormat('zh-CN', {
            style: 'currency',
            currency: 'cny',
          }).format(Number(value));
          break;

        case 'percent':
          renderedValue = `${(Number(value) * 100).toFixed(2)}%`;
          break;

        case 'number':
          renderedValue = new Intl.NumberFormat('zh-CN').format(Number(value));
          break;

        case 'tag':
        case 'badge':
          const enumValue = column.valueEnum?.[value];
          const tagText = enumValue?.text || value;
          const statusClass = enumValue?.status || 'default';
          renderedValue = (
            <span
              className={clsx(
                'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
                statusClass === 'success' && 'bg-green-100 text-green-800',
                statusClass === 'warning' && 'bg-yellow-100 text-yellow-800',
                statusClass === 'error' && 'bg-red-100 text-red-800',
                statusClass === 'default' && 'bg-gray-100 text-gray-800',
                statusClass === 'processing' && 'bg-blue-100 text-blue-800',
              )}
            >
              {tagText}
            </span>
          );
          break;

        case 'progress':
          const percentage = Math.min(100, Math.max(0, Number(value)));
          renderedValue = (
            <div className="flex items-center gap-2">
              <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-200">
                <div
                  className={clsx(
                    'h-full rounded-full transition-all',
                    percentage < 30 && 'bg-red-500',
                    percentage >= 30 && percentage < 70 && 'bg-yellow-500',
                    percentage >= 70 && 'bg-green-500',
                  )}
                  style={{ width: `${percentage}%` }}
                />
              </div>
              <span className="w-12 text-right text-xs text-gray-600">{percentage}%</span>
            </div>
          );
          break;

        case 'image':
          renderedValue = (
            <img
              src={String(value)}
              alt=""
              className="h-8 w-8 rounded object-cover"
              onError={(e) => {
                (e.target as HTMLImageElement).src =
                  'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="32" height="32"%3E%3Crect fill="%23ddd" width="32" height="32"/%3E%3C/svg%3E';
              }}
            />
          );
          break;

        case 'link':
          renderedValue = (
            <a
              href={String(value)}
              target="_blank"
              rel="noopener noreferrer"
              className="text-blue-600 hover:text-blue-800 hover:underline"
            >
              {String(value)}
            </a>
          );
          break;

        case 'code':
          renderedValue = (
            <code className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-sm">
              {String(value)}
            </code>
          );
          break;

        case 'boolean': {
          const boolVal =
            typeof value === 'boolean'
              ? value
              : typeof value === 'string'
                ? value.toLowerCase() === 'true'
                : Boolean(value);
          renderedValue = (
            <span className={boolVal ? 'text-green-600' : 'text-gray-600'}>
              {boolVal ? '是' : '否'}
            </span>
          );
          break;
        }

        case 'text':
        default:
          renderedValue = String(value);
          break;
      }
    }

    // 3. 处理 ellipsis
    if (column.ellipsis && typeof renderedValue === 'string' && renderedValue.length > 50) {
      renderedValue = (
        <span title={renderedValue} className="block truncate">
          {renderedValue}
        </span>
      );
    }

    // 4. 处理 copyable
    if (column.copyable && renderedValue) {
      return (
        <div className="group flex items-center gap-2">
          <span className="flex-1">{renderedValue}</span>
          <button
            onClick={() => {
              navigator.clipboard.writeText(String(value));
              // TODO: Show toast notification
            }}
            className="rounded p-1 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-gray-100"
            title="复制"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
              />
            </svg>
          </button>
        </div>
      );
    }

    // 5. 确保返回有效的显示值
    return renderedValue !== undefined && renderedValue !== null ? renderedValue : '';
  };

  // 渲染操作按钮
  const renderActions = (record: any, index: number) => {
    if (!schema.actions) return null;

    return (
      <div className="flex gap-2">
        {schema.actions.map((action) => {
          const actionContext = { ...context, $record: record, $index: index };

          // 检查可见性
          const isActionVisible = action.visible
            ? ExpressionParser.evaluate(action.visible, actionContext)
            : true;

          if (!isActionVisible) return null;

          // 检查禁用状态
          const isActionDisabled = action.disabled
            ? ExpressionParser.evaluate(action.disabled, actionContext)
            : false;

          return (
            <Button
              key={action.key}
              name={`${name}_action_${action.key}`}
              size="small"
              variant={action.type === 'danger' ? 'danger' : 'default'}
              disabled={isActionDisabled}
              onClick={async () => {
                if (action.confirm) {
                  const confirmTitle =
                    typeof action.confirm === 'object' ? action.confirm.title : '确认操作';
                  const confirmContent =
                    typeof action.confirm === 'object' ? action.confirm.content || '' : '';
                  const confirmed = await confirmDialog({
                    title: confirmTitle,
                    content: confirmContent,
                    variant: action.type === 'danger' ? 'danger' : 'default',
                  });
                  if (confirmed) {
                    onAction?.(action, record, index);
                  }
                } else {
                  onAction?.(action, record, index);
                }
              }}
              className={clsx(
                action.type === 'danger' && 'border-red-300 text-red-600 hover:bg-red-50',
                action.type === 'success' && 'border-green-300 text-green-600 hover:bg-green-50',
                action.type === 'warning' && 'border-yellow-300 text-yellow-600 hover:bg-yellow-50',
              )}
            >
              {action.label}
            </Button>
          );
        })}
      </div>
    );
  };

  // 渲染过滤器
  const renderFilter = (column: TableColumnConfig) => {
    const filter = schema.filters?.find((f) => f.key === column.key);
    if (!filter) return null;

    const isFilterVisible = filterVisible[column.key];

    return (
      <div className="relative">
        <button
          onClick={() => setFilterVisible((prev) => ({ ...prev, [column.key]: !prev[column.key] }))}
          className="rounded p-1 hover:bg-gray-100"
        >
          <FilterIcon className="h-4 w-4" />
        </button>

        {isFilterVisible && (
          <div className="absolute top-full left-0 z-10 min-w-48 rounded-md border border-gray-200 bg-white p-3 shadow-lg">
            {filter.type === 'input' && (
              <input
                type="text"
                placeholder={filter.placeholder}
                className="w-full rounded-md border border-gray-300 px-3 py-2"
                onChange={(e) => handleFilter(column.key, e.target.value)}
              />
            )}

            {filter.type === 'select' && (
              <select
                className="w-full rounded-md border border-gray-300 px-3 py-2"
                onChange={(e) => handleFilter(column.key, e.target.value)}
              >
                <option value="">全部</option>
                {filter.options?.map((option: { label: string; value: any }) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            )}
          </div>
        )}
      </div>
    );
  };

  // 过滤和排序数据
  const processedData = useMemo(() => {
    let result = [...actualTableData];

    // 应用过滤器
    if (schema.filters && Object.keys(filters).length > 0) {
      result = result.filter((record: any) => {
        return schema.filters!.every((filter: any) => {
          const filterValue = filters[filter.key];
          if (!filterValue) return true;

          try {
            const filterContext = { ...context, $record: record, $filter: filterValue };
            return ExpressionParser.evaluate(filter.condition, filterContext);
          } catch (error) {
            console.error('Filter evaluation error:', error);
            return true;
          }
        });
      });
    }

    // 应用排序
    if (sortConfig) {
      result.sort((a, b) => {
        const aValue = a[sortConfig.column];
        const bValue = b[sortConfig.column];

        if (aValue === bValue) return 0;

        const comparison = aValue > bValue ? 1 : -1;
        return sortConfig.direction === 'asc' ? comparison : -comparison;
      });
    }

    return result;
  }, [actualTableData, filters, sortConfig, schema.filters, context]);

  // 分页数据
  const paginatedData = useMemo(() => {
    if (!schema.pagination) return processedData;

    const start = (currentPage - 1) * pageSize;
    const end = start + pageSize;
    return processedData.slice(start, end);
  }, [processedData, currentPage, pageSize, schema.pagination]);

  const selectionConfig =
    typeof schema.selection === 'object'
      ? schema.selection
      : schema.selection
        ? { type: 'checkbox' }
        : undefined;
  const isSelectionEnabled = Boolean(selectionConfig);
  const expandableConfig = typeof schema.expandable === 'object' ? schema.expandable : undefined;
  const isExpandableEnabled = schema.expandable === true || Boolean(expandableConfig);

  // 渲染分页组件
  const renderPagination = () => {
    if (!schema.pagination) return null;

    const totalPages = Math.ceil(processedData.length / pageSize);
    const paginationConfig: PaginationConfig = {
      current: currentPage,
      pageSize,
      total: processedData.length,
      ...schema.pagination,
    };

    return (
      <div className="flex items-center justify-between border-t border-gray-200 px-6 py-3">
        <div className="flex items-center gap-4">
          {paginationConfig.showTotal && (
            <span className="text-sm text-gray-700">共 {paginationConfig.total} 条记录</span>
          )}

          {paginationConfig.showSizeChanger && (
            <select
              value={pageSize}
              onChange={(e) => handlePageChange(1, Number(e.target.value))}
              className="rounded-md border border-gray-300 px-3 py-1 text-sm"
            >
              {(paginationConfig.pageSizeOptions || [10, 20, 50, 100]).map((size: number) => (
                <option key={size} value={size}>
                  {size} 条/页
                </option>
              ))}
            </select>
          )}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => handlePageChange(currentPage - 1)}
            disabled={currentPage <= 1}
            className="rounded-md border border-gray-300 px-3 py-1 text-sm disabled:cursor-not-allowed disabled:opacity-50"
          >
            上一页
          </button>

          <span className="px-3 py-1 text-sm">
            {currentPage} / {totalPages}
          </span>

          <button
            onClick={() => handlePageChange(currentPage + 1)}
            disabled={currentPage >= totalPages}
            className="rounded-md border border-gray-300 px-3 py-1 text-sm disabled:cursor-not-allowed disabled:opacity-50"
          >
            下一页
          </button>

          {paginationConfig.showQuickJumper && (
            <div className="ml-4 flex items-center gap-2">
              <span className="text-sm">跳转到</span>
              <input
                type="number"
                min={1}
                max={totalPages}
                className="w-16 rounded-md border border-gray-300 px-2 py-1 text-sm"
                onKeyPress={(e) => {
                  if (e.key === 'Enter') {
                    const page = Number((e.target as HTMLInputElement).value);
                    if (page >= 1 && page <= totalPages) {
                      handlePageChange(page);
                    }
                  }
                }}
              />
              <span className="text-sm">页</span>
            </div>
          )}
        </div>
      </div>
    );
  };

  if (!isVisible) return null;

  return (
    <div
      className={clsx(
        'smart-table rounded-lg border border-gray-200 bg-white shadow-sm',
        className,
      )}
    >
      {/* 表格容器 */}
      <div
        className={clsx(
          'overflow-auto',
          schema.scroll?.x && 'overflow-x-auto',
          schema.scroll?.y && 'overflow-y-auto',
        )}
        style={{
          maxHeight: schema.scroll?.y,
          maxWidth: schema.scroll?.x,
        }}
      >
        <table
          className={clsx(
            'w-full',
            schema.size === 'small' && 'text-sm',
            schema.size === 'large' && 'text-lg',
            schema.bordered && 'border-collapse border border-gray-300',
            schema.striped && '[&_tbody_tr:nth-child(even)]:bg-gray-50',
          )}
        >
          {/* 表头 */}
          <thead className="border-b border-gray-200 bg-gray-50">
            <tr>
              {/* 选择列 */}
              {isSelectionEnabled && (
                <th className="px-4 py-3 text-left">
                  {selectionConfig?.type === 'checkbox' && (
                    <input
                      type="checkbox"
                      checked={
                        selectedKeys.length === paginatedData.length && paginatedData.length > 0
                      }
                      onChange={(e) => handleSelectAll(e.target.checked)}
                      className="rounded border-gray-300"
                    />
                  )}
                </th>
              )}

              {/* 展开列 */}
              {isExpandableEnabled && <th className="w-12 px-4 py-3 text-left"></th>}

              {/* 数据列 */}
              {schema.columns.map((column) => {
                const columnContext = { ...context, $column: column };
                const isColumnVisible = column.visible
                  ? ExpressionParser.evaluate(column.visible, columnContext)
                  : true;

                if (!isColumnVisible) return null;

                return (
                  <th
                    key={column.key}
                    className={clsx(
                      'px-4 py-3 font-medium text-gray-900',
                      column.align === 'center' && 'text-center',
                      column.align === 'right' && 'text-right',
                      column.sortable && 'cursor-pointer hover:bg-gray-100',
                      column.fixed === 'left' && 'sticky left-0 z-10 bg-gray-50',
                      column.fixed === 'right' && 'sticky right-0 z-10 bg-gray-50',
                    )}
                    style={{ width: column.width }}
                    onClick={() => column.sortable && handleSort(column.key)}
                  >
                    <div className="flex items-center gap-2">
                      <span>{column.title}</span>

                      {column.sortable && (
                        <div className="flex flex-col">
                          <ChevronUpIcon
                            className={clsx(
                              'h-3 w-3',
                              sortConfig?.column === column.key && sortConfig.direction === 'asc'
                                ? 'text-blue-600'
                                : 'text-gray-400',
                            )}
                          />
                          <ChevronDownIcon
                            className={clsx(
                              '-mt-1 h-3 w-3',
                              sortConfig?.column === column.key && sortConfig.direction === 'desc'
                                ? 'text-blue-600'
                                : 'text-gray-400',
                            )}
                          />
                        </div>
                      )}

                      {column.filterable && renderFilter(column)}
                    </div>
                  </th>
                );
              })}

              {/* 操作列 */}
              {schema.actions && schema.actions.length > 0 && (
                <th className="px-4 py-3 text-left">操作</th>
              )}
            </tr>
          </thead>

          {/* 表体 */}
          <tbody>
            {loading ? (
              <tr>
                <td
                  colSpan={
                    schema.columns.length +
                    (schema.selection ? 1 : 0) +
                    (schema.expandable ? 1 : 0) +
                    (schema.actions ? 1 : 0)
                  }
                  className="px-4 py-8 text-center text-gray-500"
                >
                  加载中...
                </td>
              </tr>
            ) : paginatedData?.length === 0 ? (
              <tr>
                <td
                  colSpan={
                    schema.columns.length +
                    (schema.selection ? 1 : 0) +
                    (schema.expandable ? 1 : 0) +
                    (schema.actions ? 1 : 0)
                  }
                  className="px-4 py-8 text-center text-gray-500"
                >
                  暂无数据
                </td>
              </tr>
            ) : (
              paginatedData.map((record, index) => {
                const rowKey = getRowKey(record, index);
                const isSelected = selectedKeys.includes(rowKey);
                const isExpanded = expandedKeys.includes(rowKey);

                return (
                  <React.Fragment key={rowKey}>
                    <tr
                      className={clsx(
                        'border-b border-gray-200 hover:bg-gray-50',
                        isSelected && 'bg-blue-50',
                      )}
                    >
                      {/* 选择列 */}
                      {isSelectionEnabled && (
                        <td className="px-4 py-3">
                          <input
                            type={selectionConfig?.type}
                            name={
                              selectionConfig?.type === 'radio' ? `${name}_selection` : undefined
                            }
                            checked={isSelected}
                            onChange={(e) => handleRowSelection(rowKey, e.target.checked)}
                            className="rounded border-gray-300"
                          />
                        </td>
                      )}

                      {/* 展开列 */}
                      {isExpandableEnabled && (
                        <td className="px-4 py-3">
                          {expandableConfig?.rowExpandable ? (
                            ExpressionParser.evaluate(expandableConfig.rowExpandable, {
                              ...context,
                              $record: record,
                            }) && (
                              <button
                                onClick={() => handleRowExpand(record, !isExpanded)}
                                className="rounded p-1 hover:bg-gray-100"
                              >
                                {isExpanded ? (
                                  <ChevronDownIcon className="h-4 w-4" />
                                ) : (
                                  <ChevronUpIcon className="h-4 w-4" />
                                )}
                              </button>
                            )
                          ) : (
                            <button
                              onClick={() => handleRowExpand(record, !isExpanded)}
                              className="rounded p-1 hover:bg-gray-100"
                            >
                              {isExpanded ? (
                                <ChevronDownIcon className="h-4 w-4" />
                              ) : (
                                <ChevronUpIcon className="h-4 w-4" />
                              )}
                            </button>
                          )}
                        </td>
                      )}

                      {/* 数据列 */}
                      {schema.columns.map((column) => {
                        const columnContext = { ...context, $column: column };
                        const isColumnVisible = column.visible
                          ? ExpressionParser.evaluate(column.visible, columnContext)
                          : true;

                        if (!isColumnVisible) return null;

                        const cellValue = record[column.dataIndex || column.key];

                        return (
                          <td
                            key={column.key}
                            className={clsx(
                              'px-4 py-3',
                              column.align === 'center' && 'text-center',
                              column.align === 'right' && 'text-right',
                              column.fixed === 'left' && 'sticky left-0 z-10 bg-white',
                              column.fixed === 'right' && 'sticky right-0 z-10 bg-white',
                              column.ellipsis && 'max-w-0',
                            )}
                          >
                            {renderCell(cellValue, column, record, index)}
                          </td>
                        );
                      })}

                      {/* 操作列 */}
                      {schema.actions && schema.actions.length > 0 && (
                        <td className="px-4 py-3">{renderActions(record, index)}</td>
                      )}
                    </tr>

                    {/* 展开行 */}
                    {isExpandableEnabled && isExpanded && (
                      <tr>
                        <td
                          colSpan={
                            schema.columns.length +
                            (isSelectionEnabled ? 1 : 0) +
                            (isExpandableEnabled ? 1 : 0) +
                            (schema.actions ? 1 : 0)
                          }
                          className="bg-gray-50 px-4 py-3"
                        >
                          {expandableConfig?.expandedRowRender && (
                            <div>
                              {ExpressionParser.evaluate(expandableConfig?.expandedRowRender, {
                                ...context,
                                $record: record,
                                $index: index,
                              })}
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* 分页 */}
      {renderPagination()}
    </div>
  );
};

export default Table;
