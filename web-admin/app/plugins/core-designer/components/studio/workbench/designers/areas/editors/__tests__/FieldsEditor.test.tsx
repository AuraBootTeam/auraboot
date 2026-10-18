import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { DslFieldRef } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { FieldsEditor } from '../FieldsEditor';

const fixture = vi.hoisted(() => ({ locale: 'zh-CN' }));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: fixture.locale, t: (key: string) => key }),
}));
afterEach(cleanup);

function Controlled({ readonly = false }: { readonly?: boolean }) {
  const [fields, setFields] = useState<DslFieldRef[]>([{
    field: 'name', required: true, props: {
      maxLength: 80, helpText: { 'zh-CN': '原说明', 'en-US': 'Original help' },
    },
  }]);
  return <><FieldsEditor fields={fields} onChange={setFields} readonly={readonly} />
    <output data-testid="saved-fields">{JSON.stringify(fields)}</output></>;
}

describe('field help text authoring', () => {
  for (const locale of ['zh-CN', 'en-US']) {
    it(`${locale} updates both translations while preserving other field properties`, () => {
      fixture.locale = locale;
      const view = render(<Controlled />);
      fireEvent.click(screen.getByTestId('field-item-header-name'));
      const label = locale === 'zh-CN' ? '帮助说明' : 'Help text';
      const zh = screen.getByRole('textbox', { name: `${label} (${locale === 'zh-CN' ? '中文' : 'Chinese'})` });
      const en = screen.getByRole('textbox', { name: `${label} (${locale === 'zh-CN' ? '英文' : 'English'})` });
      expect(zh).toHaveValue('原说明');
      expect(en).toHaveValue('Original help');
      fireEvent.change(zh, { target: { value: '新的说明' } });
      fireEvent.change(en, { target: { value: 'Updated help' } });
      expect(JSON.parse(screen.getByTestId('saved-fields').textContent!)).toEqual([{
        field: 'name', required: true, props: {
          maxLength: 80, helpText: { 'zh-CN': '新的说明', 'en-US': 'Updated help' },
        },
      }]);
      fixture.locale = locale === 'zh-CN' ? 'en-US' : 'zh-CN';
      view.rerender(<Controlled />);
      expect(screen.getByTestId('field-item-help-name-zh')).toHaveValue('新的说明');
      expect(screen.getByTestId('field-item-help-name-en')).toHaveValue('Updated help');
    });
  }
  it('disables help editing for a read-only field editor', () => {
    fixture.locale = 'en-US';
    render(<Controlled readonly />);
    fireEvent.click(screen.getByTestId('field-item-header-name'));
    expect(screen.getByTestId('field-item-help-name-zh')).toBeDisabled();
    expect(screen.getByTestId('field-item-help-name-en')).toBeDisabled();
    expect(screen.getByTestId('field-item-help-name-toggle')).toBeDisabled();
  });
});
