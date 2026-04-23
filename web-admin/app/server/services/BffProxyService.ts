import type { Request, Response } from 'express';
import axios, { type AxiosResponse } from 'axios';
import * as http from 'http';
import * as https from 'https';
import { config } from '~/server/utils/config';
import logger from '~/server/utils/logger';
import { sessionStorage } from '~/shared/services/session';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

// Explicit HTTP agents that bypass system proxy (http_proxy/https_proxy env vars).
// Without this, axios respects the proxy env vars and routes localhost requests
// through the system proxy (e.g. Clash at 127.0.0.1:7891), causing 502 errors.
const noProxyHttpAgent = new http.Agent({
  keepAlive: true,
  maxSockets: 100,
  maxFreeSockets: 20,
  keepAliveMsecs: 30000,
});
const noProxyHttpsAgent = new https.Agent({
  keepAlive: true,
  maxSockets: 100,
  maxFreeSockets: 20,
  keepAliveMsecs: 30000,
});

/**
 * BFF代理服务 - 统一处理API请求代理
 */
export class BffProxyService {
  private backendUrl: string;

  constructor(options: { target: string }) {
    this.backendUrl = options.target;
  }

  /**
   * Get SSE endpoints from configuration
   */
  private get sseEndpoints() {
    return config.sse.endpoints;
  }

  /**
   * Check if the request is an SSE request based on path and method
   */
  private isSSERequest(path: string, method: string): boolean {
    const upperMethod = method.toUpperCase();
    return this.sseEndpoints.some(
      (endpoint) =>
        upperMethod === endpoint.method.toUpperCase() &&
        (path === endpoint.path ||
          path.startsWith(`${endpoint.path}?`) ||
          path.startsWith(`${endpoint.path}/`)),
    );
  }

  /**
   * Get the HTTP method for an SSE endpoint
   */
  private getSSEMethod(path: string): 'get' | 'post' | null {
    const endpoint = this.sseEndpoints.find(
      (ep) => path === ep.path || path.startsWith(`${ep.path}?`) || path.startsWith(`${ep.path}/`),
    );
    return endpoint?.method || null;
  }

  /**
   * 创建代理中间件
   */
  createProxyMiddleware() {
    return (req: Request, res: Response) => {
      this.handleApiRequest(req, res);
    };
  }

