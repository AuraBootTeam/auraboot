import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { fetchResult } from '~/shared/services/http-client';
import { useSchemaLoader } from '../useSchemaLoader';

const mockFetchResult = vi.mocked(fetchResult);

describe('useSchemaLoader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads PageSchemaDTO through canonicalization before exposing schema', async () => {
    mockFetchResult.mockResolvedValue({
      code: '0',
      message: '',
      data: {
        pageKey: 'loader_contract_list',
        modelCode: 'loader_contract',
        modelCategory: null,
        kind: 'list',
        layout: { type: 'stack' },
        blocks: [
          {
            id: 'toolbar',
            blockType: 'toolbar',
            buttons: [
              {
                code: 'create',
                navigateTo: 'loader_contract_form',
              },
            ],
          },
          {
            id: 'table',
            blockType: 'table',
            table: {
              columns: [
                {
                  field: 'code',
                  valueType: 'loader_contract_code',
                },
              ],
            },
          },
        ],
        'name:zh-CN': '加载器契约',
        'name:en': 'Loader Contract',
      },
    } as any);

    const { result } = renderHook(() => useSchemaLoader({ pageKey: 'loader_contract_list' }));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(mockFetchResult).toHaveBeenCalledWith('/api/pages/key/loader_contract_list', {
      method: 'get',
      token: undefined,
    });
    expect(result.current.error).toBeNull();
    expect(result.current.schema).toMatchObject({
      id: 'loader_contract_list',
      version: '1.0.0',
      pageKey: 'loader_contract_list',
      title: {
        'zh-CN': '加载器契约',
        'en-US': 'Loader Contract',
      },
    });

    const toolbarButton = (result.current.schema?.blocks[0] as any).buttons[0];
    expect(toolbarButton.action).toEqual({
      type: 'navigate',
      to: 'loader_contract_form',
    });
    expect(toolbarButton.navigateTo).toBeUndefined();

    const column = (result.current.schema?.blocks[1] as any).table.columns[0];
    expect(column).toMatchObject({
      field: 'code',
      cellRenderer: 'loader_contract_code',
    });
    expect(column.valueType).toBeUndefined();
  });
});
