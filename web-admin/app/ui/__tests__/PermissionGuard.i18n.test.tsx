import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PermissionButton } from '../PermissionGuard';

// PermissionButton's disabled-state title was hardcoded '无权限'; it now goes through
// useSmartText('$i18n:permission_guard.no_access', 'No permission'). With no i18n provider
// in the test env, st() returns the English fallback.
vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({
    hasPermission: () => false,
    hasRole: () => false,
    hasAnyPermission: () => false,
    hasAllPermissions: () => false,
  }),
}));

describe('PermissionButton i18n', () => {
  it('shows the localized no-permission title when access is denied', () => {
    render(<PermissionButton permission="user:create">Create</PermissionButton>);
    const btn = screen.getByRole('button', { name: 'Create' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('title', 'No permission');
    // must not leak the raw i18n key or Chinese
    expect(btn.getAttribute('title')).not.toContain('permission_guard');
    expect(btn.getAttribute('title')).not.toContain('无权限');
  });
});
