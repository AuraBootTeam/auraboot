import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Force a deterministic effective timezone, different from the machine TZ, so
// the assertion only passes when the readonly display honors the timezone.
vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({
    timezone: 'America/New_York',
    formats: { date: 'YYYY-MM-DD', datetime: 'YYYY-MM-DD HH:mm:ss', time: 'HH:mm:ss' },
  }),
}));

import { Datetime } from '../Datetime';

describe('Datetime widget readonly display', () => {
  it('renders a UTC value converted into the effective timezone', () => {
    render(<Datetime name="completed_at" value="2026-06-03T03:08:04Z" readOnly />);
    // 03:08 UTC == 23:08 previous day in New York (EDT)
    expect(screen.getByText('2026-06-02 23:08:04')).toBeInTheDocument();
  });
});
