import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { PageModeSelector } from '../PageModeSelector';

function localized(locale: string, element: React.ReactNode) {
  const data = parse(readFileSync(path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8'));
  return render(<I18nProvider initialLocale={locale} initialData={data}>{element}</I18nProvider>);
}
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());
const locales = [
  ['en-US', 'Form mode', 'Switch page mode', 'Confirm switch', 'Cancel', '3 columns', 'Left', 'Label width', 'Gutter (16px)'],
  ['zh-CN', '表单模式', '切换页面模式', '确认切换', '取消', '3列', '左侧', '标签宽度', '间距 (16px)'],
];

describe('page mode selection and layout localization', () => {
  for (const compact of [false, true]) {
    it.each(locales)(`confirms and cancels mode changes in %s (compact=${compact})`, (locale, form, heading, confirm, cancel) => {
      const onModeChange = vi.fn();
      localized(locale, <PageModeSelector currentMode="floor" compact={compact} onModeChange={onModeChange} />);
      fireEvent.click(screen.getByRole('button', { name: new RegExp(form) }));
      const dialog = screen.getByRole('dialog', { name: heading });
      expect(onModeChange).not.toHaveBeenCalled();
      fireEvent.click(within(dialog).getByRole('button', { name: cancel }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(onModeChange).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole('button', { name: new RegExp(form) }));
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: confirm }));
      expect(onModeChange).toHaveBeenCalledExactlyOnceWith('form');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    it(`prevents mode changes when disabled (compact=${compact})`, () => {
      const onModeChange = vi.fn();
      localized('en-US', <PageModeSelector currentMode="floor" compact={compact} disabled onModeChange={onModeChange} />);
      const button = screen.getByRole('button', { name: /Form mode/ });
      expect(button).toBeDisabled();
      fireEvent.click(button);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(onModeChange).not.toHaveBeenCalled();
    });
  }
  it.each(locales)('localizes layout controls in %s and preserves untouched properties', (locale, _form, _heading, _confirm, _cancel, columns, left, width, gutter) => {
    const layout = { columns: 2 as const, labelPosition: 'left' as const, labelWidth: 100, gutter: 16 };
    const onFormLayoutChange = vi.fn();
    localized(locale, <PageModeSelector currentMode="form" formLayout={layout} onFormLayoutChange={onFormLayoutChange} />);
    fireEvent.click(screen.getByRole('button', { name: columns }));
    expect(onFormLayoutChange).toHaveBeenLastCalledWith({ ...layout, columns: 3 });
    if (locale === 'en-US') {
      expect(screen.getByRole('button', { name: left })).toHaveAttribute('title', 'Labels to the left of inputs');
    }
    fireEvent.click(screen.getByRole('button', { name: left }));
    expect(onFormLayoutChange).toHaveBeenLastCalledWith(layout);
    fireEvent.change(screen.getByLabelText(width), { target: { value: '120' } });
    expect(onFormLayoutChange).toHaveBeenLastCalledWith({ ...layout, labelWidth: 120 });
    fireEvent.change(screen.getByLabelText(gutter), { target: { value: '24' } });
    expect(onFormLayoutChange).toHaveBeenLastCalledWith({ ...layout, gutter: 24 });
  });
});
