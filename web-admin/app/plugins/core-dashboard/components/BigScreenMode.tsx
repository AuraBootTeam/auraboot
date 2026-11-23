/**
 * Big Screen Presentation Mode
 *
 * A fullscreen overlay that renders the dashboard without designer chrome
 * (no sidebar, header, or editing controls). Supports:
 * - ESC key to exit
 * - Auto-refresh at configurable intervals
 * - Minimal exit button in top-right corner
 */

import React, { useEffect, useRef, useCallback } from 'react';
import { X } from 'lucide-react';
import { DashboardViewer } from './DashboardViewer';
import { useDashboardStore } from '../store/useDashboardStore';
import { useI18n } from '~/contexts/I18nContext';

interface BigScreenModeProps {
  dashboardId: string;
  onExit: () => void;
  /** Auto-refresh interval in milliseconds. Default: 60000 (1 minute) */
  refreshInterval?: number;
}

export const BigScreenMode: React.FC<BigScreenModeProps> = ({
  dashboardId,
  onExit,
  refreshInterval = 60000,
}) => {
  const { locale } = useI18n();
  const exitLabel = locale.startsWith('zh') ? '退出大屏' : 'Exit presentation';
  const { dashboard, widgets, layoutConfig, loadDashboard } = useDashboardStore();
  const refreshTimerRef = useRef<NodeJS.Timeout | null>(null);

  // ESC key listener
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onExit();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onExit]);

  // Auto-refresh timer
  const refresh = useCallback(() => {
    loadDashboard(dashboardId);
  }, [dashboardId, loadDashboard]);

  useEffect(() => {
    refreshTimerRef.current = setInterval(refresh, refreshInterval);
    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
  }, [refresh, refreshInterval]);

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-white" data-testid="big-screen-mode">
      <div className="flex shrink-0 justify-end border-b border-slate-200 px-4 py-2">
        <button
          type="button"
          onClick={onExit}
          title={exitLabel}
          aria-label={exitLabel}
          className="rounded-md p-1.5 text-slate-600 transition hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-blue-600"
          data-testid="big-screen-exit"
        >
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
      </div>

      {/* Dashboard content */}
      <div className="flex-1 overflow-auto">
        <DashboardViewer
          widgets={widgets}
          layoutConfig={layoutConfig}
          title={dashboard?.title || 'Dashboard'}
          showExport={false}
          hideWidgetActions
          className="h-full"
        />
      </div>
    </div>
  );
};

export default BigScreenMode;
