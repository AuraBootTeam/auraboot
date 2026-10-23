import type { ApiClient } from './api-client.js';

export type AgentState = 'installed' | 'not_installed' | 'unknown';
export interface AgentDiscovery { state: AgentState; detail?: string }

/** Read the verified application assembly; never infer absence from HTTP errors. */
export async function discoverAgent(client: Pick<ApiClient, 'getBaseUrl' | 'getToken'>): Promise<AgentDiscovery> {
  try {
    const token = client.getToken();
    if (!token) return { state: 'unknown', detail: 'Authentication required' };
    const response = await fetch(new URL('/api/application/capabilities', client.getBaseUrl()), {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) return { state: 'unknown', detail: `Capability discovery HTTP ${response.status}` };
    const body = await response.json();
    if (body?.schemaVersion !== 1 || !['installed', 'not_installed'].includes(body?.agent?.state)) {
      return { state: 'unknown', detail: 'Unsupported application capabilities' };
    }
    return { state: body.agent.state };
  } catch {
    return { state: 'unknown', detail: 'Application capabilities unavailable' };
  }
}

export class AgentUnavailableError extends Error {
  constructor(discovery: AgentDiscovery) {
    super(discovery.state === 'not_installed'
      ? 'Agent module is not installed in this application.'
      : `Cannot verify agent availability: ${discovery.detail ?? 'unknown assembly'}. Retry after checking the session and server.`);
    this.name = 'AgentUnavailableError';
  }
}

/** No persistent cache: each action uses its current origin and session. */
export async function requireAgent(client: Pick<ApiClient, 'getBaseUrl' | 'getToken'>): Promise<void> {
  const discovery = await discoverAgent(client);
  if (discovery.state !== 'installed') throw new AgentUnavailableError(discovery);
}
