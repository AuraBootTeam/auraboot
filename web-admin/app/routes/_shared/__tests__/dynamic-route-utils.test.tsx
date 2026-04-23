import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DynamicField } from '../dynamic-route-utils';

describe('DynamicField', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: {
          pid: 'user-1',
          displayName: 'Admin User',
          email: 'admin@example.com',
        },
      }),
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('renders readonly memberpicker labels from persisted JSON arrays', async () => {
    render(
      <DynamicField
        field={{
          field: 'wd_req_cc_users',
          label: '抄送人',
          component: 'memberpicker',
          props: { multiple: true },
        }}
        value='["user-1"]'
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('member-picker-readonly')).toHaveTextContent('Admin User');
    });

    expect(globalThis.fetch).toHaveBeenCalledWith('/api/admin/users/user-1');
    expect(screen.queryByText('["user-1"]')).not.toBeInTheDocument();
  });
});
