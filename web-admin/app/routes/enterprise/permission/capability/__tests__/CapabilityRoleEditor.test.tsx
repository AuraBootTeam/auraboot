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
    getRoleDefaultScope: vi.fn().mockResolvedValue(null),
    setRoleDefaultScope: vi.fn().mockResolvedValue(undefined),
  },
}));

import { capabilityService } from '../capabilityService';
import { permissionService } from '~/shared/services/permissionService';
import CapabilityRoleEditor from '../CapabilityRoleEditor';

function cap(code: string, label: string, granted: boolean) {
  return { code, group: '报价单', label, sensitive: false, includes: [], granted, conventionDerived: false };
}
const groups: CapabilityGroup[] = [
  { group: '报价单', capabilities: [cap('qo.cap.quote_view', '查看报价', true), cap('qo.cap.quote_edit', '编辑报价', false)] },
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
    expect((screen.getByTestId('capability-checkbox-qo.cap.quote_view') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-qo.cap.quote_edit') as HTMLInputElement).checked).toBe(false);
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
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('role-pid-5', ['qo.cap.quote_view', 'qo.cap.quote_edit']),
    );
  });

  it('applies a tier preset, selecting tiered capabilities and enabling Save', async () => {
    const tieredGroups: CapabilityGroup[] = [
      {
        group: '报价单',
        capabilities: [
          { code: 'qo.cap.quote_view', group: '报价单', label: '查看报价', sensitive: false, tier: 'viewer', includes: [], granted: false, conventionDerived: false },
          { code: 'qo.cap.quote_edit', group: '报价单', label: '编辑报价', sensitive: false, tier: 'editor', includes: [], granted: false, conventionDerived: false },
        ],
      },
    ];
    mockData(tieredGroups);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await waitFor(() => screen.getByTestId('capability-role-editor'));

    fireEvent.click(screen.getByTestId('capability-preset-viewer')); // viewer preset -> only the viewer-tier capability
    expect((screen.getByTestId('capability-checkbox-qo.cap.quote_view') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('capability-checkbox-qo.cap.quote_edit') as HTMLInputElement).checked).toBe(false);
    expect((screen.getByTestId('capability-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps generated model capabilities out of the primary checklist but preserves them on save', async () => {
    const mixedGroups: CapabilityGroup[] = [
      {
        group: '报价单',
        capabilities: [
          { code: 'qo.cap.quote_view', group: '报价单', label: '查看报价', sensitive: false, tier: 'viewer', includes: [], granted: true, conventionDerived: false },
          { code: 'qo.cap.quote_edit', group: '报价单', label: '编辑报价', sensitive: false, tier: 'editor', includes: [], granted: false, conventionDerived: false },
        ],
      },
      {
        group: 'model',
        capabilities: [
          { code: 'model.qo_quote_common', group: 'model', label: 'Qo_quote_common Read', sensitive: false, tier: null, includes: [], granted: true, conventionDerived: true },
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
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('role-pid-5', [
        'qo.cap.quote_view',
        'model.qo_quote_common',
        'qo.cap.quote_edit',
      ]),
    );
  });
});
