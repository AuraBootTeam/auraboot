/**
 * Authentication Configuration for Admin Interface
 *
 * Provides authentication utilities and configuration for admin document management.
 */

export interface AdminUser {
  id: string;
  username: string;
  roles: string[];
  permissions: string[];
  tenantId: string;
}

export interface AuthToken {
  token: string;
  refreshToken?: string;
  expiresAt: number;
  user: AdminUser;
}

// Mock admin user for development/testing
export const MOCK_ADMIN_USER: AdminUser = {
  id: 'admin_001',
  username: 'admin',
  roles: ['admin'],
  permissions: [
    'admin_document_upload',
    'admin_document_read',
    'admin_document_delete',
    'admin_task_read',
    'admin_task_manage',
  ],
  tenantId: 'default',
};

// Mock auth token for development/testing
export const MOCK_AUTH_TOKEN: AuthToken = {
  token: 'mock-jwt-token-for-development',
  refreshToken: 'mock-refresh-token',
  expiresAt: Date.now() + 24 * 60 * 60 * 1000, // 24 hours
  user: MOCK_ADMIN_USER,
};

/**
 * Get current authentication token
 */
export function getAuthToken(): AuthToken | null {
  if (typeof window === 'undefined') {
    return null; // Server-side rendering
  }

  try {
    const stored = localStorage.getItem('auth_token');
    if (stored) {
      const token = JSON.parse(stored) as AuthToken;

      // Check if token is expired
      if (token.expiresAt > Date.now()) {
        return token;
      } else {
        // Token expired, remove it
        localStorage.removeItem('auth_token');
      }
    }
  } catch (error) {
    console.error('Error reading auth token:', error);
    localStorage.removeItem('auth_token');
  }

  // Return mock token for development
  if (process.env.NODE_ENV === 'development') {
    return MOCK_AUTH_TOKEN;
  }

  return null;
}

/**
 * Set authentication token
 */
export function setAuthToken(token: AuthToken): void {
  if (typeof window === 'undefined') {
    return; // Server-side rendering
  }

  try {
    localStorage.setItem('auth_token', JSON.stringify(token));
  } catch (error) {
    console.error('Error storing auth token:', error);
  }
}

/**
 * Clear authentication token
 */
export function clearAuthToken(): void {
  if (typeof window === 'undefined') {
    return; // Server-side rendering
  }

  localStorage.removeItem('auth_token');
}

/**
 * Get current user
 */
export function getCurrentUser(): AdminUser | null {
  const token = getAuthToken();
  return token?.user || null;
}

/**
 * Check if user has specific permission
 */
export function hasPermission(permission: string): boolean {
  const user = getCurrentUser();
  return user?.permissions.includes(permission) || false;
}

/**
 * Check if user has specific role
 */
export function hasRole(role: string): boolean {
  const user = getCurrentUser();
  return user?.roles.includes(role) || false;
}

/**
 * Check if user is admin
 */
export function isAdmin(): boolean {
  return hasRole('admin');
}

/**
 * Get authorization headers for API requests
 */
export function getAuthHeaders(): Record<string, string> {
  const token = getAuthToken();
  const headers: Record<string, string> = {};

  if (token) {
    headers['Authorization'] = `Bearer ${token.token}`;
  }

  // Add user context headers for admin operations
  const user = getCurrentUser();
  if (user) {
    headers['X-Admin-User-Id'] = user.id;
    headers['X-Tenant-Id'] = user.tenantId;
  }

  return headers;
}

/**
 * Create authenticated fetch wrapper
 */
export function createAuthenticatedFetch() {
  return async (url: string, options: RequestInit = {}): Promise<Response> => {
    const authHeaders = getAuthHeaders();

    const mergedOptions: RequestInit = {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders,
        ...options.headers,
      },
    };

    return fetch(url, mergedOptions);
  };
}

/**
 * Login function (mock implementation for development)
 */
export async function login(username: string, password: string): Promise<AuthToken> {
  // Mock login for development
  if (process.env.NODE_ENV === 'development') {
    if (username === 'admin' && password === 'admin') {
      const token = MOCK_AUTH_TOKEN;
      setAuthToken(token);
      return token;
    } else {
      throw new Error('Invalid credentials');
    }
  }

  // Real login implementation would go here
  throw new Error('Login not implemented for production');
}

/**
 * Logout function
 */
export async function logout(): Promise<void> {
  clearAuthToken();

  // In production, you might want to call a logout endpoint
  // to invalidate the token on the server side
}

/**
 * Refresh token function
 */
export async function refreshToken(): Promise<AuthToken | null> {
  const currentToken = getAuthToken();

  if (!currentToken?.refreshToken) {
    return null;
  }

  // Mock refresh for development
  if (process.env.NODE_ENV === 'development') {
    const newToken: AuthToken = {
      ...currentToken,
      token: 'refreshed-mock-jwt-token',
      expiresAt: Date.now() + 24 * 60 * 60 * 1000, // 24 hours
    };
    setAuthToken(newToken);
    return newToken;
  }

  // Real refresh implementation would go here
  return null;
}

/**
 * Initialize authentication on app startup
 */
export function initializeAuth(): void {
  // Check if we have a valid token
  const token = getAuthToken();

  if (token && token.expiresAt <= Date.now()) {
    // Token expired, try to refresh
    refreshToken().catch(() => {
      // Refresh failed, clear token
      clearAuthToken();
    });
  }
}

// Auto-initialize in browser environment
if (typeof window !== 'undefined') {
  initializeAuth();
}
