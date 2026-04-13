/**
 * Reports Overview Page
 *
 * Loads a dashboard instance (by code "system_overview") and renders it in read-only mode.
 * Falls back to an empty state with a link to the Dashboard Designer if no dashboard is found.
 *
 * This replaces the previous hardcoded device management charts with a configurable
 * dashboard-powered overview, unifying the data pipeline.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { ArrowPathIcon, PencilSquareIcon } from '@heroicons/react/24/outline';
import { ChartBarSquareIcon } from '@heroicons/react/24/outline';
import { ExportPdfButton } from '~/smart/components/data-tools/ExportPdfButton';
import { useToastContext } from '~/contexts/ToastContext';
import { DashboardViewer } from '~/plugins/core-dashboard/components/DashboardViewer';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import type { Dashboard } from '~/plugins/core-dashboard/types';

const OVERVIEW_DASHBOARD_CODE = 'system_overview';

export default function ReportOverview() {
  const { showSuccessToast } = useToastContext();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const dashboardRef = useRef<HTMLDivElement>(null);

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await dashboardService.findByCode(OVERVIEW_DASHBOARD_CODE);
      setDashboard(data);
    } catch (err) {
      // Dashboard not found is expected for new tenants - show empty state
      setDashboard(null);
      setError(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  const handleRefresh = useCallback(async () => {
    await loadDashboard();
    showSuccessToast('数据已刷新');
  }, [loadDashboard]);

  const handleEditDashboard = useCallback(() => {
    if (dashboard?.pid) {
      window.location.href = `/dashboard-designer/${dashboard.pid}`;
    } else {
      window.location.href = '/dashboard-designer';
    }
  }, [dashboard]);

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4">
        <div className="flex items-center">
          <ChartBarSquareIcon className="mr-3 h-7 w-7 text-blue-600" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900" data-testid="page-title">
              报表概览
            </h1>
            <p className="text-sm text-gray-500">
              {dashboard ? dashboard.description || '系统概览仪表盘' : '查看关键指标统计'}
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-2">
          <button
            onClick={handleRefresh}
            disabled={loading}
            data-testid="btn-refresh"
            className="inline-flex items-center rounded-md bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200 disabled:opacity-50"
          >
            <ArrowPathIcon className={`mr-1.5 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </button>
          {dashboard && (
            <ExportPdfButton
              targetRef={dashboardRef}
              fileName={dashboard.title || 'dashboard'}
              orientation="landscape"
            />
          )}
          <button
            onClick={handleEditDashboard}
            data-testid="btn-edit-dashboard"
            className="inline-flex items-center rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white transition-colors hover:bg-blue-700"
          >
            <PencilSquareIcon className="mr-1.5 h-4 w-4" />
            编辑仪表盘
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div className="flex h-64 items-center justify-center" data-testid="loading-indicator">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
            <span className="ml-3 text-gray-500">加载中...</span>
          </div>
        )}

        {!loading && error && (
          <div className="flex h-64 flex-col items-center justify-center text-red-500">
            <p>{error}</p>
            <button onClick={handleRefresh} className="mt-3 text-sm text-blue-600 hover:underline">
              重试
            </button>
          </div>
        )}

        {!loading && !error && dashboard && (
          <div ref={dashboardRef} data-testid="dashboard-viewer">
            <DashboardViewer
              widgets={dashboard.widgets || []}
              layoutConfig={dashboard.layoutConfig || { columns: 12, rowHeight: 80, gap: 16 }}
              className="min-h-[calc(100vh-140px)]"
            />
          </div>
        )}

        {!loading && !error && !dashboard && (
          <div
            className="flex h-64 flex-col items-center justify-center text-gray-400"
            data-testid="empty-state"
          >
            <ChartBarSquareIcon className="mb-4 h-16 w-16 text-gray-300" />
            <p className="text-lg font-medium text-gray-500">尚未配置概览仪表盘</p>
            <p className="mt-1 text-sm">
              在仪表盘设计器中创建编码为{' '}
              <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                {OVERVIEW_DASHBOARD_CODE}
              </code>{' '}
              的仪表盘
            </p>
            <button
              onClick={handleEditDashboard}
              data-testid="btn-create-dashboard"
              className="mt-4 inline-flex items-center rounded-lg bg-blue-600 px-4 py-2 text-sm text-white transition-colors hover:bg-blue-700"
            >
              <PencilSquareIcon className="mr-2 h-4 w-4" />
              创建概览仪表盘
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
