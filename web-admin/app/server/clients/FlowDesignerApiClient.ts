import { ProxyApiClient } from '~/server/clients/ProxyApiClient';
import type { AxiosResponse } from 'axios';
import logger from '~/server/utils/logger';

// 流程数据接口定义
export interface FlowData {
  id?: string;
  name: string;
  description?: string;
  nodes: any[];
  edges: any[];
  layoutMode: 'free' | 'grid';
  gridConfig: {
    columns: number;
    rowGap: number;
    columnGap: number;
  };
  status: 'draft' | 'published';
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
}

// API响应接口
export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  message?: string;
  code?: string;
}

// 分页查询参数
export interface FlowListQuery {
  page?: number;
  size?: number;
  name?: string;
  status?: 'draft' | 'published';
  createdBy?: string;
}

// 分页响应
export interface PagedResponse<T> {
  records: T[];
  total: number;
  totalPages: number;
  pageSize: number;
  page: number;
}

/**
 * 流程设计器API客户端
 * 处理流程数据的CRUD操作
 */
export class FlowDesignerApiClient {
  private proxyClient: ProxyApiClient;
  private readonly basePath = '/api/flow-designer';

  constructor() {
    this.proxyClient = new ProxyApiClient();
  }

  /**
   * 保存流程数据（草稿或发布）
   */
  async saveFlow(flowData: FlowData): Promise<ApiResponse<FlowData>> {
    try {
      logger.info('Saving flow data', {
        flowId: flowData.id,
        flowName: flowData.name,
        status: flowData.status,
        nodesCount: flowData.nodes.length,
        edgesCount: flowData.edges.length,
      });

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'post',
        path: `${this.basePath}/flows`,
        data: flowData,
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (response.status >= 200 && response.status < 300) {
        logger.info('Flow saved successfully', {
          flowId: response.data?.id,
          status: response.status,
        });
        return {
          success: true,
          data: response.data,
        };
      } else {
        logger.error('Failed to save flow', {
          status: response.status,
          data: response.data,
        });
        return {
          success: false,
          message: response.data?.message || 'Failed to save flow',
        };
      }
    } catch (error: any) {
      logger.error('Error saving flow', {
        error: error.message,
        flowName: flowData.name,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }

  /**
   * 更新流程数据
   */
  async updateFlow(flowId: string, flowData: Partial<FlowData>): Promise<ApiResponse<FlowData>> {
    try {
      logger.info('Updating flow data', {
        flowId,
        status: flowData.status,
      });

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'put',
        path: `${this.basePath}/flows/${flowId}`,
        data: flowData,
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (response.status >= 200 && response.status < 300) {
        logger.info('Flow updated successfully', {
          flowId,
          status: response.status,
        });
        return {
          success: true,
          data: response.data,
        };
      } else {
        return {
          success: false,
          message: response.data?.message || 'Failed to update flow',
        };
      }
    } catch (error: any) {
      logger.error('Error updating flow', {
        error: error.message,
        flowId,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }

  /**
   * 获取流程数据
   */
  async getFlow(flowId: string): Promise<ApiResponse<FlowData>> {
    try {
      logger.info('Getting flow data', { flowId });

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'get',
        path: `${this.basePath}/flows/${flowId}`,
      });

      if (response.status === 200) {
        return {
          success: true,
          data: response.data,
        };
      } else if (response.status === 404) {
        return {
          success: false,
          message: 'Flow not found',
        };
      } else {
        return {
          success: false,
          message: response.data?.message || 'Failed to get flow',
        };
      }
    } catch (error: any) {
      logger.error('Error getting flow', {
        error: error.message,
        flowId,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }

  /**
   * 获取流程列表
   */
  async getFlowList(query: FlowListQuery = {}): Promise<ApiResponse<PagedResponse<FlowData>>> {
    try {
      logger.info('Getting flow list', query);

      const params = new URLSearchParams();
      if (query.page !== undefined) params.append('page', query.page.toString());
      if (query.size !== undefined) params.append('size', query.size.toString());
      if (query.name) params.append('name', query.name);
      if (query.status) params.append('status', query.status);
      if (query.createdBy) params.append('createdBy', query.createdBy);

      const queryString = params.toString();
      const path = queryString ? `${this.basePath}/flows?${queryString}` : `${this.basePath}/flows`;

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'get',
        path,
      });

      if (response.status === 200) {
        return {
          success: true,
          data: response.data,
        };
      } else {
        return {
          success: false,
          message: response.data?.message || 'Failed to get flow list',
        };
      }
    } catch (error: any) {
      logger.error('Error getting flow list', {
        error: error.message,
        query,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }

  /**
   * 删除流程
   */
  async deleteFlow(flowId: string): Promise<ApiResponse<void>> {
    try {
      logger.info('Deleting flow', { flowId });

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'delete',
        path: `${this.basePath}/flows/${flowId}`,
      });

      if (response.status >= 200 && response.status < 300) {
        logger.info('Flow deleted successfully', { flowId });
        return {
          success: true,
        };
      } else {
        return {
          success: false,
          message: response.data?.message || 'Failed to delete flow',
        };
      }
    } catch (error: any) {
      logger.error('Error deleting flow', {
        error: error.message,
        flowId,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }

  /**
   * 发布流程（将草稿状态改为已发布）
   */
  async publishFlow(flowId: string): Promise<ApiResponse<FlowData>> {
    return this.updateFlow(flowId, { status: 'published' });
  }

  /**
   * 复制流程
   */
  async duplicateFlow(flowId: string, newName: string): Promise<ApiResponse<FlowData>> {
    try {
      logger.info('Duplicating flow', { flowId, newName });

      const response: AxiosResponse = await this.proxyClient.proxyRequest({
        method: 'post',
        path: `${this.basePath}/flows/${flowId}/duplicate`,
        data: { name: newName },
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (response.status >= 200 && response.status < 300) {
        return {
          success: true,
          data: response.data,
        };
      } else {
        return {
          success: false,
          message: response.data?.message || 'Failed to duplicate flow',
        };
      }
    } catch (error: any) {
      logger.error('Error duplicating flow', {
        error: error.message,
        flowId,
        newName,
      });
      return {
        success: false,
        message: error.message || 'Network error occurred',
      };
    }
  }
}

// 导出单例实例
export const flowDesignerApiClient = new FlowDesignerApiClient();
