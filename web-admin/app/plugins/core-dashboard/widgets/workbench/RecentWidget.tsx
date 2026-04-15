/**
 * RecentWidget — Workbench widget showing recently visited pages.
 * Data source: backend engagement API with localStorage as instant cache.
 */

import React, { useState, useEffect } from 'react';
import { getRecentVisits, fetchRecentVisits, type RecentVisit } from './useRecentVisits';

interface RecentWidgetProps {
  title?: string;
  maxItems?: number;
  className?: string;
}

const MODEL_ICONS: Record<string, string> = {
  crm_lead: '🎯',
  crm_opportunity: '💰',
  crm_account: '🏢',
  crm_contact: '👤',
  pm_project: '📁',
  pm_task: '✅',
  cc_contract: '📄',
};

const MODEL_BG_COLORS: Record<string, string> = {
  crm_lead: 'bg-blue-50',
  crm_opportunity: 'bg-amber-50',
  crm_account: 'bg-green-50',
  crm_contact: 'bg-teal-50',
  pm_project: 'bg-violet-50',
  pm_task: 'bg-indigo-50',
  cc_contract: 'bg-orange-50',
};

export function RecentWidget({
  title = '最近访问',
  maxItems = 8,
  className = '',
}: RecentWidgetProps) {
  const [visits, setVisits] = useState<RecentVisit[]>(() => getRecentVisits(maxItems));

  useEffect(() => {
    // Show localStorage data immediately (already set via initializer)
    setVisits(getRecentVisits(maxItems));

    // Refresh from API in background
    let cancelled = false;
    fetchRecentVisits(maxItems).then((apiVisits) => {
      if (!cancelled) {
        setVisits(apiVisits);
      }
    });
    return () => { cancelled = true; };
  }, [maxItems]);

  const formatTime = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return '刚刚';
    if (mins < 60) return `${mins}分钟前`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}小时前`;
    return date.toLocaleDateString();
  };

  if (visits.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-semibold text-gray-900">{title}</span>
          <span className="text-gray-400">
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <circle cx="4" cy="10" r="1.5" />
              <circle cx="10" cy="10" r="1.5" />
              <circle cx="16" cy="10" r="1.5" />
            </svg>
          </span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center">
          <span className="mb-1 text-2xl">🕐</span>
          <span className="text-sm text-gray-400">暂无访问记录</span>
        </div>
      </div>
    );
  }

  return (
    <div className={`flex h-full flex-col ${className}`}>
      <div className="mb-3 flex items-center justify-between px-1">
        <span className="text-sm font-semibold text-gray-900">{title}</span>
        <span className="text-gray-400">
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <circle cx="4" cy="10" r="1.5" />
            <circle cx="10" cy="10" r="1.5" />
            <circle cx="16" cy="10" r="1.5" />
          </svg>
        </span>
      </div>
      <div className="flex-1 space-y-0.5 overflow-y-auto">
        {visits.map((visit, idx) => {
          const icon = visit.icon || (visit.modelCode && MODEL_ICONS[visit.modelCode]) || '📄';
          const bgColor = (visit.modelCode && MODEL_BG_COLORS[visit.modelCode]) || 'bg-gray-100';
          return (
            <a
              key={`${visit.path}-${idx}`}
              href={visit.path}
              className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 cursor-pointer transition-colors hover:bg-gray-50"
            >
              <div
                className={`w-[30px] h-[30px] rounded-lg flex items-center justify-center text-sm flex-shrink-0 ${bgColor}`}
              >
                {icon}
              </div>
              <div className="min-w-0 flex-1">
                <span className="block truncate text-xs font-medium text-gray-700">
                  {visit.title}
                </span>
                <span className="block truncate text-[10px] text-gray-400">{visit.path}</span>
              </div>
              <span className="text-[10px] text-gray-400 flex-shrink-0">
                {formatTime(visit.visitedAt)}
              </span>
            </a>
          );
        })}
      </div>
    </div>
  );
}
