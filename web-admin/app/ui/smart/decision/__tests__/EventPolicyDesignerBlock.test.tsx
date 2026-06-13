import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EventPolicyDesignerBlock } from '../EventPolicyDesignerBlock';

const get = vi.fn();

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get,
    post: vi.fn(),
    delete: vi.fn(),
  }),
}));

describe('EventPolicyDesignerBlock', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockImplementation((endpoint: string) => {
      if (endpoint === '/event-policy/definitions') {
        return Promise.resolve({
          data: [
            {
              policyCode: 'complaint_policy',
              policyName: 'Complaint Policy',
              eventType: 'FORM_SUBMITTED',
              targetType: 'FORM',
              targetKey: 'complaint',
              status: 'DRAFT',
              latestVersionPid: 'policy-version-pid',
            },
          ],
        });
      }
      if (endpoint === '/event-policy/definitions/complaint_policy/versions') {
        return Promise.resolve({ data: [] });
      }
      if (endpoint === '/decision/model/fields') {
        return Promise.resolve({
          data: [
            {
              entityCode: 'record',
              path: 'data.customerLevel',
              label: '客户等级',
              dataType: 'enum',
            },
          ],
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('loads the selected policy from URL policyCode and renders the workflow', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'complaint_policy' } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/event-policy/definitions', {
        keyword: 'complaint_policy',
      }),
    );
    await screen.findByTestId('epd-workflow');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('Complaint Policy');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('FORM_SUBMITTED');
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/event-policy/definitions/complaint_policy/versions', undefined),
    );
  });

  it('loads model fields into the rule field picker with default event fields as fallback', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'complaint_policy' } }} />
      </MemoryRouter>,
    );

    await waitFor(() => expect(get).toHaveBeenCalledWith('/decision/model/fields', undefined));
    await screen.findByTestId('epd-workflow');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.click(screen.getByTestId('cb-add'));

    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker).toHaveTextContent('客户等级');
    expect(fieldPicker).toHaveTextContent('优先级');
    expect(fieldPicker).toHaveTextContent('金额');
  });
});
