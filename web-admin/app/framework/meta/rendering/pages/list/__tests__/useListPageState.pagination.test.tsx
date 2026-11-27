import React, { useRef } from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { useListPageState } from '../useListPageState';

vi.unmock('react-router');

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { pid: 'buyer', tenantId: 1 }, hasPermission: () => true }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));
const props = {
  tableName: 'owned_sources',
  schema: { pageKey: 'owned_sources', kind: 'list', blocks: [] },
} as any;
const late = { loadData: vi.fn(async () => {}) };
function Probe() {
  // Fail a URL feedback loop before it can exhaust the test worker.
  const renders = useRef(0);
  if (++renders.current > 30) throw new Error('Pagination URL feedback did not settle');
  const state = useListPageState(props, late);
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output data-testid="page">{state.pagination.current}</output>
      <output data-testid="url">{location.search}</output>
      <button onClick={() => navigate('?filter_task=owned&pageNum=3&pageSize=20')}>
        Open page three
      </button>
      <button onClick={() => state.setPagination((previous: any) => ({ ...previous, current: 4 }))}>
        Next page
      </button>
      <button onClick={() => navigate(-1)}>Back</button>
    </>
  );
}
describe('list URL pagination ownership', () => {
  it('hydrates an explicit page without replacing it with the default page', async () => {
    render(
      <MemoryRouter initialEntries={['/?filter_task=owned&pageNum=2&pageSize=20']}>
        <Probe />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByTestId('page')).toHaveTextContent('2'));
    expect(screen.getByTestId('url')).toHaveTextContent('pageNum=2');
  });
  it('keeps an incoming URL page authoritative and permits the next local page change', async () => {
    render(
      <MemoryRouter initialEntries={['/?filter_task=owned&pageNum=2&pageSize=20']}>
        <Probe />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Open page three' }));
    await waitFor(() => {
      expect(screen.getByTestId('page')).toHaveTextContent('3');
      expect(screen.getByTestId('url')).toHaveTextContent('pageNum=3');
    });
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      expect(screen.getByTestId('page')).toHaveTextContent('4');
      expect(screen.getByTestId('url')).toHaveTextContent('pageNum=4');
      expect(screen.getByTestId('url')).toHaveTextContent('filter_task=owned');
    });
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    await waitFor(() => {
      expect(screen.getByTestId('page')).toHaveTextContent('2');
      expect(screen.getByTestId('url')).toHaveTextContent('pageNum=2');
    });
  });
});
