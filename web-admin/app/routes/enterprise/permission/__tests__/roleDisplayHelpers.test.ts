import { describe, expect, it } from 'vitest';
import { isRecommendedBomRole, recommendedBomRoleLabel, sortRolesForPermissionSetup } from '../roleDisplayHelpers';
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
  it('recognizes the RBAC v2 roles and keeps recognizing the legacy ones', () => {
    // RBAC v2 (business-roles.json): 销售/采购/工程 separated.
    expect(isRecommendedBomRole('tenant_admin')).toBe(true);
    expect(isRecommendedBomRole('qo_sales')).toBe(true);
    expect(isRecommendedBomRole('qo_procurement')).toBe(true);
    expect(isRecommendedBomRole('bom_engineering')).toBe(true);
    // pre-v2 merged roles stay recognized so old tenants keep friendly labels.
    expect(isRecommendedBomRole('bom_operator')).toBe(true);
    expect(isRecommendedBomRole('qo_quoter')).toBe(true);
    // unrelated roles are not recommended.
    expect(isRecommendedBomRole('crm_admin')).toBe(false);
  });

  it('labels the v2 roles with their business-roles.json names', () => {
    expect(recommendedBomRoleLabel('qo_sales')).toBe('销售');
    expect(recommendedBomRoleLabel('qo_procurement')).toBe('采购');
    expect(recommendedBomRoleLabel('bom_engineering')).toBe('工程');
    expect(recommendedBomRoleLabel('crm_admin')).toBeNull();
  });

  it('sorts v2 roles first, legacy roles next, then everything else', () => {
    const sorted = sortRolesForPermissionSetup([
      role('crm_admin', 'CRM 管理员'),
      role('qo_quoter', '报价员'),
      role('bom_engineering', '工程'),
      role('tenant_admin', '租户管理员'),
      role('qo_sales', '销售'),
      role('bom_operator', 'BOM 操作员'),
      role('crm_sales', '销售代表'),
    ]);

    expect(sorted.map((item) => item.code)).toEqual([
      'tenant_admin',
      'qo_sales',
      'bom_engineering',
      'bom_operator',
      'qo_quoter',
      'crm_admin',
      'crm_sales',
    ]);
  });
});
