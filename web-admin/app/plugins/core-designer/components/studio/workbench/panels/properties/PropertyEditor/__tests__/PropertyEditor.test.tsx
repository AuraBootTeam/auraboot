import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PropertyEditor } from '../PropertyEditor';

const fixture = vi.hoisted(() => ({ locale: 'en-US', save: vi.fn(), load: vi.fn(async () => null) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: fixture.locale, t: (key: string) => key }) }));
vi.mock('~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager', () => ({ getPropertyPersistenceManager: () => ({ loadComponentProperties: fixture.load, savePropertyChange: fixture.save }) }));
afterEach(() => { cleanup(); fixture.save.mockClear(); });

function mount(type: string, ruleValue: unknown, custom?: string) {
  const onPropertyChange = vi.fn(); const onValidationError = vi.fn(); const onComponentChange = vi.fn();
  const component = { id: 'owned-component', type: 'smart-input', props: { field: 'old' } } as React.ComponentProps<typeof PropertyEditor>['component'];
  const config = { name: 'Configured name', description: 'Configured description', propertySchema: [{ key: 'field', type: type === 'min' || type === 'max' ? 'number' : 'string', label: 'Field', validation: [{ type, value: ruleValue, message: custom }] }] } as unknown as React.ComponentProps<typeof PropertyEditor>['config'];
  render(<PropertyEditor component={component} config={config} onComponentChange={onComponentChange} onPropertyChange={onPropertyChange} onValidationError={onValidationError} />);
  return { onPropertyChange, onValidationError, onComponentChange };
}
const cases = [
  ['required', true, '', 'Field is required', 'Field \u662f\u5fc5\u586b\u9879'],
  ['minLength', 3, 'ab', 'Field requires at least 3 characters', 'Field \u6700\u5c11\u9700\u8981 3 \u4e2a\u5b57\u7b26'],
  ['maxLength', 3, 'abcd', 'Field allows at most 3 characters', 'Field \u6700\u591a\u5141\u8bb8 3 \u4e2a\u5b57\u7b26'],
  ['min', 3, '2', 'Field must be at least 3', 'Field \u6700\u5c0f\u503c\u4e3a 3'],
  ['max', 3, '4', 'Field must be at most 3', 'Field \u6700\u5927\u503c\u4e3a 3'],
  ['pattern', '^A', 'B', 'Field has an invalid format', 'Field \u683c\u5f0f\u4e0d\u6b63\u786e'],
] as const;
describe('property editor validation localization', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    for (const [type, ruleValue, invalid, english, chinese] of cases) {
      it(`${locale} ${type} rejects invalid values with localized feedback and zero writes`, () => {
        fixture.locale = locale;
        const callbacks = mount(type, ruleValue);
        fireEvent.change(screen.getByLabelText('Field'), { target: { value: invalid } });
        const message = locale === 'en-US' ? english : chinese;
        expect(screen.getByText(message)).toBeInTheDocument();
        expect(callbacks.onValidationError).toHaveBeenLastCalledWith('field', message);
        expect(callbacks.onComponentChange).not.toHaveBeenCalled();
        expect(callbacks.onPropertyChange).not.toHaveBeenCalled();
        expect(fixture.save).not.toHaveBeenCalled();
      });
    }
  }
  it('keeps an explicit rule message and clears the error before persisting a valid value', () => {
    fixture.locale = 'en-US';
    const callbacks = mount('minLength', 3, 'Declared constraint');
    const input = screen.getByLabelText('Field');
    fireEvent.change(input, { target: { value: 'a' } });
    expect(screen.getByText('Declared constraint')).toBeInTheDocument();
    expect(fixture.save).not.toHaveBeenCalled();
    fireEvent.change(input, { target: { value: 'valid' } });
    expect(screen.queryByText('Declared constraint')).not.toBeInTheDocument();
    expect(callbacks.onValidationError).toHaveBeenLastCalledWith('field', null);
    expect(callbacks.onComponentChange).toHaveBeenLastCalledWith('owned-component', { props: { field: 'valid' } });
    expect(callbacks.onPropertyChange).toHaveBeenLastCalledWith('field', 'valid');
    expect(fixture.save).toHaveBeenLastCalledWith('owned-component', 'field', 'valid', 'old');
  });
});


describe('property editor group labels', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    it(`${locale} shows all five groups while preserving declared metadata`, () => {
      fixture.locale = locale;
      const groups = ['basic', 'validation', 'style', 'behavior', 'data'];
      const titles = locale === 'en-US' ? ['Basic properties', 'Validation rules', 'Appearance', 'Behavior', 'Data'] : ['\u57fa\u7840\u5c5e\u6027', '\u9a8c\u8bc1\u89c4\u5219', '\u5916\u89c2\u6837\u5f0f', '\u884c\u4e3a\u914d\u7f6e', '\u6570\u636e\u914d\u7f6e'];
      const component = { id: 'owned-groups', type: 'smart-input', props: {} } as React.ComponentProps<typeof PropertyEditor>['component'];
      const config = { name: 'Configured name', description: 'Configured description', propertySchema: groups.map(group => ({ key: group, type: 'string', label: group, group })) } as unknown as React.ComponentProps<typeof PropertyEditor>['config'];
      render(<PropertyEditor component={component} config={config} />);
      expect(screen.getByText('Configured name')).toBeInTheDocument();
      expect(screen.getByText('Configured description')).toBeInTheDocument();
      for (const title of titles) expect(screen.getByText(title)).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: new RegExp(titles[2]) }));
      expect(screen.getByLabelText('style')).toBeInTheDocument();
    });
  }
});


