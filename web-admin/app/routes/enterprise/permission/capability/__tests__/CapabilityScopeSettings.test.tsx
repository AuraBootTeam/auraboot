import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CapabilityScopeSettings from '../CapabilityScopeSettings';
import { modelService } from '~/shared/services/modelService';
import { permissionService } from '~/shared/services/permissionService';
import type { PermissionMatrixDTO } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { updateScope: vi.fn() },
}));
vi.mock('~/shared/services/modelService', () => ({ modelService: { findByCode: vi.fn() } }));
const matrix: PermissionMatrixDTO = {
  modules: [
    {
      moduleCode: 'quote',
      moduleName: 'Quote',
      resources: [
        {
          resourceCode: 'qo_quote_common',
          resourceName: 'Quote',
          actions: [
            {
              permissionId: 1,
              permissionPid: 'p1',
              code: 'model.qo_quote_common.read',
              action: 'read',
              label: 'Read quote',
              granted: true,
              supported: true,
              scopeType: 'team',
            },
          ],
        },
      ],
    },
  ],
};
const cap = {
  code: 'qo.cap.quote_view',
  group: 'Quote',
  label: 'View quotes',
  sensitive: false,
  includes: ['model.qo_quote_common.read'],
  granted: true,
  conventionDerived: false,
};
function fixture(refreshed: PermissionMatrixDTO = matrix, initial: PermissionMatrixDTO = matrix, capability = cap) {
  const onRefresh = vi.fn().mockResolvedValue(refreshed);
  const onClose = vi.fn();
  const onReadFailure = vi.fn();
  const onBusy = vi.fn();
  render(
    <CapabilityScopeSettings
      rolePid="role-5"
      capability={capability}
      matrix={initial}
      onRefresh={onRefresh}
      onClose={onClose}
      onReadFailure={onReadFailure}
      onBusy={onBusy}
    />,
  );
  return { onRefresh, onClose, onReadFailure, onBusy };
}
describe('CapabilityScopeSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(modelService.findByCode).mockResolvedValue({ displayName: '报价单' } as Awaited<ReturnType<typeof modelService.findByCode>>);
    vi.mocked(permissionService.updateScope).mockResolvedValue(undefined);
  });
  it('uses the existing exact resource/action scope API and verifies readback', async () => {
    const updated = structuredClone(matrix);
    updated.modules[0].resources[0].actions[0].scopeType = 'self';
    const callbacks = fixture(updated);
    await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    fireEvent.change(screen.getByTestId('capability-scope-model.qo_quote_common.read'), {
      target: { value: 'self' },
    });
    fireEvent.click(screen.getByTestId('capability-scope-apply'));
    await waitFor(() => expect(callbacks.onClose).toHaveBeenCalledOnce());
    expect(permissionService.updateScope).toHaveBeenCalledWith('role-5', {
      resourceCode: 'qo_quote_common',
      actionCode: 'read',
      scopeType: 'self',
    });
    expect(callbacks.onReadFailure).not.toHaveBeenCalled();
  });
  it('blocks continued editing when successful submission reads back the wrong scope', async () => {
    const callbacks = fixture();
    await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    fireEvent.change(screen.getByTestId('capability-scope-model.qo_quote_common.read'), {
      target: { value: 'self' },
    });
    fireEvent.click(screen.getByTestId('capability-scope-apply'));
    await waitFor(() => expect(callbacks.onReadFailure).toHaveBeenCalledOnce());
    expect(callbacks.onClose).not.toHaveBeenCalled();
  });
  it('cancels through Escape without writing', () => {
    const callbacks = fixture();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(callbacks.onClose).toHaveBeenCalledOnce();
    expect(permissionService.updateScope).not.toHaveBeenCalled();
  });
  it('renders absent scope as unconfigured and uses the business model name', async () => {
    const missing = structuredClone(matrix);
    missing.modules[0].resources[0].actions[0].scopeType = null;
    fixture(missing, missing);
    const select = await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    expect(select).toHaveAccessibleName('报价单 · View');
    expect(screen.getByRole('option', { name: 'Not configured' })).toBeVisible();
    expect(screen.queryByText('Invalid scope configuration')).not.toBeInTheDocument();
  });
  it('blocks scope writes if the business model name cannot be loaded', async () => {
    vi.mocked(modelService.findByCode).mockRejectedValue(new Error('unavailable'));
    fixture();
    await screen.findByRole('alert');
    expect(screen.queryByTestId('capability-scope-model.qo_quote_common.read')).not.toBeInTheDocument();
    expect(screen.getByTestId('capability-scope-apply')).toBeDisabled();
    expect(permissionService.updateScope).not.toHaveBeenCalled();
  });

  it('preserves an existing unsupported team scope and explains the denial without writing', async () => {
    fixture();
    const select = await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    expect(select).toHaveValue('team');
    expect(screen.getByRole('option', { name: 'My teams' })).toBeDisabled();
    expect(select).toHaveAccessibleDescription(/no team association configured/);
    expect(screen.getByTestId('capability-scope-apply')).toBeDisabled();
    expect(permissionService.updateScope).not.toHaveBeenCalled();
  });

  it('allows team scope when the model declares a team association', async () => {
    const model = await modelService.findByCode('qo_quote_common');
    vi.mocked(modelService.findByCode).mockResolvedValue({ ...model, extension: { dataScope: { teamField: 'quote_team' } } });
    const initial = structuredClone(matrix);
    initial.modules[0].resources[0].actions[0].scopeType = 'self';
    const callbacks = fixture(matrix, initial);
    const select = await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    expect(screen.getByRole('option', { name: 'My teams' })).not.toBeDisabled();
    fireEvent.change(select, { target: { value: 'team' } });
    fireEvent.click(screen.getByTestId('capability-scope-apply'));
    await waitFor(() => expect(callbacks.onClose).toHaveBeenCalledOnce());
    expect(permissionService.updateScope).toHaveBeenCalledWith('role-5', { resourceCode: 'qo_quote_common', actionCode: 'read', scopeType: 'team' });
  });

  it('rejects a forced unsupported team selection before calling the scope API', async () => {
    const initial = structuredClone(matrix);
    initial.modules[0].resources[0].actions[0].scopeType = 'self';
    fixture(initial, initial);
    const select = await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    fireEvent.change(select, { target: { value: 'team' } });
    fireEvent.click(screen.getByTestId('capability-scope-apply'));
    expect(permissionService.updateScope).not.toHaveBeenCalled();
  });

  it('does not offer record scopes for a generic command entry with a stored role default', async () => {
    const mixed = structuredClone(matrix);
    mixed.modules[0].resources.push({
      resourceCode: 'meta.command', resourceName: 'Command', actions: [{
        permissionId: 2, permissionPid: 'p2', code: 'meta.command.execute', action: 'execute',
        label: 'Meta command execute', granted: true, supported: true, scopeType: 'all',
      }],
    });
    fixture(mixed, mixed, { ...cap, includes: [...cap.includes, 'meta.command.execute'] });
    await screen.findByTestId('capability-scope-model.qo_quote_common.read');
    expect(screen.queryByTestId('capability-scope-meta.command.execute')).not.toBeInTheDocument();
    expect(screen.getAllByRole('combobox')).toHaveLength(1);
    expect(permissionService.updateScope).not.toHaveBeenCalled();
  });

});
