import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { EventPolicyListPage } from '../EventPolicyListPage';
import type { DecisionApi } from '../../api/decisionApi';

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function apiWith(rows: unknown[]): DecisionApi {
  return {
    listPolicies: vi.fn(async () => rows),
  } as unknown as DecisionApi;
}

describe('EventPolicyListPage', () => {
  it('renders policy rows from the API', async () => {
    const api = apiWith([
      {
        policyCode: 'complaint_form_submit_policy',
        policyName: '投诉表单提交策略',
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey: 'complaint_form',
        phase: 'AFTER_COMMIT',
        matchMode: 'COLLECT_ALL',
        status: 'PUBLISHED',
        enabled: true,
        version: 4,
        owner: '林雨',
      },
    ]);

    renderWithClient(<EventPolicyListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('event-policy-list')).toBeInTheDocument());
    expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toHaveTextContent('投诉表单提交策略');
    expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toHaveTextContent('FORM_SUBMITTED');
    expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toHaveTextContent('COLLECT_ALL');
  });

  it('filters policies by keyword and status on the client', async () => {
    const api = apiWith([
      { policyCode: 'complaint_form_submit_policy', policyName: '投诉表单提交策略', eventType: 'FORM_SUBMITTED', status: 'PUBLISHED', enabled: true },
      { policyCode: 'change_request_submit_policy', policyName: '变更申请提交策略', eventType: 'FORM_SUBMITTED', status: 'DRAFT', enabled: false },
    ]);

    renderWithClient(<EventPolicyListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('event-policy-list')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('policy-search'), { target: { value: 'change' } });
    fireEvent.change(screen.getByLabelText('policy-status-filter'), { target: { value: 'DRAFT' } });

    expect(screen.queryByTestId('epl-row-complaint_form_submit_policy')).not.toBeInTheDocument();
    expect(screen.getByTestId('epl-row-change_request_submit_policy')).toBeInTheDocument();
    expect(screen.getByTestId('epl-count')).toHaveTextContent('1');
  });

  it('hands the selected policy to the designer callback', async () => {
    const api = apiWith([
      { policyCode: 'complaint_form_submit_policy', policyName: '投诉表单提交策略', eventType: 'FORM_SUBMITTED', status: 'PUBLISHED', enabled: true },
    ]);
    const onOpenDesigner = vi.fn();

    renderWithClient(<EventPolicyListPage api={api} onOpenDesigner={onOpenDesigner} />);

    await waitFor(() => expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epl-open-designer-complaint_form_submit_policy'));

    expect(onOpenDesigner).toHaveBeenCalledWith(expect.objectContaining({
      policyCode: 'complaint_form_submit_policy',
    }));
  });

  it('creates a new policy definition from the list toolbar', async () => {
    const api = {
      listPolicies: vi.fn(async () => []),
      createPolicyDefinition: vi.fn(async () => ({
        policyCode: 'complaint_policy',
        policyName: '投诉策略',
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey: 'complaint',
        enabled: true,
      })),
    } as unknown as DecisionApi;

    renderWithClient(<EventPolicyListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('event-policy-list')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epl-new-policy'));
    fireEvent.change(screen.getByLabelText('policy-code'), { target: { value: 'complaint_policy' } });
    fireEvent.change(screen.getByLabelText('policy-name'), { target: { value: '投诉策略' } });
    fireEvent.change(screen.getByLabelText('policy-event-type'), { target: { value: 'FORM_SUBMITTED' } });
    fireEvent.change(screen.getByLabelText('policy-target-type'), { target: { value: 'FORM' } });
    fireEvent.change(screen.getByLabelText('policy-target-key'), { target: { value: 'complaint' } });
    fireEvent.click(screen.getByTestId('epl-save-policy'));

    await waitFor(() => expect(api.createPolicyDefinition).toHaveBeenCalledWith({
      policyCode: 'complaint_policy',
      policyName: '投诉策略',
      eventType: 'FORM_SUBMITTED',
      targetType: 'FORM',
      targetKey: 'complaint',
    }));
  });

  it('toggles enabled state for a policy row', async () => {
    const api = {
      listPolicies: vi.fn(async () => [
        { policyCode: 'complaint_form_submit_policy', policyName: '投诉表单提交策略', eventType: 'FORM_SUBMITTED', status: 'PUBLISHED', enabled: true },
      ]),
      setPolicyEnabled: vi.fn(async () => ({ policyCode: 'complaint_form_submit_policy', enabled: false })),
    } as unknown as DecisionApi;

    renderWithClient(<EventPolicyListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epl-toggle-enabled-complaint_form_submit_policy'));

    await waitFor(() => expect(api.setPolicyEnabled).toHaveBeenCalledWith('complaint_form_submit_policy', false));
  });

  it('opens a copy form and submits a copied policy definition', async () => {
    const api = {
      listPolicies: vi.fn(async () => [
        { policyCode: 'complaint_form_submit_policy', policyName: '投诉表单提交策略', eventType: 'FORM_SUBMITTED', targetType: 'FORM', targetKey: 'complaint', status: 'PUBLISHED', enabled: true },
      ]),
      copyPolicyDefinition: vi.fn(async () => ({ policyCode: 'complaint_form_submit_policy_copy', enabled: true })),
    } as unknown as DecisionApi;

    renderWithClient(<EventPolicyListPage api={api} />);

    await waitFor(() => expect(screen.getByTestId('epl-row-complaint_form_submit_policy')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('epl-copy-complaint_form_submit_policy'));

    expect(screen.getByLabelText('policy-code')).toHaveValue('complaint_form_submit_policy_copy');
    expect(screen.getByLabelText('policy-name')).toHaveValue('投诉表单提交策略 Copy');

    fireEvent.click(screen.getByTestId('epl-save-policy'));

    await waitFor(() => expect(api.copyPolicyDefinition).toHaveBeenCalledWith('complaint_form_submit_policy', {
      policyCode: 'complaint_form_submit_policy_copy',
      policyName: '投诉表单提交策略 Copy',
    }));
  });
});
