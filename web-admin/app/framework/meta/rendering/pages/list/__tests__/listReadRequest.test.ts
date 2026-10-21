import { describe, expect, it } from 'vitest';
import { completeListReadRequest } from '../listReadRequest';

describe('list search transport contract', () => {
  it.each([false, true])('carries the latest keyword with the existing page/filter/sort query (api=%s)', (apiDatasource) => {
    const request = { endpoint: '/list', queryParams: { pageNum: 1, status: 'draft', sortField: 'updated_at' } };
    const result = completeListReadRequest(request, { keyword: '  project-form  ', queryCode: 'project_rows', apiDatasource, retry: false });
    expect(result.queryParams).toEqual({ ...request.queryParams, keyword: 'project-form', ...(!apiDatasource ? { queryCode: 'project_rows' } : {}) });
    expect(request.queryParams).not.toHaveProperty('keyword');
  });
  it('clearing a search removes the old keyword while preserving other parameters', () => {
    const result = completeListReadRequest({ queryParams: { keyword: 'old', page: 0, status: 'draft' } }, { keyword: '  ', apiDatasource: true, retry: false });
    expect(result.queryParams).toEqual({ page: 0, status: 'draft' });
  });
  it('retry preserves the sealed failed query despite pending keyword or source changes', () => {
    const failed = { queryParams: { keyword: 'submitted', page: 2, size: 50, status: 'draft' } };
    expect(completeListReadRequest(failed, { keyword: 'pending', queryCode: 'different', apiDatasource: true, retry: true })).toEqual(failed);
  });
});
