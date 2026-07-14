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
  recordPid: string; // numeric id (not pid)
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

export const FieldHistoryViewer: React.FC<FieldHistoryViewerProps> = ({
  modelCode,
  recordPid,
  token,
  locale = 'zh-CN',
}) => {
  const [changes, setChanges] = useState<FieldChangeLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!modelCode || !recordPid) {
      setLoading(false);
      return;
    }

    const load = async () => {
      setLoading(true);
      setError(null);
      try {
        const result = await fetchResult<FieldChangeLog[]>('/api/audit/field-changes', {
          method: 'get',
          params: { modelCode, recordPid },
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
  }, [modelCode, recordPid, token, locale]);

  if (loading) {
    return (
      <div
        className="text-text-3 flex items-center justify-center py-12"
        data-testid="field-history-loading"
      >
        <div className="rounded-pill border-border-strong border-t-accent mr-2 h-5 w-5 animate-spin border-2" />
        {locale === 'zh-CN' ? '加载变更历史...' : 'Loading change history...'}
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-status-red py-8 text-center text-sm" data-testid="field-history-error">
        {error}
      </div>
    );
  }

  if (changes.length === 0) {
    return (
      <div className="text-text-3 py-12 text-center text-sm" data-testid="field-history-empty">
        {locale === 'zh-CN' ? '暂无变更记录' : 'No change history'}
      </div>
    );
  }

  // Group changes by changedAt timestamp (same second = same batch)
  const groups = groupByTimestamp(changes);

  return (
    <div className="relative" data-testid="field-history">
      {/* Timeline line */}
      <div className="absolute top-0 bottom-0 left-4 w-px bg-border" />

      <div className="space-y-6 pl-10">
        {groups.map((group, gi) => (
          <div key={gi} className="relative" data-testid={`field-history-group-${gi}`}>
            {/* Timeline dot */}
            <div className="rounded-pill border-accent bg-panel absolute top-1 -left-10 h-3 w-3 border-2" />

            {/* Header: actor + time */}
            <div className="text-text-2 mb-2 flex items-center gap-2 text-xs">
              <span className="text-text-2 font-medium">{group.actorName || '—'}</span>
              <span>&middot;</span>
              <time>{formatTime(group.changedAt, locale)}</time>
              {group.commandCode && (
                <>
                  <span>&middot;</span>
                  <span className="text-text-3">{group.commandCode}</span>
                </>
              )}
            </div>

            {/* Change entries */}
            <div className="rounded-card bg-subtle divide-y divide-border border border-border">
              {group.entries.map((entry) => (
                <div
                  key={entry.id}
                  className="flex items-start gap-3 px-3 py-2 text-sm"
                  data-testid={`field-history-entry-${entry.id}`}
                  data-field-code={entry.fieldCode}
                >
                  <ChangeTypeBadge type={entry.changeType} locale={locale} />
                  <div className="min-w-0 flex-1">
                    <span className="text-text-2 font-medium">
                      {entry.fieldLabel || entry.fieldCode}
                    </span>
                    <div className="text-text-2 mt-0.5 flex items-center gap-1.5 text-xs">
                      {entry.changeType === 'added' ? (
                        <span
                          className="text-status-green max-w-[200px] truncate"
                          title={entry.newValue || ''}
                        >
                          {entry.newValue || '—'}
                        </span>
                      ) : entry.changeType === 'removed' ? (
                        <span
                          className="text-status-red max-w-[200px] truncate line-through"
                          title={entry.oldValue || ''}
                        >
                          {entry.oldValue || '—'}
                        </span>
                      ) : (
                        <>
                          <span
                            className="text-status-red max-w-[140px] truncate line-through"
                            title={entry.oldValue || ''}
                          >
                            {entry.oldValue || '—'}
                          </span>
                          <svg
                            className="text-text-3 h-3 w-3 flex-shrink-0"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth={2}
                            viewBox="0 0 24 24"
                          >
                            <path d="M5 12h14m-4-4 4 4-4 4" />
                          </svg>
                          <span
                            className="text-status-green max-w-[140px] truncate"
                            title={entry.newValue || ''}
                          >
                            {entry.newValue || '—'}
                          </span>
                        </>
                      )}
                    </div>
                    {entry.changeReason && (
                      <p className="text-text-3 mt-1 text-xs italic">{entry.changeReason}</p>
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
    ADDED: ['新增', 'Added', 'bg-status-green-bg text-status-green'],
    MODIFIED: ['修改', 'Modified', 'bg-status-blue-bg text-status-blue'],
    REMOVED: ['删除', 'Removed', 'bg-status-red-bg text-status-red'],
  };
  const [zh, en, cls] = labels[type] || ['—', '—', 'bg-status-gray-bg text-status-gray'];
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
