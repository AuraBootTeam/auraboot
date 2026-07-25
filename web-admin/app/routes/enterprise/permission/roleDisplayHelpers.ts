import type { Role } from './types';

// RBAC v2 (business-roles.json) is the source of truth for the Quote/BOM
// deployment: four roles, with 销售/采购/工程 separated. `bom_operator` and
// `qo_quoter` are the pre-v2 merged roles — still recognized so tenants
// provisioned before v2 keep their friendly labels and ordering rather than
// regressing to a raw code, but the v2 roles are what "recommended setup"
// means now and sort first.
const RECOMMENDED_BOM_ROLE_ORDER = [
  'tenant_admin',
  'qo_sales',
  'qo_procurement',
  'bom_engineering',
  // legacy pre-v2 merged roles, kept for backward-compatible display
  'bom_operator',
  'qo_quoter',
];
const RECOMMENDED_BOM_ROLE_RANK = new Map(
  RECOMMENDED_BOM_ROLE_ORDER.map((code, index) => [code, index]),
);
const RECOMMENDED_BOM_ROLE_LABEL: Record<string, string> = {
  tenant_admin: '管理员',
  qo_sales: '销售',
  qo_procurement: '采购',
  bom_engineering: '工程',
  // legacy pre-v2 merged roles
  bom_operator: 'BOM 操作员',
  qo_quoter: '报价员',
};

export function isRecommendedBomRole(roleCode: string): boolean {
  return RECOMMENDED_BOM_ROLE_RANK.has(roleCode);
}

export function sortRolesForPermissionSetup(roles: Role[]): Role[] {
  return roles
    .map((role, index) => ({ role, index }))
    .sort((a, b) => {
      const aRank = RECOMMENDED_BOM_ROLE_RANK.get(a.role.code);
      const bRank = RECOMMENDED_BOM_ROLE_RANK.get(b.role.code);

      if (aRank !== undefined || bRank !== undefined) {
        return (aRank ?? Number.MAX_SAFE_INTEGER) - (bRank ?? Number.MAX_SAFE_INTEGER);
      }

      return a.index - b.index;
    })
    .map(({ role }) => role);
}

export function recommendedBomRoleLabel(roleCode: string): string | null {
  return RECOMMENDED_BOM_ROLE_LABEL[roleCode] ?? null;
}