  async handleApiRequest(req: Request, res: Response): Promise<void> {
    const requestId = this.generateRequestId();
    const startTime = Date.now();

    try {
      const originalPath = req.originalUrl || req.url;

      // 过滤掉高频请求的日志
      const skipLogging =
        originalPath.includes('/api/i18n/zh-CN') || originalPath.includes('/api/menu/user');

      // 构建完整的后端URL
      const backendUrl = `${this.backendUrl}${originalPath}`;

      // 记录请求开始日志
      if (!skipLogging) {
        const requestStartDetails = {
          requestId,
          method: req.method,
          url: originalPath,
          backendUrl,
          userAgent: req.headers['user-agent'],
          clientIp: req.ip || req.connection.remoteAddress,
          contentType: req.headers['content-type'],
          contentLength: req.headers['content-length'],
        };

        logger.info(`🚀 API Request Started [${requestId}] [${backendUrl}]`, requestStartDetails);
      }

      // Check if this is an SSE request using unified detection
      if (this.isSSERequest(originalPath, req.method)) {
        const sseMethod = this.getSSEMethod(originalPath);
        logger.info(
          `[${requestId}] Detected SSE request (method: ${sseMethod}), using streaming proxy`,
        );
        await this.handleUnifiedSSERequest(req, res, backendUrl, requestId);
        return;
      }

      // Fallback multipart handling for endpoints not covered by uploadRouter
      // (uploadRouter handles specific paths like /admin/documents/upload, /plugins/packages/upload)
      const contentType = req.headers['content-type'] || '';
      if (contentType.includes('multipart/form-data')) {
        logger.info(`[${requestId}] Detected multipart request (fallback), using stream proxy`);
        const token = await this.extractToken(req);
        await this.handleMultipartRequest(req, res, backendUrl, requestId, token);
        return;
      }

      // Check if this is a file download request (path segment ends with /download or /download?)
      if (/\/download(?:\?|$)/.test(originalPath)) {
        await this.handleBinaryDownload(req, res, backendUrl, requestId);
        return;
      }

      // Longer timeout for plugin import and deploy operations
      const isLongRunning =
        originalPath.includes('/plugins/import') ||
        originalPath.includes('/plugins/packages') ||
        originalPath.includes('/deploy');
      const longRunningTimeout = Number.parseInt(
        process.env.BFF_LONG_RUNNING_TIMEOUT_MS || '900000',
        10,
      );
      const timeout = isLongRunning ? longRunningTimeout : 30000;

      // 准备请求配置
      const axiosConfig = {
        method: req.method.toLowerCase() as any,
        url: backendUrl,
        headers: await this.sanitizeHeaders(req),
        data: req.body,
        timeout,
        httpAgent: noProxyHttpAgent,
        httpsAgent: noProxyHttpsAgent,
        proxy: false as const, // Disable axios built-in proxy detection
      };

      // 发送请求到后端
      const response = await axios(axiosConfig);

      // 转发响应
      this.forwardResponse(response, res, skipLogging);

      const duration = Date.now() - startTime;
      const responseSize = this.calculateResponseSize(response.data);

      // 构建详细的成功日志
      const successDetails = {
        requestId,
        method: req.method,
        url: req.originalUrl || req.url,
        status: response.status,
        duration: `${duration}ms`,
        responseSize,
        userAgent: req.headers['user-agent'],
        clientIp: req.ip || req.connection.remoteAddress,
      };

      if (!skipLogging) {
        if (this.isVerboseLogging()) {
          // 详细日志模式：包含响应数据预览
          const responsePreview = this.formatResponsePreview(response.data, 500);
          logger.info(`✅ API Proxy Success [${requestId}]`, {
            ...successDetails,
            responsePreview,
          });
        } else {
          // 简洁日志模式
          logger.info(`✅ API Proxy Success [${requestId}]`, successDetails);
        }
      }
    } catch (error) {
      const duration = Date.now() - startTime;
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';

      // 增强错误日志，包含请求详情
      const requestDetails = {
        requestId,
        method: req.method,
        url: req.originalUrl || req.url,
        userAgent: req.headers['user-agent'],
        clientIp: req.ip || req.connection.remoteAddress,
        duration: `${duration}ms`,
        error: errorMessage,
      };

      logger.error(`🚨 API Proxy Error [${requestId}]`, requestDetails);
      this.handleProxyError(error, res);
    }
  }

  /**
   * 处理二进制文件下载
   */
  private async handleBinaryDownload(
    req: Request,
    res: Response,
    backendUrl: string,
    requestId: string,
  ): Promise<void> {
    try {
      const headers = await this.sanitizeHeaders(req);
      // 移除 accept: application/json，允许接收任何类型
      delete headers['accept'];

      logger.info(`[${requestId}] Starting binary download from ${backendUrl}`);

      const response = await axios({
        method: 'get',
        url: backendUrl,
        headers,
        responseType: 'arraybuffer',
        timeout: 60000, // 60秒超时
        httpAgent: noProxyHttpAgent,
        httpsAgent: noProxyHttpsAgent,
        proxy: false as const,
      });

      // 转发响应头
      const contentType = response.headers['content-type'];
      const contentDisposition = response.headers['content-disposition'];
      const contentLength = response.headers['content-length'];

      if (contentType) {
        res.setHeader('Content-Type', contentType);
      }
      if (contentDisposition) {
        res.setHeader('Content-Disposition', contentDisposition);
      }
      if (contentLength) {
        res.setHeader('Content-Length', contentLength);
      }
      res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');

      // 发送二进制数据
      res.status(response.status).send(Buffer.from(response.data));

      logger.info(
        `[${requestId}] Binary download completed, size: ${response.data.byteLength} bytes`,
      );
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      logger.error(`[${requestId}] Binary download error: ${errorMessage}`);

      if (!res.headersSent) {
        res.status(500).json({
          error: 'Download Error',
          message: errorMessage,
        });
      }
    }
  }

