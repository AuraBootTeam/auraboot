import pino from 'pino';
import path from 'path';
import fs from 'fs';
import { config } from '~/server/utils/config';

/**
 * 创建日志目录
 */
function ensureLogDirectory(): void {
  if (config.logging.enableFile && config.logging.filePath) {
    const logDir = path.dirname(config.logging.filePath);
    if (!fs.existsSync(logDir)) {
      fs.mkdirSync(logDir, { recursive: true });
    }
  }
}

/**
 * 创建Pino配置
 */
function createPinoConfig(): pino.LoggerOptions {
  const pinoConfig: pino.LoggerOptions = {
    level: config.logging.level,
    timestamp: pino.stdTimeFunctions.isoTime,
  };

  // 开发环境使用pretty格式
  if (config.logging.format !== 'json' && config.logging.enableConsole) {
    pinoConfig.transport = {
      target: 'pino-pretty',
      options: {
        colorize: true,
        translateTime: 'yyyy-mm-dd HH:MM:ss',
        ignore: 'pid,hostname',
      },
    };
  }

  return pinoConfig;
}

/**
 * 创建文件流
 */
function createFileStreams(): pino.DestinationStream[] {
  const streams: pino.DestinationStream[] = [];

  if (config.logging.enableFile && config.logging.filePath) {
    ensureLogDirectory();

    // 普通日志文件流
    streams.push(
      pino.destination({
        dest: config.logging.filePath,
        sync: false, // 异步写入提升性能
      }),
    );

    // 错误日志文件流
    const errorLogPath = config.logging.filePath.replace('.log', '.error.log');
    streams.push(
      pino.destination({
        dest: errorLogPath,
        sync: false,
      }),
    );
  }

  return streams;
}

/**
 * 创建Pino Logger实例
 */
function createLogger(): pino.Logger {
  const pinoConfig = createPinoConfig();
  const fileStreams = createFileStreams();

  let logger: pino.Logger;

  if (fileStreams.length > 0) {
    // 使用多流输出
    const streams: pino.StreamEntry[] = [];

    // 控制台流
    if (config.logging.enableConsole) {
      streams.push({ stream: process.stdout });
    }

    // 文件流
    fileStreams.forEach((stream) => {
      streams.push({ stream });
    });

    logger = pino(pinoConfig, pino.multistream(streams));
  } else {
    // 仅控制台输出
    logger = pino(pinoConfig);
  }

  // 处理未捕获的异常和Promise拒绝
  if (config.logging.enableFile && config.logging.filePath) {
    const exceptionsPath = config.logging.filePath.replace('.log', '.exceptions.log');
    const rejectionsPath = config.logging.filePath.replace('.log', '.rejections.log');

    process.on('uncaughtException', (err) => {
      logger.fatal({ err }, 'Uncaught Exception');
      fs.appendFileSync(
        exceptionsPath,
        `${new Date().toISOString()} FATAL: ${err.message}\n${err.stack}\n`,
      );
      process.exit(1);
    });

    process.on('unhandledRejection', (reason, promise) => {
      logger.fatal({ reason, promise }, 'Unhandled Rejection');
      fs.appendFileSync(
        rejectionsPath,
        `${new Date().toISOString()} FATAL: Unhandled Rejection - ${reason}\n`,
      );
    });
  }

  return logger;
}

const logger = createLogger();

/**
 * 扩展的Logger接口
 */
export interface ExtendedLogger extends pino.Logger {
  info(message: string, meta?: any): void;
  info(meta: any, message?: string): void;
  warn(message: string, meta?: any): void;
  warn(meta: any, message?: string): void;
  error(message: string, meta?: any): void;
  error(meta: any, message?: string): void;
  debug(message: string, meta?: any): void;
  debug(meta: any, message?: string): void;
  request: (message: string, meta?: any) => void;
  response: (message: string, meta?: any) => void;
  auth: (message: string, meta?: any) => void;
  proxy: (message: string, meta?: any) => void;
  health: (message: string, meta?: any) => void;
  performance: (operation: string, duration: number, meta?: any) => void;
  security: (event: string, details?: any) => void;
}

/**
 * 添加自定义日志方法
 */
const extendedLogger = logger as ExtendedLogger;

// 请求日志
extendedLogger.request = (message: string, meta?: any) => {
  logger.info({ ...meta, type: 'request' }, `[REQUEST] ${message}`);
};

// 响应日志
extendedLogger.response = (message: string, meta?: any) => {
  logger.info({ ...meta, type: 'response' }, `[RESPONSE] ${message}`);
};

// 认证日志
extendedLogger.auth = (message: string, meta?: any) => {
  logger.info({ ...meta, type: 'auth' }, `[AUTH] ${message}`);
};

// 代理日志
extendedLogger.proxy = (message: string, meta?: any) => {
  logger.info({ ...meta, type: 'proxy' }, `[PROXY] ${message}`);
};

// 健康检查日志
extendedLogger.health = (message: string, meta?: any) => {
  logger.info({ ...meta, type: 'health' }, `[HEALTH] ${message}`);
};

// 性能日志
extendedLogger.performance = (operation: string, duration: number, meta?: any) => {
  logger.info(
    {
      ...meta,
      type: 'performance',
      duration: `${duration}ms`,
      durationMs: duration,
    },
    `[PERFORMANCE] ${operation}`,
  );
};

// 安全日志
extendedLogger.security = (event: string, details?: any) => {
  logger.warn({ ...details, type: 'security' }, `[SECURITY] ${event}`);
};

/**
 * 请求日志中间件
 */
export function requestLogger(req: any, res: any, next: any): void {
  const start = Date.now();
  const { method, url, ip } = req;

  extendedLogger.request(`${method} ${url}`, {
    ip,
    userAgent: req.get('User-Agent'),
    requestId: req.id || `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
  });

  // 记录响应时间
  res.on('finish', () => {
    const duration = Date.now() - start;
    const { statusCode } = res;

    extendedLogger.response(`${method} ${url} ${statusCode}`, {
      duration: `${duration}ms`,
      durationMs: duration,
      statusCode,
      requestId: req.id,
    });
  });

  next();
}

/**
 * 错误日志中间件
 */
export function errorLogger(err: Error, req: any, res: any, next: any): void {
  logger.error(
    {
      err,
      method: req.method,
      url: req.url,
      ip: req.ip,
      userAgent: req.get('User-Agent'),
      requestId: req.id,
      type: 'request_error',
    },
    'Request error',
  );

  next(err);
}

/**
 * 性能监控
 */
export function logPerformance(operation: string, startTime: number, meta?: any): void {
  const duration = Date.now() - startTime;
  extendedLogger.performance(operation, duration, meta);
}

/**
 * 安全日志
 */
export function logSecurity(event: string, details: any): void {
  extendedLogger.security(event, details);
}

export default extendedLogger;
