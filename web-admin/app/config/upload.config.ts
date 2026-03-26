export interface UploadConfig {
  local: {
    uploadDir: string; // 本地上传目录
    publicPath: string; // 公共访问路径
    baseUrl: string; // 基础URL
  };
  aliyun: {
    defaultFolder: string; // 默认文件夹
  };
  aws: {
    defaultFolder: string; // 默认文件夹
  };
  general: {
    maxFileSize: number; // 最大文件大小
    allowedExtensions: string[]; // 允许的文件扩展名
  };
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

// 默认配置
const defaultConfig: UploadConfig = {
  local: {
    uploadDir: 'public/upload',
    publicPath: '/upload',
    baseUrl: 'http://localhost:5173', // 使用你的实际端口
  },
  aliyun: {
    defaultFolder: 'uploads',
  },
  aws: {
    defaultFolder: 'uploads',
  },
  general: {
    maxFileSize: 10 * 1024 * 1024, // 10MB
    allowedExtensions: ['.jpg', '.jpeg', '.png', '.gif', '.pdf', '.doc', '.docx'],
  },
};

// 服务器端配置加载函数
export const getServerUploadConfig = (): UploadConfig => {
  if (!isServer) {
    throw new Error('getServerUploadConfig should only be called on server side');
  }

  return {
    local: {
      uploadDir: getEnvVar('upload_local_dir', defaultConfig.local.uploadDir),
      publicPath: getEnvVar('upload_public_path', defaultConfig.local.publicPath),
      baseUrl: getEnvVar('upload_base_url', defaultConfig.local.baseUrl),
    },
    aliyun: {
      defaultFolder: getEnvVar('upload_aliyun_folder', defaultConfig.aliyun.defaultFolder),
    },
    aws: {
      defaultFolder: getEnvVar('upload_aws_folder', defaultConfig.aws.defaultFolder),
    },
    general: {
      maxFileSize:
        parseInt(getEnvVar('upload_max_file_size', '')) || defaultConfig.general.maxFileSize,
      allowedExtensions:
        getEnvVar('upload_allowed_extensions', '').split(',').filter(Boolean) ||
        defaultConfig.general.allowedExtensions,
    },
  };
};

// 客户端配置（使用默认值）
export const getClientUploadConfig = (): UploadConfig => {
  return {
    ...defaultConfig,
    local: {
      ...defaultConfig.local,
      baseUrl: window.location.origin, // 在客户端使用当前域名
    },
  };
};

// 通用配置获取函数
export const getUploadConfig = (): UploadConfig => {
  return isServer ? getServerUploadConfig() : getClientUploadConfig();
};

// 导出配置实例
export const uploadConfig = getUploadConfig();
