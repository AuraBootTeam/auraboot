import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

const handleActionSpy = vi.fn();

vi.mock('~/framework/meta/rendering/components/ResponsiveBlockLayout', () => ({
  useMediaQuery: () => true,
}));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({
    handleAction: handleActionSpy,
    loading: false,
    error: null,
    setError: vi.fn(),
  }),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'token', hasPermission: () => true }),
}));
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async () => ({ code: '0', data: [] })),
}));

import { TableBlockRenderer } from '../TableBlockRenderer';

function runtime(rows: Record<string, unknown>[]): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'zh-CN',
    t: (key: string) => key,
    state: {},
  };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expression: string, values: Record<string, any>) => {
        if (expression.includes("operational_state == 'ready'")) {
          return values.record?.operational_state === 'ready';
        }
        return true;
      },
      evaluateTemplate: (value: any) => value,
      evaluateObject: (value: any) => value,
    }),
    getDataSourceManager: () => ({
      getData: () => rows,
      getState: () => ({ data: rows, loading: false, error: null }),
      subscribe: () => vi.fn(),
      reload: vi.fn(),
    }),
    getStateManager: () => ({
      updateState: (_scope: string, key: string, value: unknown) => {
        context.state[key] = value;
      },
      getContext: () => context,
    }),
    getScopeId: () => 'mobile-card-scope',
    getSchema: () => ({ id: 'customer-pool', modelCode: 'crm_customer_pool_item_common' }),
  } as unknown as SchemaRuntime;
}

const row = {
  pid: 'pool-item-1',
  pool_name: '华南客户公海',
  crm_cpi_account_name: '金穗精密设备',
  crm_cpi_rating: 'A',
  crm_cpi_industry: '制造业',
  operational_state: 'ready',
  crm_cpi_claim_release_at: '2026-08-20T09:00:00Z',
  owner_name: '',
};

const block = {
  id: 'customer-pool-queue',
  blockType: 'table',
  dataSource: 'poolQueue',
  selection: { mode: 'single', bind: 'selectedPoolItem', defaultFirst: true },
  mobileCard: {
    titleField: 'crm_cpi_account_name',
    eyebrowField: 'pool_name',
    statusField: 'operational_state',
    fields: ['crm_cpi_rating', 'crm_cpi_industry', 'crm_cpi_claim_release_at', 'owner_name'],
    actionCodes: ['claim', 'assign', 'details'],
  },
  columns: [
    { field: 'pool_name', label: '客户公海' },
    { field: 'crm_cpi_account_name', label: '客户名称' },
    { field: 'crm_cpi_rating', label: '评级' },
    { field: 'crm_cpi_industry', label: '行业' },
    { field: 'operational_state', label: '领取状态' },
    { field: 'crm_cpi_claim_release_at', label: '可领取时间', valueType: 'datetime' },
    { field: 'owner_name', label: '当前获配人' },
  ],
  rowActions: [
    {
      code: 'claim',
      label: '领取',
      variant: 'primary',
      mobileOnly: true,
      visibleWhen: "record.operational_state == 'ready'",
      action: { type: 'command', command: 'crm:claim_pool_customer' },
    },
    {
      code: 'assign',
      label: '分配',
      mobileOnly: true,
      action: { type: 'command', command: 'crm:assign_pool_customer' },
    },
    {
      code: 'details',
      label: '详情',
      action: { type: 'navigate', to: 'crm_customer_pool_item_detail' },
    },
  ],
} as any;

describe('TableBlockRenderer mobile cards', () => {
  beforeEach(() => handleActionSpy.mockReset());

  it('replaces the wide table with task cards and direct actions on mobile', () => {
    render(<TableBlockRenderer block={block} runtime={runtime([row])} />);

    expect(screen.getByTestId('table-mobile-cards')).toBeInTheDocument();
    expect(screen.queryByTestId('table-block')).not.toBeInTheDocument();
    expect(screen.getByText('金穗精密设备')).toBeInTheDocument();
    expect(screen.getByText('华南客户公海')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '领取' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '分配' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '详情' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '领取' }));
    expect(handleActionSpy).toHaveBeenCalledWith(expect.objectContaining({ code: 'claim' }), row);
  });

  it('keeps ordinary table blocks unchanged when no mobile-card contract is configured', () => {
    render(
      <TableBlockRenderer
        block={{ ...block, mobileCard: undefined } as any}
        runtime={runtime([row])}
      />,
    );

    expect(screen.getByTestId('table-block')).toBeInTheDocument();
    expect(screen.queryByTestId('table-mobile-cards')).not.toBeInTheDocument();
  });
});
