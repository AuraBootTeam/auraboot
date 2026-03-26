import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { Result } from '~/utils/type';

// API 调用类型
export type ApiType = 'http' | 'grpc';

// API 配置接口
export interface ApiConfig {
  endpoint: string;
  method?: string;
  serviceName?: string;
  methodName?: string;
  protocolOptions?: ProtocolOptions;
}

export interface ApiResult<T = any> {
  code: string;
  desc: string;
  data: T | null;
  success?: boolean;
  error?: any;
}

export interface ProtocolOptions {
  timeout?: number;
  headers?: Record<string, string>;
  token?: string;
  skipAutoToken?: boolean;
}

/**
 * API 适配器 - 处理不同类型的 API 调用
 *
 * 现在直接使用新的 http-client 实现
 */
export class ApiAdapter {
  /**
   * 统一的 API 调用入口
   * @param apiType API 类型
   * @param config API 配置
   * @param data 请求数据
   * @param request Optional React Router Request (for SSR)
   * @returns Promise<ApiResult<T>>
   */
  static async call<T = any>(
    apiType: ApiType,
    config: ApiConfig,
    data?: any,
    request?: Request,
  ): Promise<ApiResult<T>> {
    switch (apiType) {
      case 'http':
        return await this.callHttp<T>(config, data, request);
      case 'grpc':
        return await this.callGrpc<T>(config, data);
      default:
        throw new Error(`Unsupported API type: ${apiType}`);
    }
  }

  /**
   * 调用 HTTP API
   */
  private static async callHttp<T>(
    config: ApiConfig,
    data: any,
    request?: Request,
  ): Promise<ApiResult<T>> {
    try {
      const result = await fetchResult<T>(
        config.endpoint,
        {
          method: (config.method as any) || 'post',
          params: data,
          ...config.protocolOptions,
        },
        request,
      );

      return {
        code: result.code,
        desc: result.desc,
        data: result.data,
        success: ResultHelper.isSuccess(result),
      };
    } catch (error) {
      return {
        code: 'error',
        desc: error instanceof Error ? error.message : 'Unknown error',
        data: null,
        success: false,
        error,
      };
    }
  }

  /**
   * 调用 gRPC API (示例实现，需要根据您的 gRPC 客户端库调整)
   */
  private static async callGrpc<T>(config: ApiConfig, data: any): Promise<ApiResult<T>> {
    // 这里需要根据您使用的 gRPC 客户端库进行实现
    // 以下是一个示例框架，实际实现需要替换

    if (!config.serviceName || !config.methodName) {
      throw new Error('gRPC 调用需要提供 serviceName 和 methodName');
    }

    try {
      // 假设您有一个 gRPC 客户端工厂
      // const client = createGrpcClient(config.serviceName);
      // const response = await client[config.methodName](data, { metadata: token ? { authorization: `Bearer ${token}` } : {} });

      // 这里应该返回实际的 gRPC 响应
      return {
        code: '0',
        desc: 'gRPC 调用成功',
        data: { message: 'gRPC 调用成功' } as unknown as T,
        success: true,
      };
    } catch (error) {
      console.error('gRPC 调用失败:', error);
      return {
        code: 'grpc_error',
        desc: error instanceof Error ? error.message : '未知错误',
        data: null,
        success: false,
        error,
      };
    }
  }
}
