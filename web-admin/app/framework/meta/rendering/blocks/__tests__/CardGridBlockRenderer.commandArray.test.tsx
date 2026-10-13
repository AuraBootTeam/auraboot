import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { DataSourceManager } from '../../../runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '../../../runtime/expression/context';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { businessFunctions } from '../../../runtime/expression/business-functions';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { fetchResult } from '~/shared/services/http-client';
import { CardGridBlockRenderer } from '../CardGridBlockRenderer';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({ handleAction: vi.fn() }),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'local' }),
  usePermission: () => true,
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showErrorToast: vi.fn(),
    showSuccessToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ loaded: true, getDictLabel: () => undefined }),
}));
vi.mock('../../components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => (
    <label>
      {props.label}
      <input
        aria-label={props.label}
        value={props.value ?? ''}
        onChange={(event) => props.onChange(event.target.value)}
      />
    </label>
  ),
}));

describe('CardGrid completed command arrays (hermetic mounted DOM)', () => {
  it('uses POST array transport, retains exact dirty draft through refresh, and renders typed403', async () => {
    const state = new ScopedStateManager({} as any);
    state.createScope('edit');
    const manager = new DataSourceManager(createExpressionContext({ form: {}, state: {} } as any));
    manager.register('lines', {
      type: 'api',
      method: 'post',
      endpoint: '/api/meta/commands/execute/example:view',
      autoFetch: false,
      adaptor: 'commandArray',
      dataPath: 'lines',
      rowProjection: {pid:'pid',name:'name',quantity:'quantity', taxIncluded:{paths:'taxIncluded',optional:true,omitWhen:['undefined','null','empty']}},
      rowIdentityField:'pid',
      params: { targetRecordPid: 'p' },
    });
    const runtime = {
      getContext: () => ({
        fn: businessFunctions,
        locale: 'en',
        t: (key: string) => key,
        ...state.getScope('edit'),
      }),
      getEvaluator: () => new ExpressionEvaluator(),
      getStateManager: () => state,
      getScopeId: () => 'edit',
      getSchema: () => ({ id: 'edit' }),
      getDataSourceManager: () => manager,
      triggerFieldLinkage: vi.fn(),
    } as any;
    const completed = (qty: string) => ({
      code: '0',
      data: {
        phaseReached: 'completed',
        data: { lines: [{ pid: 'p', name: 'Item', quantity: qty, taxIncluded:false, richDTO:{secret:'not-editable'} }] },
      },
    });
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('100.000000000000001') as any);
    await manager.fetch('lines');
    render(
      <MemoryRouter>
        <CardGridBlockRenderer
          runtime={runtime}
          block={
            {
              id: 'lines',
              blockType: 'card-grid',
              dataSource: 'lines',
              field: 'choices',
              titleField: 'name',
              fields: [{ field: 'quantity', label: 'Quantity', component: 'SmartInput' }],
            } as any
          }
        />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByLabelText('Quantity')).toHaveValue('100.000000000000001'),
    );
    expect(state.getFieldValue('edit','choices')).toEqual([{pid:'p',name:'Item',quantity:'100.000000000000001',taxIncluded:false}]);
    fireEvent.change(screen.getByLabelText('Quantity'), {
      target: { value: '60.000000000000001' },
    });
    vi.mocked(fetchResult).mockResolvedValueOnce(completed('999') as any);
    await act(async () => {
      await manager.reload('lines');
    });
    expect(screen.getByLabelText('Quantity')).toHaveValue('60.000000000000001');
    expect(
      businessFunctions.PROJECT_ROWS(
        state.getFieldValue('edit', 'choices'),
        { pid: 'pid', quantity: 'quantity' },
        'pid',
      ),
    ).toEqual([{ pid: 'p', quantity: '60.000000000000001' }]);
    vi.mocked(fetchResult).mockResolvedValueOnce({
      code: '403',
      message: 'Access forbidden',
      data: null,
    } as any);
    await act(async () => {
      await manager.reload('lines');
    });
    expect((manager.getState('lines')?.error as any).status).toBe(403);
    expect(screen.queryByText('Access forbidden')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument();
    expect(state.getFieldValue('edit', 'choices')[0].quantity).toBe('60.000000000000001');
    expect(vi.mocked(fetchResult).mock.calls.every((call) => call[1]?.method === 'post')).toBe(
      true,
    );
  });
});
