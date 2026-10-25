/**
 * InboxDropdown — dropdown panel showing recent inbox items.
 * Appears when clicking InboxBadge. Shows top 5 items with "View All" link.
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  CheckCircleIcon,
  ExclamationTriangleIcon,
  BellIcon,
  ArrowRightIcon,
  EnvelopeOpenIcon,
} from '@heroicons/react/24/outline';
import { listInboxItems, markRead, markAllRead, type InboxItem } from '~/shared/services/inboxService';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import { InboxBadge } from './InboxBadge';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import FEEDBACK from '../notification/NotificationFeedback.i18n.json';


function itemIcon(type: string) {
  switch (type) {
    case 'approval':
      return <CheckCircleIcon className="h-5 w-5 text-blue-500" />;
    case 'alert':
      return <ExclamationTriangleIcon className="h-5 w-5 text-amber-500" />;
    case 'assignment':
      return <ArrowRightIcon className="h-5 w-5 text-green-500" />;
    default:
      return <BellIcon className="h-5 w-5 text-gray-400" />;
  }
}

function priorityDot(priority: string) {
  const colors: Record<string, string> = {
    urgent: 'bg-red-500',
    high: 'bg-orange-500',
    normal: 'bg-blue-400',
    low: 'bg-gray-300',
  };
  return (
    <span className={`inline-block h-2 w-2 rounded-full ${colors[priority] || colors.normal}`} />
  );
}

export function InboxHeaderWidget() {
  const { t, locale } = useI18n();
  const { timezone } = useTimezone();
  const { user } = useAuth();
  const scope = `${user?.tenantId ?? ''}:${user?.pid ?? ''}`;
  const scopeRef = useRef(scope); scopeRef.current = scope;
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<InboxItem[]>([]);
  const [loading, setLoading] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const fetchItems = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const page = await listInboxItems({
        status: 'pending',
        pageNum: 1,
        pageSize: 5,
      });
      if (scopeRef.current === scope) setItems(page.records);
    } catch {
      if (scopeRef.current === scope) setError(t('inbox.loadFailed'));
    } finally {
      if (scopeRef.current === scope) setLoading(false);
    }
  }, [scope, t]);
  useEffect(() => {setItems([]);setOpen(false);setError(null);}, [scope]);

  useEffect(() => {
    if (open) fetchItems();
  }, [open, fetchItems]);

  // Close on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const escHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    document.addEventListener('keydown', escHandler);
    return () => {
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', escHandler);
    };
  }, []);

  const handleItemClick = async (item: InboxItem) => {
    if (!item.isRead) {
      try {
        await markRead(item.id);
      } catch {
        // Non-critical
      }
    }
    setOpen(false);

    if (item.itemType === 'announcement_ack' && item.sourceId) {
      navigate(`/p/announcement_reader/view/${encodeURIComponent(item.sourceId)}`);
    } else if (item.itemType === 'approval' && item.sourceId) {
      navigate(`/inbox?task=${item.sourceId}`);
    } else if (item.sourceModel ?? item.modelCode) {
      const modelCode = item.sourceModel ?? item.modelCode;
      const recordPid = item.sourceRecordPid ?? (item.recordPid != null ? String(item.recordPid) : undefined);
      if (recordPid) {
        navigate(`/p/${modelCode}/view/${recordPid}`);
        return;
      }
      navigate('/inbox');
    } else {
      navigate('/inbox');
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await markAllRead();
      setItems((prev) => prev.map((i) => ({ ...i, isRead: true })));
    } catch {
      setError(getLocalizedText(FEEDBACK.markAllFailed, locale));
    }
  };

  return (
    <div className="relative" ref={dropdownRef} data-testid="inbox-widget">
      <InboxBadge onClick={() => setOpen(!open)} isOpen={open} />

      {open && (
        <div
          data-testid="inbox-dropdown"
          className="fixed inset-x-4 top-14 z-50 mt-2 rounded-xl border border-gray-200 bg-white shadow-xl sm:absolute sm:inset-x-auto sm:top-auto sm:end-0 sm:w-96 dark:border-gray-700 dark:bg-gray-800"
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3 dark:border-gray-700">
            <h3 className="text-sm font-semibold text-gray-900 dark:text-white">{t('inbox.title')}</h3>
            <button
              onClick={handleMarkAllRead}
              className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700 dark:text-blue-400"
              data-testid="inbox-mark-all-read"
            >
              <EnvelopeOpenIcon className="h-3.5 w-3.5" />
              {t('inbox.markAllRead')}
            </button>
          </div>

          {/* Items */}
          <div className="max-h-80 overflow-y-auto">
            {error ? <div role="alert" className="p-4 text-sm">{error}<button type="button" onClick={() => void fetchItems()} className="ms-2 underline">{t('common.retry')}</button></div> : loading && items.length === 0 ? (
              <div className="flex items-center justify-center py-8">
                <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
              </div>
            ) : items.length === 0 ? (
              <div className="py-8 text-center text-sm text-gray-500 dark:text-gray-400">
                {t('inbox.empty')}
              </div>
            ) : (
              items.map((item) => (
                <button
                  key={item.id}
                  onClick={() => handleItemClick(item)}
                  data-testid={`inbox-item-${item.id}`}
                  className={`flex w-full items-start gap-3 px-4 py-3 text-start transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50 ${
                    !item.isRead ? 'bg-blue-50/50 dark:bg-blue-900/10' : ''
                  }`}
                >
                  <div className="mt-0.5 flex-shrink-0">{itemIcon(item.itemType)}</div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      {!item.isRead && priorityDot(item.priority)}
                      <span className="truncate text-sm font-medium text-gray-900 dark:text-white">
                        {item.title}
                      </span>
                    </div>
                    {item.subtitle && (
                      <p className="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">
                        {item.subtitle}
                      </p>
                    )}
                    <div className="mt-1 flex items-center gap-2">
                      <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                        {t(`inbox.type.${['approval','task','mention','ai_suggestion','alert','assignment','system','announcement_ack'].includes(item.itemType) ? item.itemType : 'system'}`)}
                      </span>
                      <span className="text-xs text-gray-400">{new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short', timeZone: timezone }).format(new Date(item.createdAt))}</span>
                    </div>
                  </div>
                </button>
              ))
            )}
          </div>

          {/* Footer */}
          <div className="border-t border-gray-100 p-2 dark:border-gray-700">
            <Link
              to="/inbox"
              onClick={() => setOpen(false)}
              data-testid="inbox-view-all"
              className="flex w-full items-center justify-center rounded-lg py-2 text-sm font-medium text-blue-600 transition-colors hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/20"
            >
              {t('inbox.viewAll')}
              <ArrowRightIcon className="ml-1 h-4 w-4" />
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
