import axios from 'axios';
import type {
  AxiosInstance,
  AxiosResponse,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
} from 'axios';
import * as http from 'http';
import * as https from 'https';
import { config } from '~/server/utils/config';
import type { RetryConfig } from '~/server/utils/config';
import logger, { logPerformance } from '~/server/utils/logger';
import { HealthCheckService } from '~/server/utils/HealthCheckService';

// 扩展AxiosRequestConfig类型以支持metadata和retries
declare module 'axios' {
  interface InternalAxiosRequestConfig {
    metadata?: {
      startTime: number;
      retryCount?: number;
      requestId?: string;
    };
  }

  interface AxiosRequestConfig {
    metadata?: {
      startTime: number;
      retryCount?: number;
      requestId?: string;
    };
    retries?: number;
  }
}

export interface ProxyRequestOptions {
  method: string;
  path: string;
  data?: any;
  headers?: Record<string, string>;
  timeout?: number;
  retries?: number;
}

/**
 * 代理API客户端 - 封装与后端服务的代理请求逻辑
 */
export class ProxyApiClient {
  private client: AxiosInstance;
  private baseUrl: string;
  private retryConfig: RetryConfig;
  private healthCheckService: HealthCheckService;

  constructor() {
    this.baseUrl = config.proxy.baseUrl;
    this.retryConfig = config.proxy.retry;

    this.client = axios.create({
      baseURL: this.baseUrl,
      timeout: config.proxy.timeout,
      // 禁用自动重定向，让前端处理
      maxRedirects: 0,
      // 保持原始响应
      validateStatus: () => true,
      // Bypass system proxy (http_proxy env var) for localhost requests
      httpAgent: new http.Agent({ keepAlive: true }),
      httpsAgent: new https.Agent({ keepAlive: true }),
      proxy: false,
    });

    this.healthCheckService = new HealthCheckService(this.client);
    this.setupInterceptors();
  }

  /**
   * 设置请求和响应拦截器
   */
  private setupInterceptors(): void {
    // 请求拦截器
    this.client.interceptors.request.use(
      (config) => {
        const startTime = Date.now();
        config.metadata = { startTime, retryCount: 0 };

        logger.proxy(`Proxy Request: ${config.method?.toUpperCase()} ${config.url}`, {
          baseURL: config.baseURL,
          timeout: config.timeout,
          headers: this.sanitizeHeaders(config.headers),
        });

        return config;
      },
      (error) => {
        logger.error({ error: error.message }, 'Proxy Request Error');
        return Promise.reject(error);
      },
    );

    // 响应拦截器
    this.client.interceptors.response.use(
      (response) => {
        const { config } = response;
        const duration = Date.now() - (config.metadata?.startTime || 0);
        const retryCount = config.metadata?.retryCount || 0;

        logger.proxy(
          `Proxy Response: ${response.status} ${config.method?.toUpperCase()} ${config.url}`,
          {
            status: response.status,
            duration: `${duration}ms`,
            retryCount,
            dataSize: this.getResponseSize(response),
          },
        );

        return response;
      },
      (error) => {
        const { config } = error;
        const duration = config?.metadata?.startTime ? Date.now() - config.metadata.startTime : 0;
        const retryCount = config?.metadata?.retryCount || 0;

        logger.error(
          {
            method: config?.method?.toUpperCase(),
            url: config?.url,
            status: error.response?.status,
            duration: `${duration}ms`,
            retryCount,
            error: error.message,
            code: error.code,
          },
          'Proxy Response Error',
        );

        return Promise.reject(error);
      },
    );

    // 初始化健康检查服务
    this.healthCheckService = new HealthCheckService(this.client, config.proxy.healthCheck);
  }

  /**
   * 执行代理请求
   */
  async proxyRequest(options: ProxyRequestOptions): Promise<AxiosResponse> {
    const startTime = Date.now();
    const requestId = this.generateRequestId();

    logger.proxy(`Starting proxy request [${requestId}]`, {
      method: options.method,
      path: options.path,
      hasData: !!options.data,
    });

    try {
      const response = await this.executeWithRetry({
        method: options.method.toLowerCase() as any,
        url: options.path,
        data: options.data,
        headers: {
          ...options.headers,
          'X-Request-ID': requestId,
        },
        timeout: options.timeout || 30000,
        metadata: {
          startTime,
          retryCount: 0,
          requestId,
        },
      });

      logPerformance(`Proxy request [${requestId}]`, startTime, {
        method: options.method,
        path: options.path,
        status: response.status,
      });

      return response;
    } catch (error) {
      logger.error(
        {
          method: options.method,
          path: options.path,
          error: axios.isAxiosError(error) ? error.message : 'Unknown error',
          duration: `${Date.now() - startTime}ms`,
        },
        `Proxy request failed [${requestId}]`,
      );
      throw error;
    }
  }

