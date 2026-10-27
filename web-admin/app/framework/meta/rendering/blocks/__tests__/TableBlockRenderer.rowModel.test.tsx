import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { TableBlockRenderer } from '../TableBlockRenderer';
import { resolveRowActionModel } from '../rowActionModel';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';

const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: any[]) => transport(...args),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'fixture', hasPermission: () => true }),
}));
vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({ timezone: 'UTC', formats: {} }),
}));

function fixture(modelCode: unknown = 'child_model') {
  const context = createExpressionContext({ locale: 'en', t: (key: string) => key } as any);
  const manager = new DataSourceManager(context);
  manager.register('children', { type: 'static', data: [], modelCode } as any);
  const row = { pid: '01ARZ3NDEKTSV4RRFFQ69G5FAV', row_version: 2, quantity: 1 };
  vi.spyOn(manager, 'getData').mockReturnValue([row]);
  const runtime = {
    getContext: () => context,
    getSchema: () => ({ id: 'parent-list', modelCode: 'parent_model' }),
    getDataSourceManager: () => manager,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (v: any) => v,
      evaluateObject: (v: any) => v,
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'fixture',
    getFlowRunner: () => null,
  } as any;
  const block = {
    id: 'children-table',
    blockType: 'table',
    dataSource: 'children',
    columns: [{ field: 'quantity', label: 'Quantity' }],
    rowActions: [
      {
        code: 'adjust',
        label: 'Adjust child',
        action: {
          type: 'command',
          command: 'child_model:adjust',
          inputFields: [{ field: 'quantity', type: 'number', label: 'Quantity' }],
        },
      },
    ],
  } as any;
  return { runtime, block, row };
}

describe('External table row command model', () => {
  it('reads the child model, pins its fresh version and submits one exact command without retrying 409', async () => {
    const { runtime, block, row } = fixture();
    transport.mockReset();
    transport.mockResolvedValueOnce({ code: '0', data: { ...row, row_version: 4, quantity: 3 } });
    transport.mockResolvedValueOnce({
      code: '41001',
      message: 'Version conflict',
      httpStatus: 409,
    });
    const listener = vi.fn((event: Event) => {
      const detail = (event as CustomEvent).detail;
      detail.onSubmit({ quantity: 5 });
    });
    window.addEventListener('dialog:form', listener);
    try {
      render(
        <MemoryRouter>
          <TableBlockRenderer block={block} runtime={runtime} />
        </MemoryRouter>,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Adjust child' }));
      await waitFor(() => expect(transport).toHaveBeenCalledTimes(2));
      expect(listener).toHaveBeenCalledOnce();
      expect(transport).toHaveBeenNthCalledWith(
        1,
        `/api/dynamic/child_model/${row.pid}`,
        expect.anything(),
      );
      expect(transport).toHaveBeenNthCalledWith(
        2,
        '/api/meta/commands/execute/child_model:adjust',
        expect.objectContaining({
          params: expect.objectContaining({
            expectedVersion: 4,
            payload: expect.objectContaining({ quantity: 5 }),
          }),
        }),
      );
      expect(JSON.stringify(transport.mock.calls)).not.toContain('/api/dynamic/parent_model/');
    } finally {
      window.removeEventListener('dialog:form', listener);
    }
  });

  it('preserves legacy page-model fallback only when the source does not declare a model', () => {
    const { runtime, block } = fixture();
    expect(resolveRowActionModel(block, runtime)).toBe('child_model');
    expect(
      resolveRowActionModel({ ...block, dataSource: { modelCode: 'inline_child' } }, runtime),
    ).toBe('inline_child');
    expect(resolveRowActionModel({ ...block, dataSource: undefined }, runtime)).toBe(
      'parent_model',
    );
    for (const modelCode of ['', ' child ', null, 17]) {
      expect(() => resolveRowActionModel({ ...block, dataSource: { modelCode } }, runtime)).toThrow(
        'invalid data-source modelCode',
      );
    }
  });
});
