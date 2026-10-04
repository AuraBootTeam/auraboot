import React, { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { getLocalizedText } from '~/utils/i18n';
import { WidgetRegistry } from '~/plugins/core-designer/components/studio/registry/widget-registry';
import type { DslFieldOverride } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { FieldPropertyEditor } from '../FieldPropertyEditor';
import config from '../../configs/field-property-panel.json';

const fixture = vi.hoisted(() => ({ locale: 'en-US', renderComponents: [] as Array<{ code: string; dataTypes: string[] }>, ensureLoaded: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: fixture.locale, t: (key: string) => key }) }));
vi.mock('~/contexts/DslRegistryContext', () => ({ useDslRegistry: () => fixture }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
beforeEach(() => { fixture.locale = 'en-US'; fixture.renderComponents = []; });

function expandSection(code: string) {
  const section = config.sections.find(s => s.code === code)!;
  fireEvent.click(screen.getByRole('button', { name: getLocalizedText(section.title, fixture.locale) }));
}
const expectedFields = {
  'form-section': ['field', 'label', 'placeholder', 'component', 'required', 'readonly', 'maxLength', 'minLength', 'pattern', 'visible', 'disabled', 'span'],
  'filters': ['field', 'label', 'placeholder', 'component', 'required', 'readonly', 'maxLength', 'minLength', 'pattern', 'visible', 'disabled', 'advanced', 'span'],
  'table': ['field', 'label', 'visible', 'disabled', 'sortable', 'copyable', 'ellipsis', 'width', 'fixed', 'render'],
} as const;

describe('field property editor localization and DSL values', () => {
  for (const locale of ['zh-CN', 'en-US']) {
    for (const blockType of ['form-section', 'filters', 'table'] as const) {
      it(`${locale} renders the configured labels/options and exact visible fields for ${blockType}`, () => {
        fixture.locale = locale;
        render(<FieldPropertyEditor fieldRef="account_name" blockType={blockType} onChange={vi.fn()} onClose={vi.fn()} />);
        expect(screen.getByRole('heading', { name: locale === 'en-US' ? 'Field properties' : '\u5b57\u6bb5\u5c5e\u6027' })).toBeTruthy();
        expandSection('behavior'); expandSection('layout');
        for (const section of config.sections) {
          for (const field of section.fields) {
            const control = screen.queryByTestId(`field-property-${field.field}`) as HTMLElement | null;
            if (!(expectedFields[blockType] as readonly string[]).includes(field.field)) { expect(control).toBeNull(); continue; }
            expect(control, field.field).toBeTruthy();
            expect(control).toHaveTextContent(getLocalizedText(field.props.label, locale));
            if ('placeholder' in field.props && field.component === 'SmartInput') {
              expect(control!.querySelector('input')).toHaveAttribute('placeholder', getLocalizedText(field.props.placeholder, locale));
            }
            if ('options' in field.props) {
              for (const option of field.props.options ?? []) {
                const renderedOption = Array.from(control!.querySelectorAll('option')).find(optionElement => optionElement.textContent === getLocalizedText(option.label, locale));
                expect(renderedOption).toHaveValue(String(option.value));
              }
            }
          }
        }
      });
    }
  }
  it('changes UI locale while retaining string/numeric/boolean overrides and props', () => {
    const initial: DslFieldOverride & { maxLength: number } = { field: 'account_name', required: false, maxLength: 20, props: { customFlag: 'retained' } };
    const changed = vi.fn();
    function Controlled() {
      const [field, setField] = useState(initial);
      return <><FieldPropertyEditor fieldRef={field} blockType="form-section" onClose={vi.fn()} onChange={updates => { changed(updates); setField(old => ({ ...old, ...updates })); }} /><output data-testid="observed-field-value">{JSON.stringify(field)}</output></>;
    }
    const view = render(<Controlled />);
    fireEvent.change(screen.getByLabelText('Maximum length'), { target: { value: '50' } });
    expect(changed).toHaveBeenLastCalledWith({ maxLength: 50 });
    fireEvent.click(screen.getByTestId('field-property-required-switch'));
    expect(changed).toHaveBeenLastCalledWith({ required: true });
    expandSection('layout');
    fireEvent.change(screen.getByLabelText('Column span'), { target: { value: '3' } });
    expect(changed).toHaveBeenLastCalledWith({ span: 3 });
    fireEvent.change(screen.getByLabelText('Component type'), { target: { value: 'smart-input' } });
    expect(changed).toHaveBeenLastCalledWith({ component: 'smart-input' });
    fixture.locale = 'zh-CN'; view.rerender(<Controlled />);
    expect(screen.getByTestId('field-property-maxLength-input')).toHaveValue(50);
    expect(screen.getByTestId('field-property-required-switch')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByTestId('field-property-span-select')).toHaveValue('3');
    expect(screen.getByTestId('field-property-component-select')).toHaveValue('smart-input');
    expect(JSON.parse(screen.getByTestId('observed-field-value').textContent!)).toEqual({ ...initial, required: true, maxLength: 50, span: 3, component: 'smart-input' });
    expect(changed).toHaveBeenCalledTimes(4);
  });
  it('keeps decimal controls and table option values semantic rather than translated', () => {
    const change = vi.fn();
    const view = render(<FieldPropertyEditor fieldRef="amount" dataType="decimal" blockType="form-section" onChange={change} onClose={vi.fn()} />);
    expect(screen.queryByTestId('field-property-maxLength')).toBeNull();
    fireEvent.change(screen.getByLabelText('Minimum value'), { target: { value: '1.25' } });
    expect(change).toHaveBeenLastCalledWith({ minValue: 1.25 });
    view.unmount();
    render(<FieldPropertyEditor fieldRef="amount" dataType="decimal" blockType="table" onChange={change} onClose={vi.fn()} />);
    expandSection('layout');
    fireEvent.change(screen.getByLabelText('Fixed column'), { target: { value: 'right' } });
    expect(change).toHaveBeenLastCalledWith({ fixed: 'right' });
    fireEvent.change(screen.getByLabelText('Render type'), { target: { value: 'currency' } });
    expect(change).toHaveBeenLastCalledWith({ render: 'currency' });
    fireEvent.change(screen.getByLabelText('Fixed column'), { target: { value: '' } });
    expect(change).toHaveBeenLastCalledWith({ fixed: undefined });
  });
  it('retains lowercase registry component values and respects readonly controls', () => {
    fixture.renderComponents = [{ code: 'fixture-input', dataTypes: ['string'] }];
    render(<FieldPropertyEditor fieldRef="account_name" blockType="form-section" readonly onChange={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByLabelText('Component type')).toBeDisabled();
    expect(screen.getByRole('option', { name: 'fixture-input' })).toHaveValue('fixture-input');
    expect(screen.getByLabelText('Maximum length')).toBeDisabled();
    expect(screen.getByTestId('field-property-required-switch')).toBeDisabled();
  });
  it('localizes the widget property heading without changing the props namespace', () => {
    vi.spyOn(WidgetRegistry, 'getSchema').mockReturnValue([{ key: 'note', type: 'text', label: 'Note' }]);
    vi.spyOn(WidgetRegistry, 'getName').mockReturnValue('Fixture widget');
    const change = vi.fn();
    const view = render(<FieldPropertyEditor fieldRef={{ field: 'account_name', component: 'fixture-input', props: { note: 'old', retained: true } }} blockType="form-section" onChange={change} onClose={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Fixture widget Properties' })).toBeTruthy();
    fireEvent.change(screen.getByTestId('widget-prop-note').querySelector('input')!, { target: { value: 'new' } });
    expect(change).toHaveBeenLastCalledWith({ props: { note: 'new', retained: true } });
    fixture.locale = 'zh-CN';
    view.rerender(<FieldPropertyEditor fieldRef={{ field: 'account_name', component: 'fixture-input' }} blockType="form-section" onChange={change} onClose={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Fixture widget \u5c5e\u6027' })).toBeTruthy();
  });
});
