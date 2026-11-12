import { permissionService } from '~/shared/services/permissionService';
import { StrictMode } from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CapabilityRoleEditor from '../CapabilityRoleEditor';
import {
  authorizationDraftService,
  AuthorizationConflict,
  type AuthorizationSnapshot,
} from '../authorizationDraftService';
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (k: string, _: unknown, f: string) => f ?? k }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/shared/services/modelService', () => ({
  modelService: { findByCode: vi.fn().mockResolvedValue({ displayName: 'Order', extension: {} }) },
}));
vi.mock('../authorizationDraftService', async (original) => ({
  ...(await original<typeof import('../authorizationDraftService')>()),
  authorizationDraftService: {
    read: vi.fn(),
    preview: vi.fn(),
    save: vi.fn(),
    fieldPolicies: vi.fn(),
    fieldPolicyRoots: vi.fn(),
  },
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { getPolicySnapshot: vi.fn() },
}));
vi.mock('../../PolicyConfigDialog', () => ({
  default: (props: any) =>
    props.open ? (
      <section
        data-testid="shared-policy-dialog"
        data-role={props.rolePid}
        data-permission={props.permissionPid}
      >
        <output data-testid="shared-policy-values">{JSON.stringify(props.initialValues)}</output>
        <button onClick={props.onClose}>Cancel shared policy</button>
      </section>
    ) : null,
}));
function baseline(): AuthorizationSnapshot {
  const capability = (code: string, granted: boolean, includes: string[]) => ({
    code,
    label: code,
    group: 'Orders',
    granted,
    includes,
    authorizationState: granted ? ('full' as const) : ('none' as const),
    conventionDerived: false,
    sensitive: false,
    description: null,
    tier: null,
    displayOrder: 100,
    displayGroupOrder: 10000,
    localizedLabels: {},
    localizedDescriptions: {},
    missingCodes: [],
    unlockedMenus: null,
  });
  return {
    fieldPolicies: [],
    rolePid: 'role-1',
    revision: 'a'.repeat(64),
    defaultScopeType: 'self',
    scopes: [],
    groups: [
      {
        group: 'Orders',
        capabilities: [
          capability('order.read', true, ['model.order.read']),
          capability('order.edit', false, ['model.order.read', 'model.order.update']),
        ],
      },
    ],
    matrix: {
      modules: [
        {
          moduleCode: 'order',
          moduleName: 'Orders',
          resources: [
            {
              resourceCode: 'order',
              resourceName: 'Order',
              actions: ['read', 'update'].map((action, i) => ({
                permissionId: i + 1,
                permissionPid: 'p' + i,
                code: 'model.order.' + action,
                action,
                label: action,
                granted: action === 'read',
                supported: true,
                scopeType: action === 'read' ? 'self' : undefined,
              })),
            },
          ],
        },
      ],
    },
  };
}
async function open() {
  render(<CapabilityRoleEditor rolePid="role-1" />);
  await screen.findByTestId('capability-role-editor');
}
async function toggleEdit() {
  fireEvent.click(screen.getByTestId('capability-checkbox-order.edit'));
}
async function review() {
  fireEvent.click(screen.getByTestId('capability-save'));
  return screen.findByTestId('confirm-dialog');
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(authorizationDraftService.fieldPolicyRoots).mockResolvedValue([]);
  vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([]);
  vi.mocked(authorizationDraftService.read).mockResolvedValue(baseline());
  vi.mocked(authorizationDraftService.save).mockResolvedValue(baseline());
  vi.mocked(authorizationDraftService.preview).mockImplementation(async (_, body) => ({
    revision: body.expectedRevision,
    capabilities: {
      grantedCodes: ['model.order.update'],
      revokedCodes: [],
      preservedCodes: [],
      relatedMenus: [],
      resultingCapabilities: [],
    },
    defaultScopeChanged: body.defaultScopeChanged ?? false,
    defaultScopeType: body.defaultScopeType ?? null,
    applyDefaultToCurrentGrants: body.applyDefaultToCurrentGrants ?? false,
    scopeChanges: body.scopeChanges ?? [],
    grantFieldCandidates: [],
  }));
});
describe('CapabilityRoleEditor joint authorization draft', () => {
  it('reads one baseline and does not save until the draft changes', async () => {
    await open();
    expect(authorizationDraftService.read).toHaveBeenCalledWith('role-1');
    expect(screen.getByTestId('capability-checkbox-order.read')).toBeChecked();
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('previews and saves the same exact baseline and duty selection', async () => {
    await open();
    await toggleEdit();
    await review();
    const body = vi.mocked(authorizationDraftService.preview).mock.calls[0][1];
    expect(body).toMatchObject({
      expectedRevision: 'a'.repeat(64),
      selectedCapabilityCodes: ['order.read', 'order.edit'],
      scopeChanges: [],
      defaultScopeChanged: false,
    });
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() =>
      expect(authorizationDraftService.save).toHaveBeenCalledWith('role-1', body),
    );
    await waitFor(() => expect(screen.getByTestId('capability-save')).toBeDisabled());
  });
  it('stages default and newly granted action scopes before any server write', async () => {
    await open();
    await toggleEdit();
    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-dept').querySelector('input')!);
    fireEvent.click(screen.getByTestId('data-scope-apply-current'));
    fireEvent.click(screen.getByTestId('data-scope-apply'));
    fireEvent.click(screen.getByTestId('capability-scope-configure-order.edit'));
    const select = await screen.findByTestId('capability-scope-model.order.update');
    fireEvent.change(select, { target: { value: 'none' } });
    fireEvent.click(screen.getByTestId('capability-scope-apply'));
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
    expect(authorizationDraftService.preview).not.toHaveBeenCalled();
    await review();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({
        defaultScopeChanged: true,
        defaultScopeType: 'dept',
        applyDefaultToCurrentGrants: true,
        scopeChanges: [
          {
            resourceCode: 'order',
            actionCode: 'update',
            scopeType: 'none',
            mergeStrategy: undefined,
          },
        ],
      }),
    );
    expect(screen.getByTestId('draft-preview-default')).toBeVisible();
    expect(screen.getByTestId('draft-preview-scopes')).toBeVisible();
    expect(screen.getByTestId('draft-preview-scopes')).toHaveTextContent(
      'Order · Update: 无权访问',
    );
    expect(screen.getByTestId('draft-preview-scopes')).not.toHaveTextContent('model.order');
  });
  it('preserves a rejected preview draft and requires explicit reload on conflict', async () => {
    vi.mocked(authorizationDraftService.preview).mockRejectedValue(new AuthorizationConflict());
    await open();
    await toggleEdit();
    fireEvent.click(screen.getByTestId('capability-save'));
    await screen.findByTestId('authorization-conflict');
    expect(screen.getByTestId('capability-checkbox-order.edit')).toBeChecked();
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('authorization-reload'));
    await waitFor(() =>
      expect(screen.queryByTestId('authorization-conflict')).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId('capability-checkbox-order.edit')).not.toBeChecked();
  });
  it('preserves a draft when the save conflicts after a successful preview', async () => {
    vi.mocked(authorizationDraftService.save).mockRejectedValue(new AuthorizationConflict());
    await open();
    await toggleEdit();
    await review();
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await screen.findByTestId('authorization-conflict');
    expect(screen.getByTestId('capability-draft')).toBeVisible();
    expect(screen.getByTestId('capability-checkbox-order.edit')).toBeChecked();
  });
  it('keeps a failed save editable without claiming success', async () => {
    vi.mocked(authorizationDraftService.save).mockRejectedValue(new Error('unavailable'));
    await open();
    await toggleEdit();
    await review();
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() => expect(screen.getByTestId('capability-save')).toBeEnabled());
    expect(screen.getByTestId('capability-draft')).toBeVisible();
    expect(screen.queryByTestId('capability-editor-error')).not.toBeInTheDocument();
  });
  it('discards duty and scope changes together without writing', async () => {
    await open();
    await toggleEdit();
    fireEvent.click(screen.getByTestId('data-scope-modify-btn'));
    fireEvent.click(screen.getByTestId('data-scope-option-dept').querySelector('input')!);
    fireEvent.click(screen.getByTestId('data-scope-apply'));
    fireEvent.click(screen.getByTestId('capability-discard'));
    expect(screen.queryByTestId('capability-draft')).not.toBeInTheDocument();
    expect(screen.getByTestId('data-scope-default')).toHaveTextContent('仅本人');
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('keeps read-only permissions visible and blocks all authoring', async () => {
    render(<CapabilityRoleEditor rolePid="role-1" readOnly />);
    await screen.findByTestId('capability-role-editor');
    for (const id of ['capability-checkbox-order.edit', 'data-scope-modify-btn', 'capability-save'])
      expect(screen.getByTestId(id)).toBeDisabled();
    expect(screen.getByTestId('permission-diagnostics').querySelector('summary')).toBeVisible();
    expect(authorizationDraftService.preview).not.toHaveBeenCalled();
  });
  it('uses declared localized names in search and the review dialog', async () => {
    const value = baseline();
    value.groups[0].capabilities[1].localizedLabels = { en: 'Edit Orders' };
    vi.mocked(authorizationDraftService.read).mockResolvedValue(value);
    await open();
    fireEvent.change(screen.getByTestId('capability-search'), { target: { value: 'Edit Orders' } });
    await toggleEdit();
    const dialog = await review();
    expect(dialog).toHaveTextContent('Grant: Edit Orders');
  });
  it('does not seed a StrictMode draft from an obsolete request', async () => {
    let resolve!: (v: AuthorizationSnapshot) => void;
    vi.mocked(authorizationDraftService.read).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    render(
      <StrictMode>
        <CapabilityRoleEditor rolePid="role-1" />
      </StrictMode>,
    );
    await screen.findByTestId('capability-role-editor');
    await toggleEdit();
    resolve(baseline());
    await waitFor(() => expect(screen.getByTestId('capability-checkbox-order.edit')).toBeChecked());
  });
  it('retries an unavailable baseline without exposing a writable empty role', async () => {
    vi.mocked(authorizationDraftService.read).mockRejectedValueOnce(new Error('offline'));
    render(<CapabilityRoleEditor rolePid="role-1" />);
    await screen.findByTestId('capability-editor-error');
    expect(screen.getByTestId('capability-editor-error')).toBeVisible();
    expect(screen.queryByTestId('capability-save')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Retry'));
    await screen.findByTestId('capability-role-editor');
  });
});

describe('CapabilityRoleEditor retained permission safeguards', () => {
  function partial() {
    const value = baseline();
    const edit = value.groups[0].capabilities[1];
    edit.authorizationState = 'partial';
    edit.missingCodes = ['model.order.update'];
    return value;
  }
  it('represents partial actions as mixed and completes only by explicit selection', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue(partial());
    await open();
    const checkbox = screen.getByTestId('capability-checkbox-order.edit');
    expect(checkbox).toHaveAttribute('aria-checked', 'mixed');
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    fireEvent.click(checkbox);
    await review();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({
        selectedCapabilityCodes: ['order.read', 'order.edit'],
        revokedPartialCapabilityCodes: [],
      }),
    );
  });
  it('requires explicit partial revocation and supports undo and discard', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue(partial());
    await open();
    const revoke = screen.getByTestId('capability-revoke-partial-order.edit');
    fireEvent.click(revoke);
    fireEvent.click(revoke);
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    fireEvent.click(revoke);
    fireEvent.click(screen.getByTestId('capability-discard'));
    expect(screen.getByTestId('capability-checkbox-order.edit')).toHaveAttribute(
      'aria-checked',
      'mixed',
    );
    fireEvent.click(revoke);
    await review();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({ revokedPartialCapabilityCodes: ['order.edit'] }),
    );
  });
  it('clears partial revocation when an explicit tier preset selects the duty', async () => {
    const value = partial();
    value.groups[0].capabilities[1].tier = 'viewer';
    vi.mocked(authorizationDraftService.read).mockResolvedValue(value);
    await open();
    fireEvent.click(screen.getByTestId('capability-revoke-partial-order.edit'));
    fireEvent.click(screen.getByTestId('capability-preset-viewer'));
    await review();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({
        selectedCapabilityCodes: ['order.read', 'order.edit'],
        revokedPartialCapabilityCodes: [],
      }),
    );
  });
  it('tier presets retain untiered business grants', async () => {
    const value = baseline();
    value.groups[0].capabilities[1].tier = 'editor';
    vi.mocked(authorizationDraftService.read).mockResolvedValue(value);
    await open();
    fireEvent.click(screen.getByTestId('capability-preset-editor'));
    expect(screen.getByTestId('capability-checkbox-order.read')).toBeChecked();
    expect(screen.getByTestId('capability-checkbox-order.edit')).toBeChecked();
    await review();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({ selectedCapabilityCodes: ['order.read', 'order.edit'] }),
    );
  });
  it('never submits derived model bundles as declared duties', async () => {
    const value = baseline();
    value.groups[0].capabilities.push({
      ...value.groups[0].capabilities[0],
      code: 'derived.order',
      conventionDerived: true,
    });
    vi.mocked(authorizationDraftService.read).mockResolvedValue(value);
    await open();
    await toggleEdit();
    await review();
    expect(screen.queryByTestId('capability-checkbox-derived.order')).not.toBeInTheDocument();
    expect(authorizationDraftService.preview).toHaveBeenCalledWith(
      'role-1',
      expect.objectContaining({ selectedCapabilityCodes: ['order.read', 'order.edit'] }),
    );
  });
  it('clears the local draft if authoring access is revoked', async () => {
    const { rerender } = render(<CapabilityRoleEditor rolePid="role-1" />);
    await screen.findByTestId('capability-role-editor');
    await toggleEdit();
    rerender(<CapabilityRoleEditor rolePid="role-1" readOnly />);
    await waitFor(() =>
      expect(screen.getByTestId('capability-checkbox-order.edit')).not.toBeChecked(),
    );
    expect(screen.queryByTestId('capability-draft')).not.toBeInTheDocument();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
});

