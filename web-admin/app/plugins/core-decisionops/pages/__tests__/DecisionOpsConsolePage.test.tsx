import { cleanup, render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import DecisionOpsConsolePage from '../DecisionOpsConsolePage';
import { createDecisionApi } from '~/shared/decision/api/decisionApi';
import { getApiService } from '~/shared/services/ApiService';

vi.mock('~/shared/decision/ui/DecisionOpsConsole', () => ({
  DecisionOpsConsole: ({ api, fields, initialTab }: any) => (
    <div data-testid="decisionops-console">
      {initialTab}:{fields.length}:{api === fakeDecisionApi ? 'api-ready' : 'api-missing'}
    </div>
  ),
}));

const fakeDecisionApi = { getDashboard: vi.fn() };
const fakeService = {
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
};

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: vi.fn(() => fakeService),
}));

vi.mock('~/shared/decision/api/decisionApi', () => ({
  createDecisionApi: vi.fn(() => fakeDecisionApi),
}));

afterEach(() => {
  cleanup();
  window.history.pushState({}, '', '/');
});

describe('DecisionOpsConsolePage', () => {
  it('renders the Strategy Studio product entry instead of redirecting to a DSL page', () => {
    window.history.pushState({}, '', '/decision-ops');
    render(
      <BrowserRouter>
        <DecisionOpsConsolePage />
      </BrowserRouter>,
    );

    expect(screen.getByTestId('decisionops-console')).toHaveTextContent(
      'studio:3:api-ready',
    );
    expect(getApiService).toHaveBeenCalled();
    expect(createDecisionApi).toHaveBeenCalledWith(
      expect.objectContaining({
        get: expect.any(Function),
        post: expect.any(Function),
        delete: expect.any(Function),
      }),
    );
  });

  it('uses the tab query param as a no-JS fallback for deep linked tabs', () => {
    window.history.pushState({}, '', '/decision-ops?tab=logs');
    render(
      <BrowserRouter>
        <DecisionOpsConsolePage />
      </BrowserRouter>,
    );

    expect(screen.getByTestId('decisionops-console')).toHaveTextContent('logs:3:api-ready');
  });
});
