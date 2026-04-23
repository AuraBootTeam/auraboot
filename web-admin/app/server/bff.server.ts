import express from 'express';
import cookieParser from 'cookie-parser';
import compression from 'compression';
import axios from 'axios';
import * as http from 'http';
import * as https from 'https';
import dns from 'node:dns';
import { BffProxyService } from '~/server/services/BffProxyService';
import { bffFlowDesignerService } from '~/server/services/BffFlowDesignerService';

import uploadRouter from '~/server/routes/upload';
import { config } from '~/server/utils/config';
import { requestLogger, errorLogger } from '~/server/middlewares/RequestLogger';
import { register, proxyDurationHistogram } from './metrics.server';

// ============================================================
// CRITICAL: Bypass system proxy for ALL axios requests in BFF.
// Without this, http_proxy/https_proxy env vars (e.g. Clash at
// 127.0.0.1:7891) hijack localhost requests → 502 Proxy Error.
// ============================================================
axios.defaults.httpAgent = new http.Agent({
  keepAlive: true,
  maxSockets: 100,
  maxFreeSockets: 20,
  keepAliveMsecs: 30000,
});
axios.defaults.httpsAgent = new https.Agent({
  keepAlive: true,
  maxSockets: 100,
  maxFreeSockets: 20,
  keepAliveMsecs: 30000,
});
axios.defaults.proxy = false;
axios.defaults.timeout = 30000; // 30s — prevents slow backend from cascading to BFF
dns.setDefaultResultOrder('ipv4first');

const app = express();
const PORT = parseInt(process.env.BFF_PORT || '3500', 10);
const SPRING_BOOT_URL = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';

// 初始化服务
const proxyService = new BffProxyService({ target: SPRING_BOOT_URL });

// 中间件配置
app.use(cookieParser());
app.use(requestLogger);

// ✅ 修复: 跳过 multipart/form-data 请求的 body 解析
// multipart 请求需要直接透传到后端或由专门的上传路由处理
const skipBodyParsing = (req: express.Request) => {
  const contentType = req.headers['content-type'] || '';
  return contentType.includes('multipart/form-data');
};

app.use((req, res, next) => {
  if (skipBodyParsing(req)) {
    return next();
  }
  express.json({ limit: '10mb' })(req, res, next);
});

app.use((req, res, next) => {
  if (skipBodyParsing(req)) {
    return next();
  }
  express.urlencoded({ extended: true, limit: '10mb' })(req, res, next);
});

app.use(express.raw({ type: 'application/octet-stream', limit: '50mb' }));

// Gzip/deflate compression for responses
app.use(compression({
  threshold: 1024,
  filter: (req, res) => {
    if (req.headers.accept?.includes('text/event-stream')) return false;
    return compression.filter(req, res);
  },
}));

// 禁用 X-Powered-By 头
app.disable('x-powered-by');

// CORS configuration from config file
const corsConfig = config.cors;

// CORS middleware with externalized configuration
app.use((req, res, next) => {
  const origin = req.headers.origin;

  // Check if origin is in allowed list
  if (origin && corsConfig.allowedOrigins.includes(origin)) {
    res.header('Access-Control-Allow-Origin', origin);
    if (corsConfig.credentials) {
      res.header('Access-Control-Allow-Credentials', 'true');
    }
  } else if (config.server.env === 'development' && origin) {
    // Development mode: allow known localhost ports only
    const ALLOWED_DEV_PORTS = new Set(['3000', '3500', '5173', '5174', '6443']);
    try {
      const url = new URL(origin);
      if (
        (url.hostname === 'localhost' || url.hostname === '127.0.0.1') &&
        ALLOWED_DEV_PORTS.has(url.port)
      ) {
        res.header('Access-Control-Allow-Origin', origin);
        if (corsConfig.credentials) {
          res.header('Access-Control-Allow-Credentials', 'true');
        }
      }
    } catch (e) {
      // Ignore invalid origin
    }
  }

  res.header('Access-Control-Allow-Methods', corsConfig.allowedMethods.join(', '));
  res.header('Access-Control-Allow-Headers', corsConfig.allowedHeaders.join(', '));

  // Handle preflight requests
  if (req.method === 'options') {
    res.sendStatus(200);
    return;
  }

  next();
});

// Prometheus metrics endpoint
app.get('/metrics', async (_req, res) => {
  res.set('Content-Type', register.contentType);
  res.end(await register.metrics());
});

// Proxy timing middleware — measure BFF→backend latency
app.use('/api', (req, res, next) => {
  const start = process.hrtime.bigint();
  res.on('finish', () => {
    const durationSec = Number(process.hrtime.bigint() - start) / 1e9;
    // Normalize path: replace UUIDs and numeric IDs with placeholders
    const path = req.path.replace(/[0-9a-f]{8,}/gi, ':id').replace(/\d+/g, ':n');
    proxyDurationHistogram.observe(
      { method: req.method, path, status: String(res.statusCode) },
      durationSec,
    );
  });
  next();
});

// 文件上传专用路由（在通用代理之前处理）
app.use('/api', uploadRouter);

