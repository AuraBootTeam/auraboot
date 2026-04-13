/**
 * Public Shared View Page — GAP-121
 *
 * Renders a shared view without authentication.
 * Accessed via /share/{token} — fetches data from /api/views/shared/{token}.
 */

import { useState, useEffect } from 'react';
import { useParams, useSearchParams } from 'react-router';

interface SharedViewData {
  viewName: string;
  modelCode: string;
  viewType: string;
  columns: Array<{ code: string; label: string }>;
  records: Array<Record<string, any>>;
}

export default function SharedViewPage() {
  const { token } = useParams<{ token: string }>();
  const [searchParams] = useSearchParams();

  const [data, setData] = useState<SharedViewData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [passwordRequired, setPasswordRequired] = useState(false);
  const [password, setPassword] = useState('');

  const fetchSharedView = async (pwd?: string) => {
    setLoading(true);
    setError(null);
    try {
      const url = pwd
        ? `/api/views/shared/${token}?password=${encodeURIComponent(pwd)}`
        : `/api/views/shared/${token}`;
      const resp = await fetch(url);
      const json = await resp.json();

      if (resp.status === 401 || json?.message?.includes('password')) {
        setPasswordRequired(true);
        setLoading(false);
        return;
      }

      if (resp.status === 404 || resp.status === 410) {
        setError(json?.message || 'This shared link has expired or been revoked.');
        setLoading(false);
        return;
      }

      if (!resp.ok) {
        setError(json?.message || 'Failed to load shared view.');
        setLoading(false);
        return;
      }

      setData(json.data);
      setPasswordRequired(false);
    } catch {
      setError('Network error. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (token) {
      const pwd = searchParams.get('password');
      fetchSharedView(pwd || undefined);
    }
  }, [token]);

  // Password prompt
  if (passwordRequired) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <div className="w-full max-w-sm rounded-xl border border-gray-200 bg-white p-6 shadow-lg">
          <div className="mb-4 text-center">
            <svg
              className="mx-auto h-10 w-10 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              />
            </svg>
            <h2 className="mt-2 text-lg font-semibold text-gray-900">Password Required</h2>
            <p className="mt-1 text-sm text-gray-500">This shared view is password protected.</p>
          </div>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && fetchSharedView(password)}
            placeholder="Enter password"
            className="w-full rounded-lg border border-gray-300 px-4 py-2 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
            autoFocus
          />
          <button
            onClick={() => fetchSharedView(password)}
            className="mt-3 w-full rounded-lg bg-blue-600 py-2 text-sm font-medium text-white hover:bg-blue-700"
          >
            Access View
          </button>
        </div>
      </div>
    );
  }

  // Loading
  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  // Error
  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <div className="text-center">
          <svg
            className="mx-auto h-12 w-12 text-gray-300"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
            />
          </svg>
          <h2 className="mt-3 text-lg font-semibold text-gray-700">{error}</h2>
          <p className="mt-1 text-sm text-gray-400">The link may have expired or been revoked.</p>
        </div>
      </div>
    );
  }

  // Render shared view data
  if (!data) return null;

  return (
    <div className="min-h-screen bg-gray-50 p-6">
      <div className="mx-auto max-w-6xl">
        {/* Header */}
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-gray-900">{data.viewName}</h1>
            <p className="text-sm text-gray-500">
              Shared view — {data.viewType} · {data.modelCode}
            </p>
          </div>
          <div className="rounded-full bg-blue-100 px-3 py-1 text-xs font-medium text-blue-700">
            Read Only
          </div>
        </div>

        {/* Data Table */}
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-xs tracking-wide text-gray-500 uppercase">
              <tr>
                {data.columns?.map((col) => (
                  <th key={col.code} className="px-4 py-3 text-left">
                    {col.label || col.code}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {data.records?.length === 0 ? (
                <tr>
                  <td
                    colSpan={data.columns?.length || 1}
                    className="px-4 py-8 text-center text-gray-400"
                  >
                    No data available
                  </td>
                </tr>
              ) : (
                data.records?.map((record, idx) => (
                  <tr key={record.pid || idx} className="hover:bg-gray-50">
                    {data.columns?.map((col) => (
                      <td key={col.code} className="px-4 py-3 text-gray-700">
                        {String(record[col.code] ?? '')}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Footer */}
        <div className="mt-4 text-center text-xs text-gray-400">
          Powered by AuraBoot · {data.records?.length || 0} records
        </div>
      </div>
    </div>
  );
}
