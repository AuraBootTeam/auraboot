import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import {
  CurrencyField,
  AvatarField,
  ProgressField,
  DateRelativeField,
} from '../cardFields';
import { relativeDayLabel } from '../cardFields/DateRelativeField';

describe('CurrencyField', () => {
  it('renders CNY-formatted currency for numeric values', () => {
    const { container } = render(<CurrencyField value={12345.67} />);
    const node = container.querySelector('[data-field-type="currency"]');
    expect(node).not.toBeNull();
    // Intl.NumberFormat output varies by ICU but must contain digits and CNY marker
    const text = node!.textContent ?? '';
    expect(text).toMatch(/12,345\.67/);
    expect(text).toMatch(/(¥|CN¥|CNY)/);
  });

  it('respects explicit currencyCode (USD)', () => {
    const { container } = render(<CurrencyField value={1000} currencyCode="USD" />);
    const text = container.querySelector('[data-field-type="currency"]')!.textContent ?? '';
    expect(text).toMatch(/1,000/);
    expect(text).toMatch(/\$|USD/);
  });

  it('renders em-dash for non-numeric / null', () => {
    const { container: c1 } = render(<CurrencyField value={null} />);
    expect(c1.querySelector('[data-field-type="currency"]')!.textContent).toBe('—');

    const { container: c2 } = render(<CurrencyField value={'abc'} />);
    expect(c2.querySelector('[data-field-type="currency"]')!.textContent).toBe('—');

    const { container: c3 } = render(<CurrencyField value={undefined} />);
    expect(c3.querySelector('[data-field-type="currency"]')!.textContent).toBe('—');
  });
});

describe('AvatarField', () => {
  it('renders uppercase initial plus name', () => {
    const { container } = render(<AvatarField value="alice" />);
    const node = container.querySelector('[data-field-type="avatar"]');
    expect(node).not.toBeNull();
    const text = node!.textContent ?? '';
    expect(text).toContain('A');
    expect(text).toContain('alice');
  });

  it('renders em-dash for null/empty', () => {
    const { container: c1 } = render(<AvatarField value={null} />);
    expect(c1.querySelector('[data-field-type="avatar"]')!.textContent).toBe('—');

    const { container: c2 } = render(<AvatarField value="" />);
    expect(c2.querySelector('[data-field-type="avatar"]')!.textContent).toBe('—');
  });
});

describe('ProgressField', () => {
  it('renders percentage text and bar width style', () => {
    const { container } = render(<ProgressField value={42} />);
    const node = container.querySelector('[data-field-type="progress"]');
    expect(node).not.toBeNull();
    expect(node!.textContent).toContain('42%');
    const bar = node!.querySelector('[data-field-type-bar="progress"]') as HTMLSpanElement | null;
    expect(bar).not.toBeNull();
    expect(bar!.style.width).toBe('42%');
  });

  it('respects custom max', () => {
    const { container } = render(<ProgressField value={50} max={200} />);
    const node = container.querySelector('[data-field-type="progress"]')!;
    expect(node.textContent).toContain('25%');
  });

  it('clamps values above 100%', () => {
    const { container } = render(<ProgressField value={150} />);
    const bar = container.querySelector(
      '[data-field-type-bar="progress"]',
    ) as HTMLSpanElement;
    expect(bar.style.width).toBe('100%');
  });

  it('renders em-dash for non-numeric', () => {
    const { container: c1 } = render(<ProgressField value={null} />);
    expect(c1.querySelector('[data-field-type="progress"]')!.textContent).toBe('—');

    const { container: c2 } = render(<ProgressField value={'abc'} />);
    expect(c2.querySelector('[data-field-type="progress"]')!.textContent).toBe('—');
  });
});

describe('DateRelativeField', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-05-08T00:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders red+bold style for due-soon (<7 days future)', () => {
    const future = new Date('2026-05-11T00:00:00Z').toISOString();
    const { container } = render(<DateRelativeField value={future} />);
    const node = container.querySelector('[data-field-type="date-relative"]')!;
    expect(node.className).toContain('text-red-600');
    expect(node.className).toContain('font-medium');
    expect(node.textContent).toMatch(/3d/);
  });

  it('renders gray style for past dates', () => {
    const past = new Date('2026-05-01T00:00:00Z').toISOString();
    const { container } = render(<DateRelativeField value={past} />);
    const node = container.querySelector('[data-field-type="date-relative"]')!;
    expect(node.className).toContain('text-gray-500');
    expect(node.textContent).toMatch(/7d ago/);
  });

  it('renders today label for diff=0', () => {
    const today = new Date('2026-05-08T06:00:00Z').toISOString();
    const { container } = render(<DateRelativeField value={today} />);
    const node = container.querySelector('[data-field-type="date-relative"]')!;
    expect(node.textContent).toBe('today');
  });

  it('renders no urgency class for >=7 days future', () => {
    const farFuture = new Date('2026-06-01T00:00:00Z').toISOString();
    const { container } = render(<DateRelativeField value={farFuture} />);
    const node = container.querySelector('[data-field-type="date-relative"]')!;
    expect(node.className).not.toContain('text-red-600');
    expect(node.className).not.toContain('text-gray-500');
  });

  it('renders em-dash for null/invalid', () => {
    const { container: c1 } = render(<DateRelativeField value={null} />);
    expect(c1.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('—');

    const { container: c2 } = render(<DateRelativeField value={'not-a-date'} />);
    expect(c2.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('—');
  });

  // A Chinese opportunity board read `139d ago` on every card: the labels were English only.
  it('labels the date in Chinese for a Chinese locale, with the same styling', () => {
    const past = render(<DateRelativeField value={new Date('2026-05-01T00:00:00Z').toISOString()} locale="zh-CN" />);
    expect(past.container.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('7 天前');
    expect(past.container.querySelector('[data-field-type="date-relative"]')!.className).toContain('text-gray-500');

    const soon = render(<DateRelativeField value={new Date('2026-05-11T00:00:00Z').toISOString()} locale="zh-CN" />);
    expect(soon.container.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('3 天后');
    expect(soon.container.querySelector('[data-field-type="date-relative"]')!.className).toContain('text-red-600');

    const today = render(<DateRelativeField value={new Date('2026-05-08T06:00:00Z').toISOString()} locale="zh-CN" />);
    expect(today.container.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('今天');
  });

  it('labels the date in English for every other locale and for no locale', () => {
    const value = new Date('2026-05-01T00:00:00Z').toISOString();
    expect(relativeDayLabel(-139, 'zh-CN')).toBe('139 天前');
    expect(relativeDayLabel(57, 'zh-TW')).toBe('57 天后');
    expect(relativeDayLabel(0, 'zh-CN')).toBe('今天');
    expect(relativeDayLabel(-139, 'en-US')).toBe('139d ago');
    expect(relativeDayLabel(57)).toBe('in 57d');
    expect(relativeDayLabel(0)).toBe('today');
    const english = render(<DateRelativeField value={value} locale="en-US" />);
    expect(english.container.querySelector('[data-field-type="date-relative"]')!.textContent).toBe('7d ago');
  });
});
