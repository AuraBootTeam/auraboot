import { describe, expect, it, vi } from 'vitest';
import {
  generateErrorId,
  resolveErrorLocale,
  resolveErrorPresentation,
  resolveRootErrorView,
  rootT,
} from '../root-error-view';

function routeError(status: number) {
  return { status, statusText: 'status text', internal: false, data: null };
}

describe('resolveRootErrorView', () => {
  it.each([
    [404, 'not-found', false],
    [403, 'forbidden', false],
    [400, 'client', false],
    [418, 'client', false],
    [500, 'server', true],
    [503, 'server', true],
  ] as const)('classifies route error status %i', (status, kind, retryable) => {
    expect(resolveRootErrorView(routeError(status))).toMatchObject({
      kind,
      status,
      retryable,
    });
  });

  it('classifies network failures by message', () => {
    expect(resolveRootErrorView(new Error('Failed to fetch'))).toMatchObject({
      kind: 'network',
      retryable: true,
    });
    expect(
      resolveRootErrorView(new Error('NetworkError when attempting to fetch resource.')),
    ).toMatchObject({ kind: 'network' });
  });

  it('classifies network failures by error code', () => {
    const err = new Error('Unable to resolve the authenticated user') as Error & {
      code: string;
    };
    err.code = 'network_error';
    expect(resolveRootErrorView(err)).toMatchObject({ kind: 'network', retryable: true });
  });

  it('keeps non-network errors unknown and retryable', () => {
    expect(resolveRootErrorView(new Error('Unable to resolve the authenticated user'))).toMatchObject(
      { kind: 'unknown', retryable: true },
    );
    expect(resolveRootErrorView(null)).toMatchObject({ kind: 'unknown', retryable: true });
    expect(resolveRootErrorView('boom')).toMatchObject({ kind: 'unknown', retryable: true });
  });
});

describe('resolveErrorLocale', () => {
  it('prefers the application cookie locale', () => {
    expect(resolveErrorLocale('zh-CN')).toBe('zh-CN');
    expect(resolveErrorLocale('ja-JP')).toBe('ja-JP');
    expect(resolveErrorLocale('ko-KR')).toBe('ko-KR');
    expect(resolveErrorLocale('en-US')).toBe('en-US');
  });

  it('normalizes prefix-style cookie values', () => {
    expect(resolveErrorLocale('zh-TW')).toBe('zh-CN');
    expect(resolveErrorLocale('en-GB')).toBe('en-US');
    expect(resolveErrorLocale('ja')).toBe('ja-JP');
    expect(resolveErrorLocale('ko-KR')).toBe('ko-KR');
  });

  it('defaults to the app-wide locale when no cookie is available', () => {
    expect(resolveErrorLocale()).toBe('zh-CN');
    expect(resolveErrorLocale('')).toBe('zh-CN');
  });
});

describe('resolveErrorPresentation', () => {
  it('uses the status code as title where meaningful', () => {
    expect(resolveErrorPresentation({ kind: 'not-found', status: 404, retryable: false }, 'zh-CN')).toEqual({
      title: '404',
      detail: expect.stringContaining('页面不存在'),
    });
    expect(resolveErrorPresentation({ kind: 'server', status: 500, retryable: true }, 'zh-CN').title).toBe('500');
  });

  it('localizes details', () => {
    expect(
      resolveErrorPresentation({ kind: 'server', status: 500, retryable: true }, 'en-US').detail,
    ).toContain('temporarily unavailable');
    expect(
      resolveErrorPresentation({ kind: 'network', retryable: true }, 'zh-CN').title,
    ).toBe('网络连接异常');
    expect(
      resolveErrorPresentation({ kind: 'unknown', retryable: true }, 'ko-KR').detail,
    ).toContain('예기치 않은 오류');
  });
});

describe('rootT', () => {
  it('returns the requested locale text', () => {
    expect(rootT('oops', 'zh-CN')).toBe('出错了！');
    expect(rootT('retry', 'en-US')).toBe('Retry');
  });
});

describe('generateErrorId', () => {
  it('produces unique ERR- prefixed ids', () => {
    const ids = new Set(Array.from({ length: 20 }, () => generateErrorId()));
    expect(ids.size).toBe(20);
    for (const id of ids) {
      expect(id).toMatch(/^ERR-[A-HJ-NP-Z2-9]{8}$/);
    }
  });

  it('still generates an id when crypto is unavailable', () => {
    const getRandomValues = crypto.getRandomValues;
    vi.stubGlobal('crypto', {});
    try {
      expect(generateErrorId()).toMatch(/^ERR-[A-HJ-NP-Z2-9]{8}$/);
    } finally {
      vi.unstubAllGlobals();
      // restore in case other tests depend on it
      Object.defineProperty(globalThis, 'crypto', { value: { getRandomValues }, configurable: true });
    }
  });
});
