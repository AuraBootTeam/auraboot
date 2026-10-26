import { useState, type ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  createMemoryRouter,
  data,
  RouterProvider,
  useLocation,
  type LoaderFunction,
} from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DetailModalProvider, DETAIL_MODAL_QUERY, useDetailModal } from '../DetailModalProvider';
import { resolveDetailModalTarget } from '../detailModalTarget';
import { FormDialogProvider } from '~/framework/meta/runtime/actions/FormDialog';
import { useDetailPresentationTitle } from '../DetailPresentationContext';
// Exercise actual router history and fetchers instead of the global hook doubles.
vi.mock('react-router', async (importOriginal) => importOriginal<typeof import('react-router')>());
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: 'en',
    t: (key: string, _args?: unknown, fallback?: string) => fallback ?? key,
  }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({ useTimezone: () => ({ timezone: 'UTC' }) }));
vi.mock('../../DynamicPageRenderer', () => ({
  default: function MockDynamicDetail({
    tableName,
    recordPid,
  }: {
    tableName: string;
    recordPid: string;
  }) {
    useDetailPresentationTitle(`Event ${recordPid}`);
    const open = useDetailModal();
    const [identity] = useState(`${tableName}:${recordPid}`);
    return (
      <div data-testid="detail-identity">
        {identity}
        {recordPid === 'A' && (
          <button
            onClick={() =>
              open?.(resolveDetailModalTarget('/p/anc_alert/view/B'), navigation.replacementClosed)
            }
          >
            View B
          </button>
        )}
      </div>
    );
  },
}));
const navigation = vi.hoisted(() => ({ reload: vi.fn(), replacementClosed: vi.fn() }));
vi.mock('../detailModalTarget', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../detailModalTarget')>()),
  reloadDetailModalSource: navigation.reload,
}));
const routers: ReturnType<typeof createMemoryRouter>[] = [];
afterEach(() => {
  routers.splice(0).forEach((router) => router.dispose());
  navigation.reload.mockReset();
  navigation.replacementClosed.mockReset();
});
function Source({ closed }: { closed: () => void }) {
  const open = useDetailModal()!,
    location = useLocation();
  return (
    <>
      <button onClick={() => open(resolveDetailModalTarget('/p/anc_alert/view/A'), closed)}>
        Open A
      </button>
      <output data-testid="source-url">
        {location.pathname}
        {location.search}
        {location.hash}
      </output>
    </>
  );
}
function mount(
  initial: string | string[],
  loader: LoaderFunction,
  closed = vi.fn(),
  source?: ReactNode,
) {
  const router = createMemoryRouter(
    [
      {
        path: '/source',
        element: (
          <FormDialogProvider>
            <DetailModalProvider>{source ?? <Source closed={closed} />}</DetailModalProvider>
          </FormDialogProvider>
        ),
      },
      { path: '/p/:pageKey/view/:recordPid', loader, element: <div>Normal detail route</div> },
    ],
    { initialEntries: Array.isArray(initial) ? initial : [initial] },
  );
  routers.push(router);
  const view = render(<RouterProvider router={router} />);
  return { router, view, closed };
}
const allowed: LoaderFunction = ({ params }) => ({
  tableName: params.pageKey,
  recordPid: params.recordPid,
  accessError: null,
  httpStatus: 200,
});
describe('ordinary detail modal route contract', () => {
  it('preserves source filters/hash, pushes the detail URL, and Back closes with one native refresh callback', async () => {
    const loader = vi.fn(allowed),
      closed = vi.fn();
    const { router } = mount('/source?status=new#history', loader, closed);
    const opener = screen.getByRole('button', { name: 'Open A' });
    opener.focus();
    fireEvent.click(opener);
    await screen.findByText('anc_alert:A');
    const query = new URLSearchParams(router.state.location.search);
    expect(query.get('status')).toBe('new');
    expect(query.get(DETAIL_MODAL_QUERY)).toBe('/p/anc_alert/view/A');
    expect(new URL(loader.mock.calls[0][0].request.url).searchParams.get('presentation')).toBe(
      'modal',
    );
    fireEvent.click(screen.getByTestId('detail-modal-close'));
    await waitFor(() => expect(router.state.location.search).toBe('?status=new'));
    expect(router.state.location.hash).toBe('#history');
    await waitFor(() => expect(closed).toHaveBeenCalledOnce());
    await waitFor(() => expect(opener).toHaveFocus());
    expect(screen.queryByTestId('detail-modal')).not.toBeInTheDocument();
  });
  it('reconstructs the same PID after a fresh app load, ignoring width query input', async () => {
    const url = '/source?status=new&width=5000&detailModal=%2Fp%2Fanc_alert%2Fview%2FA';
    const loader = vi.fn(allowed);
    const first = mount(url, loader);
    await screen.findByText('anc_alert:A');
    expect(
      screen.getByRole('dialog', { name: 'Event A' }).style.getPropertyValue('--dialog-width'),
    ).toBe('900px');
    first.view.unmount();
    first.router.dispose();
    mount(url, loader);
    await screen.findByText('anc_alert:A');
    expect(loader).toHaveBeenCalledTimes(2);
  });
  it('reloads the explicit source after Back from a restored modal without a live callback', async () => {
    const closed = vi.fn();
    const { router } = mount(
      ['/source?status=new', '/source?status=new&detailModal=%2Fp%2Fanc_alert%2Fview%2FA'],
      allowed,
      closed,
    );
    await screen.findByText('anc_alert:A');
    await act(async () => {
      await router.navigate(-1);
    });
    await waitFor(() =>
      expect(navigation.reload).toHaveBeenCalledExactlyOnceWith('/source?status=new'),
    );
    expect(closed).not.toHaveBeenCalled();
  });
  it('uses the actual source reload for X after a deep link and preserves all source query values', async () => {
    mount('/source?status=new&detailModal=%2Fp%2Fanc_alert%2Fview%2FA#history', allowed);
    await screen.findByText('anc_alert:A');
    fireEvent.click(screen.getByTestId('detail-modal-close'));
    expect(navigation.reload).toHaveBeenCalledExactlyOnceWith('/source?status=new#history');
  });
  it('does not show a late A loader result after URL navigation to B', async () => {
    let release!: (value: unknown) => void;
    const loader = vi.fn<LoaderFunction>(({ params }) =>
      params.recordPid === 'A'
        ? new Promise((resolve) => {
            release = resolve;
          })
        : { tableName: params.pageKey, recordPid: params.recordPid, accessError: null },
    );
    const { router } = mount('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FA', loader);
    await waitFor(() => expect(loader).toHaveBeenCalledOnce());
    await act(async () => {
      await router.navigate('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FB');
    });
    await screen.findByText('anc_alert:B');
    await act(async () => {
      release({ tableName: 'anc_alert', recordPid: 'A', accessError: null });
    });
    expect(screen.getByTestId('detail-identity')).toHaveTextContent('anc_alert:B');
    expect(screen.queryByText('anc_alert:A')).not.toBeInTheDocument();
  });
  it('keeps a returned 403 as a denied state and does not mount the record renderer', async () => {
    mount('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FA', () =>
      data(
        { tableName: 'anc_alert', recordPid: 'A', accessError: 'Denied', httpStatus: 403 },
        { status: 403 },
      ),
    );
    await waitFor(() => expect(screen.getByRole('alert')).toHaveAttribute('data-status', '403'));
    expect(screen.queryByTestId('detail-identity')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Record details' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Event A' })).not.toBeInTheDocument();
    expect(screen.getByTestId('source-url')).toHaveTextContent('/source');
  });
  it('retries a correlated loader failure without navigating the background page', async () => {
    let calls = 0;
    mount('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FA', (args) =>
      ++calls === 1
        ? data(
            {
              tableName: 'anc_alert',
              recordPid: 'A',
              accessError: null,
              loadError: 'Failure',
              httpStatus: 500,
            },
            { status: 500 },
          )
        : allowed(args),
    );
    await screen.findByRole('button', { name: 'Retry' });
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByText('anc_alert:A');
    expect(calls).toBe(2);
  });
  it('rejects an untrusted target before calling any loader', () => {
    const loader = vi.fn(allowed);
    mount('/source?detailModal=https%3A%2F%2Fevil.example%2Fp%2Fa%2Fview%2FA', loader);
    expect(screen.getByRole('alert')).toHaveAttribute('data-status', '400');
    expect(loader).not.toHaveBeenCalled();
  });
});

