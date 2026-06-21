import { render, screen, fireEvent, waitFor } from '@testing-library/react';
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
  },
}));

import { capabilityService } from '../capabilityService';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityRoleEditor from '../CapabilityRoleEditor';

function cap(code: string, label: string, granted: boolean) {
  return { code, group: '客户管理', label, sensitive: false, includes: [], granted, conventionDerived: false };
}
const groups: CapabilityGroup[] = [
  { group: '客户管理', capabilities: [cap('crm.cap.account', '维护客户资料', true), cap('crm.cap.lead', '维护线索', false)] },
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
    render(<CapabilityRoleEditor roleId="5" rolePid="role-pid-5" />);

    await waitFor(() => screen.getByTestId('capability-role-editor'));
    expect(capabilityService.getForRole).toHaveBeenCalledWith('5');
    expect(permissionService.getMatrixForRole).toHaveBeenCalledWith('role-pid-5');
    // ② data-scope bar and ③ advanced section both present
    expect(screen.getByTestId('data-scope-bar')).toBeTruthy();
    expect(screen.getByTestId('advanced-atomic-section')).toBeTruthy();
    expect((screen.getByTestId('capability-checkbox-crm.cap.account') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-crm.cap.lead') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('enables Save after a toggle and persists the selection via applySelection', async () => {
    mockData();
    (capabilityService.applySelection as ReturnType<typeof vi.fn>).mockResolvedValue(groups);
    render(<CapabilityRoleEditor roleId="5" rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-checkbox-crm.cap.lead')); // select lead -> dirty
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(screen.getByTestId('capability-save'));
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('5', ['crm.cap.account', 'crm.cap.lead']),
    );
  });

  it('applies a tier preset, selecting tiered capabilities and enabling Save', async () => {
    const tieredGroups: CapabilityGroup[] = [
      {
        group: '客户管理',
        capabilities: [
          { code: 'crm.cap.account', group: '客户管理', label: '维护客户资料', sensitive: false, tier: 'viewer', includes: [], granted: false, conventionDerived: false },
          { code: 'crm.cap.lead', group: '客户管理', label: '维护线索', sensitive: false, tier: 'editor', includes: [], granted: false, conventionDerived: false },
        ],
      },
    ];
    mockData(tieredGroups);
    render(<CapabilityRoleEditor roleId="5" rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-preset-viewer')); // viewer preset -> only the viewer-tier capability
    expect((screen.getByTestId('capability-checkbox-crm.cap.account') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-crm.cap.lead') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);
  });
});
