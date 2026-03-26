/**
 * Request Logger Middleware
 * Provides unified request logging with request ID tracking
 */

import type { Request, Response, NextFunction } from 'express';
import logger from '~/server/utils/logger';

/**
 * Paths to skip logging (high-frequency endpoints)
 */
const SKIP_LOGGING_PATHS = ['/api/i18n/', '/api/menu/user', '/health', '/favicon.ico'];

/**
 * Generate a unique request ID
 */
function generateRequestId(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 11)}`;
}

/**
 * Check if a path should skip logging
 */
function shouldSkipLogging(path: string): boolean {
  return SKIP_LOGGING_PATHS.some((skipPath) => path.includes(skipPath));
}

/**
 * Request Logger Middleware
 *
 * Features:
 * - Generates/forwards X-Request-Id
 * - Logs request start and completion
 * - Skips high-frequency paths
 * - Returns Request ID in response header
 */
export function requestLogger(req: Request, res: Response, next: NextFunction): void {
  // Generate or use existing request ID
  const requestId = (req.headers['x-request-id'] as string) || generateRequestId();
  const startTime = Date.now();
  const path = req.originalUrl || req.url;
  const skipLogging = shouldSkipLogging(path);

  // Attach request ID to request object for downstream use
  (req as any).requestId = requestId;

  // Set request ID in response header
  res.setHeader('X-Request-Id', requestId);

  // Log request start
  if (!skipLogging) {
    logger.info(
      {
        requestId,
        method: req.method,
        path,
        clientIp: req.ip || req.socket?.remoteAddress,
        userAgent: req.headers['user-agent'],
      },
      `Request started`,
    );
  }

  // Hook into response finish event
  res.on('finish', () => {
    const duration = Date.now() - startTime;

    if (!skipLogging) {
      const logData = {
        requestId,
        method: req.method,
        path,
        statusCode: res.statusCode,
        duration: `${duration}ms`,
        contentLength: res.getHeader('content-length'),
      };

      if (res.statusCode >= 500) {
        logger.error(logData, `Request completed with server error`);
      } else if (res.statusCode >= 400) {
        logger.warn(logData, `Request completed with client error`);
      } else {
        logger.info(logData, `Request completed`);
      }
    }
  });

  // Hook into response close event (client disconnect)
  res.on('close', () => {
    if (!res.writableEnded && !skipLogging) {
      const duration = Date.now() - startTime;
      logger.info(
        {
          requestId,
          method: req.method,
          path,
          duration: `${duration}ms`,
        },
        `Request aborted by client`,
      );
    }
  });

  next();
}

/**
 * Error logger middleware
 * Should be used after routes to catch and log errors
 */
export function errorLogger(error: Error, req: Request, res: Response, next: NextFunction): void {
  const requestId = (req as any).requestId || 'unknown';

  logger.error(
    {
      requestId,
      method: req.method,
      path: req.originalUrl || req.url,
      error: error.message,
      stack: error.stack,
    },
    `Unhandled error in request`,
  );

  next(error);
}

export default requestLogger;