it('keeps the single record-owned host title and viewport geometry without backdrop dismissal', async () => {
  const closed = vi.fn();
  const { router } = mount('/source', allowed, closed);
  fireEvent.click(screen.getByRole('button', { name: 'Open A' }));
  await screen.findByRole('dialog', { name: 'Event A' });
  expect(screen.getAllByRole('heading', { name: 'Event A' })).toHaveLength(1);
  expect(screen.queryByRole('heading', { name: 'Record details' })).not.toBeInTheDocument();
  expect(
    screen.getByRole('dialog', { name: 'Event A' }).style.getPropertyValue('--dialog-width'),
  ).toBe('900px');
  expect(screen.getByRole('dialog', { name: 'Event A' })).toHaveClass(
    'aura-dialog-presentation__panel',
  );
  expect(screen.getByTestId('detail-modal')).toHaveClass('aura-dialog-presentation');
  expect(screen.getByTestId('detail-modal-backdrop')).toHaveClass(
    'aura-dialog-presentation__backdrop',
  );
  expect(screen.getByTestId('detail-modal-body')).toHaveClass('aura-dialog-presentation__body');
  fireEvent.click(screen.getByTestId('detail-modal-backdrop'));
  expect(new URLSearchParams(router.state.location.search).get(DETAIL_MODAL_QUERY)).toBe(
    '/p/anc_alert/view/A',
  );
  expect(closed).not.toHaveBeenCalled();
  fireEvent.click(screen.getByTestId('detail-modal-close'));
  await waitFor(() => expect(closed).toHaveBeenCalledOnce());
});
it('keeps a generic title while loading and never carries A title into a denied B modal', async () => {
  let release!: (value: unknown) => void;
  const { router } = mount('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FA', ({ params }) =>
    params.recordPid === 'A'
      ? new Promise((resolve) => {
          release = resolve;
        })
      : data(
          { tableName: 'anc_alert', recordPid: 'B', accessError: 'Denied', httpStatus: 403 },
          { status: 403 },
        ),
  );
  expect(screen.getByRole('dialog', { name: 'Record details' })).toBeInTheDocument();
  await waitFor(() => expect(release).toBeTypeOf('function'));
  await act(async () => release({ tableName: 'anc_alert', recordPid: 'A', accessError: null }));
  await screen.findByRole('dialog', { name: 'Event A' });
  await act(async () => {
    await router.navigate('/source?detailModal=%2Fp%2Fanc_alert%2Fview%2FB');
  });
  await screen.findByRole('alert');
  expect(screen.getByRole('dialog', { name: 'Record details' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: 'Event A' })).not.toBeInTheDocument();
});

