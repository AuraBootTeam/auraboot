/**
 * Dashboard Viewer — Multi-Tab entry for published dashboards.
 *
 * Route: /dashboards?code=xxx
 *
 * Features:
 * - Draggable tabs (reorder via @dnd-kit, persisted per user)
 * - First-time onboarding hint
 */

import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useSearchParams, Link } from 'react-router';
import { ArrowPathIcon } from '@heroicons/react/24/outline';
import { WelcomeGuide } from '~/ui/onboarding/WelcomeGuide';
import { QuickStartCards } from '~/ui/onboarding/QuickStartCards';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  horizontalListSortingStrategy,
  useSortable,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { DashboardViewer } from '~/plugins/core-dashboard/components/DashboardViewer';
import { ExportPdfButton } from '~/framework/smart/components/data-tools/ExportPdfButton';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import type { Dashboard } from '~/plugins/core-dashboard/types';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { userPreferenceService } from '~/shared/services/userPreferenceService';

const PREF_KEY = 'dashboard_tab_order';
const HINT_STORAGE_KEY = 'dashboard_drag_hint_shown';
const HIDDEN_DEFAULT_TAB_CODES = new Set([
  'sc_workflow_dashboard',
  'sc_arsenal_dashboard',
  'acs_dashboard',
]);

function getBrowserStorage(): Storage | null {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return null;
  }
  return window.localStorage;
}

// ---------------------------------------------------------------------------
// SortableTab — individual draggable tab
// ---------------------------------------------------------------------------

interface SortableTabProps {
  dashboard: Dashboard;
  isActive: boolean;
  onClick: () => void;
}

function SortableTab({ dashboard, isActive, onClick }: SortableTabProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: dashboard.code!,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  return (
    <button
      ref={setNodeRef}
      style={style}
      onClick={onClick}
      className={`cursor-grab border-b-2 px-1 py-3 text-sm font-medium whitespace-nowrap transition-colors active:cursor-grabbing ${
        isActive
          ? 'border-blue-500 text-blue-600'
          : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
      }`}
      {...attributes}
      {...listeners}
    >
      {dashboard.title}
    </button>
  );
}

// ---------------------------------------------------------------------------
// DragHint — subtle first-time onboarding hint
// ---------------------------------------------------------------------------

