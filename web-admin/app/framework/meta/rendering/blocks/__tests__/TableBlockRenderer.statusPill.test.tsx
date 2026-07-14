import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { TableBlockRenderer } from '../TableBlockRenderer';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async (url: string) => {
    if (url.includes('/bom_match_reason_code/')) {
      return {
        code: '0',
        data: [
          { value: 'match_spec_package', label: '规格+封装精确命中', extension: { color: 'green' } },
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

function makeRuntime(rows: Record<string, unknown>[]): SchemaRuntime {
  const context: Record<string, unknown> = { locale: 'zh-CN', t: (key: string) => key, state: {} };
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
});
