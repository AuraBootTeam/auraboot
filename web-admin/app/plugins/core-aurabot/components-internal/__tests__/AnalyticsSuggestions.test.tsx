import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AnalyticsSuggestions } from '../AnalyticsSuggestions';

const fixture = vi.hoisted(() => ({
  execute: true,
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
  },
}));
vi.mock('~/contexts/AuthContext', () => ({
  usePermission: (code: string) =>
    code === 'analytics.suggestion.read' ||
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
  fixture.loading = false;
  fixture.row.adoptionPid = 'adoption';
  fixture.row.executionGoal = 'Review the order';
});
describe('Analytics suggestion execution eligibility', () => {
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
