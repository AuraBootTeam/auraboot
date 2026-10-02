import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { SettingsPanel } from '../SettingsPanel';
import { DEFAULT_SETTINGS } from '../types';

const dictionary = (locale: string) => parse(readFileSync(path.resolve(process.cwd(),
  `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8'));
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());

describe.each(['en-US', 'zh-CN'])('designer settings in %s', locale => {
  const labels = dictionary(locale).designer_settings;
  function mount(initialSettings = {}) {
    const onClose = vi.fn(); const onSettingsChange = vi.fn();
    const element = (isOpen: boolean) => <I18nProvider initialLocale={locale} initialData={dictionary(locale)}>
      <SettingsPanel isOpen={isOpen} onClose={onClose} onSettingsChange={onSettingsChange} initialSettings={initialSettings} />
    </I18nProvider>;
    const view = render(element(true));
    return { onClose, onSettingsChange, view, element };
  }

  it('localizes all four categories without leaking raw keys', () => {
    mount(); expect(screen.getByTestId('settings-panel-heading')).toHaveTextContent(labels.heading);
    const expected = { page: [labels.title, labels.multi_view_hint], editor: [labels.save_interval_hint, labels.show_guides_hint],
      appearance: [labels.theme_system, labels.background_dots], export: [labels.metadata_hint, labels.history_hint] };
    for (const [category, texts] of Object.entries(expected)) {
      fireEvent.click(screen.getByTestId(`settings-category-${category}`));
      for (const text of texts) expect(screen.getByText(text)).toBeVisible();
      expect(screen.getByTestId('settings-panel')).not.toHaveTextContent('designer_settings.');
      if (locale === 'en-US') expect(screen.getByTestId('settings-panel').textContent).not.toMatch(/[\u4e00-\u9fff]/);
    }
    expect(screen.getByRole('button', { name: labels.close })).toBeVisible();
  });

  it('preserves zero gap and padding in the actual saved settings', () => {
    const props = mount(); const numbers = screen.getAllByRole('spinbutton');
    fireEvent.change(numbers[1], { target: { value: '0' } });
    fireEvent.change(numbers[2], { target: { value: '0' } });
    fireEvent.click(screen.getByTestId('settings-panel-save'));
    expect(props.onSettingsChange).toHaveBeenCalledWith({ ...DEFAULT_SETTINGS,
      page: { ...DEFAULT_SETTINGS.page, gridGap: 0, padding: 0 } });
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  it('resets unsaved edits to the supplied baseline without saving', () => {
    const props = mount({ page: { title: 'Orders', gridGap: 4 } });
    const input = screen.getByPlaceholderText(labels.title_placeholder);
    fireEvent.change(input, { target: { value: 'Draft' } });
    expect(screen.getByTestId('settings-panel-save')).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: labels.reset }));
    expect(input).toHaveValue('Orders'); expect(screen.getByTestId('settings-panel-save')).toBeDisabled();
    expect(props.onSettingsChange).not.toHaveBeenCalled();
  });

  it('exposes switch state and saves the flag without changing stored identifiers', () => {
    const props = mount(); const toggle = screen.getByRole('switch', { name: labels.multi_view });
    expect(toggle).toHaveAttribute('aria-checked', 'false'); fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'true'); fireEvent.click(screen.getByTestId('settings-panel-save'));
    expect(props.onSettingsChange.mock.calls[0][0].page.enableMultiView).toBe(true);
    expect(props.onSettingsChange.mock.calls[0][0].export.exportFormat).toBe('json');
  });

  it('cancels without saving and restores initial settings when reopened', () => {
    const props = mount({ page: { title: 'Orders' } });
    fireEvent.change(screen.getByPlaceholderText(labels.title_placeholder), { target: { value: 'Discard me' } });
    fireEvent.click(screen.getByRole('button', { name: labels.cancel }));
    expect(props.onClose).toHaveBeenCalledOnce(); expect(props.onSettingsChange).not.toHaveBeenCalled();
    props.view.rerender(props.element(false)); props.view.rerender(props.element(true));
    expect(screen.getByPlaceholderText(labels.title_placeholder)).toHaveValue('Orders');
    expect(screen.getByTestId('settings-panel-save')).toBeDisabled();
  });
});
