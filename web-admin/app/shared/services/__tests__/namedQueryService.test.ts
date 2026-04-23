import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
}));

vi.mock('../http-client', () => ({
  get: getMock,
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { namedQueryService } from '../namedQueryService';

describe('namedQueryService.query', () => {
  beforeEach(() => {
    getMock.mockReset();
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
