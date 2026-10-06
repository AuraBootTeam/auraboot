import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { TableBlockRenderer } from '../TableBlockRenderer';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async (url: string) => {
    if (url.includes('/bom_match_reason_code/')) {
      return {
        code: '0',
        data: [
          {
            value: 'match_spec_package',
            label: '规格+封装精确命中',
            extension: { color: 'green' },
          },
          { value: 'match_multi_candidate', label: '多候选待选择', extension: { color: 'yellow' } },
          { value: 'no_library_match', label: '物料库无匹配', extension: { color: 'red' } },
        ],
      };
    }
    return { code: '0', data: [] };
  }),
}));

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'token' }),
}));

vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
}));

function makeRuntime(rows: Record<string, unknown>[], locale = 'zh-CN'): SchemaRuntime {
  const context: Record<string, unknown> = {
    locale,
    t: (key: string) => (key === 'common.loading' ? '加载中' : key),
    state: {},
  };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: vi.fn(() => true),
      evaluateTemplate: vi.fn((template: string) => template),
      evaluateObject: vi.fn((value: unknown) => value),
    }),
    getDataSourceManager: () => ({
      getData: () => rows,
      has: () => true,
      register: vi.fn(),
      reload: vi.fn(),
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope-table-status-pill',
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
  } as unknown as SchemaRuntime;
}

function tableBlock(renderType?: string): BlockConfig {
  return {
    id: 'tbl',
    blockType: 'table',
    dataSource: 'rows',
    columns: [
      {
        field: 'reason',
        label: '状态',
        dictCode: 'bom_match_reason_code',
        valueType: 'tag',
        ...(renderType ? { renderType } : {}),
      },
    ],
  } as unknown as BlockConfig;
}