  /**
   * Handle multipart/form-data requests (file upload fallback)
   * Transparently forwards request stream to backend without parsing body
   */
  private handleMultipartRequest(
    req: Request,
    res: Response,
    backendUrl: string,
    requestId: string,
    token: string | null,
  ): Promise<void> {
    return new Promise((resolve) => {
      try {
        const parsedUrl = new URL(backendUrl);
        const isHttps = parsedUrl.protocol === 'https:';
        const httpModule = isHttps ? https : http;

        // Prepare headers, preserving original content-type (includes boundary)
        const headers: Record<string, string> = {};

        if (req.headers['content-type']) {
          headers['content-type'] = req.headers['content-type'] as string;
        }
        if (req.headers['content-length']) {
          headers['content-length'] = req.headers['content-length'] as string;
        }

        // Add authentication header
        if (token) {
          headers['authorization'] = `Bearer ${token}`;
        } else if (req.headers.authorization) {
          headers['authorization'] = req.headers.authorization as string;
        }

        // Add other required headers
        headers['x-request-id'] = requestId;
        if (req.headers['accept-language']) {
          headers['accept-language'] = req.headers['accept-language'] as string;
        }

        logger.info(
          `[${requestId}] Forwarding multipart request to ${backendUrl}, method: ${req.method}`,
        );

        const proxyReq = httpModule.request(
          {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port || (isHttps ? 443 : 80),
            path: parsedUrl.pathname + parsedUrl.search,
            method: req.method,
            headers,
          },
          (proxyRes: http.IncomingMessage) => {
            logger.info(`[${requestId}] Multipart proxy response: ${proxyRes.statusCode}`);

            res.status(proxyRes.statusCode || 200);

            // Copy response headers
            Object.keys(proxyRes.headers).forEach((key) => {
              const value = proxyRes.headers[key];
              if (value) {
                res.setHeader(key, value);
              }
            });

            // Stream forward response body
            proxyRes.pipe(res);

            proxyRes.on('end', () => {
              resolve();
            });
          },
        );

        proxyReq.on('error', (error: Error) => {
          logger.error(`[${requestId}] Multipart proxy error: ${error.message}`);
          if (!res.headersSent) {
            res.status(502).json({
              error: 'Proxy Error',
              message: error.message,
            });
          }
          resolve();
        });

        // Set timeout
        proxyReq.setTimeout(300000, () => {
          logger.error(`[${requestId}] Multipart proxy timeout`);
          proxyReq.destroy();
          if (!res.headersSent) {
            res.status(408).json({
              error: 'Request Timeout',
              message: 'Upload timeout',
            });
          }
          resolve();
        });

        // Pipe original request stream to backend
        req.pipe(proxyReq);
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Unknown error';
        logger.error(`[${requestId}] Multipart request error: ${errorMessage}`);
        if (!res.headersSent) {
          res.status(500).json({
            error: 'Internal Error',
            message: errorMessage,
          });
        }
        resolve();
      }
    });
  }

