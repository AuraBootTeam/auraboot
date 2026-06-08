import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { DecisionDefinitionListPage } from '../DecisionDefinitionListPage';
import type { DecisionApi } from '../../api/decisionApi';

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function apiWith(listImpl: () => Promise<unknown>): DecisionApi {
  return { listDefinitions: vi.fn(listImpl) } as unknown as DecisionApi;
}

describe('DecisionDefinitionListPage', () => {
  it('renders definition rows from the API', async () => {
    const api = apiWith(async () => [
      { decisionCode: 'big_amount', decisionName: 'Big Amount', scopeType: 'AUTOMATION', ownerModule: 'decision', enabled: true },
      { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: false },
    ]);
    renderWithClient(<DecisionDefinitionListPage api={api} />);
    await waitFor(() => expect(screen.getByTestId('decision-definition-list')).toBeInTheDocument());
    expect(screen.getByTestId('ddl-row-big_amount')).toHaveTextContent('Big Amount');
    expect(screen.getByTestId('ddl-row-sla_deadline')).toHaveTextContent('停用');
  });

  it('tolerates a paged {records:[]} response shape', async () => {
    const api = apiWith(async () => ({ records: [{ decisionCode: 'x', decisionName: 'X' }], total: 1 }));
    renderWithClient(<DecisionDefinitionListPage api={api} />);
    await waitFor(() => expect(screen.getByTestId('ddl-row-x')).toBeInTheDocument());
  });

  it('shows empty state for an empty list', async () => {
    const api = apiWith(async () => []);
    renderWithClient(<DecisionDefinitionListPage api={api} />);
    await waitFor(() => expect(screen.getByTestId('ddl-empty')).toBeInTheDocument());
  });

  it('shows error state when the API rejects', async () => {
    const api = apiWith(async () => { throw new Error('boom'); });
    renderWithClient(<DecisionDefinitionListPage api={api} />);
    await waitFor(() => expect(screen.getByTestId('ddl-error')).toBeInTheDocument());
  });
});
