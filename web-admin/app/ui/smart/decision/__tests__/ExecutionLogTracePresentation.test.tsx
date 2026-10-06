import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExecutionLogTraceBlock } from '../ExecutionLogTraceBlock';
import TEXT from '~/shared/decision/ui/tracePresentation.i18n.json';

const state = vi.hoisted(() => ({ locale: 'en-US', get: vi.fn(), post: vi.fn(), delete: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: state.locale, t: (key: string, _params?: unknown, fallback?: string) => fallback ?? key }),
}));
vi.mock('~/shared/services/ApiService', () => ({ getApiService: () => state }));
vi.mock('~/framework/bootstrap', () => ({
  getKernel: () => ({ contributionRegistry: { getPrimaryService: () => undefined } }),
}));

const log = {
  pid: 'owned-log', traceId: 'owned-trace', decisionCode: 'permission_model_replay',
  status: 'NOT_MATCHED', matched: false, runtimeAdapter: 'AST_EVALUATOR',
  outputSnapshot: { matched: false, truth: 'FALSE', decisionCode: 'permission_model_replay' },
};

describe('ExecutionLogTraceBlock result presentation', () => {
  beforeEach(() => {
    state.get.mockReset();
    state.get.mockImplementation((endpoint: string) => Promise.resolve({
      data: endpoint === '/decision/logs/recent' ? { records: [log], total: 1 } : [log],
    }));
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() });
  });

  for (const locale of ['en-US', 'zh-CN']) {
    it(locale + ' renders real component outputs as negative results and retains exact diagnostic codes', async () => {
      state.locale = locale;
      render(<MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>);
      fireEvent.click(await screen.findByTestId('elta-open-trace-owned-log'));
      const output = await screen.findByTestId('elta-output-snapshot-owned-log');
      const language = locale === 'en-US' ? 'en' : 'zh-CN';
      expect(output).toHaveTextContent(TEXT.semantic.matchedFalse[language]);
      expect(output).toHaveTextContent(TEXT.semantic.truthFalse[language]);
      expect(output).toHaveTextContent(TEXT.semantic.decisionFallback[language]);
      expect(output).not.toHaveTextContent('FALSE');
      expect(output).not.toHaveTextContent('permission_model_replay');
      const diagnostics = screen.getByTestId('elta-chain-technical-owned-log');
      expect(diagnostics).not.toHaveAttribute('open');
      expect(diagnostics).toHaveTextContent('permission_model_replay');
      expect(diagnostics).toHaveTextContent('AST_EVALUATOR');
      const row = screen.getByTestId('elta-chain-node-owned-log');
      expect(row.querySelector('.elta-chain-sub')).toHaveTextContent(TEXT.adapter.AST_EVALUATOR[language]);
      expect(row.querySelector('.elta-chain-sub')).not.toHaveTextContent('AST_EVALUATOR');
      expect(state.get).toHaveBeenCalledWith('/decision/logs', { traceId: 'owned-trace' });
      expect(diagnostics.querySelector('summary')).toHaveTextContent(TEXT.semantic.technicalDetails[language]);
    });
  }
});
