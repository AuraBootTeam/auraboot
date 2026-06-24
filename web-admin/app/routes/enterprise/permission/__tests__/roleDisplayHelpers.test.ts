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
    expect(isRecommendedBomRole('qo_sales')).toBe(true);
    expect(isRecommendedBomRole('qo_procurement')).toBe(true);
    expect(isRecommendedBomRole('bom_engineering')).toBe(true);
    expect(isRecommendedBomRole('bom_operator')).toBe(false);
    expect(isRecommendedBomRole('qo_quoter')).toBe(false);
    expect(isRecommendedBomRole('crm_admin')).toBe(false);
  });

  it('sorts recommended setup roles before template and legacy roles', () => {
    const sorted = sortRolesForPermissionSetup([
      role('crm_admin', 'CRM 管理员'),
      role('qo_procurement', '采购'),
      role('tenant_admin', '租户管理员'),
      role('bom_engineering', '工程'),
      role('qo_sales', '销售'),
      role('crm_sales', '销售代表'),
    ]);

    expect(sorted.map((item) => item.code)).toEqual([
      'tenant_admin',
      'qo_sales',
      'qo_procurement',
      'bom_engineering',
      'crm_admin',
      'crm_sales',
    ]);
  });
});
