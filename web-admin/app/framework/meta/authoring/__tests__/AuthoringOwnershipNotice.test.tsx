import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AuthoringOwnershipNotice } from '../AuthoringOwnershipNotice';

describe('AuthoringOwnershipNotice', () => {
  it('explains the tenant override boundary and immutable source', () => {
    render(
      <AuthoringOwnershipNotice
        ownership={{
          ownershipScope: 'TENANT',
          sourceOwnershipScope: 'APPLICATION',
          sourcePagePid: 'page-shared-1',
          overridePid: 'override-1',
          origin: 'TENANT_OVERRIDE',
          tenantOverride: true,
          sourceMutable: false,
          restoreTarget: 'APPLICATION',
        }}
      />,
    );

    const notice = screen.getByTestId('authoring-ownership-notice');
    expect(notice).toHaveTextContent('正在编辑租户派生层');
    expect(screen.getByText(/APPLICATION → TENANT/)).toBeInTheDocument();
    expect(notice).toHaveTextContent(/来源页面 page-shared-1保持不变/);
    expect(notice).toHaveTextContent(/恢复默认时应回到 APPLICATION 层/);
  });

  it('does not add noise for a tenant-owned page', () => {
    const { container } = render(
      <AuthoringOwnershipNotice
        ownership={{
          ownershipScope: 'TENANT',
          sourceOwnershipScope: 'TENANT',
          sourcePagePid: 'page-tenant-1',
          origin: 'DESIGN_STUDIO',
          tenantOverride: false,
          sourceMutable: true,
          restoreTarget: 'TENANT',
        }}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });
});
