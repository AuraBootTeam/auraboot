import { useState, useEffect } from 'react';
import { get, del } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToast } from '~/contexts/ToastContext';
import PasswordChangeForm from '~/ui/security/PasswordChangeForm';

interface SessionInfo {
  pid: string;
  deviceInfo: string;
  ipAddress: string;
  createdAt: string;
  lastActiveAt: string;
}

function ActiveSessions() {
  const { showSuccessToast, showErrorToast } = useToast();
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const fetchSessions = async () => {
    try {
      const result = await get<{ code: string; data: SessionInfo[] }>('/api/user/sessions');
      if (result && ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setSessions(result.data);
      }
    } catch (err) {
      console.error('Failed to fetch sessions:', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchSessions();
  }, []);

  const handleRevoke = async (sessionPid: string) => {
    try {
      await del(`/api/user/sessions/${sessionPid}`);
      showSuccessToast('Session revoked');
      setSessions((prev) => prev.filter((s) => s.pid !== sessionPid));
    } catch (err) {
      showErrorToast('Failed to revoke session');
    }
  };

  const handleRevokeAll = async () => {
    try {
      await del('/api/user/sessions');
      showSuccessToast('All sessions revoked. You will be logged out.');
      setTimeout(() => {
        window.location.href = '/login';
      }, 1500);
    } catch (err) {
      showErrorToast('Failed to revoke sessions');
    }
  };

  if (isLoading) {
    return <div className="text-gray-500">Loading sessions...</div>;
  }

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-medium text-gray-800">Active Sessions</h3>
        {sessions.length > 1 && (
          <button
            onClick={handleRevokeAll}
            className="text-sm font-medium text-red-600 hover:text-red-700"
            data-testid="revoke-all-sessions-btn"
          >
            Logout All Devices
          </button>
        )}
      </div>

      {sessions.length === 0 ? (
        <p className="text-gray-500">No active sessions found.</p>
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
                  IP: {session.ipAddress} &middot; Last active:{' '}
                  {new Date(session.lastActiveAt).toLocaleString()}
                </p>
                <p className="text-xs text-gray-400">
                  Created: {new Date(session.createdAt).toLocaleString()}
                </p>
              </div>
              <button
                onClick={() => handleRevoke(session.pid)}
                className="rounded border border-red-200 px-3 py-1 text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-700"
                data-testid={`revoke-session-${session.pid}`}
              >
                Revoke
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function SecuritySettings() {
  const [activeTab, setActiveTab] = useState<'password' | 'sessions'>('password');

  return (
    <div className="p-6">
      <div className="rounded-lg bg-white shadow-md">
        <div className="border-b border-gray-200 px-6 py-4">
          <h1 className="text-2xl font-semibold text-gray-900">Security Settings</h1>
        </div>

        {/* Tab navigation */}
        <div className="border-b border-gray-200 px-6">
          <nav className="flex space-x-8">
            <button
              onClick={() => setActiveTab('password')}
              className={`border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeTab === 'password'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              Change Password
            </button>
            <button
              onClick={() => setActiveTab('sessions')}
              className={`border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeTab === 'sessions'
                  ? 'border-blue-500 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              Active Sessions
            </button>
          </nav>
        </div>

        <div className="p-6">
          {activeTab === 'password' ? <PasswordChangeForm /> : <ActiveSessions />}
        </div>
      </div>
    </div>
  );
}
