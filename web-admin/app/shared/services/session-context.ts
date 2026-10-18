import type { RootLoaderData } from '~/root-data';

export const SESSION_CONTEXT_UNAVAILABLE = 'session_context_unavailable';
export type SessionContextSnapshot = Pick<
  RootLoaderData,
  'user' | 'permissions' | 'preferences' | 'menus'
>;

const identity = (user: any) => `${user?.pid ?? user?.id ?? ''}:${user?.tenantId ?? ''}`;
const capabilities = (snapshot: SessionContextSnapshot) =>
  new Set<string>([
    ...(snapshot.permissions?.permissionCodes ?? []).map((code: string) => `permission:${code}`),
    ...(snapshot.permissions?.permissions ?? []).map((item: any) => `permission:${item.code}`),
    ...(snapshot.permissions?.roles ?? []).map((item: any) => `role:${item.code}`),
  ]);

/** A reduction invalidates all previously rendered protected content, including drafts. */
export function authorizationReduced(
  previous: SessionContextSnapshot,
  next: SessionContextSnapshot,
): boolean {
  if (identity(previous.user) !== identity(next.user)) return true;
  const current = capabilities(next);
  return [...capabilities(previous)].some((code) => !current.has(code));
}

export function mergeSessionContext(
  previous: RootLoaderData,
  next: SessionContextSnapshot,
): RootLoaderData {
  let changed = false;
  const result = { ...previous };
  for (const key of ['user', 'permissions', 'preferences', 'menus'] as const) {
    if (JSON.stringify(previous[key]) !== JSON.stringify(next[key])) {
      (result as any)[key] = next[key];
      changed = true;
    }
  }
  return changed ? result : previous;
}

export function validateSessionContext(value: unknown): SessionContextSnapshot {
  const snapshot = value as SessionContextSnapshot | undefined;
  if (
    !snapshot?.user ||
    !identity(snapshot.user).split(':')[0] ||
    !snapshot.permissions ||
    !Array.isArray(snapshot.menus) ||
    (!Array.isArray(snapshot.permissions.permissionCodes) &&
      !Array.isArray(snapshot.permissions.permissions))
  ) {
    throw new Error('Session context response is missing the authenticated contract');
  }
  return snapshot;
}
