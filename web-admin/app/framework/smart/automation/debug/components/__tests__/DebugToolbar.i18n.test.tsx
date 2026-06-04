/**
 * Tests that DebugToolbar renders localized strings (not hardcoded English).
 * Verifies P1-4: debug components i18n.
 *
 * Uses the real Zustand store (via setState) rather than mocking the module,
 * following the pattern established in FlowPropertyPanel.extensions.test.tsx.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import React from 'react';

// ---------------------------------------------------------------------------
// Mock i18n — returns last segment of the $i18n: key in UPPERCASE so tests
// can distinguish resolved translations from hardcoded English fallbacks.
// ---------------------------------------------------------------------------
vi.mock('~/utils/i18n', () => ({
  useSmartText:
    () =>
    (key: string): string => {
      if (typeof key === 'string' && key.startsWith('$i18n:')) {
        return key.slice(6).split('.').pop()?.toUpperCase() ?? '';
      }
      return '';
    },
}));

// Mock the debug service so the store's async actions don't hit the network
vi.mock('~/framework/smart/automation/services/debugService', () => ({
  debugService: {
    createSession: vi.fn(),
    step: vi.fn(),
    continue: vi.fn(),
    stop: vi.fn(),
    restart: vi.fn(),
    updateBreakpoints: vi.fn(),
    getSession: vi.fn(),
  },
}));

// ---------------------------------------------------------------------------
// Import components + store AFTER mocks (~ alias resolves correctly)
// ---------------------------------------------------------------------------
import { DebugToolbar } from '~/framework/smart/automation/debug/components/DebugToolbar';
import { useDebugSession } from '~/framework/smart/automation/debug/hooks/useDebugSession';

const MOCK_SESSION = {
  pid: 'test-sess',
  automationId: 'auto-1',
  status: 'paused' as const,
  currentActionIndex: 1,
  totalActions: 3,
  breakpoints: [],
  executionContext: {},
  actionResults: [],
  triggerPayload: {},
  createdAt: '',
  updatedAt: '',
};

beforeEach(() => {
  act(() => {
    useDebugSession.setState({ session: MOCK_SESSION, loading: false });
  });
});

afterEach(() => {
  act(() => {
    useDebugSession.setState({ session: null, loading: false });
  });
});

describe('DebugToolbar — i18n', () => {
  it('renders Step button text via i18n (not hardcoded "Step")', () => {
    render(<DebugToolbar />);
    // st('$i18n:automation.debug.toolbar.step') → mock returns 'STEP'
    expect(screen.getByText('STEP')).toBeInTheDocument();
    expect(screen.queryByText(/^Step$/)).not.toBeInTheDocument();
  });

  it('renders Continue button text via i18n', () => {
    render(<DebugToolbar />);
    expect(screen.getByText('CONTINUE')).toBeInTheDocument();
    expect(screen.queryByText(/^Continue$/)).not.toBeInTheDocument();
  });

  it('renders Restart button text via i18n', () => {
    render(<DebugToolbar />);
    expect(screen.getByText('RESTART')).toBeInTheDocument();
    expect(screen.queryByText(/^Restart$/)).not.toBeInTheDocument();
  });

  it('renders Stop button text via i18n', () => {
    render(<DebugToolbar />);
    expect(screen.getByText('STOP')).toBeInTheDocument();
    expect(screen.queryByText(/^Stop$/)).not.toBeInTheDocument();
  });

  it('renders Exit Debug button text via i18n', () => {
    render(<DebugToolbar />);
    expect(screen.getByText('EXIT')).toBeInTheDocument();
    expect(screen.queryByText(/^Exit Debug$/)).not.toBeInTheDocument();
  });
});
