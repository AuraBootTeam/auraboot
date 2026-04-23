import { get, post, put, del, type Result } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  MetaModelDTO,
  MetaModelCreateRequest,
  MetaModelUpdateRequest,
  ModelQueryParams,
  PageResult,
  ModelVersion,
  ModelFieldBinding,
  VersionDiff,
  IModelService,
} from '~/types/model';

// ============================================================================
// Local Types (not exported from model.ts)
// ============================================================================

export interface RelatedPage {
  pid: string;
  code?: string;
  pageKey?: string;
  name?: string;
  title?: string | Record<string, unknown>;
  kind: string;
  route?: string;
  [key: string]: unknown;
}

export interface ModelStatistics {
  totalModels: number;
  publishedModels: number;
  draftModels: number;
  totalFields: number;
  [key: string]: unknown;
}

export interface ReleaseInfo {
  releaseId: number;
  releasePid: string;
  status: string;
  version: number;
  createdAt: string;
  [key: string]: unknown;
}

export interface BoundDictInfo {
  dictCode: string;
  dictName: string;
  items?: Array<{ label: string; value: string }>;
  [key: string]: unknown;
}

/**
 * Helper function to handle API responses
 */
function handleResponse<T>(result: Result<T>, errorMsg: string): T {
  if (ResultHelper.isSuccess(result) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

/**
 * Model服务类
 * 封装所有Model管理相关的API调用
 */
export class ModelService implements IModelService {
  private baseUrl = '/api/meta/models';

  /**
   * 分页查询Model列表
   */
  async findByPage(params: ModelQueryParams, request?: Request): Promise<PageResult<MetaModelDTO>> {
    interface MyBatisPlusPage {
      records: MetaModelDTO[];
      total: number;
      size: number;
      current: number;
      pages: number;
    }
    const result = await get<MyBatisPlusPage>(this.baseUrl, params, undefined, request);
    const responseData = handleResponse(result, 'Failed to fetch models');

    // Transform MyBatis-Plus IPage structure to PageResult
    // Backend returns: { records: [], total, size, current, pages }
    // Frontend expects: { data: [], total, page, size, totalPages }
    return {
      data: responseData.records || [],
      total: responseData.total || 0,
      page: responseData.current || 1,
      size: responseData.size || 20,
      totalPages: responseData.pages || 0,
    };
  }

  /**
   * 根据PID获取Model详情
   */
  async findByPid(pid: string, request?: Request): Promise<MetaModelDTO> {
    const result = await get<MetaModelDTO>(`${this.baseUrl}/${pid}`, undefined, undefined, request);
    return handleResponse(result, 'Failed to fetch model');
  }

  /**
   * 根据编码获取Model详情
   */
  async findByCode(code: string, request?: Request): Promise<MetaModelDTO> {
    const result = await get<MetaModelDTO>(
      `${this.baseUrl}/code/${code}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch model');
  }

  /**
   * 创建Model
   */
  async create(data: MetaModelCreateRequest, request?: Request): Promise<MetaModelDTO> {
    const result = await post<MetaModelDTO>(this.baseUrl, data, undefined, request);
    return handleResponse(result, 'Failed to create model');
  }

  /**
   * 更新Model
   */
  async update(
    pid: string,
    data: MetaModelUpdateRequest,
    request?: Request,
  ): Promise<MetaModelDTO> {
    const result = await put<MetaModelDTO>(`${this.baseUrl}/${pid}`, data, undefined, request);
    return handleResponse(result, 'Failed to update model');
  }

  /**
   * 删除Model
   */
  async delete(pid: string, request?: Request): Promise<void> {
    const result = await del<void>(`${this.baseUrl}/${pid}`, undefined, undefined, request);
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to delete model');
    }
  }

  /**
   * 批量删除Model
   */
  async batchDelete(pids: string[], request?: Request): Promise<void> {
    const result = await post<void>(`${this.baseUrl}/batch-delete`, { pids }, undefined, request);
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to batch delete models');
    }
  }

  /**
   * 检查编码唯一性
   */
  async checkCodeUnique(code: string, excludePid?: string, request?: Request): Promise<boolean> {
    const params = excludePid ? { excludePid } : {};
    const result = await get<boolean>(
      `${this.baseUrl}/code/${code}/unique`,
      params,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to check code uniqueness');
  }

  /**
   * 获取Model的版本历史
   */
  async getVersionHistory(code: string, request?: Request): Promise<ModelVersion[]> {
    const result = await get<ModelVersion[]>(
      `${this.baseUrl}/code/${code}/versions`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch version history');
  }

  /**
   * 获取指定版本的Model详情
   */
  async getVersionDetail(code: string, version: number, request?: Request): Promise<MetaModelDTO> {
    const result = await get<MetaModelDTO>(
      `${this.baseUrl}/code/${code}/versions/${version}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch version detail');
  }

  /**
   * 对比两个版本
   */
  async compareVersions(
    code: string,
    v1: number,
    v2: number,
    request?: Request,
  ): Promise<VersionDiff> {
    const result = await post<VersionDiff>(
      `${this.baseUrl}/code/${code}/versions/compare`,
      { v1, v2 },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to compare versions');
  }

  /**
   * 回滚到指定版本
   */
  async rollbackToVersion(code: string, version: number, request?: Request): Promise<MetaModelDTO> {
    const result = await post<MetaModelDTO>(
      `${this.baseUrl}/code/${code}/rollback`,
      { version },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to rollback version');
  }

  /**
   * 刷新Model缓存
   */
  async refreshCache(pid: string, request?: Request): Promise<void> {
    const result = await post<void>(
      `${this.baseUrl}/${pid}/refresh-cache`,
      undefined,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to refresh cache');
    }
  }

  /**
   * 获取Model的Field列表
   */
  async getModelFields(pid: string, request?: Request): Promise<ModelFieldBinding[]> {
    const result = await get<ModelFieldBinding[]>(
      `${this.baseUrl}/${pid}/fields`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch model fields');
  }

  /**
   * 获取Model关联的页面列表
   */
  async getRelatedPages(pid: string, request?: Request): Promise<RelatedPage[]> {
    const result = await get<RelatedPage[]>(
      `${this.baseUrl}/${pid}/pages`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch related pages');
  }

  /**
   * 导出Model列表
   */
  async exportModels(params: ModelQueryParams, selectedIds?: string[]): Promise<Blob> {
    const response = await fetch(`${this.baseUrl}/export`, {
      method: 'post',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        filters: params,
        selectedIds: selectedIds || [],
      }),
    });

    if (!response.ok) {
      throw new Error(`导出失败: ${response.statusText}`);
    }

    return await response.blob();
  }

  /**
   * 获取Model统计信息
   */
  async getStatistics(request?: Request): Promise<ModelStatistics> {
    const result = await get<ModelStatistics>(
      `${this.baseUrl}/statistics`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch statistics');
  }

  /**
   * 验证Model数据
   */
  async validate(
    data: MetaModelCreateRequest | MetaModelUpdateRequest,
    request?: Request,
  ): Promise<{
    valid: boolean;
    errors: Record<string, string>;
  }> {
    const result = await post<{ valid: boolean; errors: Record<string, string> }>(
      `${this.baseUrl}/validate`,
      data,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to validate model');
  }

  /**
   * 检查Git-First模式是否启用
   */
  async isGitFirstEnabled(request?: Request): Promise<boolean> {
    const result = await get<{ required: boolean }>(
      '/api/git/router/requires-git-first',
      { resourceType: 'model' },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to check Git-First status').required;
  }

  /**
   * 获取Model的Release信息
   */
  async getReleaseInfo(pid: string, request?: Request): Promise<ReleaseInfo> {
    const result = await get<ReleaseInfo>(
      `${this.baseUrl}/${pid}/release`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to fetch release info');
  }

  /**
   * 批量更新字段顺序
   */
  async updateFieldsOrder(
    pid: string,
    orderUpdates: Array<{ fieldCode: string; displayOrder: number }>,
    request?: Request,
  ): Promise<number> {
    // Convert array to map format expected by backend: { fieldPid: displayOrder }
    const fieldOrders: Record<string, number> = {};
    orderUpdates.forEach((update) => {
      fieldOrders[update.fieldCode] = update.displayOrder;
    });

    const result = await put<number>(
      `${this.baseUrl}/${pid}/fields/reorder`,
      fieldOrders,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to update fields order');
  }

  /**
   * 更新字段绑定配置
   */
  async updateFieldBinding(
    pid: string,
    fieldCode: string,
    config: {
      required?: boolean;
      readonly?: boolean;
      visible?: boolean;
      displayOrder?: number;
    },
    request?: Request,
  ): Promise<ModelFieldBinding> {
    // Convert pid to id for API call
    // Find the binding by fieldCode
    const fields = await this.getModelFields(pid, request);
    const binding = fields.find((f) => f.fieldCode === fieldCode);

    if (!binding) {
      throw new Error(`Field binding not found: ${fieldCode}`);
    }

    // Update the binding
    const result = await put<ModelFieldBinding>(
      `/api/meta/model-field-bindings/${binding.id}`,
      {
        ...binding,
        ...config,
      },
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to update field binding');
  }

  /**
   * 解绑字段
   */
  async unbindField(pid: string, fieldCode: string, request?: Request): Promise<void> {
    // Convert pid to id for API call
    const model = await this.findByPid(pid, request);

    // Find the field by fieldCode
    const fields = await this.getModelFields(pid, request);
    const field = fields.find((f) => f.fieldCode === fieldCode);

    if (!field) {
      throw new Error(`Field not found: ${fieldCode}`);
    }

    const result = await del<void>(
      `/api/meta/model-field-bindings/model/${model.id}/field/${field.id}`,
      undefined,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to unbind field');
    }
  }

  /**
   * Bind a field to a model with full configuration
   */
  async bindFieldToModel(
    modelPid: string,
    request: import('~/types/fieldLibrary').FieldBindingRequest,
    req?: Request,
  ): Promise<ModelFieldBinding> {
    const result = await post<ModelFieldBinding>(
      `${this.baseUrl}/${modelPid}/fields/bind`,
      request,
      undefined,
      req,
    );
    return handleResponse(result, 'Failed to bind field to model');
  }

  /**
   * Batch bind multiple fields to a model with common configuration
   */
  async batchBindFieldsToModel(
    modelPid: string,
    request: import('~/types/fieldLibrary').BatchFieldBindingRequest,
    req?: Request,
  ): Promise<ModelFieldBinding[]> {
    const result = await post<ModelFieldBinding[]>(
      `${this.baseUrl}/${modelPid}/fields/bind-batch`,
      request,
      undefined,
      req,
    );
    return handleResponse(result, 'Failed to batch bind fields to model');
  }

  /**
   * Publish a model: create the database table
   */
  async publish(pid: string, versionNote?: string, request?: Request): Promise<MetaModelDTO> {
    const params = versionNote ? `?versionNote=${encodeURIComponent(versionNote)}` : '';
    const result = await post<MetaModelDTO>(
      `${this.baseUrl}/${pid}/publish${params}`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to publish model');
  }

  /**
   * Unpublish a model: mark as deprecated
   */
  async unpublish(pid: string, request?: Request): Promise<MetaModelDTO> {
    const result = await post<MetaModelDTO>(
      `${this.baseUrl}/${pid}/unpublish`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to unpublish model');
  }

  /**
   * Preview the DDL statements that will be executed on publish
   */
  async previewPublishDDL(
    pid: string,
    request?: Request,
  ): Promise<{
    modelCode: string;
    ddlStatements: string[];
    operationType: string;
    affectedTables: string[];
    riskAssessment: {
      level: string;
      description: string;
      warnings: string[];
    } | null;
  }> {
    interface PublishPreview {
      modelCode: string;
      ddlStatements: string[];
      operationType: string;
      affectedTables: string[];
      riskAssessment: {
        level: string;
        description: string;
        warnings: string[];
      } | null;
    }
    const result = await get<PublishPreview>(
      `${this.baseUrl}/${pid}/publish/preview`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to preview publish DDL');
  }

  /**
   * Bind dictionary to field
   */
  async bindDictToField(fieldPid: string, dictCode: string, request?: Request): Promise<void> {
    const result = await post<void>(
      `/api/meta/fields/${fieldPid}/bind-dict`,
      { dictCode },
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to bind dictionary to field');
    }
  }

  /**
   * Unbind dictionary from field
   */
  async unbindDictFromField(fieldPid: string, request?: Request): Promise<void> {
    const result = await del<void>(
      `/api/meta/fields/${fieldPid}/unbind-dict`,
      undefined,
      undefined,
      request,
    );
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'Failed to unbind dictionary from field');
    }
  }

  /**
   * Get bound dictionary for field
   */
  async getBoundDict(fieldPid: string, request?: Request): Promise<BoundDictInfo> {
    const result = await get<BoundDictInfo>(
      `/api/meta/fields/${fieldPid}/bound-dict`,
      undefined,
      undefined,
      request,
    );
    return handleResponse(result, 'Failed to get bound dictionary');
  }
}

// 导出单例实例
export const modelService = new ModelService();