describe('field restrictions participate in the same role draft', () => {
  const definition = {
    pid: 'hide-cost',
    name: 'Hide quote cost',
    modelCode: 'order',
    fieldCode: 'cost',
    policyType: 'column',
    maskType: 'hide',
    enabled: true,
  };
  const binding = {
    policyPid: 'hide-cost',
    name: 'Hide quote cost',
    modelCode: 'order',
    fieldCode: 'cost',
    policyType: 'column',
    maskType: 'hide',
    enabled: true,
  };
  it('discovers business root restrictions without requiring generic model access', async () => {
    const state = baseline();
    state.groups[0].capabilities = [
      { ...state.groups[0].capabilities[0], includes: ['order.manage'] },
    ];
    state.matrix.modules![0].resources![0].resourceCode = 'business.order';
    state.matrix.modules![0].resources![0].actions = [
      {
        ...state.matrix.modules![0].resources![0].actions![0],
        code: 'order.manage',
        action: 'execute',
      },
    ];
    vi.mocked(authorizationDraftService.read).mockResolvedValue(state);
    vi.mocked(authorizationDraftService.fieldPolicyRoots).mockResolvedValue([
      { model: 'order', permissions: ['order.manage'] },
    ]);
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([
      definition,
      { ...definition, pid: 'internal-policy', modelCode: 'internal_dependency' },
    ]);
    await open();
    expect(await screen.findByTestId('field-policy-hide-cost')).not.toBeDisabled();
    expect(screen.queryByTestId('field-policy-internal-policy')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('field-policy-hide-cost'));
    await review();
    expect(vi.mocked(authorizationDraftService.preview).mock.calls[0][1].fieldPolicyPids).toEqual([
      'hide-cost',
    ]);
  });
  it('previews and atomically saves field selection together with duty changes', async () => {
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([definition]);
    vi.mocked(authorizationDraftService.preview).mockImplementation(async (_, body) => ({
      revision: body.expectedRevision,
      capabilities: {
        grantedCodes: [],
        revokedCodes: [],
        preservedCodes: [],
        relatedMenus: [],
        resultingCapabilities: [],
      },
      defaultScopeChanged: false,
      defaultScopeType: null,
      applyDefaultToCurrentGrants: false,
      scopeChanges: [],
      fieldPolicies: { added: [binding], removed: [] },
    }));
    vi.mocked(authorizationDraftService.save).mockResolvedValue({
      ...baseline(),
      fieldPolicies: [binding],
    });
    await open();
    fireEvent.click(await screen.findByTestId('field-policy-hide-cost'));
    await toggleEdit();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
    await review();
    expect(screen.getByTestId('draft-preview-fields')).toHaveTextContent('Hide quote cost');
    const body = vi.mocked(authorizationDraftService.preview).mock.calls[0][1];
    expect(body.fieldPolicyPids).toEqual(['hide-cost']);
    expect(body.selectedCapabilityCodes).toContain('order.edit');
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() =>
      expect(authorizationDraftService.save).toHaveBeenCalledWith('role-1', body),
    );
    await waitFor(() => expect(screen.getByTestId('field-policy-hide-cost')).toBeChecked());
  });
  it('cancels a field preview and discards the local selection without writes', async () => {
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([definition]);
    await open();
    fireEvent.click(await screen.findByTestId('field-policy-hide-cost'));
    expect(screen.queryByTestId('capability-draft')).not.toBeInTheDocument();
    expect(screen.getByTestId('field-policies-pending')).toBeVisible();
    await review();
    fireEvent.click(screen.getByTestId('confirm-cancel'));
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
    expect(screen.getByTestId('field-policy-hide-cost')).toBeChecked();
    fireEvent.click(screen.getByTestId('capability-discard'));
    await waitFor(() => expect(screen.getByTestId('field-policy-hide-cost')).not.toBeChecked());
    expect(screen.getByTestId('capability-save')).toBeDisabled();
  });
  it('fails a malformed field catalog without manufacturing an empty binding change', async () => {
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([
      { ...definition, enabled: undefined },
    ]);
    await open();
    await screen.findByTestId('field-policies-retry');
    expect(screen.queryByTestId('field-policy-hide-cost')).not.toBeInTheDocument();
    await toggleEdit();
    await review();
    expect(vi.mocked(authorizationDraftService.preview).mock.calls[0][1]).not.toHaveProperty(
      'fieldPolicyPids',
    );
  });
  it('retries policy loading without throwing away the staged duty draft', async () => {
    vi.mocked(authorizationDraftService.fieldPolicies)
      .mockRejectedValueOnce(new Error('Unavailable'))
      .mockResolvedValue([definition]);
    await open();
    await screen.findByTestId('field-policies-retry');
    await toggleEdit();
    fireEvent.click(screen.getByTestId('field-policies-retry'));
    await screen.findByTestId('field-policy-hide-cost');
    expect(screen.getByTestId('capability-checkbox-order.edit')).toBeChecked();
    expect(authorizationDraftService.read).toHaveBeenCalledTimes(1);
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('removes the last binding explicitly rather than omitting field changes', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue({
      ...baseline(),
      fieldPolicies: [binding],
    });
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([definition]);
    await open();
    fireEvent.click(await screen.findByTestId('field-policy-hide-cost'));
    await review();
    expect(vi.mocked(authorizationDraftService.preview).mock.calls[0][1].fieldPolicyPids).toEqual(
      [],
    );
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('preserves existing bindings when the policy catalog is denied', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue({
      ...baseline(),
      fieldPolicies: [binding],
    });
    vi.mocked(authorizationDraftService.fieldPolicies).mockRejectedValue(new Error('Forbidden'));
    await open();
    await screen.findByTestId('field-policies-retry');
    expect(screen.getByTestId('bound-field-policy-summary')).toHaveTextContent('Hide quote cost');
    await toggleEdit();
    await review();
    expect(vi.mocked(authorizationDraftService.preview).mock.calls[0][1]).not.toHaveProperty(
      'fieldPolicyPids',
    );
  });
  it('does not turn an unknown historical binding snapshot into an empty selection', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue({
      ...baseline(),
      fieldPolicies: undefined,
    });
    await open();
    expect(screen.getByTestId('role-field-policies')).toHaveTextContent('unavailable');
    expect(authorizationDraftService.fieldPolicies).not.toHaveBeenCalled();
    await toggleEdit();
    await review();
    expect(vi.mocked(authorizationDraftService.preview).mock.calls[0][1]).not.toHaveProperty(
      'fieldPolicyPids',
    );
  });
  it('does not fetch privileged policy definitions for a read-only editor', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue({
      ...baseline(),
      fieldPolicies: [binding],
    });
    render(<CapabilityRoleEditor rolePid="role-1" readOnly />);
    await screen.findByTestId('capability-role-editor');
    expect(await screen.findByTestId('field-policy-hide-cost')).toBeDisabled();
    expect(authorizationDraftService.fieldPolicies).not.toHaveBeenCalled();
  });
  it('allows removing an inactive restriction but never newly selecting it', async () => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue({
      ...baseline(),
      fieldPolicies: [{ ...binding, enabled: false }],
    });
    vi.mocked(authorizationDraftService.fieldPolicies).mockResolvedValue([
      { ...definition, enabled: false },
    ]);
    await open();
    const checkbox = await screen.findByTestId('field-policy-hide-cost');
    expect(checkbox).toBeEnabled();
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
    expect(checkbox).toBeDisabled();
  });
});

