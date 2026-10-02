import { StrictMode } from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { CapabilityGroup } from '../types';
import type { PermissionMatrixDTO } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('../capabilityService', () => ({
  capabilityService: { getForRole: vi.fn(), applySelection: vi.fn() },
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: {
    getMatrixForRole: vi.fn(),
    batchUpdateRolePermissions: vi.fn(),
    updateScope: vi.fn(),
    getRoleDefaultScope: vi.fn().mockResolvedValue(null),
    setRoleDefaultScope: vi.fn().mockResolvedValue(undefined),
  },
}));

import { capabilityService } from '../capabilityService';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityRoleEditor from '../CapabilityRoleEditor';

function cap(code: string, label: string, granted: boolean) {
  return {
    code,
    group: '报价单',
    label,
    sensitive: false,
    includes: [],
    granted,
    conventionDerived: false,
  };
}
const groups: CapabilityGroup[] = [
  {
    group: '报价单',
    capabilities: [
      cap('qo.cap.quote_view', '查看报价', true),
      cap('qo.cap.quote_edit', '编辑报价', false),
    ],
  },
];

const emptyMatrix: PermissionMatrixDTO = { modules: [] };

function mockData(g: CapabilityGroup[] = groups, m: PermissionMatrixDTO = emptyMatrix) {
  (capabilityService.getForRole as ReturnType<typeof vi.fn>).mockResolvedValue(g);
  (permissionService.getMatrixForRole as ReturnType<typeof vi.fn>).mockResolvedValue(m);
}

