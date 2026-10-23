export type AgentInstallationState = 'installed' | 'not_installed' | 'unknown';
export interface ApplicationDiscovery { agent: AgentInstallationState }

/** Installation is independent of permissions, commercial entitlement and model readiness. */
export async function discoverApplication(signal?: AbortSignal): Promise<ApplicationDiscovery> {
  try {
    const response = await fetch('/api/application/capabilities', {
      credentials: 'include', cache: 'no-store',
      signal: signal ?? AbortSignal.timeout(5000),
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return { agent: 'unknown' };
    const body = await response.json();
    if (body?.schemaVersion !== 1 || !['installed', 'not_installed'].includes(body?.agent?.state)) {
      return { agent: 'unknown' };
    }
    return { agent: body.agent.state };
  } catch {
    return { agent: 'unknown' };
  }
}

export async function requireAgentApplication(): Promise<void> {
  const { agent } = await discoverApplication();
  if (agent !== 'installed') throw new Error(agent === 'not_installed'
    ? '$i18n:application.agent.not_installed' : '$i18n:application.agent.unknown');
}

/** Preserve the original request, streaming and cancellation contracts after preflight. */
export async function agentFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  await requireAgentApplication();
  if (init?.signal?.aborted) throw init.signal.reason;
  return fetch(input, init);
}
