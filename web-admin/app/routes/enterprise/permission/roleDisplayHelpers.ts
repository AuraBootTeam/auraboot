import type { Role } from './types';

const RECOMMENDED_BOM_ROLE_ORDER = ['tenant_admin', 'bom_operator', 'qo_quoter'];
const RECOMMENDED_BOM_ROLE_RANK = new Map(
  RECOMMENDED_BOM_ROLE_ORDER.map((code, index) => [code, index]),
);
const RECOMMENDED_BOM_ROLE_LABEL: Record<string, string> = {
  tenant_admin: '管理员',
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