function DragHint({ label }: { label: string }) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const storage = getBrowserStorage();
    if (storage?.getItem(HINT_STORAGE_KEY)) return;

    // Fade in after a short delay
    const showTimer = setTimeout(() => setVisible(true), 800);

    // Auto-dismiss after 4s and mark as shown
    const hideTimer = setTimeout(() => {
      setVisible(false);
      storage?.setItem(HINT_STORAGE_KEY, '1');
    }, 4800);

    return () => {
      clearTimeout(showTimer);
      clearTimeout(hideTimer);
    };
  }, []);

  if (getBrowserStorage()?.getItem(HINT_STORAGE_KEY) && !visible) return null;

  return (
    <span
      className={`text-xs whitespace-nowrap text-gray-400 transition-opacity duration-500 ${
        visible ? 'opacity-100' : 'opacity-0'
      }`}
      data-testid="drag-hint"
    >
      {label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Main page component
// ---------------------------------------------------------------------------

export default function DashboardViewerPage() {
  const { showSuccessToast } = useToastContext();
  const { t } = useI18n();
  const [searchParams, setSearchParams] = useSearchParams();
  const codeParam = searchParams.get('code');

  const [publishedList, setPublishedList] = useState<Dashboard[]>([]);
  const [orderedCodes, setOrderedCodes] = useState<string[]>([]);
  const [activeCode, setActiveCode] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const dashboardRef = useRef<HTMLDivElement>(null);

  const visibleDashboards = useMemo(
    () =>
      publishedList.filter(
        (dashboard) =>
          !dashboard.code ||
          !HIDDEN_DEFAULT_TAB_CODES.has(dashboard.code) ||
          dashboard.code === activeCode,
      ),
    [activeCode, publishedList],
  );

  // Merge saved order with fetched list (new dashboards appended, removed ones filtered)
  const sortedList = useMemo(() => {
    if (orderedCodes.length === 0) return visibleDashboards;

    const byCode = new Map(visibleDashboards.map((d) => [d.code, d]));
    const sorted: Dashboard[] = [];

    // First: dashboards in saved order
    for (const code of orderedCodes) {
      const d = byCode.get(code);
      if (d) {
        sorted.push(d);
        byCode.delete(code);
      }
    }
    // Then: new dashboards not in saved order
    for (const d of byCode.values()) {
      sorted.push(d);
    }
    return sorted;
  }, [visibleDashboards, orderedCodes]);

  const activeDashboard = useMemo(
    () => sortedList.find((d) => d.code === activeCode) ?? null,
    [sortedList, activeCode],
  );

  // dnd-kit sensors — require 5px movement to distinguish click from drag
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  );

  // Initial load: fetch dashboards + user's saved tab order
  useEffect(() => {
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const [list, savedOrder] = await Promise.all([
          dashboardService.list({ status: 'published' }),
          userPreferenceService.get<string[]>(PREF_KEY).catch(() => null),
        ]);
        setPublishedList(list);
        if (savedOrder && Array.isArray(savedOrder)) {
          setOrderedCodes(savedOrder);
        }

        if (list.length === 0) {
          setLoading(false);
          return;
        }

        // URL code param takes priority
        if (codeParam && list.some((d) => d.code === codeParam)) {
          setActiveCode(codeParam);
        } else {
          // Try the default dashboard
          const defaultDash = await dashboardService.getDefaultDashboard().catch(() => null);
          if (defaultDash?.code && list.some((d) => d.code === defaultDash.code)) {
            setActiveCode(defaultDash.code);
          } else {
            setActiveCode(list[0].code!);
          }
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load dashboards');
      } finally {
        setLoading(false);
      }
    })();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps -- initial load only

  // Sync browser back/forward → activeCode
  useEffect(() => {
    if (codeParam && publishedList.some((d) => d.code === codeParam)) {
      setActiveCode(codeParam);
    }
  }, [codeParam, publishedList]);

  const handleTabClick = useCallback(
    (code: string) => {
      setActiveCode(code);
      setSearchParams({ code }, { replace: true });
    },
    [setSearchParams],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;

      const codes = sortedList.map((d) => d.code!);
      const oldIndex = codes.indexOf(active.id as string);
      const newIndex = codes.indexOf(over.id as string);
      if (oldIndex === -1 || newIndex === -1) return;

      const newCodes = arrayMove(codes, oldIndex, newIndex);
      setOrderedCodes(newCodes);

      // Persist to backend (fire-and-forget)
      userPreferenceService.set(PREF_KEY, newCodes).catch(() => {});

      // Dismiss hint on first drag
      localStorage.setItem(HINT_STORAGE_KEY, '1');
    },
    [sortedList],
  );

  const handleRefresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await dashboardService.list({ status: 'published' });
      setPublishedList(list);
      if (activeCode && !list.some((d) => d.code === activeCode) && list.length > 0) {
        setActiveCode(list[0].code!);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to refresh');
    } finally {
      setLoading(false);
      showSuccessToast(t('dashboard.refreshed'));
    }
  }, [activeCode, showSuccessToast, t]);

  // --- Empty state (with onboarding) ---
  if (!loading && publishedList.length === 0 && !error) {
    return (
      <div className="mx-auto max-w-7xl p-6">
        <WelcomeGuide />
        <QuickStartCards />

        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <svg
            className="mb-4 h-16 w-16 text-gray-300"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z"
            />
          </svg>
          <p className="mb-2 text-lg font-medium text-gray-500">{t('dashboard.empty')}</p>
          <Link
            to="/p/dashboard-management"
            className="text-sm text-blue-600 hover:underline"
          >
            {t('dashboard.goto_management')}
          </Link>
        </div>
      </div>
    );
  }

  const tabIds = sortedList.map((d) => d.code!);

  return (
    <div className="flex h-full flex-col">
      {/* Tab bar + actions */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6">
        {/* Sortable tabs */}
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={tabIds} strategy={horizontalListSortingStrategy}>
            <nav
              className="-mb-px flex items-center space-x-6 overflow-x-auto"
              aria-label="Dashboard tabs"
            >
              {sortedList.map((d) => (
                <SortableTab
                  key={d.code}
                  dashboard={d}
                  isActive={d.code === activeCode}
                  onClick={() => handleTabClick(d.code!)}
                />
              ))}
              {sortedList.length > 1 && <DragHint label={t('dashboard.drag_hint')} />}
            </nav>
          </SortableContext>
        </DndContext>

        {/* Actions */}
        <div className="ml-4 flex shrink-0 items-center space-x-2 py-2">
          <button
            onClick={handleRefresh}
            disabled={loading}
            className="inline-flex items-center rounded-md bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200 disabled:opacity-50"
          >
            <ArrowPathIcon className={`mr-1.5 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            {t('dashboard.refresh')}
          </button>
          {activeDashboard && (
            <ExportPdfButton
              targetRef={dashboardRef}
              fileName={activeDashboard.title || 'dashboard'}
              orientation="landscape"
            />
          )}
        </div>
      </div>

      {/* Content area */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div className="flex h-64 items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
            <span className="ml-3 text-gray-500">{t('common.loading')}</span>
          </div>
        )}

        {!loading && error && (
          <div className="flex h-64 flex-col items-center justify-center text-red-500">
            <p>{error}</p>
            <button onClick={handleRefresh} className="mt-3 text-sm text-blue-600 hover:underline">
              {t('dashboard.retry')}
            </button>
          </div>
        )}

        {!loading && !error && activeDashboard && (
          <div ref={dashboardRef}>
            <DashboardViewer
              widgets={activeDashboard.widgets || []}
              layoutConfig={activeDashboard.layoutConfig || { columns: 12, rowHeight: 80, gap: 16 }}
              className="min-h-[calc(100vh-140px)]"
            />
          </div>
        )}
      </div>
    </div>
  );
}
