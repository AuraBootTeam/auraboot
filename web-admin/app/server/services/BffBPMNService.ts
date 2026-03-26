import type { Request, Response } from 'express';
import logger from '~/server/utils/logger';
import type { BPMNProcessDefinition } from '~/bpmn-designer/types';

const NOT_IMPLEMENTED_RESPONSE = {
  success: false,
  code: '501',
  message: 'BPM API not yet implemented',
};

/**
 * @deprecated This service is dead code. All BPMN routes in bff.server.ts are commented out.
 * Requests are proxied directly to the backend at /api/bpm/process-definitions.
 * This file should be deleted once confirmed no references remain.
 */
export class BffBPMNService {
  /**
   * 保存/创建BPMN流程定义
   */
  async saveProcessDefinition(req: Request, res: Response): Promise<void> {
    logger.warn('saveProcessDefinition called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 更新BPMN流程定义
   */
  async updateProcessDefinition(req: Request, res: Response): Promise<void> {
    logger.warn('updateProcessDefinition called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 获取BPMN流程定义
   */
  async getProcessDefinition(req: Request, res: Response): Promise<void> {
    logger.warn('getProcessDefinition called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 获取BPMN流程定义列表
   */
  async getProcessDefinitionList(req: Request, res: Response): Promise<void> {
    logger.warn('getProcessDefinitionList called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 删除BPMN流程定义
   */
  async deleteProcessDefinition(req: Request, res: Response): Promise<void> {
    logger.warn('deleteProcessDefinition called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 发布BPMN流程定义
   */
  async publishProcessDefinition(req: Request, res: Response): Promise<void> {
    logger.warn('publishProcessDefinition called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }

  /**
   * 导出BPMN流程定义为XML
   */
  async exportProcessDefinitionXML(req: Request, res: Response): Promise<void> {
    logger.warn('exportProcessDefinitionXML called but not implemented');
    res.status(501).json(NOT_IMPLEMENTED_RESPONSE);
  }
}

// 导出单例实例
export const bffBPMNService = new BffBPMNService();
