import type { ApiResponse, PaginationRequest, PaginationResult } from '~/types/api';

export interface SchemaEndpoint {
  url: string;
  method: string;
  permission?: string;
}

export interface SchemaRegionLayout {
  columns?: number;
  gutter?: number;
  span?: number;
  [key: string]: unknown;
}

export interface SchemaField {
  code: string;
  label?: Record<string, string>;
  type?: string;
  required?: boolean;
  [key: string]: unknown;
}

export interface SchemaAction {
  code: string;
  label?: Record<string, string>;
  type?: string;
  [key: string]: unknown;
}

export interface SchemaColumn {
  code: string;
  label?: Record<string, string>;
  width?: number;
  sortable?: boolean;
  [key: string]: unknown;
}

export interface SchemaFilter {
  code: string;
  label?: Record<string, string>;
  type?: string;
  [key: string]: unknown;
}

export interface SchemaEventAction {
  action: string;
  params?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface PageSchema {
  meta: {
    title: Record<string, string>;
    entityCode: string;
    dslVersion: string;
    version: string;
    pageKey: string;
    schemaType: 'list' | 'form' | 'view';
  };
  endpoint?: {
    list?: SchemaEndpoint;
    create?: SchemaEndpoint;
    update?: SchemaEndpoint;
    get?: SchemaEndpoint;
    delete?: SchemaEndpoint;
  };
  regions: Array<{
    type: string;
    title?: Record<string, string>;
    layout?: SchemaRegionLayout;
    fields?: Array<SchemaField>;
    actions?: Array<SchemaAction>;
    columns?: Array<SchemaColumn>;
    filters?: Array<SchemaFilter>;
  }>;
  events?: Array<{
    on: string;
    if?: string;
    do: Array<SchemaEventAction>;
    catch?: Array<SchemaEventAction>;
  }>;
}

export interface QueryRequest extends PaginationRequest {
  filters?: Record<string, unknown>;
  sorts?: Array<{
    field: string;
    direction: 'asc' | 'desc';
  }>;
}

export interface ActionRequest {
  actionIntent: string;
  payload?: unknown;
  context?: Record<string, unknown>;
}

export interface CrudScenario {
  type: string;
  name: string;
  description?: string;
  [key: string]: unknown;
}

class SchemaService {
  private baseURL: string;

  constructor() {
    this.baseURL = '/api/meta/page-render';
  }

