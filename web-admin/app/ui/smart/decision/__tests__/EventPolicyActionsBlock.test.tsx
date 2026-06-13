import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EventPolicyActionsBlock } from '../EventPolicyActionsBlock';

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
}));
const post = vi.fn();

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    useNavigate: () => routerMocks.navigate,
  };
});

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get: vi.fn(),
    post,
    delete: vi.fn(),
  }),
}));

describe('EventPolicyActionsBlock', () => {
  beforeEach(() => {
    routerMocks.navigate.mockReset();
    post.mockReset();
    post.mockImplementation((endpoint: string, body?: Record<string, unknown>) => {
      if (endpoint === '/event-policy/definitions') {
        return Promise.resolve({
          data: {
            policyCode: body?.policyCode,
            policyName: body?.policyName,
            enabled: true,
          },
        });
      }
      if (endpoint.endsWith('/copy')) {
        return Promise.resolve({
          data: {
            policyCode: body?.policyCode,
            policyName: body?.policyName,
            enabled: true,
          },
        });
      }
      if (endpoint.endsWith('/enabled')) {
        return Promise.resolve({
          data: {
            policyCode: 'complaint_policy',
            enabled: body?.enabled,
          },
        });
      }
      return Promise.resolve({ data: {} });
    });
  });

  it('creates a policy from a DSL list custom block and navigates to detail', async () => {
    render(
      <MemoryRouter>
        <EventPolicyActionsBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByTestId('epa-new-policy'));
    fireEvent.change(screen.getByLabelText('policy-code'), { target: { value: 'complaint_policy' } });
    fireEvent.change(screen.getByLabelText('policy-name'), { target: { value: 'Complaint Policy' } });
    fireEvent.change(screen.getByLabelText('policy-event-type'), { target: { value: 'FORM_SUBMITTED' } });
    fireEvent.change(screen.getByLabelText('policy-target-type'), { target: { value: 'FORM' } });
    fireEvent.change(screen.getByLabelText('policy-target-key'), { target: { value: 'complaint' } });
    fireEvent.click(screen.getByTestId('epa-save-policy'));

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/event-policy/definitions', {
        policyCode: 'complaint_policy',
        policyName: 'Complaint Policy',
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey: 'complaint',
      }),
    );
    expect(routerMocks.navigate).toHaveBeenCalledWith('/p/decisionops_event_policies/view/complaint_policy');
  });

  it('toggles, copies, and opens the DSL designer from a detail custom block', async () => {
    render(
      <MemoryRouter>
        <EventPolicyActionsBlock
          block={{ props: { mode: 'detail' } }}
          runtime={{
            getContext: () => ({
              record: {
                policyCode: 'complaint_policy',
                policyName: 'Complaint Policy',
                enabled: true,
              },
            }),
          }}
        />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByTestId('epa-toggle-enabled'));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/event-policy/definitions/complaint_policy/enabled', {
        enabled: false,
      }),
    );

    fireEvent.click(screen.getByTestId('epa-copy-policy'));
    expect(screen.getByLabelText('policy-code')).toHaveValue('complaint_policy_copy');
    fireEvent.click(screen.getByTestId('epa-save-policy'));
    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/event-policy/definitions/complaint_policy/copy', {
        policyCode: 'complaint_policy_copy',
        policyName: 'Complaint Policy Copy',
      }),
    );

    fireEvent.click(screen.getByTestId('epa-open-designer'));
    expect(routerMocks.navigate).toHaveBeenCalledWith(
      '/p/decisionops_event_policy_designer?policyCode=complaint_policy',
    );
  });

  it('uses the detail action mode when navigation lands on a detail URL after create', () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_event_policies/view/complaint_policy']}>
        <EventPolicyActionsBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    expect(screen.queryByTestId('epa-new-policy')).not.toBeInTheDocument();
    expect(screen.getByTestId('epa-open-designer')).toBeInTheDocument();
    expect(screen.getByTestId('epa-copy-policy')).toBeInTheDocument();
    expect(screen.getByTestId('epa-open-logs')).toBeInTheDocument();
  });
});
