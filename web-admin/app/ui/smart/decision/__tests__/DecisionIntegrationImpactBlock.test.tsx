import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DecisionIntegrationImpactBlock } from '../DecisionIntegrationImpactBlock';

const get = vi.fn();

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get,
    post: vi.fn(),
    delete: vi.fn(),
  }),
}));

describe('DecisionIntegrationImpactBlock', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockImplementation((endpoint: string, query?: Record<string, unknown>) => {
      if (endpoint === '/decision/integrations/impact') {
        return Promise.resolve({
          data: {
            targetType: query?.targetType ?? 'WEBHOOK',
            targetCode: query?.targetCode ?? 'case.closed',
            manageUrl: '/p/webhook',
            references: [],
            risk: {
              blocking: false,
              summary: 'No integration consumers',
              counts: {},
            },
          },
        });
      }
      return Promise.resolve({ data: {} });
    });
  });

  it('uses configured record field as webhook target code', async () => {
    render(
      <MemoryRouter>
        <DecisionIntegrationImpactBlock
          block={{
            props: {
              targetType: 'WEBHOOK',
              targetCodeField: 'event_type',
            },
          }}
          runtime={{
            getContext: () => ({
              record: {
                pid: 'webhook-pid',
                event_type: 'case.closed',
              },
            }),
          }}
        />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/integrations/impact', {
        targetType: 'WEBHOOK',
        targetCode: 'case.closed',
      }),
    );
    expect(screen.getByTestId('integration-impact-manage')).toHaveAttribute('href', '/p/webhook');
  });

  it('loads the detail record before falling back to pid when target code field is absent from runtime context', async () => {
    get.mockImplementation((endpoint: string, query?: Record<string, unknown>) => {
      if (endpoint === '/dynamic/webhook/webhook-pid') {
        return Promise.resolve({
          data: {
            pid: 'webhook-pid',
            event_type: 'case.closed',
          },
        });
      }
      if (endpoint === '/decision/integrations/impact') {
        return Promise.resolve({
          data: {
            targetType: query?.targetType ?? 'WEBHOOK',
            targetCode: query?.targetCode ?? 'case.closed',
            manageUrl: '/p/webhook',
            references: [],
            risk: {
              blocking: false,
              summary: 'No integration consumers',
              counts: {},
            },
          },
        });
      }
      return Promise.resolve({ data: {} });
    });

    render(
      <MemoryRouter initialEntries={['/p/decisionops_webhooks/view/webhook-pid']}>
        <Routes>
          <Route
            path="/p/decisionops_webhooks/view/:recordId"
            element={
              <DecisionIntegrationImpactBlock
                block={{
                  props: {
                    targetType: 'WEBHOOK',
                    targetCodeField: 'event_type',
                  },
                }}
                runtime={{
                  getContext: () => ({
                    record: {
                      pid: 'webhook-pid',
                    },
                    $page: {
                      modelCode: 'webhook',
                    },
                  }),
                }}
              />
            }
          />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(get).toHaveBeenCalledWith('/dynamic/webhook/webhook-pid'));
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/integrations/impact', {
        targetType: 'WEBHOOK',
        targetCode: 'case.closed',
      }),
    );
    expect(get).not.toHaveBeenCalledWith('/decision/integrations/impact', {
      targetType: 'WEBHOOK',
      targetCode: 'webhook-pid',
    });
  });
});
