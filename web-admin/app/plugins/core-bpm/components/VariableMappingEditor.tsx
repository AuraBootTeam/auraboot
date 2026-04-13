/**
 * VariableMappingEditor — Table editor for field → process variable mapping.
 * Fetches page fields and lets user map each field to a process variable name.
 */

import { useState, useEffect } from 'react';
import { get, ErrorCodes } from '~/services/http-client';

interface FieldInfo {
  fieldCode: string;
  fieldLabel: string;
  fieldType: string;
}

interface Props {
  pageKey: string;
  bindings: Record<string, string>; // fieldCode → variableName
  onChange: (bindings: Record<string, string>) => void;
}

export function VariableMappingEditor({ pageKey, bindings, onChange }: Props) {
  const [fields, setFields] = useState<FieldInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!pageKey) {
      setFields([]);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    get<FieldInfo[]>(`/api/bpm/form-bindings/pages/${pageKey}/fields`)
      .then((result) => {
        if (cancelled) return;
        if (result.code === ErrorCodes.SUCCESS) {
          setFields(result.data || []);
        } else {
          setError(result.desc || 'Failed to load fields'); // TODO: i18n
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
  }, [pageKey]);

  const handleVariableChange = (fieldCode: string, variableName: string) => {
    const updated = { ...bindings };
    if (variableName.trim()) {
      updated[fieldCode] = variableName.trim();
    } else {
      delete updated[fieldCode];
    }
    onChange(updated);
  };

  if (loading) {
    return (
      <div className="py-2 text-sm text-gray-500">
        {/* TODO: i18n */}
        Loading fields...
      </div>
    );
  }

  if (error) {
    return <div className="py-2 text-sm text-red-500">{error}</div>;
  }

  if (fields.length === 0) {
    return (
      <div className="py-2 text-sm text-gray-400">
        {/* TODO: i18n */}
        No fields available for this page.
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* Header */}
      <div className="grid grid-cols-2 gap-2 text-xs font-medium text-gray-500">
        {/* TODO: i18n */}
        <span>Field</span>
        <span>Process Variable</span>
      </div>
      {/* Field rows */}
      {fields.map((field) => (
        <div key={field.fieldCode} className="grid grid-cols-2 items-center gap-2">
          <span className="truncate text-sm text-gray-700" title={field.fieldCode}>
            {field.fieldLabel || field.fieldCode}
            <span className="ml-1 text-xs text-gray-400">({field.fieldType})</span>
          </span>
          <input
            type="text"
            value={bindings[field.fieldCode] || ''}
            onChange={(e) => handleVariableChange(field.fieldCode, e.target.value)}
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
            placeholder={field.fieldCode}
          />
        </div>
      ))}
    </div>
  );
}
