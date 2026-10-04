import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ColorPicker } from '../ColorPicker';
import { JsonEditor } from '../JsonEditor';
const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));
function localized(locale: string, element: React.ReactNode) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>{element}</I18nProvider>);
}
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());
describe('ColorPicker locale and value contracts', () => {
  it.each([
    ['en-US', 'Hue', 'Opacity', 'Preset colors', 'Red', 'Transparent'],
    ['zh-CN', '色相', '透明度', '预设颜色', '红色', '透明'],
  ])('localizes defaults in %s without changing emitted colors', (locale, hue, opacity, presets, red, transparent) => {
    const onChange = vi.fn();
    localized(locale, <ColorPicker inline showAlpha value="#000000" onChange={onChange} />);
    for (const label of [hue, opacity, presets]) expect(screen.getByText(label)).toBeInTheDocument();
    fireEvent.click(screen.getByTitle(red));
    expect(onChange).toHaveBeenLastCalledWith('#ef4444');
    fireEvent.click(screen.getByTitle(transparent));
    expect(onChange).toHaveBeenLastCalledWith('transparent');
  });
  it('preserves custom names even when their colors match a default', () => {
    const onChange = vi.fn();
    localized('zh-CN', <ColorPicker inline value="#000000" onChange={onChange}
      presets={[{ name: 'Brand red', color: '#ef4444' }]} />);
    expect(screen.queryByTitle('红色')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Brand red'));
    expect(onChange).toHaveBeenCalledWith('#ef4444');
  });
  it('localizes the empty trigger and prevents disabled changes', () => {
    const onChange = vi.fn();
    localized('en-US', <ColorPicker value="" disabled onChange={onChange} />);
    expect(screen.getByRole('button', { name: 'Choose color' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Choose color' }));
    expect(screen.queryByText('Preset colors')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });
});
describe('JsonEditor schema and locale contracts', () => {
  it.each([
    ['en-US', 'Format', 'Minify', 'Property "rows": Index 0: Missing required property "amount"'],
    ['zh-CN', '格式化', '压缩', '属性 "rows": 索引 0: 缺少必需属性 "amount"'],
  ])('localizes recursive validation in %s and rejects invalid writes', (locale, format, minify, message) => {
    const onChange = vi.fn();
    localized(locale, <JsonEditor label="Payload" value={{ rows: [] }} onChange={onChange}
      schema={{ type: 'object', properties: { rows: { type: 'array', items: {
        type: 'object', required: ['amount'], properties: { amount: { type: 'number' } },
      } } } }} />);
    expect(screen.getByRole('button', { name: format })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: minify })).toBeInTheDocument();
    const input = screen.getByRole('textbox');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: '{"rows":[{}]}' } });
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: '{"rows":[{"amount":7}]}' } });
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    expect(onChange).toHaveBeenLastCalledWith({ rows: [{ amount: 7 }] });
  });
  it('rejects incorrect types and enum values before emitting the valid value', () => {
    const onChange = vi.fn();
    localized('en-US', <JsonEditor value={1} onChange={onChange} schema={{ type: 'number', enum: [1, 2] }} />);
    const input = screen.getByRole('textbox');
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: '"one"' } });
    expect(screen.getByText('Expected type "number", got "string"')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '3' } });
    expect(screen.getByText('Value must be one of: 1, 2')).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: '2' } });
    expect(onChange).toHaveBeenCalledExactlyOnceWith(2);
  });
  it('formats and minifies without changing the underlying JSON value', () => {
    const value = { rows: [1, 2] };
    const onChange = vi.fn();
    localized('en-US', <JsonEditor label="Payload" value={value} onChange={onChange} />);
    const input = screen.getByRole('textbox');
    fireEvent.click(screen.getByRole('button', { name: 'Minify' }));
    expect(input).toHaveValue(JSON.stringify(value));
    fireEvent.click(screen.getByRole('button', { name: 'Format' }));
    expect(input).toHaveValue(JSON.stringify(value, null, 2));
    expect(onChange).not.toHaveBeenCalled();
  });
});
