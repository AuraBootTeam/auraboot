import dotenv from 'dotenv';
import path from 'path';
import dns from 'node:dns';

// 加载环境变量
dotenv.config();
dns.setDefaultResultOrder('ipv4first');

export interface RetryConfig {
  retries: number;
  retryDelay: number;
  maxDelay: number;
  backoffMultiplier: number;
  jitterEnabled: boolean;
  exponentialBackoff: boolean;
  retryableStatusCodes: number[];
  retryableErrors: string[];
}

export interface HealthCheckConfig {
  cacheTtl: number;
  timeout: number;
  endpoints: Array<{
    name: string;
    url: string;
    expectedStatus: number[];
  }>;
}

export interface ProxyConfig {
  baseUrl: string;
  timeout: number;
  retry: RetryConfig;
  healthCheck: HealthCheckConfig;
}

export interface CorsConfig {
  allowedOrigins: string[];
  allowedMethods: string[];
  allowedHeaders: string[];
  credentials: boolean;
}

export interface SSEEndpointConfig {
  path: string;
  method: 'get' | 'post';
}

export interface SSEConfig {
  endpoints: SSEEndpointConfig[];
  heartbeatInterval: number;
  connectionTimeout: number;
}

export interface BffConfig {
  server: {
    port: number;
    host: string;
    env: string;
  };
  springBoot: {
    url: string;
    timeout: number;
  };
  proxy: ProxyConfig;
  ssl: {
    certPath?: string;
    keyPath?: string;
    enabled: boolean;
  };
  jwt: {
    secret: string;
    expiresIn: string;
    refreshExpiresIn: string;
  };
  logging: {
    level: string;
    format: 'json' | 'simple';
    enableConsole: boolean;
    enableFile: boolean;
    filePath?: string;
  };
  health: {
    cacheTimeout: number;
    endpoints: string[];
  };
  cors: CorsConfig;
  sse: SSEConfig;
}

/**
 * 统一配置管理
 * 支持环境变量覆盖默认值
 */
