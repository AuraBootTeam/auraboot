import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AuthProvider, useAuth } from '../AuthContext';

vi.mock('react-router', () => ({ useLoaderData: () => undefined }));

function PermissionProbe() {
  const { hasPermission } = useAuth();
  return <output data-testid="allowed">{String(hasPermission('model.tenant_member.suspend'))}</output>;
}

describe('explicit frontend permission grants', () => {
  it.each([
    [{ permissionCodes: ['*'] }, true],
    [{ permissions: [{ code: '*' }] }, true],
    [{ permissionCodes: ['model.tenant_member.suspend'] }, true],
    [{ permissionCodes: ['model.tenant_member.read'] }, false],
    [{ permissionCodes: [], roles: [{ code: 'tenant_admin' }] }, false],
    [{ permissionCodes: [], permissions: [{ code: '*' }] }, false],
  ] as const)('matches only the authoritative snapshot %#', (permissions, allowed) => {
    render(<AuthProvider data={{ user: null, permissions: permissions as any }}><PermissionProbe /></AuthProvider>);
    expect(screen.getByTestId('allowed')).toHaveTextContent(String(allowed));
  });
});
