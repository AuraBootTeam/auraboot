import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  MagnifyingGlassIcon,
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
import type { Permission } from './types';

// ---------------------------------------------------------------------------
// Module options (resolved at render time via i18n)
// ---------------------------------------------------------------------------

const MODULE_KEYS: { value: string; i18nKey: string; fallback: string }[] = [
  { value: 'content', i18nKey: 'menu.content', fallback: 'Content' },
  { value: 'distribution_plan', i18nKey: 'menu.distribution', fallback: 'Distribution' },
  { value: 'reports', i18nKey: 'menu.reports', fallback: 'Reports' },
  { value: 'enterprise', i18nKey: 'menu.enterprise', fallback: 'Enterprise' },
  { value: 'user', i18nKey: 'menu.user', fallback: 'User' },
];

// ---------------------------------------------------------------------------
// Badge components
// ---------------------------------------------------------------------------

const TYPE_BADGE_STYLES: Record<string, string> = {
  MENU: 'bg-blue-100 text-blue-700',
  BUTTON: 'bg-purple-100 text-purple-700',
  API: 'bg-orange-100 text-orange-700',
};

const STATUS_BADGE_STYLES: Record<string, string> = {
  active: 'bg-green-100 text-green-700',
  inactive: 'bg-gray-100 text-gray-500',
};

// ---------------------------------------------------------------------------
// PermissionTab
// ---------------------------------------------------------------------------

