import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams } from 'react-router';
import { fetchResult } from '~/shared/services/http-client/HttpClient';
import { useToken as useAuthToken } from '~/contexts/AuthContext';

interface WidgetData {
  [widgetId: string]: any;
}

interface DashboardDataResponse {
  widgets: WidgetData;
  fetchedAt: number;
  cacheTtl: number;
  dashboardTitle: string;
}

/**
 * Data Screen Viewer — fullscreen BI dashboard mode.
 * Features: dark theme, auto-refresh, animated counters, clock display.
 */
export default function DataScreenViewer() {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<DashboardDataResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [currentTime, setCurrentTime] = useState(new Date());
  const [refreshInterval, setRefreshInterval] = useState(30);
  const containerRef = useRef<HTMLDivElement>(null);
  const token = useAuthToken();

  // Fetch dashboard data
  const fetchData = useCallback(
    async (forceRefresh = false) => {
      if (!id) return;
      try {
        const result = await fetchResult<DashboardDataResponse>(`/api/dashboards/${id}/data`, {
          method: 'get',
          params: { forceRefresh },
          token: token ?? undefined,
        });
        if (result.code === '0' && result.data) {
          setData(result.data);
          if (result.data.cacheTtl > 0) {
            setRefreshInterval(result.data.cacheTtl);
          }
        }
      } catch (err) {
        console.error('Failed to fetch dashboard data:', err);
      } finally {
        setLoading(false);
      }
    },
    [id, token],
  );

  // Initial load
  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Auto-refresh
  useEffect(() => {
    const interval = setInterval(() => {
      fetchData();
    }, refreshInterval * 1000);
    return () => clearInterval(interval);
  }, [fetchData, refreshInterval]);

  // Clock update
  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  // Fullscreen toggle
  const toggleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      containerRef.current?.requestFullscreen();
      setIsFullscreen(true);
    } else {
      document.exitFullscreen();
      setIsFullscreen(false);
    }
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'f11') {
        e.preventDefault();
        toggleFullscreen();
      }
      if (e.key === 'Escape' && isFullscreen) {
        setIsFullscreen(false);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [isFullscreen, toggleFullscreen]);

  // Listen for fullscreen change events
  useEffect(() => {
    const handler = () => {
      setIsFullscreen(!!document.fullscreenElement);
    };
    document.addEventListener('fullscreenchange', handler);
    return () => document.removeEventListener('fullscreenchange', handler);
  }, []);

  if (loading) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-gray-900">
        <div className="animate-pulse text-xl text-white">Loading Data Screen...</div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="min-h-screen bg-gray-900 p-6 text-white"
      style={{ fontFamily: "'Inter', 'Segoe UI', sans-serif" }}
    >
      {/* Header bar */}
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-blue-400">
          {data?.dashboardTitle || 'Data Screen'}
        </h1>
        <div className="flex items-center gap-4">
          {/* Clock */}
          <div className="font-mono text-lg text-gray-300">{currentTime.toLocaleTimeString()}</div>
          {/* Refresh indicator */}
          <div className="text-sm text-gray-500">Auto-refresh: {refreshInterval}s</div>
          {/* Fullscreen button */}
          <button
            onClick={toggleFullscreen}
            className="rounded bg-gray-700 px-3 py-1 text-sm transition-colors hover:bg-gray-600"
            title="Toggle fullscreen (F11)"
          >
            {isFullscreen ? 'Exit Fullscreen' : 'Fullscreen'}
          </button>
        </div>
      </div>

      {/* Widget grid */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {data?.widgets &&
          Object.entries(data.widgets).map(([widgetId, widgetData]) => (
            <DataScreenWidget key={widgetId} widgetId={widgetId} data={widgetData} />
          ))}
      </div>

      {/* No data state */}
      {(!data?.widgets || Object.keys(data.widgets).length === 0) && (
        <div className="flex h-64 items-center justify-center text-gray-500">
          No widgets configured for this dashboard
        </div>
      )}

      {/* Last updated footer */}
      <div className="fixed right-4 bottom-4 text-xs text-gray-600">
        Last updated: {data?.fetchedAt ? new Date(data.fetchedAt).toLocaleTimeString() : '-'}
      </div>
    </div>
  );
}

/**
 * Individual widget card in the Data Screen.
 * Renders different content based on widget data type.
 */
function DataScreenWidget({ widgetId, data }: { widgetId: string; data: any }) {
  if (data === null || data === undefined) {
    return (
      <div className="rounded-lg border border-gray-700 bg-gray-800 p-4">
        <div className="text-sm text-gray-500">{widgetId}</div>
        <div className="mt-2 text-gray-400">No data</div>
      </div>
    );
  }

  // Number value — show with animated counter style
  if (typeof data === 'number') {
    return (
      <div className="rounded-lg border border-gray-700 bg-gray-800 p-6 transition-colors hover:border-blue-500">
        <div className="mb-2 text-sm text-gray-400">{widgetId}</div>
        <AnimatedNumber value={data} />
      </div>
    );
  }

  // Object with error
  if (data?.error) {
    return (
      <div className="rounded-lg border border-red-700 bg-gray-800 p-4">
        <div className="text-sm text-gray-400">{widgetId}</div>
        <div className="mt-2 text-sm text-red-400">{data.error}</div>
      </div>
    );
  }

  // Array — render as simple table
  if (Array.isArray(data)) {
    return (
      <div className="col-span-2 rounded-lg border border-gray-700 bg-gray-800 p-4">
        <div className="mb-3 text-sm text-gray-400">{widgetId}</div>
        <div className="max-h-64 overflow-auto">
          {data.length > 0 ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-700">
                  {Object.keys(data[0]).map((key) => (
                    <th key={key} className="px-2 py-1 text-left font-medium text-gray-400">
                      {key}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.slice(0, 10).map((row, i) => (
                  <tr key={i} className="border-b border-gray-800">
                    {Object.values(row).map((val, j) => (
                      <td key={j} className="px-2 py-1 text-gray-300">
                        {String(val ?? '')}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="text-gray-500">Empty dataset</div>
          )}
        </div>
      </div>
    );
  }

  // Default: JSON display
  return (
    <div className="rounded-lg border border-gray-700 bg-gray-800 p-4">
      <div className="mb-2 text-sm text-gray-400">{widgetId}</div>
      <pre className="max-h-48 overflow-auto text-xs text-gray-300">
        {JSON.stringify(data, null, 2)}
      </pre>
    </div>
  );
}

/**
 * Animated number counter — counts up from 0 to target value.
 */
function AnimatedNumber({ value }: { value: number }) {
  const [displayed, setDisplayed] = useState(0);

  useEffect(() => {
    const duration = 800; // ms
    const startTime = Date.now();
    const startVal = displayed;

    const animate = () => {
      const elapsed = Date.now() - startTime;
      const progress = Math.min(elapsed / duration, 1);
      // Ease out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplayed(startVal + (value - startVal) * eased);

      if (progress < 1) {
        requestAnimationFrame(animate);
      }
    };

    requestAnimationFrame(animate);
  }, [value]); // eslint-disable-line react-hooks/exhaustive-deps

  const isInteger = Number.isInteger(value);
  const formatted = isInteger
    ? Math.round(displayed).toLocaleString()
    : displayed.toLocaleString(undefined, { maximumFractionDigits: 2 });

  return <div className="text-4xl font-bold text-blue-300 tabular-nums">{formatted}</div>;
}
