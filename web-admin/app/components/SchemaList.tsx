import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router';
import { schemaService, type PageSchema } from '~/services/schemaService';
import type { DynamicEntity } from '~/types/dynamic';
import { SchemaRenderer } from '~/components/SchemaRenderer';

interface SchemaListProps {
  pageKey: string;
  schema?: PageSchema;
  title?: string;
  showActions?: boolean;
  onRowClick?: (record: DynamicEntity) => void;
}

/**
 * 基于 Schema 驱动的列表组件
 * 通过 pageKey 获取 schema 并动态渲染表格
 */
export function SchemaList({
  pageKey,
  schema: initialSchema,
  title,
  showActions: _showActions = true,
  onRowClick,
}: SchemaListProps) {
  const [schema, setSchema] = useState<PageSchema | null>(initialSchema || null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [pagination, setPagination] = useState({
    current: 1,
    pageSize: 20,
    total: 0,
  });
  const [searchKeyword, setSearchKeyword] = useState('');

  // 获取 schema
  const loadSchema = useCallback(async () => {
    // 如果已经有 schema，就不需要再加载了
    if (initialSchema) {
      setSchema(initialSchema);
      return;
    }

    try {
      setLoading(true);
      const schemaData = await schemaService.getPageSchema(pageKey);
      setSchema(schemaData);
    } catch (err) {
      setError(err instanceof Error ? err.message : '获取 schema 失败');
    } finally {
      setLoading(false);
    }
  }, [pageKey, initialSchema]);

  // 加载数据
  const loadData = useCallback(
    async (params?: { page?: number; size?: number; keyword?: string }) => {
      if (!schema) return;

      try {
        setLoading(true);
        const queryRequest = {
          page: params?.page ?? pagination.current - 1,
          size: params?.size ?? pagination.pageSize,
          keyword: params?.keyword ?? searchKeyword,
        };

        const result = await schemaService.executeQuery(pageKey, queryRequest);
        setData(result.records);
        setPagination((prev) => ({
          ...prev,
          total: result.total,
          current: result.page + 1,
        }));
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载数据失败');
      } finally {
        setLoading(false);
      }
    },
    [pageKey, schema, pagination.current, pagination.pageSize, searchKeyword],
  );

  // 初始化
  useEffect(() => {
    loadSchema();
  }, [loadSchema]);

  // schema 加载完成后加载数据
  useEffect(() => {
    if (schema) {
      loadData();
    }
  }, [schema]);

  // 搜索处理
  const handleSearch = useCallback(() => {
    setPagination((prev) => ({ ...prev, current: 1 }));
    loadData({ keyword: searchKeyword, page: 0 });
  }, [searchKeyword, loadData]);

  // 分页处理
  const handlePageChange = useCallback(
    (page: number) => {
      setPagination((prev) => ({ ...prev, current: page }));
      loadData({ page: page - 1 });
    },
    [loadData],
  );

  // 获取列配置
  const getColumns = () => {
    if (!schema) return [];

    // 从 regions 中查找 table 类型的区域
    const tableRegion = schema.regions?.find((region) => region.type === 'table');
    if (tableRegion?.columns) {
      return tableRegion.columns;
    }

    return [];
  };

  // 渲染单元格内容
  const renderCellContent = useCallback(
    (column: any, record: DynamicEntity) => {
      const value = record[column.code || column.field || column.dataIndex];

      if (value === null || value === undefined) {
        return <span className="text-gray-400">-</span>;
      }

      switch (column.type || column.render) {
        case 'date':
          return new Date(value).toLocaleDateString();
        case 'datetime':
          return new Date(value).toLocaleString();
        case 'boolean':
          return (
            <span
              className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${
                value ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
              }`}
            >
              {value ? '是' : '否'}
            </span>
          );
        case 'status':
          return (
            <span className="inline-flex rounded-full bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
              {value}
            </span>
          );
        case 'link':
          return (
            <Link
              to={`/dynamic/${schema?.meta?.entityCode}/${record.id}`}
              className="text-blue-600 underline hover:text-blue-800"
            >
              {value}
            </Link>
          );
        default:
          return String(value);
      }
    },
    [schema],
  );

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-6">
        <h3 className="mb-2 text-lg font-medium text-red-800">加载失败</h3>
        <p className="mb-4 text-red-600">{error}</p>
        <button
          onClick={() => {
            setError(null);
            loadSchema();
          }}
          className="rounded-md bg-red-600 px-4 py-2 text-white hover:bg-red-700"
        >
          重试
        </button>
      </div>
    );
  }

  if (loading && !schema) {
    return (
      <div className="flex items-center justify-center p-8">
        <div className="flex items-center space-x-2">
          <svg
            className="h-5 w-5 animate-spin text-blue-600"
            xmlns="http://www.w3.org/2000/svg"
            fill="none"
            viewBox="0 0 24 24"
          >
            <circle
              className="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              strokeWidth="4"
            ></circle>
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            ></path>
          </svg>
          <span className="text-gray-600">加载中...</span>
        </div>
      </div>
    );
  }

  // 如果有 schema，使用新的 SchemaRenderer
  if (schema) {
    return (
      <SchemaRenderer
        schema={schema}
        data={data}
        loading={loading}
        pagination={pagination}
        onSearch={(filters) => {
          // 处理搜索逻辑
          loadData({ ...filters, page: 0 });
        }}
        onPageChange={handlePageChange}
        onRowClick={onRowClick}
      />
    );
  }

  // 兼容性：如果没有 schema，使用原有的渲染逻辑
  const columns = getColumns();

  return (
    <div className="rounded-lg bg-white shadow-sm">
      {/* 头部 */}
      <div className="border-b border-gray-200 px-6 py-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-medium text-gray-900">{title || '数据列表'}</h2>
          <Link
            to={`/dynamic/unknown/new`}
            className="inline-flex items-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            新建
          </Link>
        </div>

        {/* 搜索 */}
        <div className="mt-4 flex items-center space-x-4">
          <div className="relative max-w-md flex-1">
            <input
              type="text"
              placeholder="搜索..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              onKeyPress={(e) => {
                if (e.key === 'Enter') {
                  handleSearch();
                }
              }}
              className="block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-blue-500 focus:ring-blue-500 focus:outline-none"
            />
            <button
              onClick={handleSearch}
              className="absolute top-1/2 right-2 -translate-y-1/2 transform text-gray-400 hover:text-gray-600"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
            </button>
          </div>
        </div>
      </div>

      {/* 表格 */}
      <div className="overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              {columns.map((column: any) => (
                <th
                  key={column.code || column.field || column.dataIndex}
                  className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                >
                  {column.title || column.label || column.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {loading ? (
              <tr>
                <td colSpan={columns.length} className="px-6 py-4 text-center">
                  <div className="flex items-center justify-center">
                    <svg
                      className="mr-2 h-5 w-5 animate-spin text-blue-600"
                      xmlns="http://www.w3.org/2000/svg"
                      fill="none"
                      viewBox="0 0 24 24"
                    >
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      ></circle>
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                      ></path>
                    </svg>
                    加载中...
                  </div>
                </td>
              </tr>
            ) : data?.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-6 py-4 text-center text-gray-500">
                  暂无数据
                </td>
              </tr>
            ) : (
              data.map((record, index) => (
                <tr
                  key={record.id || index}
                  onClick={() => onRowClick?.(record)}
                  className={onRowClick ? 'cursor-pointer hover:bg-gray-50' : ''}
                >
                  {columns.map((column: any) => (
                    <td
                      key={column.code || column.field || column.dataIndex}
                      className="px-6 py-4 text-sm whitespace-nowrap text-gray-900"
                    >
                      {renderCellContent(column, record)}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 分页 */}
      {pagination.total > 0 && (
        <div className="flex items-center justify-between border-t border-gray-200 px-6 py-3">
          <div className="flex flex-1 justify-between sm:hidden">
            <button
              onClick={() => handlePageChange(pagination.current - 1)}
              disabled={pagination.current <= 1}
              className="relative inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              上一页
            </button>
            <button
              onClick={() => handlePageChange(pagination.current + 1)}
              disabled={pagination.current >= Math.ceil(pagination.total / pagination.pageSize)}
              className="relative ml-3 inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              下一页
            </button>
          </div>
          <div className="hidden sm:flex sm:flex-1 sm:items-center sm:justify-between">
            <div>
              <p className="text-sm text-gray-700">
                显示第{' '}
                <span className="font-medium">
                  {(pagination.current - 1) * pagination.pageSize + 1}
                </span>{' '}
                到{' '}
                <span className="font-medium">
                  {Math.min(pagination.current * pagination.pageSize, pagination.total)}
                </span>{' '}
                条，共 <span className="font-medium">{pagination.total}</span> 条记录
              </p>
            </div>
            <div>
              <nav
                className="relative z-0 inline-flex -space-x-px rounded-md shadow-sm"
                aria-label="Pagination"
              >
                <button
                  onClick={() => handlePageChange(pagination.current - 1)}
                  disabled={pagination.current <= 1}
                  className="relative inline-flex items-center rounded-l-md border border-gray-300 bg-white px-2 py-2 text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  上一页
                </button>
                <button
                  onClick={() => handlePageChange(pagination.current + 1)}
                  disabled={pagination.current >= Math.ceil(pagination.total / pagination.pageSize)}
                  className="relative inline-flex items-center rounded-r-md border border-gray-300 bg-white px-2 py-2 text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  下一页
                </button>
              </nav>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default SchemaList;
