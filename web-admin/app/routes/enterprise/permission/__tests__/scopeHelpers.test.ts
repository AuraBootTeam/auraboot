import { describe, it, expect } from 'vitest';
import type { PermissionMatrixDTO } from '../types';
import { grantedActions, deriveRoleScope } from '../scopeHelpers';

function matrix(
  actions: Array<{ r: string; a: string; granted: boolean; scope?: string; code?: string }>,
): PermissionMatrixDTO {
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
              code: x.code ?? `${x.r}.${x.a}`,
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
      { r: 'crm.deal', a: 'read', granted: true, code: 'model.crm_deal.read' }, // missing model scope remains explicit
    ]);
    expect(grantedActions(m)).toEqual([
      { resourceCode: 'crm.account', actionCode: 'read', scopeType: 'dept' },
      { resourceCode: 'crm.deal', actionCode: 'read', scopeType: 'not_configured' },
    ]);
  });

  it('distinguishes empty grants from an unavailable matrix', () => {
    expect(deriveRoleScope(matrix([{ r: 'crm.account', a: 'read', granted: false }]))).toBe(
      'no_grants',
    );
    expect(deriveRoleScope(null)).toBe('unavailable');
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

  it('does not count non-record menu and tab grants as missing record scopes', () => {
    const m = matrix([
      { r: 'qo_quote_common', a: 'read', code: 'model.qo_quote_common.read', granted: true, scope: 'team' },
      { r: 'quote_management', a: 'access', code: 'quote_management', granted: true },
      { r: 'quote_material', a: 'read', code: 'qo.quote.material.read', granted: true },
    ]);
    expect(grantedActions(m)).toEqual([
      { resourceCode: 'qo_quote_common', actionCode: 'read', scopeType: 'team' },
    ]);
    expect(deriveRoleScope(m)).toBe('team');
  });

  it('still reports a missing model scope and an invalid configured scope', () => {
    expect(deriveRoleScope(matrix([
      { r: 'crm_deal', a: 'read', code: 'model.crm_deal.read', granted: true },
    ]))).toBe('not_configured');
    expect(deriveRoleScope(matrix([
      { r: 'crm_deal', a: 'read', code: 'model.crm_deal.read', granted: true, scope: 'unknown' },
    ]))).toBe('invalid');
  });
});
