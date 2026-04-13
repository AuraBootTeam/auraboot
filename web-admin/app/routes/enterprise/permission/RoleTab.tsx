import { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheckIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  EyeIcon,
  EyeSlashIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { fetchResult } from '~/shared/services/http-client';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import ConfirmDialog from '~/ui/ConfirmDialog';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import RoleFormDialog from './RoleFormDialog';
import type { Role } from './types';

interface RoleTabProps {
  onAssignPermissions: (role: Role) => void;
}

const TYPE_BADGE: Record<string, string> = {
  SYSTEM: 'bg-blue-100 text-blue-800',
  CUSTOM: 'bg-gray-100 text-gray-800',
  TENANT: 'bg-purple-100 text-purple-800',
};

const TYPE_I18N: Record<string, string> = {
  SYSTEM: 'admin.permission.role.type.system',
  CUSTOM: 'admin.permission.role.type.custom',
  TENANT: 'admin.permission.role.type.tenant',
};

export default function RoleTab({ onAssignPermissions }: RoleTabProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();

  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(false);
  const [showRoleForm, setShowRoleForm] = useState(false);
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<{ open: boolean; role: Role | null }>({
    open: false,
    role: null,
  });

  const fetchRoles = useCallback(async () => {
    setLoading(true);
    try {
      const result = await fetchResult<{ records: Role[] }>('/api/roles?pageSize=100', {
        method: 'get',
      });
      handleSubmitResult(result, {
        onSuccess: (data) => setRoles(data.records || []),
        showToast: false,
      });
    } finally {
      setLoading(false);
    }
  }, [handleSubmitResult]);

  useEffect(() => {
    fetchRoles();
  }, []);

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

  const handleToggleStatus = async (role: Role) => {
    const action = role.status === 'active' ? 'disable' : 'enable';
    const result = await fetchResult<boolean>(`/api/roles/${role.pid}/${action}`, {
      method: 'put',
    });
    handleSubmitResult(result, {
      onSuccess: () => {
        showSuccessToast(
          t(
            action === 'enable'
              ? 'admin.permission.role.enable.success'
              : 'admin.permission.role.disable.success',
          ) || (action === 'enable' ? 'Enabled successfully' : 'Disabled successfully'),
        );
        fetchRoles();
      },
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
        fetchRoles();
      },
      showToast: false,
    });
  };

  if (loading) {
    return (
      <div data-testid="role-tab">
        <LoadingSpinner />
      </div>
    );
  }

  return (
    <div data-testid="role-tab">
      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <div />
        <button
          data-testid="role-create-btn"
          onClick={() => {
            setEditingRole(null);
            setShowRoleForm(true);
          }}
          className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700"
        >
          <PlusIcon className="h-4 w-4" />
          {t('admin.permission.role.create') || 'Create Role'}
        </button>
      </div>

      {/* Content */}
      {roles.length === 0 ? (
        <div className="flex items-center justify-center py-16 text-gray-500">
          {t('admin.permission.empty.roles') || 'No roles yet'}
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow">
          <table data-testid="role-table" className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('admin.permission.role.name') || 'Role'}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('common.field.type') || 'Type'}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('common.field.status') || 'Status'}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('common.field.createdAt') || 'Created At'}
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('common.actions') || 'Actions'}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {roles.map((role) => (
                <tr
                  key={role.pid}
                  data-testid={`role-row-${role.code}`}
                  className="hover:bg-gray-50"
                >
                  {/* Role Info */}
                  <td className="px-6 py-4">
                    <div className="text-sm font-medium text-gray-900">{role.name}</div>
                    <div className="text-xs text-gray-500">{role.code}</div>
                    {role.description && (
                      <div className="mt-0.5 text-xs text-gray-400">{role.description}</div>
                    )}
                  </td>

                  {/* Type */}
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${TYPE_BADGE[role.type] || 'bg-gray-100 text-gray-800'}`}
                    >
                      {t(TYPE_I18N[role.type] || '') || role.type}
                    </span>
                  </td>

                  {/* Status */}
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
                        role.status === 'active'
                          ? 'bg-green-100 text-green-800'
                          : 'bg-red-100 text-red-800'
                      }`}
                    >
                      {role.status === 'active'
                        ? t('admin.permission.role.status.active') || 'Active'
                        : t('admin.permission.role.status.inactive') || 'Inactive'}
                    </span>
                  </td>

                  {/* Created At */}
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {role.createdAt ? new Date(role.createdAt).toLocaleDateString() : '-'}
                  </td>

                  {/* Actions */}
                  <td className="px-6 py-4 text-right whitespace-nowrap">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        data-testid={`role-action-assign-${role.code}`}
                        onClick={() => onAssignPermissions(role)}
                        className="rounded p-1.5 text-blue-600 hover:bg-blue-50"
                        title={t('admin.permission.role.assignPermission') || 'Assign Permissions'}
                      >
                        <ShieldCheckIcon className="h-4 w-4" />
                      </button>
                      <button
                        data-testid={`role-action-edit-${role.code}`}
                        onClick={() => {
                          setEditingRole(role);
                          setShowRoleForm(true);
                        }}
                        className="rounded p-1.5 text-blue-600 hover:bg-blue-50"
                        title={t('common.edit') || 'Edit'}
                      >
                        <PencilIcon className="h-4 w-4" />
                      </button>
                      <button
                        data-testid={`role-action-toggle-${role.code}`}
                        onClick={() => handleToggleStatus(role)}
                        className="rounded p-1.5 text-blue-600 hover:bg-blue-50"
                        title={
                          role.status === 'active'
                            ? t('common.disable') || 'Disable'
                            : t('common.enable') || 'Enable'
                        }
                      >
                        {role.status === 'active' ? (
                          <EyeIcon className="h-4 w-4" />
                        ) : (
                          <EyeSlashIcon className="h-4 w-4" />
                        )}
                      </button>
                      {!role.isSystem && (
                        <button
                          data-testid={`role-action-delete-${role.code}`}
                          onClick={() => setConfirmDelete({ open: true, role })}
                          className="rounded p-1.5 text-red-600 hover:bg-red-50"
                          title={t('common.delete') || 'Delete'}
                        >
                          <TrashIcon className="h-4 w-4" />
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

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
