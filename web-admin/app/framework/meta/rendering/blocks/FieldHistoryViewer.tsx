/**
 * FieldHistoryViewer - Timeline view of field-level change history.
 * Calls /api/audit/field-changes to display who changed what, when.
 *
 * @since 6.2.0
 */

import React, { useState, useEffect } from 'react';
import dayjs from 'dayjs';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface FieldChangeLog {
  id: number;
  fieldCode: string;
  fieldLabel: string;
  valueType: string;
  oldValue: string | null;
  newValue: string | null;
  changeType: string; // ADDED, MODIFIED, REMOVED
  commandCode: string | null;
  actorName: string | null;
  changedAt: string; // ISO instant
  changeReason: string | null;
}

export interface FieldHistoryViewerProps {
  modelCode: string;
  recordId: string; // numeric id (not pid)
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

export const FieldHistoryViewer: React.FC<FieldHistoryViewerProps> = ({
  modelCode,
  recordId,
  token,
  locale = 'zh-CN',
}) => {
  const [changes, setChanges] = useState<FieldChangeLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!modelCode || !recordId) {
      setLoading(false);
      return;
    }

    const load = async () => {
      setLoading(true);
      setError(null);
      try {
        const result = await fetchResult<FieldChangeLog[]>('/api/audit/field-changes', {
          method: 'get',
          params: { modelCode, recordId },
          token,
        });
        if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
          setChanges(result.data);
        } else {
          setChanges([]);
        }
      } catch (e: any) {
        // 403 = no permission, show friendly message
        if (e?.status === 403) {
          setError(locale === 'zh-CN' ? '无审计查看权限' : 'No audit read permission');
        } else {
          setError(e?.message || 'Failed to load field history');
        }
      } finally {
        setLoading(false);
      }
    };

    load();
  }, [modelCode, recordId, token, locale]);

  if (loading) {
    return (
      <div
        className="flex items-center justify-center py-12 text-gray-400"
        data-testid="field-history-loading"
      >
        <div className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        {locale === 'zh-CN' ? '加载变更历史...' : 'Loading change history...'}
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-8 text-center text-sm text-red-500" data-testid="field-history-error">
        {error}
      </div>
    );
  }

  if (changes.length === 0) {
    return (
      <div className="py-12 text-center text-sm text-gray-400" data-testid="field-history-empty">
        {locale === 'zh-CN' ? '暂无变更记录' : 'No change history'}
      </div>
    );
  }

  // Group changes by changedAt timestamp (same second = same batch)
  const groups = groupByTimestamp(changes);

  return (
    <div className="relative" data-testid="field-history">
      {/* Timeline line */}
      <div className="absolute top-0 bottom-0 left-4 w-px bg-gray-200" />

      <div className="space-y-6 pl-10">
        {groups.map((group, gi) => (
          <div key={gi} className="relative" data-testid={`field-history-group-${gi}`}>
            {/* Timeline dot */}
            <div className="absolute top-1 -left-10 h-3 w-3 rounded-full border-2 border-blue-500 bg-white" />

            {/* Header: actor + time */}
            <div className="mb-2 flex items-center gap-2 text-xs text-gray-500">
              <span className="font-medium text-gray-700">{group.actorName || '—'}</span>
              <span>&middot;</span>
              <time>{formatTime(group.changedAt, locale)}</time>
              {group.commandCode && (
                <>
                  <span>&middot;</span>
                  <span className="text-gray-400">{group.commandCode}</span>
                </>
              )}
            </div>

            {/* Change entries */}
            <div className="divide-y divide-gray-100 rounded-lg border border-gray-100 bg-gray-50">
              {group.entries.map((entry) => (
                <div
                  key={entry.id}
                  className="flex items-start gap-3 px-3 py-2 text-sm"
                  data-testid={`field-history-entry-${entry.id}`}
                  data-field-code={entry.fieldCode}
                >
                  <ChangeTypeBadge type={entry.changeType} locale={locale} />
                  <div className="min-w-0 flex-1">
                    <span className="font-medium text-gray-700">
                      {entry.fieldLabel || entry.fieldCode}
                    </span>
                    <div className="mt-0.5 flex items-center gap-1.5 text-xs text-gray-500">
                      {entry.changeType === 'added' ? (
                        <span
                          className="max-w-[200px] truncate text-green-600"
                          title={entry.newValue || ''}
                        >
                          {entry.newValue || '—'}
                        </span>
                      ) : entry.changeType === 'removed' ? (
                        <span
                          className="max-w-[200px] truncate text-red-500 line-through"
                          title={entry.oldValue || ''}
                        >
                          {entry.oldValue || '—'}
                        </span>
                      ) : (
                        <>
                          <span
                            className="max-w-[140px] truncate text-red-500 line-through"
                            title={entry.oldValue || ''}
                          >
                            {entry.oldValue || '—'}
                          </span>
                          <svg
                            className="h-3 w-3 flex-shrink-0 text-gray-400"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth={2}
                            viewBox="0 0 24 24"
                          >
                            <path d="M5 12h14m-4-4 4 4-4 4" />
                          </svg>
                          <span
                            className="max-w-[140px] truncate text-green-600"
                            title={entry.newValue || ''}
                          >
                            {entry.newValue || '—'}
                          </span>
                        </>
                      )}
                    </div>
                    {entry.changeReason && (
                      <p className="mt-1 text-xs text-gray-400 italic">{entry.changeReason}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

// ============================================================================
// Helpers
// ============================================================================

function ChangeTypeBadge({ type, locale }: { type: string; locale: string }) {
  const labels: Record<string, [string, string, string]> = {
    // [zh, en, color]
    ADDED: ['新增', 'Added', 'bg-green-100 text-green-700'],
    MODIFIED: ['修改', 'Modified', 'bg-blue-100 text-blue-700'],
    REMOVED: ['删除', 'Removed', 'bg-red-100 text-red-700'],
  };
  const [zh, en, cls] = labels[type] || ['—', '—', 'bg-gray-100 text-gray-600'];
  return (
    <span
      className={`inline-block flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${cls}`}
    >
      {locale === 'zh-CN' ? zh : en}
    </span>
  );
}

interface ChangeGroup {
  changedAt: string;
  actorName: string | null;
  commandCode: string | null;
  entries: FieldChangeLog[];
}

function groupByTimestamp(changes: FieldChangeLog[]): ChangeGroup[] {
  const map = new Map<string, ChangeGroup>();
  // Sort newest first
  const sorted = [...changes].sort(
    (a, b) => new Date(b.changedAt).getTime() - new Date(a.changedAt).getTime(),
  );
  for (const c of sorted) {
    // Group by second-level timestamp + actor
    const key = `${c.changedAt?.substring(0, 19)}|${c.actorName}`;
    if (!map.has(key)) {
      map.set(key, {
        changedAt: c.changedAt,
        actorName: c.actorName,
        commandCode: c.commandCode,
        entries: [],
      });
    }
    map.get(key)!.entries.push(c);
  }
  return Array.from(map.values());
}

function formatTime(iso: string, _locale: string): string {
  const d = dayjs(iso);
  if (!d.isValid()) return iso;
  return d.format('MM-DD HH:mm');
}
