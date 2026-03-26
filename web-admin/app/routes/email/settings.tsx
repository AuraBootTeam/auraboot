/**
 * EmailSettingsPage — manage connected Gmail accounts.
 *
 * Features:
 *  - List connected accounts with status badges
 *  - Connect new Gmail account via OAuth2
 *  - Toggle sync mode (full ↔ metadata_only)
 *  - Disconnect account with confirmation
 *  - View/manage shared account members
 *  - Trigger manual sync
 */

import { useState, useEffect, useCallback } from 'react';
import {
  Cog6ToothIcon,
  PlusIcon,
  ArrowPathIcon,
  TrashIcon,
  UsersIcon,
  EnvelopeIcon,
} from '@heroicons/react/24/outline';
import {
  listAccounts,
  updateSyncMode,
  disconnectAccount,
  triggerSync,
  listMembers,
  removeMember,
  getOAuthUrl,
  type EmailAccount,
  type EmailAccountMember,
} from '~/services/emailService';
import { useToastContext } from '~/contexts/ToastContext';

function statusBadge(status: string) {
  const styles: Record<string, string> = {
    active: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    disconnected: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
    error: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${styles[status] || styles.disconnected}`}>
      {status}
    </span>
  );
}

function syncModeBadge(mode: string) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${
      mode === 'full'
        ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
        : 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
    }`}>
      {mode === 'full' ? 'Full sync' : 'Metadata only'}
    </span>
  );
}

