import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DecisionRolloutMonitor } from '../DecisionRolloutMonitor';
import type { DecisionApi } from '../../api/decisionApi';

function renderMonitor(
  api: Partial<DecisionApi>,
  hasPermission: (permissionCode: string) => boolean = () => true,
  props: Partial<React.ComponentProps<typeof DecisionRolloutMonitor>> = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <DecisionRolloutMonitor
        api={api as DecisionApi}
        initialDecisionCode="risk_score"
        hasPermission={hasPermission}
        {...props}
      />
    </QueryClientProvider>,
  );
}

function rolloutApi(overrides: Partial<DecisionApi> = {}): Partial<DecisionApi> {
  return {
    listRollouts: vi.fn(async () => [
      {
        pid: 'rollout-1',
        decisionCode: 'risk_score',
        baselineVersion: 1,
        candidateVersion: 2,
        status: 'ACTIVE',
        percentage: 10,
        cohort: { routingKeys: ['record-1'], traceIdPrefix: ['vip-'] },
        segment: { tenantSegments: ['early'] },
        routingKeyExpr: 'traceId',
        salt: 'risk-score',
        audit: {
          action: 'PROMOTE',
          note: 'candidate metrics accepted',
          by: 'user-123',
          at: '2026-06-09T13:00:00Z',
        },
      },
    ]),
    getRolloutMetrics: vi.fn(async () => ({
      policyPid: 'rollout-1',
      baseline: {
        version: 1,
        evaluations: 90,
        matched: 60,
        errors: 1,
        matchedRate: 0.6667,
        errorRate: 0.0111,
        p95LatencyMs: 22,
        resultDistribution: { APPROVE: 60, REVIEW: 30 },
      },
      candidate: {
        version: 2,
        evaluations: 10,
        matched: 8,
        errors: 0,
        matchedRate: 0.8,
        errorRate: 0,
        p95LatencyMs: 18,
        resultDistribution: { APPROVE: 8, REVIEW: 2 },
      },
    })),
    createRollout: vi.fn(async () => ({
      pid: 'rollout-new',
      decisionCode: 'risk_score',
      baselineVersion: 1,
      candidateVersion: 3,
      status: 'DRAFT',
      percentage: 5,
    })),
    activateRollout: vi.fn(async () => ({ pid: 'rollout-1', status: 'ACTIVE' })),
    pauseRollout: vi.fn(async () => ({ pid: 'rollout-1', status: 'PAUSED' })),
    promoteRollout: vi.fn(async () => ({ pid: 'rollout-1', status: 'PROMOTED' })),
    rollbackRollout: vi.fn(async () => ({ pid: 'rollout-1', status: 'ROLLED_BACK' })),
    ...overrides,
  };
}

