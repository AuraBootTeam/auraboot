import React from 'react';
import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// Mock I18nContext — we provide our own t/locale via the wrapper
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: vi.fn(),
}));

import { useI18n } from '~/contexts/I18nContext';
import { useI18nResolver, useButtonLabel, useColumnLabel, useFieldLabel, useMessage } from '../useI18nResolver';

const mockUseI18n = vi.mocked(useI18n);

/**
 * A t() that returns the key itself unless specifically overridden.
 * Callers can pass overrides to simulate known translations.
 */
function makeT(overrides: Record<string, string> = {}) {
  return (key: string) => overrides[key] ?? key;
}

describe('useI18nResolver', () => {
  it('returns an I18nResolver bound to the given modelCode', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'action.create': '新建' }),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    const resolver = result.current;

    // Button label via action key
    expect(resolver.resolveButtonLabel({ code: 'btn_create', action: 'create' })).toBe('新建');
  });

  it('falls back to button code when action key has no translation', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({}),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveButtonLabel({ code: 'my_btn', action: 'unknown_action' })).toBe('my_btn');
  });

  it('resolveFieldLabel returns translated model-specific label', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'model.order.amount.label': 'Amount' }),
      locale: 'en-US',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveFieldLabel({ field: 'amount' })).toBe('Amount');
  });

  it('resolveFieldLabel falls back to fieldCode when no translation', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({}),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveFieldLabel({ field: 'unknown_field' })).toBe('unknown_field');
  });

  it('resolveColumnLabel returns "操作" for action column with no translation', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({}),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveColumnLabel({ field: 'actions', isActionColumn: true })).toBe('操作');
  });

  it('resolveMessage falls back to the raw messageKey when no translation', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({}),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveMessage('delete.success')).toBe('delete.success');
  });

  it('resolveMessage returns translated value', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'message.delete.success': '删除成功' }),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useI18nResolver('order'));
    expect(result.current.resolveMessage('delete.success')).toBe('删除成功');
  });
});

describe('useButtonLabel', () => {
  it('returns translated action label', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'action.edit': '编辑' }),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() =>
      useButtonLabel({ code: 'edit_btn', action: 'edit' }),
    );
    expect(result.current).toBe('编辑');
  });
});

describe('useFieldLabel', () => {
  it('returns translated field label', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'model.crm_account.name.label': 'Account Name' }),
      locale: 'en-US',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() =>
      useFieldLabel({ field: 'name' }, 'crm_account'),
    );
    expect(result.current).toBe('Account Name');
  });
});

describe('useColumnLabel', () => {
  it('returns field-code as fallback', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({}),
      locale: 'zh-CN',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() =>
      useColumnLabel({ field: 'status' }, 'order'),
    );
    expect(result.current).toBe('status');
  });
});

describe('useMessage', () => {
  it('returns translated message', () => {
    mockUseI18n.mockReturnValue({
      t: makeT({ 'message.save.success': 'Saved!' }),
      locale: 'en-US',
      setLocale: vi.fn(),
      loading: false,
      recovering: false,
      isRTL: false,
    });

    const { result } = renderHook(() => useMessage('save.success'));
    expect(result.current).toBe('Saved!');
  });
});
