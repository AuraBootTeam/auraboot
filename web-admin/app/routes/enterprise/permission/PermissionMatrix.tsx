import { useState, useMemo, useCallback, useRef, useEffect } from 'react';
import {
  ChevronRightIcon,
  ChevronDownIcon,
  MinusIcon,
  Cog6ToothIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import type {
  PermissionMatrixDTO,
  PermissionMatrixModuleDTO,
  PermissionMatrixActionDTO,
} from './types';
import PolicyConfigDialog from './PolicyConfigDialog';

// ---------------------------------------------------------------------------
// Standard action ordering
// ---------------------------------------------------------------------------

const STANDARD_ACTIONS = ['read', 'create', 'update', 'delete', 'import', 'export'] as const;

const ACTION_I18N: Record<string, string> = {
  read: 'admin.permission.matrix.action.read',
  create: 'admin.permission.matrix.action.create',
  update: 'admin.permission.matrix.action.update',
  delete: 'admin.permission.matrix.action.delete',
  import: 'admin.permission.matrix.action.import',
  export: 'admin.permission.matrix.action.export',
};

const ACTION_FALLBACK: Record<string, string> = {
  read: 'View',
  create: 'Add',
  update: 'Edit',
  delete: 'Delete',
  import: 'Import',
  export: 'Export',
};

// ---------------------------------------------------------------------------
// Scope configuration
// ---------------------------------------------------------------------------

interface ScopeOption {
  value: string;
  labelKey: string;
  labelFallback: string;
  badge: string;
  color: string;
}

const SCOPE_OPTIONS: ScopeOption[] = [
  {
    value: 'all',
    labelKey: 'admin.permission.scope.all',
    labelFallback: 'All Data',
    badge: 'ALL',
    color: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
  },
  {
    value: 'dept_and_sub',
    labelKey: 'admin.permission.scope.dept_and_sub',
    labelFallback: 'Dept & Sub',
    badge: 'T',
    color: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
  },
  {
    value: 'dept',
    labelKey: 'admin.permission.scope.dept',
    labelFallback: 'Dept Only',
    badge: 'D',
    color: 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300',
  },
  {
    value: 'self',
    labelKey: 'admin.permission.scope.self',
    labelFallback: 'Self Only',
    badge: 'S',
    color: 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300',
  },
  {
    value: 'none',
    labelKey: 'admin.permission.scope.none',
    labelFallback: 'No Access',
    badge: 'N',
    color: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
  },
];

function getScopeOption(scopeType: string | null | undefined): ScopeOption | undefined {
  if (!scopeType || scopeType === 'all') return undefined;
  return SCOPE_OPTIONS.find((o) => o.value === scopeType);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Collect unique action codes for a single module, sorted: standard first, custom alphabetically.
 */
function collectModuleActions(mod: PermissionMatrixModuleDTO): string[] {
  const actionSet = new Set<string>();
  for (const res of mod.resources) {
    for (const act of res.actions) {
      actionSet.add(act.action);
    }
  }
  const standard = (STANDARD_ACTIONS as readonly string[]).filter((a) => actionSet.has(a));
  const custom = [...actionSet]
    .filter((a) => !(STANDARD_ACTIONS as readonly string[]).includes(a))
    .sort();
  return [...standard, ...custom];
}

// ---------------------------------------------------------------------------
// ScopeBadge
// ---------------------------------------------------------------------------

interface ScopeBadgeProps {
  resourceCode: string;
  actionCode: string;
  scopeType: string | null | undefined;
  onSelect: (scopeType: string) => void;
}

function ScopeBadge({ resourceCode, actionCode, scopeType, onSelect }: ScopeBadgeProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const badgeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        badgeRef.current &&
        !badgeRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const current = getScopeOption(scopeType);

  return (
    <div className="relative inline-flex">
      <button
        ref={badgeRef}
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
        title={
          current
            ? t(current.labelKey) || current.labelFallback
            : t('admin.permission.scope.all') || 'All Data'
        }
        className={`ml-1 inline-flex items-center rounded px-1 py-0.5 text-[10px] font-semibold leading-none transition-opacity hover:opacity-80 focus:outline-none ${
          current
            ? current.color
            : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
        }`}
        data-testid={`scope-badge-${resourceCode}-${actionCode}`}
      >
        {current ? current.badge : 'ALL'}
      </button>

      {open && (
        <div
          ref={dropdownRef}
          className="absolute left-0 top-full z-50 mt-1 min-w-[140px] rounded-md border border-gray-200 bg-white shadow-lg dark:border-gray-700 dark:bg-gray-800"
          data-testid={`scope-dropdown-${resourceCode}-${actionCode}`}
        >
          <div className="px-2 py-1 text-[10px] font-medium uppercase tracking-wide text-gray-400">
            {t('admin.permission.scope.label') || 'Data Scope'}
          </div>
          {SCOPE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                onSelect(opt.value);
                setOpen(false);
              }}
              className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-gray-50 dark:hover:bg-gray-700 ${
                scopeType === opt.value || (!scopeType && opt.value === 'all')
                  ? 'font-semibold text-blue-600 dark:text-blue-400'
                  : 'text-gray-700 dark:text-gray-300'
              }`}
              data-testid={`scope-option-${resourceCode}-${actionCode}-${opt.value}`}
            >
              <span
                className={`inline-flex w-5 items-center justify-center rounded px-0.5 py-0.5 text-[9px] font-bold ${opt.color}`}
              >
                {opt.badge}
              </span>
              {t(opt.labelKey) || opt.labelFallback}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface PermissionMatrixProps {
  data: PermissionMatrixDTO;
  rolePid?: string;
  onToggle: (permissionId: number, granted: boolean) => void;
  onScopeChange?: (
    resourceCode: string,
    actionCode: string,
    scopeType: string,
    mergeStrategy?: string,
  ) => void;
  loading?: boolean;
}

// ---------------------------------------------------------------------------
// Policy dialog state
// ---------------------------------------------------------------------------

interface PolicyDialogState {
  permissionPid: string;
  permissionLabel: string;
  schema: Record<string, any>;
  initialValues?: Record<string, any>;
}

// ---------------------------------------------------------------------------
// ActionCell — single checkbox + scope badge + policy gear
// ---------------------------------------------------------------------------

interface ActionCellProps {
  act: PermissionMatrixActionDTO | undefined;
  resourceCode: string;
  action: string;
  onToggle: (permissionId: number, granted: boolean) => void;
  onScopeChange?: (resourceCode: string, actionCode: string, scopeType: string) => void;
  onPolicyClick: (state: PolicyDialogState) => void;
}

function ActionCell({ act, resourceCode, action, onToggle, onScopeChange, onPolicyClick }: ActionCellProps) {
  if (!act || !act.supported) {
    return (
      <td className="px-3 py-2 text-center" data-testid={`matrix-cell-${resourceCode}-${action}`}>
        <MinusIcon className="mx-auto h-3.5 w-3.5 text-gray-300" />
      </td>
    );
  }

  const parsedPolicySchema = act.policySchema
    ? (() => {
        try {
          return JSON.parse(act.policySchema);
        } catch {
          return null;
        }
      })()
    : null;

  return (
    <td className="px-3 py-2 text-center" data-testid={`matrix-cell-${resourceCode}-${action}`}>
      <div className="flex items-center justify-center gap-1">
        <input
          type="checkbox"
          checked={act.granted}
          onChange={() => onToggle(act.permissionId, !act.granted)}
          className="h-4 w-4 cursor-pointer rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          data-testid={`matrix-checkbox-${resourceCode}-${action}`}
        />
        {act.granted && onScopeChange && (
          <ScopeBadge
            resourceCode={resourceCode}
            actionCode={act.action}
            scopeType={act.scopeType}
            onSelect={(scopeType) => onScopeChange(resourceCode, act.action, scopeType)}
          />
        )}
        {act.granted && parsedPolicySchema && (
          <button
            type="button"
            title="Configure policy"
            onClick={(e) => {
              e.stopPropagation();
              onPolicyClick({
                permissionPid: act.permissionPid,
                permissionLabel: act.label || act.code,
                schema: parsedPolicySchema,
                initialValues: act.policyValues,
              });
            }}
            className="ml-0.5 inline-flex items-center rounded p-0.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-blue-600 focus:outline-none dark:hover:bg-gray-700 dark:hover:text-blue-400"
            data-testid={`matrix-policy-gear-${resourceCode}-${action}`}
          >
            <Cog6ToothIcon className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </td>
  );
}

// ---------------------------------------------------------------------------
// ModuleSection — one module with its own column headers
// ---------------------------------------------------------------------------

interface ModuleSectionProps {
  mod: PermissionMatrixModuleDTO;
  collapsed: boolean;
  onToggleCollapse: () => void;
  onToggle: (permissionId: number, granted: boolean) => void;
  onScopeChange?: (resourceCode: string, actionCode: string, scopeType: string) => void;
  onPolicyClick: (state: PolicyDialogState) => void;
}

function ModuleSection({
  mod,
  collapsed,
  onToggleCollapse,
  onToggle,
  onScopeChange,
  onPolicyClick,
}: ModuleSectionProps) {
  const { t } = useI18n();

  const moduleActions = useMemo(() => collectModuleActions(mod), [mod]);

  // Build action lookup per resource: resourceCode -> action -> DTO
  const actionMap = useMemo(() => {
    const map = new Map<string, Map<string, PermissionMatrixActionDTO>>();
    for (const res of mod.resources) {
      const m = new Map<string, PermissionMatrixActionDTO>();
      for (const act of res.actions) {
        m.set(act.action, act);
      }
      map.set(res.resourceCode, m);
    }
    return map;
  }, [mod]);

  return (
    <div className="mb-4" data-testid={`matrix-module-${mod.moduleCode}`}>
      {/* Module header */}
      <button
        onClick={onToggleCollapse}
        className="flex w-full items-center gap-2 rounded-t-lg border border-gray-200 bg-gray-50 px-4 py-2.5 text-left transition-colors hover:bg-gray-100 dark:border-gray-700 dark:bg-gray-800 dark:hover:bg-gray-750"
        data-testid={`matrix-module-toggle-${mod.moduleCode}`}
      >
        {collapsed ? (
          <ChevronRightIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
        ) : (
          <ChevronDownIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
        )}
        <span className="text-sm font-semibold text-gray-900 dark:text-white">
          {mod.moduleName}
        </span>
        <span className="text-xs text-gray-400">({mod.resources.length})</span>
      </button>

      {/* Module table — only visible when expanded */}
      {!collapsed && (
        <div className="overflow-x-auto rounded-b-lg border border-t-0 border-gray-200 dark:border-gray-700">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50/50 dark:border-gray-700 dark:bg-gray-800/50">
                <th className="w-[240px] min-w-[180px] px-4 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                  {t('admin.permission.matrix.resource', undefined, 'Resource')}
                </th>
                {moduleActions.map((action) => (
                  <th
                    key={action}
                    className="min-w-[80px] px-3 py-2 text-center text-xs font-medium uppercase tracking-wider text-gray-500"
                  >
                    {t(
                      ACTION_I18N[action] || `admin.permission.matrix.action.${action}`,
                      undefined,
                      ACTION_FALLBACK[action] || action,
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {mod.resources.map((res) => {
                const resActions = actionMap.get(res.resourceCode);
                return (
                  <tr
                    key={res.resourceCode}
                    className="border-b border-gray-100 transition-colors hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
                    data-testid={`matrix-row-${res.resourceCode}`}
                  >
                    <td className="px-4 py-2 text-gray-700 dark:text-gray-300">
                      {res.resourceName}
                    </td>
                    {moduleActions.map((action) => (
                      <ActionCell
                        key={action}
                        act={resActions?.get(action)}
                        resourceCode={res.resourceCode}
                        action={action}
                        onToggle={onToggle}
                        onScopeChange={onScopeChange}
                        onPolicyClick={onPolicyClick}
                      />
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function PermissionMatrix({
  data,
  rolePid,
  onToggle,
  onScopeChange,
  loading,
}: PermissionMatrixProps) {
  const { t } = useI18n();
  const [collapsedModules, setCollapsedModules] = useState<Set<string>>(new Set());
  const [policyDialog, setPolicyDialog] = useState<PolicyDialogState | null>(null);

  const toggleModule = useCallback((moduleCode: string) => {
    setCollapsedModules((prev) => {
      const next = new Set(prev);
      if (next.has(moduleCode)) {
        next.delete(moduleCode);
      } else {
        next.add(moduleCode);
      }
      return next;
    });
  }, []);

  if (data.modules.length === 0) {
    return (
      <div className="flex items-center justify-center py-16 text-sm text-gray-400">
        {t('admin.permission.matrix.empty', undefined, 'No permissions available')}
      </div>
    );
  }

  return (
    <>
      <div
        data-testid="permission-matrix"
        className={`space-y-0 ${loading ? 'pointer-events-none opacity-60' : ''}`}
      >
        {data.modules.map((mod) => (
          <ModuleSection
            key={mod.moduleCode}
            mod={mod}
            collapsed={collapsedModules.has(mod.moduleCode)}
            onToggleCollapse={() => toggleModule(mod.moduleCode)}
            onToggle={onToggle}
            onScopeChange={onScopeChange}
            onPolicyClick={setPolicyDialog}
          />
        ))}
      </div>

      {/* Policy configuration dialog */}
      {policyDialog && rolePid && (
        <PolicyConfigDialog
          open={policyDialog !== null}
          onClose={() => setPolicyDialog(null)}
          rolePid={rolePid}
          permissionPid={policyDialog.permissionPid}
          permissionLabel={policyDialog.permissionLabel}
          schema={policyDialog.schema}
          initialValues={policyDialog.initialValues}
          onSuccess={() => setPolicyDialog(null)}
        />
      )}
    </>
  );
}
