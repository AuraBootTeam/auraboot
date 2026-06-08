import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { DecisionOpsConsole } from '../DecisionOpsConsole';
import { type FieldOption } from '../ConditionBuilder';
import type { DecisionApi } from '../../api/decisionApi';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
];

function api(): DecisionApi {
  return {
    listDefinitions: vi.fn(async () => [{ decisionCode: 'big', decisionName: 'Big', enabled: true }]),
    validate: vi.fn(async () => ({ valid: true })),
  } as unknown as DecisionApi;
}

function renderConsole(initialTab?: Parameters<typeof DecisionOpsConsole>[0]['initialTab']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <DecisionOpsConsole
        api={api()} fields={FIELDS} initialTab={initialTab}
        modelFields={[{ entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 3 }]}
        logs={[{ traceId: 't1', policyCode: 'p1', status: 'SUCCESS' }]}
        connectors={[{ code: 'c1', name: 'Hook', type: 'WEBHOOK', health: 'HEALTHY', enabled: true }]}
        permissionGrants={[{ role: '管理员', caps: { view: true, publish: true } }]}
        dashboard={{ summary: { definitions: 5, policies: 2, evaluationsToday: 10, matched: 8, failed: 0, retrying: 0 }, exceptions: [] }}
      />
    </QueryClientProvider>,
  );
}

describe('DecisionOpsConsole', () => {
  it('renders the tab bar + the default dashboard tab', () => {
    renderConsole();
    expect(screen.getByTestId('doc-tab-definitions')).toBeInTheDocument();
    expect(screen.getByTestId('decision-dashboard')).toBeInTheDocument();
    expect(screen.getByTestId('dd-card-definitions')).toHaveTextContent('5');
  });

  it('switches to Definitions (F5, self-fetching) tab', async () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-definitions'));
    await waitFor(() => expect(screen.getByTestId('ddl-row-big')).toBeInTheDocument());
  });

  it('switches to Designer (F3) tab and shows the condition builder', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-designer'));
    expect(screen.getByTestId('condition-designer')).toBeInTheDocument();
  });

  it('switches to Logs / Model / Permissions / Connectors tabs', () => {
    renderConsole();
    fireEvent.click(screen.getByTestId('doc-tab-logs'));
    expect(screen.getByTestId('exec-log-viewer')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-model'));
    expect(screen.getByTestId('data-model-viewer')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-permissions'));
    expect(screen.getByTestId('permission-matrix')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('doc-tab-connectors'));
    expect(screen.getByTestId('connector-list')).toBeInTheDocument();
  });

  it('honors initialTab', () => {
    renderConsole('connectors');
    expect(screen.getByTestId('connector-list')).toBeInTheDocument();
  });
});
