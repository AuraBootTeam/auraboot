export interface ApiConfig {
  protocol: string; // http 或 https
  domain: string; // 域名或IP
  port?: number; // 端口号
  basePath?: string; // 基础路径
  timeout?: number; // 请求超时时间
}

export interface ApiEnvironmentConfig {
  development: ApiConfig;
  production: ApiConfig;
  k8s: ApiConfig;
}

// 检查是否在服务器环境
const isServer = typeof window === 'undefined';

// 安全获取环境变量的函数
const getEnvVar = (key: string, defaultValue: string = ''): string => {
  if (!isServer) {
    return defaultValue;
  }
  return process.env[key] || defaultValue;
};

// 获取当前环境
const getCurrentEnvironment = (): keyof ApiEnvironmentConfig => {
  if (!isServer) {
    // 客户端环境检测
    if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
      return 'development';
    }
    return 'production';
  }

  // 服务器端环境检测
  const nodeEnv = getEnvVar('node_env', 'development');
  const isK8s = getEnvVar('kubernetes_service_host', '') !== '';

  if (isK8s) {
    return 'k8s';
  }

  return nodeEnv === 'production' ? 'production' : 'development';
};

// 默认配置
const defaultApiConfig: ApiEnvironmentConfig = {
  development: {
    // 在开发环境下，API请求指向本地BFF服务器
    protocol: 'http',
    domain: 'localhost',
    port: 3000,
    basePath: '',
    timeout: 10000,
  },
  production: {
    protocol: 'https',
    domain: getEnvVar('api_domain', '192.168.31.53:8443'),
    port: getEnvVar('api_port') ? parseInt(getEnvVar('api_port')) : undefined,
    basePath: getEnvVar('api_base_path', ''),
    timeout: 30000,
  },
  k8s: {
    protocol: getEnvVar('api_protocol', 'http'),
    domain: getEnvVar('api_service_name', 'auraboot-platform'),
    port: getEnvVar('api_service_port') ? parseInt(getEnvVar('api_service_port')) : 6443,
    basePath: getEnvVar('api_base_path', ''),
    timeout: 30000,
  },
};

// 获取当前环境的API配置
export const getApiConfig = (): ApiConfig => {
  const env = getCurrentEnvironment();
  const config = defaultApiConfig[env];

  // 运行时环境变量覆盖（支持动态配置）
  if (!isServer) {
    // 客户端可以从window对象获取配置
    const runtimeConfig = (window as any).__API_CONFIG__;
    if (runtimeConfig) {
      return { ...config, ...runtimeConfig };
    }
  }

  return config;
};

// 构建完整的API URL
export const buildApiUrl = (path: string, config?: Partial<ApiConfig>): string => {
  const apiConfig = { ...getApiConfig(), ...config };
  const { protocol, domain, port, basePath } = apiConfig;

  // 确保path以/开头
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;

  // 在浏览器环境中，对于BFF架构，使用相对路径
  if (typeof window !== 'undefined') {
    const env = getCurrentEnvironment();
    if (env === 'development') {
      // 开发环境下，直接返回路径，让BFF处理
      return normalizedPath;
    }
  }

  // 服务器端或生产环境，构建完整URL
  let baseUrl = `${protocol}://${domain}`;

  // 添加端口（如果不是标准端口）
  if (port && !((protocol === 'http' && port === 80) || (protocol === 'https' && port === 443))) {
    baseUrl += `:${port}`;
  }

  // 添加基础路径
  if (basePath) {
    baseUrl += basePath.startsWith('/') ? basePath : `/${basePath}`;
  }

  return baseUrl + normalizedPath;
};

// 导出当前环境信息（用于调试）
export const getEnvironmentInfo = () => {
  return {
    environment: getCurrentEnvironment(),
    config: getApiConfig(),
    isServer,
  };
};
