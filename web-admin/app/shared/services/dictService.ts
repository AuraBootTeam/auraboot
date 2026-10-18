import { internalRequest } from './http-client/internal-api';
import type { InternalRequestInput } from './http-client/internal-api';
import type { Result } from './http-client/types';
import type { DictCreateRequest, DictDTO, DictQueryRequest, DictUpdateRequest } from '~/types/dict';
import {
  dictionaryData,
  dictionaryItems,
  dictionaryPage,
  dictionaryTree,
  dictionaryView,
} from './dict-adapter';

function data<T>(result: Result<T>, message: string): T {
  if (result.code !== '0') throw new Error(result.message || result.desc || message);
  if (result.data === null) throw new Error(message);
  return result.data;
}
function success(result: Result<unknown>, message: string): void {
  if (result.code !== '0') throw new Error(result.message || result.desc || message);
}

export const dictService = {
  async create(body: DictCreateRequest, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('post', '/api/meta/dict', { body, request }),
        'Failed to create dictionary',
      ),
    );
  },
  async update(pid: string, body: DictUpdateRequest, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('put', '/api/meta/dict/{pid}', { path: { pid }, body, request }),
        'Failed to update dictionary',
      ),
    );
  },
  async replaceItems(
    pid: string,
    body: InternalRequestInput<'put', '/api/meta/dict/{pid}/items'>['body'],
    request?: Request,
  ) {
    return dictionaryView(
      data(
        await internalRequest('put', '/api/meta/dict/{pid}/items', {
          path: { pid },
          body,
          request,
        }),
        'Failed to replace dictionary items',
      ),
    );
  },
  async delete(pid: string, request?: Request): Promise<void> {
    success(
      await internalRequest('delete', '/api/meta/dict/{pid}', { path: { pid }, request }),
      'Failed to delete dictionary',
    );
  },
  async findByPid(pid: string, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('get', '/api/meta/dict/{pid}', { path: { pid }, request }),
        'Failed to fetch dictionary',
      ),
    );
  },
  async findByCode(code: string, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('get', '/api/meta/dict/by-code/{code}', { path: { code }, request }),
        'Failed to fetch dictionary',
      ),
    );
  },
  async query(query: DictQueryRequest, request?: Request) {
    return dictionaryPage(
      data(
        await internalRequest('get', '/api/meta/dict', { query, request }),
        'Failed to query dictionaries',
      ),
    );
  },
  async loadData(
    pid: string,
    versionStrategy = 'latest',
    pinnedVersion?: string,
    request?: Request,
  ) {
    return dictionaryData(
      data(
        await internalRequest('get', '/api/meta/dict/{pid}/data', {
          path: { pid },
          query: { versionStrategy, pinnedVersion },
          request,
        }),
        'Failed to load dictionary data',
      ),
    );
  },
  async publish(pid: string, versionNote?: string, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('post', '/api/meta/dict/{pid}/publish', {
          path: { pid },
          query: { versionNote },
          request,
        }),
        'Failed to publish dictionary',
      ),
    );
  },
  async unpublish(pid: string, request?: Request) {
    return dictionaryView(
      data(
        await internalRequest('post', '/api/meta/dict/{pid}/unpublish', { path: { pid }, request }),
        'Failed to unpublish dictionary',
      ),
    );
  },
  async getVersionHistory(code: string, request?: Request) {
    return data(
      await internalRequest('get', '/api/meta/dict/{code}/versions', { path: { code }, request }),
      'Failed to fetch version history',
    ).map(dictionaryView);
  },
  async getCascadeChildren(pid: string, parentValue?: string, request?: Request) {
    return dictionaryItems(
      data(
        await internalRequest('get', '/api/meta/dict/{pid}/cascade/children', {
          path: { pid },
          query: { parentValue },
          request,
        }),
        'Failed to fetch cascade children',
      ),
    );
  },
  async buildCascadeTree(pid: string, request?: Request) {
    return dictionaryTree(
      data(
        await internalRequest('get', '/api/meta/dict/{pid}/cascade/tree', {
          path: { pid },
          request,
        }),
        'Failed to build cascade tree',
      ),
    );
  },
  async getStatistics(request?: Request) {
    return data(
      await internalRequest('get', '/api/meta/dict/statistics', { request }),
      'Failed to fetch statistics',
    );
  },
  async validateConfig(code: string, request?: Request) {
    return data(
      await internalRequest('get', '/api/meta/dict/{code}/validate', { path: { code }, request }),
      'Failed to validate dictionary',
    );
  },
  async checkCodeUnique(code: string, excludePid?: string, request?: Request) {
    return data(
      await internalRequest('get', '/api/meta/dict/code/{code}/unique', {
        path: { code },
        query: { excludePid },
        request,
      }),
      'Failed to check code uniqueness',
    );
  },
  async batchDelete(pids: string[], request?: Request) {
    return data(
      await internalRequest('delete', '/api/meta/dict/batch', { query: { pids }, request }),
      'Failed to batch delete dictionaries',
    );
  },
  async search(keyword: string, request?: Request) {
    return data(
      await internalRequest('get', '/api/meta/dict/search', { query: { keyword }, request }),
      'Failed to search dictionaries',
    ).map(dictionaryView);
  },
  async findAll(request?: Request): Promise<DictDTO[]> {
    return (await dictService.query({ pageNum: 1, pageSize: 1000, status: 'published' }, request))
      .records;
  },
};
