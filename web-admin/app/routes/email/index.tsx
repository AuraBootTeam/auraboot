/**
 * EmailInboxPage — list view for synced email messages.
 *
 * Tabs: Inbox (inbound) | Sent (outbound) | All
 * Click a row to navigate to the thread view.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import {
  EnvelopeIcon,
  PaperAirplaneIcon,
  PaperClipIcon,
  MagnifyingGlassIcon,
  PencilSquareIcon,
} from '@heroicons/react/24/outline';
import {
  listMessages,
  markMessageRead,
  type EmailMessage,
  type EmailPage,
} from '~/services/emailService';

const TABS = [
  { key: 'inbound', label: 'Inbox', icon: EnvelopeIcon },
  { key: 'outbound', label: 'Sent', icon: PaperAirplaneIcon },
  { key: '', label: 'All', icon: EnvelopeIcon },
] as const;

function timeAgo(dateStr: string): string {
  const now = Date.now();
  const diff = now - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

function messagePreview(message: EmailMessage): string {
  const text = message.bodyText || '';
  return text.length > 100 ? text.slice(0, 100) + '…' : text;
}

function senderDisplay(message: EmailMessage): string {
  if (message.direction === 'inbound') {
    return message.fromName || message.fromAddress;
  }
  const to = message.toAddresses;
  return to.length > 0 ? `To: ${to[0]}${to.length > 1 ? ` +${to.length - 1}` : ''}` : 'Sent';
}

export default function EmailInboxPage() {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<string>('inbound');
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [page, setPage] = useState<EmailPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [currentPage, setCurrentPage] = useState(1);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listMessages({
        direction: activeTab || undefined,
        keyword: keyword || undefined,
        pageNum: currentPage,
        pageSize: 20,
      });
      setPage(result);
    } catch (err) {
      console.error('Failed to load emails:', err);
    } finally {
      setLoading(false);
    }
  }, [activeTab, keyword, currentPage]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    setCurrentPage(1);
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setKeyword(searchInput);
    setCurrentPage(1);
  };

  const handleMessageClick = async (message: EmailMessage) => {
    if (!message.isRead && message.direction === 'inbound') {
      markMessageRead(message.id).catch(() => {/* non-critical */});
    }
    navigate(`/email/thread/${message.gmailThreadId}`);
  };

  const items = page?.records || [];
  const total = page?.total || 0;
  const totalPages = page?.pages || 0;

  return (
    <div className="mx-auto max-w-4xl px-4 py-6" data-testid="email-inbox-page">
      {/* Page Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <EnvelopeIcon className="h-7 w-7 text-gray-700 dark:text-gray-300" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">Email</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">{total} messages</p>
          </div>
        </div>
        <button
          onClick={() => navigate('/email/compose')}
          data-testid="compose-email-btn"
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
        >
          <PencilSquareIcon className="h-4 w-4" />
          Compose
        </button>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex items-center gap-1 rounded-lg border border-gray-200 bg-gray-50 p-1 dark:border-gray-700 dark:bg-gray-800">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => handleTabChange(tab.key)}
              data-testid={`email-tab-${tab.key || 'all'}`}
              className={`flex items-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium transition-all ${
                isActive
                  ? 'bg-white text-blue-600 shadow-sm dark:bg-gray-700 dark:text-blue-400'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              <Icon className="h-4 w-4" />
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Search */}
      <form onSubmit={handleSearch} className="mb-4 flex gap-2">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder="Search emails..."
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            data-testid="email-search-input"
            className="w-full rounded-lg border border-gray-200 bg-white py-2 pl-10 pr-4 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:placeholder-gray-500"
          />
        </div>
        <button
          type="submit"
          className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
        >
          Search
        </button>
      </form>

      {/* Message List */}
      <div className="divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200 bg-white dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          </div>
        ) : items.length === 0 ? (
          <div
            className="flex flex-col items-center justify-center py-16"
            data-testid="email-empty-state"
          >
            <EnvelopeIcon className="mb-3 h-10 w-10 text-gray-300 dark:text-gray-600" />
            <p className="text-sm text-gray-500 dark:text-gray-400">
              No emails to show
            </p>
          </div>
        ) : (
          items.map((message) => (
            <button
              key={message.id}
              onClick={() => handleMessageClick(message)}
              data-testid={`email-row-${message.id}`}
              className={`flex w-full items-start gap-4 px-5 py-4 text-start transition-colors hover:bg-gray-50 dark:hover:bg-gray-750 ${
                !message.isRead && message.direction === 'inbound'
                  ? 'bg-blue-50/40 dark:bg-blue-900/10'
                  : ''
              }`}
            >
              {/* Direction indicator */}
              <div className="mt-0.5 flex-shrink-0">
                {message.direction === 'inbound' ? (
                  <EnvelopeIcon className="h-5 w-5 text-gray-400" />
                ) : (
                  <PaperAirplaneIcon className="h-5 w-5 text-blue-400" />
                )}
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-2">
                  <span
                    className={`text-sm ${
                      !message.isRead && message.direction === 'inbound'
                        ? 'font-semibold text-gray-900 dark:text-white'
                        : 'font-medium text-gray-700 dark:text-gray-300'
                    }`}
                  >
                    {senderDisplay(message)}
                  </span>
                  <div className="flex flex-shrink-0 items-center gap-2">
                    {message.hasAttachments && (
                      <PaperClipIcon className="h-4 w-4 text-gray-400" />
                    )}
                    <span className="text-xs text-gray-400">
                      {timeAgo(message.gmailDate)}
                    </span>
                  </div>
                </div>
                <p
                  className={`mt-0.5 text-sm ${
                    !message.isRead && message.direction === 'inbound'
                      ? 'font-medium text-gray-800 dark:text-gray-200'
                      : 'text-gray-600 dark:text-gray-400'
                  }`}
                >
                  {message.subject || '(No subject)'}
                </p>
                <p className="mt-0.5 truncate text-xs text-gray-400 dark:text-gray-500">
                  {messagePreview(message)}
                </p>
              </div>
            </button>
          ))
        )}
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Page {currentPage} of {totalPages} ({total} messages)
          </p>
          <div className="flex gap-2">
            <button
              onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
              disabled={currentPage <= 1}
              className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:hover:bg-gray-700"
            >
              Previous
            </button>
            <button
              onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
              disabled={currentPage >= totalPages}
              className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:hover:bg-gray-700"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
