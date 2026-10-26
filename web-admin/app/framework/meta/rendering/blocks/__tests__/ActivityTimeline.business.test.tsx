import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityTimeline } from '../ActivityTimeline';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import capturedNullOmission from './fixtures/business-timeline-null-omission.json';
import type {
  BusinessActivityRecordMapping,
  BusinessActivityDetailField,
  BusinessActivitySnapshotField,
} from '~/framework/meta/schemas/types';
const { fetchResultMock } = vi.hoisted(() => ({ fetchResultMock: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: fetchResultMock }));
const managers: DataSourceManager[] = [];
const mapping: BusinessActivityRecordMapping = {
  pid: 'pid',
  objectRecord: 'anc_alert_ref',
  occurredAt: 'anc_action_at',
  subject: 'anc_action_name',
  content: 'anc_action_note',
  actorName: 'anc_operator',
  fromState: 'anc_from',
  toState: 'anc_to',
};
const detailFields: BusinessActivityDetailField[] = [
  { field: 'anc_resolution', label: 'Current outcome', dictCode: 'resolution' },
];
const snapshots: BusinessActivitySnapshotField[] = [
  { field: 'anc_previous_resolution', label: 'Previous outcome', dictCode: 'resolution' },
  { field: 'anc_previous_note', label: 'Previous note' },
  { field: 'anc_previous_review_note', label: 'Previous review' },
];
const dictionaries = (code: string) =>
  code === 'status'
    ? [
        { value: 'new', label: 'Awaiting investigation' },
        { value: 'closed', label: 'Closed' },
        { value: 'investigating', label: 'Investigating' },
      ]
    : [{ value: 'insufficient', label: 'Insufficient evidence' }];
