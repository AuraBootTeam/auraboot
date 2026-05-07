/**
 * AdminShadowRunsPage.test.tsx — D.5 Phase 1.
 *
 * Pins the page wiring against the AdminShadowRunController REST contract:
 *   - empty fixture renders explicit empty state
 *   - aggregations render concrete KPI cells
 *   - API failure surfaces error banner with retry button
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

const { listAggsMock, listRunsMock } = vi.hoisted(() => ({
  listAggsMock: vi.fn(),
  listRunsMock: vi.fn(async (..._args: unknown[]) => [] as unknown[]),
}));

// Vitest in this repo runs with `isolate: false` (single-thread, shared
// module graph). Both AdminShadowRunsPage.test.tsx and
// ShadowRunDetailDrawer.test.tsx mock `services/shadowRunsApi`. To avoid
// the first file's vi.mock factory winning for both, declare both
// exported symbols as forwarding-to-vi.fn shims here too — the drawer
// test also forwards through its own hoisted mock and replaces the impl
// per-test via mockResolvedValue.
vi.mock('../services/shadowRunsApi', () => ({
  listShadowRunAggregations: (...args: unknown[]) => listAggsMock(...args),
  listShadowRunsForDraft: (...args: unknown[]) => listRunsMock(...args),
}));

// Per-file: re-import real react-router so useSearchParams works for state.
vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return actual;
});

// Stub the I18n context so the page resolves a locale.
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US' }),
}));

import { MemoryRouter } from 'react-router';
import AdminShadowRunsPage from '../pages/admin/shadow-runs';

const SAMPLE_AGGS = [
  {
    draftId: 'DRAFT01',
    draftSkillCode: 'auto.alpha',
    draftStatus: 'SHADOW_RUNNING',
    runCount: 5,
    fidelitySamples: 5,
    outputSamples: 5,
    fidelityMatchRate: 0.8,
    outputMatchRate: 1.0,
    costDelta: 0.0012,
    latestAt: new Date(Date.now() - 60_000).toISOString(),
  },
  {
    draftId: 'DRAFT02',
    draftSkillCode: 'auto.beta',
    draftStatus: 'REVIEWED_OK',
    runCount: 2,
    fidelitySamples: 2,
    outputSamples: 2,
    fidelityMatchRate: 0.5,
    outputMatchRate: 0.5,
    costDelta: -0.0008,
    latestAt: new Date(Date.now() - 3_600_000).toISOString(),
  },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/admin/agent-runs/shadow-runs']}>
      <AdminShadowRunsPage />
    </MemoryRouter>,
  );
}

describe('AdminShadowRunsPage', () => {
  beforeEach(() => {
    listAggsMock.mockReset();
  });

  it('renders explicit empty state when no aggregations', async () => {
    listAggsMock.mockResolvedValueOnce([]);
    renderPage();
    await waitFor(() => expect(screen.getByTestId('empty-state')).toBeInTheDocument());
    expect(screen.getByTestId('empty-state').textContent).toMatch(/no shadow runs/i);
  });

  it('renders aggregation rows with KPI cells when data loaded', async () => {
    listAggsMock.mockResolvedValueOnce(SAMPLE_AGGS);
    renderPage();
    await waitFor(() =>
      expect(screen.getByTestId('aggregations-table')).toBeInTheDocument(),
    );
    // Concrete KPI assertions (NOT just toBeVisible).
    expect(screen.getByTestId('fidelity-rate-DRAFT01').textContent).toBe('80%');
    expect(screen.getByTestId('output-rate-DRAFT01').textContent).toBe('100%');
    expect(screen.getByTestId('cost-delta-DRAFT01').textContent).toMatch(/\+\$0\.0012/);
    expect(screen.getByTestId('fidelity-rate-DRAFT02').textContent).toBe('50%');
    expect(screen.getByTestId('cost-delta-DRAFT02').textContent).toMatch(/-\$0\.0008/);
  });

  it('shows error banner with retry button on API failure', async () => {
    listAggsMock
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce([]);
    renderPage();
    await waitFor(() => expect(screen.getByTestId('error-banner')).toBeInTheDocument());
    expect(screen.getByTestId('error-banner').textContent).toMatch(/boom/);

    // Retry recovers to empty state
    fireEvent.click(screen.getByTestId('error-retry'));
    await waitFor(() => expect(screen.getByTestId('empty-state')).toBeInTheDocument());
  });
});
