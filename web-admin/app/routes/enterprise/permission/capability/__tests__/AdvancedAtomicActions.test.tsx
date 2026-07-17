import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { PermissionMatrixDTO } from '../../types';
import type { CapabilityGroup } from '../types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (_key: string, vars?: Record<string, any>, fallback?: string) => {
      // substitute {name} into the fallback so the source pill text is assertable
      if (fallback && vars && 'name' in vars) return fallback.replace('{name}', String(vars.name));
      return fallback ?? _key;
    },
  }),
}));
vi.mock('~/shared/services/permissionService', () => ({
  permissionService: {
    getPolicy: vi.fn(),
  },
}));

vi.mock('../../PolicyConfigDialog', () => ({
  default: (props: any) =>
    props.open ? (
      <div data-testid="policy-dialog">
        {props.permissionLabel}
        <span data-testid="policy-dialog-initial-values">{JSON.stringify(props.initialValues ?? {})}</span>
      </div>
    ) : null,
}));

import AdvancedAtomicActions from '../AdvancedAtomicActions';
import { permissionService } from '~/shared/services/permissionService';

const matrix: PermissionMatrixDTO = {
  modules: [
    {
      moduleCode: 'crm',
      moduleName: 'CRM',
      resources: [
        {
          resourceCode: 'crm.account',
          resourceName: '客户',
          actions: [
            { permissionId: 1, permissionPid: 'p1', code: 'crm.account.read', action: 'read', label: '查看客户', granted: true, supported: true, scopeType: 'dept' },
            { permissionId: 2, permissionPid: 'p2', code: 'crm.account.export', action: 'export', label: '导出客户', granted: true, supported: true },
            { permissionId: 3, permissionPid: 'p3', code: 'crm.account.manage', action: 'manage', label: '维护客户', granted: false, supported: true, extension: { displayGroup: '客户管理', displayGroupOrder: 20, displayOrder: 20 } },
            { permissionId: 4, permissionPid: 'p4', code: 'crm.account.audit', action: 'audit', label: '审计客户', granted: false, supported: true, extension: { displayGroup: '审计', displayGroupOrder: 80, displayOrder: 10 } },
          ],
        },
      ],
    },
  ],
};

const capabilityGroups: CapabilityGroup[] = [
  {
    group: '客户管理',
    // crm.account.read covered by a declared capability; crm.account.export only convention-derived
    capabilities: [
      { code: 'crm.cap.account', group: '客户管理', label: '查看客户列表', sensitive: false, includes: ['crm.account.read'], granted: true, conventionDerived: false },
      { code: 'crm.account', group: 'crm', label: '客户', sensitive: false, includes: ['crm.account.read', 'crm.account.export'], granted: true, conventionDerived: true },
    ],
  },
];

const matrixWithPolicy: PermissionMatrixDTO = {
  ...matrix,
  modules: matrix.modules.map((module) => ({
    ...module,
    resources: module.resources.map((resource) => ({
      ...resource,
      actions: resource.actions.map((action) =>
        action.code === 'crm.account.read'
          ? {
              ...action,
              policySchema: JSON.stringify({
                dynamicAbac: {
                  type: 'rule-center',
                  label: 'Dynamic ABAC',
                },
              }),
            }
          : action,
      ),
    })),
  })),
};

function renderAdvanced(overrides: Partial<React.ComponentProps<typeof AdvancedAtomicActions>> = {}) {
  const onToggle = vi.fn();
  const onScopeChange = vi.fn();
  render(
    <AdvancedAtomicActions
      rolePid="r1"
      matrix={matrix}
      capabilityGroups={capabilityGroups}
      onToggle={onToggle}
      onScopeChange={onScopeChange}
      {...overrides}
    />,
  );
  return { onToggle, onScopeChange };
}

describe('AdvancedAtomicActions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(permissionService.getPolicy).mockResolvedValue({});
  });

  it('is collapsed by default and expands on click', () => {
    renderAdvanced();
    expect(screen.queryByTestId('advanced-atomic-body')).toBeNull();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    expect(screen.getByTestId('advanced-atomic-body')).toBeTruthy();
  });

  it('shows the covering capability for a covered code and "exception" for an uncovered one', () => {
    renderAdvanced();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    expect(screen.getByTestId('atomic-source-crm.account.read').textContent).toContain('查看客户列表');
    expect(screen.getByTestId('atomic-source-crm.account.export').textContent).toContain('exception');
  });

  it('toggles a grant and changes a per-code scope', () => {
    const { onToggle, onScopeChange } = renderAdvanced();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));

    fireEvent.click(screen.getByTestId('atomic-checkbox-crm.account.read'));
    expect(onToggle).toHaveBeenCalledWith(1, false);

    fireEvent.change(screen.getByTestId('atomic-scope-crm.account.read'), { target: { value: 'self' } });
    expect(onScopeChange).toHaveBeenCalledWith('crm.account', 'read', 'self');
  });

  it('filters rows by the search box', () => {
    renderAdvanced();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    fireEvent.change(screen.getByTestId('advanced-atomic-search'), { target: { value: 'export' } });
    expect(screen.queryByTestId('atomic-row-crm.account.export')).toBeTruthy();
    expect(screen.queryByTestId('atomic-row-crm.account.read')).toBeNull();
  });

  it('"uncovered only" filter keeps exception rows and drops covered ones', () => {
    renderAdvanced();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    fireEvent.click(screen.getByTestId('advanced-filter-uncovered'));
    expect(screen.queryByTestId('atomic-row-crm.account.export')).toBeTruthy();
    expect(screen.queryByTestId('atomic-row-crm.account.read')).toBeNull();
  });

  it('groups and sorts rows by permission display metadata when present', () => {
    renderAdvanced();
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));

    const bodyText = screen.getByTestId('advanced-atomic-body').textContent ?? '';
    expect(bodyText.indexOf('客户管理')).toBeLessThan(bodyText.indexOf('审计'));
    expect(bodyText.indexOf('crm.account.manage')).toBeLessThan(bodyText.indexOf('crm.account.audit'));
  });

  it('loads saved policy values before opening the policy dialog', async () => {
    vi.mocked(permissionService.getPolicy).mockResolvedValue({
      dynamicAbac: {
        ruleBinding: {
          consumerType: 'PERMISSION',
          decisionBinding: {
            decisionCode: 'permission_department_guard',
          },
        },
      },
    });

    renderAdvanced({ matrix: matrixWithPolicy });
    fireEvent.click(screen.getByTestId('advanced-atomic-toggle'));
    fireEvent.click(screen.getByTestId('atomic-policy-crm.account.read'));

    await waitFor(() => {
      expect(permissionService.getPolicy).toHaveBeenCalledWith('r1', 'p1');
    });
    expect(await screen.findByTestId('policy-dialog')).toBeTruthy();
    expect(screen.getByTestId('policy-dialog-initial-values').textContent).toContain(
      'permission_department_guard',
    );
  });
});
