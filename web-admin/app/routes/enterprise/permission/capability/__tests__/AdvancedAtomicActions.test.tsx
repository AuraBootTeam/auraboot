import { render, screen, fireEvent } from '@testing-library/react';
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
vi.mock('../../PolicyConfigDialog', () => ({ default: () => null }));

import AdvancedAtomicActions from '../AdvancedAtomicActions';

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
  beforeEach(() => vi.clearAllMocks());

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
});