describe('CapabilityRoleEditor', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads capability + matrix views, seeds selection from granted, disables Save until dirty', async () => {
    mockData();
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);

    await waitFor(() => screen.getByTestId('capability-role-editor'));
    expect(capabilityService.getForRole).toHaveBeenCalledWith('role-pid-5');
    expect(permissionService.getMatrixForRole).toHaveBeenCalledWith('role-pid-5');
    // ② data-scope bar and ③ advanced section both present
    expect(screen.getByTestId('data-scope-bar')).toBeTruthy();
    expect(screen.getByTestId('advanced-atomic-section')).toBeTruthy();
    expect(
      (screen.getByTestId('capability-checkbox-qo.cap.quote_view') as HTMLInputElement).checked,
    ).toBe(true);
    expect(
      (screen.getByTestId('capability-checkbox-qo.cap.quote_edit') as HTMLInputElement).checked,
    ).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables Save after a toggle and persists the selection via applySelection', async () => {
    mockData();
    (capabilityService.applySelection as ReturnType<typeof vi.fn>).mockResolvedValue(groups);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit')); // select quote edit -> dirty
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(screen.getByTestId('capability-save'));
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('role-pid-5', [
        'qo.cap.quote_view',
        'qo.cap.quote_edit',
      ]),
    );
  });

  it('applies a tier preset, selecting tiered capabilities and enabling Save', async () => {
    const tieredGroups: CapabilityGroup[] = [
      {
        group: '报价单',
        capabilities: [
          {
            code: 'qo.cap.quote_view',
            group: '报价单',
            label: '查看报价',
            sensitive: false,
            tier: 'viewer',
            includes: [],
            granted: false,
            conventionDerived: false,
          },
          {
            code: 'qo.cap.quote_edit',
            group: '报价单',
            label: '编辑报价',
            sensitive: false,
            tier: 'editor',
            includes: [],
            granted: false,
            conventionDerived: false,
          },
        ],
      },
    ];
    mockData(tieredGroups);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-preset-viewer')); // viewer preset -> only the viewer-tier capability
    expect(
      (screen.getByTestId('capability-checkbox-qo.cap.quote_view') as HTMLInputElement).checked,
    ).toBe(true);
    expect(
      (screen.getByTestId('capability-checkbox-qo.cap.quote_edit') as HTMLInputElement).checked,
    ).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps generated model capabilities out of the primary checklist but preserves them on save', async () => {
    const mixedGroups: CapabilityGroup[] = [
      {
        group: '报价单',
        capabilities: [
          {
            code: 'qo.cap.quote_view',
            group: '报价单',
            label: '查看报价',
            sensitive: false,
            tier: 'viewer',
            includes: [],
            granted: true,
            conventionDerived: false,
          },
          {
            code: 'qo.cap.quote_edit',
            group: '报价单',
            label: '编辑报价',
            sensitive: false,
            tier: 'editor',
            includes: [],
            granted: false,
            conventionDerived: false,
          },
        ],
      },
      {
        group: 'model',
        capabilities: [
          {
            code: 'model.qo_quote_common',
            group: 'model',
            label: 'Qo_quote_common Read',
            sensitive: false,
            tier: null,
            includes: [],
            granted: true,
            conventionDerived: true,
          },
        ],
      },
    ];
    mockData(mixedGroups);
    (capabilityService.applySelection as ReturnType<typeof vi.fn>).mockResolvedValue(mixedGroups);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    expect(screen.getByTestId('capability-checkbox-qo.cap.quote_view')).toBeTruthy();
    expect(screen.queryByTestId('capability-checkbox-model.qo_quote_common')).toBeNull();
    expect(screen.getByTestId('advanced-capability-summary')).toHaveTextContent('1/1');

    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('role-pid-5', [
        'qo.cap.quote_view',
        'model.qo_quote_common',
        'qo.cap.quote_edit',
      ]),
    );
  });
  it('preserves untiered business grants when applying a tier preset', async () => {
    mockData([
      {
        group: '新业务',
        capabilities: [
          { ...cap('custom.cap.new', '新业务', true), tier: null },
          { ...cap('custom.cap.read', '读取', false), tier: 'viewer' },
        ],
      },
    ]);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('capability-preset-viewer'));
    expect(screen.getByTestId('capability-checkbox-custom.cap.new')).toBeChecked();
    expect(screen.getByTestId('capability-checkbox-custom.cap.read')).toBeChecked();
  });
  it('retains the draft when saving fails', async () => {
    mockData();
    vi.mocked(capabilityService.applySelection).mockRejectedValue(new Error('offline'));
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    fireEvent.click(screen.getByTestId('confirm-ok'));
    await waitFor(() => expect(screen.getByTestId('capability-save')).not.toBeDisabled());
    expect(screen.getByTestId('capability-checkbox-qo.cap.quote_edit')).toBeChecked();
    expect(screen.getByTestId('capability-draft')).toBeTruthy();
  });
  it('ignores a stale StrictMode initialization response after the user starts a draft', async () => {
    let finishOldRead!: (value: CapabilityGroup[]) => void;
    const oldRead = new Promise<CapabilityGroup[]>((resolve) => {
      finishOldRead = resolve;
    });
    mockData();
    vi.mocked(capabilityService.getForRole).mockImplementationOnce(() => oldRead);
    render(
      <StrictMode>
        <CapabilityRoleEditor rolePid="role-pid-5" />
      </StrictMode>,
    );
    await screen.findByTestId('capability-role-editor');
    expect(capabilityService.getForRole).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    expect(screen.getByTestId('capability-checkbox-qo.cap.quote_edit')).toBeChecked();
    await act(async () => {
      finishOldRead(groups);
    });
    expect(screen.getByTestId('capability-checkbox-qo.cap.quote_edit')).toBeChecked();
    expect(screen.getByTestId('capability-draft')).toBeTruthy();
  });
  it('hides a stale snapshot after an atomic write succeeds but matrix readback fails', async () => {
    const matrix: PermissionMatrixDTO = {
      modules: [
        {
          moduleCode: 'quote',
          moduleName: 'Quote',
          resources: [
            {
              resourceCode: 'qo.quote',
              resourceName: 'Quote',
              actions: [
                {
                  permissionId: 1,
                  permissionPid: 'permission-1',
                  code: 'qo.quote.read',
                  action: 'read',
                  label: 'Read quote',
                  granted: false,
                  supported: true,
                },
              ],
            },
          ],
        },
      ],
    };
    mockData(groups, matrix);
    vi.mocked(permissionService.batchUpdateRolePermissions).mockResolvedValue(undefined);
    vi.mocked(permissionService.getMatrixForRole)
      .mockResolvedValueOnce(matrix)
      .mockRejectedValueOnce(new Error('readback unavailable'));
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    fireEvent.click(screen.getByTestId('atomic-checkbox-qo.quote.read'));
    await screen.findByTestId('capability-editor-error');
    expect(permissionService.batchUpdateRolePermissions).toHaveBeenCalledWith('role-pid-5', [
      { permissionId: 1, granted: true },
    ]);
    expect(screen.queryByTestId('capability-role-editor')).toBeNull();
    vi.mocked(permissionService.getMatrixForRole).mockResolvedValue({
      ...matrix,
      modules: [
        {
          ...matrix.modules[0],
          resources: [
            {
              ...matrix.modules[0].resources[0],
              actions: [{ ...matrix.modules[0].resources[0].actions[0], granted: true }],
            },
          ],
        },
      ],
    });
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    expect(screen.getByTestId('atomic-checkbox-qo.quote.read')).toBeChecked();
  });
});
