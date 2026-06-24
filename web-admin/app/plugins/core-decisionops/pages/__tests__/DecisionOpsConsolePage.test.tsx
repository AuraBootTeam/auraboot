import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import DecisionOpsConsolePage from '../DecisionOpsConsolePage';
import { createDecisionApi } from '~/shared/decision/api/decisionApi';
import { getApiService } from '~/shared/services/ApiService';

vi.mock('~/shared/decision/ui/DecisionOpsConsole', () => ({
  DecisionOpsConsole: ({ api, fields, initialTab }: any) => (
    <div data-testid="decisionops-console-preview">
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

describe('DecisionOpsConsolePage', () => {
  it('renders the integrated console preview instead of redirecting to a DSL page', () => {
    render(<DecisionOpsConsolePage />);

    expect(screen.getByTestId('decisionops-console-preview')).toHaveTextContent(
      'dashboard:3:api-ready',
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
});
