import { describe, expect, it } from 'vitest';
import {
  isRecommendedBomRole,
  localizedRoleText,
  recommendedBomRoleLabel,
  sortRolesForPermissionSetup,
} from '../roleDisplayHelpers';
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

describe('localizedRoleText', () => {
  it('translates shipped descriptions in both directions', () => {
    const procurement = {
      ...role('qo_procurement', '采购'),
      description:
        'Procurement role: maintain sourcing, manual prices, costs, evidence, process points, and corrected BOM imports for QuoteOps.',
    };
    expect(localizedRoleText(procurement, 'description', 'zh-CN')).toContain('采购岗位');
    expect(localizedRoleText(procurement, 'description', 'en-US')).toBe(procurement.description);
    expect(localizedRoleText(procurement, 'name', 'en-US')).toBe('Procurement');
    expect(
      localizedRoleText(
        { ...role('tenant_admin'), description: '租户管理员角色，拥有该租户的所有权限' },
        'description',
        'en-US',
      ),
    ).toContain('Tenant administrator');
  });
  it('preserves tenant-edited names, descriptions, and unknown roles', () => {
    const edited = { ...role('qo_procurement', '本地采购负责人'), description: '仅处理华东客户' };
    expect(localizedRoleText(edited, 'name', 'en-US')).toBe(edited.name);
    expect(localizedRoleText(edited, 'description', 'en-US')).toBe(edited.description);
    expect(localizedRoleText({ ...edited, code: 'custom_buyer' }, 'description', 'zh-CN')).toBe(
      edited.description,
    );
  });
});
