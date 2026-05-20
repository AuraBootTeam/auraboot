import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock, putMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  putMock: vi.fn(),
}));

vi.mock('../http-client', () => ({
  get: getMock,
  post: postMock,
  put: putMock,
  del: vi.fn(),
}));

import { namedQueryService } from '../namedQueryService';

describe('namedQueryService.query', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
  });

  it('normalizes backend pagination fields for the named query list page', async () => {
    getMock.mockResolvedValue({
      code: '0',
      desc: 'OK',
      success: true,
      data: {
        records: [{ pid: 'nq-1', code: 'query_1', title: 'Query 1', fromSql: 'select 1' }],
        total: 1,
        page: 3,
        pageSize: 50,
        totalPages: 9,
      },
    });

    const result = await namedQueryService.query({ pageNum: 3, pageSize: 50 });

    expect(result.records).toHaveLength(1);
    expect(result.current).toBe(3);
    expect(result.size).toBe(50);
    expect(result.pages).toBe(9);
    expect(result.total).toBe(1);
  });
});

describe('namedQueryService.execute', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
  });

  it('posts backend execute DTO fields and normalizes pagination', async () => {
    postMock.mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: {
        records: [{ name: 'System overview', page_key: 'system_overview' }],
        total: 1,
        current: 1,
        size: 20,
        pages: 1,
      },
    });

    const result = await namedQueryService.execute('udw_pages', {
      parameters: { keyword: 'system' },
      whereConditions: { name: { operator: 'contains', value: 'system' } },
      orderConditions: { name: 'asc' },
      page: 1,
      size: 20,
      executeQuery: true,
    });

    expect(postMock).toHaveBeenCalledWith(
      '/api/meta/named-queries/udw_pages/execute',
      {
        parameters: { keyword: 'system' },
        whereConditions: { name: { operator: 'contains', value: 'system' } },
        orderConditions: { name: 'asc' },
        page: 1,
        size: 20,
        executeQuery: true,
      },
      undefined,
      undefined,
    );
    expect(result.records).toHaveLength(1);
    expect(result.records[0].page_key).toBe('system_overview');
  });

  it('preserves backend error code for runtime permission classification', async () => {
    postMock.mockResolvedValue({
      code: '403',
      desc: 'Access forbidden',
      message: 'Access forbidden',
      data: null,
      context: { permission: 'meta.query.read' },
    });

    await expect(
      namedQueryService.execute('restricted_query', { page: 1, size: 20 }),
    ).rejects.toMatchObject({
      message: 'Access forbidden',
      code: '403',
      context: { permission: 'meta.query.read' },
    });
  });
});

describe('namedQueryService.updateStatus', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    putMock.mockReset();
  });

  it('sends status in the request body expected by the backend controller', async () => {
    putMock.mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: {
        pid: 'nq-1',
        code: 'query_1',
        title: 'Query 1',
        fromSql: 'ab_page_schema p',
        status: 'testing',
        createdAt: '',
        updatedAt: '',
      },
    });

    const result = await namedQueryService.updateStatus('nq-1', 'testing');

    expect(putMock).toHaveBeenCalledWith(
      '/api/meta/named-queries/nq-1/status',
      { status: 'testing' },
      undefined,
      undefined,
    );
    expect(result.status).toBe('testing');
  });
});
