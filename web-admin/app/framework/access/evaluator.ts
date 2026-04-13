/**
 * Access evaluator — applies permission and feature-gate checks.
 *
 * Used by the RouteRegistry when building per-user menu trees, by Slot
 * rendering to gate enterprise overlays, and by ActionButton components.
 *
 * Key invariant: feature and permission are independent gates.
 *   visible = !hidden && hasAllPermissions(p) && hasAllFeatures(f)
 */

export interface AccessUser {
  permissions: ReadonlySet<string> | readonly string[]
  features: ReadonlySet<string> | readonly string[]
}

export interface AccessRequirement {
  permission?: string | readonly string[]
  featureKey?: string | readonly string[]
  hidden?: boolean
}

export interface AccessDecision {
  visible: boolean
  /** First failing reason, useful for "upgrade required" affordances. */
  reason?: 'hidden' | 'missing_permission' | 'missing_feature'
  /** When reason is missing_*, the offending key. */
  missing?: string
}

function toSet(v: ReadonlySet<string> | readonly string[]): Set<string> {
  if (v instanceof Set) return v as Set<string>
  return new Set(v)
}

function toList(v: string | readonly string[] | undefined): readonly string[] {
  if (v === undefined) return []
  return typeof v === 'string' ? [v] : v
}

export function evaluateAccess(
  req: AccessRequirement,
  user: AccessUser,
): AccessDecision {
  if (req.hidden) {
    return { visible: false, reason: 'hidden' }
  }

  const userPerms = toSet(user.permissions)
  for (const p of toList(req.permission)) {
    if (!userPerms.has(p)) {
      return { visible: false, reason: 'missing_permission', missing: p }
    }
  }

  const userFeatures = toSet(user.features)
  for (const f of toList(req.featureKey)) {
    if (!userFeatures.has(f)) {
      return { visible: false, reason: 'missing_feature', missing: f }
    }
  }

  return { visible: true }
}
