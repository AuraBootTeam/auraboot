import { render, screen, waitFor } from '@testing-library/react';
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
});
