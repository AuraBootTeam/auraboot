import { get, post, put, del } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  PaginationRequest,
  PaginationResult,
  DynamicEntity,
  BatchOperationRequest,
  CustomQueryRequest,
  FieldOptionsRequest,
  RelatedDataRequest,
  ExportRequest,
  ImportRequest,
  EntityStats,
  PageSchema,
  FieldOption,
  ApiResponse,
} from '~/types/dynamic';

/**
 * Helper function to handle API responses
 */
function handleResponse<T>(
  result: { code: string; desc: string; data: T | null },
  errorMsg: string,
): T {
  if (ResultHelper.isSuccess(result) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

/**
 * 动态CRUD服务类
 * 封装所有动态实体的API调用
 */
export class DynamicService {
  private baseUrl = '/api/dynamic';

  /**
   * 分页查询数据
   */
  async findByPage(
    entityCode: string,
    data: PaginationRequest,
    request?: Request,
  ): Promise<PaginationResult<DynamicEntity>> {
    const params: Record<string, string> = {
      page: String(data.page ?? 0),
      size: String(data.size ?? 20),
    };
    if (data.keyword) params.keyword = data.keyword;
    if (data.sortBy) params.sortBy = data.sortBy;
    if (data.sortDirection) params.sortDirection = data.sortDirection;
    const result = await get<any>(`${this.baseUrl}/${entityCode}/list`, params, undefined, request);
    const raw = handleResponse(result, 'Failed to fetch page data');
    // Backend GET /list returns { records, total, page, pageSize, totalPages }
    return {
      records: raw.records ?? [],
      total: raw.total ?? 0,
      page: raw.page ?? 0,
      pageSize: raw.pageSize ?? 20,
      totalPages: raw.totalPages ?? 0,
    };
  }

  /**
   * 根据ID获取单条数据
   */
  async findById(entityCode: string, id: string, request?: Request): Promise<DynamicEntity> {
    const result = await get<DynamicEntity>(
      `${this.baseUrl}/${entityCode}/${id}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch entity');
  }

  /**
   * 创建数据
   */
  async create(
    entityCode: string,
    data: Record<string, any>,
    request?: Request,
  ): Promise<DynamicEntity> {
    const result = await post<DynamicEntity>(
      `${this.baseUrl}/${entityCode}`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to create entity');
  }

  /**
   * 更新数据
   */
  async update(
    entityCode: string,
    id: string,
    data: Record<string, any>,
    request?: Request,
  ): Promise<DynamicEntity> {
    const result = await put<DynamicEntity>(
      `${this.baseUrl}/${entityCode}/${id}`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to update entity');
  }

  /**
   * 删除数据
   */
  async deleteById(entityCode: string, id: string, request?: Request): Promise<void> {
    const result = await del<void>(
      `${this.baseUrl}/${entityCode}/${id}`,
      undefined,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to delete entity');
    }
  }

  /**
   * 批量创建数据
   */
  async batchCreate(
    entityCode: string,
    dataList: Record<string, any>[],
    request?: Request,
  ): Promise<DynamicEntity[]> {
    const result = await post<DynamicEntity[]>(
      `${this.baseUrl}/${entityCode}/batch`,
      { dataList },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to batch create entities');
  }

  /**
   * 批量更新数据
   */
  async batchUpdate(
    entityCode: string,
    updates: { id: string; data: Record<string, any> }[],
    request?: Request,
  ): Promise<DynamicEntity[]> {
    const result = await put<DynamicEntity[]>(
      `${this.baseUrl}/${entityCode}/batch`,
      { updates },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to batch update entities');
  }

  /**
   * 批量删除数据
   */
  async batchDelete(entityCode: string, ids: string[], request?: Request): Promise<void> {
    const result = await del<void>(
      `${this.baseUrl}/${entityCode}/batch`,
      { ids },
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to batch delete entities');
    }
  }

  /**
   * 验证数据
   */
  async validate(
    entityCode: string,
    data: Record<string, any>,
    request?: Request,
  ): Promise<{ valid: boolean; errors: Record<string, string> }> {
    const result = await post<{ valid: boolean; errors: Record<string, string> }>(
      `${this.baseUrl}/${entityCode}/validate`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to validate entity');
  }

  /**
   * 获取字段选项
   */
  async getFieldOptions(data: FieldOptionsRequest, request?: Request): Promise<FieldOption[]> {
    const { entityCode, fieldName, ...params } = data;
    const result = await post<FieldOption[]>(
      `${this.baseUrl}/${entityCode}/fields/${fieldName}/options`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch field options');
  }

  /**
   * 执行自定义查询
   */
  async executeCustomQuery(
    data: CustomQueryRequest,
    request?: Request,
  ): Promise<PaginationResult<DynamicEntity>> {
    const result = await post<PaginationResult<DynamicEntity>>(
      `${this.baseUrl}/query`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to execute custom query');
  }

  /**
   * 执行自定义操作
   */
  async executeCustomAction(
    entityCode: string,
    actionName: string,
    data: Record<string, any>,
    request?: Request,
  ): Promise<any> {
    const result = await post(
      `${this.baseUrl}/${entityCode}/actions/${actionName}`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to execute custom action');
  }

  /**
   * 导出数据
   */
  async exportData(request: ExportRequest): Promise<Blob> {
    const response = await fetch(`${this.baseUrl}/${request.entityCode}/export`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });

    if (!response.ok) {
      throw new Error(`导出失败: ${response.statusText}`);
    }

    return await response.blob();
  }

  /**
   * 导入数据
   */
  async importData(
    request: ImportRequest,
  ): Promise<{ success: number; failed: number; errors?: string[] }> {
    const formData = new FormData();
    formData.append('file', request.file);

    if (request.mapping) {
      formData.append('mapping', JSON.stringify(request.mapping));
    }

    if (request.options) {
      formData.append('options', JSON.stringify(request.options));
    }

    const response = await fetch(`${this.baseUrl}/${request.entityCode}/import`, {
      method: 'post',
      body: formData,
    });

    if (!response.ok) {
      throw new Error(`导入失败: ${response.statusText}`);
    }

    const result = await response.json();
    return result.data;
  }

  /**
   * 获取统计数据
   */
  async getStats(entityCode: string, request?: Request): Promise<EntityStats> {
    const result = await get<EntityStats>(
      `${this.baseUrl}/${entityCode}/stats`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch entity stats');
  }

  /**
   * 获取关联数据
   */
  async getRelatedData(
    data: RelatedDataRequest,
    request?: Request,
  ): Promise<PaginationResult<DynamicEntity>> {
    const { entityCode, relationField, targetEntityCode, ...params } = data;
    const result = await post<PaginationResult<DynamicEntity>>(
      `${this.baseUrl}/${entityCode}/relations/${relationField}/${targetEntityCode}`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch related data');
  }

  /**
   * 获取页面元数据
   */
  async getPageMetadata(entityCode: string, request?: Request): Promise<PageSchema> {
    const result = await get<PageSchema>(
      `/api/schemas/${entityCode}/page`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch page metadata');
  }
}

// 导出单例实例
export const dynamicService = new DynamicService();
