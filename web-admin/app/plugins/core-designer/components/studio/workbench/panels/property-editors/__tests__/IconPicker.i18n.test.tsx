import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { IconPicker } from '../IconPicker';

const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));

beforeEach(() => localStorage.clear());
afterEach(() => cleanup());

function renderPicker(locale: string, value = '') {
  const onChange = vi.fn();
  render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>
    <IconPicker value={value} onChange={onChange} />
  </I18nProvider>);
  return onChange;
}

describe('IconPicker locale contracts', () => {
  it('uses the English dictionary for labels, categories and empty state', () => {
    renderPicker('en-US');
    fireEvent.click(screen.getByText('Choose icon'));
    expect(screen.getByText('Actions')).toBeInTheDocument();
    expect(screen.getByTestId('icon-picker-option-save')).toHaveAttribute('title', 'Save');
    fireEvent.change(screen.getByPlaceholderText('Search icons...'), { target: { value: 'no-such-icon' } });
    expect(screen.getByText('No matching icons')).toBeInTheDocument();
  });

  it('uses Chinese labels and emits the stable icon identifier', () => {
    const onChange = renderPicker('zh-CN');
    fireEvent.click(screen.getByText('选择图标'));
    expect(screen.getByText('操作')).toBeInTheDocument();
    expect(screen.getByTestId('icon-picker-option-save')).toHaveAttribute('title', '保存');
    fireEvent.click(screen.getByTestId('icon-picker-option-save'));
    expect(onChange).toHaveBeenCalledWith('save');
  });

  it('retains native search aliases in English and clears selection', () => {
    const onChange = renderPicker('en-US', 'save');
    fireEvent.click(screen.getByText('Save'));
    fireEvent.change(screen.getByPlaceholderText('Search icons...'), { target: { value: '保存' } });
    expect(screen.getByTestId('icon-picker-option-save')).toHaveAttribute('title', 'Save');
    expect(screen.queryByTestId('icon-picker-option-search')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Clear selection'));
    expect(onChange).toHaveBeenCalledWith('');
  });
});