// 流程设计器专用路由
app.post('/api/flow-designer/flows', bffFlowDesignerService.saveFlow.bind(bffFlowDesignerService));
app.put(
  '/api/flow-designer/flows/:id',
  bffFlowDesignerService.updateFlow.bind(bffFlowDesignerService),
);
app.get(
  '/api/flow-designer/flows/:id',
  bffFlowDesignerService.getFlow.bind(bffFlowDesignerService),
);
app.get(
  '/api/flow-designer/flows',
  bffFlowDesignerService.getFlowList.bind(bffFlowDesignerService),
);
app.delete(
  '/api/flow-designer/flows/:id',
  bffFlowDesignerService.deleteFlow.bind(bffFlowDesignerService),
);
app.post(
  '/api/flow-designer/flows/:id/publish',
  bffFlowDesignerService.publishFlow.bind(bffFlowDesignerService),
);
app.post(
  '/api/flow-designer/flows/:id/duplicate',
  bffFlowDesignerService.duplicateFlow.bind(bffFlowDesignerService),
);

// ✅ 所有 /api/* 请求（包括 /api/ai/*）都转发到 Gateway
// Gateway 会处理认证、RBAC、租户隔离，然后转发到相应的服务
app.use('/api', proxyService.createProxyMiddleware());

// 健康检查端点
app.get('/health', async (req, res) => {
  try {
    const proxyHealth = await proxyService.healthCheck();
    res.json({
      status: 'ok',
      timestamp: new Date().toISOString(),
      services: {
        bff: { status: 'healthy' },
        springBoot: proxyHealth,
      },
    });
  } catch (error) {
    res.status(503).json({
      status: 'error',
      timestamp: new Date().toISOString(),
      error: error instanceof Error ? error.message : 'Unknown error',
    });
  }
});

// ============================================================
// Static asset cache headers
// - /assets/* (Vite content-hashed): immutable, 1 year
// - favicon, logos, manifest, etc.: 1 day
// - HTML responses (SPA shell): no-cache
// ============================================================
app.use('/assets', (_req, res, next) => {
  res.set('Cache-Control', 'public, max-age=31536000, immutable');
  next();
});

// Static file serving with default 1-day cache for non-hashed files
app.use(express.static('build/client', {
  maxAge: '1d',
  setHeaders: (res, filePath) => {
    // HTML files must not be cached (SPA shell changes on every deploy)
    if (filePath.endsWith('.html')) {
      res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
    }
  },
}));

// SPA fallback — serve index.html for all non-API routes
app.get('*', (req, res) => {
  if (process.env.NODE_ENV === 'development') {
    res.status(404).json({ error: 'Route not found in BFF' });
  } else {
    res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.sendFile('index.html', { root: 'build/client' });
  }
});

// Error logging middleware
app.use(errorLogger);

// Error handling middleware
app.use((error: any, req: express.Request, res: express.Response, _next: express.NextFunction) => {
  const requestId = (req as any).requestId || 'unknown';
  console.error(`[${requestId}] BFF Server Error:`, error);
  res.status(500).json({
    error: 'Internal Server Error',
    message:
      config.server.env === 'development'
        ? error?.message || 'Unknown error'
        : 'Something went wrong',
    requestId,
  });
});

// 导出BFF路由设置函数，供Vite插件使用
export const setupBffRoutes = (_expressApp: express.Application) => {
  // 所有BFF路由已在上面定义，这里不需要额外设置
};

// 独立服务器启动（用于生产环境或独立运行）
// 启动服务器（仅在直接运行此文件时）
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1].includes('bff.server')) {
  const server = app.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 BFF Server running on http://0.0.0.0:${PORT}`);
    console.log(`📡 Proxying /api/* (including /api/ai/*) to Gateway at ${SPRING_BOOT_URL}`);
    console.log(`✅ All AI requests now go through Gateway for auth, RBAC, and tenant isolation`);
    console.log(`🔧 CORS enabled for cross-origin requests`);
    console.log(`📁 File upload limit: 100MB`);
  });

  // 设置服务器超时
  server.timeout = 120000; // 2min: sufficient for plugin import + large data export
  server.keepAliveTimeout = 65000; // Keep-alive超时
  server.headersTimeout = 66000; // 头部超时

  // 优雅关闭
  process.on('sigterm', () => {
    console.log('🛑 SIGTERM received, shutting down gracefully');
    server.close(() => {
      console.log('✅ BFF Server closed');
      process.exit(0);
    });
  });

  process.on('sigint', () => {
    console.log('🛑 SIGINT received, shutting down gracefully');
    server.close(() => {
      console.log('✅ BFF Server closed');
      process.exit(0);
    });
  });

  // 处理未捕获的异常
  const isDev = process.env.NODE_ENV !== 'production';

  process.on('uncaughtException', (error) => {
    console.error('💥 Uncaught Exception:', error);
    if (!isDev) process.exit(1);
  });

  process.on('unhandledRejection', (reason, promise) => {
    console.error('💥 Unhandled Rejection at:', promise, 'reason:', reason);
    if (!isDev) process.exit(1);
  });
}

// 默认导出Express应用
export default app;
