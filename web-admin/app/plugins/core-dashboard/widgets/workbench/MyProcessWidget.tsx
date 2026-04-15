/**
 * MyProcessWidget — Workbench widget showing user's own process instances.
 *
 * Features:
 * - Filter pills (All / Running / Completed)
 * - Process icon in colored container + name + current node + status badge + time
 * - Click to navigate to process detail
 * - Loading: skeleton rows
 * - Empty: "No processes started"
 * - Max 5 items with "View All" link
 *
 * Data source: /api/bpm/process-instances (existing API via bpmWorkbenchService).
 */

import React, { useEffect, useState, useCallback } from 'react';
import { getStartedProcesses, type ProcessInstance } from '~/plugins/core-bpm/services/bpmWorkbenchService';
import { useI18n } from '~/contexts/I18nContext';

interface MyProcessWidgetProps {
  title?: string;
  maxItems?: number;
  className?: string;
}

type FilterKey = 'all' | 'running' | 'completed';

const STATUS_BADGE: Record<string, string> = {
  running: 'bg-blue-100 text-blue-700',
  completed: 'bg-green-100 text-green-700',
  cancelled: 'bg-gray-100 text-gray-500',
  suspended: 'bg-amber-100 text-amber-700',
  terminated: 'bg-red-100 text-red-600',
};

const FILTER_PILLS: { key: FilterKey; labelKey: string }[] = [
  { key: 'all', labelKey: 'workbench.process.filterAll' },
  { key: 'running', labelKey: 'workbench.process.filterRunning' },
  { key: 'completed', labelKey: 'workbench.process.filterCompleted' },
];

function formatTime(dateStr: string, t: (key: string, vars?: Record<string, unknown>, fallback?: string) => string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const hours = Math.floor(diff / 3600000);
  if (hours < 1) return t('workbench.process.justNow', {}, 'Just now');
  if (hours < 24) return t('workbench.process.hoursAgo', { hours }, `${hours}h ago`);
  const days = Math.floor(hours / 24);
  if (days < 7) return t('workbench.process.daysAgo', { days }, `${days}d ago`);
  return date.toLocaleDateString();
}

export function MyProcessWidget({
  title,
  maxItems = 5,
  className = '',
}: MyProcessWidgetProps) {
  const { t } = useI18n();
  const [processes, setProcesses] = useState<ProcessInstance[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeFilter, setActiveFilter] = useState<FilterKey>('all');

  const resolvedTitle = title || t('workbench.process.title', {}, 'My Processes');

  const loadProcesses = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getStartedProcesses();
      setProcesses(result);
    } catch {
      setProcesses([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadProcesses();
  }, [loadProcesses]);

  const filteredProcesses = processes.filter((p) => {
    if (activeFilter === 'all') return true;
    if (activeFilter === 'running') return p.status === 'running' || p.status === 'active';
    if (activeFilter === 'completed') return p.status === 'completed' || p.status === 'finished';
    return true;
  });

  const displayedProcesses = filteredProcesses.slice(0, maxItems);
  const hasMore = filteredProcesses.length > maxItems;

  const handleProcessClick = (process: ProcessInstance) => {
    window.location.href = `/bpm/process/${process.instanceId}`;
  };

  // --- Header ---
  const renderHeader = () => (
    <div className="mb-3 flex items-center justify-between gap-2">
      <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
      {hasMore && (
        <a
          href="/bpm/processes"
          className="whitespace-nowrap text-[11px] text-blue-500 hover:text-blue-600"
        >
          {t('workbench.process.viewAll', {}, 'View All')} &rarr;
        </a>
      )}
    </div>
  );

  // --- Filter pills ---
  const renderFilters = () => (
    <div className="mb-3 flex items-center gap-1">
      {FILTER_PILLS.map((pill) => {
        const isActive = activeFilter === pill.key;
        return (
          <button
            key={pill.key}
            type="button"
            onClick={() => setActiveFilter(pill.key)}
            className={`cursor-pointer rounded-full px-3 py-1 text-[11px] transition-colors ${
              isActive
                ? 'bg-blue-50 font-medium text-blue-600'
                : 'text-gray-500 hover:bg-gray-50'
            }`}
          >
            {t(pill.labelKey, {}, pill.key === 'all' ? 'All' : pill.key === 'running' ? 'Running' : 'Completed')}
          </button>
        );
      })}
    </div>
  );

  // --- Skeleton loading ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        {renderFilters()}
        <div className="flex-1 space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="flex items-center gap-3 rounded-lg border border-gray-100 p-3">
              <div className="h-9 w-9 animate-pulse rounded-[10px] bg-gray-200" />
              <div className="flex-1 space-y-1.5">
                <div className="h-3 w-3/4 animate-pulse rounded bg-gray-200" />
                <div className="h-2.5 w-1/2 animate-pulse rounded bg-gray-200" />
              </div>
              <div className="h-5 w-14 animate-pulse rounded-full bg-gray-200" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- Empty state ---
  if (displayedProcesses.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        {renderFilters()}
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83D\uDCCB'}</span>
          <span className="text-sm">
            {t('workbench.process.empty', {}, 'No processes started')}
          </span>
        </div>
      </div>
    );
  }

  // --- Process list ---
  return (
    <div className={`flex h-full flex-col ${className}`}>
      {renderHeader()}
      {renderFilters()}
      <div className="flex-1 space-y-2 overflow-y-auto">
        {displayedProcesses.map((process) => {
          const statusClass = STATUS_BADGE[process.status] || STATUS_BADGE.running;
          const statusLabel = t(`workbench.process.status.${process.status}`, {}, process.status);

          return (
            <button
              key={process.instanceId}
              type="button"
              onClick={() => handleProcessClick(process)}
              className="flex w-full cursor-pointer items-center gap-3 rounded-lg border border-gray-100 bg-white p-3 text-left transition-colors hover:border-blue-200 hover:bg-blue-50/30"
            >
              {/* Process icon */}
              <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[10px] bg-blue-50 text-base">
                {'\uD83D\uDCCB'}
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-[13px] font-medium text-gray-900">
                    {process.title || process.processDefinitionKey}
                  </span>
                </div>
                <p className="mt-0.5 truncate text-[11px] text-gray-400">
                  {process.businessKey ? `${process.businessKey} \u00B7 ` : ''}
                  {formatTime(process.startTime, t)}
                </p>
              </div>

              {/* Status badge */}
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-medium ${statusClass}`}
              >
                {statusLabel}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
