import { useState, useCallback } from 'react';
import { dynamicService } from '~/services/dynamicService';
import type {
  PaginationRequest,
  PaginationResult,
  DynamicEntity,
  FieldOptionsRequest,
  RelatedDataRequest,
  ExportRequest,
  ImportRequest,
  EntityStats,
  PageSchema,
  FieldOption,
  UseDynamicApiReturn,
} from '~/types/dynamic';

/**
 * 动态API Hook
 * 提供统一的API调用接口和状态管理
 */
export function useDynamicApi(): UseDynamicApiReturn {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 包装API调用，统一处理loading和error状态
   */
  const wrapApiCall = useCallback(async <T>(apiCall: () => Promise<T>): Promise<T> => {
    try {
      setLoading(true);
      setError(null);
      const result = await apiCall();
      return result;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : '操作失败';
      setError(errorMessage);
      throw err;
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 分页查询数据
   */
  const findByPage = useCallback(
    async (
      entityCode: string,
      request: PaginationRequest,
    ): Promise<PaginationResult<DynamicEntity>> => {
      return wrapApiCall(() => dynamicService.findByPage(entityCode, request));
    },
    [wrapApiCall],
  );

  /**
   * 根据ID获取单条数据
   */
  const findById = useCallback(
    async (entityCode: string, id: string): Promise<DynamicEntity> => {
      return wrapApiCall(() => dynamicService.findById(entityCode, id));
    },
    [wrapApiCall],
  );

  /**
   * 创建数据
   */
  const create = useCallback(
    async (entityCode: string, data: Record<string, any>): Promise<DynamicEntity> => {
      return wrapApiCall(() => dynamicService.create(entityCode, data));
    },
    [wrapApiCall],
  );

  /**
   * 更新数据
   */
  const update = useCallback(
    async (entityCode: string, id: string, data: Record<string, any>): Promise<DynamicEntity> => {
      return wrapApiCall(() => dynamicService.update(entityCode, id, data));
    },
    [wrapApiCall],
  );

  /**
   * 删除数据
   */
  const deleteById = useCallback(
    async (entityCode: string, id: string): Promise<void> => {
      return wrapApiCall(() => dynamicService.deleteById(entityCode, id));
    },
    [wrapApiCall],
  );

  /**
   * 批量创建数据
   */
  const batchCreate = useCallback(
    async (entityCode: string, dataList: Record<string, any>[]): Promise<DynamicEntity[]> => {
      return wrapApiCall(() => dynamicService.batchCreate(entityCode, dataList));
    },
    [wrapApiCall],
  );

  /**
   * 批量更新数据
   */
  const batchUpdate = useCallback(
    async (
      entityCode: string,
      updates: { id: string; data: Record<string, any> }[],
    ): Promise<DynamicEntity[]> => {
      return wrapApiCall(() => dynamicService.batchUpdate(entityCode, updates));
    },
    [wrapApiCall],
  );

  /**
   * 批量删除数据
   */
  const batchDelete = useCallback(
    async (entityCode: string, ids: string[]): Promise<void> => {
      return wrapApiCall(() => dynamicService.batchDelete(entityCode, ids));
    },
    [wrapApiCall],
  );

  /**
   * 获取字段选项
   */
  const getFieldOptions = useCallback(
    async (request: FieldOptionsRequest): Promise<FieldOption[]> => {
      return wrapApiCall(() => dynamicService.getFieldOptions(request));
    },
    [wrapApiCall],
  );

  /**
   * 获取关联数据
   */
  const getRelatedData = useCallback(
    async (request: RelatedDataRequest): Promise<PaginationResult<DynamicEntity>> => {
      return wrapApiCall(() => dynamicService.getRelatedData(request));
    },
    [wrapApiCall],
  );

  /**
   * 导出数据
   */
  const exportData = useCallback(
    async (request: ExportRequest): Promise<Blob> => {
      return wrapApiCall(() => dynamicService.exportData(request));
    },
    [wrapApiCall],
  );

  /**
   * 导入数据
   */
  const importData = useCallback(
    async (
      request: ImportRequest,
    ): Promise<{ success: number; failed: number; errors?: string[] }> => {
      return wrapApiCall(() => dynamicService.importData(request));
    },
    [wrapApiCall],
  );

  /**
   * 获取统计数据
   */
  const getStats = useCallback(
    async (entityCode: string): Promise<EntityStats> => {
      return wrapApiCall(() => dynamicService.getStats(entityCode));
    },
    [wrapApiCall],
  );

  /**
   * 获取页面Schema
   */
  const getPageSchema = useCallback(
    async (entityCode: string): Promise<PageSchema> => {
      return wrapApiCall(() => dynamicService.getPageMetadata(entityCode));
    },
    [wrapApiCall],
  );

  return {
    // 基础CRUD操作
    findByPage,
    findById,
    create,
    update,
    deleteById,

    // 批量操作
    batchCreate,
    batchUpdate,
    batchDelete,

    // 扩展功能
    getFieldOptions,
    getRelatedData,
    exportData,
    importData,
    getStats,

    // Schema相关
    getPageSchema,

    // 状态管理
    loading,
    error,
  };
}

/**
 * 动态表单Hook
 * 专门用于表单相关的API调用和状态管理
 */
export function useDynamicForm(entityCode: string, id?: string) {
  const api = useDynamicApi();
  const [formData, setFormData] = useState<DynamicEntity | null>(null);
  const [schema, setSchema] = useState<PageSchema | null>(null);
  const [initializing, setInitializing] = useState(true);

  /**
   * 初始化表单数据和Schema
   */
  const initialize = useCallback(async () => {
    try {
      setInitializing(true);

      // 获取Schema
      const pageSchema = await api.getPageSchema(entityCode);
      setSchema(pageSchema);

      // 如果有ID，获取现有数据
      if (id) {
        const data = await api.findById(entityCode, id);
        setFormData(data);
      } else {
        // 新建模式，设置默认值
        const defaultValues: Record<string, any> = {};
        pageSchema.formSchema.fields.forEach((field) => {
          if (field.defaultValue !== undefined) {
            defaultValues[field.name] = field.defaultValue;
          }
        });
        setFormData(defaultValues);
      }
    } catch (error) {
      console.error('初始化表单失败:', error);
    } finally {
      setInitializing(false);
    }
  }, [entityCode, id, api]);

  /**
   * 提交表单
   */
  const submit = useCallback(
    async (data: Record<string, any>) => {
      if (id) {
        return await api.update(entityCode, id, data);
      } else {
        return await api.create(entityCode, data);
      }
    },
    [entityCode, id, api],
  );

  return {
    formData,
    schema,
    initializing,
    loading: api.loading,
    error: api.error,
    initialize,
    submit,
    setFormData,
  };
}

/**
 * 动态列表Hook
 * 专门用于列表相关的API调用和状态管理
 */
export function useDynamicList(entityCode: string) {
  const api = useDynamicApi();
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [schema, setSchema] = useState<PageSchema | null>(null);
  const [pagination, setPagination] = useState({
    current: 1,
    pageSize: 20,
    total: 0,
  });
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [filters, setFilters] = useState<Record<string, any>>({});
  const [sorter, setSorter] = useState<{ field?: string; order?: 'ascend' | 'descend' }>({});

  /**
   * 加载数据
   */
  const loadData = useCallback(
    async (params?: Partial<PaginationRequest>) => {
      const request: PaginationRequest = {
        page: pagination.current - 1, // 后端从0开始
        size: pagination.pageSize,
        ...filters,
        ...params,
      };

      if (sorter.field && sorter.order) {
        request.sortBy = sorter.field;
        request.sortDirection = sorter.order === 'ascend' ? 'asc' : 'desc';
      }

      const result = await api.findByPage(entityCode, request);
      setData(result.records);
      setPagination((prev) => ({
        ...prev,
        current: result.page + 1, // 前端从1开始
        total: result.total,
      }));
    },
    [entityCode, api, pagination.current, pagination.pageSize, filters, sorter],
  );

  /**
   * 初始化列表
   */
  const initialize = useCallback(async () => {
    try {
      // 获取Schema
      const pageSchema = await api.getPageSchema(entityCode);
      setSchema(pageSchema);

      // 加载数据
      await loadData();
    } catch (error) {
      console.error('初始化列表失败:', error);
    }
  }, [entityCode, api, loadData]);

  /**
   * 刷新数据
   */
  const refresh = useCallback(() => {
    return loadData();
  }, [loadData]);

  /**
   * 删除选中项
   */
  const deleteSelected = useCallback(async () => {
    if (selectedRowKeys.length === 0) return;

    await api.batchDelete(entityCode, selectedRowKeys);
    setSelectedRowKeys([]);
    await refresh();
  }, [entityCode, selectedRowKeys, api, refresh]);

  return {
    data,
    schema,
    pagination,
    selectedRowKeys,
    filters,
    sorter,
    loading: api.loading,
    error: api.error,
    initialize,
    loadData,
    refresh,
    deleteSelected,
    setPagination,
    setSelectedRowKeys,
    setFilters,
    setSorter,
  };
}
