import { fireEvent, render, screen } from '@testing-library/react';
vi.mock('react-router', () => ({ useParams: () => ({ recordPid: 'owned-subscription' }) }));
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WebhookDeliveryHistory } from '../WebhookDeliveryHistory';

const get = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/ApiService', () => ({ getApiService: () => ({ get }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
const show = () => render(<WebhookDeliveryHistory />);
beforeEach(() => get.mockReset());

describe('existing Webhook DSL delivery history', () => {
  it('reads the exact subscription API and uses shared messages inside its original detail context', async () => {
    get.mockResolvedValue({ success: true, data: [{ pid: 'delivery', deliveryStatus: 'success', responseStatus: 200,
      retryCount: 0, createdAt: '2026-11-24T12:00:00Z', requestBody: '{"order":"P001"}', responseBody: '{"accepted":true}' }] });
    show();
    const row = await screen.findByText('Delivered');
    fireEvent.click(row.closest('summary')!);
    expect(screen.getByTestId('http-message-details')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Request body' })).toHaveTextContent('P001');
    expect(screen.getByRole('region', { name: 'Response body' })).toHaveTextContent('"accepted": true');
    expect(get).toHaveBeenCalledWith('/webhooks/owned-subscription/deliveries', undefined, expect.any(AbortSignal));
  });

  it('keeps a failed authorized API response distinct from an empty delivery history', async () => {
    get.mockResolvedValue({ success: false, code: '403', data: null });
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to read delivery logs');
    expect(screen.queryByText('No delivery records')).not.toBeInTheDocument();
    expect(screen.queryByTestId('http-message-details')).not.toBeInTheDocument();
    get.mockResolvedValueOnce({ success: true, data: [] });
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No delivery records')).toBeVisible();
    expect(get).toHaveBeenCalledTimes(2);
  });
});
