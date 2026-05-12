import type { Request, Response, NextFunction } from 'express';
import { AuthApiClient } from '~/server/clients/AuthApiClient';
import { ResultHelper } from '~/utils/type';
import logger from '~/server/utils/logger';
import { config } from '~/server/utils/config';
import jwt, { type JwtPayload } from 'jsonwebtoken';

/**
 * 静默刷新中间件
 * 自动检查和刷新即将过期的JWT token
 */
export class SilentRefreshMiddleware {
  private authClient: AuthApiClient;
  private jwtSecret: string;

  constructor() {
    this.authClient = new AuthApiClient();
    this.jwtSecret = config.jwt.secret;
  }

  /**
   * 中间件处理函数
   */
  middleware = async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    const requestId = (req.headers['x-request-id'] as string) || this.generateRequestId();
    const clientIp = req.ip || req.connection.remoteAddress;
    const userAgent = req.headers['user-agent'];
    const requestPath = req.path;
    const requestMethod = req.method;

    logger.auth(`Silent refresh middleware started [${requestId}]`, {
      requestId,
      clientIp,
      userAgent,
      requestPath,
      requestMethod,
      timestamp: new Date().toISOString(),
    });

    try {
      const token = req.cookies?.token;
      const refreshToken = req.cookies?.refreshToken;

      // 记录认证状态检查
      logger.auth(`Authentication status check [${requestId}]`, {
        requestId,
        authStatus: {
          hasToken: !!token,
          hasRefreshToken: !!refreshToken,
          tokenLength: token ? token.length : 0,
          refreshTokenLength: refreshToken ? refreshToken.length : 0,
          cookieNames: Object.keys(req.cookies || {}),
        },
        timestamp: new Date().toISOString(),
      });

      if (!token || !refreshToken) {
        logger.auth(`No authentication tokens found, skipping refresh [${requestId}]`, {
          requestId,
          reason: 'Missing authentication tokens',
          hasToken: !!token,
          hasRefreshToken: !!refreshToken,
          availableCookies: Object.keys(req.cookies || {}),
          timestamp: new Date().toISOString(),
        });
        return next();
      }

      // 解析token信息
      const tokenInfo = this.getTokenInfo(token);
      logger.auth(`Token information parsed [${requestId}]`, {
        requestId,
        tokenInfo: {
          isValid: tokenInfo.isValid,
          expiresAt: tokenInfo.expiresAt,
          timeUntilExpiry: tokenInfo.timeUntilExpiry,
          isExpiringSoon: tokenInfo.timeUntilExpiry !== null && tokenInfo.timeUntilExpiry < 300,
        },
        timestamp: new Date().toISOString(),
      });

      // 检查token是否即将过期（5分钟内）
      if (this.shouldRefreshToken(token)) {
        logger.auth(`Token refresh needed [${requestId}]`, {
          requestId,
          reason: 'Token expiring soon',
          tokenInfo,
          refreshAction: 'starting_silent_refresh',
          timestamp: new Date().toISOString(),
        });

        await this.performSilentRefresh(req, res, refreshToken, requestId);
      } else {
        logger.auth(`Token refresh not needed [${requestId}]`, {
          requestId,
          reason: 'Token still valid',
          tokenInfo,
          timestamp: new Date().toISOString(),
        });
      }

      logger.auth(`Silent refresh middleware completed [${requestId}]`, {
        requestId,
        result: 'middleware_completed',
        timestamp: new Date().toISOString(),
      });

      next();
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;

      logger.error(
        {
          requestId,
          error: errorMessage,
          errorStack,
          errorType: error?.constructor?.name,
          clientIp,
          userAgent,
          requestPath,
          timestamp: new Date().toISOString(),
        },
        `Silent refresh failed [${requestId}]`,
      );

      // 清除无效的cookies
      logger.auth(`Clearing authentication cookies due to error [${requestId}]`, {
        requestId,
        action: 'clearing_auth_cookies',
        reason: 'silent_refresh_error',
        timestamp: new Date().toISOString(),
      });

      this.clearAuthCookies(res);
      next();
    }
  };

  /**
   * 获取token详细信息
   */
  private getTokenInfo(token: string): {
    isValid: boolean;
    expiresAt: string | null;
    timeUntilExpiry: number | null;
    userId?: string;
    username?: string;
  } {
    const decoded = this.verifyToken(token);
    if (!decoded?.exp) {
      return {
        isValid: false,
        expiresAt: null,
        timeUntilExpiry: null,
      };
    }

    const currentTime = Math.floor(Date.now() / 1000);
    const timeUntilExpiry = decoded.exp - currentTime;
    const expiresAt = new Date(decoded.exp * 1000).toISOString();

    return {
      isValid: timeUntilExpiry > 0,
      expiresAt,
      timeUntilExpiry,
      userId: decoded.sub || decoded.userId,
      username: decoded.username || decoded.name,
    };
  }

  /**
   * 检查token是否需要刷新
   */
  private shouldRefreshToken(token: string): boolean {
    const decoded = this.verifyToken(token);
    if (!decoded?.exp) {
      return false;
    }

    const currentTime = Math.floor(Date.now() / 1000);
    const timeUntilExpiry = decoded.exp - currentTime;

    // 如果token在5分钟内过期，则需要刷新
    return timeUntilExpiry < 300;
  }

  /**
   * 执行静默刷新
   */
  private async performSilentRefresh(
    req: Request,
    res: Response,
    refreshToken: string,
    requestId: string,
  ): Promise<void> {
    const clientIp = req.ip || req.connection.remoteAddress;
    const userAgent = req.headers['user-agent'];

    logger.auth(`Starting silent token refresh [${requestId}]`, {
      requestId,
      action: 'starting_token_refresh',
      refreshTokenLength: refreshToken.length,
      clientIp,
      userAgent,
      timestamp: new Date().toISOString(),
    });

    try {
      const refreshStartTime = Date.now();
      const result = await this.authClient.refreshToken({ refreshToken });
      const refreshDuration = Date.now() - refreshStartTime;

      logger.auth(`Token refresh API call completed [${requestId}]`, {
        requestId,
        refreshResult: {
          success: ResultHelper.isSuccess(result),
          hasData: !!result.data,
          error: !ResultHelper.isSuccess(result) ? result.message : null,
          duration: refreshDuration,
        },
        timestamp: new Date().toISOString(),
      });

      if (ResultHelper.isSuccess(result) && result.data) {
        // 设置新的认证cookies
        logger.auth(`Setting new authentication cookies [${requestId}]`, {
          requestId,
          action: 'setting_new_cookies',
          newTokenInfo: {
            tokenLength: result.data.token ? result.data.token.length : 0,
            refreshTokenLength: result.data.refreshToken ? result.data.refreshToken.length : 0,
            expiresIn: result.data.expiresIn,
          },
          timestamp: new Date().toISOString(),
        });

        this.setAuthCookies(res, result.data);

        logger.auth(`Token refreshed successfully [${requestId}]`, {
          requestId,
          newTokenExpiry: this.getTokenExpiry(result.data.token),
          refreshDuration,
          result: 'success',
          timestamp: new Date().toISOString(),
        });
      } else {
        // 刷新失败，清除cookies
        logger.warn(
          {
            requestId,
            action: 'clearing_cookies_on_failure',
            error: result.message || 'Invalid refresh response',
            refreshDuration,
            clientIp,
            userAgent,
            timestamp: new Date().toISOString(),
          },
          `Token refresh failed, clearing cookies [${requestId}]`,
        );

        this.clearAuthCookies(res);
        throw new Error('Invalid refresh response');
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;

      // 刷新异常，清除cookies
      logger.error(
        {
          requestId,
          action: 'clearing_cookies_on_exception',
          error: errorMessage,
          errorStack,
          errorType: error?.constructor?.name,
          clientIp,
          userAgent,
          timestamp: new Date().toISOString(),
        },
        `Token refresh exception, clearing cookies [${requestId}]`,
      );

      this.clearAuthCookies(res);
      throw error;
    }
  }

  /**
   * 设置认证相关的cookies
   */
  private setAuthCookies(res: Response, data: any): void {
    const { token, refreshToken, expiresIn } = data;

    if (token) {
      res.cookie('token', token, {
        httpOnly: true,
        secure: config.server.env === 'production',
        sameSite: 'lax',
        maxAge: (expiresIn || 3600) * 1000,
      });
    }

    if (refreshToken) {
      res.cookie('refreshToken', refreshToken, {
        httpOnly: true,
        secure: config.server.env === 'production',
        sameSite: 'lax',
        maxAge: 7 * 24 * 60 * 60 * 1000, // 7天
      });
    }
  }

  /**
   * 清除认证相关的cookies
   */
  private clearAuthCookies(res: Response): void {
    res.clearCookie('token');
    res.clearCookie('refreshToken');
  }

  /**
   * 获取token过期时间
   */
  private getTokenExpiry(token: string): string | null {
    const decoded = this.verifyToken(token);
    if (decoded?.exp) {
      return new Date(decoded.exp * 1000).toISOString();
    }
    return null;
  }

  private verifyToken(token: string): JwtPayload | null {
    try {
      const decoded = jwt.verify(token, this.jwtSecret);
      return typeof decoded === 'string' ? null : decoded;
    } catch (error) {
      return null;
    }
  }

  /**
   * 生成请求ID
   */
  private generateRequestId(): string {
    return `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
}

// 导出中间件实例
export const silentRefreshMiddleware = new SilentRefreshMiddleware().middleware;
