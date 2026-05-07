/**
 * ShadowRunDetailDrawer.test.tsx — D.5 Phase 1.
 *
 * Two cases:
 *   - opens with seeded data, renders prod + shadow output cells
 *   - shows skeleton loader before data resolves
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

const { listMock } = vi.hoisted(() => ({ listMock: vi.fn() }));

vi.mock('../../services/shadowRunsApi', () => ({
  listShadowRunsForDraft: (...args: unknown[]) => listMock(...args),
}));

import ShadowRunDetailDrawer from '../ShadowRunDetailDrawer';

const SAMPLE_RUN = {
  pid: 'SHRUN001',
  draftId: 'DRAFT01',
  originalRunId: 'RUN001',
  shadowStatus: 'success',
  shadowDurationMs: 1200,
  shadowCostUsd: 0.005,
  shadowTokens: 42,
  shadowOutputHash: 'abc123',
  originalStatus: 'success',
  originalDurationMs: 1500,
  originalCostUsd: 0.004,
  originalOutputHash: 'def456',
  outputMatch: false,
  fidelityMatch: true,
  outputDiff: '[{"path":"/result","shadow":"a","production":"b"}]',
  createdAt: new Date().toISOString(),
};

describe('ShadowRunDetailDrawer', () => {
  beforeEach(() => listMock.mockReset());

  it('renders prod + shadow output cells once data resolves', async () => {
    listMock.mockResolvedValue([SAMPLE_RUN]);
    render(
      <ShadowRunDetailDrawer
        draftId="DRAFT01"
        draftSkillCode="auto.alpha"
        onClose={vi.fn()}
      />,
    );
    await waitFor(() =>
      expect(screen.getByTestId('shadow-run-list')).toBeInTheDocument(),
    );
    // Concrete value checks
    const prod = screen.getByTestId('shadow-run-prod-SHRUN001');
    expect(prod.textContent).toContain('1.50s');
    expect(prod.textContent).toContain('$0.0040');
    expect(prod.textContent).toContain('def456');

    const shadow = screen.getByTestId('shadow-run-shadow-SHRUN001');
    expect(shadow.textContent).toContain('1.20s');
    expect(shadow.textContent).toContain('$0.0050');
    expect(shadow.textContent).toContain('abc123');

    expect(screen.getByTestId('shadow-run-output-match-SHRUN001').textContent).toContain(
      '✗',
    );
    expect(screen.getByTestId('shadow-run-fidelity-match-SHRUN001').textContent).toContain(
      '✓',
    );
    expect(screen.getByTestId('shadow-run-drawer-title').textContent).toBe('auto.alpha');
  });

  it('shows skeleton loader before data resolves', async () => {
    let resolveFn: (v: typeof SAMPLE_RUN[]) => void = () => {};
    listMock.mockReturnValueOnce(
      new Promise<typeof SAMPLE_RUN[]>((resolve) => {
        resolveFn = resolve;
      }),
    );
    render(
      <ShadowRunDetailDrawer
        draftId="DRAFT01"
        draftSkillCode="auto.alpha"
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('shadow-run-drawer-loading')).toBeInTheDocument();
    resolveFn([]);
    await waitFor(() =>
      expect(screen.getByTestId('shadow-run-drawer-empty')).toBeInTheDocument(),
    );
  });
});
