import { useState, useEffect, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { permissionService } from '~/services/permissionService';
import PermissionMatrix from './PermissionMatrix';
import type { PermissionMatrixDTO } from './types';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface PermissionMatrixTabProps {
  rolePid: string | null;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function PermissionMatrixTab({ rolePid }: PermissionMatrixTabProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [matrixData, setMatrixData] = useState<PermissionMatrixDTO | null>(null);
  const [loading, setLoading] = useState(false);

  // Fetch matrix when rolePid changes
  useEffect(() => {
    if (!rolePid) {
      setMatrixData(null);
      return;
    }

    let cancelled = false;
    const fetchMatrix = async () => {
      setLoading(true);
      try {
        const data = await permissionService.getMatrixForRole(rolePid);
        if (!cancelled) {
          setMatrixData(data);
        }
      } catch (err) {
        if (!cancelled) {
          showErrorToast(
            t('admin.permission.matrix.loadError') || 'Failed to load permission matrix',
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchMatrix();
    return () => {
      cancelled = true;
    };
  }, [rolePid]); // eslint-disable-line react-hooks/exhaustive-deps

  // Optimistic toggle handler
  const handleToggle = useCallback(
    async (permissionId: number, granted: boolean) => {
      if (!rolePid || !matrixData) return;

      // Optimistic update
      setMatrixData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          modules: prev.modules.map((mod) => ({
            ...mod,
            resources: mod.resources.map((res) => ({
              ...res,
              actions: res.actions.map((act) =>
                act.permissionId === permissionId ? { ...act, granted } : act,
              ),
            })),
          })),
        };
      });

      try {
        await permissionService.batchUpdateRolePermissions(rolePid, [{ permissionId, granted }]);
        showSuccessToast(t('admin.permission.matrix.updateSuccess') || 'Permission updated');
      } catch {
        // Revert optimistic update
        setMatrixData((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            modules: prev.modules.map((mod) => ({
              ...mod,
              resources: mod.resources.map((res) => ({
                ...res,
                actions: res.actions.map((act) =>
                  act.permissionId === permissionId ? { ...act, granted: !granted } : act,
                ),
              })),
            })),
          };
        });
        showErrorToast(t('admin.permission.matrix.updateError') || 'Failed to update permission');
      }
    },
    [rolePid, matrixData, showSuccessToast, showErrorToast, t],
  );

  // Optimistic scope change handler
  const handleScopeChange = useCallback(
    async (resourceCode: string, actionCode: string, scopeType: string) => {
      if (!rolePid || !matrixData) return;

      // Optimistic update — apply scopeType immediately in local state
      setMatrixData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          modules: prev.modules.map((mod) => ({
            ...mod,
            resources: mod.resources.map((res) => ({
              ...res,
              actions: res.actions.map((act) =>
                res.resourceCode === resourceCode && act.action === actionCode
                  ? { ...act, scopeType }
                  : act,
              ),
            })),
          })),
        };
      });

      try {
        await permissionService.updateScope(rolePid, { resourceCode, actionCode, scopeType });
        showSuccessToast(t('admin.permission.scope.updateSuccess') || 'Data scope updated');
      } catch {
        // Revert optimistic update
        setMatrixData((prev) => {
          if (!prev) return prev;
          // Re-fetch would be cleanest, but for simplicity restore the original scopeType
          // We don't have the old value readily available, so just refetch
          return prev;
        });
        showErrorToast(
          t('admin.permission.scope.updateError') || 'Failed to update data scope',
        );
        // Refetch to restore accurate state after failure
        if (rolePid) {
          permissionService
            .getMatrixForRole(rolePid)
            .then((data) => setMatrixData(data))
            .catch(() => {
              /* ignore secondary error */
            });
        }
      }
    },
    [rolePid, matrixData, showSuccessToast, showErrorToast, t],
  );

  // Empty state: no role selected
  if (!rolePid) {
    return (
      <div
        data-testid="permission-matrix-tab-empty"
        className="flex items-center justify-center rounded-lg border border-dashed border-gray-300 py-24 text-sm text-gray-400 dark:border-gray-600"
      >
        {t('admin.permission.matrix.selectRole') || 'Select a role to view permissions'}
      </div>
    );
  }

  // Loading state
  if (loading) {
    return (
      <div data-testid="permission-matrix-tab-loading" className="py-16">
        <LoadingSpinner />
      </div>
    );
  }

  // Matrix loaded
  if (!matrixData) {
    return (
      <div className="flex items-center justify-center py-16 text-sm text-gray-400">
        {t('admin.permission.matrix.noData') || 'No data available'}
      </div>
    );
  }

  return (
    <div data-testid="permission-matrix-tab">
      <PermissionMatrix
        data={matrixData}
        rolePid={rolePid}
        onToggle={handleToggle}
        onScopeChange={handleScopeChange}
        loading={loading}
      />
    </div>
  );
}
