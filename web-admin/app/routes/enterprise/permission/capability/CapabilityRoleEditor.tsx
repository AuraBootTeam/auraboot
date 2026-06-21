import { useEffect, useState, useCallback, useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { CapabilityGroup } from './types';
import type { PermissionMatrixDTO } from '../types';
import { capabilityService } from './capabilityService';
import { grantedCapabilityCodes, toggleCapability, isDirty, capabilityCodesForTier } from './capabilityHelpers';
import { deriveCodeSources, exceptionCount } from './coverageHelpers';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityChecklist from './CapabilityChecklist';
import DataScopeBar from './DataScopeBar';
import AdvancedAtomicActions from './AdvancedAtomicActions';

interface CapabilityRoleEditorProps {
  /** Numeric role id (capability endpoint keys on id). */
  roleId: string;
  /** Role pid (matrix / scope endpoints key on pid). */
  rolePid: string;
}

/**
 * Permission v2 role editor — the primary, business-language grant surface, three orthogonal
 * dimensions kept separate (never interleaved):
 *   ② data scope (top bar + drawer) — which records,
 *   ① business capabilities (checklist) — what can be done,  ← everyday surface
 *   ③ advanced atomic actions (collapsed escape hatch) — per-code audit / exceptions.
 * The raw resource×action matrix is folded into ③; ① stays the default surface.
 */
export default function CapabilityRoleEditor({ roleId, rolePid }: CapabilityRoleEditorProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [groups, setGroups] = useState<CapabilityGroup[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [matrix, setMatrix] = useState<PermissionMatrixDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const loadGroups = useCallback(async () => {
    const fetched = await capabilityService.getForRole(roleId);
    setGroups(fetched);
    setSelected(grantedCapabilityCodes(fetched));
    return fetched;
  }, [roleId]);

  const loadMatrix = useCallback(async () => {
    const data = await permissionService.getMatrixForRole(rolePid);
    setMatrix(data);
    return data;
  }, [rolePid]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      await Promise.all([loadGroups(), loadMatrix()]);
    } finally {
      setLoading(false);
    }
  }, [loadGroups, loadMatrix]);

  useEffect(() => {
    void load();
  }, [load]);

  const onToggle = useCallback((code: string) => {
    setSelected((current) => toggleCapability(current, code));
  }, []);

  const save = useCallback(async () => {
    setSaving(true);
    try {
      const refreshed = await capabilityService.applySelection(roleId, selected);
      setGroups(refreshed);
      setSelected(grantedCapabilityCodes(refreshed));
      // capability grants change the underlying atomic codes — keep ③ in sync.
      await loadMatrix();
      showSuccessToast(t('common.saveSuccess', undefined, 'Saved'));
    } catch {
      showErrorToast(t('common.saveError', undefined, 'Save failed'));
    } finally {
      setSaving(false);
    }
  }, [roleId, selected, loadMatrix, showSuccessToast, showErrorToast, t]);

  // ③ atomic grant toggle — optimistic on the matrix, then resync capability view (an atomic grant
  // may complete/break a capability's all-or-nothing granted state and its coverage).
  const onAtomicToggle = useCallback(
    async (permissionId: number, granted: boolean) => {
      setMatrix((prev) =>
        prev
          ? {
              ...prev,
              modules: prev.modules.map((m) => ({
                ...m,
                resources: m.resources.map((r) => ({
                  ...r,
                  actions: r.actions.map((a) => (a.permissionId === permissionId ? { ...a, granted } : a)),
                })),
              })),
            }
          : prev,
      );
      try {
        await permissionService.batchUpdateRolePermissions(rolePid, [{ permissionId, granted }]);
        await loadGroups();
      } catch {
        showErrorToast(t('admin.permission.matrix.updateError', undefined, 'Failed to update permission'));
        await loadMatrix();
      }
    },
    [rolePid, loadGroups, loadMatrix, showErrorToast, t],
  );

  // ③ per-code data scope override — optimistic, refetch on failure.
  const onAtomicScopeChange = useCallback(
    async (resourceCode: string, actionCode: string, scopeType: string) => {
      setMatrix((prev) =>
        prev
          ? {
              ...prev,
              modules: prev.modules.map((m) => ({
                ...m,
                resources: m.resources.map((r) => ({
                  ...r,
                  actions: r.actions.map((a) =>
                    r.resourceCode === resourceCode && a.action === actionCode ? { ...a, scopeType } : a,
                  ),
                })),
              })),
            }
          : prev,
      );
      try {
        await permissionService.updateScope(rolePid, { resourceCode, actionCode, scopeType });
      } catch {
        showErrorToast(t('admin.permission.scope.updateError', undefined, 'Failed to update data scope'));
        await loadMatrix();
      }
    },
    [rolePid, loadMatrix, showErrorToast, t],
  );

  const effective = useMemo(() => {
    const sources = deriveCodeSources(groups);
    // granted leaf permission codes from the matrix, scored for coverage by the capability view.
    const codes = (matrix?.modules ?? [])
      .flatMap((m) => m.resources)
      .flatMap((r) => r.actions)
      .filter((a) => a.granted)
      .map((a) => a.code);
    return { total: codes.length, exceptions: exceptionCount(sources, codes) };
  }, [groups, matrix]);

  if (loading) {
    return <div data-testid="capability-editor-loading">{t('common.loading', undefined, '加载中…')}</div>;
  }

  const dirty = isDirty(groups, selected);

  return (
    <div data-testid="capability-role-editor" className="flex flex-col gap-4">
      {/* ② data scope */}
      <DataScopeBar rolePid={rolePid} matrix={matrix} onScopeApplied={() => void loadMatrix()} />

      {/* ① business capabilities (primary) */}
      <div className="flex flex-col gap-3">
        <div data-testid="capability-presets" className="flex items-center gap-2">
          <span className="text-xs text-gray-500">{t('permission.capability.preset.label', undefined, '预设')}</span>
          {[
            { tier: 'viewer', label: t('permission.capability.preset.viewer', undefined, '查看者') },
            { tier: 'editor', label: t('permission.capability.preset.editor', undefined, '编辑者') },
            { tier: 'admin', label: t('permission.capability.preset.admin', undefined, '管理员') },
          ].map((p) => (
            <button
              key={p.tier}
              type="button"
              data-testid={`capability-preset-${p.tier}`}
              onClick={() => setSelected(capabilityCodesForTier(groups, p.tier))}
              className="h-7 rounded-md border border-gray-200 px-2 text-xs text-gray-700 hover:bg-gray-50"
            >
              {p.label}
            </button>
          ))}
        </div>
        <CapabilityChecklist groups={groups} selected={selected} onToggle={onToggle} />
        <div className="flex items-center justify-between">
          <span data-testid="effective-summary" className="text-xs text-gray-500">
            {t('admin.permission.effective.summary', { total: effective.total, exceptions: effective.exceptions },
              `${effective.total} effective permissions · ${effective.exceptions} exceptions`)}
          </span>
          <button
            type="button"
            data-testid="capability-save"
            disabled={!dirty || saving}
            onClick={() => void save()}
            className="h-8 rounded-md bg-blue-600 px-3 text-sm text-white disabled:opacity-50"
          >
            {saving ? t('common.saving', undefined, '保存中…') : t('common.save', undefined, '保存')}
          </button>
        </div>
      </div>

      {/* ③ advanced atomic actions (escape hatch, default collapsed) */}
      <AdvancedAtomicActions
        rolePid={rolePid}
        matrix={matrix}
        capabilityGroups={groups}
        onToggle={onAtomicToggle}
        onScopeChange={onAtomicScopeChange}
      />
    </div>
  );
}