function deferredRead() {
  let resolve!: () => void, reject!: (failure: Error) => void;
  const promise = new Promise<void>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function RefreshingSource({
  read,
  started,
  filtered = false,
}: {
  read: Promise<void>;
  started: () => void;
  filtered?: boolean;
}) {
  const open = useDetailModal()!;
  const [updated, setUpdated] = useState(false);
  const refresh = async () => {
    started();
    await read;
    setUpdated(true);
  };
  return (
    <>
      <input aria-label="Source search" />
      <section data-aura-block-id="records" data-aura-page-id="cases">
        <table>
          <tbody>
            {updated && filtered ? (
              <tr data-record-pid="B">
                <td>Another record</td>
              </tr>
            ) : (
              <tr key={updated ? 'new-row' : 'old-row'} data-record-pid="A">
                <td>
                  <button
                    onClick={() => open(resolveDetailModalTarget('/p/anc_alert/view/A'), refresh)}
                  >
                    {updated ? 'Open A after read' : 'Open A before read'}
                  </button>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </>
  );
}
async function nextFrame() {
  await act(async () => {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  });
}
it.each(['X', 'Back'])(
  'waits for the real source read after %s and restores a rebuilt PID row without choosing an action',
  async (closing) => {
    const read = deferredRead(),
      started = vi.fn();
    const { router, view } = mount(
      '/source?status=new#events',
      allowed,
      undefined,
      <RefreshingSource read={read.promise} started={started} />,
    );
    const opener = screen.getByRole('button', { name: 'Open A before read' });
    opener.focus();
    fireEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Event A' });
    await waitFor(() => expect(screen.getByTestId('detail-modal-close')).toHaveFocus());
    if (closing === 'X') fireEvent.click(screen.getByTestId('detail-modal-close'));
    else
      await act(async () => {
        await router.navigate(-1);
      });
    await waitFor(() => expect(started).toHaveBeenCalledOnce());
    await nextFrame();
    expect(opener).not.toHaveFocus();
    await act(async () => read.resolve());
    await screen.findByRole('button', { name: 'Open A after read' });
    const row = view.container.querySelector('[data-record-pid="A"]');
    if (!(row instanceof HTMLElement)) throw new Error('Refreshed record row was not mounted');
    await waitFor(() => expect(row).toHaveFocus());
    expect(opener.isConnected).toBe(false);
    expect(row).toHaveAttribute('tabindex', '-1');
    expect(router.state.location.search).toBe('?status=new');
    expect(router.state.location.hash).toBe('#events');
    expect(navigation.reload).not.toHaveBeenCalled();
    screen.getByRole('textbox', { name: 'Source search' }).focus();
    expect(row).not.toHaveAttribute('tabindex');
  },
);
it.each(['filtered', 'failed'])(
  'returns to the source block when the record is %s',
  async (outcome) => {
    const read = deferredRead(),
      started = vi.fn();
    const { view } = mount(
      '/source',
      allowed,
      undefined,
      <RefreshingSource read={read.promise} started={started} filtered={outcome === 'filtered'} />,
    );
    const opener = screen.getByRole('button', { name: 'Open A before read' });
    opener.focus();
    fireEvent.click(opener);
    await screen.findByRole('dialog', { name: 'Event A' });
    fireEvent.click(screen.getByTestId('detail-modal-close'));
    await waitFor(() => expect(started).toHaveBeenCalledOnce());
    await act(async () =>
      outcome === 'failed' ? read.reject(new Error('Source read failed')) : read.resolve(),
    );
    const block = view.container.querySelector('[data-aura-block-id="records"]');
    if (!(block instanceof HTMLElement)) throw new Error('Source block was not mounted');
    await waitFor(() => expect(block).toHaveFocus());
    expect(block).toHaveAttribute('tabindex', '-1');
    expect(opener).not.toHaveFocus();
  },
);
it('does not steal a deliberate source focus change while its read is pending', async () => {
  const read = deferredRead(),
    started = vi.fn();
  mount('/source', allowed, undefined, <RefreshingSource read={read.promise} started={started} />);
  const opener = screen.getByRole('button', { name: 'Open A before read' });
  opener.focus();
  fireEvent.click(opener);
  await screen.findByRole('dialog', { name: 'Event A' });
  fireEvent.click(screen.getByTestId('detail-modal-close'));
  await waitFor(() => expect(started).toHaveBeenCalledOnce());
  const search = screen.getByRole('textbox', { name: 'Source search' });
  fireEvent.pointerDown(search);
  search.focus();
  await act(async () => read.resolve());
  await screen.findByRole('button', { name: 'Open A after read' });
  await nextFrame();
  expect(search).toHaveFocus();
});
it('cancels a pending return when the source query changes before the read settles', async () => {
  const read = deferredRead(),
    started = vi.fn();
  const { router, view } = mount(
    '/source?status=new',
    allowed,
    undefined,
    <RefreshingSource read={read.promise} started={started} />,
  );
  const opener = screen.getByRole('button', { name: 'Open A before read' });
  opener.focus();
  fireEvent.click(opener);
  await screen.findByRole('dialog', { name: 'Event A' });
  fireEvent.click(screen.getByTestId('detail-modal-close'));
  await waitFor(() => expect(started).toHaveBeenCalledOnce());
  await act(async () => {
    await router.navigate('/source?status=closed');
  });
  await act(async () => read.resolve());
  await screen.findByRole('button', { name: 'Open A after read' });
  await nextFrame();
  expect(router.state.location.search).toBe('?status=closed');
  expect(view.container.querySelector('[data-record-pid="A"]')).not.toHaveFocus();
  expect(view.container.querySelector('[data-aura-block-id="records"]')).not.toHaveFocus();
});
it('keeps the first live source callback and focus owner when a modal itself opens B', async () => {
  const closed = vi.fn(),
    { router } = mount('/source?status=new', allowed, closed);
  const opener = screen.getByRole('button', { name: 'Open A' });
  opener.focus();
  fireEvent.click(opener);
  await screen.findByRole('dialog', { name: 'Event A' });
  const viewB = screen.getByRole('button', { name: 'View B' });
  viewB.focus();
  fireEvent.click(viewB);
  await screen.findByRole('dialog', { name: 'Event B' });
  expect(new URLSearchParams(router.state.location.search).get(DETAIL_MODAL_QUERY)).toBe(
    '/p/anc_alert/view/B',
  );
  fireEvent.click(screen.getByTestId('detail-modal-close'));
  await waitFor(() => expect(closed).toHaveBeenCalledOnce());
  await waitFor(() => expect(opener).toHaveFocus());
  expect(navigation.replacementClosed).not.toHaveBeenCalled();
  expect(router.state.location.search).toBe('?status=new');
});
