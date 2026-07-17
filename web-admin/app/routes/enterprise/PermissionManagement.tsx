import { useState, useEffect, useCallback, useMemo } from 'react';
import { useLocation } from 'react-router';
import {
  ShieldCheckIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  MagnifyingGlassIcon,
  UsersIcon,
  PowerIcon,
  ClockIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { fetchResult } from '~/shared/services/http-client';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import ConfirmDialog from '~/ui/ConfirmDialog';
import RoleFormDialog from './permission/RoleFormDialog';
import CapabilityRoleEditor from './permission/capability/CapabilityRoleEditor';
import RoleMemberTab from './permission/RoleMemberTab';
import PermissionAuditTab from './permission/PermissionAuditTab';
import type { Role } from './permission/types';
import {
  isRecommendedBomRole,
  recommendedBomRoleLabel,
  sortRolesForPermissionSetup,
} from './permission/roleDisplayHelpers';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

// v2 IA: capability is the primary, business-language grant surface (the raw matrix is folded into
// the capability editor as a collapsed "advanced" escape hatch); the separate flat "assignments"
// tab is retired. The role surface keeps capabilities and members, with audit as the
// operator-facing trace view for permission DENY decisions.
type RightTabKey = 'capabilities' | 'members' | 'audit';

const TYPE_BADGE: Record<string, string> = {
  SYSTEM: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300',
  CUSTOM: 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300',
  TENANT: 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-300',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function PermissionManagement() {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();
  const location = useLocation();
  const searchParams = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const auditDeepLink = searchParams.get('tab') === 'audit' || Boolean(searchParams.get('traceId'));

  // Role list state
  const [roles, setRoles] = useState<Role[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedRolePid, setSelectedRolePid] = useState<string | null>(null);

  // Right panel tab state — capability editor is the default surface.
  const [activeRightTab, setActiveRightTab] = useState<RightTabKey>(
    auditDeepLink ? 'audit' : 'capabilities',
  );

  // Dialog state
  const [showRoleForm, setShowRoleForm] = useState(false);
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ open: boolean; role: Role | null }>({
    open: false,
    role: null,
  });

  // -------------------------------------------------------------------------
  // Data fetching
  // -------------------------------------------------------------------------

  const fetchRoles = useCallback(async () => {
    setRolesLoading(true);
    try {
      const result = await fetchResult<{ records: Role[] }>('/api/roles?pageSize=100', {
        method: 'get',
      });
      handleSubmitResult(result, {
        onSuccess: (data) => {
          setRoles(data.records || []);
        },
        showToast: false,
      });
    } finally {
      setRolesLoading(false);
    }
  }, [handleSubmitResult]);

  useEffect(() => {
    fetchRoles();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (auditDeepLink) {
      setActiveRightTab('audit');
    }
  }, [auditDeepLink]);

  const filteredRoles = useMemo(() => {
    const q = searchQuery.toLowerCase();
    const matched = searchQuery
      ? roles.filter(
          (r) => r.name.toLowerCase().includes(q) || r.code.toLowerCase().includes(q),
        )
      : roles;
    return sortRolesForPermissionSetup(matched);
  }, [roles, searchQuery]);

  const recommendedRoleCount = useMemo(
    () => roles.filter((role) => isRecommendedBomRole(role.code)).length,
    [roles],
  );

  const selectedRole = useMemo(
    () => roles.find((r) => r.pid === selectedRolePid) || null,
    [roles, selectedRolePid],
  );

  useEffect(() => {
    if (selectedRolePid || filteredRoles.length === 0) return;
    setSelectedRolePid(filteredRoles[0].pid);
  }, [filteredRoles, selectedRolePid]);

  // -------------------------------------------------------------------------
  // Handlers
  // -------------------------------------------------------------------------

  const handleSaveRole = async (roleData: {
    code: string;
    name: string;
    description: string;
    type: string;
  }) => {
    const isEditing = !!editingRole;
    const url = isEditing ? `/api/roles/${editingRole!.pid}` : '/api/roles';
    const method = isEditing ? 'put' : 'post';

    const result = await fetchResult<Role>(url, { method, params: roleData });
    handleSubmitResult(result, {
      onSuccess: () => {
        showSuccessToast(
          t(
            isEditing
              ? 'admin.permission.role.update.success'
              : 'admin.permission.role.create.success',
          ) || (isEditing ? 'Role updated' : 'Role created'),
        );
        setShowRoleForm(false);
        setEditingRole(null);
        fetchRoles();
      },
      onError: (error) => showErrorToast(error || 'Failed'),
      showToast: false,
    });
  };

  const handleDeleteRole = async () => {
    if (!confirmDelete.role) return;
    const result = await fetchResult<boolean>(`/api/roles/${confirmDelete.role.pid}`, {
      method: 'delete',
    });
    handleSubmitResult(result, {
      onSuccess: () => {
        showSuccessToast(t('admin.permission.role.delete.success') || 'Deleted');
        if (selectedRolePid === confirmDelete.role?.pid) {
          setSelectedRolePid(null);
        }
        setConfirmDelete({ open: false, role: null });
        fetchRoles();
      },
      onError: (error) => showErrorToast(error || 'Delete failed'),
      showToast: false,
    });
  };

  const handleToggleRole = async (role: Role) => {
    const disabled = role.status === 'disabled';
    const action = disabled ? 'enable' : 'disable';
    const nextStatus = disabled ? 'active' : 'disabled';
    const result = await fetchResult<boolean>(`/api/roles/${role.pid}/${action}`, {
      method: 'put',
    });
    handleSubmitResult(result, {
      onSuccess: () => {
        showSuccessToast(
          t(
            disabled
              ? 'admin.permission.role.enable.success'
              : 'admin.permission.role.disable.success',
          ) || (disabled ? 'Enabled' : 'Disabled'),
        );
        // Optimistic local update: mutate just this role's status so the row
        // stays mounted (stable key=pid) and interactive for subsequent clicks.
        // Avoids a full fetchRoles() refetch which re-creates list array and
        // can race against rapid user / E2E toggle sequences.
        setRoles((prev) =>
          prev.map((r) => (r.pid === role.pid ? { ...r, status: nextStatus } : r)),
        );
      },
      onError: (error) => showErrorToast(error || 'Toggle failed'),
      showToast: false,
    });
  };

  // -------------------------------------------------------------------------
  // Render helpers
  // -------------------------------------------------------------------------

  const renderRolesTab = () => (
    <div className="flex min-w-0 flex-1 overflow-hidden">
      {/* Left — Role table */}
      <div className="flex w-80 min-w-0 flex-shrink-0 flex-col border-r border-gray-200 dark:border-gray-700">
        <div className="border-b border-gray-200 p-3 dark:border-gray-700">
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <MagnifyingGlassIcon className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                data-testid="role-search-input"
                placeholder={t('admin.permission.role.search') || 'Search roles...'}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full rounded-md border border-gray-300 py-1.5 pr-3 pl-8 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
              />
            </div>
            <button
              data-testid="role-create-btn"
              onClick={() => {
                setEditingRole(null);
                setShowRoleForm(true);
              }}
              className="flex-shrink-0 rounded-md bg-blue-600 p-1.5 text-white hover:bg-blue-700"
              title={t('admin.permission.role.create') || 'Create Role'}
            >
              <PlusIcon className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto">
          {recommendedRoleCount > 0 && !searchQuery && (
            <div className="border-b border-gray-100 px-3 py-2 text-xs text-gray-500 dark:border-gray-800 dark:text-gray-400">
              {t(
                'admin.permission.role.recommendedHint',
                undefined,
                '建议岗位: 管理员 tenant_admin；销售/采购 bom_operator + qo_quoter；工程 bom_operator',
              )}
            </div>
          )}
          {rolesLoading && roles.length === 0 ? (
            <div className="py-8">
              <LoadingSpinner />
            </div>
          ) : filteredRoles.length === 0 ? (
            <div className="px-3 py-8 text-center text-sm text-gray-400">
              {searchQuery
                ? t('admin.permission.role.searchEmpty') || 'No matching roles'
                : t('admin.permission.empty.roles') || 'No roles yet'}
            </div>
          ) : (
            <table data-testid="role-table" className="w-full table-fixed">
              <thead className="sr-only">
                <tr>
                  <th>Role</th>
                  <th>Type</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredRoles.map((role) => {
                  const isSelected = selectedRolePid === role.pid;
                  const isDisabled = role.status === 'disabled';
                  return (
                    <tr
                      key={role.pid}
                      data-testid={`role-item-${role.code}`}
                      onClick={() => setSelectedRolePid(role.pid)}
                      className={`group cursor-pointer border-b border-gray-100 transition-colors dark:border-gray-700 ${
                        isSelected
                          ? 'bg-blue-50 dark:bg-blue-900/20'
                          : 'hover:bg-gray-50 dark:hover:bg-gray-800'
                      }`}
                    >
                      <td data-testid={`role-row-${role.code}`} className="max-w-0 px-3 py-2">
                        <div className="flex items-center gap-2">
                          {role.type === 'SYSTEM' && (
                            <span
                              className={`inline-flex flex-shrink-0 items-center rounded px-1 py-0.5 text-[10px] font-medium ${
                                TYPE_BADGE[role.type] || ''
                              }`}
                            >
                              Sys
                            </span>
                          )}
                          <span
                            className={`truncate text-sm font-medium ${
                              isDisabled
                                ? 'text-gray-400 line-through'
                                : 'text-gray-900 dark:text-gray-100'
                            }`}
                          >
                            {role.name}
                          </span>
                          {isRecommendedBomRole(role.code) && (
                            <span className="inline-flex flex-shrink-0 items-center rounded-full border border-blue-200 bg-blue-50 px-1.5 py-0.5 text-[10px] font-medium text-blue-700 dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-300">
                              {recommendedBomRoleLabel(role.code)}
                            </span>
                          )}
                        </div>
                        {role.description && (
                          <div className="mt-0.5 truncate text-xs text-gray-500">
                            {role.description}
                          </div>
                        )}
                      </td>
                      <td className="w-20 px-2 py-2 text-right whitespace-nowrap">
                        <div className="flex items-center justify-end gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                          <button
                            data-testid={`role-action-edit-${role.code}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              setEditingRole(role);
                              setShowRoleForm(true);
                            }}
                            className="rounded p-1 text-gray-400 hover:bg-gray-200 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                            title={t('common.edit') || 'Edit'}
                          >
                            <PencilIcon className="h-3.5 w-3.5" />
                          </button>
                          {!role.isSystem && (
                            <>
                              <button
                                data-testid={`role-action-toggle-${role.code}`}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleToggleRole(role);
                                }}
                                className="rounded p-1 text-gray-400 hover:bg-amber-50 hover:text-amber-600 dark:hover:bg-amber-900/20"
                                title={isDisabled ? 'Enable' : 'Disable'}
                              >
                                <PowerIcon className="h-3.5 w-3.5" />
                              </button>
                              <button
                                data-testid={`role-action-delete-${role.code}`}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setConfirmDelete({ open: true, role });
                                }}
                                className="rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20 dark:hover:text-red-400"
                                title={t('common.delete') || 'Delete'}
                              >
                                <TrashIcon className="h-3.5 w-3.5" />
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* Right — Role detail tabs (Permissions / Members) */}
      <div className="flex flex-1 flex-col overflow-hidden">
        <div className="border-b border-gray-200 px-6 dark:border-gray-700">
          <nav className="-mb-px flex space-x-6">
            <button
              type="button"
              role="tab"
              aria-selected={activeRightTab === 'capabilities'}
              data-testid="permission-right-tab-capabilities"
              onClick={() => setActiveRightTab('capabilities')}
              className={`flex items-center border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeRightTab === 'capabilities'
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
              }`}
            >
              <ShieldCheckIcon className="mr-1.5 h-4 w-4" />
              {t('admin.permission.tab.capabilities') || 'Capabilities'}
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeRightTab === 'members'}
              data-testid="permission-right-tab-members"
              onClick={() => setActiveRightTab('members')}
              className={`flex items-center border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeRightTab === 'members'
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
              }`}
            >
              <UsersIcon className="mr-1.5 h-4 w-4" />
              {t('admin.permission.tab.members') || 'Members'}
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={activeRightTab === 'audit'}
              data-testid="permission-right-tab-audit"
              onClick={() => setActiveRightTab('audit')}
              className={`flex items-center border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                activeRightTab === 'audit'
                  ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
              }`}
            >
              <ClockIcon className="mr-1.5 h-4 w-4" />
              {t('admin.permission.tab.audit', undefined, '审计')}
            </button>
          </nav>
        </div>

        <div className="flex-1 overflow-auto p-6">
          {selectedRole && (
            <div className="mb-4">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                {selectedRole.name}
              </h2>
              {selectedRole.description && (
                <p className="mt-0.5 text-sm text-gray-500">{selectedRole.description}</p>
              )}
            </div>
          )}

          {activeRightTab === 'capabilities' &&
            (selectedRole ? (
              <CapabilityRoleEditor key={selectedRole.pid} rolePid={selectedRole.pid} />
            ) : (
              <div className="text-sm text-gray-400">{t('admin.permission.selectRole') || 'Select a role'}</div>
            ))}
          {activeRightTab === 'members' && <RoleMemberTab rolePid={selectedRolePid} />}
          {activeRightTab === 'audit' && <PermissionAuditTab />}
        </div>
      </div>
    </div>
  );

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  return (
    <div className="flex h-full flex-col" data-testid="permission-page">
      {/* Header */}
      <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700">
        <div className="flex items-center">
          <ShieldCheckIcon className="mr-3 h-7 w-7 text-blue-600" />
          <div>
            <h1 className="text-xl font-bold text-gray-900 dark:text-white">
              {t('admin.permission.title') || 'Permission Management'}
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">
              {t('admin.permission.description') || 'Manage permissions, roles and assignments'}
            </p>
          </div>
        </div>
      </div>

      {/* Role management (role list + per-role capability / member editor) */}
      <div className="flex flex-1 overflow-hidden" data-testid="permission-tab-roles">
        {renderRolesTab()}
      </div>

      {/* Role Form Dialog */}
      <RoleFormDialog
        open={showRoleForm}
        onOpenChange={(open) => {
          setShowRoleForm(open);
          if (!open) setEditingRole(null);
        }}
        role={editingRole}
        onSave={handleSaveRole}
      />

      {/* Delete Confirmation */}
      <ConfirmDialog
        open={confirmDelete.open}
        title={t('admin.permission.role.delete.title') || 'Delete Role'}
        content={
          t('admin.permission.role.delete.content') ||
          `Are you sure you want to delete role "${confirmDelete.role?.name}"?`
        }
        variant="danger"
        onConfirm={handleDeleteRole}
        onCancel={() => setConfirmDelete({ open: false, role: null })}
      />
    </div>
  );
}
