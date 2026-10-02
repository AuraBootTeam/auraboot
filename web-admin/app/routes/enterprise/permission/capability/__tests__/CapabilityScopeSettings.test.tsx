import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import CapabilityScopeSettings from '../CapabilityScopeSettings';
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
function fixture(refreshed: PermissionMatrixDTO = matrix) {
  const onRefresh = vi.fn().mockResolvedValue(refreshed);
  const onClose = vi.fn();
  const onReadFailure = vi.fn();
  const onBusy = vi.fn();
  render(
    <CapabilityScopeSettings
      rolePid="role-5"
      capability={cap}
      matrix={matrix}
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
    vi.mocked(permissionService.updateScope).mockResolvedValue(undefined);
  });
  it('uses the existing exact resource/action scope API and verifies readback', async () => {
    const updated = structuredClone(matrix);
    updated.modules[0].resources[0].actions[0].scopeType = 'self';
    const callbacks = fixture(updated);
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
});
