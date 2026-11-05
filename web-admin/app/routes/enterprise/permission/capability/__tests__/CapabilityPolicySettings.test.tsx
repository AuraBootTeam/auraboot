import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityPolicySettings from '../CapabilityPolicySettings';
import type { Capability } from '../types';
import type { PermissionMatrixDTO } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (_k: string, _v: unknown, fallback: string) => fallback }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast: vi.fn() }),
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: { getPolicy: vi.fn() },
}));
vi.mock('../../PolicyConfigDialog', () => ({
  default: (p: any) => (
    <div data-testid="policy-target" data-role={p.rolePid}>
      <output>{JSON.stringify(p.initialValues)}</output>
      <button onClick={() => void p.onSuccess()}>Saved rule</button>
    </div>
  ),
}));
const capability = {
  code: 'test.cap',
  label: 'Manage records',
  localizedLabels: {},
  includes: ['test.create', 'test.read', 'test.other'],
} as Capability;
const action = {
  permissionId: 1,
  permissionPid: 'permission-create',
  code: 'test.create',
  action: 'create',
  label: 'Create record',
  supported: true,
  granted: true,
  policySchema: JSON.stringify({ dynamicAbac: { type: 'rule-center' } }),
};
const matrix: PermissionMatrixDTO = {
  modules: [
    {
      moduleCode: 'test',
      moduleName: 'Test',
      resources: [
        {
          resourceCode: 'test',
          resourceName: 'Record',
          actions: [
            action,
            {
              ...action,
              permissionPid: 'denied',
              code: 'test.read',
              label: 'Denied action',
              granted: false,
            },
            {
              ...action,
              permissionPid: 'unsupported',
              code: 'test.other',
              label: 'Unsupported action',
              supported: false,
            },
          ],
        },
      ],
    },
  ],
};
function props(rolePid = 'role-a') {
  return {
    rolePid,
    capability,
    matrix,
    onClose: vi.fn(),
    onRefresh: vi.fn().mockResolvedValue(matrix),
    onReadFailure: vi.fn(),
  };
}
describe('CapabilityPolicySettings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(permissionService.getPolicy).mockResolvedValue({ dynamicAbac: { enabled: true } });
  });
  it('offers only granted supported actions and reads the exact role and permission', async () => {
    const p = props();
    render(<CapabilityPolicySettings {...p} />);
    expect(screen.queryByRole('button', { name: 'Denied action' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Unsupported action' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(await screen.findByTestId('policy-target')).toHaveAttribute('data-role', 'role-a');
    expect(permissionService.getPolicy).toHaveBeenCalledWith('role-a', 'permission-create');
  });
  it('keeps a failed read distinct from an empty policy and retries explicitly', async () => {
    vi.mocked(permissionService.getPolicy).mockRejectedValueOnce(new Error('offline'));
    render(<CapabilityPolicySettings {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
    expect(screen.queryByTestId('policy-target')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(await screen.findByTestId('policy-target')).toHaveTextContent('"enabled":true');
  });
  it('blocks duplicate selections while a current read is pending', async () => {
    let resolve!: (value: Record<string, unknown>) => void;
    vi.mocked(permissionService.getPolicy).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    render(<CapabilityPolicySettings {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(screen.getByRole('button', { name: 'Create record' })).toBeDisabled();
    await act(async () => resolve({}));
    expect(await screen.findByTestId('policy-target')).toBeInTheDocument();
  });
  it('ignores a late policy response from the previous role', async () => {
    let resolve!: (value: Record<string, unknown>) => void;
    vi.mocked(permissionService.getPolicy).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const view = render(<CapabilityPolicySettings {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    view.rerender(<CapabilityPolicySettings {...props('role-b')} />);
    await act(async () => resolve({ previous: true }));
    expect(screen.queryByTestId('policy-target')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(await screen.findByTestId('policy-target')).toHaveAttribute('data-role', 'role-b');
    expect(screen.getByTestId('policy-target')).not.toHaveTextContent('previous');
  });
  it('rejects malformed schema before opening an editable policy', async () => {
    const invalid = structuredClone(matrix);
    invalid.modules[0].resources[0].actions[0].policySchema = '{"invalid":null}';
    render(<CapabilityPolicySettings {...props()} matrix={invalid} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    expect(await screen.findByRole('alert')).toBeVisible();
    expect(permissionService.getPolicy).not.toHaveBeenCalled();
  });
  it('blocks continued editing if the saved policy matrix cannot be read back', async () => {
    const p = props();
    p.onRefresh.mockRejectedValue(new Error('offline'));
    render(<CapabilityPolicySettings {...p} />);
    fireEvent.click(screen.getByRole('button', { name: 'Create record' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Saved rule' }));
    await waitFor(() => expect(p.onReadFailure).toHaveBeenCalledOnce());
    expect(p.onClose).toHaveBeenCalledOnce();
  });
});