  /**
   * 获取页面 Schema 配置
   * 注意：此方法现在主要用于兼容性，实际的 schema 应该通过 loader 在服务端获取
   * @param pageKey 页面标识
   * @returns Promise<PageSchema>
   */
  async getPageSchema(pageKey: string): Promise<PageSchema> {
    try {
      // 通过 BFF 代理调用后端接口
      const response = await fetch(`/api/proxy${this.baseURL}/schema/${pageKey}`, {
        method: 'get',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch schema: ${response.statusText}`);
      }

      const result: ApiResponse<PageSchema> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to get page schema');
      }

      return result.data;
    } catch (error) {
      console.error('Error fetching page schema:', error);
      throw error;
    }
  }

  /**
   * 执行页面查询
   * @param pageKey 页面标识
   * @param queryRequest 查询请求参数
   * @returns Promise<PaginationResult<Record<string, unknown>>>
   */
  async executeQuery(
    pageKey: string,
    queryRequest: QueryRequest,
  ): Promise<PaginationResult<Record<string, unknown>>> {
    try {
      // 通过 BFF 代理调用后端接口
      const response = await fetch(`/api/proxy${this.baseURL}/query/${pageKey}`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(queryRequest),
      });

      if (!response.ok) {
        throw new Error(`Failed to execute query: ${response.statusText}`);
      }

      const result: ApiResponse<PaginationResult<Record<string, unknown>>> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to execute query');
      }

      return result.data;
    } catch (error) {
      console.error('Error executing query:', error);
      throw error;
    }
  }

  /**
   * 执行页面动作
   * @param pageKey 页面标识
   * @param actionIntent 动作意图
   * @param payload 动作载荷
   * @returns Promise<unknown>
   */
  async executeAction(pageKey: string, actionIntent: string, payload?: unknown): Promise<unknown> {
    try {
      // 通过 BFF 代理调用后端接口
      const response = await fetch(`/api/proxy${this.baseURL}/action/${pageKey}/${actionIntent}`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(payload || {}),
      });

      if (!response.ok) {
        throw new Error(`Failed to execute action: ${response.statusText}`);
      }

      const result: ApiResponse<unknown> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to execute action');
      }

      return result.data;
    } catch (error) {
      console.error('Error executing action:', error);
      throw error;
    }
  }

  /**
   * 验证页面配置
   * @param pageKey 页面标识
   * @param config 页面配置
   * @returns Promise<boolean>
   */
  async validatePageConfig(pageKey: string, config: Partial<PageSchema>): Promise<boolean> {
    try {
      const response = await fetch(`/api/proxy${this.baseURL}/validate/${pageKey}`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(config),
      });

      if (!response.ok) {
        throw new Error(`Failed to validate config: ${response.statusText}`);
      }

      const result: ApiResponse<boolean> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to validate page config');
      }

      return result.data;
    } catch (error) {
      console.error('Error validating page config:', error);
      throw error;
    }
  }

  /**
   * 预加载页面数据
   * @param pageKey 页面标识
   * @param context 上下文参数
   * @returns Promise<unknown>
   */
  async preloadPageData(pageKey: string, context?: Record<string, unknown>): Promise<unknown> {
    try {
      const response = await fetch(`/api/proxy${this.baseURL}/preload/${pageKey}`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(context || {}),
      });

      if (!response.ok) {
        throw new Error(`Failed to preload data: ${response.statusText}`);
      }

      const result: ApiResponse<unknown> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to preload page data');
      }

      return result.data;
    } catch (error) {
      console.error('Error preloading page data:', error);
      throw error;
    }
  }

  /**
   * 获取页面权限
   * @param pageKey 页面标识
   * @returns Promise<Record<string, boolean>>
   */
  async getPagePermissions(pageKey: string): Promise<Record<string, boolean>> {
    try {
      const response = await fetch(`/api/proxy${this.baseURL}/permissions/${pageKey}`, {
        method: 'get',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`Failed to get permissions: ${response.statusText}`);
      }

      const result: ApiResponse<Record<string, boolean>> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to get page permissions');
      }

      return result.data;
    } catch (error) {
      console.error('Error getting page permissions:', error);
      throw error;
    }
  }

  /**
   * 获取可用的 CRUD 场景
   * @param entityCode 实体编码
   * @returns Promise<Array<CrudScenario>>
   */
  async getAvailableCrudScenarios(entityCode: string): Promise<Array<CrudScenario>> {
    try {
      const response = await fetch(`/api/proxy${this.baseURL}/crud-scenarios/${entityCode}`, {
        method: 'get',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`Failed to get CRUD scenarios: ${response.statusText}`);
      }

      const result: ApiResponse<Array<CrudScenario>> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to get CRUD scenarios');
      }

      return result.data;
    } catch (error) {
      console.error('Error getting CRUD scenarios:', error);
      throw error;
    }
  }

  /**
   * 生成 CRUD 页面配置
   * @param entityCode 实体编码
   * @param scenarioType 场景类型
   * @returns Promise<PageSchema>
   */
  async generateCrudPageConfig(entityCode: string, scenarioType: string): Promise<PageSchema> {
    try {
      const response = await fetch(
        `/api/proxy${this.baseURL}/crud-config/${entityCode}/${scenarioType}`,
        {
          method: 'post',
          headers: {
            'Content-Type': 'application/json',
          },
          credentials: 'include',
        },
      );

      if (!response.ok) {
        throw new Error(`Failed to generate CRUD config: ${response.statusText}`);
      }

      const result: ApiResponse<PageSchema> = await response.json();

      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to generate CRUD page config');
      }

      return result.data;
    } catch (error) {
      console.error('Error generating CRUD page config:', error);
      throw error;
    }
  }
}

// 创建单例实例
export const schemaService = new SchemaService();

// 导出类型和服务
export { SchemaService };
export default schemaService;
