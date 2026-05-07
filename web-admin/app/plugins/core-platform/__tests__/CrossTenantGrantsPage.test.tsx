/**
 * CrossTenantGrantsPage vitest spec — pins the page + modal wiring against
 * the C.2 REST contract.
 *
 * <p>All page-level + modal-level cases live in one file because vitest is
 * configured with {@code isolate:false} (see vitest.config.ts), so mocks
 * declared via {@code vi.mock} in separate files would bleed across the
 * shared module graph and cause flaky cross-file failures. Consolidating
 * keeps the {@code vi.hoisted} mocks scoped to one module-load.
 *
 * <p>Cases:
 * <ul>
 *   <li>rendersListWithRows — 2 rows render after API responds</li>
 *   <li>rendersEmptyState — empty payload triggers the empty-state CTA</li>
 *   <li>rendersErrorBanner — failure triggers the error banner with retry</li>
 *   <li>GrantFormModal.submitsCreateGrant — form submit calls createGrant</li>
 *   <li>GrantFormModal.showsErrorOnFailure — API failure surfaces in form</li>
 *   <li>RevokeConfirmModal.confirmsRevoke — confirm fires revokeGrant + onRevoked</li>
 * </ul>
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

const {
  listGrantsMock,
  createGrantMock,
  revokeGrantMock,
  listAuditMock,
} = vi.hoisted(() => ({
  listGrantsMock: vi.fn(),
  createGrantMock: vi.fn(),
  revokeGrantMock: vi.fn(),
  listAuditMock: vi.fn(),
}));

vi.mock('../services/crossTenantGrantsApi', () => ({
  listGrants: (...args: unknown[]) => listGrantsMock(...args),
  createGrant: (...args: unknown[]) => createGrantMock(...args),
  revokeGrant: (...args: unknown[]) => revokeGrantMock(...args),
  listAudit: (...args: unknown[]) => listAuditMock(...args),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en' }),
}));

import CrossTenantGrantsPage, {
  GrantFormModal,
  RevokeConfirmModal,
} from '../pages/CrossTenantGrantsPage';

const SAMPLE_ROWS = [
  {
    id: 1,
    parent_tenant_id: 100,
    child_tenant_id: 200,
    grant_type: 'spawn_sub_agent',
    granted_by: 9,
    granted_at: new Date().toISOString(),
    expires_at: null,
    revoked_at: null,
    revoked_by: null,
    note: 'fixture',
  },
  {
    id: 2,
    parent_tenant_id: 100,
    child_tenant_id: 300,
    grant_type: 'spawn_sub_agent',
    granted_by: 9,
    granted_at: new Date().toISOString(),
    expires_at: null,
    revoked_at: new Date().toISOString(),
    revoked_by: 9,
    note: null,
  },
];

beforeEach(() => {
  listGrantsMock.mockReset();
  createGrantMock.mockReset();
  revokeGrantMock.mockReset();
  listAuditMock.mockReset();
});

describe('CrossTenantGrantsPage', () => {
  it('rendersListWithRows: list endpoint feeds the table with parent/child tenant ids', async () => {
    listGrantsMock.mockResolvedValueOnce({
      success: true,
      data: { records: SAMPLE_ROWS, total: 2, pageNum: 1, pageSize: 20 },
    });

    render(<CrossTenantGrantsPage />);

    await waitFor(() => {
      expect(screen.getByTestId('grants-table')).toBeInTheDocument();
    });
    expect(screen.getByTestId('grant-row-1')).toBeInTheDocument();
    expect(screen.getByTestId('grant-row-2')).toBeInTheDocument();
    // Revoked row shows "revoked" badge but no revoke button.
    expect(screen.queryByTestId('grant-revoke-button-2')).toBeNull();
    // Active row shows the revoke button.
    expect(screen.getByTestId('grant-revoke-button-1')).toBeInTheDocument();
    expect(listGrantsMock).toHaveBeenCalledWith(1, 20, false);
  });

  it('rendersEmptyState: empty list triggers empty-state CTA', async () => {
    listGrantsMock.mockResolvedValueOnce({
      success: true,
      data: { records: [], total: 0, pageNum: 1, pageSize: 20 },
    });

    render(<CrossTenantGrantsPage />);

    await waitFor(() => {
      expect(screen.getByTestId('grants-empty')).toBeInTheDocument();
    });
    expect(screen.getByText(/Create the first grant/)).toBeInTheDocument();
  });

  it('rendersErrorBanner: failure surfaces error banner with retry', async () => {
    listGrantsMock.mockResolvedValueOnce({
      success: false,
      message: 'platform_admin required',
    });

    render(<CrossTenantGrantsPage />);

    await waitFor(() => {
      expect(screen.getByTestId('grants-error-banner')).toBeInTheDocument();
    });
    expect(screen.getByText(/platform_admin required/)).toBeInTheDocument();
  });
});

describe('GrantFormModal', () => {
  it('submitsCreateGrant: form submit calls createGrant with parent/child tenant ids', async () => {
    createGrantMock.mockResolvedValueOnce({ success: true, data: { id: 42 } });
    const onCreated = vi.fn();
    render(<GrantFormModal onClose={() => {}} onCreated={onCreated} />);

    fireEvent.change(screen.getByTestId('grant-form-parent-tenant'), { target: { value: '100' } });
    fireEvent.change(screen.getByTestId('grant-form-child-tenant'), { target: { value: '200' } });
    fireEvent.change(screen.getByTestId('grant-form-note'), { target: { value: 'staging supervisor' } });
    // jsdom does not propagate button-click → form-submit reliably; dispatch
    // submit on the form directly so the React handler fires.
    fireEvent.submit(screen.getByTestId('grant-form-submit').closest('form')!);

    await waitFor(() => {
      expect(createGrantMock).toHaveBeenCalledTimes(1);
    });
    const args = createGrantMock.mock.calls[0][0];
    expect(args.parentTenantId).toBe(100);
    expect(args.childTenantId).toBe(200);
    expect(args.note).toBe('staging supervisor');
    expect(onCreated).toHaveBeenCalled();
  });

  it('showsErrorOnFailure: API failure renders form error message', async () => {
    createGrantMock.mockResolvedValueOnce({
      success: false,
      message: 'active grant already exists for this tenant pair',
    });
    render(<GrantFormModal onClose={() => {}} onCreated={() => {}} />);

    fireEvent.change(screen.getByTestId('grant-form-parent-tenant'), { target: { value: '100' } });
    fireEvent.change(screen.getByTestId('grant-form-child-tenant'), { target: { value: '200' } });
    fireEvent.submit(screen.getByTestId('grant-form-submit').closest('form')!);

    await waitFor(() => {
      expect(screen.getByTestId('grant-form-error')).toBeInTheDocument();
    });
    expect(screen.getByText(/active grant already exists/)).toBeInTheDocument();
  });
});

describe('RevokeConfirmModal', () => {
  const TARGET = {
    id: 7,
    parent_tenant_id: 100,
    child_tenant_id: 200,
    grant_type: 'spawn_sub_agent',
    granted_by: 9,
    granted_at: new Date().toISOString(),
    expires_at: null,
    revoked_at: null,
    revoked_by: null,
    note: null,
  };

  it('confirmsRevoke: clicking confirm calls revokeGrant(targetId) and fires onRevoked', async () => {
    revokeGrantMock.mockResolvedValueOnce({ success: true, data: { id: 7 } });
    const onRevoked = vi.fn();
    render(<RevokeConfirmModal target={TARGET} onClose={() => {}} onRevoked={onRevoked} />);

    fireEvent.click(screen.getByTestId('grant-revoke-confirm'));

    await waitFor(() => {
      expect(revokeGrantMock).toHaveBeenCalledWith(7);
    });
    expect(onRevoked).toHaveBeenCalled();
  });
});
