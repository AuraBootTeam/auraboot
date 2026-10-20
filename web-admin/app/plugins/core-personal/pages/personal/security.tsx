import { useEffect, useState } from 'react';
import { fetchActiveSessions, revokeActiveSession, revokeAllActiveSessions, type ActiveSession } from '~/shared/services/activeSessionService';
import { useI18n } from '~/contexts/I18nContext';
import { useToast } from '~/contexts/ToastContext';

function ActiveSessions() {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToast();
  const [sessions, setSessions] = useState<ActiveSession[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const [loadError, setLoadError] = useState(false);

  const fetchSessions = async () => {
    setIsLoading(true);
    setLoadError(false);
    try {
      setSessions(await fetchActiveSessions());
    } catch {
      setLoadError(true);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchSessions();
  }, []);

  const handleRevoke = async (sessionPid: string) => {
    try {
      await revokeActiveSession(sessionPid);
      showSuccessToast(t('session_security.revoked'));
      setSessions((prev) => prev.filter((s) => s.pid !== sessionPid));
    } catch (err) {
      showErrorToast(t('session_security.revoke_failed'));
    }
  };

  const handleRevokeAll = async () => {
    try {
      await revokeAllActiveSessions();
      showSuccessToast(t('session_security.all_revoked'));
      setTimeout(() => {
        window.location.href = '/login';
      }, 1500);
    } catch (err) {
      showErrorToast(t('session_security.revoke_all_failed'));
    }
  };

  if (isLoading) {
    return <div className="text-gray-500">{t('session_security.loading')}</div>;
  }

  if (loadError) return <div role="alert">
    <p>{t('session_security.load_failed')}</p>
    <button type="button" onClick={() => void fetchSessions()}>{t('session_security.retry')}</button>
  </div>;

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-medium text-gray-800">{t('session_security.active')}</h3>
        {sessions.length > 1 && (
          <button
            onClick={handleRevokeAll}
            className="text-sm font-medium text-red-600 hover:text-red-700"
            data-testid="revoke-all-sessions-btn"
          >
            {t('session_security.logout_all')}
          </button>
        )}
      </div>

      {sessions.length === 0 ? (
        <p className="text-gray-500">{t('session_security.empty')}</p>
      ) : (
        <div className="space-y-3">
          {sessions.map((session) => (
            <div
              key={session.pid}
              className="flex items-center justify-between rounded-lg border bg-gray-50 p-4"
            >
              <div>
                <p className="font-medium text-gray-900">{session.deviceInfo}</p>
                <p className="text-sm text-gray-500">
                  {t('session_security.ip')}: {session.ipAddress} &middot; {t('session_security.last_active')}:{' '}
                  {new Date(session.lastActiveAt).toLocaleString()}
                </p>
                <p className="text-xs text-gray-400">
                  {t('session_security.created')}: {new Date(session.createdAt).toLocaleString()}
                </p>
              </div>
              <button
                onClick={() => handleRevoke(session.pid)}
                className="rounded border border-red-200 px-3 py-1 text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-700"
                data-testid={`revoke-session-${session.pid}`}
              >
                {t('session_security.revoke')}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function SecuritySettings() {
  const { t } = useI18n();
  return (
    <div className="p-6">
      <div className="rounded-lg bg-white shadow-md">
        <div className="border-b border-gray-200 px-6 py-4">
          <h1 className="text-2xl font-semibold text-gray-900">{t('session_security.title')}</h1>
        </div>

        <div className="p-6">
          <ActiveSessions />
        </div>
      </div>
    </div>
  );
}