describe('DecisionRolloutMonitor', () => {
  it('lists rollout policies and renders baseline/candidate metrics', async () => {
    const api = rolloutApi();
    renderMonitor(api);

    await waitFor(() => expect(api.listRollouts).toHaveBeenCalledWith('risk_score'));
    await waitFor(() => expect(screen.getByTestId('rollout-row-rollout-1')).toBeInTheDocument());
    expect(screen.getByTestId('rollout-row-rollout-1')).toHaveTextContent('v1');
    expect(screen.getByTestId('rollout-row-rollout-1')).toHaveTextContent('v2');
    expect(screen.getByTestId('rollout-row-rollout-1')).toHaveTextContent('Cohort keys 1');
    expect(screen.getByTestId('rollout-row-rollout-1')).toHaveTextContent('Segments 1');
    await waitFor(() => expect(screen.getByTestId('rollout-metrics-baseline')).toBeInTheDocument());
    expect(api.getRolloutMetrics).toHaveBeenCalledWith('rollout-1');
    expect(screen.getByTestId('rollout-metrics-baseline')).toHaveTextContent('90');
    expect(screen.getByTestId('rollout-metrics-candidate')).toHaveTextContent('10');
    expect(screen.getByTestId('rollout-metrics-candidate')).toHaveTextContent('80.0%');
    expect(screen.getByTestId('rollout-metrics-baseline-distribution')).toHaveTextContent(
      'APPROVE',
    );
    expect(screen.getByTestId('rollout-metrics-baseline-distribution')).toHaveTextContent('60');
    expect(screen.getByTestId('rollout-metrics-candidate-distribution')).toHaveTextContent(
      'REVIEW',
    );
    expect(screen.getByTestId('rollout-metrics-candidate-distribution')).toHaveTextContent('2');
  });

  it('creates a rollout policy from the inline configuration form', async () => {
    const api = rolloutApi({ listRollouts: vi.fn(async () => []) });
    renderMonitor(api);

    fireEvent.change(screen.getByLabelText('rollout-baseline-version'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('rollout-candidate-version'), {
      target: { value: '3' },
    });
    fireEvent.change(screen.getByLabelText('rollout-percentage'), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText('rollout-routing-key'), {
      target: { value: 'recordId' },
    });
    fireEvent.change(screen.getByLabelText('rollout-cohort-routing-keys'), {
      target: { value: 'record-1, record-2' },
    });
    fireEvent.change(screen.getByLabelText('rollout-cohort-trace-prefixes'), {
      target: { value: 'vip-, beta-' },
    });
    fireEvent.change(screen.getByLabelText('rollout-tenant-segments'), {
      target: { value: 'early, beta' },
    });
    fireEvent.change(screen.getByLabelText('rollout-salt'), { target: { value: 'stable-salt' } });
    fireEvent.click(screen.getByTestId('rollout-create'));

    await waitFor(() =>
      expect(api.createRollout).toHaveBeenCalledWith('risk_score', {
        baselineVersion: 1,
        candidateVersion: 3,
        percentage: 5,
        cohort: {
          routingKeys: ['record-1', 'record-2'],
          traceIdPrefix: ['vip-', 'beta-'],
        },
        segment: { tenantSegments: ['early', 'beta'] },
        routingKeyExpr: 'recordId',
        salt: 'stable-salt',
      }),
    );
    expect(await screen.findByTestId('rollout-status-message')).toHaveTextContent('灰度策略已创建');
  });

  it('prefills decision and version fields from a start-rollout deep link', async () => {
    const api = rolloutApi({ listRollouts: vi.fn(async () => []) });
    renderMonitor(api, () => true, {
      initialDecisionCode: 'sla_deadline',
      initialBaselineVersion: 4,
      initialCandidateVersion: 5,
    });

    await waitFor(() => expect(api.listRollouts).toHaveBeenCalledWith('sla_deadline'));
    expect(screen.getByLabelText('rollout-decision-code')).toHaveValue('sla_deadline');
    expect(screen.getByLabelText('rollout-baseline-version')).toHaveValue(4);
    expect(screen.getByLabelText('rollout-candidate-version')).toHaveValue(5);
  });

  it('renders rollout audit timeline details for the selected policy', async () => {
    const api = rolloutApi();
    renderMonitor(api);

    await waitFor(() => expect(screen.getByTestId('rollout-row-rollout-1')).toBeInTheDocument());
    expect(screen.getByTestId('rollout-audit-panel')).toHaveTextContent('审计时间线');
    expect(screen.getByTestId('rollout-audit-panel')).toHaveTextContent('PROMOTE');
    expect(screen.getByTestId('rollout-audit-panel')).toHaveTextContent(
      'candidate metrics accepted',
    );
    expect(screen.getByTestId('rollout-audit-panel')).toHaveTextContent('user-123');
    expect(screen.getByTestId('rollout-audit-panel')).toHaveTextContent('2026-06-09T13:00:00Z');
  });

  it('disables rollout lifecycle controls with visible reasons when current user lacks permissions', async () => {
    const api = rolloutApi();
    renderMonitor(api, () => false);

    await waitFor(() => expect(screen.getByTestId('rollout-row-rollout-1')).toBeInTheDocument());
    expect(screen.getByTestId('rollout-create')).toBeDisabled();
    expect(screen.getByTestId('rollout-activate-rollout-1')).toBeDisabled();
    expect(screen.getByTestId('rollout-pause-rollout-1')).toBeDisabled();
    expect(screen.getByTestId('rollout-promote-rollout-1')).toBeDisabled();
    expect(screen.getByTestId('rollout-rollback-rollout-1')).toBeDisabled();
    expect(screen.getByTestId('rollout-permission-hint')).toHaveTextContent('缺少灰度管理权限');
    expect(screen.getByTestId('rollout-permission-hint')).toHaveTextContent('缺少全量发布权限');
    expect(screen.getByTestId('rollout-permission-hint')).toHaveTextContent('缺少灰度回滚权限');
  });

  it('requires inline confirmation before promote and rollback lifecycle actions', async () => {
    const api = rolloutApi();
    renderMonitor(api);

    await waitFor(() => expect(screen.getByTestId('rollout-row-rollout-1')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('rollout-promote-rollout-1'));
    expect(screen.getByTestId('rollout-confirm-panel')).toHaveTextContent('promote');
    fireEvent.change(screen.getByLabelText('rollout-action-note'), {
      target: { value: 'metrics accepted' },
    });
    fireEvent.click(screen.getByTestId('rollout-confirm-action'));

    await waitFor(() =>
      expect(api.promoteRollout).toHaveBeenCalledWith('rollout-1', { note: 'metrics accepted' }),
    );
    expect(screen.getByTestId('rollout-status-message')).toHaveTextContent('已执行 promote');

    fireEvent.click(screen.getByTestId('rollout-rollback-rollout-1'));
    fireEvent.change(screen.getByLabelText('rollout-action-note'), {
      target: { value: 'candidate regression' },
    });
    fireEvent.click(screen.getByTestId('rollout-confirm-action'));

    await waitFor(() =>
      expect(api.rollbackRollout).toHaveBeenCalledWith('rollout-1', {
        note: 'candidate regression',
      }),
    );
    expect(screen.getByTestId('rollout-status-message')).toHaveTextContent('已执行 rollback');
  });
});