describe('positive grant field authoring in the original role draft', () => {
  const metadataRevision = 'b'.repeat(64);
  const fields = [
    { code: 'name', label: 'Name' },
    { code: 'cost', label: 'Cost' },
  ];
  const binding = {
    bindingPid: 'saved-binding',
    permissionCode: 'model.order.read',
    modelCode: 'order',
    modelLabel: 'Order',
    editable: true,
    legacy: false,
    readFields: ['name', 'cost'],
    writeFields: [],
    fields,
    metadataRevision,
  };
  function snapshot() {
    return {
      ...baseline(),
      grantFields: [binding],
      fieldAccess: [
        {
          code: 'order',
          label: 'Order',
          fields: fields.map((field) => ({
            ...field,
            access: 'inherit',
            additionalRestrictions: [],
          })),
        },
      ],
    };
  }
  beforeEach(() => {
    vi.mocked(authorizationDraftService.read).mockResolvedValue(snapshot());
    vi.mocked(authorizationDraftService.preview).mockImplementation(async (_, body) => {
      const candidates = [
        binding,
        ...(body.selectedCapabilityCodes.includes('order.edit')
          ? [
              {
                ...binding,
                bindingPid: null,
                permissionCode: 'model.order.update',
                legacy: true,
                readFields: [],
                writeFields: [],
              },
            ]
          : []),
      ];
      return {
        revision: body.expectedRevision,
        capabilities: {
          grantedCodes: [],
          revokedCodes: [],
          preservedCodes: [],
          relatedMenus: [],
          resultingCapabilities: [],
        },
        defaultScopeChanged: false,
        defaultScopeType: null,
        applyDefaultToCurrentGrants: false,
        scopeChanges: [],
        grantFieldCandidates: candidates,
        grantFieldChanges: (body.grantFieldChanges ?? []).map((change) => ({
          before: candidates.find((candidate) =>
            change.bindingPid
              ? candidate.bindingPid === change.bindingPid
              : candidate.permissionCode === change.permissionCode,
          ),
          readFields: change.readFields,
          writeFields: change.writeFields,
        })),
      };
    });
  });
  it('stages existing and newly selected bindings separately and saves the exact reviewed fields once', async () => {
    const view = render(<CapabilityRoleEditor rolePid="role-1" />);
    await screen.findByTestId('capability-role-editor');
    await toggleEdit();
    view.rerender(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" />);
    const read = await screen.findByTestId('grant-field-model.order.read-0-read-cost');
    await waitFor(() => expect(read).toBeEnabled());
    fireEvent.click(read);
    fireEvent.click(screen.getByText('Set a finite field list'));
    fireEvent.click(screen.getByTestId('grant-field-model.order.update-1-write-cost'));
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
    await review();
    const body = vi.mocked(authorizationDraftService.preview).mock.calls.at(-1)![1];
    expect(body.selectedCapabilityCodes).toEqual(['order.read', 'order.edit']);
    expect(body.grantFieldChanges).toEqual([
      {
        bindingPid: 'saved-binding',
        modelCode: 'order',
        expectedMetadataRevision: metadataRevision,
        readFields: ['name'],
        writeFields: [],
      },
      {
        permissionCode: 'model.order.update',
        modelCode: 'order',
        expectedMetadataRevision: metadataRevision,
        readFields: [],
        writeFields: ['name'],
      },
    ]);
    expect(screen.getByTestId('draft-preview-grant-fields')).toHaveTextContent('Order');
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() =>
      expect(authorizationDraftService.save).toHaveBeenCalledExactlyOnceWith('role-1', body),
    );
  });
  it('preserves field edits on candidate failure and exposes an explicit reset', async () => {
    const view = render(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" />);
    const checkbox = await screen.findByTestId('grant-field-model.order.read-0-read-cost');
    fireEvent.click(checkbox);
    vi.mocked(authorizationDraftService.preview).mockRejectedValue(new Error('unavailable'));
    view.rerender(<CapabilityRoleEditor rolePid="role-1" activeSection="capabilities" />);
    view.rerender(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" />);
    await screen.findByText(/Could not check field candidates/);
    expect(screen.getByTestId('grant-fields-pending')).toBeVisible();
    fireEvent.click(screen.getByText('Discard field edits'));
    expect(screen.queryByTestId('grant-fields-pending')).not.toBeInTheDocument();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('does not silently rebase a staged metadata token on a later candidate refresh', async () => {
    const view = render(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" />);
    fireEvent.click(await screen.findByTestId('grant-field-model.order.read-0-read-cost'));
    const preview = vi.mocked(authorizationDraftService.preview).getMockImplementation()!;
    vi.mocked(authorizationDraftService.preview).mockImplementation(async (...args) => {
      const result = await preview(...args);
      return {
        ...result,
        grantFieldCandidates: result.grantFieldCandidates?.map((candidate) => ({
          ...candidate,
          metadataRevision: 'c'.repeat(64),
        })),
      };
    });
    view.rerender(<CapabilityRoleEditor rolePid="role-1" activeSection="capabilities" />);
    view.rerender(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" />);
    await waitFor(() =>
      expect(screen.getByTestId('grant-field-model.order.read-0-read-name')).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId('grant-field-model.order.read-0-read-name'));
    await review();
    expect(
      vi.mocked(authorizationDraftService.preview).mock.calls.at(-1)![1].grantFieldChanges,
    ).toEqual([
      {
        bindingPid: 'saved-binding',
        modelCode: 'order',
        expectedMetadataRevision: metadataRevision,
        readFields: [],
        writeFields: [],
      },
    ]);
    fireEvent.click(screen.getByTestId('confirm-cancel'));
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
  it('uses the saved catalog without privileged preview for read-only roles', async () => {
    render(<CapabilityRoleEditor rolePid="role-1" activeSection="scope" readOnly />);
    expect(await screen.findByTestId('grant-field-model.order.read-0-read-cost')).toBeDisabled();
    expect(authorizationDraftService.preview).not.toHaveBeenCalled();
  });
});

describe('mainline business-rule policy integration', () => {
  function policyBaseline() {
    const value = baseline();
    const action = value.matrix.modules?.[0]?.resources?.[0]?.actions?.[0];
    if (!action) throw new Error('Expected the saved grant fixture');
    action.policySchema = JSON.stringify({
      dynamicAbac: { type: 'rule-center', label: 'Business conditions', fields: [] },
    });
    vi.mocked(authorizationDraftService.read).mockResolvedValue(value);
    vi.mocked(permissionService.getPolicySnapshot).mockResolvedValue({
      revision: 'grant:1',
      values: { dynamicAbac: { enabled: true } },
    });
  }

  it('opens the saved grant policy without changing the atomic draft', async () => {
    policyBaseline();
    await open();
    fireEvent.click(await screen.findByTestId('capability-policy-configure-order.read'));
    fireEvent.click(await screen.findByRole('button', { name: 'read', exact: true }));
    const dialog = await screen.findByTestId('shared-policy-dialog');
    expect(permissionService.getPolicySnapshot).toHaveBeenCalledWith('role-1', 'p0');
    expect(dialog).toHaveAttribute('data-role', 'role-1');
    expect(dialog).toHaveAttribute('data-permission', 'p0');
    expect(screen.getByTestId('shared-policy-values')).toHaveTextContent('"enabled":true');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel shared policy' }));
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });

  it('keeps the policy entry unavailable without scope edit authority', async () => {
    policyBaseline();
    render(<CapabilityRoleEditor rolePid="role-1" scopeReadOnly />);
    await screen.findByTestId('capability-role-editor');
    expect(screen.queryByTestId('capability-policy-configure-order.read')).not.toBeInTheDocument();
    expect(permissionService.getPolicySnapshot).not.toHaveBeenCalled();
  });

  it('keeps a pending joint draft from editing the saved policy independently', async () => {
    policyBaseline();
    await open();
    await toggleEdit();
    expect(screen.queryByTestId('capability-policy-configure-order.read')).not.toBeInTheDocument();
    expect(permissionService.getPolicySnapshot).not.toHaveBeenCalled();
    expect(authorizationDraftService.save).not.toHaveBeenCalled();
  });
});
