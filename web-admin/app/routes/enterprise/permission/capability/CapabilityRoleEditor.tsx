import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { Capability, CapabilityGroup, CapabilitySelectionPreview } from './types';
import type { PermissionMatrixDTO } from '../types';
import { capabilityService } from './capabilityService';
import {
  grantedCapabilityCodes,
  toggleCapability,
  isDirty,
  capabilityCodesForTier,
  splitCapabilityGroupsForPrimaryView,
} from './capabilityHelpers';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityChecklist from './CapabilityChecklist';
import DataScopeBar from './DataScopeBar';
import CapabilityDiagnostics from './CapabilityDiagnostics';
import CapabilityScopeSettings from './CapabilityScopeSettings';
import ConfirmDialog from '~/ui/ConfirmDialog';

interface CapabilityRoleEditorProps {
  /** Role pid (all role-scoped endpoints key on the PID — role ids exceed JS safe-int range). */
  rolePid: string;
  onDirtyChange?: (dirty: boolean) => void;
  readOnly?: boolean;
  scopeReadOnly?: boolean;
}

/**
 * Permission v2 role editor — the primary, business-language grant surface, three orthogonal
 * dimensions kept separate (never interleaved):
 *   ② data scope (top bar + drawer) — which records,
 *   ① business capabilities (checklist) — what can be done,  ← everyday surface
 *   ③ read-only diagnostics — per-code audit.
 * The raw resource×action matrix is folded into ③; ① stays the default surface.
 */
