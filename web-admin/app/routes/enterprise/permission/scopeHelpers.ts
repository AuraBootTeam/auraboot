import type { PermissionMatrixDTO } from './types';
import { normalizeScope } from './scopeConfig';

export interface GrantedActionRef {
  resourceCode: string;
  actionCode: string;
  scopeType: string;
}

/** All granted leaf actions across the matrix, with their normalized scope. */
export function grantedActions(matrix: PermissionMatrixDTO | null): GrantedActionRef[] {
  if (!matrix) return [];
  const out: GrantedActionRef[] = [];
  for (const mod of matrix.modules) {
    for (const res of mod.resources) {
      for (const act of res.actions) {
        if (act.granted) {
          out.push({
            resourceCode: res.resourceCode,
            actionCode: act.action,
            scopeType: normalizeScope(act.scopeType),
          });
        }
      }
    }
  }
  return out;
}

/**
 * The role's overall data scope:
 * - 'all' when nothing is granted (nothing to scope);
 * - the single normalized scope when every granted action shares it;
 * - 'mixed' when granted actions use more than one scope.
 */
export function deriveRoleScope(matrix: PermissionMatrixDTO | null): string {
  const granted = grantedActions(matrix);
  if (granted.length === 0) return 'all';
  const scopes = new Set(granted.map((g) => g.scopeType));
  return scopes.size === 1 ? [...scopes][0] : 'mixed';
}
