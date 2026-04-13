/**
 * Generic Dashboard Viewer Page
 *
 * Loads a dashboard instance by code from the URL and renders it in read-only mode.
 * Route: /dashboards/view/:code
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, Link } from 'react-router';
import { ArrowPathIcon, PencilSquareIcon, ArrowLeftIcon } from '@heroicons/react/24/outline';
import { ChartBarSquareIcon } from '@heroicons/react/24/outline';
import { ExportPdfButton } from '~/framework/smart/components/data-tools/ExportPdfButton';
import { useToastContext } from '~/contexts/ToastContext';
import { DashboardViewer } from '~/plugins/core-dashboard/components/DashboardViewer';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import type { Dashboard } from '~/plugins/core-dashboard/types';

export default function DashboardViewByCode() {
  const { showSuccessToast } = useToastContext();
  const { code } = useParams<{ code: string }>();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const dashboardRef = useRef<HTMLDivElement>(null);

  const loadDashboard = useCallback(async () => {
    if (!code) return;
    setLoading(true);
    setError(null);
    try {
      const data = await dashboardService.findByCode(code);
      setDashboard(data);
    } catch (err) {
      setDashboard(null);
      setError(`Dashboard not found: ${code}`);
    } finally {
      setLoading(false);
    }
  }, [code]);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  const handleRefresh = useCallback(async () => {
    await loadDashboard();
    showSuccessToast('Data refreshed');
  }, [loadDashboard]);

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4">
        <div className="flex items-center">
          <Link to="/dashboards" className="mr-3 text-gray-400 hover:text-gray-600">
            <ArrowLeftIcon className="h-5 w-5" />
          </Link>
          <ChartBarSquareIcon className="mr-3 h-7 w-7 text-blue-600" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900">{dashboard?.title || code}</h1>
            {dashboard?.description && (
              <p className="text-sm text-gray-500">{dashboard.description}</p>
            )}
          </div>
        </div>

        <div className="flex items-center space-x-2">
          <button
            onClick={handleRefresh}
            disabled={loading}
            className="inline-flex items-center rounded-md bg-gray-100 px-3 py-1.5 text-sm text-gray-700 transition-colors hover:bg-gray-200 disabled:opacity-50"
          >
            <ArrowPathIcon className={`mr-1.5 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          {dashboard && (
            <ExportPdfButton
              targetRef={dashboardRef}
              fileName={dashboard.title || 'dashboard'}
              orientation="landscape"
            />
          )}
          {dashboard?.pid && (
            <Link
              to={`/dashboard-designer/${dashboard.pid}`}
              className="inline-flex items-center rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white transition-colors hover:bg-blue-700"
            >
              <PencilSquareIcon className="mr-1.5 h-4 w-4" />
              Edit
            </Link>
          )}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div className="flex h-64 items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
            <span className="ml-3 text-gray-500">Loading...</span>
          </div>
        )}

        {!loading && error && (
          <div className="flex h-64 flex-col items-center justify-center text-red-500">
            <p>{error}</p>
            <button onClick={handleRefresh} className="mt-3 text-sm text-blue-600 hover:underline">
              Retry
            </button>
          </div>
        )}

        {!loading && !error && dashboard && (
          <div ref={dashboardRef}>
            <DashboardViewer
              widgets={dashboard.widgets || []}
              layoutConfig={dashboard.layoutConfig || { columns: 12, rowHeight: 80, gap: 16 }}
              className="min-h-[calc(100vh-140px)]"
            />
          </div>
        )}

        {!loading && !error && !dashboard && (
          <div className="flex h-64 flex-col items-center justify-center text-gray-400">
            <ChartBarSquareIcon className="mb-4 h-16 w-16 text-gray-300" />
            <p className="text-lg font-medium text-gray-500">Dashboard not found</p>
            <p className="mt-1 text-sm">
              No dashboard with code{' '}
              <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                {code}
              </code>
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
