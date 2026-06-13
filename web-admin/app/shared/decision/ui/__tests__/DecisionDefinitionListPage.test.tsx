import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

  it('opens an API-backed impact graph from a decision row', async () => {
    const api = {
      listDefinitions: vi.fn(async () => [
        { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: true },
      ]),
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'sla_deadline',
        incoming: [
          { sourceType: 'AUTOMATION', sourceCode: 'auto-high-priority', sourceName: 'High Priority Automation' },
          { sourceType: 'SLA_RULE', sourceCode: 'sla-high-priority', sourceName: 'High Priority SLA' },
        ],
        outgoing: [
          { targetType: 'FIELD', targetPath: 'record.data.priority' },
        ],
        risk: {
          blocking: true,
          summary: 'Used by 1 automation + 1 SLA rule',
          counts: { AUTOMATION: 1, SLA_RULE: 1 },
        },
      })),
    } as unknown as DecisionApi;

    renderWithClient(<DecisionDefinitionListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('ddl-row-sla_deadline')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('ddl-impact-sla_deadline'));

    await waitFor(() => expect(api.getDecisionImpact).toHaveBeenCalledWith('sla_deadline'));
    await waitFor(() => expect(screen.getByTestId('impact-graph-panel')).toBeInTheDocument());
    expect(screen.getByTestId('impact-graph-panel')).toHaveTextContent('High Priority Automation');
    expect(screen.getByTestId('impact-graph-panel')).toHaveTextContent('record.data.priority');
    expect(screen.getByTestId('impact-blast-radius')).toHaveTextContent('Used by 1 automation + 1 SLA rule');
  });

  it('requires impact acknowledgement before publishing a referenced validated version', async () => {
    const api = {
      listDefinitions: vi.fn(async () => [
        { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: true },
      ]),
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'sla_deadline',
        incoming: [
          { sourceType: 'AUTOMATION', sourceCode: 'auto-high-priority', sourceName: 'High Priority Automation' },
        ],
        outgoing: [],
        risk: {
          blocking: true,
          summary: 'Used by 1 automation',
          counts: { AUTOMATION: 1 },
        },
      })),
      listVersions: vi.fn(async () => [
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'VALIDATED' },
      ]),
      publishVersion: vi.fn(async () => ({ status: 'PUBLISHED', version: 2 })),
    } as unknown as DecisionApi;

    renderWithClient(<DecisionDefinitionListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('ddl-row-sla_deadline')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('ddl-impact-sla_deadline'));

    await waitFor(() => expect(screen.getByTestId('ddl-publish-version-pid-2')).toBeDisabled());
    fireEvent.click(screen.getByTestId('ddl-impact-ack'));
    fireEvent.click(screen.getByTestId('ddl-publish-version-pid-2'));

    await waitFor(() => expect(api.publishVersion).toHaveBeenCalledWith('version-pid-2', {
      impactAcknowledged: true,
    }));
    await waitFor(() => expect(screen.getByTestId('ddl-publish-message')).toHaveTextContent('发布成功'));
  });

  it('requires impact acknowledgement before deprecating or retiring referenced versions', async () => {
    const api = {
      listDefinitions: vi.fn(async () => [
        { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: true },
      ]),
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'sla_deadline',
        incoming: [
          { sourceType: 'SLA_RULE', sourceCode: 'sla-high-priority', sourceName: 'High Priority SLA' },
        ],
        outgoing: [],
        risk: {
          blocking: true,
          summary: 'Used by 1 SLA rule',
          counts: { SLA_RULE: 1 },
        },
      })),
      listVersions: vi.fn(async () => [
        { pid: 'version-pid-3', decisionCode: 'sla_deadline', version: 3, status: 'PUBLISHED' },
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'DEPRECATED' },
      ]),
      deprecateVersion: vi.fn(async () => ({ status: 'DEPRECATED', version: 3 })),
      retireVersion: vi.fn(async () => ({ status: 'RETIRED', version: 2 })),
    } as unknown as DecisionApi;

    renderWithClient(<DecisionDefinitionListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('ddl-row-sla_deadline')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('ddl-impact-sla_deadline'));

    await waitFor(() => expect(screen.getByTestId('ddl-deprecate-version-pid-3')).toBeDisabled());
    expect(screen.getByTestId('ddl-retire-version-pid-2')).toBeDisabled();

    fireEvent.click(screen.getByTestId('ddl-impact-ack'));
    fireEvent.click(screen.getByTestId('ddl-deprecate-version-pid-3'));
    fireEvent.click(screen.getByTestId('ddl-retire-version-pid-2'));

    await waitFor(() => expect(api.deprecateVersion).toHaveBeenCalledWith('version-pid-3', {
      impactAcknowledged: true,
      note: 'DecisionOps impact acknowledged in F5 drawer',
    }));
    await waitFor(() => expect(api.retireVersion).toHaveBeenCalledWith('version-pid-2', {
      impactAcknowledged: true,
      note: 'DecisionOps impact acknowledged in F5 drawer',
    }));
    await waitFor(() => expect(screen.getByTestId('ddl-publish-message')).toHaveTextContent('退役成功'));
  });

  it('shows a draft delete action in the lifecycle drawer', async () => {
    const api = {
      listDefinitions: vi.fn(async () => [
        { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: true },
      ]),
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'sla_deadline',
        incoming: [],
        outgoing: [],
        risk: {
          blocking: false,
          summary: 'No downstream consumers',
          counts: {},
        },
      })),
      listVersions: vi.fn(async () => [
        { pid: 'version-pid-draft', decisionCode: 'sla_deadline', version: 4, status: 'DRAFT' },
      ]),
      deleteVersion: vi.fn(async () => ({ status: 'DRAFT', version: 4 })),
    } as unknown as DecisionApi;

    renderWithClient(<DecisionDefinitionListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('ddl-row-sla_deadline')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('ddl-impact-sla_deadline'));

    await waitFor(() => expect(screen.getByTestId('ddl-delete-version-pid-draft')).toBeEnabled());
    fireEvent.click(screen.getByTestId('ddl-delete-version-pid-draft'));

    await waitFor(() => expect(api.deleteVersion).toHaveBeenCalledWith('version-pid-draft'));
    await waitFor(() => expect(screen.getByTestId('ddl-publish-message')).toHaveTextContent('删除草稿成功'));
  });

  it('links bindable version rows to the DSL rollout monitor with baseline and candidate versions', async () => {
    const api = {
      listDefinitions: vi.fn(async () => [
        { decisionCode: 'sla_deadline', decisionName: 'SLA Deadline', scopeType: 'SLA', enabled: true },
      ]),
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'sla_deadline',
        incoming: [],
        outgoing: [],
        risk: {
          blocking: false,
          summary: 'No downstream consumers',
          counts: {},
        },
      })),
      listVersions: vi.fn(async () => [
        { pid: 'version-pid-1', decisionCode: 'sla_deadline', version: 1, status: 'PUBLISHED' },
        { pid: 'version-pid-2', decisionCode: 'sla_deadline', version: 2, status: 'PUBLISHED' },
      ]),
      deprecateVersion: vi.fn(async () => ({ status: 'DEPRECATED', version: 2 })),
    } as unknown as DecisionApi;

    renderWithClient(<DecisionDefinitionListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('ddl-row-sla_deadline')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('ddl-impact-sla_deadline'));

    const startRollout = await screen.findByTestId('ddl-start-rollout-version-pid-2');
    expect(startRollout).toHaveAttribute(
      'href',
      '/p/decisionops_rollouts?decisionCode=sla_deadline&baselineVersion=1&candidateVersion=2',
    );
  });
});
