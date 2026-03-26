import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { homedir } from 'os';
import { join } from 'path';
import chalk from 'chalk';

const AURA_DIR = join(homedir(), '.aura');
const CREDENTIALS_FILE = join(AURA_DIR, 'credentials.json');
const CONFIG_FILE = join(AURA_DIR, 'config.json');

export interface Credentials {
  jwt: string;
  email: string;
  expiresAt?: string;
}

export interface AuraConfig {
  defaultEnv: string;
  environments: Record<string, { baseUrl: string }>;
  output: 'table' | 'json' | 'compact';
}

const DEFAULT_CONFIG: AuraConfig = {
  defaultEnv: 'local',
  environments: {
    local: { baseUrl: 'http://localhost:6443' },
  },
  output: 'table',
};

/**
 * Load config from ~/.aura/config.json (or defaults).
 */
export function loadConfig(): AuraConfig {
  if (!existsSync(CONFIG_FILE)) return DEFAULT_CONFIG;
  try {
    return { ...DEFAULT_CONFIG, ...JSON.parse(readFileSync(CONFIG_FILE, 'utf-8')) };
  } catch {
    return DEFAULT_CONFIG;
  }
}

/**
 * Resolve the base URL for the given environment.
 */
export function resolveBaseUrl(env?: string): string {
  const config = loadConfig();
  const envName = env || config.defaultEnv;
  const envConfig = config.environments[envName];
  if (!envConfig) {
    console.error(chalk.red(`Unknown environment: ${envName}`));
    console.error(chalk.dim(`Available: ${Object.keys(config.environments).join(', ')}`));
    process.exit(1);
  }
  return envConfig.baseUrl;
}

/**
 * Load cached credentials for the given environment.
 */
export function loadCredentials(env?: string): Credentials | null {
  if (!existsSync(CREDENTIALS_FILE)) return null;
  try {
    const all = JSON.parse(readFileSync(CREDENTIALS_FILE, 'utf-8'));
    const config = loadConfig();
    const envName = env || config.defaultEnv;
    return all[envName] || null;
  } catch {
    return null;
  }
}

/**
 * Save credentials for the given environment.
 */
export function saveCredentials(creds: Credentials, env?: string): void {
  mkdirSync(AURA_DIR, { recursive: true });

  let all: Record<string, Credentials> = {};
  if (existsSync(CREDENTIALS_FILE)) {
    try {
      all = JSON.parse(readFileSync(CREDENTIALS_FILE, 'utf-8'));
    } catch { /* ignore */ }
  }

  const config = loadConfig();
  const envName = env || config.defaultEnv;
  all[envName] = creds;

  writeFileSync(CREDENTIALS_FILE, JSON.stringify(all, null, 2), { mode: 0o600 });
}

/**
 * Save config to ~/.aura/config.json.
 */
export function saveConfig(config: AuraConfig): void {
  mkdirSync(AURA_DIR, { recursive: true });
  writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
}

/**
 * Check if the stored token for the given environment has expired.
 */
export function isTokenExpired(env?: string): boolean {
  const creds = loadCredentials(env);
  if (!creds?.expiresAt) return false; // no expiry info — assume valid
  return new Date(creds.expiresAt).getTime() <= Date.now();
}

/**
 * Resolve token from: CLI flag > env var > credentials file.
 * Returns null if no token available or if the stored token has expired.
 */
export function resolveToken(options: { token?: string; env?: string }): string | null {
  // 1. CLI flag (always trust explicit tokens)
  if (options.token) return options.token;

  // 2. Environment variable (always trust explicit tokens)
  if (process.env.AURA_TOKEN) return process.env.AURA_TOKEN;

  // 3. Credentials file — skip expired tokens
  const creds = loadCredentials(options.env);
  if (creds?.jwt) {
    if (creds.expiresAt && new Date(creds.expiresAt).getTime() <= Date.now()) {
      return null; // expired — will be handled by requireAuth()
    }
    return creds.jwt;
  }

  return null;
}

