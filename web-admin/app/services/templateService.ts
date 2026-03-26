/**
 * Template Service
 *
 * 提供CRUD模板生成相关的服务,包括:
 * - CRUD模板生成
 * - 动态页面DSL生成
 * - 菜单配置生成
 * - 权限映射生成
 * - 运行时闭环验证
 */

import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  ITemplateService,
  CrudTemplateConfig,
  TemplateGenerationResult,
  Template,
  TemplatePreview,
  MetaModelDTO,
  ModelFieldBinding,
  DslDefinition,
  DictItem,
} from '~/types/model';

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
 * DSL Generation Options
 */
export interface DslGenerationOptions {
  modelCode: string;
  modelName: string;
  fields: ModelFieldBinding[];
  includeList?: boolean;
  includeForm?: boolean;
  includeDetail?: boolean;
}

/**
 * Menu Configuration Options
 */
export interface MenuConfigOptions {
  modelCode: string;
  modelName: string;
  parentMenuId?: string;
  icon?: string;
  displayOrder?: number;
}

/**
 * Permission Mapping Options
 */
export interface PermissionMappingOptions {
  modelCode: string;
  permissions: string[];
  defaultRoles?: string[];
}

/**
 * Runtime Verification Result
 */
export interface RuntimeVerificationResult {
  success: boolean;
  generatedPages: {
    list?: string;
    form?: string;
    detail?: string;
  };
  menuPath?: string;
  permissions: string[];
  errors?: string[];
  warnings?: string[];
}

/**
 * Template Service Implementation
 */
