import { useState, useEffect, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { fetchResult } from '~/shared/services/http-client';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import PermissionTree from './PermissionTree';
import type { Role, PermissionTreeNode } from './types';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface AssignmentTabProps {
  preSelectedRole?: Role | null;
}

// ---------------------------------------------------------------------------
// PID <-> ID conversion helpers
// ---------------------------------------------------------------------------

const getPermissionIdByPid = (pid: string, tree: PermissionTreeNode[]): string | number | null => {
  for (const node of tree) {
    if (node.pid === pid) return node.id;
    if (node.children) {
      const found = getPermissionIdByPid(pid, node.children);
      if (found !== null) return found;
    }
  }
  return null;
};

const getPermissionPidById = (id: string | number, tree: PermissionTreeNode[]): string | null => {
  for (const node of tree) {
    if (node.id === id) return node.pid;
    if (node.children) {
      const found = getPermissionPidById(id, node.children);
      if (found) return found;
    }
  }
  return null;
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function AssignmentTab({ preSelectedRole }: AssignmentTabProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();

  const [roles, setRoles] = useState<Role[]>([]);
  const [permissionTree, setPermissionTree] = useState<PermissionTreeNode[]>([]);
  const [selectedRole, setSelectedRole] = useState<Role | null>(null);
  const [rolePermissions, setRolePermissions] = useState<(string | number)[]>([]);
  const [loading, setLoading] = useState(false);
  const [treeLoading, setTreeLoading] = useState(false);

  // ---- Fetch roles & permission tree on mount ----
  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      try {
        const [rolesResult, treeResult] = await Promise.all([
          fetchResult<{ records: Role[] }>('/api/roles?pageSize=100', { method: 'get' }),
          fetchResult<PermissionTreeNode[]>('/api/permissions/tree', { method: 'get' }),
        ]);

        if (rolesResult.code === '0' && rolesResult.data) {
          setRoles(rolesResult.data.records);
        }
        if (treeResult.code === '0' && treeResult.data) {
          setPermissionTree(treeResult.data);
        }
      } catch {
        showErrorToast(t('admin.permission.assign.loadError') || 'Failed to load data');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, [showErrorToast, t]);

  // ---- Auto-select from preSelectedRole ----
  useEffect(() => {
    if (preSelectedRole) {
      setSelectedRole(preSelectedRole);
    }
  }, [preSelectedRole]);

  // ---- Fetch role permissions when selectedRole changes ----
  useEffect(() => {
    if (!selectedRole || permissionTree.length === 0) {
      setRolePermissions([]);
      return;
    }

    const fetchRolePermissions = async () => {
      setTreeLoading(true);
      try {
        const result = await fetchResult<string[]>(`/api/roles/${selectedRole.pid}/permissions`, {
          method: 'get',
        });
        if (result.code === '0' && result.data) {
          // Convert PIDs from API to internal IDs used by the tree
          const ids = result.data
            .map((pid) => getPermissionIdByPid(pid, permissionTree))
            .filter((id): id is string | number => id !== null);
          setRolePermissions(ids);
        }
      } catch {
        showErrorToast(t('admin.permission.assign.loadError') || 'Failed to load permissions');
      } finally {
        setTreeLoading(false);
      }
    };
    fetchRolePermissions();
  }, [selectedRole, permissionTree]);

  // ---- Save handler ----
  const handleSave = useCallback(async () => {
    if (!selectedRole) return;

    // Convert internal IDs to PIDs for the API
    const permissionPids = rolePermissions
      .map((id) => getPermissionPidById(id, permissionTree))
      .filter((pid): pid is string => pid !== null);

    setTreeLoading(true);
    try {
      const result = await fetchResult<boolean>(`/api/roles/${selectedRole.pid}/permissions`, {
        method: 'post',
        params: permissionPids,
      });
      handleSubmitResult(result, {
        onSuccess: () => showSuccessToast(t('admin.permission.assign.save.success') || 'Saved'),
        onError: (error) => showErrorToast(error || 'Save failed'),
        showToast: false,
      });
    } catch {
      showErrorToast(t('admin.permission.assign.saveError') || 'Save failed');
    } finally {
      setTreeLoading(false);
    }
  }, [
    selectedRole,
    rolePermissions,
    permissionTree,
    handleSubmitResult,
    showSuccessToast,
    showErrorToast,
    t,
  ]);

  // ---- Loading state ----
  if (loading) {
    return (
      <div data-testid="assignment-tab" className="flex items-center justify-center py-24">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div data-testid="assignment-tab" className="grid gap-6 lg:grid-cols-2">
      {/* Left panel — Role list */}
      <div>
        <h3 className="mb-3 text-sm font-semibold text-gray-700">
          {t('admin.permission.assign.roleList')}
        </h3>
        <div className="max-h-96 space-y-2 overflow-y-auto pr-1">
          {roles.map((role) => {
            const isSelected = selectedRole?.pid === role.pid;
            return (
              <button
                key={role.pid}
                data-testid={`assignment-role-${role.code}`}
                onClick={() => setSelectedRole(role)}
                className={`w-full rounded-lg border p-3 text-left transition-colors ${
                  isSelected
                    ? 'border-blue-500 bg-blue-50'
                    : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50'
                }`}
              >
                <div className="text-sm font-medium text-gray-900">{role.name}</div>
                {role.description && (
                  <div className="mt-0.5 text-xs text-gray-500">{role.description}</div>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {/* Right panel — Permission tree */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-700">
            {selectedRole
              ? t('admin.permission.assign.forRole', { name: selectedRole.name })
              : t('admin.permission.assign.permissionTree')}
          </h3>
          {selectedRole && (
            <div className="flex items-center gap-2">
              <span
                data-testid="assignment-selected-count"
                className="rounded-full bg-blue-100 px-2.5 py-0.5 text-xs font-medium text-blue-700"
              >
                {t('admin.permission.assign.selected', { count: rolePermissions.length })}
              </span>
              <button
                data-testid="assignment-save-btn"
                onClick={handleSave}
                disabled={treeLoading}
                className="rounded-md bg-blue-600 px-4 py-1.5 text-xs font-medium text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {t('admin.permission.assign.save')}
              </button>
            </div>
          )}
        </div>

        {selectedRole ? (
          <PermissionTree
            nodes={permissionTree}
            selectedIds={rolePermissions}
            onSelectionChange={setRolePermissions}
            loading={treeLoading}
          />
        ) : (
          <div className="flex items-center justify-center rounded-lg border border-dashed border-gray-300 py-24 text-sm text-gray-400">
            {t('admin.permission.assign.selectRole')}
          </div>
        )}
      </div>
    </div>
  );
}
