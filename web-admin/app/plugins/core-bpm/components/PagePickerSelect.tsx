/**
 * PagePickerSelect — Combobox for selecting published form pages.
 * Fetches available pages from the BPM form-bindings API.
 */

import { useState, useEffect } from 'react';
import { get, ErrorCodes } from '~/shared/services/http-client';

interface FormPageOption {
  pageKey: string;
  pageName: string;
  modelCode: string;
}

interface Props {
  value: string; // selected pageKey
  onChange: (pageKey: string) => void;
}

export function PagePickerSelect({ value, onChange }: Props) {
  const [options, setOptions] = useState<FormPageOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    get<FormPageOption[]>('/api/bpm/form-bindings/pages', {
      params: { pageType: 'form', status: 'published' },
    })
      .then((result) => {
        if (cancelled) return;
        if (result.code === ErrorCodes.SUCCESS) {
          setOptions(result.data || []);
        } else {
          setError(result.desc || 'Failed to load pages'); // TODO: i18n
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.message || 'Network error'); // TODO: i18n
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-sm text-gray-500">
        <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
          />
        </svg>
        {/* TODO: i18n */}
        Loading pages...
      </div>
    );
  }

  if (error) {
    return <div className="text-sm text-red-500">{error}</div>;
  }

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
      data-testid="form-binding-page-select"
    >
      {/* TODO: i18n */}
      <option value="">-- Select a form page --</option>
      {options.map((opt) => (
        <option key={opt.pageKey} value={opt.pageKey}>
          {opt.pageName} ({opt.modelCode})
        </option>
      ))}
    </select>
  );
}
