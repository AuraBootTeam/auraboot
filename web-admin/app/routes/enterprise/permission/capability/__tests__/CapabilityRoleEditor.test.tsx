import { StrictMode } from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { CapabilityGroup } from '../types';
import type { PermissionMatrixDTO } from '../../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, _vars?: unknown, fallback?: string) =>
      key === 'permission.qo.quote.read' ? '查看报价单' : fallback,
  }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: vi.fn(), showErrorToast: vi.fn() }),
}));
vi.mock('../capabilityService', () => ({
  capabilityService: { getForRole: vi.fn(), applySelection: vi.fn(), previewSelection: vi.fn() },
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
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(capabilityService.previewSelection).mockResolvedValue({
      grantedCodes: [],
      revokedCodes: [],
      preservedCodes: [],
      resultingCapabilities: [],
      relatedMenus: [],
    });
  });

  it('loads capability + matrix views, seeds selection from granted, disables Save until dirty', async () => {
    mockData();
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);

    await waitFor(() => screen.getByTestId('capability-role-editor'));
    expect(capabilityService.getForRole).toHaveBeenCalledWith('role-pid-5');
    expect(permissionService.getMatrixForRole).toHaveBeenCalledWith('role-pid-5');
    // ② data-scope bar and ③ advanced section both present
    expect(screen.getByTestId('data-scope-bar')).toBeTruthy();
    expect(screen.getByTestId('permission-diagnostics')).toBeTruthy();
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
    fireEvent.click(await screen.findByTestId('confirm-ok'));
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
    expect(screen.queryByTestId('advanced-capability-summary')).toBeNull();

    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByTestId('confirm-ok'));
    await waitFor(() =>
      expect(capabilityService.applySelection).toHaveBeenCalledWith('role-pid-5', [
        'qo.cap.quote_view',
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
    fireEvent.click(await screen.findByTestId('confirm-ok'));
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
  it('shows read-only diagnostics without atomic write controls', async () => {
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
                  permissionPid: 'p1',
                  code: 'qo.quote.read',
                  action: 'read',
                  label: 'Read quote',
                  granted: true,
                  supported: true,
                },
              ],
            },
          ],
        },
      ],
    };
    mockData(groups, matrix);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    expect(screen.getByTestId('permission-diagnostics')).toBeTruthy();
    expect(screen.queryByTestId('atomic-checkbox-qo.quote.read')).toBeNull();
    expect(screen.queryByTestId('atomic-scope-qo.quote.read')).toBeNull();
    const diagnostics = screen.getByTestId('permission-diagnostics') as HTMLDetailsElement;
    diagnostics.open = true;
    fireEvent(diagnostics, new Event('toggle'));
    expect(await screen.findByText('查看报价单')).toBeTruthy();
    expect(screen.queryByText('Read quote')).toBeNull();
    fireEvent.change(screen.getByRole('textbox', { name: 'Search permission diagnostics' }), {
      target: { value: '查看报价单' },
    });
    expect(screen.getByTestId('diagnostic-action-qo.quote.read')).toBeTruthy();
    vi.mocked(capabilityService.previewSelection).mockResolvedValue({
      grantedCodes: [],
      revokedCodes: ['qo.quote.read'],
      preservedCodes: [],
      resultingCapabilities: [],
      relatedMenus: [],
    });
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    expect(await screen.findByText(/Revoke: 查看报价单/)).toBeTruthy();
    expect(screen.queryByText(/Revoke: Read quote/)).toBeNull();
    expect(permissionService.batchUpdateRolePermissions).not.toHaveBeenCalled();
  });

  it('hides stale state when capability write succeeds but readback fails', async () => {
    mockData();
    vi.mocked(capabilityService.applySelection).mockResolvedValue(groups);
    vi.mocked(permissionService.getMatrixForRole)
      .mockResolvedValueOnce(emptyMatrix)
      .mockRejectedValueOnce(new Error('readback unavailable'));
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    fireEvent.click(await screen.findByTestId('confirm-ok'));
    await screen.findByTestId('capability-editor-error');
    expect(screen.queryByTestId('capability-role-editor')).toBeNull();
    vi.mocked(permissionService.getMatrixForRole).mockResolvedValue(emptyMatrix);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByTestId('capability-role-editor');
  });

  it('keeps authoritative shared impacts in a collapsed disclosure without burying the requested change', async () => {
    mockData(groups, {
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
                  permissionPid: 'p1',
                  code: 'qo.quote.read',
                  action: 'read',
                  label: 'Read quote',
                  granted: true,
                  supported: true,
                },
              ],
            },
          ],
        },
      ],
    });
    const affected = Array.from({ length: 30 }, (_, index) => ({
      ...cap(`shared.${index}`, `Shared capability ${index}`, false),
      authorizationState: 'partial' as const,
    }));
    vi.mocked(capabilityService.previewSelection).mockResolvedValue({
      grantedCodes: ['qo.quote.update'],
      revokedCodes: ['qo.quote.read'],
      preservedCodes: ['legacy.read'],
      resultingCapabilities: [
        ...affected,
        { ...cap('qo.cap.quote_edit', '编辑报价', false), authorizationState: 'partial' },
        { ...cap('qo.cap.quote_view', '查看报价', false), authorizationState: 'none' },
      ],
      relatedMenus: ['Quotes', 'Organization'],
    });
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    const impact = (await screen.findByTestId('capability-preview-impact')) as HTMLDetailsElement;
    expect(impact.open).toBe(false);
    expect(screen.getByText('Grant: 编辑报价')).toBeTruthy();
    expect(impact.querySelector('summary')).toHaveTextContent('affected capabilities (32)');
    expect(screen.getByTestId('capability-preview-resulting').querySelectorAll('li')).toHaveLength(
      32,
    );
    const secondary = screen.getByTestId('capability-preview-partial-impact') as HTMLDetailsElement;
    expect(secondary.open).toBe(false);
    expect(secondary.querySelectorAll('li')).toHaveLength(30);
    expect(secondary).not.toHaveTextContent('编辑报价');
    expect(secondary).not.toHaveTextContent('查看报价');
    expect(secondary).toHaveTextContent('Partial actions do not grant the complete capability');
    expect(impact).toHaveTextContent('Shared capability 29');
    expect(impact).toHaveTextContent('Quotes / Organization');
    expect(impact).toHaveTextContent('Revoke: 查看报价单');
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
  });

  it('does not submit when authoritative preview fails and preserves the draft', async () => {
    mockData();
    vi.mocked(capabilityService.previewSelection).mockRejectedValue(
      new Error('preview unavailable'),
    );
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    fireEvent.click(screen.getByTestId('capability-checkbox-qo.cap.quote_edit'));
    fireEvent.click(screen.getByTestId('capability-save'));
    await waitFor(() => expect(screen.getByTestId('capability-save')).not.toBeDisabled());
    expect(screen.queryByTestId('confirm-dialog')).toBeNull();
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
    expect(screen.getByTestId('capability-draft')).toBeTruthy();
  });
  it('shows a partial grant as mixed and allows explicit completion through preview', async () => {
    mockData([
      {
        group: 'Business',
        capabilities: [
          {
            ...cap('partial.cap', 'Partial capability', false),
            authorizationState: 'partial',
            includes: ['read', 'write'],
            missingCodes: ['write'],
          },
        ],
      },
    ]);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-role-editor');
    const checkbox = screen.getByTestId('capability-checkbox-partial.cap') as HTMLInputElement;
    expect(checkbox.indeterminate).toBe(true);
    expect(checkbox).toHaveAttribute('aria-checked', 'mixed');
    expect(screen.getByTestId('capability-partial-partial.cap')).toBeTruthy();
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();
    fireEvent.click(screen.getByTestId('capability-save'));
    await screen.findByTestId('confirm-dialog');
    expect(capabilityService.previewSelection).toHaveBeenCalledWith('role-pid-5', ['partial.cap']);
  });
  it('requires explicit partial revocation and supports undo and discard before preview', async () => {
    mockData([
      {
        group: '报价单',
        capabilities: [
          {
            ...cap('partial.cap', 'Partial capability', false),
            authorizationState: 'partial',
            includes: ['record.read', 'record.edit'],
            missingCodes: ['record.edit'],
          },
        ],
      },
    ]);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-revoke-partial-partial.cap');
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    fireEvent.click(screen.getByTestId('capability-revoke-partial-partial.cap'));
    expect(screen.getByTestId('capability-checkbox-partial.cap')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    fireEvent.click(screen.getByTestId('capability-revoke-partial-partial.cap'));
    expect(screen.getByTestId('capability-save')).toBeDisabled();
    fireEvent.click(screen.getByTestId('capability-revoke-partial-partial.cap'));
    fireEvent.click(screen.getByTestId('capability-discard'));
    expect(screen.getByTestId('capability-checkbox-partial.cap')).toHaveAttribute(
      'aria-checked',
      'mixed',
    );
    fireEvent.click(screen.getByTestId('capability-revoke-partial-partial.cap'));
    fireEvent.click(screen.getByTestId('capability-save'));
    await screen.findByTestId('confirm-ok');
    expect(capabilityService.previewSelection).toHaveBeenCalledWith(
      'role-pid-5',
      [],
      ['partial.cap'],
    );
    expect(capabilityService.applySelection).not.toHaveBeenCalled();
  });
  it('clears conflicting partial revocation when a preset explicitly selects that capability', async () => {
    mockData([
      {
        group: '报价单',
        capabilities: [
          {
            ...cap('partial.cap', 'Partial capability', false),
            tier: 'viewer',
            authorizationState: 'partial',
            includes: ['record.read', 'record.edit'],
            missingCodes: ['record.edit'],
          },
        ],
      },
    ]);
    render(<CapabilityRoleEditor rolePid="role-pid-5" />);
    await screen.findByTestId('capability-revoke-partial-partial.cap');
    fireEvent.click(screen.getByTestId('capability-revoke-partial-partial.cap'));
    fireEvent.click(screen.getByTestId('capability-preset-viewer'));
    expect(screen.getByTestId('capability-checkbox-partial.cap')).toBeChecked();
    fireEvent.click(screen.getByTestId('capability-save'));
    await screen.findByTestId('confirm-ok');
    expect(capabilityService.previewSelection).toHaveBeenCalledWith('role-pid-5', ['partial.cap']);
  });
});
