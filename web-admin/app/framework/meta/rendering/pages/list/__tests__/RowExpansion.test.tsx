import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RowExpansion, rowExpansionEndpoint } from '../RowExpansion';
import { ListTable, type ListTableProps } from '../ListTable';
import type { RowExpansionConfig } from '~/framework/meta/schemas/types';

const state = vi.hoisted(() => ({ fetch: vi.fn(), user: { tenantId: 1, pid: 'buyer-a' } }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: state.fetch }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ user: state.user }) }));
const config: RowExpansionConfig = {
  endpoint: '/api/tasks/${row.pid}/sources',
  titleField: 'code',
  pageSize: 10,
  columns: [
    { field: 'material', label: 'Material', multiline: true },
    { field: 'quantity', label: 'Quantity' },
  ],
};
const record = { pid: 'task-a', code: 'RFQ-001' };
const row = (id = 'material-a') => ({
  pid: id,
  material: id,
  quantity: '25 pcs',
  secret: { must: 'never render' },
});
const response = (records: unknown[] = [row()], total = records.length) => ({
  code: '0',
  data: { records, total },
});
const props = () => ({ config, record, locale: 'en-US', t: (key: string) => key });
function tableProps(): ListTableProps {
  return {
    columns: [{ field: 'code', label: 'RFQ' }],
    data: [record],
    loading: false,
    activeSorts: [],
    selectedIds: new Set(),
    modelCode: 'tasks',
    columnOrder: [],
    columnWidths: {},
    onColumnReorder: vi.fn(),
    onColumnResize: vi.fn(),
    onToggleSort: vi.fn(),
    onSelectRow: vi.fn(),
    onSelectAll: vi.fn(),
    onRowClick: vi.fn(),
    onContextMenu: vi.fn(),
    renderCellContent: (item) => item.code,
    evaluateVisibleWhen: () => true,
    resolveButtonLabel: () => 'Action',
    handleAction: vi.fn(),
    resolveColumnLabel: () => 'RFQ',
    collapsedGroups: new Set(),
    onToggleGroupCollapse: vi.fn(),
    t: (key) => key,
    locale: 'en-US',
    enableSelection: false,
    rowExpansion: config,
  };
}

