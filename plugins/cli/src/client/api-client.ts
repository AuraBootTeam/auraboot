import chalk from 'chalk';
import { resolveToken, resolveBaseUrl, autoLogin, isTokenExpired, loadCredentials } from './auth.js';

export interface ApiClientOptions {
  token?: string;
  env?: string;
}

export interface ApiResponse<T = any> {
  ok: boolean;
  status: number;
  data: T;
  message?: string;
}

// Semantic exit codes
export const EXIT = {
  SUCCESS: 0,
  FAILURE: 1,
  CANCELLED: 2,
  FORBIDDEN: 3,
  NOT_FOUND: 4,
  AUTH_REQUIRED: 5,
} as const;

/**
 * Unified API client for Aura CLI.
 * Handles JWT injection, auto-renewal on 401, and structured error responses.
 */
export class ApiClient {
  private baseUrl: string;
  private token: string | null;
  private env?: string;

  constructor(options: ApiClientOptions = {}) {
    this.env = options.env;
    this.baseUrl = resolveBaseUrl(options.env);
    this.token = resolveToken(options);
  }

  /**
   * Ensure we have a valid token.
   * Detects expired tokens proactively and attempts auto-renewal before failing.
   */
  async requireAuth(): Promise<void> {
    // Proactive expiration check — detect before making any request
    if (!this.token && isTokenExpired(this.env)) {
      const creds = loadCredentials(this.env);
      console.error(chalk.yellow(`Session expired${creds?.email ? ` (${creds.email})` : ''}.`));
      // Try auto-renewal with env vars
      try {
        this.token = await autoLogin(this.baseUrl, this.env);
        console.error(chalk.green('✓'), 'Session renewed automatically.');
        return;
      } catch {
        console.error(chalk.dim('Run: aura login'));
        process.exit(EXIT.AUTH_REQUIRED);
      }
    }

    if (!this.token) {
      console.error(chalk.red('Not authenticated. Run: aura login'));
      process.exit(EXIT.AUTH_REQUIRED);
    }
  }

  /**
   * Make an authenticated GET request.
   */
  async get<T = any>(path: string, params?: Record<string, string>): Promise<ApiResponse<T>> {
    const url = new URL(path, this.baseUrl);
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        url.searchParams.set(k, v);
      }
    }
    return this.request<T>('get', url.toString());
  }

  /**
   * Make an authenticated POST request.
   */
  async post<T = any>(path: string, body?: any): Promise<ApiResponse<T>> {
    const url = new URL(path, this.baseUrl);
    return this.request<T>('post', url.toString(), body);
  }

  /**
   * Make an authenticated PUT request.
   */
  async put<T = any>(path: string, body?: any): Promise<ApiResponse<T>> {
    const url = new URL(path, this.baseUrl);
    return this.request<T>('put', url.toString(), body);
  }

  /**
   * Make an authenticated DELETE request.
   */
  async delete<T = any>(path: string): Promise<ApiResponse<T>> {
    const url = new URL(path, this.baseUrl);
    return this.request<T>('delete', url.toString());
  }

  /**
   * Get the base URL (for SSE connections).
   */
  getBaseUrl(): string {
    return this.baseUrl;
  }

  /**
   * Get the current token (for SSE connections).
   */
  getToken(): string | null {
    return this.token;
  }

  private async request<T>(method: string, url: string, body?: any): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`;
    }

    let resp: Response;
    try {
      resp = await fetch(url, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (err) {
      const msg = (err as Error).message;
      if (msg.includes('ECONNREFUSED') || msg.includes('fetch failed')) {
        const host = new URL(url).origin;
        return { ok: false, status: 0, data: null as any, message: `Cannot connect to ${host}. Is the server running?` };
      }
      return { ok: false, status: 0, data: null as any, message: `Network error: ${msg}` };
    }

    // Handle 401 — try auto-renewal
    if (resp.status === 401 && this.token) {
      try {
        this.token = await autoLogin(this.baseUrl, this.env);
        // Retry with new token
        headers['Authorization'] = `Bearer ${this.token}`;
        const retry = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
        return this.parseResponse<T>(retry);
      } catch {
        console.error(chalk.yellow('Session expired. Please re-authenticate.'));
        console.error(chalk.dim('Run: aura login'));
        return { ok: false, status: 401, data: null as any, message: 'Session expired' };
      }
    }

    return this.parseResponse<T>(resp);
  }

  private async parseResponse<T>(resp: Response): Promise<ApiResponse<T>> {
    if (resp.status === 403) {
      const data = await resp.json().catch(() => ({})) as any;
      const msg = data.message || data.error || 'Access denied';
      // Check if this is an enterprise feature restriction
      if (msg.includes('Professional') || msg.includes('license') || msg.includes('Enterprise')) {
        console.error(chalk.yellow(`\n  This feature requires a Professional license.`));
        console.error(chalk.dim(`  Learn more: https://auraboot.com/pricing\n`));
      } else {
        console.error(chalk.red(`Permission denied: ${msg}`));
      }
      process.exit(EXIT.FORBIDDEN);
    }

    if (resp.status === 404) {
      const data = await resp.json().catch(() => ({})) as any;
      console.error(chalk.red(`Not found: ${data.message || resp.url}`));
      process.exit(EXIT.NOT_FOUND);
    }

    if (!resp.ok) {
      const text = await resp.text();
      return { ok: false, status: resp.status, data: null as any, message: text };
    }

    const json = await resp.json() as any;
    // AuraBoot API wraps responses in { code, data, message }
    // Note: code can be string "0" or number 0/200
    if (json.code !== undefined) {
      const code = Number(json.code);
      const ok = code === 200 || code === 0;
      // For business errors (code != 0/200), extract a readable message
      const message = !ok && json.context?.detail
        ? json.context.detail
        : json.message;
      return { ok, status: resp.status, data: json.data as T, message };
    }
    return { ok: true, status: resp.status, data: json as T };
  }
}
