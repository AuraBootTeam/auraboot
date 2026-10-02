/**
 * Data-scope tiers shared by the v2 permission surfaces (the ② data-scope bar/drawer and the ③
 * advanced atomic-actions table). Missing and unknown values are diagnostic states, never all.
 */
export interface ScopeOption {
  value: string;
  labelKey: string;
  labelFallback: string;
  badge: string;
  /** Tailwind classes for the badge chip. */
  color: string;
}

export const SCOPE_OPTIONS: ScopeOption[] = [
  {
    value: 'all',
    labelKey: 'admin.permission.scope.all',
    labelFallback: '全公司',
    badge: 'ALL',
    color: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  },
  {
    value: 'dept_and_sub',
    labelKey: 'admin.permission.scope.dept_and_sub',
    labelFallback: '本部门及下属',
    badge: 'T',
    color: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
  },
  {
    value: 'team',
    labelKey: 'admin.permission.scope.team',
    labelFallback: 'My teams',
    badge: 'G',
    color: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
  },
  {
    value: 'dept',
    labelKey: 'admin.permission.scope.dept',
    labelFallback: '本部门',
    badge: 'D',
    color: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  },
  {
    value: 'self',
    labelKey: 'admin.permission.scope.self',
    labelFallback: '仅本人',
    badge: 'S',
    color: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
  },
  {
    value: 'none',
    labelKey: 'admin.permission.scope.none',
    labelFallback: '无权访问',
    badge: 'N',
    color: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  },
];

/** Preserve absence and invalid data without presenting either as an unrestricted grant. */
export function normalizeScope(scopeType: string | null | undefined): string {
  if (scopeType == null) return 'not_configured';
  return isValidScope(scopeType) ? scopeType : 'invalid';
}

export function isValidScope(scopeType: string | null | undefined): scopeType is string {
  return SCOPE_OPTIONS.some((option) => option.value === scopeType);
}

export function scopeOption(scopeType: string | null | undefined): ScopeOption {
  const option = SCOPE_OPTIONS.find((o) => o.value === scopeType);
  if (option) return option;
  const state = scopeType == null ? 'not_configured' : scopeType;
  const labels: Record<string, string> = {
    not_configured: 'Not configured',
    mixed: 'Multiple scopes',
    no_grants: 'No granted actions',
    unavailable: 'Scope unavailable',
    invalid: 'Invalid scope configuration',
  };
  const value = state in labels ? state : 'invalid';
  return {
    value,
    labelKey: `admin.permission.scope.${value}`,
    labelFallback: labels[value],
    badge: '—',
    color: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300',
  };
}