export interface UserSpace {
  tenantId: number;
  tenantName: string;
  tenantDisplayName: string;
  spaceType: 'platform' | 'business';
  roleCodes: string[];
  isDefault: boolean;
}

export interface LoginResult {
  jwt: string;
  tenantId: number | null;
  spaces: UserSpace[];
  selectedSpace?: UserSpace;
}

/**
 * Login to the platform, auto-select space, and cache the JWT.
 *
 * When the user belongs to multiple tenants, the login API returns a JWT
 * without tenantId. This function automatically:
 * 1. Fetches available spaces via /api/tenant-selection/my-spaces
 * 2. Selects the matching space (by tenantName if provided, or first business space)
 * 3. Gets a new JWT with the selected tenantId
 *
 * @param tenantName  optional tenant name to select (for multi-tenant users)
 */
export async function login(
  baseUrl: string,
  email: string,
  password: string,
  env?: string,
  tenantName?: string,
): Promise<LoginResult> {
  // Step 1: Authenticate
  const resp = await fetch(`${baseUrl}/api/auth/login`, {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });

  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`Login failed (${resp.status}): ${text}`);
  }

  const data = (await resp.json()) as any;
  let jwt = data.data?.jwt;
  const tenantId = data.data?.tenantId;
  if (!jwt) {
    throw new Error('Login response missing JWT');
  }

  // Step 2: Fetch available spaces
  let spaces: UserSpace[] = [];
  try {
    const spacesResp = await fetch(`${baseUrl}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${jwt}` },
    });
    if (spacesResp.ok) {
      const spacesData = (await spacesResp.json()) as any;
      spaces = spacesData.data || [];
    }
  } catch {
    // Non-critical — proceed with current JWT
  }

  // Step 3: Auto-select space if tenantId is null or user specified --tenant
  let selectedSpace: UserSpace | undefined;

  if (tenantName) {
    // User explicitly selected a tenant by name
    const match = spaces.find(
      (s) => s.tenantName.toLowerCase() === tenantName.toLowerCase()
        || s.tenantDisplayName.toLowerCase() === tenantName.toLowerCase(),
    );
    if (!match) {
      const available = spaces.map((s) => s.tenantDisplayName || s.tenantName).join(', ');
      throw new Error(`Tenant "${tenantName}" not found. Available: ${available || 'none'}`);
    }
    selectedSpace = match;
  } else if (!tenantId && spaces.length > 0) {
    // No tenantId and no explicit choice — auto-select first business space
    selectedSpace = spaces.find((s) => s.spaceType === 'business') || spaces[0];
  }

  // Step 4: Select space to get JWT with tenantId
  if (selectedSpace) {
    try {
      const selectResp = await fetch(`${baseUrl}/api/tenant-selection/process`, {
        method: 'post',
        headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'select', tenantId: selectedSpace.tenantId }),
      });
      if (selectResp.ok) {
        const selectData = (await selectResp.json()) as any;
        if (selectData.data?.jwt) {
          jwt = selectData.data.jwt;
        }
      }
    } catch {
      // Non-critical — use original JWT
    }
  }

  saveCredentials({ jwt, email, expiresAt: new Date(Date.now() + 24 * 3600 * 1000).toISOString() }, env);

  return { jwt, tenantId: selectedSpace?.tenantId ?? tenantId, spaces, selectedSpace };
}

/**
 * Auto-login using env vars or stored credentials, returning a valid JWT.
 * Throws if no credentials are available.
 */
export async function autoLogin(baseUrl: string, env?: string): Promise<string> {
  const email = process.env.AURA_USER;
  const password = process.env.AURA_PASSWORD;
  if (email && password) {
    const result = await login(baseUrl, email, password, env);
    return result.jwt;
  }
  throw new Error('No credentials available. Run: aura login');
}