export const config: BffConfig = {
  server: {
    port: parseInt(process.env.BFF_PORT || '3000', 10),
    host: process.env.BFF_HOST || '0.0.0.0',
    env: process.env.NODE_ENV || 'development',
  },
  springBoot: {
    url: process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443',
    timeout: parseInt(process.env.SPRING_BOOT_TIMEOUT || '30000', 10),
  },
  proxy: {
    baseUrl: process.env.PROXY_TARGET || process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443',
    timeout: parseInt(process.env.PROXY_TIMEOUT || '30000', 10),
    retry: {
      retries: parseInt(process.env.PROXY_RETRIES || '3', 10),
      retryDelay: parseInt(process.env.PROXY_RETRY_BASE_DELAY || '1000', 10),
      maxDelay: parseInt(process.env.PROXY_RETRY_MAX_DELAY || '30000', 10),
      backoffMultiplier: parseFloat(process.env.PROXY_RETRY_BACKOFF_MULTIPLIER || '2'),
      jitterEnabled: process.env.PROXY_RETRY_JITTER_ENABLED !== 'false',
      exponentialBackoff: process.env.PROXY_EXPONENTIAL_BACKOFF !== 'false',
      retryableStatusCodes: process.env.PROXY_RETRYABLE_STATUS_CODES
        ? process.env.PROXY_RETRYABLE_STATUS_CODES.split(',').map((code) =>
            parseInt(code.trim(), 10),
          )
        : [408, 429, 500, 502, 503, 504],
      retryableErrors: process.env.PROXY_RETRYABLE_ERRORS
        ? process.env.PROXY_RETRYABLE_ERRORS.split(',').map((error) => error.trim())
        : ['econnaborted', 'enotfound', 'econnrefused', 'etimedout', 'econnreset'],
    },
    healthCheck: {
      cacheTtl: parseInt(process.env.PROXY_HEALTH_CHECK_CACHE_TTL || '30000', 10),
      timeout: parseInt(process.env.PROXY_HEALTH_CHECK_TIMEOUT || '5000', 10),
      endpoints: [
        {
          name: 'spring-boot-actuator',
          url: `${process.env.PROXY_TARGET || process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443'}/actuator/health`,
          expectedStatus: [200],
        },
      ],
    },
  },
  ssl: {
    certPath: process.env.SSL_CERT_PATH,
    keyPath: process.env.SSL_KEY_PATH,
    enabled: process.env.SSL_ENABLED === 'true',
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'your-secret-key-change-in-production',
    expiresIn: process.env.JWT_EXPIRES_IN || '1h',
    refreshExpiresIn: process.env.JWT_REFRESH_EXPIRES_IN || '7d',
  },
  logging: {
    level: process.env.LOG_LEVEL || 'info',
    format: (process.env.LOG_FORMAT as 'json' | 'simple') || 'simple',
    enableConsole: process.env.LOG_ENABLE_CONSOLE !== 'false',
    enableFile: process.env.LOG_ENABLE_FILE === 'true',
    filePath: process.env.LOG_FILE_PATH || path.join(process.cwd(), 'logs', 'bff.log'),
  },
  health: {
    cacheTimeout: parseInt(process.env.HEALTH_CACHE_TIMEOUT || '30000', 10),
    endpoints: process.env.HEALTH_ENDPOINTS
      ? process.env.HEALTH_ENDPOINTS.split(',')
      : ['/actuator/health'],
  },
  cors: {
    allowedOrigins: process.env.CORS_ALLOWED_ORIGINS
      ? process.env.CORS_ALLOWED_ORIGINS.split(',').map((s) => s.trim())
      : [
          'http://localhost:5173', // Development Vite
          'http://localhost:3500', // BFF local
          'https://app.auraboot.com', // Production domain
          'https://admin.auraboot.com', // Admin console
        ],
    allowedMethods: process.env.CORS_ALLOWED_METHODS
      ? process.env.CORS_ALLOWED_METHODS.split(',').map((s) => s.trim())
      : ['get', 'post', 'put', 'delete', 'options'],
    allowedHeaders: process.env.CORS_ALLOWED_HEADERS
      ? process.env.CORS_ALLOWED_HEADERS.split(',').map((s) => s.trim())
      : ['Origin', 'X-Requested-With', 'Content-Type', 'Accept', 'Authorization', 'Cookie'],
    credentials: process.env.CORS_CREDENTIALS !== 'false',
  },
  sse: {
    endpoints: [
      { path: '/api/ai/chat', method: 'post' as const },
      { path: '/api/ai/aurabot/chat/stream', method: 'post' as const },
      { path: '/api/ai/aurabot/wizard', method: 'post' as const },
      { path: '/api/notifications/stream', method: 'get' as const },
      { path: '/api/agent/events/stream', method: 'get' as const },
    ],
    heartbeatInterval: parseInt(process.env.SSE_HEARTBEAT_INTERVAL || '30000', 10),
    connectionTimeout: parseInt(process.env.SSE_CONNECTION_TIMEOUT || '300000', 10),
  },
};

/**
 * 验证配置的有效性
 */
export function validateConfig(): void {
  const errors: string[] = [];

  if (config.server.port < 1 || config.server.port > 65535) {
    errors.push('Invalid server port');
  }

  if (!config.springBoot.url) {
    errors.push('Spring Boot URL is required');
  }

  if (config.ssl.enabled && (!config.ssl.certPath || !config.ssl.keyPath)) {
    errors.push('SSL certificate and key paths are required when SSL is enabled');
  }

  if (errors.length > 0) {
    throw new Error(`Configuration validation failed: ${errors.join(', ')}`);
  }
}

/**
 * 获取环境特定的配置
 */
export function getEnvConfig(env: string = config.server.env): Partial<BffConfig> {
  switch (env) {
    case 'production':
      return {
        logging: {
          ...config.logging,
          level: 'warn',
          format: 'json',
          enableFile: true,
        },
      };
    case 'test':
      return {
        logging: {
          ...config.logging,
          level: 'error',
          enableConsole: false,
        },
      };
    default:
      return {};
  }
}

/**
 * 合并环境配置
 */
export function getMergedConfig(): BffConfig {
  const envConfig = getEnvConfig();
  return {
    ...config,
    ...envConfig,
    logging: {
      ...config.logging,
      ...envConfig.logging,
    },
  };
}