function timeAgo(dateStr?: string): string {
  if (!dateStr) return 'Never';
  const now = Date.now();
  const diff = now - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

interface MembersPanelProps {
  account: EmailAccount;
  onClose: () => void;
}

function MembersPanel({ account, onClose }: MembersPanelProps) {
  const [members, setMembers] = useState<EmailAccountMember[]>([]);
  const [loading, setLoading] = useState(true);
  const { showSuccessToast, showErrorToast } = useToastContext();

  useEffect(() => {
    setLoading(true);
    listMembers(account.id)
      .then(setMembers)
      .catch(() => showErrorToast('Failed to load members'))
      .finally(() => setLoading(false));
  }, [account.id]);

  const handleRemove = async (member: EmailAccountMember) => {
    try {
      await removeMember(account.id, member.id);
      setMembers((prev) => prev.filter((m) => m.id !== member.id));
      showSuccessToast('Member removed');
    } catch {
      showErrorToast('Failed to remove member');
    }
  };

  return (
    <div className="mt-4 rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800/50">
      <div className="mb-3 flex items-center justify-between">
        <h4 className="text-sm font-medium text-gray-900 dark:text-white">
          Shared Members
        </h4>
        <button
          onClick={onClose}
          className="text-xs text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
        >
          Close
        </button>
      </div>
      {loading ? (
        <div className="flex justify-center py-4">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : members.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">No members yet.</p>
      ) : (
        <ul className="space-y-2">
          {members.map((m) => (
            <li key={m.id} className="flex items-center justify-between rounded-md bg-white px-3 py-2 dark:bg-gray-800">
              <div>
                <p className="text-sm font-medium text-gray-900 dark:text-white">
                  {m.userDisplayName || `User #${m.userId}`}
                </p>
                <p className="text-xs text-gray-500 dark:text-gray-400">{m.userEmail} · {m.role}</p>
              </div>
              {m.role !== 'owner' && (
                <button
                  onClick={() => handleRemove(m)}
                  className="rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
                  title="Remove member"
                >
                  <TrashIcon className="h-4 w-4" />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function EmailSettingsPage() {
  const [accounts, setAccounts] = useState<EmailAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState<number | null>(null);
  const [expandedMembers, setExpandedMembers] = useState<number | null>(null);
  const [disconnectConfirm, setDisconnectConfirm] = useState<number | null>(null);
  const { showSuccessToast, showErrorToast } = useToastContext();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setAccounts(await listAccounts());
    } catch {
      showErrorToast('Failed to load email accounts');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleConnect = async () => {
    try {
      const url = await getOAuthUrl();
      if (url) {
        window.location.href = url;
      } else {
        showErrorToast('Failed to get OAuth URL');
      }
    } catch {
      showErrorToast('Failed to initiate Gmail connection');
    }
  };

  const handleSyncModeToggle = async (account: EmailAccount) => {
    const newMode = account.syncMode === 'full' ? 'metadata_only' : 'full';
    try {
      await updateSyncMode(account.id, newMode);
      setAccounts((prev) =>
        prev.map((a) => (a.id === account.id ? { ...a, syncMode: newMode } : a)),
      );
      showSuccessToast('Sync mode updated');
    } catch {
      showErrorToast('Failed to update sync mode');
    }
  };

  const handleDisconnect = async (accountId: number) => {
    try {
      await disconnectAccount(accountId);
      setAccounts((prev) =>
        prev.map((a) =>
          a.id === accountId ? { ...a, status: 'disconnected' } : a,
        ),
      );
      showSuccessToast('Account disconnected');
      setDisconnectConfirm(null);
    } catch {
      showErrorToast('Failed to disconnect account');
    }
  };

  const handleSync = async (accountId: number) => {
    setSyncing(accountId);
    try {
      await triggerSync(accountId);
      showSuccessToast('Sync triggered');
    } catch {
      showErrorToast('Failed to trigger sync');
    } finally {
      setSyncing(null);
    }
  };

  return (
    <div className="mx-auto max-w-3xl px-4 py-6" data-testid="email-settings-page">
      {/* Page Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Cog6ToothIcon className="h-7 w-7 text-gray-700 dark:text-gray-300" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">
              Email Settings
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              Manage connected Gmail accounts
            </p>
          </div>
        </div>
        <button
          onClick={handleConnect}
          data-testid="connect-gmail-btn"
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
        >
          <PlusIcon className="h-4 w-4" />
          Connect Gmail
        </button>
      </div>

      {/* Account List */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      ) : accounts.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-300 py-16 dark:border-gray-600"
          data-testid="email-settings-empty"
        >
          <EnvelopeIcon className="mb-3 h-10 w-10 text-gray-300 dark:text-gray-600" />
          <p className="mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
            No Gmail accounts connected
          </p>
          <p className="mb-4 text-xs text-gray-400 dark:text-gray-500">
            Connect a Gmail account to start syncing emails
          </p>
          <button
            onClick={handleConnect}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            <PlusIcon className="h-4 w-4" />
            Connect Gmail
          </button>
        </div>
      ) : (
        <div className="space-y-4">
          {accounts.map((account) => (
            <div
              key={account.id}
              className="rounded-xl border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800"
              data-testid={`email-account-${account.id}`}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <EnvelopeIcon className="h-5 w-5 flex-shrink-0 text-gray-400" />
                    <span className="font-medium text-gray-900 dark:text-white">
                      {account.emailAddress}
                    </span>
                    {statusBadge(account.status)}
                    {syncModeBadge(account.syncMode)}
                    {account.accountType === 'shared' && (
                      <span className="rounded-full bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-400">
                        Shared
                      </span>
                    )}
                  </div>
                  {account.displayName && (
                    <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                      {account.displayName}
                    </p>
                  )}
                  <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">
                    Last sync: {timeAgo(account.syncState?.lastSyncAt)}
                    {account.syncState?.syncStatus && ` · ${account.syncState.syncStatus}`}
                  </p>
                </div>

                {/* Actions */}
                <div className="flex flex-shrink-0 items-center gap-2">
                  {/* Sync mode toggle */}
                  <button
                    onClick={() => handleSyncModeToggle(account)}
                    title={`Switch to ${account.syncMode === 'full' ? 'metadata only' : 'full sync'}`}
                    className="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                  >
                    {account.syncMode === 'full' ? 'Metadata only' : 'Full sync'}
                  </button>

                  {/* Members (shared accounts) */}
                  {account.accountType === 'shared' && (
                    <button
                      onClick={() =>
                        setExpandedMembers(
                          expandedMembers === account.id ? null : account.id,
                        )
                      }
                      title="Manage members"
                      className="rounded-lg border border-gray-200 p-1.5 text-gray-500 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-400 dark:hover:bg-gray-700"
                    >
                      <UsersIcon className="h-4 w-4" />
                    </button>
                  )}

                  {/* Manual sync */}
                  <button
                    onClick={() => handleSync(account.id)}
                    disabled={syncing === account.id}
                    title="Trigger sync"
                    className="rounded-lg border border-gray-200 p-1.5 text-gray-500 transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-400 dark:hover:bg-gray-700"
                  >
                    <ArrowPathIcon
                      className={`h-4 w-4 ${syncing === account.id ? 'animate-spin' : ''}`}
                    />
                  </button>

                  {/* Disconnect */}
                  {disconnectConfirm === account.id ? (
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => handleDisconnect(account.id)}
                        className="rounded-lg bg-red-600 px-2 py-1 text-xs font-medium text-white hover:bg-red-700"
                      >
                        Confirm
                      </button>
                      <button
                        onClick={() => setDisconnectConfirm(null)}
                        className="rounded-lg border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300"
                      >
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => setDisconnectConfirm(account.id)}
                      title="Disconnect"
                      className="rounded-lg border border-gray-200 p-1.5 text-red-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:border-gray-600 dark:hover:bg-red-900/20"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {/* Members panel */}
              {expandedMembers === account.id && (
                <MembersPanel
                  account={account}
                  onClose={() => setExpandedMembers(null)}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
