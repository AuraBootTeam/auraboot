/**
 * RoleMemberTab — Shows members assigned to a role with add/remove capability.
 *
 * Displays a paginated table of role members (name, department, position, assigned date).
 * Provides "Add Member" button to open AddMemberDialog.
 * Each row has a "Remove" action with confirmation.
 */

import { useState, useEffect, useCallback } from 'react';
import {
  UserPlusIcon,
  TrashIcon,
  UsersIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import ConfirmDialog from '~/components/ConfirmDialog';
import AddMemberDialog from './AddMemberDialog';
import { permissionService } from '~/services/permissionService';
import type { RoleMemberDTO } from './types';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface RoleMemberTabProps {
  rolePid: string | null;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function RoleMemberTab({ rolePid }: RoleMemberTabProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  // Data state
  const [members, setMembers] = useState<RoleMemberDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const pageSize = 20;

  // Dialog state
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<{
    open: boolean;
    member: RoleMemberDTO | null;
  }>({ open: false, member: null });
  const [removing, setRemoving] = useState(false);

  // ---------------------------------------------------------------------------
  // Data fetching
  // ---------------------------------------------------------------------------

  const fetchMembers = useCallback(async () => {
    if (!rolePid) return;
    setLoading(true);
    try {
      const data = await permissionService.getRoleMembers(rolePid, { pageNum, pageSize });
      setMembers(data.records || []);
      setTotal(data.total || 0);
    } catch {
      setMembers([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [rolePid, pageNum]);

  useEffect(() => {
    setPageNum(1);
    setMembers([]);
    setTotal(0);
  }, [rolePid]);

  useEffect(() => {
    fetchMembers();
  }, [fetchMembers]);

  // ---------------------------------------------------------------------------
  // Remove handler
  // ---------------------------------------------------------------------------

  const handleRemove = async () => {
    if (!rolePid || !confirmRemove.member) return;
    setRemoving(true);
    try {
      await permissionService.removeRoleMembers(rolePid, [confirmRemove.member.memberPid]);
      showSuccessToast(
        (t('admin.permission.members.removeSuccess') || 'Member removed: {name}').replace(
          '{name}',
          confirmRemove.member.userName,
        ),
      );
      setConfirmRemove({ open: false, member: null });
      fetchMembers();
    } catch (err: any) {
      showErrorToast(err?.message || t('common.error') || 'Failed');
    } finally {
      setRemoving(false);
    }
  };

  // ---------------------------------------------------------------------------
  // Pagination
  // ---------------------------------------------------------------------------

  const totalPages = Math.ceil(total / pageSize);

  // ---------------------------------------------------------------------------
  // No role selected
  // ---------------------------------------------------------------------------

  if (!rolePid) {
    return (
      <div
        data-testid="role-member-tab-empty"
        className="flex items-center justify-center rounded-lg border border-dashed border-gray-300 py-24 text-sm text-gray-400 dark:border-gray-600"
      >
        {t('admin.permission.members.selectRole') || 'Select a role to view members'}
      </div>
    );
  }

  // ---------------------------------------------------------------------------
  // Existing member PIDs for disabling in AddMemberDialog
  // ---------------------------------------------------------------------------

  const existingMemberPids = members.map((m) => m.memberPid);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <div data-testid="role-member-tab">
      {/* Toolbar */}
      <div className="mb-4 flex items-center justify-between">
        <span className="text-sm text-gray-500 dark:text-gray-400">
          {total > 0
            ? (t('admin.permission.members.totalCount') || '{count} member(s)').replace(
                '{count}',
                String(total),
              )
            : ''}
        </span>
        <button
          data-testid="role-member-add-btn"
          onClick={() => setShowAddDialog(true)}
          className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          <UserPlusIcon className="h-4 w-4" />
          {t('admin.permission.members.add') || 'Add Members'}
        </button>
      </div>

      {/* Table */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <LoadingSpinner />
        </div>
      ) : members.length === 0 ? (
        <div
          data-testid="role-member-empty"
          className="flex flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 py-16 dark:border-gray-600"
        >
          <UsersIcon className="mb-3 h-10 w-10 text-gray-300" />
          <p className="mb-3 text-sm text-gray-400">
            {t('admin.permission.members.empty') || 'No members in this role'}
          </p>
          <button
            data-testid="role-member-empty-add-btn"
            onClick={() => setShowAddDialog(true)}
            className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            <UserPlusIcon className="h-4 w-4" />
            {t('admin.permission.members.add') || 'Add Members'}
          </button>
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
            <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
              <thead className="bg-gray-50 dark:bg-gray-800">
                <tr>
                  <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                    {t('admin.permission.members.colName') || 'Name'}
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                    {t('admin.permission.members.colDepartment') || 'Department'}
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                    {t('admin.permission.members.colPosition') || 'Position'}
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500">
                    {t('admin.permission.members.colAssignedAt') || 'Assigned'}
                  </th>
                  <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wider text-gray-500">
                    {t('common.actions') || 'Actions'}
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white dark:divide-gray-700 dark:bg-gray-900">
                {members.map((member) => (
                  <tr
                    key={member.memberId}
                    data-testid={`role-member-row-${member.memberId}`}
                    className="hover:bg-gray-50 dark:hover:bg-gray-800"
                  >
                    <td className="whitespace-nowrap px-4 py-3 text-sm font-medium text-gray-900 dark:text-gray-100">
                      <div>
                        <div>{member.userName}</div>
                        <div className="text-xs text-gray-400">{member.email}</div>
                      </div>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {member.departmentName || '-'}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {member.positionName || '-'}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-sm text-gray-500 dark:text-gray-400">
                      {member.assignedAt
                        ? new Date(member.assignedAt).toLocaleDateString()
                        : '-'}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right">
                      <button
                        data-testid={`role-member-remove-${member.memberId}`}
                        onClick={() =>
                          setConfirmRemove({ open: true, member })
                        }
                        className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20"
                      >
                        <TrashIcon className="h-3.5 w-3.5" />
                        {t('common.remove') || 'Remove'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between">
              <span className="text-xs text-gray-500">
                {(t('common.pagination.showing') || 'Page {page} of {total}')
                  .replace('{page}', String(pageNum))
                  .replace('{total}', String(totalPages))}
              </span>
              <div className="flex gap-1">
                <button
                  disabled={pageNum <= 1}
                  onClick={() => setPageNum((p) => p - 1)}
                  className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-600"
                >
                  {t('common.pagination.prev') || 'Prev'}
                </button>
                <button
                  disabled={pageNum >= totalPages}
                  onClick={() => setPageNum((p) => p + 1)}
                  className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-600"
                >
                  {t('common.pagination.next') || 'Next'}
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {/* Add Member Dialog */}
      <AddMemberDialog
        open={showAddDialog}
        onClose={() => setShowAddDialog(false)}
        rolePid={rolePid}
        existingMemberPids={existingMemberPids}
        onSuccess={() => {
          setShowAddDialog(false);
          fetchMembers();
        }}
      />

      {/* Remove Confirmation */}
      <ConfirmDialog
        open={confirmRemove.open}
        title={t('admin.permission.members.removeTitle') || 'Remove Member'}
        content={
          (
            t('admin.permission.members.removeContent') ||
            'Are you sure you want to remove "{name}" from this role?'
          ).replace('{name}', confirmRemove.member?.userName || '')
        }
        variant="danger"
        onConfirm={handleRemove}
        onCancel={() => setConfirmRemove({ open: false, member: null })}
      />
    </div>
  );
}
