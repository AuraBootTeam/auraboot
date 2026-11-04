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
  authorizationDraftService: { read: vi.fn(), preview: vi.fn(), save: vi.fn() },
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
