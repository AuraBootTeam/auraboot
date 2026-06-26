import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

let mockPreferences: Record<string, unknown> | null = null;
let mockIsAuthenticated = true;
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ preferences: mockPreferences, isAuthenticated: mockIsAuthenticated }),
}));

const tenantGet = vi.fn();
vi.mock('~/shared/services/tenantPreferenceService', () => ({
  tenantPreferenceService: {
    get: (key: string) => tenantGet(key),
    set: vi.fn(),
  },
}));

import { TimezoneProvider, useTimezone } from '../TimezoneContext';

function Probe() {
  const { timezone, formats } = useTimezone();
  return (
    <div>
      <span data-testid="tz">{timezone}</span>
      <span data-testid="datetime-fmt">{formats.datetime}</span>
    </div>
  );
}

function renderProbe() {
  return render(
    <TimezoneProvider>
      <Probe />
    </TimezoneProvider>,
  );
}

describe('TimezoneContext resolution chain', () => {
  beforeEach(() => {
    mockPreferences = null;
    mockIsAuthenticated = true;
    tenantGet.mockReset();
    window.localStorage.clear();
  });

  it('does not fetch tenant preferences before authentication', async () => {
    mockIsAuthenticated = false;
    mockPreferences = null;
    tenantGet.mockResolvedValue('America/New_York');

    renderProbe();

    await waitFor(() => expect(screen.getByTestId('tz').textContent).toBeTruthy());
    expect(tenantGet).not.toHaveBeenCalled();
  });

  it('falls back to the tenant ui.timezone preference when the user has none', async () => {
    mockPreferences = {}; // logged in, but no personal timezone
    // Use a tenant tz different from any China dev machine's local tz so the
    // test only passes when the tenant preference is actually consumed (a
    // browser-tz fallback would yield a different value).
    tenantGet.mockImplementation((key: string) =>
      Promise.resolve(
        key === 'ui.timezone'
          ? 'America/New_York'
          : key === 'ui.datetime.format'
            ? 'YYYY/MM/DD HH:mm'
            : null,
      ),
    );

    renderProbe();

    await waitFor(() => expect(screen.getByTestId('tz').textContent).toBe('America/New_York'));
    expect(screen.getByTestId('datetime-fmt').textContent).toBe('YYYY/MM/DD HH:mm');
  });

  it('prefers the user personal timezone over the tenant preference', async () => {
    mockPreferences = { timezone: 'America/New_York' };
    tenantGet.mockResolvedValue('Asia/Shanghai');

    renderProbe();

    await waitFor(() => expect(screen.getByTestId('tz').textContent).toBe('America/New_York'));
  });
});
