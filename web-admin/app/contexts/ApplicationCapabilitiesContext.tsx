import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useAuth } from './AuthContext';
import { useI18n } from './I18nContext';
import { discoverApplication, type AgentInstallationState } from '~/shared/services/application-capabilities';
import { requiresAgentPath } from '~/shared/services/agent-surfaces';

interface CapabilitySnapshot { agent: AgentInstallationState; loading: boolean; refresh: () => void }
const Context = createContext<CapabilitySnapshot>({ agent: 'unknown', loading: true, refresh: () => {} });
export const useApplicationCapabilities = () => useContext(Context);

export function ApplicationCapabilitiesProvider({ children }: { children: ReactNode }) {
  const { user, isAuthenticated } = useAuth();
  const session = `${user?.id ?? ''}:${user?.tenantId ?? ''}:${user?.jwt ?? ''}:${isAuthenticated}`;
  const [revision, setRevision] = useState(0);
  const [snapshot, setSnapshot] = useState({ session: '', agent: 'unknown' as AgentInstallationState, loading: true });
  const refresh = useCallback(() => setRevision(value => value + 1), []);
  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    setSnapshot(current => ({ session, agent: current.session === session ? current.agent : 'unknown', loading: true }));
    if (!isAuthenticated) {
      setSnapshot({ session, agent: 'unknown', loading: false });
      return () => controller.abort();
    }
    const timeout = setTimeout(() => controller.abort(), 5000);
    void discoverApplication(controller.signal).then(result => {
      if (cancelled) return;
      if (!controller.signal.aborted) setSnapshot({ session, agent: result.agent, loading: false });
      else setSnapshot(current => current.session === session ? { session, agent: 'unknown', loading: false } : current);
    }).finally(() => clearTimeout(timeout));
    return () => { cancelled = true; clearTimeout(timeout); controller.abort(); };
  }, [session, isAuthenticated, revision]);
  useEffect(() => {
    window.addEventListener('online', refresh);
    window.addEventListener('focus', refresh);
    return () => { window.removeEventListener('online', refresh); window.removeEventListener('focus', refresh); };
  }, [refresh]);
  const current = snapshot.session === session ? snapshot : { agent: 'unknown' as const, loading: true };
  return <Context.Provider value={{ ...current, refresh }}>{children}</Context.Provider>;
}

/** A deep link cannot mount an AI screen while installation is absent or unverified. */
export function AgentRouteBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const { agent, loading, refresh } = useApplicationCapabilities();
  const { t } = useI18n();
  if (!requiresAgentPath(pathname)) return children;
  if (agent === 'installed') return <div className="relative flex min-h-0 flex-1 flex-col" aria-busy={loading}>
    <div inert={loading || undefined}>{children}</div>
    {loading && <div role="status" data-testid="agent-rediscovery" className="absolute inset-0 z-50 flex items-start justify-center bg-white/70 pt-8 dark:bg-gray-900/70">
      <p className="rounded-lg border bg-white px-4 py-3 text-sm dark:bg-gray-800">{t('application.agent.loading')}</p>
    </div>}
  </div>;
  const key = loading ? 'loading' : agent;
  return <section role="status" data-testid="agent-availability" data-state={key} className="mx-auto my-12 max-w-lg rounded-lg border border-gray-200 bg-white p-8 text-center dark:border-gray-700 dark:bg-gray-800">
    <h1 className="mb-3 text-xl font-semibold">{t(`application.agent.${key}`)}</h1>
    <p className="mb-6 text-sm text-gray-500">{t('application.agent.guidance')}</p>
    {!loading && <button type="button" onClick={refresh} className="mr-4 rounded border px-4 py-2">{t('application.agent.retry')}</button>}
    <Link to="/home" className="underline">{t('application.agent.home')}</Link>
  </section>;
}