describe('TableBlockRenderer status-pill renderType', () => {
  it('keeps dictionary codes hidden until delayed labels arrive', async () => {
    let resolveDictionary!: (result: any) => void;
    vi.mocked(fetchResult).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveDictionary = resolve;
        }),
    );
    const runtime = makeRuntime([{ pid: 'r1', reason: 'match_spec_package' }]);
    render(<TableBlockRenderer block={tableBlock()} runtime={runtime} />);

    expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('加载中');
    await act(async () =>
      resolveDictionary({
        code: '0',
        data: [{ value: 'match_spec_package', label: '规格+封装精确命中' }],
      }),
    );
    expect(await screen.findByText('规格+封装精确命中')).toBeInTheDocument();
    expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
  });

  it('keeps a newly configured dictionary pending after an earlier dictionary loaded', async () => {
    const runtime = makeRuntime([{ pid: 'r1', reason: 'match_spec_package' }]);
    const { rerender } = render(<TableBlockRenderer block={tableBlock()} runtime={runtime} />);
    expect(await screen.findByText('规格+封装精确命中')).toBeInTheDocument();

    let resolveDictionary!: (result: any) => void;
    vi.mocked(fetchResult).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveDictionary = resolve;
        }),
    );
    const changedBlock = tableBlock();
    if (!Array.isArray(changedBlock.columns)) {
      throw new Error('Dictionary test requires explicit table columns');
    }
    changedBlock.columns[0].dictCode = 'new_reason_dictionary';
    rerender(<TableBlockRenderer block={changedBlock} runtime={runtime} />);

    expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('加载中');
    await act(async () =>
      resolveDictionary({
        code: '0',
        data: [{ value: 'match_spec_package', label: '新匹配说明' }],
      }),
    );
    expect(await screen.findByText('新匹配说明')).toBeInTheDocument();
    expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
  });

  it.each(['business', 'network'])(
    'hides codes when dictionary loading fails: %s',
    async (failure) => {
      if (failure === 'network') {
        vi.mocked(fetchResult).mockRejectedValueOnce(
          new Error('internal dictionary transport failure'),
        );
      } else {
        vi.mocked(fetchResult).mockResolvedValueOnce({ code: '403', data: null } as any);
      }
      render(
        <TableBlockRenderer
          block={tableBlock()}
          runtime={makeRuntime([{ pid: 'r1', reason: 'match_spec_package' }])}
        />,
      );
      expect(await screen.findByText('标签加载失败')).toBeInTheDocument();
      expect(screen.getByRole('alert')).toHaveAttribute('title', '请刷新页面重试');
      expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
      expect(screen.queryByText('internal dictionary transport failure')).not.toBeInTheDocument();
    },
  );

  it.each([{ items: [] }, { items: [{ value: 'unmapped_code', label: '' }] }])(
    'shows a missing-label state without exposing the stored value',
    async ({ items }) => {
      vi.mocked(fetchResult).mockResolvedValueOnce({ code: '0', data: items } as any);
      render(
        <TableBlockRenderer
          block={tableBlock()}
          runtime={makeRuntime([{ pid: 'r1', reason: 'unmapped_code' }])}
        />,
      );
      expect(await screen.findByText('标签未配置')).toBeInTheDocument();
      expect(screen.queryByText('unmapped_code')).not.toBeInTheDocument();
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
    },
  );

  it('localizes dictionary failure fallback for English without translations', async () => {
    vi.mocked(fetchResult).mockResolvedValueOnce({ code: '403', data: null } as any);
    render(
      <TableBlockRenderer
        block={tableBlock()}
        runtime={makeRuntime([{ pid: 'r1', reason: 'match_spec_package' }], 'en-US')}
      />,
    );
    expect(await screen.findByText('Labels failed to load')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveAttribute('title', 'Refresh the page to retry');
    expect(screen.queryByText('match_spec_package')).not.toBeInTheDocument();
  });

  it('renders dict-coded tags as semantic dots by default', async () => {
    render(
      <TableBlockRenderer
        block={tableBlock()}
        runtime={makeRuntime([{ pid: 'r1', reason: 'match_spec_package' }])}
      />,
    );

    expect(await screen.findByText('规格+封装精确命中')).toBeInTheDocument();
    expect(screen.queryByTestId('table-status-pill')).not.toBeInTheDocument();
  });

  it('renders only opt-in dict-coded tags as status pills', async () => {
    render(
      <TableBlockRenderer
        block={tableBlock('status-pill')}
        runtime={makeRuntime([{ pid: 'r1', reason: 'match_multi_candidate' }])}
      />,
    );

    const pill = await screen.findByTestId('table-status-pill');
    expect(pill).toHaveTextContent('多候选待选择');
    expect(pill).toHaveClass('bg-status-amber-bg');
  });

  it('formats date and datetime cells with the runtime locale', () => {
    const date = '2026-08-09T10:20:30Z';
    const block = {
      id: 'localized-dates',
      blockType: 'table',
      dataSource: 'rows',
      columns: [
        { field: 'dueDate', label: '日期', valueType: 'date' },
        { field: 'updatedAt', label: '时间', valueType: 'datetime' },
      ],
    } as unknown as BlockConfig;
    render(
      <TableBlockRenderer
        block={block}
        runtime={makeRuntime([{ pid: 'r1', dueDate: date, updatedAt: date }], 'zh-CN')}
      />,
    );

    expect(screen.getByText(new Date(date).toLocaleDateString('zh-CN'))).toBeInTheDocument();
    expect(screen.getByText(new Date(date).toLocaleString('zh-CN'))).toBeInTheDocument();
  });

  it('prefers a backend-enriched reference display label over the raw pid', () => {
    const block = {
      id: 'reference-label',
      blockType: 'table',
      dataSource: 'rows',
      columns: [{ field: 'crm_fcst_owner', label: '提交人' }],
    } as unknown as BlockConfig;
    render(
      <TableBlockRenderer
        block={block}
        runtime={makeRuntime([
          {
            pid: 'forecast-1',
            crm_fcst_owner: '01KZKV0T575BDJ3B3FT2QHBKDW',
            crm_fcst_owner_display: 'Admin User',
          },
        ])}
      />,
    );

    expect(screen.getByText('Admin User')).toBeInTheDocument();
    expect(screen.queryByText('01KZKV0T575BDJ3B3FT2QHBKDW')).not.toBeInTheDocument();
  });
});
