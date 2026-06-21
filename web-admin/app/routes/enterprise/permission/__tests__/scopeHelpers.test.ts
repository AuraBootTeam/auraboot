import { describe, it, expect } from 'vitest';
import type { PermissionMatrixDTO } from '../types';
import { grantedActions, deriveRoleScope } from '../scopeHelpers';

function matrix(actions: Array<{ r: string; a: string; granted: boolean; scope?: string }>): PermissionMatrixDTO {
  return {
    modules: [
      {
        moduleCode: 'crm',
        moduleName: 'CRM',
        resources: actions.map((x) => ({
          resourceCode: x.r,
          resourceName: x.r,
          actions: [
            {
              permissionId: 1,
              permissionPid: 'p',
              code: `${x.r}.${x.a}`,
              action: x.a,
              label: x.a,
              granted: x.granted,
              supported: true,
              scopeType: x.scope,
            },
          ],
        })),
      },
    ],
  };
}

describe('scopeHelpers', () => {
  it('grantedActions lists only granted leaves with normalized scope', () => {
    const m = matrix([
      { r: 'crm.account', a: 'read', granted: true, scope: 'dept' },
      { r: 'crm.lead', a: 'read', granted: false, scope: 'self' },
      { r: 'crm.deal', a: 'read', granted: true }, // no scope -> all
    ]);
    expect(grantedActions(m)).toEqual([
      { resourceCode: 'crm.account', actionCode: 'read', scopeType: 'dept' },
      { resourceCode: 'crm.deal', actionCode: 'read', scopeType: 'all' },
    ]);
  });

  it('deriveRoleScope is all when nothing granted', () => {
    expect(deriveRoleScope(matrix([{ r: 'crm.account', a: 'read', granted: false }]))).toBe('all');
    expect(deriveRoleScope(null)).toBe('all');
  });

  it('deriveRoleScope returns the shared scope when uniform', () => {
    const m = matrix([
      { r: 'crm.account', a: 'read', granted: true, scope: 'dept_and_sub' },
      { r: 'crm.lead', a: 'read', granted: true, scope: 'dept_and_sub' },
    ]);
    expect(deriveRoleScope(m)).toBe('dept_and_sub');
  });

  it('deriveRoleScope returns mixed when scopes differ', () => {
    const m = matrix([
      { r: 'crm.account', a: 'read', granted: true, scope: 'dept' },
      { r: 'crm.lead', a: 'read', granted: true, scope: 'self' },
    ]);
    expect(deriveRoleScope(m)).toBe('mixed');
  });
});
