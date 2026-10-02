import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import I18nResourcesPage from '../index';

const mocks = vi.hoisted(() => ({ request: vi.fn(), confirm: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: mocks.request }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US' }) }));
vi.mock('~/utils/confirmDialog', () => ({ confirmDialog: mocks.confirm }));
const resource = { pid: 'p1', i18nKey: 'test.review.key', lang: 'en-US', value: 'Original', source: 'import', refType: 'model', status: 'review' };

describe('i18n resources workflow feedback', () => {
  let current: typeof resource;
  beforeEach(() => {
    vi.clearAllMocks();
    current = { ...resource };
    mocks.confirm.mockResolvedValue(false);
    mocks.request.mockImplementation(async (_path, options) => {
      if (options.method === 'get') return { code: '0', data: { records: [current], total: 1 } };
      return { code: '0', data: current };
    });
  });
  afterEach(cleanup);

  it('approves only review rows through the dedicated endpoint', async () => {
    mocks.request.mockImplementation(async (_path, options) => {
      if (options.method === 'post') current = { ...current, status: 'approved' };
      return { code: '0', data: options.method === 'get' ? { records: [current], total: 1 } : current };
    });
    render(<I18nResourcesPage />);
    expect(await screen.findByText('Pending Review')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('approve-test.review.key'));
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/admin/i18n/resources/p1/approve', { method: 'post' }));
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(screen.queryByLabelText('approve-test.review.key')).not.toBeInTheDocument();
  });

  it('draft rows offer submit rather than bypassing review', async () => {
    current.status = 'draft';
    render(<I18nResourcesPage />);
    fireEvent.click(await screen.findByLabelText('submit-review-test.review.key'));
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/admin/i18n/resources/p1/submit-review', { method: 'post' }));
    expect(screen.queryByLabelText('approve-test.review.key')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('reject-test.review.key')).not.toBeInTheDocument();
  });

  it('requires rejection feedback and retains the dialog on business failure', async () => {
    mocks.request.mockImplementation(async (_path, options) => options.method === 'get'
      ? { code: '0', data: { records: [current], total: 1 } }
      : { code: '400', message: 'Review action denied' });
    render(<I18nResourcesPage />);
    fireEvent.click(await screen.findByLabelText('reject-test.review.key'));
    const reject = screen.getByRole('button', { name: 'Reject' });
    expect(reject).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Rejection reason (required)'), { target: { value: '   ' } });
    expect(reject).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Rejection reason (required)'), { target: { value: ' Please revise ' } });
    fireEvent.click(reject);
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/admin/i18n/resources/p1/reject', { method: 'post', params: { reason: 'Please revise' } }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Review action denied');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('Rejection reason (required)')).toHaveValue(' Please revise ');
  });

  it('editing a translation does not silently approve it', async () => {
    render(<I18nResourcesPage />);
    fireEvent.click(await screen.findByLabelText('edit-test.review.key'));
    fireEvent.change(screen.getByDisplayValue('Original'), { target: { value: 'Revised' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(mocks.request).toHaveBeenCalledWith('/api/admin/i18n/resources/p1', { method: 'put', params: { value: 'Revised' } }));
    expect(await screen.findByText('Pending Review')).toBeInTheDocument();
  });

  it('business failure preserves create input instead of reporting success', async () => {
    mocks.request.mockImplementation(async (_path, options) => options.method === 'get'
      ? { code: '0', data: { records: [], total: 0 } }
      : { code: '403', message: 'Management permission required' });
    render(<I18nResourcesPage />);
    fireEvent.change(screen.getByPlaceholderText('key', { exact: true }), { target: { value: 'new.key' } });
    fireEvent.change(screen.getByPlaceholderText('value', { exact: true }), { target: { value: 'New value' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('Management permission required')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('key', { exact: true })).toHaveValue('new.key');
    expect(screen.getByPlaceholderText('value', { exact: true })).toHaveValue('New value');
  });

  it('cancelled deletion does not mutate the resource', async () => {
    render(<I18nResourcesPage />);
    fireEvent.click(await screen.findByLabelText('delete-test.review.key'));
    await waitFor(() => expect(mocks.confirm).toHaveBeenCalled());
    expect(mocks.request.mock.calls.some(([, options]) => options.method === 'delete')).toBe(false);
  });
});
