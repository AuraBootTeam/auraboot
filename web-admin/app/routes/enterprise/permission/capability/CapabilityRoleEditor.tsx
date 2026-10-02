import { useEffect, useState, useCallback, useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { CapabilityGroup } from './types';
import type { PermissionMatrixDTO } from '../types';
import { capabilityService } from './capabilityService';
import {
  grantedCapabilityCodes,
  toggleCapability,
  isDirty,
  capabilityCodesForTier,
  splitCapabilityGroupsForPrimaryView,
} from './capabilityHelpers';
import { deriveCodeSources, exceptionCount } from './coverageHelpers';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityChecklist from './CapabilityChecklist';
import DataScopeBar from './DataScopeBar';
import AdvancedAtomicActions from './AdvancedAtomicActions';
import ConfirmDialog from '~/ui/ConfirmDialog';

interface CapabilityRoleEditorProps {
  /** Role pid (all role-scoped endpoints key on the PID — role ids exceed JS safe-int range). */
  rolePid: string;
  onDirtyChange?: (dirty: boolean) => void;
}

/**
 * Permission v2 role editor — the primary, business-language grant surface, three orthogonal
 * dimensions kept separate (never interleaved):
 *   ② data scope (top bar + drawer) — which records,
 *   ① business capabilities (checklist) — what can be done,  ← everyday surface
 *   ③ advanced atomic actions (collapsed escape hatch) — per-code audit / exceptions.
 * The raw resource×action matrix is folded into ③; ① stays the default surface.
 */
export default function CapabilityRoleEditor({
  rolePid,
  onDirtyChange,
}: CapabilityRoleEditorProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [groups, setGroups] = useState<CapabilityGroup[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [matrix, setMatrix] = useState<PermissionMatrixDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [preview, setPreview] = useState(false);
  const [query, setQuery] = useState('');
  const [atomicUpdating, setAtomicUpdating] = useState(false);

  const capabilityView = useMemo(() => splitCapabilityGroupsForPrimaryView(groups), [groups]);
  const primaryCodes = useMemo(
    () =>
      new Set(
        capabilityView.primaryGroups.flatMap((group) =>
          group.capabilities.map((capability) => capability.code),
        ),
      ),
    [capabilityView.primaryGroups],
  );
  const primarySelected = selected.filter((code) => primaryCodes.has(code));
  const dirty = isDirty(capabilityView.primaryGroups, primarySelected);
  useEffect(() => {
    onDirtyChange?.(dirty || saving || atomicUpdating);
  }, [dirty, saving, atomicUpdating, onDirtyChange]);

  const loadGroups = useCallback(async () => {
    const fetched = await capabilityService.getForRole(rolePid);
    setGroups(fetched);
    setSelected(grantedCapabilityCodes(fetched));
    return fetched;
  }, [rolePid]);

  const loadMatrix = useCallback(async () => {
    const data = await permissionService.getMatrixForRole(rolePid);
    setMatrix(data);
    return data;
  }, [rolePid]);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const [fetched, data] = await Promise.all([
        capabilityService.getForRole(rolePid),
        permissionService.getMatrixForRole(rolePid),
      ]);
      setGroups(fetched);
      setSelected(grantedCapabilityCodes(fetched));
      setMatrix(data);
    } catch {
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  }, [rolePid]);

  useEffect(() => {
    void load();
  }, [load]);

  const onToggle = useCallback((code: string) => {
    setSelected((current) => toggleCapability(current, code));
  }, []);

  const applyPreset = useCallback(
    (tier: string) => {
      const tieredCodes = new Set(capabilityCodesForTier(capabilityView.primaryGroups, 'admin'));
      const preservedSelection = selected.filter((code) => !tieredCodes.has(code));
      setSelected([
        ...preservedSelection,
        ...capabilityCodesForTier(capabilityView.primaryGroups, tier),
      ]);
    },
    [capabilityView.primaryGroups, primaryCodes, selected],
  );

  const save = useCallback(async () => {
    setSaving(true);
    try {
      const refreshed = await capabilityService.applySelection(rolePid, selected);
      const refreshedMatrix = await permissionService.getMatrixForRole(rolePid);
      setGroups(refreshed);
      setSelected(grantedCapabilityCodes(refreshed));
      // capability grants change the underlying atomic codes (and inherit the role default scope) —
      // keep ③ in sync.
      setMatrix(refreshedMatrix);
      showSuccessToast(t('common.saveSuccess', undefined, 'Saved'));
    } catch {
      showErrorToast(t('common.saveError', undefined, 'Save failed'));
    } finally {
      setSaving(false);
    }
  }, [rolePid, selected, loadMatrix, showSuccessToast, showErrorToast, t]);

  // ③ atomic grant toggle — persist, then resync capability view (an atomic grant
  // may complete/break a capability's all-or-nothing granted state and its coverage).
  const onAtomicToggle = useCallback(
    async (permissionId: number, granted: boolean) => {
      if (dirty || saving || atomicUpdating) return;
      setAtomicUpdating(true);
      try {
        await permissionService.batchUpdateRolePermissions(rolePid, [{ permissionId, granted }]);
        // Refetch both: a grant may complete/break a capability's all-or-nothing state, and newly-
        // granted codes inherit the role's default data scope server-side — reload to surface it.
        await Promise.all([loadGroups(), loadMatrix()]);
      } catch {
        showErrorToast(
          t('admin.permission.matrix.updateError', undefined, 'Failed to update permission'),
        );
      } finally {
        setAtomicUpdating(false);
      }
    },
    [rolePid, loadGroups, loadMatrix, showErrorToast, t, dirty, saving, atomicUpdating],
  );

  // ③ per-code data scope override — persist before displaying the new scope.
  const onAtomicScopeChange = useCallback(
    async (resourceCode: string, actionCode: string, scopeType: string) => {
      if (dirty || saving || atomicUpdating) return;
      setAtomicUpdating(true);
      try {
        await permissionService.updateScope(rolePid, { resourceCode, actionCode, scopeType });
        await loadMatrix();
      } catch {
        showErrorToast(
          t('admin.permission.scope.updateError', undefined, 'Failed to update data scope'),
        );
      } finally {
        setAtomicUpdating(false);
      }
    },
    [rolePid, loadMatrix, showErrorToast, t, dirty, saving, atomicUpdating],
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
    return (
      <div data-testid="capability-editor-loading" data-role-pid={rolePid}>
        {t('common.loading', undefined, '加载中…')}
      </div>
    );
  }

  if (loadError)
    return (
      <div
        role="alert"
        data-testid="capability-editor-error"
        className="rounded-card border border-red-200 p-4 text-sm text-red-700"
      >
        {t('admin.permission.editor.loadError', undefined, 'Could not load role permissions.')}
        <button
          type="button"
          onClick={() => void load()}
          className="text-accent ml-3 hover:underline"
        >
          {t('common.retry', undefined, 'Retry')}
        </button>
      </div>
    );

  const filteredGroups = capabilityView.primaryGroups
    .map((group) => ({
      ...group,
      capabilities: group.capabilities.filter((cap) =>
        `${group.group} ${cap.label}`.toLowerCase().includes(query.trim().toLowerCase()),
      ),
    }))
    .filter((group) => group.capabilities.length > 0);
  const baseline = new Set(grantedCapabilityCodes(groups));
  const changes = capabilityView.primaryGroups
    .flatMap((group) => group.capabilities)
    .filter((cap) => baseline.has(cap.code) !== selected.includes(cap.code));
  const previewContent = changes
    .map(
      (cap) =>
        `${
          selected.includes(cap.code)
            ? t('admin.permission.editor.add', undefined, 'Grant')
            : t('admin.permission.editor.remove', undefined, 'Revoke')
        }: ${cap.label}`,
    )
    .join('\n');

  return (
    <div
      data-testid="capability-role-editor"
      data-role-pid={rolePid}
      className="flex flex-col gap-4"
    >
      {/* ② data scope */}
      <DataScopeBar
        rolePid={rolePid}
        matrix={matrix}
        onScopeApplied={loadMatrix}
        disabled={dirty || saving || atomicUpdating}
      />

      {/* ① business capabilities (primary) */}
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="text-text text-sm font-semibold">
              {t('admin.permission.editor.capabilities', undefined, 'Business capabilities')}
            </h3>
            <p className="text-text-2 mt-1 text-xs">
              {t(
                'admin.permission.editor.selection',
                { selected: primarySelected.length, total: capabilityView.primaryTotal },
                `${primarySelected.length} selected of ${capabilityView.primaryTotal}`,
              )}
            </p>
          </div>
          <input
            aria-label={t('admin.permission.editor.search', undefined, 'Search capabilities')}
            data-testid="capability-search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t('admin.permission.editor.search', undefined, 'Search capabilities')}
            className="rounded-control border-border bg-panel text-text h-8 border px-3 text-sm"
          />
        </div>
        <div data-testid="capability-presets" className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-gray-500">
            {t('permission.capability.preset.label', undefined, '预设')}
          </span>
          {[
            {
              tier: 'viewer',
              label: t('permission.capability.preset.viewer', undefined, '查看者'),
            },
            {
              tier: 'editor',
              label: t('permission.capability.preset.editor', undefined, '编辑者'),
            },
            { tier: 'admin', label: t('permission.capability.preset.admin', undefined, '管理员') },
          ].map((p) => (
            <button
              key={p.tier}
              type="button"
              data-testid={`capability-preset-${p.tier}`}
              onClick={() => applyPreset(p.tier)}
              disabled={saving || atomicUpdating}
              className="h-7 rounded-md border border-gray-200 px-2 text-xs text-gray-700 hover:bg-gray-50"
            >
              {p.label}
            </button>
          ))}
        </div>
        <CapabilityChecklist
          groups={filteredGroups}
          selected={selected}
          onToggle={onToggle}
          disabled={saving || atomicUpdating}
        />
        {filteredGroups.length === 0 && (
          <p data-testid="capability-search-empty" className="text-text-2 py-4 text-sm">
            {t('admin.permission.editor.empty', undefined, 'No matching business capabilities')}
          </p>
        )}
        {dirty && (
          <div
            data-testid="capability-draft"
            className="rounded-card border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"
          >
            {t(
              'admin.permission.editor.draft',
              { count: changes.length },
              `${changes.length} pending changes. Save or discard before editing data scope or advanced permissions.`,
            )}
          </div>
        )}
        {capabilityView.advancedTotal > 0 && (
          <div
            data-testid="advanced-capability-summary"
            className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-xs text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300"
          >
            {t(
              'admin.permission.generated.summary',
              { granted: capabilityView.advancedGranted, total: capabilityView.advancedTotal },
              `高级模型/系统权限已收纳: ${capabilityView.advancedGranted}/${capabilityView.advancedTotal}`,
            )}
          </div>
        )}
        <div className="flex items-center justify-between">
          <span data-testid="effective-summary" className="text-xs text-gray-500">
            {t(
              'admin.permission.effective.summary',
              { total: effective.total, exceptions: effective.exceptions },
              `${effective.total} granted actions · ${effective.exceptions} not covered by declared capabilities`,
            )}
          </span>
          <div className="flex items-center gap-2">
            {dirty && (
              <button
                type="button"
                data-testid="capability-discard"
                disabled={saving}
                onClick={() => setSelected(grantedCapabilityCodes(groups))}
                className="rounded-control border-border text-text-2 h-8 border px-3 text-sm"
              >
                {t('admin.permission.editor.discard', undefined, 'Discard changes')}
              </button>
            )}
            <button
              type="button"
              data-testid="capability-save"
              disabled={!dirty || saving || atomicUpdating}
              onClick={() => setPreview(true)}
              className="h-8 rounded-md bg-blue-600 px-3 text-sm text-white disabled:opacity-50"
            >
              {saving
                ? t('common.saving', undefined, 'Saving…')
                : t('admin.permission.editor.reviewSave', undefined, 'Review and save')}
            </button>
          </div>
        </div>
      </div>

      {/* ③ advanced atomic actions (escape hatch, default collapsed) */}
      <AdvancedAtomicActions
        rolePid={rolePid}
        matrix={matrix}
        capabilityGroups={groups}
        onToggle={onAtomicToggle}
        onScopeChange={onAtomicScopeChange}
        disabled={dirty || saving || atomicUpdating}
      />
      <ConfirmDialog
        open={preview}
        title={t('admin.permission.editor.preview', undefined, 'Review permission changes')}
        content={previewContent}
        confirmText={t('common.save', undefined, 'Save')}
        onCancel={() => setPreview(false)}
        onConfirm={() => {
          setPreview(false);
          void save();
        }}
      />
    </div>
  );
}
