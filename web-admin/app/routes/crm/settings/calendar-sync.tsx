/**
 * CRM Calendar Sync Settings
 *
 * Manage calendar integrations for CRM activities:
 * - Provider cards: Google Calendar, Microsoft Outlook
 * - Each card shows connection status, last sync, sync direction
 * - Connect via OAuth redirect / Disconnect
 * - Sync direction toggle: Both / Read / Write
 */

import { useState, useEffect, useCallback } from 'react';
import { ArrowPathIcon, CheckCircleIcon, XCircleIcon } from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

type SyncDirection = 'both' | 'read' | 'write';
type CalendarProvider = 'google' | 'microsoft';

interface CalendarProviderStatus {
  provider: CalendarProvider;
  connected: boolean;
  calendarName?: string;
  accountEmail?: string;
  lastSyncAt?: string;
  syncDirection: SyncDirection;
}

const PROVIDER_META: Record<
  CalendarProvider,
  { name: string; description: string; icon: React.ReactNode; accentColor: string }
> = {
  google: {
    name: 'Google Calendar',
    description: 'Sync CRM activities with your Google Calendar.',
    icon: (
      <svg viewBox="0 0 48 48" className="h-10 w-10">
        <path fill="#EA4335" d="M34 7H14l-2 2v26l2 2h20l2-2V9z" />
        <path
          fill="#fff"
          d="M34 7H14l-2 2v26l2 2h20l2-2V9zM24 35.2c-6.2 0-11.2-5-11.2-11.2S17.8 12.8 24 12.8 35.2 17.8 35.2 24 30.2 35.2 24 35.2z"
        />
        <text x="24" y="29" textAnchor="middle" fontSize="14" fill="#4285F4" fontWeight="bold">
          31
        </text>
      </svg>
    ),
    accentColor: 'border-blue-200 bg-blue-50 dark:bg-blue-900/10',
  },
  microsoft: {
    name: 'Microsoft Outlook',
    description: 'Sync CRM activities with your Outlook calendar.',
    icon: (
      <svg viewBox="0 0 48 48" className="h-10 w-10">
        <rect x="4" y="4" width="20" height="20" rx="2" fill="#f25022" />
        <rect x="26" y="4" width="18" height="20" rx="2" fill="#7fba00" />
        <rect x="4" y="26" width="20" height="18" rx="2" fill="#00a4ef" />
        <rect x="26" y="26" width="18" height="18" rx="2" fill="#ffb900" />
      </svg>
    ),
    accentColor: 'border-indigo-200 bg-indigo-50 dark:bg-indigo-900/10',
  },
};

const SYNC_DIRECTIONS: { value: SyncDirection; label: string; description: string }[] = [
  { value: 'both', label: 'Both', description: 'Two-way sync (CRM ↔ Calendar)' },
  { value: 'read', label: 'Read Only', description: 'Import calendar events to CRM' },
  { value: 'write', label: 'Write Only', description: 'Push CRM activities to calendar' },
];

// ─── API helpers ──────────────────────────────────────────────────────────────

