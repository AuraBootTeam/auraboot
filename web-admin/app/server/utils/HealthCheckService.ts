import type { AxiosInstance } from 'axios';
import logger from '~/server/utils/logger';
import { config } from '~/server/utils/config';

/**
 * 健康检查结果接口
 */
export interface HealthCheckResult {
  status: 'healthy' | 'unhealthy' | 'degraded';
  timestamp: string;
  duration: number;
  checks: {
    [key: string]: {
      status: 'up' | 'down' | 'warning';
      message?: string;
      duration?: number;
      details?: any;
    };
  };
}

/**
 * 健康检查配置
 */
interface HealthCheckConfig {
  cacheTtl: number; // 缓存TTL（毫秒）
  timeout: number; // 检查超时时间
  retries: number; // 重试次数
  endpoints: {
    name: string;
    url: string;
    method?: 'get' | 'post' | 'head';
    expectedStatus?: number[];
    timeout?: number;
  }[];
}

/**
 * 缓存项接口
 */
interface CacheItem {
  result: HealthCheckResult;
  expiry: number;
}

/**
 * 增强的健康检查服务
 */
export class HealthCheckService {
  private cache = new Map<string, CacheItem>();
  private config: HealthCheckConfig;

  constructor(
    private httpClient: AxiosInstance,
    customConfig?: Partial<HealthCheckConfig>,
  ) {
    this.config = {
      cacheTtl: 30000, // 默认30秒缓存
      timeout: 5000, // 默认5秒超时
      retries: 2, // 默认重试2次
      endpoints: [
        {
          name: 'spring-boot-actuator',
          url: `${config.springBoot.url}/actuator/health`,
          expectedStatus: [200],
        },
        {
          name: 'spring-boot-info',
          url: `${config.springBoot.url}/actuator/info`,
          expectedStatus: [200, 404], // info端点可能未启用
        },
      ],
      ...customConfig,
    };
  }

  /**
   * 执行完整的健康检查
   */
  async performHealthCheck(useCache: boolean = true): Promise<HealthCheckResult> {
    const cacheKey = 'full-health-check';

    // 检查缓存
    if (useCache) {
      const cached = this.getFromCache(cacheKey);
      if (cached) {
        logger.health('Health check result from cache', {
          status: cached.status,
          age: Date.now() - new Date(cached.timestamp).getTime(),
        });
        return cached;
      }
    }

    const startTime = Date.now();
    const checks: HealthCheckResult['checks'] = {};

    logger.health('Starting comprehensive health check', {
      endpoints: this.config.endpoints.length,
      useCache,
    });

    // 并行执行所有检查
    const checkPromises = [
      ...this.config.endpoints.map((endpoint) => this.checkEndpoint(endpoint)),
      this.checkBffSelf(),
      this.checkSystemResources(),
    ];

    const results = await Promise.allSettled(checkPromises);

    // 处理端点检查结果
    this.config.endpoints.forEach((endpoint, index) => {
      const result = results[index];
      if (result.status === 'fulfilled') {
        checks[endpoint.name] = result.value;
      } else {
        checks[endpoint.name] = {
          status: 'down',
          message: result.reason?.message || 'Check failed',
        };
      }
    });

    // 处理BFF自检结果
    const bffResult = results[this.config.endpoints.length];
    if (bffResult.status === 'fulfilled') {
      checks['bff-self'] = bffResult.value;
    } else {
      checks['bff-self'] = {
        status: 'down',
        message: bffResult.reason?.message || 'BFF self-check failed',
      };
    }

    // 处理系统资源检查结果
    const systemResult = results[this.config.endpoints.length + 1];
    if (systemResult.status === 'fulfilled') {
      checks['system-resources'] = systemResult.value;
    } else {
      checks['system-resources'] = {
        status: 'warning',
        message: systemResult.reason?.message || 'System check failed',
      };
    }

    // 计算整体状态
    const overallStatus = this.calculateOverallStatus(checks);
    const duration = Date.now() - startTime;

    const result: HealthCheckResult = {
      status: overallStatus,
      timestamp: new Date().toISOString(),
      duration,
      checks,
    };

    // 缓存结果
    if (useCache) {
      this.setCache(cacheKey, result);
    }

    logger.health('Health check completed', {
      status: overallStatus,
      duration,
      checksCount: Object.keys(checks).length,
    });

    return result;
  }