  /**
   * Unified SSE handler - handles both GET and POST SSE requests
   * with proper client disconnect handling to prevent resource leaks
   */
  private async handleUnifiedSSERequest(
    req: Request,
    res: Response,
    backendUrl: string,
    requestId: string,
  ): Promise<void> {
    const method = req.method.toUpperCase();
    let aborted = false;
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    const abortController = new AbortController();
    const connectionTimeout = config.sse.connectionTimeout;
    const timeoutId = setTimeout(() => {
      logger.info(`[${requestId}] SSE connection timeout (${connectionTimeout}ms), aborting`);
      abortController.abort();
    }, connectionTimeout);

    try {
      const headers = await this.sanitizeHeaders(req);
      // Use SSE-specific Accept header
      headers['accept'] = 'text/event-stream';

      logger.info(`[${requestId}] Starting SSE stream (${method}) to ${backendUrl}`);

      // Prepare fetch options based on HTTP method
      const fetchOptions: RequestInit = {
        method,
        headers,
        signal: abortController.signal,
      };

      // Add body for POST requests (method is uppercased on line 386)
      if (method === 'POST' && req.body) {
        fetchOptions.body = JSON.stringify(req.body);
      }

      // Handle client disconnect - this is critical for resource cleanup
      const onClientClose = () => {
        logger.info(`[${requestId}] Client disconnected from SSE stream`);
        aborted = true;
        abortController.abort();
        if (reader) {
          reader.cancel().catch(() => {
            // Ignore cancel errors - connection is already closed
          });
        }
      };

      req.on('close', onClientClose);
      req.on('aborted', onClientClose);

      // Make the request to backend
      const response = await fetch(backendUrl, fetchOptions);

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      // Set SSE response headers
      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('Connection', 'keep-alive');
      res.setHeader('X-Accel-Buffering', 'no'); // Disable nginx buffering
      res.setHeader('X-Request-Id', requestId);

      // Get reader for streaming
      reader = response.body?.getReader() || null;
      if (!reader) {
        throw new Error('Response body is null');
      }

      const decoder = new TextDecoder();
      let eventCount = 0;

      try {
        // Main streaming loop - check aborted flag on each iteration
        while (!aborted) {
          const { done, value } = await reader.read();

          if (done) {
            logger.info(`[${requestId}] SSE stream completed normally, events: ${eventCount}`);
            break;
          }

          // Check if client disconnected during read
          if (aborted) {
            logger.info(`[${requestId}] SSE stream aborted by client after read`);
            break;
          }

          // Forward data chunk to client
          const chunk = decoder.decode(value, { stream: true });

          // Check if response is still writable
          if (!res.writableEnded) {
            res.write(chunk);
          } else {
            aborted = true;
            break;
          }

          // Count events (count data: lines)
          eventCount += (chunk.match(/^data:/gm) || []).length;
        }
      } finally {
        clearTimeout(timeoutId);
        abortController.abort();
        reader.releaseLock();
        if (!res.writableEnded) {
          res.end();
        }
      }
    } catch (error) {
      clearTimeout(timeoutId);
      abortController.abort();
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';

      // Don't log error if it was just a client disconnect or abort
      if (!aborted && !abortController.signal.aborted) {
        logger.error(`[${requestId}] SSE stream error: ${errorMessage}`);
      }

      if (!res.headersSent) {
        res.status(500).json({
          error: 'SSE Stream Error',
          message: errorMessage,
        });
      } else if (!res.writableEnded) {
        // Send error event if streaming has started
        res.write(`event: error\ndata: ${JSON.stringify({ message: errorMessage })}\n\n`);
        res.end();
      }
    }
  }

  /**
   * 清理请求头并添加locale和token信息
   */
  private async sanitizeHeaders(req: Request): Promise<Record<string, string>> {
    const sanitized: Record<string, string> = {};

    // 检查是否已有Authorization header（来自前端SSR）
    let hasAuthHeader = false;

    Object.entries(req.headers).forEach(([key, value]) => {
      const lowerKey = key.toLowerCase();

      // 跳过 cookie 头，我们会单独处理
      if (lowerKey === 'cookie') {
        return;
      }

      // 保留前端发送的Authorization header
      if (lowerKey === 'authorization') {
        hasAuthHeader = true;
        if (typeof value === 'string') {
          sanitized[key] = value;
          logger.debug(
            `[BFF] Using Authorization header from frontend: ${value.substring(0, 30)}...`,
          );
        }
        return;
      }

      if (typeof value === 'string') {
        sanitized[key] = value;
      } else if (Array.isArray(value)) {
        sanitized[key] = value[0];
      }
    });

    // Default to JSON for API requests (SSE handlers override this later)
    if (!sanitized['accept'] || sanitized['accept'] === '*/*') {
      sanitized['accept'] = 'application/json';
    }
    sanitized['content-type'] = sanitized['content-type'] || 'application/json';

    // 提取并设置locale信息
    const locale = this.extractLocale(req);
    if (locale) {
      sanitized['Accept-Language'] = locale;
      sanitized['X-Locale'] = locale;
      logger.debug(`设置locale请求头: ${locale}`);
    }

    // 如果前端没有发送Authorization header，则从session中提取token
    if (!hasAuthHeader) {
      const token = await this.extractToken(req);
      if (token) {
        sanitized['Authorization'] = `Bearer ${token}`;
        logger.debug(`[BFF] Extracted JWT token from session: ${token.substring(0, 20)}...`);
      } else {
        logger.warn(
          '[BFF] No Authorization header from frontend and failed to extract token from session',
        );
      }
    }

    return sanitized;
  }

