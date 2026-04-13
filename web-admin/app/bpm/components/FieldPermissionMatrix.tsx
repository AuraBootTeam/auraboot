/**
 * FieldPermissionMatrix — Radio matrix for field permissions per node.
 * Shows editable / readonly / hidden radio buttons for each form field.
 */

import { useState, useEffect } from 'react';
import { get, ErrorCodes } from '~/services/http-client';

type FieldPermission = 'editable' | 'readonly' | 'hidden';

interface FieldInfo {
  fieldCode: string;
  fieldLabel: string;
  fieldType: string;
}

interface Props {
  pageKey: string;
  permissions: Record<string, FieldPermission>;
  onChange: (permissions: Record<string, FieldPermission>) => void;
}

const PERMISSION_OPTIONS: { value: FieldPermission; label: string }[] = [
  { value: 'editable', label: 'Editable' }, // TODO: i18n
  { value: 'readonly', label: 'Readonly' }, // TODO: i18n
  { value: 'hidden', label: 'Hidden' }, // TODO: i18n
];

export function FieldPermissionMatrix({ pageKey, permissions, onChange }: Props) {
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

  const handlePermissionChange = (fieldCode: string, permission: FieldPermission) => {
    onChange({ ...permissions, [fieldCode]: permission });
  };

  const setAllPermissions = (permission: FieldPermission) => {
    const updated: Record<string, FieldPermission> = {};
    fields.forEach((f) => {
      updated[f.fieldCode] = permission;
    });
    onChange(updated);
  };

  const clearAllPermissions = () => {
    onChange({});
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
    <div className="space-y-3">
      {/* Quick actions */}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => setAllPermissions('editable')}
          className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
        >
          {/* TODO: i18n */}
          All Editable
        </button>
        <button
          type="button"
          onClick={() => setAllPermissions('readonly')}
          className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
        >
          {/* TODO: i18n */}
          All Readonly
        </button>
        <button
          type="button"
          onClick={clearAllPermissions}
          className="rounded border border-gray-300 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
        >
          {/* TODO: i18n */}
          Inherit Default
        </button>
      </div>

      {/* Header */}
      <div className="grid grid-cols-[1fr_auto] items-center gap-2 text-xs font-medium text-gray-500">
        {/* TODO: i18n */}
        <span>Field</span>
        <div className="grid grid-cols-3 gap-3">
          {PERMISSION_OPTIONS.map((opt) => (
            <span key={opt.value} className="w-16 text-center">
              {opt.label}
            </span>
          ))}
        </div>
      </div>

      {/* Field rows */}
      {fields.map((field) => (
        <div key={field.fieldCode} className="grid grid-cols-[1fr_auto] items-center gap-2">
          <span className="truncate text-sm text-gray-700" title={field.fieldCode}>
            {field.fieldLabel || field.fieldCode}
          </span>
          <div className="grid grid-cols-3 gap-3">
            {PERMISSION_OPTIONS.map((opt) => (
              <label key={opt.value} className="flex w-16 items-center justify-center">
                <input
                  type="radio"
                  name={`perm_${field.fieldCode}`}
                  checked={
                    permissions[field.fieldCode] === opt.value ||
                    (!permissions[field.fieldCode] && opt.value === 'editable')
                  }
                  onChange={() => handlePermissionChange(field.fieldCode, opt.value)}
                  className="h-3.5 w-3.5"
                />
              </label>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
