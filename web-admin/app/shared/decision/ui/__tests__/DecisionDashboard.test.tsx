import { render, screen, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { DecisionDashboard, type DashboardSummary, type ExceptionItem } from '../DecisionDashboard';

const summary: DashboardSummary = {
  definitions: 42, policies: 12, evaluationsToday: 200, matched: 150, failed: 3, retrying: 2, p95LatencyMs: 87,
};
const exceptions: ExceptionItem[] = [
  { traceId: 't-1', code: 'vip_case', status: 'FAILED_RETRYING', error: 'connector timeout', time: '09:40' },
  { traceId: 't-2', code: 'complaint_form', status: 'ERROR', error: 'unknown field', time: '09:31' },
];

describe('DecisionDashboard', () => {
  it('renders KPI cards with derived match rate + combined failures', () => {
    render(<DecisionDashboard summary={summary} exceptions={exceptions} />);
    expect(screen.getByTestId('dd-card-definitions')).toHaveTextContent('42');
    expect(screen.getByTestId('dd-card-match-rate')).toHaveTextContent('75%'); // 150/200
    expect(screen.getByTestId('dd-card-failed')).toHaveTextContent('5'); // failed 3 + retrying 2
    expect(screen.getByTestId('dd-card-p95')).toHaveTextContent('87ms');
  });

  it('renders the exception queue', () => {
    render(<DecisionDashboard summary={summary} exceptions={exceptions} />);
    const item = screen.getByTestId('dd-exc-t-1');
    expect(item).toHaveAttribute('data-status', 'FAILED_RETRYING');
    expect(within(item).getByText('connector timeout')).toBeInTheDocument();
  });

  it('shows empty match rate when no evaluations + empty exceptions', () => {
    render(<DecisionDashboard summary={{ ...summary, evaluationsToday: 0, matched: 0 }} exceptions={[]} />);
    expect(screen.getByTestId('dd-card-match-rate')).toHaveTextContent('—');
    expect(screen.getByTestId('dd-exceptions-empty')).toBeInTheDocument();
  });
});