  /**
   * 带重试机制的请求执行
   */
  private async executeWithRetry(requestConfig: AxiosRequestConfig): Promise<AxiosResponse> {
    let lastError: any;
    const maxRetries = requestConfig.retries || this.retryConfig.retries;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        // 更新重试计数
        if (requestConfig.metadata) {
          requestConfig.metadata.retryCount = attempt;
        }

        const response = await this.client.request(requestConfig);

        // 检查是否需要重试
        if (attempt < maxRetries && this.shouldRetry(response, null)) {
          await this.delay(this.calculateRetryDelay(attempt));
          continue;
        }

        return response;
      } catch (error) {
        lastError = error;

        // 检查是否需要重试
        if (attempt < maxRetries && this.shouldRetry(null, error)) {
          const delay = this.calculateRetryDelay(attempt);
          logger.proxy(`Retrying request in ${delay}ms (attempt ${attempt + 1}/${maxRetries})`, {
            error: axios.isAxiosError(error) ? error.message : 'Unknown error',
            requestId: requestConfig.metadata?.requestId,
          });

          await this.delay(delay);
          continue;
        }

        break;
      }
    }

    throw lastError;
  }

  /**
   * 判断是否应该重试
   */
  private shouldRetry(response: AxiosResponse | null, error: any): boolean {
    // 网络错误或超时错误
    if (error && axios.isAxiosError(error)) {
      if (error.code && this.retryConfig.retryableErrors.includes(error.code)) {
        return true;
      }

      // 检查响应状态码
      if (error.response && this.retryConfig.retryableStatusCodes.includes(error.response.status)) {
        return true;
      }
    }

    // 检查响应状态码
    if (response && this.retryConfig.retryableStatusCodes.includes(response.status)) {
      return true;
    }

    return false;
  }

  /**
   * 计算重试延迟（指数退避 + 抖动）
   */
  private calculateRetryDelay(attempt: number): number {
    if (this.retryConfig.exponentialBackoff) {
      // 指数退避计算
      const exponentialDelay =
        this.retryConfig.retryDelay * Math.pow(this.retryConfig.backoffMultiplier, attempt);

      // 限制最大延迟
      const cappedDelay = Math.min(exponentialDelay, this.retryConfig.maxDelay);

      // 添加随机抖动，避免惊群效应
      if (this.retryConfig.jitterEnabled) {
        const jitterRange = cappedDelay * 0.1; // 10%的抖动范围
        const jitter = (Math.random() - 0.5) * 2 * jitterRange;
        return Math.max(0, Math.floor(cappedDelay + jitter));
      }

      return Math.floor(cappedDelay);
    } else {
      // 固定延迟
      return this.retryConfig.retryDelay * (attempt + 1);
    }
  }

  /**
   * 延迟函数
   */
  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * 生成请求ID
   */
  private generateRequestId(): string {
    return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * 清理敏感头部信息用于日志
   */
  private sanitizeHeaders(headers: any): any {
    if (!headers) return {};

    const sanitized = { ...headers };
    const sensitiveHeaders = ['authorization', 'cookie', 'x-api-key', 'x-auth-token'];

    sensitiveHeaders.forEach((header) => {
      if (sanitized[header]) {
        sanitized[header] = '[REDACTED]';
      }
      if (sanitized[header.toLowerCase()]) {
        sanitized[header.toLowerCase()] = '[REDACTED]';
      }
    });

    return sanitized;
  }

  /**
   * 获取响应大小
   */
  private getResponseSize(response: AxiosResponse): string {
    try {
      const size = JSON.stringify(response.data).length;
      if (size > 1024 * 1024) {
        return `${(size / 1024 / 1024).toFixed(2)}MB`;
      } else if (size > 1024) {
        return `${(size / 1024).toFixed(2)}KB`;
      } else {
        return `${size}B`;
      }
    } catch {
      return 'unknown';
    }
  }

  /**
   * 检查代理服务健康状态（使用增强的健康检查服务）
   */
  async checkHealth(useCache: boolean = true): Promise<any> {
    try {
      const result = await this.healthCheckService.performHealthCheck(useCache);

      logger.proxy('Health check completed', {
        status: result.status,
        duration: result.duration,
        checksCount: Object.keys(result.checks).length,
      });

      return result;
    } catch (error: any) {
      logger.error(
        {
          error: error.message,
        },
        'Health check service failed',
      );

      // 降级到简单健康检查
      return this.simpleHealthCheck();
    }
  }

  /**
   * 简单健康检查（降级方案）
   */
  private async simpleHealthCheck(): Promise<{ status: string; timestamp: string; backend?: any }> {
    try {
      const response = await this.client.get('/actuator/health', {
        timeout: config.proxy.healthCheck.timeout,
      });

      const isHealthy = response.status === 200;

      logger.health('Proxy service health check', {
        status: response.status,
        healthy: isHealthy,
      });

      return {
        status: isHealthy ? 'healthy' : 'unhealthy',
        timestamp: new Date().toISOString(),
        backend: response.data,
      };
    } catch (error) {
      logger.error(
        {
          error: axios.isAxiosError(error) ? error.message : 'Unknown error',
        },
        'Proxy service health check failed',
      );

      return {
        status: 'unhealthy',
        timestamp: new Date().toISOString(),
        backend: axios.isAxiosError(error) ? error.response?.data : undefined,
      };
    }
  }

  /**
   * 更新重试配置
   */
  updateRetryConfig(newConfig: Partial<RetryConfig>): void {
    this.retryConfig = {
      ...this.retryConfig,
      ...newConfig,
    };

    logger.info('Proxy retry configuration updated', this.retryConfig);
  }

  /**
   * 获取客户端状态信息
   */
  getClientInfo(): { baseUrl: string; timeout: number; retryConfig: RetryConfig } {
    return {
      baseUrl: this.baseUrl,
      timeout: this.client.defaults.timeout || 0,
      retryConfig: this.retryConfig,
    };
  }
}