  /**
   * 检查单个端点
   */
  private async checkEndpoint(endpoint: {
    name: string;
    url: string;
    method?: 'get' | 'post' | 'head';
    expectedStatus?: number[];
    timeout?: number;
  }): Promise<{
    status: 'up' | 'down' | 'warning';
    message?: string;
    duration?: number;
    details?: any;
  }> {
    const startTime = Date.now();
    const method = endpoint.method || 'get';
    const timeout = endpoint.timeout || this.config.timeout;
    const expectedStatus = endpoint.expectedStatus || [200];

    try {
      const response = await this.httpClient.request({
        method,
        url: endpoint.url,
        timeout,
        validateStatus: () => true, // 不抛出状态码错误
      });

      const duration = Date.now() - startTime;
      const isExpectedStatus = expectedStatus.includes(response.status);

      return {
        status: isExpectedStatus ? 'up' : 'warning',
        message: isExpectedStatus ? 'OK' : `Unexpected status: ${response.status}`,
        duration,
        details: {
          status: response.status,
          headers: response.headers,
          responseTime: duration,
        },
      };
    } catch (error: any) {
      const duration = Date.now() - startTime;

      return {
        status: 'down',
        message: error.message || 'Request failed',
        duration,
        details: {
          error: error.code || error.name,
          timeout: error.code === 'econnaborted',
        },
      };
    }
  }

  /**
   * BFF自检
   */
  private async checkBffSelf(): Promise<{
    status: 'up' | 'down' | 'warning';
    message?: string;
    details?: any;
  }> {
    try {
      const startTime = Date.now();

      // 检查基本功能
      const checks = {
        memory: this.checkMemoryUsage(),
        uptime: process.uptime(),
        nodeVersion: process.version,
        platform: process.platform,
        arch: process.arch,
      };

      const duration = Date.now() - startTime;

      return {
        status: 'up',
        message: 'BFF is running normally',
        details: {
          ...checks,
          checkDuration: duration,
        },
      };
    } catch (error: any) {
      return {
        status: 'down',
        message: error.message || 'BFF self-check failed',
      };
    }
  }

  /**
   * 检查系统资源
   */
  private async checkSystemResources(): Promise<{
    status: 'up' | 'down' | 'warning';
    message?: string;
    details?: any;
  }> {
    try {
      const memoryUsage = process.memoryUsage();
      const cpuUsage = process.cpuUsage();

      // 计算内存使用率（简单估算）
      const memoryUsagePercent = (memoryUsage.heapUsed / memoryUsage.heapTotal) * 100;

      let status: 'up' | 'down' | 'warning' = 'up';
      let message = 'System resources are normal';

      // 内存使用率检查
      if (memoryUsagePercent > 90) {
        status = 'warning';
        message = 'High memory usage detected';
      } else if (memoryUsagePercent > 95) {
        status = 'down';
        message = 'Critical memory usage';
      }

      return {
        status,
        message,
        details: {
          memory: {
            heapUsed: Math.round(memoryUsage.heapUsed / 1024 / 1024), // MB
            heapTotal: Math.round(memoryUsage.heapTotal / 1024 / 1024), // MB
            usagePercent: Math.round(memoryUsagePercent * 100) / 100,
          },
          cpu: {
            user: cpuUsage.user,
            system: cpuUsage.system,
          },
          uptime: Math.round(process.uptime()),
        },
      };
    } catch (error: any) {
      return {
        status: 'warning',
        message: error.message || 'System resource check failed',
      };
    }
  }

  /**
   * 计算整体健康状态
   */
  private calculateOverallStatus(
    checks: HealthCheckResult['checks'],
  ): 'healthy' | 'unhealthy' | 'degraded' {
    const statuses = Object.values(checks).map((check) => check.status);

    if (statuses.every((status) => status === 'up')) {
      return 'healthy';
    }

    if (statuses.some((status) => status === 'down')) {
      return 'unhealthy';
    }

    return 'degraded';
  }

  /**
   * 检查内存使用情况
   */
  private checkMemoryUsage(): {
    heapUsed: number;
    heapTotal: number;
    external: number;
    rss: number;
  } {
    const usage = process.memoryUsage();
    return {
      heapUsed: Math.round(usage.heapUsed / 1024 / 1024), // MB
      heapTotal: Math.round(usage.heapTotal / 1024 / 1024), // MB
      external: Math.round(usage.external / 1024 / 1024), // MB
      rss: Math.round(usage.rss / 1024 / 1024), // MB
    };
  }

  /**
   * 从缓存获取结果
   */
  private getFromCache(key: string): HealthCheckResult | null {
    const item = this.cache.get(key);
    if (!item) {
      return null;
    }

    if (Date.now() > item.expiry) {
      this.cache.delete(key);
      return null;
    }

    return item.result;
  }

  /**
   * 设置缓存
   */
  private setCache(key: string, result: HealthCheckResult): void {
    const expiry = Date.now() + this.config.cacheTtl;
    this.cache.set(key, { result, expiry });
  }

  /**
   * 清除缓存
   */
  clearCache(): void {
    this.cache.clear();
    logger.health('Health check cache cleared');
  }

  /**
   * 获取缓存统计
   */
  getCacheStats(): { size: number; keys: string[] } {
    return {
      size: this.cache.size,
      keys: Array.from(this.cache.keys()),
    };
  }
}
