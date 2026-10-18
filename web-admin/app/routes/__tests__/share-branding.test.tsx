import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useParams } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import SharedViewPage from '../share.$token';

const load = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/savedViewService', () => ({ loadPublicSharedView: load }));
vi.mock('~/root-data', () => ({
  useRootLoaderData: () => ({
    branding: { productName: 'AuraBoot', poweredByText: 'Powered by AuraBoot' },
  }),
}));
const payload = {
  code: '0',
  desc: 'OK',
  data: {
    name: 'Public orders',
    modelCode: 'orders',
    viewType: 'table',
    viewConfig: { columns: [{ fieldCode: 'name' }], filters: [], sorts: [] },
  },
};
function show() {
  return render(
    <I18nProvider initialLocale="en-US" initialData={{}}>
      <MemoryRouter initialEntries={['/share/public-token']}>
        <SharedViewPage />
      </MemoryRouter>
    </I18nProvider>,
  );
}
beforeEach(() => {
  vi.mocked(useParams).mockReturnValue({ token: 'public-token' });
  load.mockReset();
});
afterEach(cleanup);
describe('SharedViewPage actual producer contract', () => {
  it('renders root attribution and view settings without inventing records', async () => {
    load.mockResolvedValue(payload);
    show();
    expect(await screen.findByRole('heading', { name: 'Public orders' })).toBeVisible();
    expect(screen.getByText('Powered by AuraBoot')).toBeVisible();
    expect(
      screen.getByText(
        'This link shares view settings. Sign in to access business data according to your permissions.',
      ),
    ).toBeVisible();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByText(/records$/)).toBeNull();
    expect(load).toHaveBeenCalledWith('public-token', undefined, expect.any(AbortSignal));
  });
  it('uses the structured validation context for password challenge and preserves retry until accepted', async () => {
    const invalid = {
      code: '35000',
      httpStatus: 422,
      desc: 'Localized envelope message',
      context: { error: 'Invalid password' },
      data: null,
    };
    load
      .mockResolvedValueOnce(invalid)
      .mockResolvedValueOnce(invalid)
      .mockResolvedValueOnce(payload);
    show();
    const password = await screen.findByLabelText('Access password');
    fireEvent.change(password, { target: { value: 'wrong-fixture' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open shared view' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The password is incorrect');
    fireEvent.change(screen.getByLabelText('Access password'), {
      target: { value: 'correct-fixture' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open shared view' }));
    expect(await screen.findByRole('heading', { name: 'Public orders' })).toBeVisible();
    expect(load).toHaveBeenNthCalledWith(
      2,
      'public-token',
      'wrong-fixture',
      expect.any(AbortSignal),
    );
    expect(load).toHaveBeenNthCalledWith(
      3,
      'public-token',
      'correct-fixture',
      expect.any(AbortSignal),
    );
  });
  it.each([
    ['Share link has expired', 'This shared link has expired.'],
    ['Share link has been revoked', 'This shared link has been revoked.'],
  ])('renders %s without showing stale view content', async (error, expected) => {
    load.mockResolvedValue({
      code: '35000',
      httpStatus: 422,
      desc: 'Bad parameter',
      context: { error },
      data: null,
    });
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent(expected);
    expect(screen.queryByTestId('public-shared-view')).toBeNull();
  });
  it('aborts and ignores the old response when a new share identity arrives', async () => {
    let finish!: (value: typeof payload) => void;
    const previous = new Promise<typeof payload>((resolve) => {
      finish = resolve;
    });
    load
      .mockReturnValueOnce(previous)
      .mockResolvedValueOnce({ ...payload, data: { ...payload.data, name: 'Current identity' } });
    const page = show();
    await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
    const previousSignal = load.mock.calls[0][2] as AbortSignal;
    vi.mocked(useParams).mockReturnValue({ token: 'current-token' });
    page.rerender(
      <I18nProvider initialLocale="en-US" initialData={{}}>
        <MemoryRouter initialEntries={['/share/current-token']}>
          <SharedViewPage />
        </MemoryRouter>
      </I18nProvider>,
    );
    expect(await screen.findByRole('heading', { name: 'Current identity' })).toBeVisible();
    expect(previousSignal.aborted).toBe(true);
    await act(async () => {
      finish(payload);
      await previous;
    });
    expect(screen.getByRole('heading', { name: 'Current identity' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Public orders' })).toBeNull();
  });
});