interface HistoryRow {
  pid: string;
  anc_alert_ref: string;
  anc_action_at: string;
  anc_action_name: string;
  anc_action_note: string;
  anc_operator: string;
  anc_from: string | null;
  anc_to: string | null;
  anc_resolution?: string | null;
  anc_previous_resolution?: string | null;
  anc_previous_note?: string | null;
  anc_previous_review_note?: string | null;
}
function row(pid: string, alert = 'alert-A', note = 'Actual note'): HistoryRow {
  return {
    pid,
    anc_alert_ref: alert,
    anc_action_at: '2026-10-26T10:00:00Z',
    anc_action_name: 'Actual action',
    anc_action_note: note,
    anc_operator: 'Verified operator',
    anc_from: null,
    anc_to: 'new',
    anc_resolution: null,
  };
}
function response(rows: unknown[]) {
  return { code: '0', data: { records: rows, total: rows.length, page: 1, pageSize: 100 } };
}
function runtime(pid = 'alert-A') {
  const state = new ScopedStateManager({ locale: 'en-US', theme: 'light' });
  state.createScope('timeline', {
    form: {},
    record: { pid },
    $page: { recordPid: pid, modelCode: 'anc_alert' },
  });
  const manager = new DataSourceManager(
    createExpressionContext({ locale: 'en-US', global: { locale: 'en-US', theme: 'light' } }),
  );
  managers.push(manager);
  manager.bindStateManager(state, 'timeline');
  manager.register('history', {
    type: 'api',
    modelCode: 'anc_action',
    endpoint: '/api/dynamic/anc_action/list',
    method: 'get',
    adaptor: 'raw',
    autoFetch: false,
    dependOn: ['$page.recordPid'],
    params: {
      pageNum: 1,
      pageSize: 100,
      filters: [{ fieldName: 'anc_alert_ref', operator: 'EQ', value: '${$page.recordPid}' }],
    },
  });
  return {
    manager,
    state,
    getContext: () => state.getContext('timeline'),
    getDataSourceManager: () => manager,
  };
}
const props = {
  businessOnly: true,
  modelCode: 'anc_alert',
  recordPid: 'alert-A',
  businessDataSource: 'history',
  businessRecordMapping: mapping,
  businessStateDictCode: 'status',
  businessSnapshotFields: snapshots,
  getDictItems: dictionaries,
  compact: true,
  locale: 'en-US',
  timezone: 'UTC',
};
beforeEach(() => {
  fetchResultMock.mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  managers.splice(0).forEach((manager) => manager.clear());
  vi.restoreAllMocks();
});
describe('ActivityTimeline named business-only source', () => {
  it('renders the captured dynamic API response with omitted nullable display fields', async () => {
    const received = capturedNullOmission.data.records[0];
    expect(received).not.toHaveProperty('anc_from');
    expect(received).not.toHaveProperty('anc_resolution');
    fetchResultMock.mockResolvedValue(capturedNullOmission);
    render(
      <ActivityTimeline
        {...props}
        recordPid={received.anc_alert_ref}
        businessDetailFields={detailFields}
        runtime={runtime(received.anc_alert_ref)}
      />,
    );
    const entry = await screen.findByTestId(`activity-timeline-item-business-${received.pid}`);
    expect(entry).toHaveTextContent(received.anc_action_name);
    expect(entry).toHaveTextContent(received.anc_action_note);
    expect(entry).toHaveTextContent(received.anc_operator);
    expect(screen.getByTestId(`activity-timeline-transition-${received.pid}`)).toHaveTextContent(
      'Not provided or not visible→Awaiting investigation',
    );
    expect(screen.queryByTestId(`activity-timeline-details-${received.pid}`)).toBeNull();
    expect(screen.queryByTestId(`activity-timeline-snapshot-${received.pid}`)).toBeNull();
    expect(fetchResultMock).toHaveBeenCalledTimes(1);
    expect(fetchResultMock.mock.calls[0][1].params.filters).toBe(
      JSON.stringify([
        { fieldName: 'anc_alert_ref', operator: 'EQ', value: received.anc_alert_ref },
      ]),
    );
  });
  it('removes previously visible optional fields when a later authorized projection omits them', async () => {
    const hidden: Record<string, unknown> = { ...row('projection') };
    for (const key of [
      'anc_action_name',
      'anc_action_note',
      'anc_operator',
      'anc_from',
      'anc_to',
    ]) {
      delete hidden[key];
    }
    fetchResultMock
      .mockResolvedValueOnce(response([row('projection')]))
      .mockResolvedValueOnce(response([hidden]));
    const host = runtime();
    render(<ActivityTimeline {...props} runtime={host} />);
    await screen.findByText('Actual note');
    await act(async () => {
      await host.manager.reload('history');
    });
    const entry = await screen.findByTestId('activity-timeline-item-business-projection');
    expect(entry).toHaveTextContent('Actor unavailable');
    expect(screen.queryByText('Verified operator')).toBeNull();
    expect(screen.queryByText('Actual note')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Actual action' })).toBeNull();
    expect(screen.queryByTestId('activity-timeline-transition-projection')).toBeNull();
    expect(screen.queryByTestId('activity-timeline-snapshot-projection')).toBeNull();
    expect(fetchResultMock).toHaveBeenCalledTimes(2);
    expect(
      fetchResultMock.mock.calls.every(([url]) => url === '/api/dynamic/anc_action/list'),
    ).toBe(true);
  });
  it.each(['pid', 'anc_alert_ref', 'anc_action_at'])(
    'still rejects a missing required identity or clock field: %s',
    async (field) => {
      const invalid: Record<string, unknown> = { ...row('required') };
      delete invalid[field];
      fetchResultMock.mockResolvedValue(response([invalid]));
      render(<ActivityTimeline {...props} runtime={runtime()} />);
      expect(await screen.findByTestId('activity-timeline-error')).toHaveAttribute(
        'data-error-reason',
        'invalid',
      );
      expect(screen.queryByTestId('activity-timeline')).toBeNull();
    },
  );
  it.each([
    ['anc_action_name', {}],
    ['anc_action_note', []],
    ['anc_operator', {}],
    ['anc_from', {}],
    ['anc_to', []],
    ['anc_resolution', {}],
    ['anc_previous_note', {}],
    ['anc_action_at', 'not-a-time'],
  ])('still rejects a malformed present mapped field: %s', async (field, actual) => {
    fetchResultMock.mockResolvedValue(response([{ ...row('malformed'), [String(field)]: actual }]));
    render(<ActivityTimeline {...props} businessDetailFields={detailFields} runtime={runtime()} />);
    expect(await screen.findByTestId('activity-timeline-error')).toHaveAttribute(
      'data-error-reason',
      'invalid',
    );
    expect(screen.queryByTestId('activity-timeline')).toBeNull();
  });
  it('still rejects duplicate identities instead of rendering a partial history', async () => {
    fetchResultMock.mockResolvedValue(response([row('duplicate'), row('duplicate')]));
    render(<ActivityTimeline {...props} runtime={runtime()} />);
    expect(await screen.findByTestId('activity-timeline-error')).toHaveAttribute(
      'data-error-reason',
      'invalid',
    );
    expect(screen.queryByTestId('activity-timeline')).toBeNull();
  });
  it('uses the actual DMS filter, shows complete text and actual snapshots without audit access', async () => {
    const note =
      'First line\n' +
      'A real saved sentence. '.repeat(30) +
      '\nCase 123e4567-e89b-42d3-a456-426614174000 remains in the saved note.';
    fetchResultMock.mockResolvedValue(
      response([
        { ...row('received'), anc_action_name: 'Received event' },
        {
          ...row('closed', 'alert-A', 'Actual close explanation'),
          anc_action_at: '2026-10-26T11:00:00Z',
          anc_action_name: 'Closed event',
          anc_from: 'investigating',
          anc_to: 'closed',
          anc_resolution: 'insufficient',
        },
        {
          ...row('reopened', 'alert-A', note),
          anc_action_at: '2026-10-26T12:00:00Z',
          anc_action_name: 'Reopened event',
          anc_from: 'closed',
          anc_to: 'investigating',
          anc_previous_resolution: 'insufficient',
          anc_previous_note: 'Actual previous note',
          anc_previous_review_note: null,
        },
      ]),
    );
    render(<ActivityTimeline {...props} businessDetailFields={detailFields} runtime={runtime()} />);
    const content = await screen.findByTestId('activity-timeline-note-reopened');
    expect(content.textContent).toBe(note);
    expect(content.className).not.toContain('line-clamp');
    expect(screen.getByTestId('activity-timeline-actor-reopened')).toHaveTextContent(
      'Verified operator',
    );
    expect(screen.getByTestId('activity-timeline-transition-received')).toHaveTextContent(
      '—→Awaiting investigation',
    );
    expect(screen.getByTestId('activity-timeline-transition-reopened')).toHaveTextContent(
      'Closed→Investigating',
    );
    const closed = screen.getByTestId('activity-timeline-item-business-closed');
    expect(within(closed).getByRole('heading', { name: 'Closed event' })).toBeVisible();
    const currentOutcome = screen.getByText('Current outcome').closest('div');
    expect(closed).toContainElement(currentOutcome);
    expect(currentOutcome).toHaveTextContent('Current outcome');
    expect(currentOutcome).toHaveTextContent('Insufficient evidence');
    expect(closed).toHaveTextContent('Actual close explanation');
    expect(closed.querySelector('details')).toBeNull();
    expect(screen.queryByTestId('activity-timeline-details-received')).toBeNull();
    expect(screen.queryByTestId('activity-timeline-details-reopened')).toBeNull();
    const disclosure = screen.getByTestId('activity-timeline-snapshot-reopened');
    expect(disclosure).not.toHaveAttribute('open');
    fireEvent.click(disclosure.querySelector('summary')!);
    expect(disclosure).toHaveAttribute('open');
    expect(disclosure).toHaveTextContent('Previous outcome');
    expect(disclosure).toHaveTextContent('Insufficient evidence');
    expect(disclosure).not.toHaveTextContent('Current outcome');
    expect(disclosure).toHaveTextContent('Actual previous note');
    expect(disclosure).not.toHaveTextContent('Previous review');
    expect(screen.queryByTestId('activity-timeline-snapshot-received')).toBeNull();
    expect(fetchResultMock).toHaveBeenCalledTimes(1);
    expect(fetchResultMock).toHaveBeenCalledWith(
      '/api/dynamic/anc_action/list',
      expect.objectContaining({
        params: expect.objectContaining({
          filters: JSON.stringify([
            { fieldName: 'anc_alert_ref', operator: 'EQ', value: 'alert-A' },
          ]),
        }),
      }),
    );
    expect(fetchResultMock.mock.calls.some(([url]) => url === '/api/activities')).toBe(false);
  });
  it('keeps the real DMS refresh subscription instead of freezing a copied array', async () => {
    fetchResultMock
      .mockResolvedValueOnce(
        response([
          { ...row('entry', 'alert-A', 'Before refresh'), anc_resolution: 'insufficient' },
        ]),
      )
      .mockResolvedValueOnce(
        response([{ ...row('entry', 'alert-A', 'After refresh'), anc_resolution: 'insufficient' }]),
      );
    const host = runtime();
    render(<ActivityTimeline {...props} runtime={host} />);
    await screen.findByText('Before refresh');
    expect(screen.queryByTestId('activity-timeline-details-entry')).toBeNull();
    await act(async () => {
      await host.manager.reload('history');
    });
    await screen.findByText('After refresh');
    expect(screen.queryByTestId('activity-timeline-details-entry')).toBeNull();
    expect(screen.queryByText('Before refresh')).toBeNull();
    expect(fetchResultMock).toHaveBeenCalledTimes(2);
  });
  it('retains a denied error and retries the same native source without a false empty state', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ code: '403', data: null })
      .mockResolvedValueOnce(response([row('retry-result')]));
    const host = runtime();
    render(<ActivityTimeline {...props} runtime={host} />);
    const error = await screen.findByTestId('activity-timeline-error');
    expect(error).toHaveAttribute('data-error-reason', 'denied');
    expect(error).toHaveTextContent(
      'The current session or permission does not allow this history read.',
    );
    expect(screen.queryByTestId('activity-timeline-empty')).toBeNull();
    fireEvent.click(screen.getByTestId('activity-timeline-retry'));
    await screen.findByTestId('activity-timeline-item-business-retry-result');
    expect(fetchResultMock).toHaveBeenCalledTimes(2);
    expect(host.manager.getState('history')?.error).toBeNull();
  });
  it('does not fetch a missing parent identity and rejects an exact-PID mismatch as invalid', async () => {
    const host = runtime('');
    const view = render(<ActivityTimeline {...props} recordPid="" runtime={host} />);
    expect(screen.getByTestId('activity-timeline-pending')).toBeVisible();
    expect(fetchResultMock).not.toHaveBeenCalled();
    fetchResultMock.mockResolvedValue(response([row('wrong-record', 'alert-A')]));
    act(() => {
      host.state.updateScope('timeline', {
        record: { pid: 'alert-B' },
        $page: { recordPid: 'alert-B', modelCode: 'anc_alert' },
      });
    });
    view.rerender(<ActivityTimeline {...props} recordPid="alert-B" runtime={host} />);
    expect(await screen.findByTestId('activity-timeline-error')).toHaveAttribute(
      'data-error-reason',
      'invalid',
    );
    expect(screen.queryByTestId('activity-timeline-empty')).toBeNull();
    expect(screen.queryByTestId('activity-timeline-item-business-wrong-record')).toBeNull();
  });
  it('discards a delayed A response after the real DMS scope changes to B', async () => {
    let resolveA!: (value: ReturnType<typeof response>) => void;
    fetchResultMock.mockImplementation((_url, options) =>
      JSON.parse(options.params.filters)[0].value === 'alert-A'
        ? new Promise<ReturnType<typeof response>>((resolve) => {
            resolveA = resolve;
          })
        : Promise.resolve(response([row('B-entry', 'alert-B', 'B-only note')])),
    );
    const host = runtime();
    const view = render(<ActivityTimeline {...props} runtime={host} />);
    await waitFor(() => expect(fetchResultMock).toHaveBeenCalledTimes(1));
    act(() => {
      host.state.updateScope('timeline', {
        record: { pid: 'alert-B' },
        $page: { recordPid: 'alert-B', modelCode: 'anc_alert' },
      });
    });
    view.rerender(<ActivityTimeline {...props} recordPid="alert-B" runtime={host} />);
    await screen.findByText('B-only note');
    await act(async () => {
      resolveA(response([row('A-entry', 'alert-A', 'A must stay hidden')]));
    });
    expect(screen.getByTestId('activity-timeline')).toHaveAttribute('data-record-pid', 'alert-B');
    expect(screen.queryByText('A must stay hidden')).toBeNull();
    expect(screen.queryByTestId('activity-timeline-item-business-A-entry')).toBeNull();
  });
  it('discloses the render limit against real page totals instead of implying full history', async () => {
    fetchResultMock.mockResolvedValue(response([row('one'), row('two'), row('three')]));
    render(<ActivityTimeline {...props} previewLimit={2} runtime={runtime()} />);
    await screen.findByTestId('activity-timeline');
    expect(screen.getAllByTestId(/^activity-timeline-item-business-/)).toHaveLength(2);
    expect(screen.getByTestId('activity-timeline-limit')).toHaveTextContent(
      'Showing 2 of 3; limit 2',
    );
  });
});