describe('lazy read-only row expansion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.fetch.mockReset();
    state.user = { tenantId: 1, pid: 'buyer-a' };
  });

  it('escapes references and rejects missing, object, traversal and external endpoints', () => {
    expect(rowExpansionEndpoint(config, { pid: 'A ?#' })).toBe('/api/tasks/A%20%3F%23/sources');
    for (const input of [{}, { pid: {} }, { pid: '..' }, { pid: '' }])
      expect(() => rowExpansionEndpoint(config, input)).toThrow();
    for (const endpoint of [
      'https://elsewhere.invalid/api/x',
      '//elsewhere.invalid/api/x',
      '/api/../../auth',
      '/api/${form.pid}',
    ])
      expect(() => rowExpansionEndpoint({ ...config, endpoint }, record)).toThrow();
  });

  it('loads only after an actual toggle, keeps row navigation separate and discards a closed request', async () => {
    let resolve: (value: unknown) => void = () => {};
    state.fetch
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      )
      .mockResolvedValue(response());
    const options = tableProps();
    render(<ListTable {...options} />);
    expect(state.fetch).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Expand details: RFQ-001' }));
    await waitFor(() => expect(state.fetch).toHaveBeenCalledOnce());
    expect(options.onRowClick).not.toHaveBeenCalled();
    expect(within(screen.getByTestId('row-expansion-detail')).getByRole('status')).toHaveTextContent('Loading details');
    fireEvent.click(screen.getByRole('button', { name: 'Collapse details: RFQ-001' }));
    await act(async () => resolve(response([{ ...row(), material: 'late closed material' }])));
    expect(screen.queryByText('late closed material')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Expand details: RFQ-001' }));
    await screen.findByText('material-a');
    fireEvent.click(screen.getByText('RFQ-001'));
    expect(options.onRowClick).toHaveBeenCalledWith(record);
    expect(options.onSelectRow).not.toHaveBeenCalled();
  });

  it('pages only the selected child endpoint and renders scalar text without HTML or objects', async () => {
    const records = Array.from({ length: 10 }, (_, index) => row('part-' + index));
    records[0].material = '<img src=x onerror=alert(1)>\n0805';
    state.fetch.mockImplementation(async (_endpoint: string, { params }: any) =>
      params.page === 0 ? response(records, 11) : response([row('last-part')], 11),
    );
    const { container } = render(<RowExpansion {...props()} />);
    await screen.findByText('<img src=x onerror=alert(1)> 0805');
    expect(container.querySelector('img')).toBeNull();
    expect(container).not.toHaveTextContent('never render');
    expect(state.fetch).toHaveBeenCalledWith('/api/tasks/task-a/sources', {
      method: 'get',
      params: { page: 0, size: 10 },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await screen.findByText('last-part');
    expect(screen.queryByText('part-1')).not.toBeInTheDocument();
    expect(state.fetch).toHaveBeenLastCalledWith('/api/tasks/task-a/sources', {
      method: 'get',
      params: { page: 1, size: 10 },
    });
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
    expect(screen.getByText('2 / 2')).toBeVisible();
  });

  it('drops delayed data after tenant/user or endpoint changes and starts at the first page', async () => {
    let resolve: (value: unknown) => void = () => {};
    state.fetch
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      )
      .mockResolvedValue(response([row('new-company-material')]));
    const view = render(<RowExpansion {...props()} />);
    await waitFor(() => expect(state.fetch).toHaveBeenCalledOnce());
    state.user = { tenantId: 2, pid: 'buyer-b' };
    view.rerender(<RowExpansion {...props()} record={{ pid: 'task-b', code: 'RFQ-002' }} />);
    await screen.findByText('new-company-material');
    await act(async () => resolve(response([row('old-company-material')])));
    expect(screen.queryByText('old-company-material')).not.toBeInTheDocument();
    expect(state.fetch).toHaveBeenLastCalledWith('/api/tasks/task-b/sources', {
      method: 'get',
      params: { page: 0, size: 10 },
    });
  });

  it('shows a localized failure and retries the same read only after clicking retry', async () => {
    state.fetch
      .mockRejectedValueOnce(new Error('private body must not leak'))
      .mockResolvedValue(response([], 0));
    render(<RowExpansion {...props()} />);
    await screen.findByRole('alert');
    expect(state.fetch).toHaveBeenCalledOnce();
    expect(screen.queryByText('private body must not leak')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByText('No details on this page.');
    expect(state.fetch).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each([
    response([row(), row()], 2),
    response([], 3),
    { code: '403', data: { records: [], total: 0 } },
  ])(
    'rejects incomplete, repeated or unauthorized collections instead of reporting empty',
    async (value) => {
      state.fetch.mockResolvedValue(value);
      render(<RowExpansion {...props()} />);
      await screen.findByRole('alert');
      expect(screen.queryByText('No details on this page.')).not.toBeInTheDocument();
    },
  );

  it('never requests an incomplete reference or malformed pagination configuration', async () => {
    const view = render(<RowExpansion {...props()} record={{}} />);
    await screen.findByRole('alert');
    expect(state.fetch).not.toHaveBeenCalled();
    view.rerender(
      <RowExpansion {...props()} config={{ ...config, pageParam: 'same', sizeParam: 'same' }} />,
    );
    await screen.findByRole('alert');
    expect(state.fetch).not.toHaveBeenCalled();
  });

  it('keeps parent rows bounded and expansion usable when the list would otherwise virtualize', async () => {
    state.fetch.mockResolvedValue(response());
    const options = tableProps();
    options.data = Array.from({ length: 60 }, (_, index) => ({
      pid: 'task-' + index,
      code: 'RFQ-' + index,
    }));
    render(<ListTable {...options} />);
    fireEvent.click(screen.getByRole('button', { name: 'Expand details: RFQ-59' }));
    const detail = await screen.findByTestId('row-expansion-detail');
    expect(detail).toHaveTextContent('material-a');
    expect(state.fetch).toHaveBeenCalledWith('/api/tasks/task-59/sources', expect.anything());
  });
});