export default function PermissionTab() {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { handleSubmitResult } = useFormSubmit();

  // Resolve module labels via i18n
  const moduleOptions = useMemo(
    () => MODULE_KEYS.map((m) => ({ value: m.value, label: t(m.i18nKey) || m.fallback })),
    [t],
  );
  const moduleLabelMap = useMemo(
    () => Object.fromEntries(moduleOptions.map((m) => [m.value, m.label])),
    [moduleOptions],
  );

  // State
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedModule, setSelectedModule] = useState('');
  const [selectedType, setSelectedType] = useState('');
  const [selectedStatus, setSelectedStatus] = useState('');

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<Permission | null>(null);

  // -----------------------------------------------------------------------
  // Fetch
  // -----------------------------------------------------------------------

  const fetchPermissions = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (searchKeyword) params.append('keyword', searchKeyword);
      if (selectedModule) params.append('module', selectedModule);
      if (selectedType) params.append('type', selectedType);
      if (selectedStatus) params.append('status', selectedStatus);
      params.append('pageSize', '100');

      const result = await fetchResult<{ records: Permission[] }>(`/api/permissions?${params}`, {
        method: 'get',
      });
      if (result.code === '0' && result.data) {
        setPermissions(result.data.records ?? []);
      } else {
        setPermissions([]);
      }
    } catch {
      setPermissions([]);
    } finally {
      setLoading(false);
    }
  }, [searchKeyword, selectedModule, selectedType, selectedStatus]);

  useEffect(() => {
    fetchPermissions();
  }, [fetchPermissions]);

  // -----------------------------------------------------------------------
  // Actions
  // -----------------------------------------------------------------------

  const handleToggleStatus = useCallback(
    async (perm: Permission) => {
      const action = perm.status === 'active' ? 'disable' : 'enable';
      try {
        const result = await fetchResult<boolean>(`/api/permissions/${perm.pid}/${action}`, {
          method: 'put',
        });
        handleSubmitResult(result, {
          successMessage:
            action === 'enable'
              ? t('admin.permission.role.enable.success')
              : t('admin.permission.role.disable.success'),
          onSuccess: () => fetchPermissions(),
        });
      } catch {
        showErrorToast('Operation failed');
      }
    },
    [fetchPermissions, handleSubmitResult, showErrorToast, t],
  );

  const handleDelete = useCallback(async () => {
    if (!deleteTarget) return;
    try {
      const result = await fetchResult<boolean>(`/api/permissions/${deleteTarget.pid}`, {
        method: 'delete',
      });
      handleSubmitResult(result, {
        successMessage: t('admin.permission.role.delete.success'),
        onSuccess: () => fetchPermissions(),
      });
    } catch {
      showErrorToast('Delete failed');
    } finally {
      setDeleteTarget(null);
    }
  }, [deleteTarget, fetchPermissions, handleSubmitResult, showErrorToast, t]);

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  const typeLabel = (type: string) => {
    const map: Record<string, string> = {
      MENU: t('admin.permission.type.menu'),
      BUTTON: t('admin.permission.type.button'),
      API: t('admin.permission.type.api'),
    };
    return map[type] || type;
  };

  const statusLabel = (status: string) => {
    const map: Record<string, string> = {
      active: t('admin.permission.role.status.active'),
      inactive: t('admin.permission.role.status.inactive'),
    };
    return map[status] || status;
  };

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  return (
    <div data-testid="perm-tab" className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        {/* Search */}
        <div className="relative min-w-[220px] flex-1">
          <MagnifyingGlassIcon className="pointer-events-none absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            data-testid="perm-search"
            type="text"
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            placeholder={t('admin.permission.perm.search')}
            className="w-full rounded-md border border-gray-300 py-2 pr-3 pl-9 text-sm placeholder:text-gray-400 focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
          />
        </div>

        {/* Filter: Module */}
        <select
          data-testid="perm-filter-module"
          value={selectedModule}
          onChange={(e) => setSelectedModule(e.target.value)}
          className="rounded-md border border-gray-300 py-2 pr-8 pl-3 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{t('admin.permission.perm.allModules')}</option>
          {moduleOptions.map((m) => (
            <option key={m.value} value={m.value}>
              {m.label}
            </option>
          ))}
        </select>

        {/* Filter: Type */}
        <select
          data-testid="perm-filter-type"
          value={selectedType}
          onChange={(e) => setSelectedType(e.target.value)}
          className="rounded-md border border-gray-300 py-2 pr-8 pl-3 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{t('admin.permission.perm.allTypes')}</option>
          <option value="menu">{t('admin.permission.type.menu')}</option>
          <option value="button">{t('admin.permission.type.button')}</option>
          <option value="api">{t('admin.permission.type.api')}</option>
        </select>

        {/* Filter: Status */}
        <select
          data-testid="perm-filter-status"
          value={selectedStatus}
          onChange={(e) => setSelectedStatus(e.target.value)}
          className="rounded-md border border-gray-300 py-2 pr-8 pl-3 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{t('admin.permission.perm.allStatus')}</option>
          <option value="active">{t('admin.permission.role.status.active')}</option>
          <option value="inactive">{t('admin.permission.role.status.inactive')}</option>
        </select>

        {/* Create button */}
        <button
          data-testid="perm-create-btn"
          className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
        >
          <PlusIcon className="h-4 w-4" />
          {t('admin.permission.perm.create')}
        </button>
      </div>

      {/* Loading */}
      {loading && (
        <div className="flex items-center justify-center py-16">
          <LoadingSpinner />
        </div>
      )}

      {/* Empty state */}
      {!loading && permissions.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <p className="text-sm">{t('admin.permission.empty.permissions')}</p>
        </div>
      )}

      {/* Table */}
      {!loading && permissions.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-gray-200">
          <table data-testid="perm-table" className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('admin.permission.perm.info')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('admin.permission.perm.module')}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('admin.permission.type') || 'Type'}
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('admin.permission.status') || 'Status'}
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {t('common.actions') || 'Actions'}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {permissions.map((perm) => (
                <tr
                  key={perm.pid}
                  data-testid={`perm-row-${perm.code}`}
                  className="hover:bg-gray-50"
                >
                  {/* Permission Info */}
                  <td className="px-4 py-3">
                    <div className="flex flex-col gap-0.5">
                      <span className="text-sm font-medium text-gray-900">{perm.name}</span>
                      <span className="text-xs text-gray-400">{perm.code}</span>
                      {perm.description && (
                        <span className="text-xs text-gray-500">{perm.description}</span>
                      )}
                    </div>
                  </td>

                  {/* Module */}
                  <td className="px-4 py-3 text-sm text-gray-700">
                    {moduleLabelMap[perm.module] || perm.module}
                  </td>

                  {/* Type badge */}
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${TYPE_BADGE_STYLES[perm.type] || 'bg-gray-100 text-gray-600'}`}
                    >
                      {typeLabel(perm.type)}
                    </span>
                  </td>

                  {/* Status badge */}
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_BADGE_STYLES[perm.status] || 'bg-gray-100 text-gray-500'}`}
                    >
                      {statusLabel(perm.status)}
                    </span>
                  </td>

                  {/* Actions */}
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      {/* Edit */}
                      <button
                        title={t('common.edit') || 'Edit'}
                        className="rounded p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                      >
                        <PencilIcon className="h-4 w-4" />
                      </button>

                      {/* Toggle status */}
                      <button
                        title={
                          perm.status === 'active'
                            ? t('admin.permission.role.status.inactive')
                            : t('admin.permission.role.status.active')
                        }
                        onClick={() => handleToggleStatus(perm)}
                        className="rounded p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                      >
                        {perm.status === 'active' ? (
                          <EyeSlashIcon className="h-4 w-4" />
                        ) : (
                          <EyeIcon className="h-4 w-4" />
                        )}
                      </button>

                      {/* Delete (only non-system) */}
                      {!perm.isSystem && (
                        <button
                          title={t('common.delete') || 'Delete'}
                          onClick={() => setDeleteTarget(perm)}
                          className="rounded p-1.5 text-gray-400 hover:bg-red-50 hover:text-red-500"
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

      {/* Delete confirmation dialog */}
      <ConfirmDialog
        open={!!deleteTarget}
        title={t('admin.permission.perm.delete.title')}
        content={t('admin.permission.perm.delete.content')}
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
