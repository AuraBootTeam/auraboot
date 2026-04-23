/**
 * RecentWidget — Workbench widget showing recently visited pages.
 * Data source: backend engagement API with localStorage as instant cache.
 */

import React, { useMemo, useState, useEffect } from 'react';
import { getRecentVisits, fetchRecentVisits, type RecentVisit } from './useRecentVisits';
import { useI18n } from '~/contexts/I18nContext';
import { useRootLoaderData } from '~/root';

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

interface UiMenuItem {
  name?: string;
  nameKey?: string;
  path?: string;
  submenu?: UiMenuItem[];
}

function flattenMenus(items: UiMenuItem[]): UiMenuItem[] {
  return items.flatMap((item) => [item, ...(item.submenu ? flattenMenus(item.submenu) : [])]);
}

function humanizeSegment(segment: string): string {
  return segment
    .replace(/[_-]+/g, ' ')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function prettifyPath(path: string): string {
  return path
    .split('/')
    .filter(Boolean)
    .slice(0, 2)
    .map(humanizeSegment)
    .join(' / ');
}

function isRawVisitTitle(title: string, path: string): boolean {
  return !title || title === path || title.startsWith('/') || /^[a-z0-9/_-]+$/i.test(title);
}

export function RecentWidget({
  title,
  maxItems = 8,
  className = '',
}: RecentWidgetProps) {
  const { t, locale } = useI18n();
  const rootData = useRootLoaderData();
  const [visits, setVisits] = useState<RecentVisit[]>(() => getRecentVisits(maxItems));

  const resolvedTitle = title
    ? t(title)
    : t('workbench.recent.title');

  const menuTitleMap = useMemo(() => {
    const menus = ((rootData?.menus as UiMenuItem[] | undefined) ?? []);
    const flatMenus = flattenMenus(menus);
    return new Map(
      flatMenus
        .filter((item) => item.path)
        .map((item) => [item.path as string, item.nameKey ? t(item.nameKey) : item.name || item.path]),
    );
  }, [rootData?.menus, t]);

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
    if (mins < 1) return t('workbench.recent.justNow');
    if (mins < 60) return t('workbench.recent.minutesAgo', { minutes: mins });
    const hours = Math.floor(mins / 60);
    if (hours < 24) return t('workbench.recent.hoursAgo', { hours });
    return date.toLocaleDateString(locale);
  };

  const resolveVisitTitle = (visit: RecentVisit) => {
    const exactMenuTitle = menuTitleMap.get(visit.path);
    if (exactMenuTitle) return exactMenuTitle;

    const bestPrefixMatch = [...menuTitleMap.entries()]
      .filter(([path]) => visit.path === path || visit.path.startsWith(`${path}/`))
      .sort((a, b) => b[0].length - a[0].length)[0]?.[1];
    if (bestPrefixMatch) return bestPrefixMatch;

    if (!isRawVisitTitle(visit.title, visit.path)) return visit.title;
    return prettifyPath(visit.path) || visit.path;
  };

  const resolveVisitSubtitle = (visit: RecentVisit, primaryTitle: string) => {
    if (!isRawVisitTitle(visit.title, visit.path) && visit.title !== primaryTitle) {
      return visit.title;
    }
    return visit.path;
  };

  if (visits.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        <div className="mb-3 flex items-center justify-between px-3 pt-3">
          <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
          <span className="text-gray-400">
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <circle cx="4" cy="10" r="1.5" />
              <circle cx="10" cy="10" r="1.5" />
              <circle cx="16" cy="10" r="1.5" />
            </svg>
          </span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center px-3 pb-3">
          <span className="mb-1 text-2xl">🕐</span>
          <span className="text-sm text-gray-400">
            {t('workbench.recent.empty')}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className={`flex h-full flex-col ${className}`}>
      <div className="mb-3 flex items-center justify-between px-3 pt-3">
        <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        <span className="text-gray-400">
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <circle cx="4" cy="10" r="1.5" />
            <circle cx="10" cy="10" r="1.5" />
            <circle cx="16" cy="10" r="1.5" />
          </svg>
        </span>
      </div>
      <div className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-3">
        {visits.map((visit, idx) => {
          const icon = visit.icon || (visit.modelCode && MODEL_ICONS[visit.modelCode]) || '📄';
          const bgColor = (visit.modelCode && MODEL_BG_COLORS[visit.modelCode]) || 'bg-gray-100';
          const primaryTitle = resolveVisitTitle(visit);
          const secondaryTitle = resolveVisitSubtitle(visit, primaryTitle);
          return (
            <a
              key={`${visit.path}-${idx}`}
              href={visit.path}
              className="group flex items-center gap-3 rounded-xl border border-transparent px-3 py-2.5 transition-all hover:border-gray-200 hover:bg-gray-50 hover:shadow-sm"
            >
              <div
                className={`flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl text-sm shadow-sm ${bgColor}`}
              >
                {icon}
              </div>
              <div className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-gray-800 transition-colors group-hover:text-gray-900">
                  {primaryTitle}
                </span>
                <span className="block truncate text-[11px] text-gray-400">{secondaryTitle}</span>
              </div>
              <span className="flex-shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-[10px] text-gray-500">
                {formatTime(visit.visitedAt)}
              </span>
            </a>
          );
        })}
      </div>
    </div>
  );
}