async function apiFetch<T = unknown>(path: string, opts?: RequestInit): Promise<T> {
  const jwt = localStorage.getItem('jwt');
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
    },
    ...opts,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== undefined && json.code != 0 && json.code != 200) {
    throw new Error(json.message ?? 'API error');
  }
  return json.data as T;
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function CalendarSyncPage() {
  const { showToast } = useToastContext();
  const [providers, setProviders] = useState<CalendarProviderStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncLoading, setSyncLoading] = useState<Record<CalendarProvider, boolean>>({
    google: false,
    microsoft: false,
  });
  const [connectLoading, setConnectLoading] = useState<Record<CalendarProvider, boolean>>({
    google: false,
    microsoft: false,
  });
  const [disconnectLoading, setDisconnectLoading] = useState<Record<CalendarProvider, boolean>>({
    google: false,
    microsoft: false,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiFetch<CalendarProviderStatus[]>('/api/crm/calendar/providers');
      // Ensure both providers are present
      const result: CalendarProviderStatus[] = (['google', 'microsoft'] as CalendarProvider[]).map(
        (p) =>
          data?.find((d) => d.provider === p) ?? {
            provider: p,
            connected: false,
            syncDirection: 'both',
          },
      );
      setProviders(result);
    } catch {
      // Show default disconnected state
      setProviders([
        { provider: 'google', connected: false, syncDirection: 'both' },
        { provider: 'microsoft', connected: false, syncDirection: 'both' },
      ]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleConnect = async (provider: CalendarProvider) => {
    setConnectLoading((prev) => ({ ...prev, [provider]: true }));
    try {
      const providerKey = provider.toLowerCase();
      const data = await apiFetch<{ authUrl: string }>(`/api/crm/calendar/connect/${providerKey}`);
      if (data?.authUrl) {
        window.location.href = data.authUrl;
      } else {
        throw new Error('No auth URL returned');
      }
    } catch (e: unknown) {
      showToast(
        (e instanceof Error ? e.message : null) ?? `Failed to connect ${provider}`,
        'error',
      );
      setConnectLoading((prev) => ({ ...prev, [provider]: false }));
    }
  };

  const handleDisconnect = async (provider: CalendarProvider) => {
    const meta = PROVIDER_META[provider];
    if (!confirm(`Disconnect ${meta.name}? Calendar sync will stop immediately.`)) return;
    setDisconnectLoading((prev) => ({ ...prev, [provider]: true }));
    try {
      await apiFetch('/api/crm/calendar/disconnect', {
        method: 'post',
        body: JSON.stringify({ provider }),
      });
      setProviders((prev) =>
        prev.map((p) =>
          p.provider === provider ? { provider, connected: false, syncDirection: 'both' } : p,
        ),
      );
      showToast(`${meta.name} disconnected`, 'success');
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? `Failed to disconnect`, 'error');
    } finally {
      setDisconnectLoading((prev) => ({ ...prev, [provider]: false }));
    }
  };

  const handleSyncNow = async (provider: CalendarProvider) => {
    setSyncLoading((prev) => ({ ...prev, [provider]: true }));
    try {
      await apiFetch(`/api/crm/calendar/sync-now`, {
        method: 'post',
        body: JSON.stringify({ provider }),
      });
      showToast('Sync triggered successfully', 'success');
      load();
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Sync failed', 'error');
    } finally {
      setSyncLoading((prev) => ({ ...prev, [provider]: false }));
    }
  };

  const handleDirectionChange = async (provider: CalendarProvider, direction: SyncDirection) => {
    try {
      await apiFetch(`/api/crm/calendar/sync-direction`, {
        method: 'put',
        body: JSON.stringify({ provider, direction }),
      });
      setProviders((prev) =>
        prev.map((p) => (p.provider === provider ? { ...p, syncDirection: direction } : p)),
      );
      showToast('Sync direction updated', 'success');
    } catch (e: unknown) {
      showToast(
        (e instanceof Error ? e.message : null) ?? 'Failed to update sync direction',
        'error',
      );
    }
  };

  if (loading) {
    return <div className="mx-auto max-w-3xl p-6 text-center text-gray-400">Loading...</div>;
  }

  return (
    <div className="mx-auto max-w-3xl p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Calendar Sync</h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          Connect external calendars to sync CRM activities and meetings automatically.
        </p>
      </div>

      {/* Provider Cards */}
      <div className="space-y-4" data-testid="calendar-providers">
        {providers.map((providerStatus) => {
          const meta = PROVIDER_META[providerStatus.provider];
          const isConnecting = connectLoading[providerStatus.provider];
          const isDisconnecting = disconnectLoading[providerStatus.provider];
          const isSyncing = syncLoading[providerStatus.provider];

          return (
            <div
              key={providerStatus.provider}
              className={`overflow-hidden rounded-xl border-2 bg-white p-6 shadow-sm dark:bg-gray-800 ${meta.accentColor}`}
              data-testid={`calendar-provider-${providerStatus.provider.toLowerCase()}`}
            >
              <div className="flex items-start gap-4">
                {/* Icon */}
                <div className="flex-shrink-0">{meta.icon}</div>

                {/* Main content */}
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between">
                    <div>
                      <h3 className="text-base font-semibold text-gray-900 dark:text-white">
                        {meta.name}
                      </h3>
                      <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                        {meta.description}
                      </p>
                    </div>

                    {/* Connection status badge */}
                    <div
                      className={`ml-4 flex flex-shrink-0 items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${
                        providerStatus.connected
                          ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                          : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                      }`}
                    >
                      {providerStatus.connected ? (
                        <CheckCircleIcon className="h-3.5 w-3.5" />
                      ) : (
                        <XCircleIcon className="h-3.5 w-3.5" />
                      )}
                      {providerStatus.connected ? 'Connected' : 'Disconnected'}
                    </div>
                  </div>

                  {/* Connected details */}
                  {providerStatus.connected && (
                    <div className="mt-3 space-y-3">
                      <div className="flex flex-wrap gap-4 text-sm">
                        {providerStatus.accountEmail && (
                          <div className="flex items-center gap-1.5 text-gray-600 dark:text-gray-300">
                            <span className="font-medium text-gray-500 dark:text-gray-400">
                              Account:
                            </span>
                            {providerStatus.accountEmail}
                          </div>
                        )}
                        {providerStatus.calendarName && (
                          <div className="flex items-center gap-1.5 text-gray-600 dark:text-gray-300">
                            <span className="font-medium text-gray-500 dark:text-gray-400">
                              Calendar:
                            </span>
                            {providerStatus.calendarName}
                          </div>
                        )}
                        {providerStatus.lastSyncAt && (
                          <div className="flex items-center gap-1.5 text-gray-600 dark:text-gray-300">
                            <span className="font-medium text-gray-500 dark:text-gray-400">
                              Last sync:
                            </span>
                            {new Date(providerStatus.lastSyncAt).toLocaleString()}
                          </div>
                        )}
                      </div>

                      {/* Sync Direction */}
                      <div>
                        <label className="mb-2 block text-xs font-medium text-gray-600 dark:text-gray-400">
                          Sync Direction
                        </label>
                        <div className="flex gap-2">
                          {SYNC_DIRECTIONS.map((dir) => (
                            <button
                              key={dir.value}
                              onClick={() =>
                                handleDirectionChange(providerStatus.provider, dir.value)
                              }
                              title={dir.description}
                              className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                                providerStatus.syncDirection === dir.value
                                  ? 'border-blue-600 bg-blue-600 text-white'
                                  : 'border-gray-300 bg-white text-gray-600 hover:border-blue-400 hover:text-blue-600 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300'
                              }`}
                              data-testid={`sync-direction-${dir.value.toLowerCase()}`}
                            >
                              {dir.label}
                            </button>
                          ))}
                        </div>
                        <p className="mt-1 text-xs text-gray-400">
                          {
                            SYNC_DIRECTIONS.find((d) => d.value === providerStatus.syncDirection)
                              ?.description
                          }
                        </p>
                      </div>
                    </div>
                  )}

                  {/* Action buttons */}
                  <div className="mt-4 flex items-center gap-3">
                    {providerStatus.connected ? (
                      <>
                        <button
                          onClick={() => handleSyncNow(providerStatus.provider)}
                          disabled={isSyncing}
                          className="flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
                          data-testid={`sync-now-${providerStatus.provider.toLowerCase()}`}
                        >
                          <ArrowPathIcon className={`h-4 w-4 ${isSyncing ? 'animate-spin' : ''}`} />
                          {isSyncing ? 'Syncing...' : 'Sync Now'}
                        </button>
                        <button
                          onClick={() => handleDisconnect(providerStatus.provider)}
                          disabled={isDisconnecting}
                          className="rounded-lg border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50 dark:border-red-700 dark:bg-transparent dark:hover:bg-red-900/20"
                          data-testid={`disconnect-${providerStatus.provider.toLowerCase()}`}
                        >
                          {isDisconnecting ? 'Disconnecting...' : 'Disconnect'}
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => handleConnect(providerStatus.provider)}
                        disabled={isConnecting}
                        className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
                        data-testid={`connect-${providerStatus.provider.toLowerCase()}`}
                      >
                        {isConnecting ? (
                          <>
                            <ArrowPathIcon className="h-4 w-4 animate-spin" />
                            Connecting...
                          </>
                        ) : (
                          `Connect ${meta.name}`
                        )}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Info box */}
      <div className="mt-6 rounded-xl border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800/50">
        <h4 className="mb-2 text-sm font-medium text-gray-700 dark:text-gray-300">
          How Calendar Sync Works
        </h4>
        <ul className="space-y-1 text-sm text-gray-500 dark:text-gray-400">
          <li>• CRM activities (meetings, calls, tasks) are synced as calendar events</li>
          <li>• Sync runs automatically every 15 minutes when connected</li>
          <li>• "Both" mode: changes in CRM update Calendar, and vice versa</li>
          <li>• Events are tagged with "[CRM]" prefix to identify them in your calendar</li>
        </ul>
      </div>
    </div>
  );
}