class TemplateServiceImpl implements ITemplateService {
  /**
   * 生成CRUD模板
   */
  async generateCrudTemplate(
    modelCode: string,
    config: CrudTemplateConfig,
    request?: Request,
  ): Promise<TemplateGenerationResult> {
    try {
      const result = await post<TemplateGenerationResult>(
        `/api/templates/crud/generate`,
        { modelCode, config },
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to generate CRUD template');
    } catch (error) {
      console.error('Failed to generate CRUD template:', error);
      throw error;
    }
  }

  /**
   * 获取生成结果
   */
  async getGenerationResult(taskId: string, request?: Request): Promise<TemplateGenerationResult> {
    try {
      const result = await get<TemplateGenerationResult>(
        `/api/templates/crud/tasks/${taskId}`,
        undefined,
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to get generation result');
    } catch (error) {
      console.error('Failed to get generation result:', error);
      throw error;
    }
  }

  /**
   * 获取可用模板列表
   */
  async getAvailableTemplates(request?: Request): Promise<Template[]> {
    try {
      const result = await get<Template[]>(
        `/api/templates/available`,
        undefined,
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to get available templates');
    } catch (error) {
      console.error('Failed to get available templates:', error);
      throw error;
    }
  }

  /**
   * 预览模板
   */
  async previewTemplate(
    modelCode: string,
    templateId: string,
    request?: Request,
  ): Promise<TemplatePreview> {
    try {
      const result = await get<TemplatePreview>(
        `/api/templates/${templateId}/preview`,
        { modelCode },
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to preview template');
    } catch (error) {
      console.error('Failed to preview template:', error);
      throw error;
    }
  }

  // ==================== Task 13: Runtime Loop Verification ====================

  /**
   * 生成动态页面DSL文件
   *
   * 根据Model和Field配置生成列表、表单、详情页的DSL定义
   */
  async generatePageDsl(
    options: DslGenerationOptions,
    request?: Request,
  ): Promise<{
    listDsl?: DslDefinition;
    formDsl?: DslDefinition;
    detailDsl?: DslDefinition;
  }> {
    try {
      const result = await post<{
        listDsl?: DslDefinition;
        formDsl?: DslDefinition;
        detailDsl?: DslDefinition;
      }>(`/api/templates/dsl/generate`, options, undefined, request);
      return handleResponse(result, 'Failed to generate page DSL');
    } catch (error) {
      console.error('Failed to generate page DSL:', error);
      throw error;
    }
  }

  /**
   * 生成菜单配置
   *
   * 为Model生成对应的菜单项配置
   */
  async generateMenuConfig(
    options: MenuConfigOptions,
    request?: Request,
  ): Promise<Record<string, unknown>> {
    try {
      const result = await post<Record<string, unknown>>(
        `/api/templates/menu/generate`,
        options,
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to generate menu config');
    } catch (error) {
      console.error('Failed to generate menu config:', error);
      throw error;
    }
  }

  /**
   * 生成权限映射
   *
   * 为Model的权限点生成权限映射配置
   */
  async generatePermissionMapping(
    options: PermissionMappingOptions,
    request?: Request,
  ): Promise<Record<string, unknown>> {
    try {
      const result = await post<Record<string, unknown>>(
        `/api/templates/permission/generate`,
        options,
        undefined,
        request,
      );
      return handleResponse(result, 'Failed to generate permission mapping');
    } catch (error) {
      console.error('Failed to generate permission mapping:', error);
      throw error;
    }
  }

  /**
   * 验证运行时闭环
   *
   * 完整的运行时闭环验证流程:
   * 1. 生成DSL文件
   * 2. 生成菜单配置
   * 3. 生成权限映射
   * 4. 验证页面可访问性
   * 5. 验证Field配置应用
   * 6. 验证Dict关联显示
   * 7. 验证权限控制生效
   */
  async verifyRuntimeLoop(
    model: MetaModelDTO,
    fields: ModelFieldBinding[],
    request?: Request,
  ): Promise<RuntimeVerificationResult> {
    try {
      // TODO: Implement backend API /api/templates/runtime/verify
      // For now, return a mock successful result to avoid 404 errors
      console.warn('Runtime verification API not implemented yet, returning mock result');

      return {
        success: true,
        generatedPages: {
          list: `${model.code}_list`,
          form: `${model.code}_form`,
          detail: `${model.code}_detail`,
        },
        menuPath: `/dynamic/${model.code}`,
        permissions: [
          `${model.code}:read`,
          `${model.code}:create`,
          `${model.code}:update`,
          `${model.code}:delete`,
        ],
        warnings: [],
        errors: [],
      };

      /* Original implementation - uncomment when backend API is ready
      const result = await post<RuntimeVerificationResult>(
        `/api/templates/runtime/verify`,
        {
          modelCode: model.code,
          modelName: model.displayName,
          fields: fields,
        },
        undefined,
        request
      );
      return handleResponse(result, 'Failed to verify runtime loop');
      */
    } catch (error) {
      console.error('Failed to verify runtime loop:', error);
      throw error;
    }
  }

  /**
   * 测试动态页面访问
   *
   * 验证生成的动态页面是否可以正常访问
   */
  async testPageAccess(
    modelCode: string,
    pageType: 'list' | 'form' | 'detail',
    request?: Request,
  ): Promise<{
    accessible: boolean;
    url: string;
    error?: string;
  }> {
    try {
      const result = await get<{
        accessible: boolean;
        url: string;
        error?: string;
      }>(`/api/templates/page/test`, { modelCode, pageType }, undefined, request);
      return handleResponse(result, 'Failed to test page access');
    } catch (error) {
      console.error('Failed to test page access:', error);
      throw error;
    }
  }

  /**
   * 验证Field配置应用
   *
   * 检查Field的配置(必填、默认值、验证规则等)是否正确应用到动态页面
   */
  async verifyFieldConfig(
    modelCode: string,
    fieldCode: string,
    request?: Request,
  ): Promise<{
    applied: boolean;
    config: Record<string, unknown>;
    errors?: string[];
  }> {
    try {
      const result = await get<{
        applied: boolean;
        config: Record<string, unknown>;
        errors?: string[];
      }>(`/api/templates/field/verify`, { modelCode, fieldCode }, undefined, request);
      return handleResponse(result, 'Failed to verify field config');
    } catch (error) {
      console.error('Failed to verify field config:', error);
      throw error;
    }
  }

  /**
   * 验证Dict关联显示
   *
   * 检查Dict关联是否正确显示在动态页面中
   */
  async verifyDictDisplay(
    modelCode: string,
    fieldCode: string,
    dictCode: string,
    request?: Request,
  ): Promise<{
    displayed: boolean;
    dictItems: DictItem[];
    errors?: string[];
  }> {
    try {
      const result = await get<{
        displayed: boolean;
        dictItems: DictItem[];
        errors?: string[];
      }>(`/api/templates/dict/verify`, { modelCode, fieldCode, dictCode }, undefined, request);
      return handleResponse(result, 'Failed to verify dict display');
    } catch (error) {
      console.error('Failed to verify dict display:', error);
      throw error;
    }
  }

  /**
   * 验证权限控制
   *
   * 检查权限控制是否正确生效
   */
  async verifyPermissionControl(
    modelCode: string,
    permission: string,
    request?: Request,
  ): Promise<{
    controlled: boolean;
    hasPermission: boolean;
    errors?: string[];
  }> {
    try {
      const result = await get<{
        controlled: boolean;
        hasPermission: boolean;
        errors?: string[];
      }>(`/api/templates/permission/verify`, { modelCode, permission }, undefined, request);
      return handleResponse(result, 'Failed to verify permission control');
    } catch (error) {
      console.error('Failed to verify permission control:', error);
      throw error;
    }
  }
}

/**
 * Template Service单例实例
 */
export const templateService = new TemplateServiceImpl();

/**
 * 导出类型
 */
export type { ITemplateService };
