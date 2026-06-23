/**
 * Data-scope tiers shared by the v2 permission surfaces (the ② data-scope bar/drawer and the ③
 * advanced atomic-actions table). A permission with no stored scope is treated as "all" (full
 * company). Mirrors the backend RoleDataScope.scopeType values.
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

/** Normalize a stored scope value (null/empty -> 'all'). */
export function normalizeScope(scopeType: string | null | undefined): string {
  return scopeType && scopeType.trim() ? scopeType : 'all';
}

export function scopeOption(scopeType: string | null | undefined): ScopeOption {
  const v = normalizeScope(scopeType);
  return SCOPE_OPTIONS.find((o) => o.value === v) ?? SCOPE_OPTIONS[0];
}
