import { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheckIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  MagnifyingGlassIcon,
  UsersIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { fetchResult } from '~/services/http-client';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import ConfirmDialog from '~/components/ConfirmDialog';
import RoleFormDialog from './permission/RoleFormDialog';
import PermissionMatrixTab from './permission/PermissionMatrixTab';
import RoleMemberTab from './permission/RoleMemberTab';
import type { Role } from './permission/types';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

type RightTabKey = 'permissions' | 'members';

const TYPE_BADGE: Record<string, string> = {
  SYSTEM: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300',
  CUSTOM: 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300',
  TENANT: 'bg-purple-100 text-purple-800 dark:bg-purple-900/30 dark:text-purple-300',
};

const TYPE_SHORT: Record<string, string> = {
  SYSTEM: 'admin.permission.role.type.system.short',
  CUSTOM: 'admin.permission.role.type.custom.short',
  TENANT: 'admin.permission.role.type.tenant.short',
};

const TYPE_FALLBACK_SHORT: Record<string, string> = {
  SYSTEM: 'Sys',
  CUSTOM: 'Cust',
  TENANT: 'Tenant',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function PermissionManagement() {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();

  // Left panel state
  const [roles, setRoles] = useState<Role[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedRolePid, setSelectedRolePid] = useState<string | null>(null);

  // Right panel state
  const [activeTab, setActiveTab] = useState<RightTabKey>('permissions');

  // Dialog state
  const [showRoleForm, setShowRoleForm] = useState(false);
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ open: boolean; role: Role | null }>({
    open: false,
    role: null,
  });

  // ---------------------------------------------------------------------------
  // Data fetching
  // ---------------------------------------------------------------------------

  const fetchRoles = useCallback(async () => {
    setRolesLoading(true);
    try {
      const result = await fetchResult<{ records: Role[] }>('/api/roles?pageSize=100', {
        method: 'get',
      });
      handleSubmitResult(result, {
        onSuccess: (data) => {
          const records = data.records || [];
          setRoles(records);
          // Auto-select first role if none selected
          if (records.length > 0 && !selectedRolePid) {
            setSelectedRolePid(records[0].pid);
          }
        },
        showToast: false,
      });
    } finally {
      setRolesLoading(false);
    }
  }, [handleSubmitResult]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    fetchRoles();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // ---------------------------------------------------------------------------
  // Filtered roles
  // ---------------------------------------------------------------------------

  const filteredRoles = searchQuery
    ? roles.filter(
        (r) =>
          r.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          r.code.toLowerCase().includes(searchQuery.toLowerCase()),
      )
    : roles;

  const selectedRole = roles.find((r) => r.pid === selectedRolePid) || null;

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

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
        setConfirmDelete({ open: false, role: null });
        // If deleted role was selected, clear selection
        if (selectedRolePid === confirmDelete.role?.pid) {
          setSelectedRolePid(null);
        }
        fetchRoles();
      },
      showToast: false,
    });
  };

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

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

      {/* Body: left role list + right panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* ============================================================ */}
        {/* Left Panel — Role List                                       */}
        {/* ============================================================ */}
        <div className="flex w-72 flex-shrink-0 flex-col border-r border-gray-200 dark:border-gray-700">
          {/* Search + Create */}
          <div className="space-y-2 border-b border-gray-200 p-3 dark:border-gray-700">
            <div className="flex items-center gap-2">
              <div className="relative flex-1">
                <MagnifyingGlassIcon className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
                <input
                  type="text"
                  data-testid="role-search-input"
                  placeholder={t('admin.permission.role.search') || 'Search roles...'}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full rounded-md border border-gray-300 py-1.5 pl-8 pr-3 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
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

          {/* Role list */}
          <div className="flex-1 overflow-y-auto">
            {rolesLoading ? (
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
              <div className="py-1">
                {filteredRoles.map((role) => {
                  const isSelected = selectedRolePid === role.pid;
                  return (
                    <div
                      key={role.pid}
                      data-testid={`role-item-${role.code}`}
                      onClick={() => setSelectedRolePid(role.pid)}
                      className={`group relative mx-1 cursor-pointer rounded-md px-3 py-2 transition-colors ${
                        isSelected
                          ? 'bg-blue-50 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                          : 'text-gray-700 hover:bg-gray-50 dark:text-gray-300 dark:hover:bg-gray-800'
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        {/* Type badge (short) */}
                        {role.type === 'SYSTEM' && (
                          <span
                            className={`inline-flex flex-shrink-0 items-center rounded px-1 py-0.5 text-[10px] font-medium ${TYPE_BADGE[role.type] || ''}`}
                          >
                            {t(TYPE_SHORT[role.type] || '') ||
                              TYPE_FALLBACK_SHORT[role.type] ||
                              role.type}
                          </span>
                        )}
                        {/* Role name */}
                        <span className="truncate text-sm font-medium">{role.name}</span>
                      </div>

                      {/* Action buttons on hover */}
                      <div className="absolute top-1/2 right-2 flex -translate-y-1/2 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
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
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        {/* ============================================================ */}
        {/* Right Panel — Tabs (Permissions / Members)                   */}
        {/* ============================================================ */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {/* Tab bar */}
          <div className="border-b border-gray-200 px-6 dark:border-gray-700">
            <nav className="-mb-px flex space-x-6">
              <button
                data-testid="permission-right-tab-permissions"
                onClick={() => setActiveTab('permissions')}
                className={`flex items-center border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                  activeTab === 'permissions'
                    ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
                }`}
              >
                <ShieldCheckIcon className="mr-1.5 h-4 w-4" />
                {t('admin.permission.tab.permissions') || 'Permissions'}
              </button>
              <button
                data-testid="permission-right-tab-members"
                onClick={() => setActiveTab('members')}
                className={`flex items-center border-b-2 px-1 py-3 text-sm font-medium transition-colors ${
                  activeTab === 'members'
                    ? 'border-blue-500 text-blue-600 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400'
                }`}
              >
                <UsersIcon className="mr-1.5 h-4 w-4" />
                {t('admin.permission.tab.members') || 'Members'}
              </button>
            </nav>
          </div>

          {/* Tab content */}
          <div className="flex-1 overflow-auto p-6">
            {/* Show selected role name */}
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

            {activeTab === 'permissions' && (
              <PermissionMatrixTab rolePid={selectedRolePid} />
            )}

            {activeTab === 'members' && <RoleMemberTab rolePid={selectedRolePid} />}
          </div>
        </div>
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
