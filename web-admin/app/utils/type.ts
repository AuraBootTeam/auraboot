export { ErrorCodes } from '~/shared/services/http-client/types';
import { ErrorCodes } from '~/shared/services/http-client/types';

/**
 * Unified API result type.
 * Compatible with both http-client Result and legacy callers.
 */
export type Result<T> = {
  code: string;
  desc?: string;
  message?: string;
  success?: boolean;
  data: T | null;
};

// Legacy alias — prefer importing ErrorCodes directly
export const ERROR_CODES = {
  NO_ERROR: ErrorCodes.SUCCESS,
  VALIDATION_ERROR: '10000',
  UNAUTHORIZED: ErrorCodes.UNAUTHORIZED,
  FORBIDDEN: ErrorCodes.FORBIDDEN,
  TOKEN_EXPIRED: '400',
  BUSINESS_ERROR: '40000',
  INTERNAL_ERROR: ErrorCodes.SUCCESS,
  SERVICE_UNAVAILABLE: '1',
  NETWORK_ERROR: ErrorCodes.NETWORK_ERROR,
} as const;

/**
 * Utility class for checking and creating API response results.
 * Provides type-safe response code checking without magic strings.
 */
export class ResultHelper {
  static isSuccess(result: { code: string | number }): boolean {
    return String(result.code) === ErrorCodes.SUCCESS;
  }

  static isValidationError(result: { code: string }): boolean {
    return result.code === ERROR_CODES.VALIDATION_ERROR;
  }

  static isAuthError(result: { code: string }): boolean {
    return [ERROR_CODES.UNAUTHORIZED, ERROR_CODES.FORBIDDEN, ERROR_CODES.TOKEN_EXPIRED].includes(
      result.code as any,
    );
  }

  static isBusinessError(result: { code: string }): boolean {
    return result.code === ERROR_CODES.BUSINESS_ERROR;
  }

  static isSystemError(result: { code: string }): boolean {
    return [
      ERROR_CODES.SERVICE_UNAVAILABLE,
      ErrorCodes.NETWORK_ERROR,
      ErrorCodes.TIMEOUT_ERROR,
    ].includes(result.code as any);
  }

  static error<T>(code: string, desc?: string): Result<T> {
    return { code, desc, message: desc, success: false, data: null };
  }

  static success<T>(data: T, desc?: string): Result<T> {
    return { code: ErrorCodes.SUCCESS, desc, message: desc, success: true, data };
  }

  static handleError<T>(
    result: Result<T>,
    options?: {
      onValidationError?: (result: Result<T>) => void;
      onAuthError?: (result: Result<T>) => void;
      onBusinessError?: (result: Result<T>) => void;
      onSystemError?: (result: Result<T>) => void;
    },
  ) {
    const { onValidationError, onAuthError, onBusinessError, onSystemError } = options || {};
    if (this.isValidationError(result)) onValidationError?.(result);
    else if (this.isAuthError(result)) onAuthError?.(result);
    else if (this.isBusinessError(result)) onBusinessError?.(result);
    else if (this.isSystemError(result)) onSystemError?.(result);
  }
}

export type User = {
  id: string;
  pid?: string;
  tenantId?: number | string;
  tenantName?: string;
  email: string;
  jwt?: string;
  username?: string;
  nickname?: string;
  avatar?: string;
  name: string;
  exp?: number; // JWT过期时间
  iat?: number; // JWT签发时间
};

export interface Preferences {
  timezone: string;
  dateFormat: string;
  datetimeFormat: string;
  timeFormat: string;
}

export interface Permission {
  id: number;
  code: string;
  name: string;
  type: string;
  module?: string;
}

export interface Role {
  id: number;
  code: string;
  name: string;
  type: string;
}

export interface UserPermissions {
  roles: Role[];
  permissions?: Permission[]; // 旧格式：对象数组
  permissionCodes?: string[]; // 新格式：字符串数组（后端当前返回格式）
}
