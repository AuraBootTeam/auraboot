/**
 * FieldPermissionSection — role-based view/edit permission editor for DSL fields.
 *
 * Stores the result in the field override's props.fieldPermission:
 * {
 *   "view": ["admin", "sales_manager"],   // empty = all roles
 *   "edit": ["admin"]                     // empty = all roles
 * }
 *
 * Appears as a collapsible "Permissions" section in FieldPropertyEditor.
 */

import React, { useCallback, useEffect, useState } from 'react';

export interface FieldPermissionValue {
  view: string[];
  edit: string[];
}

interface RoleOption {
  label: string;
  value: string;
}

interface FieldPermissionSectionProps {
  /** Current value, may be undefined if not yet configured. */
  value?: FieldPermissionValue;
  /** Called when view or edit roles change. Passes null to clear the permission. */
  onChange: (next: FieldPermissionValue | null) => void;
  /** When true, inputs are read-only. */
  disabled?: boolean;
}

/** Fetch all active roles from the API. */
async function loadRoles(): Promise<RoleOption[]> {
  try {
    const resp = await fetch('/api/roles/all', { credentials: 'include' });
    if (!resp.ok) return [];
    const json = await resp.json();
    const list: Array<{ code: string; name: string }> = json?.data ?? [];
    return list.map((r) => ({ label: r.name || r.code, value: r.code }));
  } catch {
    return [];
  }
}

/**
 * A minimal multi-select that renders checkboxes in a scrollable list.
 * Avoids heavy UI library dependencies while still being usable.
 */
const RoleMultiSelect: React.FC<{
  label: string;
  hint: string;
  options: RoleOption[];
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
}> = ({ label, hint, options, selected, onChange, disabled }) => {
  const toggle = useCallback(
    (code: string) => {
      if (disabled) return;
      const next = selected.includes(code)
        ? selected.filter((c) => c !== code)
        : [...selected, code];
      onChange(next);
    },
    [disabled, selected, onChange],
  );

  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <label className="text-xs font-medium text-gray-600">{label}</label>
        <span className="text-xs text-gray-400">{hint}</span>
      </div>
      {options.length === 0 ? (
        <div className="rounded-md border border-dashed border-gray-200 px-3 py-2 text-xs text-gray-400">
          No roles available
        </div>
      ) : (
        <div className="max-h-32 overflow-y-auto rounded-md border border-gray-200 bg-white">
          {options.map((opt) => {
            const checked = selected.includes(opt.value);
            return (
              <label
                key={opt.value}
                className={`flex cursor-pointer items-center gap-2 px-2 py-1.5 text-xs transition-colors hover:bg-gray-50 ${
                  disabled ? 'cursor-not-allowed opacity-50' : ''
                }`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(opt.value)}
                  disabled={disabled}
                  className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="flex-1 text-gray-700">{opt.label}</span>
                <span className="font-mono text-gray-400">{opt.value}</span>
              </label>
            );
          })}
        </div>
      )}
      {selected.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">
          {selected.map((code) => (
            <span
              key={code}
              className="inline-flex items-center gap-1 rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700"
            >
              {code}
              {!disabled && (
                <button
                  type="button"
                  onClick={() => toggle(code)}
                  className="text-blue-400 hover:text-blue-600"
                  aria-label={`Remove ${code}`}
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      )}
    </div>
  );
};

export const FieldPermissionSection: React.FC<FieldPermissionSectionProps> = ({
  value,
  onChange,
  disabled,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [roles, setRoles] = useState<RoleOption[]>([]);
  const [loading, setLoading] = useState(false);

  // Load roles when section is first expanded
  useEffect(() => {
    if (!isExpanded || roles.length > 0) return;
    setLoading(true);
    loadRoles()
      .then(setRoles)
      .finally(() => setLoading(false));
  }, [isExpanded, roles.length]);

  const viewRoles: string[] = value?.view ?? [];
  const editRoles: string[] = value?.edit ?? [];

  const handleViewChange = useCallback(
    (next: string[]) => {
      const updated: FieldPermissionValue = { view: next, edit: editRoles };
      // If both are empty, clear the permission entirely
      onChange(next.length === 0 && editRoles.length === 0 ? null : updated);
    },
    [editRoles, onChange],
  );

  const handleEditChange = useCallback(
    (next: string[]) => {
      const updated: FieldPermissionValue = { view: viewRoles, edit: next };
      onChange(viewRoles.length === 0 && next.length === 0 ? null : updated);
    },
    [viewRoles, onChange],
  );

  const handleClear = useCallback(() => {
    onChange(null);
  }, [onChange]);

  const hasPermission = viewRoles.length > 0 || editRoles.length > 0;

  return (
    <div className="mb-3" data-testid="field-permission-section">
      {/* Section header */}
      <button
        type="button"
        className="flex w-full items-center justify-between rounded-t border border-gray-200 bg-gray-50 p-2 transition-colors hover:bg-gray-100"
        onClick={() => setIsExpanded((v) => !v)}
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-700">Permissions</span>
          {hasPermission && (
            <span className="rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-600">
              {viewRoles.length > 0 ? `view: ${viewRoles.length}` : ''}
              {viewRoles.length > 0 && editRoles.length > 0 ? ' · ' : ''}
              {editRoles.length > 0 ? `edit: ${editRoles.length}` : ''}
            </span>
          )}
        </div>
        <svg
          className={`h-4 w-4 transform text-gray-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M19 9l-7 7-7-7"
          />
        </svg>
      </button>

      {isExpanded && (
        <div className="rounded-b border border-t-0 border-gray-200 bg-white p-3">
          {loading ? (
            <div className="flex items-center justify-center py-4 text-xs text-gray-400">
              Loading roles…
            </div>
          ) : (
            <div className="flex flex-col gap-4">
              {/* Description */}
              <p className="text-xs text-gray-400">
                Restrict who can view or edit this field. Empty = all roles have access.
              </p>

              <RoleMultiSelect
                label="View Roles"
                hint="who can see this field"
                options={roles}
                selected={viewRoles}
                onChange={handleViewChange}
                disabled={disabled}
              />

              <RoleMultiSelect
                label="Edit Roles"
                hint="who can modify this field"
                options={roles}
                selected={editRoles}
                onChange={handleEditChange}
                disabled={disabled}
              />

              {hasPermission && !disabled && (
                <button
                  type="button"
                  onClick={handleClear}
                  className="w-full rounded border border-red-200 bg-red-50 px-2 py-1 text-xs text-red-600 transition-colors hover:bg-red-100"
                >
                  Clear all permissions (restore default access)
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default FieldPermissionSection;
