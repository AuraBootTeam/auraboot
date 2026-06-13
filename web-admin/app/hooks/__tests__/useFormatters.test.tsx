import { renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: vi.fn(() => ({ locale: 'zh-CN' })),
}));

vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: vi.fn(() => ({ timezone: 'Asia/Shanghai' })),
}));

import { useNumberFormat } from '../useFormatters';
import { useDateFormat } from '../useFormatters';
import { useCurrencyFormat } from '../useFormatters';

describe('useNumberFormat', () => {
  it('returns em-dash for null', () => {
    const { result } = renderHook(() => useNumberFormat());
    expect(result.current.formatNumber(null)).toBe('—');
  });

  it('returns em-dash for undefined', () => {
    const { result } = renderHook(() => useNumberFormat());
    expect(result.current.formatNumber(undefined)).toBe('—');
  });

  it('formats a number with zh-CN locale', () => {
    const { result } = renderHook(() => useNumberFormat());
    // 1234567 → "1,234,567" in zh-CN
    const out = result.current.formatNumber(1234567);
    expect(out).toBeTruthy();
    expect(typeof out).toBe('string');
    // The number should appear somewhere in the output
    expect(out).toMatch(/1.234.567|1,234,567/);
  });

  it('respects number format options', () => {
    const { result } = renderHook(() => useNumberFormat());
    const out = result.current.formatNumber(3.14159, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    expect(out).toContain('3');
    expect(out).toContain('14');
  });
});

describe('useDateFormat', () => {
  it('returns em-dash for null', () => {
    const { result } = renderHook(() => useDateFormat());
    expect(result.current.formatDate(null)).toBe('—');
  });

  it('returns em-dash for undefined', () => {
    const { result } = renderHook(() => useDateFormat());
    expect(result.current.formatDate(undefined)).toBe('—');
  });

  it('formats a valid ISO date string', () => {
    const { result } = renderHook(() => useDateFormat());
    const out = result.current.formatDate('2024-01-15T10:00:00Z');
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
    expect(out).not.toBe('—');
  });

  it('returns string representation for invalid date', () => {
    const { result } = renderHook(() => useDateFormat());
    const out = result.current.formatDate('not-a-date');
    expect(out).toBe('not-a-date');
  });

  it('formats a Date object', () => {
    const { result } = renderHook(() => useDateFormat());
    const d = new Date('2024-06-01T00:00:00Z');
    const out = result.current.formatDate(d);
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('formats a numeric timestamp', () => {
    const { result } = renderHook(() => useDateFormat());
    const ts = new Date('2024-03-20T12:00:00Z').getTime();
    const out = result.current.formatDate(ts);
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });
});

describe('useCurrencyFormat', () => {
  it('returns em-dash for null', () => {
    const { result } = renderHook(() => useCurrencyFormat());
    expect(result.current.formatCurrency(null)).toBe('—');
  });

  it('returns em-dash for undefined', () => {
    const { result } = renderHook(() => useCurrencyFormat());
    expect(result.current.formatCurrency(undefined)).toBe('—');
  });

  it('formats a currency value with default CNY', () => {
    const { result } = renderHook(() => useCurrencyFormat());
    const out = result.current.formatCurrency(1234.5);
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('formats with custom currency code', () => {
    const { result } = renderHook(() => useCurrencyFormat());
    const out = result.current.formatCurrency(99.99, 'USD');
    expect(typeof out).toBe('string');
    expect(out.length).toBeGreaterThan(0);
  });

  it('falls back gracefully on invalid currency', () => {
    const { result } = renderHook(() => useCurrencyFormat());
    // 'INVALID' is not a valid ISO 4217 code → fallback branch
    const out = result.current.formatCurrency(42, 'INVALID');
    expect(out).toContain('INVALID');
    expect(out).toContain('42');
  });
});