export default function CapabilityRoleEditor({
  rolePid,
  onDirtyChange,
  readOnly = false,
  scopeReadOnly = readOnly,
}: CapabilityRoleEditorProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [groups, setGroups] = useState<CapabilityGroup[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [revokedPartial, setRevokedPartial] = useState<string[]>([]);
  const [matrix, setMatrix] = useState<PermissionMatrixDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [preview, setPreview] = useState(false);
  const [query, setQuery] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewPlan, setPreviewPlan] = useState<CapabilitySelectionPreview | null>(null);
  const [scopeCapability, setScopeCapability] = useState<Capability | null>(null);
  const [scopeUpdating, setScopeUpdating] = useState(false);
  const loadRequest = useRef(0);

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
  const scopeConfigurableCodes = useMemo(() => {
    const actions = (matrix?.modules ?? [])
      .flatMap((module) => module.resources.flatMap((resource) => resource.actions))
      .filter(
        (action) =>
          action.granted && (action.code.startsWith('model.') || action.scopeType != null),
      );
    return new Set(
      capabilityView.primaryGroups
        .flatMap((group) => group.capabilities)
        .filter((cap) => actions.some((action) => cap.includes.includes(action.code)))
        .map((cap) => cap.code),
    );
  }, [matrix, capabilityView.primaryGroups]);
  const primarySelected = selected.filter((code) => primaryCodes.has(code));
  const dirty = isDirty(capabilityView.primaryGroups, primarySelected) || revokedPartial.length > 0;
  useEffect(() => {
    onDirtyChange?.(dirty || saving || previewLoading || scopeUpdating || scopeCapability !== null);
  }, [dirty, saving, previewLoading, scopeUpdating, scopeCapability, onDirtyChange]);

  const loadMatrix = useCallback(async () => {
    const data = await permissionService.getMatrixForRole(rolePid);
    setMatrix(data);
    return data;
  }, [rolePid]);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    setLoading(true);
    setLoadError(false);
    setScopeCapability(null);
    setRevokedPartial([]);
    setPreview(false);
    setPreviewPlan(null);
    try {
      const [fetched, data] = await Promise.all([
        capabilityService.getForRole(rolePid),
        permissionService.getMatrixForRole(rolePid),
      ]);
      // StrictMode can launch two reads. Only the current initialization may seed a draft.
      if (request !== loadRequest.current) return;
      setGroups(fetched);
      setSelected(
        grantedCapabilityCodes(fetched).filter((code) =>
          fetched.some((group) =>
            group.capabilities.some((cap) => cap.code === code && !cap.conventionDerived),
          ),
        ),
      );
      setMatrix(data);
    } catch {
      if (request === loadRequest.current) setLoadError(true);
    } finally {
      if (request === loadRequest.current) setLoading(false);
    }
  }, [rolePid, readOnly, scopeReadOnly]);

  useEffect(() => {
    void load();
    return () => {
      loadRequest.current++;
    };
  }, [load]);

  const onToggle = useCallback((code: string) => {
    if (readOnly) return;
    setRevokedPartial((current) => current.filter((item) => item !== code));
    setSelected((current) => toggleCapability(current, code));
  }, [readOnly]);

  const applyPreset = useCallback(
    (tier: string) => {
      if (readOnly) return;
      const tieredCodes = new Set(capabilityCodesForTier(capabilityView.primaryGroups, 'admin'));
      const preservedSelection = selected.filter((code) => !tieredCodes.has(code));
      const nextSelection = [
        ...preservedSelection,
        ...capabilityCodesForTier(capabilityView.primaryGroups, tier),
      ];
      setSelected(nextSelection);
      setRevokedPartial((current) => current.filter((code) => !nextSelection.includes(code)));
    },
    [capabilityView.primaryGroups, primaryCodes, selected, readOnly],
  );

  const save = useCallback(async () => {
    if (readOnly) return;
    setSaving(true);
    let submitted = false;
    try {
      const refreshed = await (revokedPartial.length
        ? capabilityService.applySelection(rolePid, primarySelected, undefined, revokedPartial)
        : capabilityService.applySelection(rolePid, primarySelected));
      submitted = true;
      const refreshedMatrix = await permissionService.getMatrixForRole(rolePid);
      setGroups(refreshed);
      setRevokedPartial([]);
      setSelected(
        grantedCapabilityCodes(refreshed).filter((code) =>
          refreshed.some((group) =>
            group.capabilities.some((cap) => cap.code === code && !cap.conventionDerived),
          ),
        ),
      );
      // capability grants change the underlying atomic codes (and inherit the role default scope) —
      // keep ③ in sync.
      setMatrix(refreshedMatrix);
      showSuccessToast(t('common.saveSuccess', undefined, 'Saved'));
    } catch {
      if (submitted) setLoadError(true);
      showErrorToast(
        submitted
          ? t(
              'admin.permission.editor.readbackErrorV2',
              undefined,
              'Permissions were saved, but the result could not be loaded. Reload before continuing.',
            )
          : t('common.saveError', undefined, 'Save failed'),
      );
    } finally {
      setSaving(false);
    }
  }, [rolePid, selected, primarySelected, revokedPartial, showSuccessToast, showErrorToast, t, readOnly]);

  const reviewChanges = async () => {
    if (readOnly || !dirty || saving || previewLoading) return;
    setPreviewLoading(true);
    const request = loadRequest.current;
    try {
      const plan = await (revokedPartial.length
        ? capabilityService.previewSelection(rolePid, primarySelected, revokedPartial)
        : capabilityService.previewSelection(rolePid, primarySelected));
      if (request !== loadRequest.current) return;
      setPreviewPlan(plan);
      setPreview(true);
    } catch {
      showErrorToast(
        t(
          'admin.permission.editor.previewErrorV2',
          undefined,
          'Could not calculate permission changes. Your draft is preserved.',
        ),
      );
    } finally {
      setPreviewLoading(false);
    }
  };

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
    .filter(
      (cap) =>
        baseline.has(cap.code) !== selected.includes(cap.code) || revokedPartial.includes(cap.code),
    );
  const actionLabels = new Map(
    (matrix?.modules ?? []).flatMap((module) =>
      module.resources.flatMap((resource) =>
        resource.actions.map(
          (action) =>
            [action.code, t(`permission.${action.code}`, undefined, action.label)] as const,
        ),
      ),
    ),
  );
  const changedCodes = new Set(changes.map((cap) => cap.code));
  const secondaryPartial = (previewPlan?.resultingCapabilities ?? []).filter(
    (cap) => cap.authorizationState === 'partial' && !changedCodes.has(cap.code),
  );
  const primaryImpact = (previewPlan?.resultingCapabilities ?? []).filter(
    (cap) => cap.authorizationState !== 'partial' || changedCodes.has(cap.code),
  );
  const resulting = previewPlan?.resultingCapabilities ?? [];
  const fullCount = resulting.filter((cap) => cap.authorizationState === 'full').length;
  const partialCount = resulting.filter((cap) => cap.authorizationState === 'partial').length;
  const noneCount = resulting.filter((cap) => cap.authorizationState === 'none').length;
  const renderImpact = (cap: Capability) => (
    <li key={cap.code} data-testid={`capability-preview-impact-${cap.code}`}>
      {cap.label}:{' '}
      {t(
        `admin.permission.capability.${cap.authorizationState}V2`,
        undefined,
        cap.authorizationState ?? '',
      )}
    </li>
  );
  const previewContent = previewPlan ? (
    <div className="space-y-4" data-testid="capability-preview-summary">
      <ul className="space-y-1">
        {changes.map((cap) => (
          <li key={cap.code}>
            {selected.includes(cap.code)
              ? t('admin.permission.editor.add', undefined, 'Grant')
              : t('admin.permission.editor.remove', undefined, 'Revoke')}
            : {cap.label}
          </li>
        ))}
      </ul>
      <p>
        {t(
          'admin.permission.editor.atomicDiffV2',
          {
            added: previewPlan.grantedCodes.length,
            removed: previewPlan.revokedCodes.length,
            preserved: previewPlan.preservedCodes.length,
          },
          `Add ${previewPlan.grantedCodes.length} actions, remove ${previewPlan.revokedCodes.length}; ${previewPlan.preservedCodes.length} existing actions outside the selection remain unchanged.`,
        )}
      </p>
      {resulting.length > 0 && (
        <div className="space-y-1">
          <p data-testid="capability-preview-state-guidance" className="text-text-2 text-xs leading-5">
            {t(
              'admin.permission.capability.partialGuidanceV2',
              undefined,
              'Some actions may be shared dependencies of other capabilities. Partial actions do not grant the complete capability. Select to complete; review revocation effects before saving.',
            )}
          </p>
        </div>
      )}
      <details
        data-testid="capability-preview-impact"
        className="border-border rounded-card border p-3"
      >
        <summary className="cursor-pointer font-medium">
          {t(
            'admin.permission.editor.impactStateDetailsV2',
            undefined,
            'Review related capability states, menus and action details',
          )}
        </summary>
        <div className="mt-3 space-y-3">
          <p
            data-testid="capability-preview-state-counts"
            data-full={fullCount}
            data-partial={partialCount}
            data-none={noneCount}
            className="text-text text-sm font-medium"
          >
            {t(
              'admin.permission.editor.previewStateCountsV2',
              { full: fullCount, partial: partialCount, none: noneCount },
              `Related capabilities after saving: ${fullCount} fully granted · ${partialCount} with partial actions · ${noneCount} not granted`,
            )}
          </p>
          <div data-testid="capability-preview-resulting" className="space-y-3">
            {primaryImpact.length > 0 && (
              <ul className="space-y-1">{primaryImpact.map(renderImpact)}</ul>
            )}
            {secondaryPartial.length > 0 && (
              <details data-testid="capability-preview-partial-impact">
                <summary className="text-text-2 cursor-pointer">
                  {t('admin.permission.capability.partialV2', undefined, 'Partial actions')}
                  {' · '}
                  {secondaryPartial.length}
                </summary>
                <p className="text-text-2 mt-2 text-xs leading-5">
                  {t(
                    'admin.permission.capability.partialGuidanceV2',
                    undefined,
                    'Some actions may be shared dependencies of other capabilities. Partial actions do not grant the complete capability. Select to complete; review revocation effects before saving.',
                  )}
                </p>
                <ul className="mt-2 space-y-1">{secondaryPartial.map(renderImpact)}</ul>
              </details>
            )}
          </div>
          {previewPlan.relatedMenus.length > 0 && (
            <details data-testid="capability-preview-menus">
              <summary className="text-text-2 cursor-pointer">
                {t('admin.permission.capability.relatedMenusV2', undefined, 'Related menus')}
                {' · '}
                {previewPlan.relatedMenus.length}
              </summary>
              <ul className="mt-2 space-y-1">
                {previewPlan.relatedMenus.map((name) => (
                  <li key={name}>{name}</li>
                ))}
              </ul>
            </details>
          )}
          <ul className="space-y-1">
            {previewPlan.revokedCodes.map((code) => (
              <li key={code}>
                {t('admin.permission.editor.remove', undefined, 'Revoke')}:{' '}
                {actionLabels.get(code) ??
                  t('admin.permission.diagnostics.unmappedV2', undefined, 'No declared capability')}
              </li>
            ))}
          </ul>
        </div>
      </details>
      <p className="text-text-3">
        {t(
          'admin.permission.editor.roleLocalV2',
          undefined,
          'This changes this role only. Other roles, record scopes and sharing still determine user access.',
        )}
      </p>
    </div>
  ) : (
    ''
  );

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
        disabled={scopeReadOnly || dirty || saving || previewLoading || scopeUpdating}
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
              disabled={readOnly || saving || previewLoading || scopeUpdating}
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
          revokedPartial={revokedPartial}
          onRevokePartial={(code) => {
            if (readOnly) return;
            setSelected((current) => current.filter((item) => item !== code));
            setRevokedPartial((current) => toggleCapability(current, code));
          }}
          onConfigureScope={scopeReadOnly || dirty ? undefined : setScopeCapability}
          scopeConfigurableCodes={scopeConfigurableCodes}
          disabled={readOnly || saving || previewLoading || scopeUpdating}
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
              'admin.permission.editor.draftV2',
              { count: changes.length },
              `${changes.length} pending changes. Save or discard before editing record scope.`,
            )}
          </div>
        )}
        <div className="flex items-center justify-end">
          <div className="flex items-center gap-2">
            {dirty && (
              <button
                type="button"
                data-testid="capability-discard"
                disabled={saving}
                onClick={() => {
                  setSelected(grantedCapabilityCodes(capabilityView.primaryGroups));
                  setRevokedPartial([]);
                }}
                className="rounded-control border-border text-text-2 h-8 border px-3 text-sm"
              >
                {t('admin.permission.editor.discard', undefined, 'Discard changes')}
              </button>
            )}
            <button
              type="button"
              data-testid="capability-save"
              disabled={readOnly || !dirty || saving || previewLoading}
              onClick={() => void reviewChanges()}
              className="h-8 rounded-md bg-blue-600 px-3 text-sm text-white disabled:opacity-50"
            >
              {saving
                ? t('common.saving', undefined, 'Saving…')
                : t('admin.permission.editor.reviewSave', undefined, 'Review and save')}
            </button>
          </div>
        </div>
      </div>

      <CapabilityDiagnostics matrix={matrix} groups={groups} />
      {scopeCapability && !scopeReadOnly && (
        <CapabilityScopeSettings
          rolePid={rolePid}
          capability={scopeCapability}
          matrix={matrix}
          onClose={() => setScopeCapability(null)}
          onRefresh={loadMatrix}
          onBusy={setScopeUpdating}
          onReadFailure={() => setLoadError(true)}
        />
      )}

      <ConfirmDialog
        open={preview && !readOnly}
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
