/**
 * RecentWidget — Workbench widget showing recently visited pages.
 * Data source: backend engagement API with localStorage as instant cache.
 */

import React, { useMemo, useState, useEffect, useRef } from 'react';
import { dedupeVisits, getRecentVisits, fetchRecentVisits, type RecentVisit } from './useRecentVisits';
import { useI18n } from '~/contexts/I18nContext';
import { useRootLoaderData } from '~/root-data';
import { useFittingRows } from './useFittingRows';

interface RecentWidgetProps {
  title?: string;
  maxItems?: number;
  className?: string;
}

const MODEL_ICONS: Record<string, string> = {
  pm_project: '📁',
  pm_task: '✅',
  cc_contract: '📄',
};

const MODEL_BG_COLORS: Record<string, string> = {
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

function pathMatchesMenu(path: string, menuPath: string): boolean {
  return path === menuPath || path.startsWith(`${menuPath}/`) || path.startsWith(`${menuPath}?`);
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

type Translate = (key: string, params?: unknown, fallback?: string) => string;

/** What a sub-page of a menu entry is called on the row's second line. */
const SUBPAGE_LABELS: Array<[segment: string, key: string, fallback: string]> = [
  ['new', 'workbench.recent.subpage.new', '新建'],
  ['edit', 'workbench.recent.subpage.edit', '编辑'],
  ['view', 'workbench.recent.subpage.view', '详情'],
];

/** The page a visit points at, without its query string or hash: `/p/x?page=2` is the page `/p/x`. */
function pathnameOf(path: string): string {
  return path.split(/[?#]/)[0];
}

/**
 * The menu entry a visit sits under: the entry itself, or the longest path it is a sub-page of.
 * Pages do not set a document title, so this is where the row's name comes from.
 */
function resolveMenuEntry(
  visit: RecentVisit,
  menuTitleMap: Map<string, string | undefined>,
): { title: string; menuPath: string } | null {
  const pathname = pathnameOf(visit.path);
  const exact = menuTitleMap.get(pathname);
  if (exact) return { title: exact, menuPath: pathname };
  const parent = [...menuTitleMap.keys()]
    .filter((menuPath) => pathname.startsWith(`${menuPath}/`))
    .sort((a, b) => b.length - a.length)[0];
  return parent ? { title: menuTitleMap.get(parent) || parent, menuPath: parent } : null;
}

/**
 * A page under a menu entry is not that entry: its "new" form, a record's detail, an edit page.
 * Left unnamed, the list, its create form and a record all read as the same line.
 */
function subpageLabel(path: string, menuPath: string, t: Translate): string | null {
  const rest = path.split(/[?#]/)[0].slice(menuPath.replace(/\/$/, '').length + 1);
  const segment = rest.split('/')[0];
  if (!segment) return null;
  const known = SUBPAGE_LABELS.find(([name]) => name === segment);
  if (known) return t(known[1], undefined, known[2]);
  return humanizeSegment(segment) || null;
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

  const visibleMenuPaths = useMemo(() => new Set(menuTitleMap.keys()), [menuTitleMap]);

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
    const entry = resolveMenuEntry(visit, menuTitleMap);
    if (entry) return entry.title;

    if (!isRawVisitTitle(visit.title, visit.path)) return visit.title;
    return prettifyPath(visit.path) || visit.path;
  };

  /** The row's second line names the record when the visit carries its title, else the kind of
   * sub-page (新建 / 编辑 / 详情). A title that is only the path, a code or the site's name says
   * nothing the menu-derived first line has not said. */
  const resolveVisitSubtitle = (visit: RecentVisit, primaryTitle: string): string | null => {
    if (!isRawVisitTitle(visit.title, visit.path) && visit.title !== primaryTitle) {
      return visit.title;
    }
    const entry = resolveMenuEntry(visit, menuTitleMap);
    // the entry's own page has nothing after its path, so it gets no second line
    return entry ? subpageLabel(visit.path, entry.menuPath, t as Translate) : null;
  };

  const visibleVisits = useMemo(
    () =>
      dedupeVisits(
        visibleMenuPaths.size === 0
          ? visits
          : visits.filter((visit) =>
              [...visibleMenuPaths].some((menuPath) => pathMatchesMenu(visit.path, menuPath)),
            ),
      ),
    [visibleMenuPaths, visits],
  );

  // Whole rows only: the rows that fit in the card are shown and the rest sit behind a "More" control.
  const listRef = useRef<HTMLDivElement>(null);
  const fittingRows = useFittingRows(listRef, [visibleVisits.length]);
  const [expanded, setExpanded] = useState(false);
  const shownVisits =
    !expanded && fittingRows !== null && visibleVisits.length > fittingRows
      ? visibleVisits.slice(0, fittingRows)
      : visibleVisits;
  const hiddenCount = visibleVisits.length - shownVisits.length;

  if (visibleVisits.length === 0) {
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
        <div className="flex items-center gap-3">
          {(hiddenCount > 0 || expanded) && (
            <button
              type="button"
              onClick={() => setExpanded((prev) => !prev)}
              aria-expanded={expanded}
              className="text-xs text-gray-400 transition-colors hover:text-blue-500"
              data-testid="recent-more-button"
            >
              {expanded
                ? t('workbench.recent.collapse', undefined, '收起')
                : `${t('workbench.recent.more', undefined, '更多')} (${hiddenCount})`}
            </button>
          )}
          <span className="text-gray-400">
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <circle cx="4" cy="10" r="1.5" />
              <circle cx="10" cy="10" r="1.5" />
              <circle cx="16" cy="10" r="1.5" />
            </svg>
          </span>
        </div>
      </div>
      <div ref={listRef} data-testid="recent-list" className="flex-1 space-y-0.5 overflow-y-auto px-3 pb-3">
        {shownVisits.map((visit, idx) => {
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
                {secondaryTitle ? (
                  <span className="block truncate text-[11px] text-gray-400">{secondaryTitle}</span>
                ) : null}
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
