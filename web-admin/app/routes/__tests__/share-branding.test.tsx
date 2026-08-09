import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useParams } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import SharedViewPage from '../share.$token';

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => ({
    branding: {
      productName: 'AuraBoot',
      poweredByText: 'Powered by AuraBoot',
    },
  }),
}));

describe('SharedViewPage branding', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders public attribution from the root branding contract', async () => {
    vi.mocked(useParams).mockReturnValue({ token: 'public-token' });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        data: {
          viewName: 'Public orders',
          modelCode: 'orders',
          viewType: 'table',
          columns: [{ code: 'name', label: 'Name' }],
          records: [{ pid: '1', name: 'Order 1' }],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <MemoryRouter initialEntries={['/share/public-token']}>
        <SharedViewPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/views/shared/public-token'));
    expect(await screen.findByText('Powered by AuraBoot · 1 records')).toBeVisible();
  });
});
