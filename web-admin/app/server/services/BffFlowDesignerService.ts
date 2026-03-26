import type { Request, Response } from 'express';
import { flowDesignerApiClient } from '~/server/clients/FlowDesignerApiClient';
import type { FlowData, FlowListQuery } from '~/server/clients/FlowDesignerApiClient';
import logger from '~/server/utils/logger';

/**
 * BFF流程设计器服务
 * 处理前端流程设计器相关的请求
 */
export class BffFlowDesignerService {
  /**
   * 保存流程数据
   */
  async saveFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowData: FlowData = req.body;

      // 基本数据验证
      if (!flowData.name || !flowData.name.trim()) {
        res.status(400).json({
          success: false,
          message: 'Flow name is required',
        });
        return;
      }

      if (!flowData.nodes || !Array.isArray(flowData.nodes)) {
        res.status(400).json({
          success: false,
          message: 'Flow nodes are required',
        });
        return;
      }

      if (!flowData.edges || !Array.isArray(flowData.edges)) {
        res.status(400).json({
          success: false,
          message: 'Flow edges are required',
        });
        return;
      }

      if (!['draft', 'published'].includes(flowData.status)) {
        res.status(400).json({
          success: false,
          message: 'Invalid flow status. Must be "draft" or "published"',
        });
        return;
      }

      // 设置默认值
      const processedFlowData: FlowData = {
        ...flowData,
        name: flowData.name.trim(),
        description: flowData.description?.trim() || '',
        layoutMode: flowData.layoutMode || 'free',
        gridConfig: {
          columns: flowData.gridConfig?.columns || 3,
          rowGap: flowData.gridConfig?.rowGap || 20,
          columnGap: flowData.gridConfig?.columnGap || 20,
        },
        createdAt: flowData.createdAt || new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };

      logger.info('Processing save flow request', {
        flowName: processedFlowData.name,
        status: processedFlowData.status,
        nodesCount: processedFlowData.nodes.length,
        edgesCount: processedFlowData.edges.length,
      });

      const result = await flowDesignerApiClient.saveFlow(processedFlowData);

      if (result.success) {
        res.status(201).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in saveFlow service', {
        error: error.message,
        stack: error.stack,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 更新流程数据
   */
  async updateFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowId = req.params.id;
      const flowData: Partial<FlowData> = req.body;

      if (!flowId) {
        res.status(400).json({
          success: false,
          message: 'Flow ID is required',
        });
        return;
      }

      // 添加更新时间
      const processedFlowData = {
        ...flowData,
        updatedAt: new Date().toISOString(),
      };

      logger.info('Processing update flow request', {
        flowId,
        hasName: !!flowData.name,
        hasNodes: !!flowData.nodes,
        hasEdges: !!flowData.edges,
        status: flowData.status,
      });

      const result = await flowDesignerApiClient.updateFlow(flowId, processedFlowData);

      if (result.success) {
        res.status(200).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in updateFlow service', {
        error: error.message,
        flowId: req.params.id,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 获取流程数据
   */
  async getFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowId = req.params.id;

      if (!flowId) {
        res.status(400).json({
          success: false,
          message: 'Flow ID is required',
        });
        return;
      }

      logger.info('Processing get flow request', { flowId });

      const result = await flowDesignerApiClient.getFlow(flowId);

      if (result.success) {
        res.status(200).json(result);
      } else {
        const statusCode = result.message === 'Flow not found' ? 404 : 400;
        res.status(statusCode).json(result);
      }
    } catch (error: any) {
      logger.error('Error in getFlow service', {
        error: error.message,
        flowId: req.params.id,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 获取流程列表
   */
  async getFlowList(req: Request, res: Response): Promise<void> {
    try {
      const query: FlowListQuery = {
        page: req.query.page ? parseInt(req.query.page as string) : undefined,
        size: req.query.size ? parseInt(req.query.size as string) : undefined,
        name: req.query.name as string,
        status: req.query.status as 'draft' | 'published',
        createdBy: req.query.createdBy as string,
      };

      // 验证分页参数
      if (query.page !== undefined && (query.page < 0 || isNaN(query.page))) {
        res.status(400).json({
          success: false,
          message: 'Invalid page parameter',
        });
        return;
      }

      if (query.size !== undefined && (query.size < 1 || query.size > 100 || isNaN(query.size))) {
        res.status(400).json({
          success: false,
          message: 'Invalid size parameter. Must be between 1 and 100',
        });
        return;
      }

      if (query.status && !['draft', 'published'].includes(query.status)) {
        res.status(400).json({
          success: false,
          message: 'Invalid status parameter. Must be "draft" or "published"',
        });
        return;
      }

      logger.info('Processing get flow list request', query);

      const result = await flowDesignerApiClient.getFlowList(query);

      if (result.success) {
        res.status(200).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in getFlowList service', {
        error: error.message,
        query: req.query,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 删除流程
   */
  async deleteFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowId = req.params.id;

      if (!flowId) {
        res.status(400).json({
          success: false,
          message: 'Flow ID is required',
        });
        return;
      }

      logger.info('Processing delete flow request', { flowId });

      const result = await flowDesignerApiClient.deleteFlow(flowId);

      if (result.success) {
        res.status(200).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in deleteFlow service', {
        error: error.message,
        flowId: req.params.id,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 发布流程
   */
  async publishFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowId = req.params.id;

      if (!flowId) {
        res.status(400).json({
          success: false,
          message: 'Flow ID is required',
        });
        return;
      }

      logger.info('Processing publish flow request', { flowId });

      const result = await flowDesignerApiClient.publishFlow(flowId);

      if (result.success) {
        res.status(200).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in publishFlow service', {
        error: error.message,
        flowId: req.params.id,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }

  /**
   * 复制流程
   */
  async duplicateFlow(req: Request, res: Response): Promise<void> {
    try {
      const flowId = req.params.id;
      const { name: newName } = req.body;

      if (!flowId) {
        res.status(400).json({
          success: false,
          message: 'Flow ID is required',
        });
        return;
      }

      if (!newName || !newName.trim()) {
        res.status(400).json({
          success: false,
          message: 'New flow name is required',
        });
        return;
      }

      logger.info('Processing duplicate flow request', {
        flowId,
        newName: newName.trim(),
      });

      const result = await flowDesignerApiClient.duplicateFlow(flowId, newName.trim());

      if (result.success) {
        res.status(201).json(result);
      } else {
        res.status(400).json(result);
      }
    } catch (error: any) {
      logger.error('Error in duplicateFlow service', {
        error: error.message,
        flowId: req.params.id,
        newName: req.body.name,
      });
      res.status(500).json({
        success: false,
        message: 'Internal server error',
      });
    }
  }
}

// 导出单例实例
export const bffFlowDesignerService = new BffFlowDesignerService();