describe('property editor missing selection and width branch', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    for (const missing of ['component', 'config']) {
      it(`${locale} missing ${missing} shows the empty state without writes`, () => {
        fixture.locale = locale;
        const onComponentChange = vi.fn(); const onPropertyChange = vi.fn();
        const component = { id: 'owned-empty', type: 'smart-input', props: {} };
        const config = { name: 'Configured name', propertySchema: [] } as unknown as NonNullable<React.ComponentProps<typeof PropertyEditor>['config']>;
        render(<PropertyEditor component={missing === 'component' ? null : component} config={missing === 'config' ? null : config} onComponentChange={onComponentChange} onPropertyChange={onPropertyChange} />);
        expect(screen.getByText(locale === 'en-US' ? 'Select a component to edit its properties' : '\u8bf7\u9009\u62e9\u4e00\u4e2a\u7ec4\u4ef6\u8fdb\u884c\u5c5e\u6027\u7f16\u8f91')).toBeInTheDocument();
        expect(onComponentChange).not.toHaveBeenCalled();
        expect(onPropertyChange).not.toHaveBeenCalled();
        expect(fixture.save).not.toHaveBeenCalled();
      });
    }
  }
  it('valid width updates props, span and size.span with the same persisted value', () => {
    fixture.locale = 'en-US';
    const onComponentChange = vi.fn(); const onPropertyChange = vi.fn();
    const component = { id: 'owned-width', type: 'smart-input', props: { width: 2 }, span: 2, size: { width: 100, height: 30, span: 2 } };
    const config = { name: 'Configured name', propertySchema: [{ key: 'width', label: 'Width', type: 'number' }] } as unknown as NonNullable<React.ComponentProps<typeof PropertyEditor>['config']>;
    render(<PropertyEditor component={component} config={config} onComponentChange={onComponentChange} onPropertyChange={onPropertyChange} />);
    fireEvent.change(screen.getByLabelText('Width'), { target: { value: '5' } });
    expect(onComponentChange).toHaveBeenLastCalledWith('owned-width', { props: { width: 5 }, span: 5, size: { width: 100, height: 30, span: 5 } });
    expect(onPropertyChange).toHaveBeenLastCalledWith('width', 5);
    expect(fixture.save).toHaveBeenLastCalledWith('owned-width', 'width', 5, 2);
  });
});


describe('property width consistency with actual local storage', () => {
  for (const [type, inputValue, expected] of [['number', '20', 12], ['number', '-2', 1], ['number', '0', 1], ['string', '800px', '800px'], ['string', '70vw', '70vw']] as const) {
    it(`${type} width ${inputValue} preserves one value across updates, storage and export`, async () => {
      const { PropertyPersistenceManager } = await vi.importActual<typeof import('~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager')>('~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager');
      vi.useFakeTimers();
      const manager = new PropertyPersistenceManager({ debounceDelay: 0, autoSaveInterval: 0, enableUndoRedo: false });
      const id = `owned-width-${type}-${inputValue}`;
      const storageKey = `component-properties-${id}`;
      window.localStorage.setItem(storageKey, JSON.stringify({ width: 2, unrelated: 'preserved' }));
      fixture.save.mockImplementation(manager.savePropertyChange.bind(manager));
      try {
        const onComponentChange = vi.fn(); const onPropertyChange = vi.fn();
        const component = { id, type: 'formref', props: { width: 2 }, span: 2, size: { width: 100, height: 30, span: 2 } };
        const config = { name: 'Configured name', propertySchema: [{ key: 'width', label: 'Width', type }] } as unknown as NonNullable<React.ComponentProps<typeof PropertyEditor>['config']>;
        render(<PropertyEditor component={component} config={config} onComponentChange={onComponentChange} onPropertyChange={onPropertyChange} />);
        fireEvent.change(screen.getByLabelText('Width'), { target: { value: inputValue } });
        await vi.runAllTimersAsync();
        const stored = await manager.loadComponentProperties(id);
        expect(stored).toEqual({ width: expected, unrelated: 'preserved' });
        expect((await manager.exportComponentProperties(component)).properties).toEqual(stored);
        const expectedUpdates = type === 'number' ? { props: { width: expected }, span: expected, size: { width: 100, height: 30, span: expected } } : { props: { width: expected } };
        expect(onComponentChange).toHaveBeenLastCalledWith(id, expectedUpdates);
        expect(onPropertyChange).toHaveBeenLastCalledWith('width', expected);
        expect(fixture.save).toHaveBeenLastCalledWith(id, 'width', expected, 2);
      } finally {
        cleanup(); manager.destroy(); fixture.save.mockReset();
        window.localStorage.removeItem(storageKey); vi.useRealTimers();
      }
    });
  }
});
