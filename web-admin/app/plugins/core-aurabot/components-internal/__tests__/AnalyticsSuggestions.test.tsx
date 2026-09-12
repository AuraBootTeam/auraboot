import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AnalyticsSuggestions } from '../AnalyticsSuggestions';

const fixture = vi.hoisted(() => ({
  execute: true,
  sourceRead: true,
  loading: false,
  row: {
    pid: 'version',
    title: 'Saved suggestion',
    content: 'Advice',
    version: 1,
    groupKey: 'group',
    origin: 'human_authored',
    adoptionPid: 'adoption' as string | null,
    executionGoal: 'Review the order' as string | null,
    execution: null as { state: string; attempts: number } | null,
  },
}));
vi.mock('~/contexts/AuthContext', () => ({
  usePermission: (code: string) =>
    code === 'analytics.suggestion.read' ||
    (fixture.sourceRead &&
      ['model.core_dashboard_suggestion.read', 'model.core_dashboard_adoption.read'].includes(
        code,
      )) ||
    (code === 'analytics.suggestion.execute' && fixture.execute),
}));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('../../components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { isLoading: fixture.loading }, sendMessage: vi.fn() }),
}));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async () => ({
    code: '0',
    data: { records: [fixture.row], total: 1, page: 1, pageSize: 5 },
  })),
}));
const show = async () => {
  render(
    <AnalyticsSuggestions
      analysisId="analysis"
      query={{ type: 'aggregate', modelCode: 'orders' }}
    />,
  );
  await screen.findByText('Saved suggestion');
};
beforeEach(() => {
  fixture.execute = true;
  fixture.sourceRead = true;
  fixture.loading = false;
  fixture.row.adoptionPid = 'adoption';
  fixture.row.executionGoal = 'Review the order';
  fixture.row.execution = null;
});
describe('Analytics suggestion execution eligibility', () => {
  it.each(['success', 'failed', 'pending', 'queued', 'running', 'cancelled', 'unknown'])(
    'shows persisted %s without another first-run action',
    async (state) => {
      fixture.row.execution = { state, attempts: 1 };
      await show();
      expect(screen.getByTestId('analytics-execution-status')).toHaveTextContent('运行次数: 1');
      expect(screen.queryByRole('button', { name: '发起执行' })).not.toBeInTheDocument();
    },
  );
  it('allows a created task with no run to re-enter admission', async () => {
    fixture.row.execution = { state: 'not_started', attempts: 0 };
    await show();
    expect(screen.getByRole('button', { name: '发起执行' })).toBeEnabled();
  });
  it('requires source-record read permissions even with execution permission', async () => {
    fixture.sourceRead = false;
    await show();
    expect(screen.queryByRole('button', { name: '发起执行' })).not.toBeInTheDocument();
  });
  it('hides execution without the dedicated permission', async () => {
    fixture.execute = false;
    await show();
    expect(screen.queryByRole('button', { name: '发起执行' })).not.toBeInTheDocument();
  });
  it('hides execution before adoption', async () => {
    fixture.row.adoptionPid = null;
    await show();
    expect(screen.queryByRole('button', { name: '发起执行' })).not.toBeInTheDocument();
  });
  it('keeps text-only suggestions without an execution action', async () => {
    fixture.row.executionGoal = null;
    await show();
    expect(screen.queryByRole('button', { name: '发起执行' })).not.toBeInTheDocument();
  });
  it('disables launch while the conversation is running', async () => {
    fixture.loading = true;
    await show();
    expect(screen.getByRole('button', { name: '发起执行' })).toBeDisabled();
  });
});
