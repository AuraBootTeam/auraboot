import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  internalRequest,
  serializeInternalQuery,
  serializeInternalFormData,
} from '../internal-api';
import { fetchResult } from '../HttpClient';
import {
  activateAuthoringPreviewGuard,
  resetAuthoringPreviewGuardForTests,
} from '../AuthoringPreviewGuard';

vi.mock('~/shared/services/session', () => ({
  sessionStorage: {
    getSession: vi.fn(async () => ({ get: vi.fn(), set: vi.fn(), unset: vi.fn() })),
  },
}));

describe('generated internal operation transport', () => {
  it('serializes repeated scalar and binary parts without losing false, zero or empty values', () => {
    const file = new File(['bytes'], 'owned.png', { type: 'image/png' });
    const form = serializeInternalFormData({
      file,
      flags: [false, true],
      offset: 0,
      empty: '',
      absent: undefined,
    });
    expect(form.get('file')).toBe(file);
    expect(form.getAll('flags')).toEqual(['false', 'true']);
    expect(form.get('offset')).toBe('0');
    expect(form.get('empty')).toBe('');
    expect(form.has('absent')).toBe(false);
    for (const invalid of [
      null,
      [],
      { nested: {} },
      { nested: [[]] },
      { value: null },
      { value: NaN },
    ])
      expect(() => serializeInternalFormData(invalid)).toThrow();
  });
  const originalFetch = globalThis.fetch;
  beforeEach(() => {
    globalThis.fetch = vi.fn(
      async () =>
        new Response(JSON.stringify({ code: '0', message: 'OK', data: { pid: 'view-pid' } }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    resetAuthoringPreviewGuardForTests();
    window.sessionStorage.clear();
    window.localStorage.clear();
  });

  it('encodes path values once and preserves the normalized result envelope', async () => {
    const result = await internalRequest('get', '/api/views/{pid}', {
      path: { pid: 'folder/view pid' },
    });
    expect(String(vi.mocked(globalThis.fetch).mock.calls[0][0])).toContain(
      '/api/views/folder%2Fview%20pid',
    );
    expect(result).toMatchObject({
      code: '0',
      success: true,
      desc: 'OK',
      data: { pid: 'view-pid' },
    });
  });

  it('keeps typed query parameters separate from a JSON body', async () => {
    await internalRequest('get', '/api/dynamic/{pageKey}/list', {
      path: { pageKey: 'test_page' },
      query: { pageNum: 2, pageSize: 15, keyword: 'A B' },
    });
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[0];
    expect(String(url)).toContain('/api/dynamic/test_page/list?pageNum=2&pageSize=15&keyword=A+B');
    expect(init?.body).toBeUndefined();
    expect(init?.credentials).toBe('include');
  });

  it('preserves the declared create payload and caller AbortSignal', async () => {
    const controller = new AbortController();
    const body = { code: 'example', name: 'Example', dictType: 'simple', sourceType: 'STATIC' };
    await internalRequest('post', '/api/meta/dict', {
      body,
      options: { signal: controller.signal },
    });
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[0];
    expect(String(url)).toMatch(/\/api\/meta\/dict$/);
    expect(JSON.parse(String(init?.body))).toEqual(body);
    expect(init?.signal).toBe(controller.signal);
  });

  it('retains the authoring preview write guard on generated operations', async () => {
    activateAuthoringPreviewGuard('session-pid');
    const result = await internalRequest('post', '/api/meta/dict', {
      body: { code: 'example', name: 'Example', dictType: 'simple', sourceType: 'STATIC' },
    });
    expect(result.code).toBe('authoring_preview_write_blocked');
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('uses repeated parameters for a declared Spring query array', async () => {
    await internalRequest('delete', '/api/meta/dict/batch', {
      query: { pids: ['pid one', 'pid/two'] },
    });
    const [url, init] = vi.mocked(globalThis.fetch).mock.calls[0];
    const parsed = new URL(String(url), 'http://fixture.invalid');
    expect(parsed.searchParams.getAll('pids')).toEqual(['pid one', 'pid/two']);
    expect(init?.body).toBeUndefined();
    expect(
      serializeInternalQuery({
        enabled: false,
        offset: 0,
        empty: undefined,
        values: { key: 'value' },
      }),
    ).toBe('enabled=false&offset=0&key=value');
    expect(() => serializeInternalQuery({ values: [{ nested: 'invalid' }] })).toThrow(
      'explicit serialization contract',
    );
  });

  it('assigns and preserves command request identities with explicit bodies', async () => {
    await fetchResult('/api/meta/commands/execute/example:create', {
      method: 'post',
      body: { payload: {} },
    });
    const first = JSON.parse(String(vi.mocked(globalThis.fetch).mock.calls[0][1]?.body));
    expect(first.payload).toEqual({});
    expect(first.clientRequestId).toMatch(/^ui-/);
    await fetchResult('/api/meta/commands/execute/example:create', {
      method: 'post',
      body: { payload: {}, clientRequestId: 'caller-owned' },
    });
    const second = JSON.parse(String(vi.mocked(globalThis.fetch).mock.calls[1][1]?.body));
    expect(second.clientRequestId).toBe('caller-owned');
  });
});
