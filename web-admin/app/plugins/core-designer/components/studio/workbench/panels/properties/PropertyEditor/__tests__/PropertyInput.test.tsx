import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { PropertyInput } from '../PropertyInput';

const fixture = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/contexts/I18nContext')>();
  return { ...actual, useI18n: () => ({ ...actual.useI18n(), locale: fixture.locale }) };
});
beforeEach(() => { fixture.locale = 'zh-CN'; });
afterEach(cleanup);

describe('PropertyInput', () => {
  it('renders the shared icon picker when property type is icon', () => {
    render(
      <I18nProvider initialLocale="zh-CN" initialData={{ designer_icon_picker: { labels: { success: '\u6210\u529f' } } }}>
      <PropertyInput
        property={{ key: 'icon', type: 'icon', label: '图标' }}
        value="success"
        onChange={vi.fn()}
      />
      </I18nProvider>,
    );

    expect(screen.getByRole('button', { name: '成功' })).toBeInTheDocument();
    expect(screen.getByText('成功')).toBeInTheDocument();
  });
});


describe('property input localized prompts and exact values', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    for (const type of ['string', 'text', 'number', 'textarea', 'unknown']) {
      it(`${locale} ${type} uses the localized fallback without changing values`, () => {
        fixture.locale = locale;
        const onChange = vi.fn();
        render(<PropertyInput property={{ key: 'amount', type, label: 'Amount' }} value={type === 'number' ? 2 : 'old'} onChange={onChange} />);
        const input = screen.getByPlaceholderText(locale === 'en-US' ? 'Enter Amount' : '\u8bf7\u8f93\u5165Amount');
        fireEvent.change(input, { target: { value: type === 'number' ? '7' : 'new' } });
        expect(onChange).toHaveBeenCalledWith(type === 'number' ? 7 : 'new');
      });
    }
    it(`${locale} select keeps option and callback values`, () => {
      fixture.locale = locale;
      const onChange = vi.fn();
      render(<PropertyInput property={{ key: 'mode', type: 'select', label: 'Mode', options: [{ label: 'Exact label', value: 'raw-mode' }] }} value="" onChange={onChange} />);
      expect(screen.getByRole('option', { name: locale === 'en-US' ? 'Select Mode' : '\u8bf7\u9009\u62e9Mode' })).toHaveValue('');
      fireEvent.change(screen.getByRole('combobox'), { target: { value: 'raw-mode' } });
      expect(onChange).toHaveBeenCalledWith('raw-mode');
    });
    for (const type of ['array', 'object']) {
      it(`${locale} ${type} prompt preserves JSON parse and malformed draft values`, () => {
        fixture.locale = locale;
        const onChange = vi.fn();
        render(<PropertyInput property={{ key: 'value', type, label: 'Value' }} value="" onChange={onChange} />);
        const input = screen.getByRole('textbox');
        expect(input.getAttribute('placeholder')).toContain(locale === 'en-US' ? `Enter a JSON ${type}` : '\u8bf7\u8f93\u5165JSON');
        const serialized = type === 'array' ? '[1,2]' : '{"key":"value"}';
        fireEvent.change(input, { target: { value: serialized } });
        expect(onChange).toHaveBeenLastCalledWith(JSON.parse(serialized));
        fireEvent.change(input, { target: { value: '{draft' } });
        expect(onChange).toHaveBeenLastCalledWith('{draft');
      });
    }
  }
  it('retains an explicit description instead of substituting a default prompt', () => {
    fixture.locale = 'en-US';
    render(<PropertyInput property={{ key: 'value', type: 'string', label: 'Value', description: 'Exact configured guidance' }} value="" onChange={vi.fn()} />);
    expect(screen.getByPlaceholderText('Exact configured guidance')).toBeInTheDocument();
  });
});
