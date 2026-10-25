import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ProfileProvider } from '@auraboot/runtime-kernel';
import { adminProfile } from '~/framework/meta/profiles/admin';
import { DetailPageContent, assertStrictDetailRefreshParent } from '../DetailPageContent';
import { actionRegistry } from '../../../runtime/actions/ActionRegistry';
const owned = vi.hoisted(() => ({
  transport: vi.fn(),
  toast: vi.fn(),
  formButtonsReady: undefined as Promise<void> | undefined,
}));
vi.mock('~/framework/meta/rendering/blocks/FormButtonsBlockRenderer', async () => {
  await owned.formButtonsReady;
  return vi.importActual<any>('~/framework/meta/rendering/blocks/FormButtonsBlockRenderer');
});
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => owned.transport(...args),
}));
vi.mock('~/contexts/AuthContext', async () => ({
  ...(await vi.importActual<any>('~/contexts/AuthContext')),
  useAuth: () => ({
    user: { id: 'u1', tenantId: '11' },
    permissions: { permissionCodes: [] },
    token: 'fixture',
    hasPermission: () => true,
  }),
  usePermission: () => true,
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en', t: (key: string) => key }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: owned.toast,
    showErrorToast: owned.toast,
    showWarningToast: owned.toast,
    showInfoToast: owned.toast,
  }),
}));
vi.mock('../hooks/useDictCache', () => ({
  useDictCache: () => ({ loaded: true, getDictLabel: () => undefined, getDictItems: () => [] }),
}));
vi.mock('~/framework/extensions/use-contribution', () => ({
  useContributionRegistry: () => ({
    listPageRuntimeHooks: () => [],
    getComponentLoader: () => undefined,
  }),
}));
vi.mock('~/framework/meta/hooks/useDataSync', () => ({ useDataSync: () => {} }));
vi.mock('../../components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => (
    <label>
      {props.label}
      <input
        aria-label={props.label}
        value={props.value ?? ''}
        readOnly={props.readOnly}
        onChange={(event) => props.onChange(event.target.value)}
      />
    </label>
  ),
}));
describe('Actual Detail parent loader through FormButtons and registered flow', () => {
  it('save refreshes the real parent snapshot once and the second submit uses its current version', async () => {
    let releaseFormButtons!: () => void;
    owned.formButtonsReady = new Promise<void>((resolve) => {
      releaseFormButtons = resolve;
    });
    let version = 1;
    owned.transport.mockReset().mockImplementation(async (endpoint: string) => {
      if (endpoint === '/api/example/parent')
        return {
          code: '0',
          data: {
            phaseReached: 'completed',
            data: { order: { pid: 'order', rowVersion: version, state: 'open' } },
          },
        };
      if (endpoint === '/api/example/save') {
        version++;
        return { code: '0', data: { phaseReached: 'completed', data: { saved: true } } };
      }
      throw new Error('Unexpected test transport');
    });
    const schema = {
      id: 'detail',
      pageKey: 'detail',
      kind: 'detail',
      modelCode: 'example',
      title: 'Order',
      extension: {
        skipFieldMeta: true,
        skipDynamicFieldMeta: true,
        dataSource: {
          type: 'api',
          method: 'post',
          endpoint: '/api/example/parent',
          adaptor: 'commandRecord',
          recordPath: 'order',
          params: { targetRecordPid: '${$page.recordPid}', payload: {} },
        },
      },
      blocks: [
        {
          id: 'facts',
          blockType: 'record-inspector',
          context: '${record}',
          fields: [{ field: 'rowVersion', label: 'Current version' }],
        },
        {
          id: 'actions',
          blockType: 'form-buttons',
          buttons: [
            {
              code: 'save',
              content: 'Save',
              action: {
                type: 'flow',
                steps: [
                  {
                    action: 'api.request',
                    endpoint: '/api/example/save',
                    method: 'post',
                    body: {
                      targetRecordPid: '${record.pid}',
                      expectedVersion: '${record.rowVersion}',
                    },
                  },
                  { action: 'refresh' },
                ],
              },
            },
          ],
        },
      ],
    } as any;
    render(
      <MemoryRouter>
        <ProfileProvider value={adminProfile}>
          <DetailPageContent
            schema={schema}
            tableName="example"
            recordPid="order"
            token="fixture"
          />
        </ProfileProvider>
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByTestId('record-inspector').querySelector('dd')).toHaveTextContent(/^1$/),
    );
    expect(
      owned.transport.mock.calls.filter((call) => call[0] === '/api/example/parent'),
    ).toHaveLength(1);
    // Parent data can arrive before the independently lazy-loaded action block.
    expect(screen.queryByTestId('form-btn-save')).not.toBeInTheDocument();
    releaseFormButtons();
    fireEvent.click(await screen.findByTestId('form-btn-save'));
    await waitFor(() =>
      expect(screen.getByTestId('record-inspector').querySelector('dd')).toHaveTextContent(/^2$/),
    );
    expect(
      owned.transport.mock.calls.filter((call) => call[0] === '/api/example/parent'),
    ).toHaveLength(2);
    fireEvent.click(await screen.findByTestId('form-btn-save'));
    await waitFor(() =>
      expect(screen.getByTestId('record-inspector').querySelector('dd')).toHaveTextContent(/^3$/),
    );
    const requests = owned.transport.mock.calls.filter((call) => call[0] === '/api/example/save');
    expect(requests.map((call) => call[1].params)).toEqual([
      { targetRecordPid: 'order', expectedVersion: 1 },
      { targetRecordPid: 'order', expectedVersion: 2 },
    ]);
    expect(
      owned.transport.mock.calls.filter((call) => call[0] === '/api/example/parent'),
    ).toHaveLength(3);
  });
  it.each([
    { label: 'network failure', payload: undefined },
    { label: 'empty aggregate', payload: {} },
    { label: 'missing parent', payload: { other: { pid: 'order' } } },
    { label: 'null parent', payload: { order: null } },
    { label: 'empty parent', payload: { order: {} } },
    { label: 'array parent', payload: { order: [] } },
    { label: 'foreign parent', payload: { order: { pid: 'foreign', rowVersion: 2 } } },
    { label: 'missing existing version', payload: { order: { pid: 'order', state: 'open' } } },
  ])(
    'a saved command followed by invalid parent reload ($label) keeps dirty rows/edit state and never reaches reset',
    async ({ payload }) => {
      let parentReads = 0;
      owned.toast.mockClear();
      owned.transport.mockReset().mockImplementation(async (endpoint: string) => {
        if (endpoint === '/api/example/parent') {
          parentReads++;
          if (parentReads > 1) {
            if (payload === undefined) throw new Error('Controlled parent transport failure');
            return { code: '0', data: { phaseReached: 'completed', data: payload } };
          }
          return {
            code: '0',
            data: {
              phaseReached: 'completed',
              data: { order: { pid: 'order', rowVersion: 1, state: 'open' } },
            },
          };
        }
        if (endpoint === '/api/example/lines')
          return {
            code: '0',
            data: {
              phaseReached: 'completed',
              data: { lines: [{ pid: 'line', quantity: '250' }] },
            },
          };
        if (endpoint === '/api/example/save')
          return { code: '0', data: { phaseReached: 'completed', data: { saved: true } } };
        throw new Error('Unexpected test transport');
      });
      const schema = {
        id: 'detail',
        pageKey: 'detail',
        kind: 'detail',
        modelCode: 'example',
        title: 'Order',
        state: { editing: true },
        extension: {
          skipFieldMeta: true,
          skipDynamicFieldMeta: true,
          dataSource: {
            type: 'api',
            method: 'post',
            endpoint: '/api/example/parent',
            adaptor: 'commandRecord',
            recordPath: 'order',
            params: { targetRecordPid: '${$page.recordPid}', payload: {} },
          },
        },
        dataSources: {
          draftRows: {
            type: 'api',
            method: 'post',
            endpoint: '/api/example/lines',
            autoFetch: true,
            adaptor: 'commandArray',
            dataPath: 'lines',
            rowProjection: { linePid: 'pid', quantity: 'quantity' },
            rowIdentityField: 'linePid',
          },
        },
        blocks: [
          {
            id: 'facts',
            blockType: 'record-inspector',
            context: '${record}',
            fields: [{ field: 'rowVersion', label: 'Current version' }],
          },
          {
            id: 'editing',
            blockType: 'record-inspector',
            context: '${state}',
            fields: [{ field: 'editing', label: 'Editing' }],
          },
          {
            id: 'draft',
            blockType: 'card-grid',
            field: 'draftLines',
            dataSource: 'draftRows',
            titleField: 'linePid',
            fields: [{ field: 'quantity', label: 'Quantity', component: 'SmartInput' }],
          },
          {
            id: 'actions',
            blockType: 'form-buttons',
            buttons: [
              {
                code: 'save',
                content: 'Save',
                action: {
                  type: 'flow',
                  steps: [
                    {
                      action: 'api.request',
                      endpoint: '/api/example/save',
                      method: 'post',
                      body: {
                        targetRecordPid: '${record.pid}',
                        expectedVersion: '${record.rowVersion}',
                        lines:
                          "${fn.PROJECT_ROWS(form.draftLines,{linePid:'linePid',quantity:'quantity'},'linePid')}",
                      },
                    },
                    { action: 'refresh' },
                    { action: 'form.reset' },
                    { action: 'state.set', args: { editing: false } },
                  ],
                },
              },
            ],
          },
        ],
      } as any;
      render(
        <MemoryRouter>
          <ProfileProvider value={adminProfile}>
            <DetailPageContent
              schema={schema}
              tableName="example"
              recordPid="order"
              token="fixture"
            />
          </ProfileProvider>
        </MemoryRouter>,
      );
      await waitFor(() => expect(screen.getAllByTestId('record-inspector')[0].querySelector('dd')).toHaveTextContent(/^1$/));
      await waitFor(() => expect(screen.getByLabelText('Quantity')).toHaveValue('250'));
      fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '260' } });
      fireEvent.click(screen.getByTestId('form-btn-save'));
      await waitFor(() => expect(owned.toast).toHaveBeenCalled());
      expect(parentReads).toBe(2);
      expect(screen.getByLabelText('Quantity')).toHaveValue('260');
      expect(screen.getByText('true')).toBeDefined();
      const requests = owned.transport.mock.calls.filter((call) => call[0] === '/api/example/save');
      expect(requests).toHaveLength(1);
      expect(requests[0][1].params).toEqual({
        targetRecordPid: 'order',
        expectedVersion: 1,
        lines: [{ linePid: 'line', quantity: '260' }],
      });
    },
  );
  it('preserves version wire values and does not invent singleton/legacy identity or version fields', () => {
    const raw = { order: { pid: 'order', rowVersion: '2', state: 'open' } };
    expect(() =>
      assertStrictDetailRefreshParent(raw, 'order', { pid: 'order', rowVersion: 1 }, 'order'),
    ).not.toThrow();
    expect(raw.order.rowVersion).toBe('2');
    expect(() =>
      assertStrictDetailRefreshParent({ name: 'Singleton' }, undefined, {}),
    ).not.toThrow();
    for (const value of [undefined, null, false, '', 1.5, Number.MAX_SAFE_INTEGER + 1])
      expect(() =>
        assertStrictDetailRefreshParent(
          { order: { pid: 'order', rowVersion: value } },
          'order',
          { rowVersion: 1 },
          'order',
        ),
      ).toThrow('version');
  });
  it('unconfigured refresh rejects and cannot continue a success flow', async () => {
    await expect(actionRegistry.execute('refresh', {})).rejects.toThrow(
      'Page refresh loader is unavailable',
    );
    const load = vi.fn();
    const reload = vi.fn().mockResolvedValue(undefined);
    await actionRegistry.execute('refresh', {
      loadData: load,
      dataSourceManager: { reload } as any,
      args: { target: 'rows' },
    });
    expect(reload).toHaveBeenCalledWith('rows');
    expect(load).not.toHaveBeenCalled();
  });
});