  /**
   * 从请求中提取locale信息
   * 优先级：查询参数 > Cookie > Accept-Language > 默认值
   */
  private extractLocale(req: Request): string {
    // 1. 优先从查询参数获取
    const url = new URL(req.url, `http://${req.headers.host}`);
    const localeParam = url.searchParams.get('locale');
    if (localeParam) {
      logger.debug(`从查询参数获取locale: ${localeParam}`);
      return localeParam;
    }

    // 2. 从Cookie中获取
    const cookies = this.parseCookies(req.headers.cookie || '');
    const localeCookie = cookies['locale'];
    if (localeCookie) {
      logger.debug(`从Cookie获取locale: ${localeCookie}`);
      return localeCookie;
    }

    // 3. 从Accept-Language请求头获取
    const acceptLanguage = req.headers['accept-language'];
    if (acceptLanguage) {
      const browserLocale = this.parseBrowserLocale(acceptLanguage);
      if (browserLocale) {
        logger.debug(`从Accept-Language获取locale: ${browserLocale}`);
        return browserLocale;
      }
    }

    // 4. 默认值
    const defaultLocale = 'zh-CN';
    logger.debug(`使用默认locale: ${defaultLocale}`);
    return defaultLocale;
  }

  /**
   * 从请求中提取JWT token
   */
  private async extractToken(req: Request): Promise<string | null> {
    try {
      const cookieHeader = req.headers.cookie || '';
      const requestUrl = req.originalUrl || req.url;

      if (!cookieHeader) {
        logger.debug(`🍪 No cookies found in request to ${requestUrl}`);
        return null;
      }

      // 解析cookie以便调试
      const cookies = this.parseCookies(cookieHeader);
      const cookieNames = Object.keys(cookies);

      logger.debug(`🍪 Found cookies: [${cookieNames.join(', ')}] for ${requestUrl}`);

      // 使用 React Router 的 sessionStorage 解析 session
      const session = await sessionStorage.getSession(cookieHeader);

      // 从 session 中提取 JWT token
      const token = session.get(JWT_TOKEN_KEY);

      if (token && typeof token === 'string') {
        // 验证token格式
        const tokenParts = token.split('.');
        if (tokenParts.length === 3) {
          logger.debug(
            `🔑 Valid JWT token extracted for ${requestUrl} (${token.substring(0, 20)}...)`,
          );
          return token;
        } else {
          logger.warn(
            `🔑 Invalid JWT token format for ${requestUrl} (parts: ${tokenParts.length})`,
          );
          return null;
        }
      }

      logger.debug(
        `🔑 No JWT token found in session for ${requestUrl} (session keys: [${Object.keys(session.data || {}).join(', ')}])`,
      );
      return null;
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      logger.warn(
        `🔑 Failed to extract JWT token for ${req.originalUrl || req.url}: ${errorMessage}`,
      );
      return null;
    }
  }

  /**
   * 解析Cookie字符串
   */
  private parseCookies(cookieHeader: string): Record<string, string> {
    const cookies: Record<string, string> = {};

    cookieHeader.split(';').forEach((cookie) => {
      const [name, ...rest] = cookie.trim().split('=');
      if (name && rest.length > 0) {
        cookies[name] = decodeURIComponent(rest.join('='));
      }
    });

    return cookies;
  }

