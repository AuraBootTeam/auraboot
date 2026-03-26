import type { Request, Response, NextFunction } from 'express';
import logger from '~/server/utils/logger';
import { AxiosError } from 'axios';

/**
 * 错误类型定义
 */
export interface BffError extends Error {
  status?: number;
  code?: string;
  details?: any;
}

/**
 * 统一错误处理中间件
 */
export class ErrorHandlerMiddleware {
  /**
   * Express错误处理中间件
   */
  middleware = (error: any, req: Request, res: Response, next: NextFunction): void => {
    const requestId = (req.headers['x-request-id'] as string) || this.generateRequestId();

    // 记录错误日志
    this.logError(error, req, requestId);

    // 处理不同类型的错误
    if (this.isAxiosError(error)) {
      this.handleAxiosError(error, res, requestId);
    } else if (this.isBffError(error)) {
      this.handleBffError(error, res, requestId);
    } else if (this.isValidationError(error)) {
      this.handleValidationError(error, res, requestId);
    } else {
      this.handleGenericError(error, res, requestId);
    }
  };

  /**
   * 记录错误日志
   */
  private logError(error: any, req: Request, requestId: string): void {
    const errorInfo = {
      requestId,
      method: req.method,
      path: req.path,
      userAgent: req.headers['user-agent'],
      ip: req.ip || req.connection.remoteAddress,
      error: {
        name: error.name,
        message: error.message,
        stack: error.stack,
        code: error.code,
        status: error.status || error.response?.status,
      },
    };

    logger.error(`Request error [${requestId}]`, errorInfo);
  }

  /**
   * 处理Axios错误（网络请求错误）
   */
  private handleAxiosError(error: AxiosError, res: Response, requestId: string): void {
    if (error.response) {
      // 服务器响应了错误状态
      const status = error.response.status;
      const message = this.extractErrorMessage(error.response.data) || error.message;

      res.status(status).json({
        error: 'Backend Service Error',
        message,
        requestId,
        timestamp: new Date().toISOString(),
      });
    } else if (error.request) {
      // 请求发出但没有响应
      res.status(503).json({
        error: 'Service Unavailable',
        message: 'Backend service is not responding',
        requestId,
        timestamp: new Date().toISOString(),
      });
    } else {
      // 请求配置错误
      res.status(500).json({
        error: 'Request Configuration Error',
        message: error.message,
        requestId,
        timestamp: new Date().toISOString(),
      });
    }
  }

  /**
   * 处理BFF自定义错误
   */
  private handleBffError(error: BffError, res: Response, requestId: string): void {
    const status = error.status || 500;

    res.status(status).json({
      error: error.name || 'BFF Error',
      message: error.message,
      code: error.code,
      details: error.details,
      requestId,
      timestamp: new Date().toISOString(),
    });
  }

  /**
   * 处理验证错误
   */
  private handleValidationError(error: any, res: Response, requestId: string): void {
    res.status(400).json({
      error: 'Validation Error',
      message: error.message,
      details: error.details || error.errors,
      requestId,
      timestamp: new Date().toISOString(),
    });
  }

  /**
   * 处理通用错误
   */
  private handleGenericError(error: Error, res: Response, requestId: string): void {
    // 不向客户端暴露内部错误详情
    res.status(500).json({
      error: 'Internal Server Error',
      message: 'An unexpected error occurred',
      requestId,
      timestamp: new Date().toISOString(),
    });
  }

  /**
   * 检查是否为Axios错误
   */
  private isAxiosError(error: any): error is AxiosError {
    return error.isAxiosError === true;
  }

  /**
   * 检查是否为BFF自定义错误
   */
  private isBffError(error: any): error is BffError {
    return error instanceof Error && ('status' in error || 'code' in error);
  }

  /**
   * 检查是否为验证错误
   */
  private isValidationError(error: any): boolean {
    return (
      error.name === 'ValidationError' ||
      error.name === 'ValidatorError' ||
      (error.errors && Array.isArray(error.errors))
    );
  }

  /**
   * 从响应数据中提取错误消息
   */
  private extractErrorMessage(data: any): string | null {
    if (typeof data === 'string') {
      return data;
    }

    if (data && typeof data === 'object') {
      return data.message || data.error || data.msg || null;
    }

    return null;
  }

  /**
   * 生成请求ID
   */
  private generateRequestId(): string {
    return `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
}

/**
 * 创建BFF自定义错误
 */
export function createBffError(
  message: string,
  status: number = 500,
  code?: string,
  details?: any,
): BffError {
  const error = new Error(message) as BffError;
  error.status = status;
  error.code = code;
  error.details = details;
  return error;
}

/**
 * 异步错误处理包装器
 */
export function asyncErrorHandler(
  fn: (req: Request, res: Response, next: NextFunction) => Promise<any>,
) {
  return (req: Request, res: Response, next: NextFunction) => {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
}

// 导出中间件实例
export const errorHandlerMiddleware = new ErrorHandlerMiddleware().middleware;
