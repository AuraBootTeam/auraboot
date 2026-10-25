import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PagedChoiceField from '../PagedChoiceField';

const state = vi.hoisted(() => ({ fetchResult: vi.fn(), user: { tenantId: 1, pid: 'publisher' } }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: state.fetchResult }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ user: state.user }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US', t: (key: string) => key }) }));
const response = (records: Array<{value: string; label: string}>, total = records.length) =>
  ({ code: '0', data: { records, total, pageNum: 1, pageSize: 25 } });
const alpha = { value: 'person-a', label: 'Alpha member' };
const beta = { value: 'person-b', label: 'Beta member' };

describe('bounded authorized choice field', () => {
  beforeEach(() => { vi.clearAllMocks(); state.user = { tenantId: 1, pid: 'publisher' }; });

  it('pages and searches on the server while retaining selected labels outside the current page', async () => {
    state.fetchResult.mockImplementation(async (_endpoint: string, { params }: any) => {
      if (params.values) return response([alpha]);
      if (params.query === 'Beta' || params.pageNum === 2) return response([beta], 26);
      return response([alpha], 26);
    });
    const change = vi.fn();
    const view = render(<PagedChoiceField endpoint="/api/choices" value={[]} label="People" onChange={change} />);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: alpha.label })).toBeVisible());
    fireEvent.click(screen.getByRole('checkbox', { name: alpha.label })); expect(change).toHaveBeenCalledWith(['person-a']);
    view.rerender(<PagedChoiceField endpoint="/api/choices" value={['person-a']} label="People" onChange={change} />);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: alpha.label })).toBeChecked());
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(screen.getByRole('checkbox', { name: beta.label })).toBeVisible());
    expect(screen.getByRole('checkbox', { name: alpha.label })).toBeChecked();
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Beta' } });
    await waitFor(() => expect(state.fetchResult).toHaveBeenCalledWith('/api/choices', expect.objectContaining({ params: { query: 'Beta', pageNum: 1, pageSize: 25 } })));
    expect(screen.getByRole('checkbox', { name: alpha.label })).toBeChecked();
  });

  it('shows transport failure instead of a false empty state and retries the same bounded request', async () => {
    state.fetchResult.mockRejectedValueOnce(new Error('network')).mockResolvedValue(response([beta]));
    render(<PagedChoiceField endpoint="/api/choices" value={[]} label="People" onChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Unable to load authorized choices'));
    expect(screen.queryByText('No matching choices')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.getByRole('checkbox', { name: beta.label })).toBeVisible());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('discards delayed directory responses after the authenticated tenant changes', async () => {
    let release: (result: unknown) => void = () => {};
    state.fetchResult.mockReturnValueOnce(new Promise(resolve => { release = resolve; })).mockResolvedValue(response([beta]));
    const view = render(<PagedChoiceField endpoint="/api/choices" value={[]} label="People" onChange={vi.fn()} />);
    state.user = { tenantId: 2, pid: 'publisher' };
    view.rerender(<PagedChoiceField endpoint="/api/choices" value={[]} label="People" onChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: beta.label })).toBeVisible());
    await act(async () => { release(response([alpha])); });
    expect(screen.queryByRole('checkbox', { name: alpha.label })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: beta.label })).toBeVisible();
  });

  it('hydrates selections in batches of at most fifty and never displays unauthorized identifiers', async () => {
    const values = Array.from({ length: 51 }, (_, index) => `private-member-${index}`);
    state.fetchResult.mockResolvedValue(response([]));
    render(<PagedChoiceField endpoint="/api/choices" value={values} label="People" onChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('no longer available'));
    const batches = state.fetchResult.mock.calls.map(([, request]) => request.params.values).filter(Boolean);
    expect(batches.map((batch: string) => batch.split(',').length)).toEqual([50, 1]);
    expect(screen.queryByText('private-member-0')).not.toBeInTheDocument();
  });
});