  /**
   * 解析浏览器Accept-Language头
   */
  private parseBrowserLocale(acceptLanguage: string): string | null {
    try {
      const supportedLocales = ['zh-CN', 'en-US', 'ja-JP', 'ko-KR'];
      const languages = acceptLanguage.split(',');

      for (const language of languages) {
        const locale = language.split(';')[0].trim();

        // 直接匹配支持的locale
        if (supportedLocales.includes(locale)) {
          return locale;
        }

        // 尝试匹配语言代码
        const languageCode = locale.split('-')[0];
        const matchedLocale = supportedLocales.find((supported) =>
          supported.startsWith(languageCode + '-'),
        );
        if (matchedLocale) {
          return matchedLocale;
        }
      }
    } catch (error: unknown) {
      logger.warn(
        { error: error instanceof Error ? error.message : String(error) },
        '解析Accept-Language失败',
      );
    }

    return null;
  }

  /**
   * 生成请求ID
   */
  private generateRequestId(): string {
    return `${Date.now()}-${Math.random().toString(36).substring(2, 11)}`;
  }

  /**
   * 计算响应大小
   */
  private calculateResponseSize(data: any): string {
    if (!data) return '0B';

    let size = 0;
    if (typeof data === 'string') {
      size = Buffer.byteLength(data, 'utf8');
    } else if (Buffer.isBuffer(data)) {
      size = data.length;
    } else if (data instanceof ArrayBuffer) {
      size = data.byteLength;
    } else {
      size = Buffer.byteLength(JSON.stringify(data), 'utf8');
    }

    if (size < 1024) return `${size}B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`;
    return `${(size / (1024 * 1024)).toFixed(1)}MB`;
  }

  /**
   * 格式化响应数据预览
   */
  private formatResponsePreview(data: any, maxLength: number = 100): string {
    if (!data) return 'null';

    let preview: string;
    if (typeof data === 'string') {
      preview = data;
    } else if (typeof data === 'object') {
      try {
        preview = JSON.stringify(data);
      } catch {
        preview = '[Object - Cannot stringify]';
      }
    } else {
      preview = String(data);
    }

    if (preview.length <= maxLength) {
      return preview;
    }

    return preview.substring(0, maxLength) + '...';
  }

  /**
   * 检查是否启用详细日志
   */
  private isVerboseLogging(): boolean {
    return (
      process.env.LOG_LEVEL === 'debug' ||
      process.env.BFF_VERBOSE_LOGGING === 'true' ||
      config.server.env === 'development'
    );
  }

