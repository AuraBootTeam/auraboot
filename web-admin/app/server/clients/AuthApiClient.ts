import type { Result } from '~/utils/type';
import { ResultHelper } from '~/utils/type';

export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface RefreshTokenResponse {
  token: string;
  refreshToken: string;
  expiresIn?: number;
}

export class AuthApiClient {
  async refreshToken(request: RefreshTokenRequest): Promise<Result<RefreshTokenResponse>> {
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
      });

      if (!response.ok) {
        return {
          code: response.status.toString(),
          desc: response.statusText || 'Refresh token request failed',
          message: response.statusText || 'Refresh token request failed',
          success: false,
          data: null,
        };
      }

      const result = (await response.json()) as Result<RefreshTokenResponse>;
      return {
        ...result,
        message: result.message ?? result.desc,
        success: result.success ?? ResultHelper.isSuccess(result),
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Unknown error';
      return {
        code: 'network_error',
        desc: message,
        message,
        success: false,
        data: null,
      };
    }
  }
}
