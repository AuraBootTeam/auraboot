import { beforeEach, describe, expect, it, vi } from 'vitest';

const { callMock } = vi.hoisted(() => ({ callMock: vi.fn() }));
vi.mock('../http-client/internal-api', () => ({ internalRequest: callMock }));
import { dictService } from '../dictService';
import { dictionaryData, dictionaryPage, dictionaryTree, dictionaryView } from '../dict-adapter';

const dto = {
  pid: 'dict-pid',
  code: 'STATUS',
  name: 'Status',
  dictType: 'simple',
  status: 'published',
  updatedAt: '2026-10-18T00:00:00',
};
const item = {
  value: 'active',
  label: 'Active',
  enabled: true,
  sortOrder: 2,
  extension: { color: 'green' },
};
const page = { records: [dto], total: 1, size: 20, current: 1, pages: 1 };
const body = { code: 'STATUS', name: 'Status', dictType: 'simple', sourceType: 'STATIC' };
const cases = [
  {
    name: 'create',
    invoke: () => dictService.create(body),
    method: 'post',
    path: '/api/meta/dict',
    input: { body, request: undefined },
    data: dto,
  },
  {
    name: 'update',
    invoke: () => dictService.update('dict-pid', { name: 'Updated' }),
    method: 'put',
    path: '/api/meta/dict/{pid}',
    input: { path: { pid: 'dict-pid' }, body: { name: 'Updated' }, request: undefined },
    data: dto,
  },
  {
    name: 'replace items',
    invoke: () => dictService.replaceItems('dict-pid', []),
    method: 'put',
    path: '/api/meta/dict/{pid}/items',
    input: { path: { pid: 'dict-pid' }, body: [], request: undefined },
    data: dto,
  },
  {
    name: 'delete',
    invoke: () => dictService.delete('dict-pid'),
    method: 'delete',
    path: '/api/meta/dict/{pid}',
    input: { path: { pid: 'dict-pid' }, request: undefined },
    data: null,
  },
  {
    name: 'find by pid',
    invoke: () => dictService.findByPid('dict-pid'),
    method: 'get',
    path: '/api/meta/dict/{pid}',
    input: { path: { pid: 'dict-pid' }, request: undefined },
    data: dto,
  },
  {
    name: 'find by code',
    invoke: () => dictService.findByCode('STATUS'),
    method: 'get',
    path: '/api/meta/dict/by-code/{code}',
    input: { path: { code: 'STATUS' }, request: undefined },
    data: dto,
  },
  {
    name: 'query',
    invoke: () => dictService.query({ pageNum: 1, pageSize: 20 }),
    method: 'get',
    path: '/api/meta/dict',
    input: { query: { pageNum: 1, pageSize: 20 }, request: undefined },
    data: page,
  },
  {
    name: 'load data',
    invoke: () => dictService.loadData('dict-pid'),
    method: 'get',
    path: '/api/meta/dict/{pid}/data',
    input: {
      path: { pid: 'dict-pid' },
      query: { versionStrategy: 'latest', pinnedVersion: undefined },
      request: undefined,
    },
    data: { success: true, items: [item] },
  },
  {
    name: 'publish',
    invoke: () => dictService.publish('dict-pid', 'Release'),
    method: 'post',
    path: '/api/meta/dict/{pid}/publish',
    input: { path: { pid: 'dict-pid' }, query: { versionNote: 'Release' }, request: undefined },
    data: dto,
  },
  {
    name: 'unpublish',
    invoke: () => dictService.unpublish('dict-pid'),
    method: 'post',
    path: '/api/meta/dict/{pid}/unpublish',
    input: { path: { pid: 'dict-pid' }, request: undefined },
    data: dto,
  },
  {
    name: 'version history',
    invoke: () => dictService.getVersionHistory('STATUS'),
    method: 'get',
    path: '/api/meta/dict/{code}/versions',
    input: { path: { code: 'STATUS' }, request: undefined },
    data: [dto],
  },
  {
    name: 'cascade children',
    invoke: () => dictService.getCascadeChildren('dict-pid', 'parent'),
    method: 'get',
    path: '/api/meta/dict/{pid}/cascade/children',
    input: { path: { pid: 'dict-pid' }, query: { parentValue: 'parent' }, request: undefined },
    data: [item],
  },
  {
    name: 'cascade tree',
    invoke: () => dictService.buildCascadeTree('dict-pid'),
    method: 'get',
    path: '/api/meta/dict/{pid}/cascade/tree',
    input: { path: { pid: 'dict-pid' }, request: undefined },
    data: { value: 'root', label: 'Status', children: [] },
  },
  {
    name: 'statistics',
    invoke: () => dictService.getStatistics(),
    method: 'get',
    path: '/api/meta/dict/statistics',
    input: { request: undefined },
    data: { totalCount: 1 },
  },
  {
    name: 'validate config',
    invoke: () => dictService.validateConfig('dict-pid'),
    method: 'get',
    path: '/api/meta/dict/{pid}/validate',
    input: { path: { pid: 'dict-pid' }, request: undefined },
    data: { valid: false, errors: ['invalid'], warnings: [] },
  },
  {
    name: 'code uniqueness false',
    invoke: () => dictService.checkCodeUnique('STATUS', 'dict-pid'),
    method: 'get',
    path: '/api/meta/dict/code/{code}/unique',
    input: { path: { code: 'STATUS' }, query: { excludePid: 'dict-pid' }, request: undefined },
    data: false,
  },
  {
    name: 'batch delete zero',
    invoke: () => dictService.batchDelete(['a', 'b']),
    method: 'delete',
    path: '/api/meta/dict/batch',
    input: { query: { pids: ['a', 'b'] }, request: undefined },
    data: 0,
  },
  {
    name: 'search',
    invoke: () => dictService.search('Sta'),
    method: 'get',
    path: '/api/meta/dict/search',
    input: { query: { keyword: 'Sta' }, request: undefined },
    data: [dto],
  },
  {
    name: 'find all',
    invoke: () => dictService.findAll(),
    method: 'get',
    path: '/api/meta/dict',
    input: { query: { pageNum: 1, pageSize: 1000, status: 'published' }, request: undefined },
    data: page,
  },
];