  /**
   * 转发响应到客户端
   */
  private forwardResponse(
    response: AxiosResponse,
    res: Response,
    skipLogging: boolean = false,
  ): void {
    // 设置响应状态码
    res.status(response.status);

    try {
      // 设置JSON响应头
      this.setJsonResponseHeaders(res, response.headers);

      // 直接转发JSON响应
      res.json(response.data);

      // 记录响应转发日志
      if (!skipLogging) {
        if (this.isVerboseLogging()) {
          //todo 这里的1000000是一个临时值，需要根据实际情况调整
          const responsePreview = this.formatResponsePreview(response.data, 1000000);
          logger.info(
            `JSON response forwarded - Status: ${response.status}, Response: ${responsePreview}`,
          );
        } else {
          logger.info(`JSON response forwarded - Status: ${response.status}`);
        }
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      logger.error(`Failed to process JSON response: ${errorMessage}`);

      res.status(500).json({
        error: 'Response Processing Error',
        message: 'Failed to process backend response',
        details: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  }

  /**
   * 设置JSON响应头
   */
  private setJsonResponseHeaders(res: Response, originalHeaders: any): void {
    // 清除可能冲突的响应头
    res.removeHeader('Content-Length');
    res.removeHeader('Transfer-Encoding');
    res.set('Content-Type', 'application/json; charset=utf-8');

    // 转发其他响应头（排除可能冲突的头）
    Object.entries(originalHeaders).forEach(([key, value]) => {
      const lowerKey = key.toLowerCase();
      if (
        typeof value === 'string' &&
        lowerKey !== 'content-type' &&
        lowerKey !== 'content-length' &&
        lowerKey !== 'transfer-encoding'
      ) {
        res.set(key, value);
      }
    });
  }

  /**
   * 处理代理错误
   */
  private handleProxyError(error: any, res: Response): void {
    const errorMessage = error.message || 'Unknown error';
    const errorCode = error.code || 'unknown';
    const errorStatus = error.response?.status || 'N/A';
    const errorStatusText = error.response?.statusText || 'N/A';
    const requestUrl = error.config?.url || 'Unknown URL';
    const requestMethod = error.config?.method?.toUpperCase() || 'Unknown Method';

    // 构建详细的错误日志
    const errorDetails = {
      message: errorMessage,
      code: errorCode,
      status: errorStatus,
      statusText: errorStatusText,
      url: requestUrl,
      method: requestMethod,
      headers: error.config?.headers ? this.sanitizeHeadersForLogging(error.config.headers) : {},
      responseData: error.response?.data || null,
      requestData: error.config?.data
        ? this.sanitizeRequestDataForLogging(error.config.data)
        : null,
    };

    // 根据错误类型提供更详细的日志
    if (axios.isAxiosError(error) && error.response) {
      const status = error.response.status;
      const responseData = error.response.data;
      const binaryResponse =
        Buffer.isBuffer(responseData) ||
        responseData instanceof ArrayBuffer ||
        ArrayBuffer.isView(responseData);

      if (status === 401) {
        logger.error(`🔐 Authentication Error [${errorCode}] - ${requestMethod} ${requestUrl}`, {
          status: status,
          message: errorMessage,
          responseData: error.response.data,
          headers: errorDetails.headers,
          suggestion: 'Check JWT token validity, expiration, or authentication configuration',
        });
      } else if (status === 403) {
        logger.error(`🚫 Authorization Error [${errorCode}] - ${requestMethod} ${requestUrl}`, {
          status: status,
          message: errorMessage,
          responseData: error.response.data,
          headers: errorDetails.headers,
          suggestion: 'Check user permissions and role-based access control',
        });
      } else if (status === 404) {
        logger.error(`🔍 Not Found Error [${errorCode}] - ${requestMethod} ${requestUrl}`, {
          status: status,
          message: errorMessage,
          responseData: error.response.data,
          suggestion: 'Check API endpoint URL and backend service availability',
        });
      } else if (status >= 500) {
        logger.error(`🔥 Server Error [${errorCode}] - ${requestMethod} ${requestUrl}`, {
          status: status,
          message: errorMessage,
          responseData: error.response.data,
          headers: errorDetails.headers,
          suggestion: 'Backend service internal error, check backend logs',
        });
      } else {
        logger.error(
          `❌ Client Error [${errorCode}] - ${requestMethod} ${requestUrl}`,
          errorDetails,
        );
      }

      // Preserve backend error envelopes so frontend/runtime tests see the real API contract.
      if (responseData !== undefined && responseData !== null) {
        if (typeof responseData === 'string') {
          try {
            this.setJsonResponseHeaders(res, error.response.headers || {});
            res.status(status).json(JSON.parse(responseData));
            return;
          } catch {
            res.status(status).send(responseData);
            return;
          }
        }

        if (binaryResponse) {
          const buffer = Buffer.isBuffer(responseData)
            ? responseData
            : responseData instanceof ArrayBuffer
              ? Buffer.from(responseData)
              : Buffer.from(responseData.buffer, responseData.byteOffset, responseData.byteLength);
          const text = buffer.toString('utf-8');
          try {
            this.setJsonResponseHeaders(res, error.response.headers || {});
            res.status(status).json(JSON.parse(text));
            return;
          } catch {
            res.status(status).send(text);
            return;
          }
        }

        if (typeof responseData === 'object') {
          this.setJsonResponseHeaders(res, error.response.headers || {});
          res.status(status).json(responseData);
          return;
        }
      }

      res.status(status).json({
        error: 'Proxy Error',
        message: error.message,
        status,
        timestamp: new Date().toISOString(),
      });
    } else if (error.code === 'econnrefused') {
      logger.error(`🔌 Connection Refused [${errorCode}] - ${requestMethod} ${requestUrl}`, {
        message: errorMessage,
        suggestion: 'Backend service is not running or not accessible',
        backendUrl: this.backendUrl,
      });

      res.status(502).json({
        error: 'Service Unavailable',
        message: 'Backend service is not accessible',
        details: 'Connection refused - please check if the backend service is running',
        timestamp: new Date().toISOString(),
      });
    } else if (error.code === 'etimedout' || error.code === 'enotfound') {
      logger.error(`⏰ Network Error [${errorCode}] - ${requestMethod} ${requestUrl}`, {
        message: errorMessage,
        suggestion: 'Network connectivity issue or DNS resolution failure',
        backendUrl: this.backendUrl,
      });

      res.status(502).json({
        error: 'Network Error',
        message: 'Failed to connect to backend service',
        details: errorMessage,
        timestamp: new Date().toISOString(),
      });
    } else {
      // 其他未知错误
      logger.error(
        `💥 Unknown Proxy Error [${errorCode}] - ${requestMethod} ${requestUrl}`,
        errorDetails,
      );

      res.status(502).json({
        error: 'Bad Gateway',
        message: 'Failed to connect to backend service',
        details: error.message,
        timestamp: new Date().toISOString(),
      });
    }
  }

  /**
   * 清理请求头用于日志记录（移除敏感信息）
   */
  private sanitizeHeadersForLogging(headers: any): Record<string, any> {
    const sanitized: Record<string, any> = {};

    Object.entries(headers).forEach(([key, value]) => {
      const lowerKey = key.toLowerCase();

      if (lowerKey === 'authorization') {
        // 只显示token类型和前几个字符
        const authValue = String(value);
        if (authValue.startsWith('Bearer ')) {
          const token = authValue.substring(7);
          sanitized[key] =
            `Bearer ${token.substring(0, 10)}...${token.substring(token.length - 4)}`;
        } else {
          sanitized[key] = '[REDACTED]';
        }
      } else if (lowerKey === 'cookie') {
        sanitized[key] = '[REDACTED]';
      } else {
        sanitized[key] = value;
      }
    });

    return sanitized;
  }

  /**
   * 清理请求数据用于日志记录（移除敏感信息）
   */
  private sanitizeRequestDataForLogging(data: any): any {
    if (!data) return null;

    try {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;

      // 移除可能的敏感字段
      const sensitiveFields = ['password', 'token', 'secret', 'key', 'credential'];
      const sanitized = { ...parsed };

      sensitiveFields.forEach((field) => {
        if (sanitized[field]) {
          sanitized[field] = '[REDACTED]';
        }
      });

      // 限制数据大小
      const sanitizedStr = JSON.stringify(sanitized);
      if (sanitizedStr.length > 1000) {
        return sanitizedStr.substring(0, 1000) + '... [TRUNCATED]';
      }

      return sanitized;
    } catch (error) {
      return '[UNPARSEABLE DATA]';
    }
  }

  /**
   * 健康检查
   */
  async healthCheck(): Promise<{ status: string; timestamp: string; backend?: any }> {
    const startTime = Date.now();

    try {
      // 直接检查后端健康状态
      const response = await axios.get(`${this.backendUrl}/actuator/health`, {
        timeout: 5000,
        httpAgent: noProxyHttpAgent,
        httpsAgent: noProxyHttpsAgent,
        proxy: false as const,
      });

      const duration = Date.now() - startTime;

      logger.info(
        `Health check successful in ${duration}ms - Status: ${response.status}, Backend Status: ${response.data?.status || 'N/A'}`,
      );

      return {
        status: 'healthy',
        timestamp: new Date().toISOString(),
        backend: response.data,
      };
    } catch (error) {
      const duration = Date.now() - startTime;
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';

      logger.error(`Health check failed in ${duration}ms - Error: ${errorMessage}`);

      return {
        status: 'unhealthy',
        timestamp: new Date().toISOString(),
        backend: {
          error: errorMessage,
        },
      };
    }
  }
}
