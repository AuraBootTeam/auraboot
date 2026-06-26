import { describe, expect, it } from 'vitest';
import { isRecommendedBomRole, sortRolesForPermissionSetup } from '../roleDisplayHelpers';
import type { Role } from '../types';

function role(code: string, name = code): Role {
  return {
    id: 1,
    pid: code,
    code,
    name,
    description: '',
    type: 'CUSTOM',
    status: 'active',
    isSystem: false,
    tenantId: 1,
    createdAt: '',
    updatedAt: '',
  };
}

describe('roleDisplayHelpers', () => {
  it('recognizes compact BOM/quote setup roles', () => {
    expect(isRecommendedBomRole('tenant_admin')).toBe(true);
    expect(isRecommendedBomRole('bom_operator')).toBe(true);
    expect(isRecommendedBomRole('qo_quoter')).toBe(true);
    expect(isRecommendedBomRole('qo_sales')).toBe(false);
    expect(isRecommendedBomRole('qo_procurement')).toBe(false);
    expect(isRecommendedBomRole('bom_engineering')).toBe(false);
    expect(isRecommendedBomRole('crm_admin')).toBe(false);
  });

  it('sorts recommended setup roles before template and legacy roles', () => {
    const sorted = sortRolesForPermissionSetup([
      role('crm_admin', 'CRM 管理员'),
      role('qo_quoter', '报价员'),
      role('tenant_admin', '租户管理员'),
      role('bom_operator', 'BOM 操作员'),
      role('crm_sales', '销售代表'),
    ]);

    expect(sorted.map((item) => item.code)).toEqual([
      'tenant_admin',
      'bom_operator',
      'qo_quoter',
      'crm_admin',
      'crm_sales',
    ]);
  });
});
