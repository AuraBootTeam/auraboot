import { describe, expect, it } from 'vitest';
import type { RootLoaderData } from '~/root-data';
import {
  authorizationReduced,
  mergeSessionContext,
  validateSessionContext,
} from '../session-context';
const source = {
  user: { id: 'one', tenantId: 'school' },
  permissions: { permissionCodes: ['read'], roles: [{ code: 'teacher' }] },
  preferences: null,
  menus: [],
  branding: { productName: 'Brand' },
  i18n: { original: 'kept' },
} as unknown as RootLoaderData;
describe('session context projection', () => {
  it('preserves referential identity when session data has not changed', () => {
    expect(mergeSessionContext(source, JSON.parse(JSON.stringify(source)))).toBe(source);
  });
  it('updates only session fields and retains deployment and locale references', () => {
    const changed = mergeSessionContext(source, { ...source, menus: [{ path: '/home' }] });
    expect(changed.menus).toEqual([{ path: '/home' }]);
    expect(changed.branding).toBe(source.branding);
    expect(changed.i18n).toBe(source.i18n);
    expect(changed.user).toBe(source.user);
  });
  it('rejects actor, tenant, role and permission reductions', () => {
    for (const next of [
      { ...source, user: { ...source.user, id: 'two' } },
      { ...source, user: { ...source.user, tenantId: 'other' } },
      { ...source, permissions: { ...source.permissions, roles: [] } },
      { ...source, permissions: { ...source.permissions, permissionCodes: [] } },
    ])
      expect(authorizationReduced(source, next)).toBe(true);
  });
  it('accepts a typed snapshot and fails fast on invalid contracts', () => {
    expect(validateSessionContext(source)).toBe(source);
    expect(() => validateSessionContext({ user: source.user })).toThrow('contract');
  });
});
