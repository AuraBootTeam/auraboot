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
        if (expression.includes('row.changed == true')) return values.row?.changed === true;
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

  it('lets matching row backgrounds override the default card while keeping selection and unmatched rows', () => {
    const changed = { ...row, changed: true };
    const unchanged = { ...row, pid: 'pool-item-2', changed: false };
    const schema = runtime([changed, unchanged]);
    render(<TableBlockRenderer block={{ ...block,
      rowClassRules: [{ when: '${row.changed == true}', className: 'bg-amber-50' }],
    } as any} runtime={schema} />);
    const highlighted = screen.getByTestId('table-mobile-card-pool-item-1');
    const ordinary = screen.getByTestId('table-mobile-card-pool-item-2');
    expect(highlighted).toHaveClass('bg-amber-50');
    expect(highlighted).not.toHaveClass('bg-panel');
    expect(highlighted).toHaveClass('border-accent', 'ring-2');
    expect(ordinary).toHaveClass('bg-panel');
    expect(ordinary).not.toHaveClass('bg-amber-50');
    fireEvent.click(ordinary);
    expect(schema.getContext().state.selectedPoolItem.pid).toBe('pool-item-2');
    expect(handleActionSpy).not.toHaveBeenCalled();
  });

  it('preserves configured localized empty guidance on mobile without adding actions', () => {
    render(<TableBlockRenderer block={{ ...block, empty: {
      title: { 'zh-CN': '当前没有可查看的记录', en: 'No records' },
      description: { 'zh-CN': '请返回修改记录并选择一次正式提交。', en: 'Select a formal submission from history.' },
    } } as any} runtime={runtime([])} />);
    const empty = screen.getByTestId('table-mobile-empty');
    expect(empty).toHaveTextContent('当前没有可查看的记录');
    expect(empty).toHaveTextContent('请返回修改记录并选择一次正式提交。');
    expect(empty).not.toHaveTextContent('Select a formal submission');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

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
