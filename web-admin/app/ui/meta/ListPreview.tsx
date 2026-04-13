/**
 * 列表预览组件
 *
 * 根据Model和Field配置动态生成列表预览
 *
 * 功能特性:
 * - 根据Field配置动态生成列表列
 * - 支持Dict关联的标签显示
 * - 示例数据展示
 * - 搜索、过滤、排序功能
 * - 分页控件
 *
 * 需求: 10.1-10.9
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import type { MetaModelDTO, ModelFieldBinding } from '~/types/model';

/**
 * 列表预览Props
 */
interface ListPreviewProps {
  /** 是否显示预览 */
  visible: boolean;
  /** Model信息 */
  model: MetaModelDTO | null;
  /** Field绑定列表 */
  fieldBindings: ModelFieldBinding[];
  /** 关闭回调 */
  onClose: () => void;
}

/**
 * 示例数据行
 */
interface SampleDataRow {
  id: string;
  [fieldCode: string]: any;
}

/**
 * 列表预览组件
 */
export function ListPreview({ visible, model, fieldBindings, onClose }: ListPreviewProps) {
  // 示例数据
  const [sampleData, setSampleData] = useState<SampleDataRow[]>([]);

  // 字典选项缓存
  const [dictOptions, setDictOptions] = useState<Record<string, Record<string, string>>>({});

  // 搜索关键词
  const [searchKeyword, setSearchKeyword] = useState('');

  // 排序配置
  const [sortConfig, setSortConfig] = useState<{ field: string; direction: 'asc' | 'desc' } | null>(
    null,
  );

  // 分页配置
  const [pagination, setPagination] = useState({
    page: 1,
    pageSize: 20,
    total: 0,
  });

  // 加载状态

  /**
   * 初始化数据
   */
  useEffect(() => {
    if (visible && model && fieldBindings.length > 0) {
      loadDictOptions();
      generateSampleData();
    }
  }, [visible, model, fieldBindings]);

  /**
   * 加载字典选项
   */
  const loadDictOptions = useCallback(async () => {
    try {
      const dictCodes = fieldBindings
        .filter((binding) => binding.dictCode)
        .map((binding) => binding.dictCode!);

      if (dictCodes.length === 0) return;

      // TODO: 调用API批量加载字典选项
      // const options = await dictService.batchGetOptionsMap(dictCodes);
      // setDictOptions(options);

      // 模拟数据
      const mockOptions: Record<string, Record<string, string>> = {};
      dictCodes.forEach((code) => {
        mockOptions[code] = {
          '1': '选项1',
          '2': '选项2',
          '3': '选项3',
        };
      });
      setDictOptions(mockOptions);
    } catch (error) {
      console.error('Failed to load dict options:', error);
    }
  }, [fieldBindings]);

  /**
   * 生成示例数据
   */
  const generateSampleData = useCallback(() => {
    const data: SampleDataRow[] = [];

    // 生成15条示例数据
    for (let i = 1; i <= 15; i++) {
      const row: SampleDataRow = {
        id: `sample-${i}`,
      };

      fieldBindings.forEach((binding) => {
        // 根据字段类型生成示例值
        const dataType = binding.dataType || 'string';

        if (binding.dictCode) {
          // 字典字段 - 随机选择一个选项
          row[binding.fieldCode] = String((i % 3) + 1);
        } else if (dataType === 'boolean') {
          row[binding.fieldCode] = i % 2 === 0;
        } else if (['integer', 'long'].includes(dataType)) {
          row[binding.fieldCode] = i * 100;
        } else if (dataType === 'decimal') {
          row[binding.fieldCode] = (i * 99.99).toFixed(2);
        } else if (dataType === 'date') {
          const date = new Date();
          date.setDate(date.getDate() - i);
          row[binding.fieldCode] = date.toISOString().split('T')[0];
        } else if (dataType === 'datetime') {
          const date = new Date();
          date.setHours(date.getHours() - i);
          row[binding.fieldCode] = date.toISOString();
        } else {
          // 默认字符串
          row[binding.fieldCode] = `${binding.fieldName}示例${i}`;
        }
      });

      data.push(row);
    }

    setSampleData(data);
    setPagination((prev) => ({ ...prev, total: data.length }));
  }, [fieldBindings]);

  /**
   * 处理搜索
   */
  const handleSearch = useCallback((keyword: string) => {
    setSearchKeyword(keyword);
    setPagination((prev) => ({ ...prev, page: 1 }));
  }, []);

  /**
   * 处理排序
   */
  const handleSort = useCallback((fieldCode: string) => {
    setSortConfig((prev) => {
      if (prev?.field === fieldCode) {
        // 切换排序方向
        return {
          field: fieldCode,
          direction: prev.direction === 'asc' ? 'desc' : 'asc',
        };
      } else {
        // 新字段，默认升序
        return {
          field: fieldCode,
          direction: 'asc',
        };
      }
    });
  }, []);

  /**
   * 处理分页
   */
  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({ ...prev, page }));
  }, []);

  /**
   * 过滤和排序后的数据
   */
  const filteredAndSortedData = useMemo(() => {
    let result = [...sampleData];

    // 搜索过滤
    if (searchKeyword) {
      result = result.filter((row) => {
        return fieldBindings.some((binding) => {
          const value = row[binding.fieldCode];
          if (value === undefined || value === null) return false;
          return String(value).toLowerCase().includes(searchKeyword.toLowerCase());
        });
      });
    }

    // 排序
    if (sortConfig) {
      result.sort((a, b) => {
        const aValue = a[sortConfig.field];
        const bValue = b[sortConfig.field];

        if (aValue === bValue) return 0;

        const comparison = aValue > bValue ? 1 : -1;
        return sortConfig.direction === 'asc' ? comparison : -comparison;
      });
    }

    return result;
  }, [sampleData, searchKeyword, sortConfig, fieldBindings]);

  /**
   * 当前页数据
   */
  const currentPageData = useMemo(() => {
    const start = (pagination.page - 1) * pagination.pageSize;
    const end = start + pagination.pageSize;
    return filteredAndSortedData.slice(start, end);
  }, [filteredAndSortedData, pagination]);

  /**
   * 总页数
   */
  const totalPages = useMemo(() => {
    return Math.ceil(filteredAndSortedData.length / pagination.pageSize);
  }, [filteredAndSortedData.length, pagination.pageSize]);

  /**
   * 格式化字段值
   */
  const formatFieldValue = useCallback(
    (binding: ModelFieldBinding, value: any): string => {
      if (value === undefined || value === null) return '-';

      // 字典字段 - 显示标签
      if (binding.dictCode && dictOptions[binding.dictCode]) {
        return dictOptions[binding.dictCode][String(value)] || String(value);
      }

      // 布尔值
      if (binding.dataType === 'boolean') {
        return value ? '是' : '否';
      }

      // 日期时间
      if (binding.dataType === 'datetime') {
        return new Date(value).toLocaleString('zh-CN');
      }

      // 日期
      if (binding.dataType === 'date') {
        return new Date(value).toLocaleDateString('zh-CN');
      }

      return String(value);
    },
    [dictOptions],
  );

  /**
   * 渲染排序图标
   */
  const renderSortIcon = useCallback(
    (fieldCode: string) => {
      if (sortConfig?.field !== fieldCode) {
        return (
          <svg
            className="h-4 w-4 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"
            />
          </svg>
        );
      }

      if (sortConfig.direction === 'asc') {
        return (
          <svg
            className="h-4 w-4 text-blue-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
          </svg>
        );
      }

      return (
        <svg
          className="h-4 w-4 text-blue-600"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      );
    },
    [sortConfig],
  );

  if (!visible || !model) return null;

  // 按displayOrder排序
  const sortedBindings = [...fieldBindings].sort((a, b) => a.displayOrder - b.displayOrder);

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="relative flex max-h-[90vh] w-full max-w-7xl flex-col rounded-lg bg-white shadow-xl">
          {/* 标题栏 */}
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">列表预览</h2>
            <p className="mt-1 text-sm text-gray-500">
              预览模型 <span className="font-mono text-blue-600">{model.code}</span> 生成的动态列表
            </p>
          </div>

          {/* 工具栏 */}
          <div className="border-b border-gray-200 bg-gray-50 px-6 py-3">
            <div className="flex items-center gap-4">
              <div className="flex-1">
                <input
                  type="text"
                  value={searchKeyword}
                  onChange={(e) => handleSearch(e.target.value)}
                  placeholder="搜索..."
                  className="w-full max-w-md rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
              <div className="text-sm text-gray-600">共 {filteredAndSortedData.length} 条记录</div>
            </div>
          </div>

          {/* 列表内容 */}
          <div className="flex-1 overflow-auto px-6 py-4">
            {sortedBindings.length === 0 ? (
              <div className="py-12 text-center">
                <p className="text-gray-500">该模型还没有关联任何字段</p>
                <p className="mt-2 text-sm text-gray-400">请先添加字段后再预览列表</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      {sortedBindings.map((binding) => (
                        <th
                          key={binding.fieldCode}
                          onClick={() => handleSort(binding.fieldCode)}
                          className="cursor-pointer px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase hover:bg-gray-100"
                        >
                          <div className="flex items-center gap-2">
                            <span>{binding.fieldName}</span>
                            {renderSortIcon(binding.fieldCode)}
                          </div>
                        </th>
                      ))}
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        操作
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {currentPageData.length === 0 ? (
                      <tr>
                        <td
                          colSpan={sortedBindings.length + 1}
                          className="px-6 py-12 text-center text-gray-500"
                        >
                          没有找到匹配的数据
                        </td>
                      </tr>
                    ) : (
                      currentPageData.map((row) => (
                        <tr key={row.id} className="hover:bg-gray-50">
                          {sortedBindings.map((binding) => (
                            <td
                              key={binding.fieldCode}
                              className="px-6 py-4 text-sm whitespace-nowrap text-gray-900"
                            >
                              {formatFieldValue(binding, row[binding.fieldCode])}
                            </td>
                          ))}
                          <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                            <button className="mr-3 text-blue-600 hover:text-blue-700">查看</button>
                            <button className="mr-3 text-blue-600 hover:text-blue-700">编辑</button>
                            <button className="text-red-600 hover:text-red-700">删除</button>
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* 分页控件 */}
          {sortedBindings.length > 0 && filteredAndSortedData.length > 0 && (
            <div className="border-t border-gray-200 bg-gray-50 px-6 py-3">
              <div className="flex items-center justify-between">
                <div className="text-sm text-gray-600">
                  显示 {(pagination.page - 1) * pagination.pageSize + 1} 到{' '}
                  {Math.min(pagination.page * pagination.pageSize, filteredAndSortedData.length)}{' '}
                  条， 共 {filteredAndSortedData.length} 条
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handlePageChange(pagination.page - 1)}
                    disabled={pagination.page === 1}
                    className="rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    上一页
                  </button>
                  <div className="flex items-center gap-1">
                    {Array.from({ length: Math.min(totalPages, 5) }, (_, i) => {
                      let pageNum;
                      if (totalPages <= 5) {
                        pageNum = i + 1;
                      } else if (pagination.page <= 3) {
                        pageNum = i + 1;
                      } else if (pagination.page >= totalPages - 2) {
                        pageNum = totalPages - 4 + i;
                      } else {
                        pageNum = pagination.page - 2 + i;
                      }

                      return (
                        <button
                          key={pageNum}
                          onClick={() => handlePageChange(pageNum)}
                          className={`rounded-md border px-3 py-1 text-sm ${
                            pagination.page === pageNum
                              ? 'border-blue-600 bg-blue-600 text-white'
                              : 'border-gray-300 hover:bg-gray-100'
                          }`}
                        >
                          {pageNum}
                        </button>
                      );
                    })}
                  </div>
                  <button
                    onClick={() => handlePageChange(pagination.page + 1)}
                    disabled={pagination.page >= totalPages}
                    className="rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    下一页
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* 底部按钮 */}
          <div className="flex justify-end border-t border-gray-200 px-6 py-4">
            <button
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            >
              关闭
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
