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
        // Match capability scope settings: menu/tab grants have no record scope.
        // Missing model scopes remain visible as configuration gaps.
        if (act.granted && (act.code.startsWith('model.') || act.scopeType != null)) {
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
 * - explicit unavailable/no_grants states when no scope can be inferred;
 * - the single normalized scope when every granted action shares it;
 * - 'mixed' when granted actions use more than one scope.
 */
export function deriveRoleScope(matrix: PermissionMatrixDTO | null): string {
  if (!matrix) return 'unavailable';
  const granted = grantedActions(matrix);
  if (granted.length === 0) return 'no_grants';
  const scopes = new Set(granted.map((g) => g.scopeType));
  if (scopes.has('invalid')) return 'invalid';
  return scopes.size === 1 ? [...scopes][0] : 'mixed';
}
