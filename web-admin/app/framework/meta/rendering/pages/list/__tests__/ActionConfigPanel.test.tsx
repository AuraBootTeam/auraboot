import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ActionConfigPanel } from '../ActionConfigPanel';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
const dictionary = (locale: string) => {
  const resources = path.resolve(process.cwd(), '../platform/src/main/resources');
  const seed = JSON.parse(readFileSync(path.join(resources, 'seed/i18n-base.json'), 'utf8'));
  return {
    ...Object.fromEntries(
      seed
        .filter((row: Record<string, string>) => row[locale])
        .map((row: Record<string, string>) => [row.key, row[locale]]),
    ),
    ...parse(readFileSync(path.join(resources, `i18n.${locale}.yaml`), 'utf8')),
  };
};
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());
describe.each(['en-US', 'zh-CN'])('action configuration in %s', (locale) => {
  const data = dictionary(locale);
  const buttons: ButtonConfig[] = [
    { code: 'approve', label: 'Approval', mandatory: true },
    { code: 'archive', label: 'Archive' },
  ];
  const mount = (override = {}) => {
    const onChange = vi.fn();
    const onClose = vi.fn();
    render(
      <I18nProvider initialLocale={locale} initialData={data}>
        <ActionConfigPanel
          buttons={buttons}
          resolveLabel={(button) => String(button.label)}
          onChange={onChange}
          onClose={onClose}
          {...override}
        />
      </I18nProvider>,
    );
    return { onChange, onClose };
  };
  it('renders the dictionary title, sections and built-in labels without emitting an initial save', () => {
    const { onChange } = mount();
    expect(screen.getByRole('heading')).toHaveTextContent(data['action_config.title']);
    for (const label of [data['action_config.toolbar'], data['action_config.more_menu']])
      expect(screen.getByText(label, { exact: true })).toBeVisible();
    const keys = {
      _import: 'action.import',
      _export_excel: 'data_tools.export_excel',
      _export_csv: 'data_tools.export_csv',
      _print: 'action.print',
    };
    for (const [code, key] of Object.entries(keys)) {
      expect(screen.getByTestId(`action-config-visible-${code}`).parentElement).toHaveTextContent(
        data[key],
      );
    }
    expect(onChange).not.toHaveBeenCalled();
  });
  it('saves an ordinary action visibility and placement with unchanged codes', () => {
    const { onChange } = mount();
    fireEvent.click(screen.getByTestId('action-config-visible-archive'));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ code: 'archive', visible: false, pinned: true }),
      ]),
    );
    fireEvent.click(screen.getByTestId('action-config-pin-archive'));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ code: 'archive', visible: false, pinned: false }),
      ]),
    );
  });
  it('keeps required actions visible while allowing placement changes and closes through a localized button', () => {
    const { onChange, onClose } = mount();
    const visible = screen.getByTestId('action-config-visible-approve');
    expect(visible).toBeDisabled();
    expect(visible).toHaveAttribute('title', data.common.saved_view_mandatory_action_reason);
    fireEvent.click(visible);
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('action-config-pin-approve'));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ code: 'approve', visible: true, pinned: false }),
      ]),
    );
    fireEvent.click(screen.getByRole('button', { name: data['action.close'] }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
  it('does not reintroduce hidden built-ins', () => {
    mount({ hiddenBuiltinCodes: ['_import', '_export_csv'] });
    expect(screen.queryByTestId('action-config-visible-_import')).not.toBeInTheDocument();
    expect(screen.queryByTestId('action-config-visible-_export_csv')).not.toBeInTheDocument();
    expect(screen.getByTestId('action-config-visible-_export_excel')).toBeVisible();
  });
});