describe('generated dictionary operations', () => {
  beforeEach(() => callMock.mockReset());
  it.each(cases)(
    '$name uses its declared operation and input slots',
    async ({ invoke, method, path, input, data }) => {
      callMock.mockResolvedValue({ code: '0', desc: 'OK', data });
      const result = await invoke();
      expect(callMock).toHaveBeenCalledExactlyOnceWith(method, path, input);
      if (data === false || data === 0) expect(result).toBe(data);
      if (data === null) expect(result).toBeUndefined();
      if (data === dto) expect(result).toEqual(dto);
    },
  );
  it('forwards the SSR request identity separately from the operation body', async () => {
    const request = new Request('http://frontend.invalid/meta/dict');
    callMock.mockResolvedValue({ code: '0', desc: 'OK', data: dto });
    await dictService.create(body, request);
    expect(callMock).toHaveBeenCalledWith('post', '/api/meta/dict', { body, request });
  });
  it('uses the authoritative code and refuses missing successful data', async () => {
    callMock.mockResolvedValue({ code: '403', success: true, message: 'Denied', data: dto });
    await expect(dictService.create(body)).rejects.toThrow('Denied');
    callMock.mockResolvedValue({ code: '0', desc: 'OK', data: null });
    await expect(dictService.findByPid('dict-pid')).rejects.toThrow('Failed to fetch dictionary');
  });
});

describe('dictionary wire to editor projections', () => {
  it('accepts only the producer empty root while refusing malformed children', () => {
    expect(dictionaryTree({ value: null, label: null, children: [] })).toBeUndefined();
    expect(() =>
      dictionaryTree({
        value: 'root',
        label: 'Root',
        children: [{ value: null, label: null, children: [] }],
      }),
    ).toThrow('value');
    expect(() => dictionaryTree({ children: [] })).toThrow('value');
  });
  it('rejects missing identity/display and pagination fields rather than inventing defaults', () => {
    expect(() => dictionaryView({ pid: 'dict-pid' })).toThrow('code');
    expect(() => dictionaryPage({ records: [] })).toThrow('total');
    expect(() => dictionaryTree({ value: 'root' })).toThrow('label');
    expect(() => dictionaryView({ ...dto, status: 'unknown' })).toThrow(
      'Unsupported dictionary display status',
    );
  });
  it('maps wire ordering/enabled fields to editor state and preserves extension JSON', () => {
    const result = dictionaryData({
      success: true,
      code: 'STATUS',
      version: '3',
      loadTimestamp: 123,
      items: [item],
    });
    expect(result).toMatchObject({
      code: 'STATUS',
      version: '3',
      loadTimestamp: 123,
      items: [
        {
          value: 'active',
          label: 'Active',
          order: 2,
          disabled: false,
          extension: { color: 'green' },
        },
      ],
    });
    expect(result).not.toHaveProperty('dictCode');
    expect(result).not.toHaveProperty('loadedAt');
    expect(() =>
      dictionaryData({ success: false, errorMessage: 'Load failed', items: [] }),
    ).toThrow('Load failed');
    expect(() =>
      dictionaryData({
        success: true,
        items: [{ value: 'x', label: 'X', extension: ['invalid'] }],
      }),
    ).toThrow('JSON object');
  });
});
