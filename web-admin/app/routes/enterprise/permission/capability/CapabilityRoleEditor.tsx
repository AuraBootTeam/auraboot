import { roleNavigationService } from './roleNavigationService';
import RoleGrantFieldsEditor, {
  type GrantFieldChange,
  type GrantFieldBinding,
} from './RoleGrantFieldsEditor';
import RoleObjectScopeSettings from './RoleObjectScopeSettings';
import RoleFieldAccessGrid, { type FieldAccessChange } from './RoleFieldAccessGrid';
import { getLocalizedText } from '~/utils/i18n';
import { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { Capability, CapabilityGroup, CapabilitySelectionPreview } from './types';
import type { PermissionMatrixDTO } from '../types';
import {
  authorizationDraftService,
  AuthorizationConflict,
  draftMatrix,
  type AuthorizationSnapshot,
  type AuthorizationDraft,
  type AuthorizationPreview,
  type ScopeChange,
} from './authorizationDraftService';
import {
  grantedCapabilityCodes,
  toggleCapability,
  isDirty,
  capabilityCodesForTier,
  splitCapabilityGroupsForPrimaryView,
} from './capabilityHelpers';
import CapabilityChecklist from './CapabilityChecklist';
import DataScopeBar from './DataScopeBar';
import { scopeOption } from '../scopeConfig';
import CapabilityDiagnostics from './CapabilityDiagnostics';
import CapabilityScopeSettings from './CapabilityScopeSettings';
import ConfirmDialog from '~/ui/ConfirmDialog';
import FieldPolicyChecklist from './FieldPolicyChecklist';
import RoleMenuChecklist from './RoleMenuChecklist';
import UserNavigationPreviewDialog, { type ActiveRoleDraft } from './UserNavigationPreviewDialog';

export type RoleDraftStatus = 'loading' | 'failed' | 'conflict' | 'saving' | 'dirty' | 'saved';
interface CapabilityRoleEditorProps {
  onStatusChange?: (status: RoleDraftStatus) => void;
  /** Role pid (all role-scoped endpoints key on the PID — role ids exceed JS safe-int range). */
  rolePid: string;
  onDirtyChange?: (dirty: boolean) => void;
  readOnly?: boolean;
  scopeReadOnly?: boolean;
  activeSection?: 'menus' | 'capabilities' | 'scope';
  onDraftChange?: (draft: ActiveRoleDraft | null) => void;
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
  onStatusChange,
  readOnly = false,
  scopeReadOnly = readOnly,
  activeSection = 'capabilities',
  onDraftChange,
}: CapabilityRoleEditorProps) {
  const { t, locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [grantFieldChanges, setGrantFieldChanges] = useState<GrantFieldChange[]>([]);
  const [grantFieldCandidates, setGrantFieldCandidates] = useState<
    GrantFieldBinding[] | undefined
  >();
  const [grantFieldsLoading, setGrantFieldsLoading] = useState(false);
  const [grantFieldsError, setGrantFieldsError] = useState(false);
  const [grantFieldsAttempt, setGrantFieldsAttempt] = useState(0);
  const [fieldAccessChanges, setFieldAccessChanges] = useState<FieldAccessChange[]>([]);
  const [menuSelection, setMenuSelection] = useState<string[] | null>(null);
  const [subjectPreview, setSubjectPreview] = useState(false);
  const [previewMenuPid, setPreviewMenuPid] = useState<string | undefined>();
  const [navigationProjection, setNavigationProjection] = useState<Record<string, boolean> | null>(
    null,
  );
  const [navigationProjectionError, setNavigationProjectionError] = useState(false);
  const [groups, setGroups] = useState<CapabilityGroup[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [revokedPartial, setRevokedPartial] = useState<string[]>([]);
  const [matrix, setMatrix] = useState<PermissionMatrixDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [preview, setPreview] = useState(false);
  const [query, setQuery] = useState('');
  const [activeGroup, setActiveGroup] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewPlan, setPreviewPlan] = useState<CapabilitySelectionPreview | null>(null);
  const [scopeCapability, setScopeCapability] = useState<Capability | null>(null);
  const [baselineSnapshot, setBaselineSnapshot] = useState<AuthorizationSnapshot | null>(null);
  const [defaultScope, setDefaultScope] = useState<string | null>(null);
  const [applyDefault, setApplyDefault] = useState(false);
  const [fieldSelection, setFieldSelection] = useState<string[] | null>(null);
  const [scopeChanges, setScopeChanges] = useState<ScopeChange[]>([]);
  const [scopeOpen, setScopeOpen] = useState(false);
  const [scopeLabels, setScopeLabels] = useState<Record<string, string>>({});
  const [conflict, setConflict] = useState(false);
  const [draftPreview, setDraftPreview] = useState<AuthorizationPreview | null>(null);
  const loadRequest = useRef(0);
  const reviewedDraft = useRef<AuthorizationDraft | null>(null);

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
  // Editing projection only. The server validates grants and scopes again before preview/save.
  const projectedMatrix = useMemo(() => {
    if (!matrix) return null;
    const current = new Set(
      matrix.modules.flatMap((m) =>
        m.resources.flatMap((r) => r.actions.filter((a) => a.granted).map((a) => a.code)),
      ),
    );
    const caps = groups.flatMap((g) => g.capabilities).filter((c) => !c.conventionDerived);
    const desired = new Set(
      caps.filter((c) => selected.includes(c.code)).flatMap((c) => c.includes),
    );
    const revokable = new Set(
      caps
        .filter(
          (c) => c.includes.every((code) => current.has(code)) || revokedPartial.includes(c.code),
        )
        .flatMap((c) => c.includes),
    );
    return {
      modules: matrix.modules.map((module) => ({
        ...module,
        resources: module.resources.map((resource) => ({
          ...resource,
          actions: resource.actions.map((action) => {
            const granted =
              desired.has(action.code) || (current.has(action.code) && !revokable.has(action.code));
            const staged = scopeChanges.find(
              (c) => c.resourceCode === resource.resourceCode && c.actionCode === action.action,
            );
            return {
              ...action,
              granted,
              scopeType:
                staged?.scopeType ??
                (applyDefault || (granted && !action.granted) ? defaultScope : action.scopeType),
            };
          }),
        })),
      })),
    };
  }, [matrix, groups, selected, revokedPartial, scopeChanges, applyDefault, defaultScope]);
  const activeScopeChanges = scopeChanges.filter((change) =>
    projectedMatrix?.modules.some((module) =>
      module.resources.some(
        (resource) =>
          resource.resourceCode === change.resourceCode &&
          resource.actions.some((action) => action.action === change.actionCode && action.granted),
      ),
    ),
  );
  const defaultChanged =
    defaultScope !== (baselineSnapshot?.defaultScopeType ?? null) || applyDefault;
  const scopeConfigurableCodes = useMemo(() => {
    const actions = (projectedMatrix?.modules ?? [])
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
  }, [projectedMatrix, capabilityView.primaryGroups]);
  const primarySelected = selected.filter((code) => primaryCodes.has(code));
  const boundFields = baselineSnapshot?.fieldPolicies;
  const fieldBaseline = (boundFields ?? []).flatMap((policy) =>
    typeof policy.policyPid === 'string' ? [policy.policyPid] : [],
  );
  const selectedFields = fieldSelection ?? fieldBaseline;
  const fieldsDirty =
    fieldSelection !== null &&
    JSON.stringify([...fieldSelection].sort()) !== JSON.stringify([...fieldBaseline].sort());
  const fieldModels = new Set(
    (projectedMatrix?.modules ?? []).flatMap((module) =>
      module.resources
        .filter((resource) =>
          resource.actions.some((action) => action.granted && action.code.startsWith('model.')),
        )
        .map((resource) => resource.resourceCode),
    ),
  );

  const dirty =
    isDirty(capabilityView.primaryGroups, primarySelected) ||
    revokedPartial.length > 0 ||
    defaultChanged ||
    activeScopeChanges.length > 0 ||
    fieldsDirty ||
    fieldAccessChanges.length > 0 ||
    grantFieldChanges.length > 0 ||
    menuSelection !== null;
  useEffect(() => {
    onStatusChange?.(
      loading
        ? 'loading'
        : loadError
          ? 'failed'
          : conflict
            ? 'conflict'
            : saving || previewLoading
              ? 'saving'
              : dirty
                ? 'dirty'
                : 'saved',
    );
  }, [loading, loadError, conflict, saving, previewLoading, dirty, onStatusChange]);

  useEffect(() => {
    onDirtyChange?.(dirty || saving || previewLoading || scopeCapability !== null || scopeOpen);
  }, [dirty, saving, previewLoading, scopeCapability, scopeOpen, onDirtyChange]);

  const acceptSnapshot = useCallback((snapshot: AuthorizationSnapshot) => {
    const data = draftMatrix(snapshot.matrix);
    reviewedDraft.current = null;
    setBaselineSnapshot(snapshot);
    setDefaultScope(snapshot.defaultScopeType);
    setApplyDefault(false);
    setScopeChanges([]);
    setFieldSelection(null);
    setFieldAccessChanges([]);
    setGrantFieldChanges([]);
    setGrantFieldCandidates(undefined);
    setGrantFieldsError(false);
    setMenuSelection(null);
    setScopeLabels({});
    setConflict(false);
    setDraftPreview(null);
    setRevokedPartial([]);
    setGroups(snapshot.groups);
    setSelected(
      grantedCapabilityCodes(snapshot.groups).filter((code) =>
        snapshot.groups.some((group) =>
          group.capabilities.some((cap) => cap.code === code && !cap.conventionDerived),
        ),
      ),
    );
    setMatrix(data);
  }, []);
  const draftBody = useMemo<AuthorizationDraft>(
    () => ({
      expectedRevision: baselineSnapshot?.revision ?? '',
      selectedCapabilityCodes: primarySelected,
      revokedPartialCapabilityCodes: revokedPartial,
      defaultScopeChanged: defaultChanged,
      defaultScopeType: defaultChanged ? defaultScope : null,
      applyDefaultToCurrentGrants: applyDefault,
      scopeChanges: activeScopeChanges,
      ...(grantFieldChanges.length ? { grantFieldChanges } : {}),
      ...(fieldAccessChanges.length ? { fieldChanges: fieldAccessChanges } : {}),
      ...(fieldsDirty ? { fieldPolicyPids: selectedFields } : {}),
      ...(menuSelection !== null ? { menuPids: menuSelection } : {}),
    }),
    [
      baselineSnapshot?.revision,
      JSON.stringify(primarySelected),
      JSON.stringify(revokedPartial),
      defaultChanged,
      defaultScope,
      applyDefault,
      JSON.stringify(activeScopeChanges),
      fieldsDirty,
      JSON.stringify(selectedFields),
      JSON.stringify(menuSelection),
      JSON.stringify(fieldAccessChanges),
      JSON.stringify(grantFieldChanges),
    ],
  );
  useEffect(() => {
    if (readOnly || activeSection !== 'menus' || !baselineSnapshot?.navigation) return;
    let current = true;
    setNavigationProjection(null);
    setNavigationProjectionError(false);
    void roleNavigationService
      .preview({ rolePids: [rolePid], draftRolePid: rolePid, draft: draftBody })
      .then((result) => {
        if (current)
          setNavigationProjection(
            Object.fromEntries(result.menus.map((menu) => [menu.pid, menu.businessAllowed])),
          );
      })
      .catch(() => {
        if (current) setNavigationProjectionError(true);
      });
    return () => {
      current = false;
    };
  }, [readOnly, activeSection, rolePid, draftBody, baselineSnapshot?.navigation]);
  // Candidate discovery never writes grants or rebases an existing field edit's metadata token.
  const candidateRequest = JSON.stringify({ ...draftBody, grantFieldChanges: undefined });
  useEffect(() => {
    if (loading || activeSection !== 'scope' || baselineSnapshot?.rolePid !== rolePid) return;
    if (readOnly) {
      setGrantFieldCandidates(baselineSnapshot?.grantFields);
      setGrantFieldsLoading(false);
      return;
    }
    let current = true;
    setGrantFieldsLoading(true);
    setGrantFieldsError(false);
    setGrantFieldCandidates(undefined);
    void authorizationDraftService
      .preview(rolePid, JSON.parse(candidateRequest) as AuthorizationDraft)
      .then((plan) => {
        if (!plan.grantFieldCandidates) throw new Error('Missing grant field candidate contract');
        if (current) setGrantFieldCandidates(plan.grantFieldCandidates);
      })
      .catch((error) => {
        if (!current) return;
        setGrantFieldsError(true);
        if (error instanceof AuthorizationConflict) setConflict(true);
      })
      .finally(() => {
        if (current) setGrantFieldsLoading(false);
      });
    return () => {
      current = false;
    };
  }, [
    rolePid,
    loading,
    activeSection,
    baselineSnapshot,
    readOnly,
    candidateRequest,
    grantFieldsAttempt,
  ]);
  const requestBody = () => draftBody;
  useEffect(() => {
    onDraftChange?.(
      baselineSnapshot ? { rolePid: baselineSnapshot.rolePid, draft: draftBody } : null,
    );
  }, [draftBody, baselineSnapshot, onDraftChange]);
  useEffect(() => () => onDraftChange?.(null), [onDraftChange]);

  const load = useCallback(async () => {
    const request = ++loadRequest.current;
    setLoading(true);
    setLoadError(false);
    setScopeCapability(null);
    setScopeOpen(false);
    setRevokedPartial([]);
    setPreview(false);
    setPreviewPlan(null);
    try {
      const snapshot = await authorizationDraftService.read(rolePid);
      // StrictMode and role switches cannot seed a draft from an obsolete response.
      if (request !== loadRequest.current) return;
      acceptSnapshot(snapshot);
    } catch {
      if (request === loadRequest.current) setLoadError(true);
    } finally {
      if (request === loadRequest.current) setLoading(false);
    }
  }, [rolePid, readOnly, scopeReadOnly, acceptSnapshot]);

  useEffect(() => {
    void load();
    return () => {
      loadRequest.current++;
    };
  }, [load]);

  const onToggle = useCallback(
    (code: string) => {
      if (readOnly) return;
      setRevokedPartial((current) => current.filter((item) => item !== code));
      setSelected((current) => toggleCapability(current, code));
    },
    [readOnly],
  );

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

  const save = async () => {
    if (readOnly || !baselineSnapshot || conflict || !reviewedDraft.current) return;
    setSaving(true);
    const request = loadRequest.current;
    try {
      const snapshot = await authorizationDraftService.save(rolePid, reviewedDraft.current);
      if (request !== loadRequest.current) return;
      acceptSnapshot(snapshot);
      window.dispatchEvent(new Event('menu:refresh'));
      showSuccessToast(t('common.saveSuccess', undefined, 'Saved'));
    } catch (error) {
      if (request !== loadRequest.current) return;
      if (error instanceof AuthorizationConflict) setConflict(true);
      showErrorToast(
        t(
          'admin.permission.editor.draftSaveError',
          undefined,
          'Could not confirm the save. Your draft is preserved; reload to check the current configuration.',
        ),
      );
    } finally {
      setSaving(false);
    }
  };

  const reviewChanges = async () => {
    if (readOnly || !dirty || saving || previewLoading || conflict) return;
    setPreviewLoading(true);
    const request = loadRequest.current;
    try {
      const body = requestBody();
      const plan = await authorizationDraftService.preview(rolePid, body);
      if (request !== loadRequest.current) return;
      reviewedDraft.current = body;
      setPreviewPlan(plan.capabilities);
      setDraftPreview(plan);
      setPreview(true);
    } catch (error) {
      if (request !== loadRequest.current) return;
      if (error instanceof AuthorizationConflict) setConflict(true);
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
    .filter((group) => activeGroup === null || group.group === activeGroup)
    .map((group) => ({
      ...group,
      capabilities: group.capabilities.filter((cap) =>
        `${group.group === null ? '' : t(`permission.module.${group.group}`, undefined, group.group)} ${getLocalizedText(cap.localizedLabels, locale, t) || cap.label}`
          .toLowerCase()
          .includes(query.trim().toLowerCase()),
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
      {getLocalizedText(cap.localizedLabels, locale, t) || cap.label}:{' '}
      {t(
        `admin.permission.capability.${cap.authorizationState}V2`,
        undefined,
        cap.authorizationState ?? '',
      )}
    </li>
  );
  const previewContent = previewPlan ? (
    <div className="space-y-4" data-testid="capability-preview-summary">
      {!!draftPreview?.grantFieldChanges?.length && (
        <section
          data-testid="draft-preview-grant-fields"
          className="border-border rounded-control space-y-2 border p-3 text-xs"
        >
          <h3 className="font-medium">
            {t('roleGrantFields.title', undefined, 'Allowed fields per authorization')}
          </h3>
          {draftPreview.grantFieldChanges.map((change, index) => {
            const binding = change.before;
            const label = (code: string) => {
              const field = binding?.fields?.find((item) => item.code === code);
              return t(
                `model.${binding?.modelCode}.${code}.label`,
                undefined,
                field?.label && field.label !== code
                  ? field.label
                  : t('roleFields.unnamedField', undefined, 'Field definition unavailable'),
              );
            };
            return (
              <div key={index}>
                <p className="font-medium">
                  {binding?.modelLabel ||
                    t(
                      'roleFields.unnamedModel',
                      undefined,
                      'Business object definition unavailable',
                    )}{' '}
                  ·{' '}
                  {t(
                    `roleGrantFields.action.${binding?.permissionCode?.split('.').at(-1)}`,
                    undefined,
                    t('roleGrantFields.authorization', undefined, 'Authorization'),
                  )}
                </p>
                <p>
                  {t('roleGrantFields.read', undefined, 'May read')}:{' '}
                  {change.readFields?.map(label).join('、') ||
                    t('roleGrantFields.none', undefined, 'No fields')}
                </p>
                <p>
                  {t('roleGrantFields.write', undefined, 'May write')}:{' '}
                  {change.writeFields?.map(label).join('、') ||
                    t('roleGrantFields.none', undefined, 'No fields')}
                </p>
              </div>
            );
          })}
        </section>
      )}
      {!!draftPreview?.fieldAccessChanges?.length && (
        <section
          data-testid="draft-preview-field-access"
          className="border-border rounded-control border p-3 text-xs"
        >
          <h3 className="mb-2 font-medium">{t('roleFields.title', undefined, 'Field access')}</h3>
          {draftPreview.fieldAccessChanges.map((change) => (
            <p key={change.modelCode + ':' + change.fieldCode}>
              {change.modelLabel} · {change.fieldLabel}:{' '}
              {t('roleFields.' + change.beforeAccess, undefined, change.beforeAccess)} →{' '}
              {t('roleFields.' + change.access, undefined, change.access)}
            </p>
          ))}
        </section>
      )}

      {draftPreview?.navigation && (
        <section
          data-testid="draft-preview-menus"
          className="border-border rounded-control border p-3"
        >
          <h3 className="text-text-1 text-sm font-medium">
            {t('roleNavigation.reviewMenus', undefined, 'Visible menu changes')}
          </h3>
          <p className="text-text-2 mt-1 text-xs">
            {t(
              'roleNavigation.selected',
              { count: draftPreview.navigation.selectedMenuPids.length },
              `${draftPreview.navigation.selectedMenuPids.length} selected`,
            )}
          </p>
          <ul className="mt-2 text-xs">
            {draftPreview.navigation.menus
              .filter(
                (m) => !m.directory && draftPreview.navigation!.selectedMenuPids.includes(m.pid),
              )
              .map((m) => (
                <li key={m.pid}>
                  {m.name || t('roleNavigation.removed', undefined, 'Removed menu')}
                </li>
              ))}
          </ul>
          <p className="text-text-2 mt-2 text-xs">
            {t(
              'roleNavigation.help',
              undefined,
              'Selection controls navigation only. Business permissions, record scopes and fields remain independent.',
            )}
          </p>
        </section>
      )}
      <ul className="space-y-1">
        {changes.map((cap) => (
          <li key={cap.code}>
            {selected.includes(cap.code)
              ? t('admin.permission.editor.add', undefined, 'Grant')
              : t('admin.permission.editor.remove', undefined, 'Revoke')}
            : {getLocalizedText(cap.localizedLabels, locale, t) || cap.label}
          </li>
        ))}
      </ul>
      <p>
        {t(
          'permission.capability.details.permissionDiff',
          {
            added: previewPlan.grantedCodes.length,
            removed: previewPlan.revokedCodes.length,
            preserved: previewPlan.preservedCodes.length,
          },
          `Add ${previewPlan.grantedCodes.length} permission items, remove ${previewPlan.revokedCodes.length}; ${previewPlan.preservedCodes.length} existing permission items remain unchanged.`,
        )}
      </p>
      {resulting.length > 0 && (
        <div className="space-y-1">
          <p
            data-testid="capability-preview-state-guidance"
            className="text-text-2 text-xs leading-5"
          >
            {t(
              'permission.capability.details.partialGuidance',
              undefined,
              'Permission items may be shared by other duties. Partial configuration does not grant the complete duty. Select to complete; review revocation effects before saving.',
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
            'permission.capability.details.impactDetails',
            undefined,
            'Review related duty states, menus and permission details',
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
              'permission.capability.details.previewCounts',
              { full: fullCount, partial: partialCount, none: noneCount },
              `Related capabilities after saving: ${fullCount} fully granted · ${partialCount} partially configured · ${noneCount} not granted`,
            )}
          </p>
          <div data-testid="capability-preview-resulting" className="space-y-3">
            {primaryImpact.length > 0 && (
              <ul className="space-y-1">{primaryImpact.map(renderImpact)}</ul>
            )}
            {secondaryPartial.length > 0 && (
              <details data-testid="capability-preview-partial-impact">
                <summary className="text-text-2 cursor-pointer">
                  {t(
                    'permission.capability.details.partial',
                    undefined,
                    'Partial permission items',
                  )}
                  {' · '}
                  {secondaryPartial.length}
                </summary>
                <p className="text-text-2 mt-2 text-xs leading-5">
                  {t(
                    'permission.capability.details.partialGuidance',
                    undefined,
                    'Permission items may be shared by other duties. Partial configuration does not grant the complete duty. Select to complete; review revocation effects before saving.',
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
      {draftPreview?.defaultScopeChanged && (
        <p data-testid="draft-preview-default">
          {t('admin.permission.scope.default', undefined, 'Role default')}:{' '}
          {t(
            scopeOption(draftPreview.defaultScopeType).labelKey,
            undefined,
            scopeOption(draftPreview.defaultScopeType).labelFallback,
          )}
          {draftPreview.applyDefaultToCurrentGrants &&
            ` · ${t('admin.permission.scope.applyCurrentDraft', undefined, 'Also apply to current grants')}`}
        </p>
      )}
      {(draftPreview?.scopeChanges.length ?? 0) > 0 && (
        <div data-testid="draft-preview-scopes">
          <p>
            {t(
              'admin.permission.editor.scopeChangeCount',
              { count: draftPreview?.scopeChanges.length },
              `${draftPreview?.scopeChanges.length} record scope overrides`,
            )}
          </p>
          <ul className="mt-2 space-y-1">
            {draftPreview?.scopeChanges.map((change, index) => {
              const scope = scopeOption(change.scopeType);
              return (
                <li key={index}>
                  {scopeLabels[`${change.resourceCode}:${change.actionCode}`]}:{' '}
                  {t(scope.labelKey, undefined, scope.labelFallback)}
                </li>
              );
            })}
          </ul>
        </div>
      )}
      {((draftPreview?.fieldPolicies?.added?.length ?? 0) > 0 ||
        (draftPreview?.fieldPolicies?.removed?.length ?? 0) > 0) && (
        <div data-testid="draft-preview-fields">
          <p>{t('admin.permission.fields.preview', undefined, 'Field restriction changes')}</p>
          <ul>
            {draftPreview?.fieldPolicies?.added?.map((policy) => (
              <li key={policy.policyPid}>
                {t('admin.permission.fields.apply', undefined, 'Apply restriction')}: {policy.name}
              </li>
            ))}
            {draftPreview?.fieldPolicies?.removed?.map((policy) => (
              <li key={policy.policyPid}>
                {t('admin.permission.fields.remove', undefined, 'Remove restriction')}:{' '}
                {policy.name}
              </li>
            ))}
          </ul>
        </div>
      )}
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
      className="flex min-h-0 flex-1 flex-col overflow-hidden"
    >
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-4">
        {activeSection === 'menus' && (
          <RoleMenuChecklist
            selection={baselineSnapshot?.navigation}
            previewAllowed={readOnly ? undefined : navigationProjection}
            previewFailed={navigationProjectionError}
            selected={menuSelection ?? baselineSnapshot?.navigation?.selectedMenuPids ?? []}
            disabled={readOnly || saving || previewLoading || conflict}
            onChange={setMenuSelection}
            onPreview={(pid) => {
              setPreviewMenuPid(pid);
              setSubjectPreview(true);
            }}
          />
        )}
        {/* Record scope shares the same draft as duties, fields and navigation. */}
        <details
          hidden={activeSection !== 'scope'}
          className="border-border rounded-control border px-4 py-3"
          data-testid="role-default-scope-settings"
        >
          <summary className="text-text-2 cursor-pointer text-xs">
            {t('rbacExperience.defaultScopeAdvanced', undefined, 'Role default scope · advanced')}
          </summary>
          <DataScopeBar
            matrix={projectedMatrix}
            defaultScope={defaultScope}
            applyToCurrent={applyDefault}
            onOpenChange={setScopeOpen}
            onStage={(scope, applyCurrent) => {
              setDefaultScope(scope);
              setApplyDefault(applyCurrent);
            }}
            disabled={scopeReadOnly || saving || previewLoading || conflict}
          />
        </details>

        {conflict && (
          <div
            role="alert"
            data-testid="authorization-conflict"
            className="rounded-card border border-amber-300 p-3 text-sm"
          >
            <p>
              {t(
                'admin.permission.editor.conflictDraft',
                undefined,
                'Authorization configuration changed. Your draft is preserved and cannot overwrite the newer version.',
              )}
            </p>
            <button
              type="button"
              data-testid="authorization-reload"
              onClick={() => void load()}
              className="text-accent mt-2 underline"
            >
              {t('admin.permission.editor.reloadDraft', undefined, 'Discard draft and reload')}
            </button>
          </div>
        )}
        {/* ① business capabilities (primary) */}
        <div className="flex flex-col gap-3">
          <div hidden={activeSection !== 'capabilities'}>
            <p className="bg-subtle text-text-2 rounded-control mb-4 px-3 py-2 text-xs">
              {t(
                'roleNavigation.businessHelp',
                undefined,
                'Business duties do not select menus automatically. Internal service steps are authorized by the business use case.',
              )}
            </p>
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
            <div data-testid="capability-module-filter" className="my-3 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setActiveGroup(null)}
                className={`rounded-control border px-2 py-1 text-xs ${activeGroup === null ? 'border-blue-500 bg-blue-50 text-blue-700' : 'border-border text-text-2'}`}
              >
                {t('roleNavigation.allModules', undefined, 'All modules')}
              </button>
              {capabilityView.primaryGroups
                .filter((g) => g.group !== null)
                .map((g) => (
                  <button
                    key={g.group}
                    type="button"
                    onClick={() => setActiveGroup(g.group)}
                    className={`rounded-control border px-2 py-1 text-xs ${activeGroup === g.group ? 'border-blue-500 bg-blue-50 text-blue-700' : 'border-border text-text-2'}`}
                  >
                    {t(`permission.module.${g.group}`, undefined, g.group ?? '')}
                  </button>
                ))}
            </div>
            <div data-testid="capability-presets" className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-gray-500">
                {t('permission.capability.preset.label', undefined, 'Presets')}
              </span>
              {[
                {
                  tier: 'viewer',
                  label: t('permission.capability.preset.viewer', undefined, 'Viewer'),
                },
                {
                  tier: 'editor',
                  label: t('permission.capability.preset.editor', undefined, 'Editor'),
                },
                {
                  tier: 'admin',
                  label: t('permission.capability.preset.admin', undefined, 'Administrator'),
                },
              ].map((p) => (
                <button
                  key={p.tier}
                  type="button"
                  data-testid={`capability-preset-${p.tier}`}
                  onClick={() => applyPreset(p.tier)}
                  disabled={readOnly || saving || previewLoading}
                  className="h-7 rounded-md border border-gray-200 px-2 text-xs text-gray-700 hover:bg-gray-50"
                >
                  {p.label}
                </button>
              ))}
            </div>
            <p className="bg-subtle text-text-2 rounded-control mb-4 px-3 py-3 text-xs">
              {t(
                'rbacExperience.executionBoundary',
                undefined,
                'Internal steps run within the authorized business use case. Tenant, object relationships, state, version and field restrictions remain enforced.',
              )}
            </p>
            <CapabilityChecklist
              groups={filteredGroups}
              catalog={groups}
              matrix={matrix}
              projectedMatrix={projectedMatrix}
              selected={selected}
              onToggle={onToggle}
              revokedPartial={revokedPartial}
              onRevokePartial={(code) => {
                if (readOnly) return;
                setSelected((current) => current.filter((item) => item !== code));
                setRevokedPartial((current) => toggleCapability(current, code));
              }}
              onConfigureScope={scopeReadOnly || conflict ? undefined : setScopeCapability}
              scopeConfigurableCodes={scopeConfigurableCodes}
              disabled={readOnly || saving || previewLoading}
            />
          </div>
          <div hidden={activeSection !== 'scope'} className="space-y-4">
            {grantFieldChanges.length > 0 && (
              <p
                data-testid="grant-fields-pending"
                className="text-text-2 flex flex-wrap justify-between gap-2 text-xs"
              >
                {t(
                  'roleGrantFields.pendingChanges',
                  { count: grantFieldChanges.length },
                  `${grantFieldChanges.length} authorization field edits pending. Review and save with the same draft.`,
                )}
                <button
                  type="button"
                  disabled={readOnly || saving || previewLoading}
                  className="text-accent underline"
                  onClick={() => setGrantFieldChanges([])}
                >
                  {t('roleGrantFields.reset', undefined, 'Discard field edits')}
                </button>
              </p>
            )}

            <RoleFieldAccessGrid
              renderGrantFields={(model) => (
                <RoleGrantFieldsEditor
                  model={model}
                  candidates={grantFieldCandidates}
                  changes={grantFieldChanges}
                  disabled={readOnly || saving || previewLoading || conflict}
                  loading={grantFieldsLoading}
                  error={grantFieldsError}
                  onRetry={() => setGrantFieldsAttempt((value) => value + 1)}
                  onChange={setGrantFieldChanges}
                />
              )}
              renderRecordScope={(model, label, permissions) => (
                <RoleObjectScopeSettings
                  model={model}
                  label={label}
                  permissionCodes={permissions}
                  matrix={projectedMatrix}
                  disabled={scopeReadOnly || saving || previewLoading || conflict}
                  onStage={(changes, labels) => {
                    setScopeLabels((current) => ({ ...current, ...labels }));
                    setScopeChanges((current) => [
                      ...current.filter(
                        (old) =>
                          !changes.some(
                            (change) =>
                              change.resourceCode === old.resourceCode &&
                              change.actionCode === old.actionCode,
                          ),
                      ),
                      ...changes,
                    ]);
                  }}
                />
              )}
              rolePid={rolePid}
              catalog={baselineSnapshot?.fieldAccess}
              changes={fieldAccessChanges}
              models={fieldModels}
              grantedCodes={
                new Set(
                  (projectedMatrix?.modules ?? []).flatMap((module) =>
                    module.resources.flatMap((resource) =>
                      resource.actions
                        .filter((action) => action.granted)
                        .map((action) => action.code),
                    ),
                  ),
                )
              }
              disabled={readOnly || saving || previewLoading || conflict}
              onChange={setFieldAccessChanges}
            />
            <details data-testid="role-shared-field-policies">
              <summary className="text-text-2 cursor-pointer text-sm">
                {t('roleFields.sharedPolicies', undefined, 'Shared field policies')}
              </summary>
              <FieldPolicyChecklist
                key={rolePid}
                rolePid={rolePid}
                bound={boundFields}
                selected={selectedFields}
                models={fieldModels}
                grantedCodes={
                  new Set(
                    (projectedMatrix?.modules ?? []).flatMap((module) =>
                      module.resources.flatMap((resource) =>
                        resource.actions
                          .filter((action) => action.granted)
                          .map((action) => action.code),
                      ),
                    ),
                  )
                }
                readOnly={readOnly}
                disabled={readOnly || saving || previewLoading || conflict}
                onChange={setFieldSelection}
              />
            </details>
          </div>
          {activeSection === 'capabilities' && filteredGroups.length === 0 && (
            <p data-testid="capability-search-empty" className="text-text-2 py-4 text-sm">
              {t('admin.permission.editor.empty', undefined, 'No matching business capabilities')}
            </p>
          )}
          {(changes.length > 0 || defaultChanged || activeScopeChanges.length > 0) && (
            <div
              data-testid="capability-draft"
              className="rounded-card border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"
            >
              {t(
                'admin.permission.editor.jointDraft',
                { count: changes.length, scopes: activeScopeChanges.length },
                `${changes.length} duty changes and ${activeScopeChanges.length} record scope overrides pending. Review and save together.`,
              )}
            </div>
          )}
          {fieldsDirty && (
            <p data-testid="field-policies-pending" className="text-text-2 text-sm">
              {t(
                'admin.permission.fields.pending',
                { count: selectedFields.length },
                `${selectedFields.length} field restrictions selected; review and save with this draft.`,
              )}
            </p>
          )}
        </div>

        {activeSection === 'capabilities' && (
          <CapabilityDiagnostics matrix={matrix} groups={groups} />
        )}
      </div>
      <div className="border-border bg-panel flex shrink-0 flex-wrap items-center justify-between gap-3 border-t px-5 py-3">
        <p data-testid="role-draft-summary" className="text-text-2 text-xs">
          {t(
            'rbacExperience.draftSummary',
            {
              menus: (menuSelection ?? baselineSnapshot?.navigation?.selectedMenuPids ?? []).length,
              duties: primarySelected.length,
            },
            `${(menuSelection ?? baselineSnapshot?.navigation?.selectedMenuPids ?? []).length} menu entries · ${primarySelected.length} business duties`,
          )}
        </p>
        <div className="flex items-center gap-2">
          <button
            type="button"
            data-testid="capability-discard"
            disabled={saving || !dirty}
            onClick={() => {
              if (conflict) void load();
              else if (baselineSnapshot) acceptSnapshot(baselineSnapshot);
            }}
            className="rounded-control border-border text-text-2 h-8 border px-3 text-sm"
          >
            {t('admin.permission.editor.discard', undefined, 'Discard changes')}
          </button>

          <button
            type="button"
            data-testid="capability-save"
            disabled={readOnly || !dirty || saving || previewLoading || conflict}
            onClick={() => void reviewChanges()}
            className="h-8 rounded-md bg-blue-600 px-3 text-sm text-white disabled:opacity-50"
          >
            {saving
              ? t('common.saving', undefined, 'Saving…')
              : t('admin.permission.editor.reviewSave', undefined, 'Review and save')}
          </button>
        </div>
      </div>
      <UserNavigationPreviewDialog
        open={subjectPreview}
        initialMenuPid={previewMenuPid}
        onClose={() => setSubjectPreview(false)}
        rolePid={rolePid}
        draft={baselineSnapshot ? { rolePid, draft: draftBody } : null}
      />
      {scopeCapability && !scopeReadOnly && (
        <CapabilityScopeSettings
          capability={scopeCapability}
          matrix={projectedMatrix}
          onClose={() => setScopeCapability(null)}
          onStage={(changes, labels) => {
            setScopeLabels((current) => ({ ...current, ...labels }));
            setScopeChanges((current) => [
              ...current.filter(
                (old) =>
                  !changes.some(
                    (change) =>
                      change.resourceCode === old.resourceCode &&
                      change.actionCode === old.actionCode,
                  ),
              ),
              ...changes,
            ]);
          }}
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
