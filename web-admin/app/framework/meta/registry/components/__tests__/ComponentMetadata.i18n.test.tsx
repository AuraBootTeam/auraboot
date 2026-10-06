import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { ALL_COMPONENT_CONFIGS } from '../ComponentConfigs';
import { COMPONENT_CATEGORIES } from '../ComponentConfig';
import { componentRegistry } from '../ComponentRegistry';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { ComponentPalette } from '~/plugins/core-designer/components/studio/workbench/palette/ComponentPalette/ComponentPalette';
import { SmartComponentLibrary } from '~/plugins/core-designer/components/studio/workbench/designers/areas/SmartComponentLibrary';
import { PropertyEditor } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/PropertyEditor';
import { BindablePropertyInput } from '~/plugins/core-designer/components/studio/workbench/panels/properties/BindablePropertyInput';
import { PropertyInput } from '~/plugins/core-designer/components/studio/workbench/panels/properties/PropertyEditor/PropertyInput';

const fixture = vi.hoisted(() => ({ locale: 'en-GB', drags: new Map<string, any>(), save: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: fixture.locale, t: (key: string) => key }) }));
vi.mock('~/plugins/core-designer/components/studio/workbench/components/expression-editor', () => ({ ExpressionInput: () => null }));
vi.mock('@dnd-kit/core', () => ({ useDraggable: (options: any) => {
  fixture.drags.set(String(options.id), options);
  return { attributes: { 'data-testid': `drag-${options.id}` }, listeners: {}, setNodeRef: vi.fn(), isDragging: false, transform: null };
} }));
vi.mock('~/plugins/core-designer/components/studio/services/state/PropertyPersistenceManager', () => ({ getPropertyPersistenceManager: () => ({ loadComponentProperties: async () => null, savePropertyChange: fixture.save }) }));
vi.mock('~/plugins/core-designer/components/studio/workbench/panels/property-editors/IconPicker', () => ({ IconPicker: () => null }));
beforeEach(() => {
  fixture.locale = 'en-GB'; fixture.drags.clear(); fixture.save.mockClear();
  componentRegistry.clear(); componentRegistry.registerBatch(ALL_COMPONENT_CONFIGS);
});
afterEach(cleanup);
const english = (value: any) => getLocalizedText(value, 'en-GB');
function expectEnglish(value: any) { expect(english(value).trim()).not.toBe(''); expect(english(value)).not.toMatch(/[一-鿿]/); }

describe('Smart component metadata locale consumers', () => {
  it('provides English registry metadata without pruning supported component types', () => {
    expect(ALL_COMPONENT_CONFIGS.map(config => config.type)).toEqual(["input", "textarea", "json-editor", "select", "checkbox", "radio", "datepicker", "formref", "timepicker", "switch", "numberinput", "upload", "display", "image", "table", "decisionrolloutmonitor", "decisionfieldimpact", "decisionintegrationimpact", "decisionrulebinding", "decisionactionplan", "button", "div", "form", "navigation", "container", "grid", "flex", "columns", "card", "date", "datetime", "smart-number-card", "smart-bar-chart", "smart-line-chart", "smart-pie-chart", "smart-kanban", "bar-chart", "line-chart", "pie-chart", "area-chart"]);
    for (const config of ALL_COMPONENT_CONFIGS) {
      expectEnglish(config.name); expectEnglish(config.description);
      for (const property of config.propertySchema) {
        expectEnglish(property.label);
        if (property.description) expectEnglish(property.description);
        for (const option of property.options ?? []) expectEnglish(option.label);
      }
    }
    for (const category of COMPONENT_CATEGORIES) { expectEnglish(category.name); expectEnglish(category.description); }
  });
  it('searches the requested locale while preserving default Chinese search and tag identity', () => {
    expect(componentRegistry.searchComponents('single-line', 'en-GB').map(config => config.type)).toEqual(['input']);
    expect(componentRegistry.searchComponents('单行').map(config => config.type)).toEqual(['input']);
    expect(componentRegistry.searchComponents('decision-binding', 'en-GB').map(config => config.type)).toContain('decisionrulebinding');
  });
  it('renders and searches the palette in English, then updates it after locale changes', () => {
    const view = render(<ComponentPalette />);
    expect(screen.getByTestId('drag-palette-input')).toHaveTextContent('Input');
    const query = screen.getByPlaceholderText('Search components...');
    fireEvent.change(query, { target: { value: 'single-line' } });
    expect(screen.getByTestId('drag-palette-input')).toHaveTextContent('Input');
    expect(screen.queryByTestId('drag-palette-textarea')).not.toBeInTheDocument();
    expect(fixture.drags.get('palette-input').data.component.type).toBe('input');
    fireEvent.change(query, { target: { value: '' } });
    fixture.locale = 'zh-CN'; view.rerender(<ComponentPalette />);
    expect(screen.getByTestId('drag-palette-input')).toHaveTextContent('输入框');
    expect(screen.getByPlaceholderText('搜索组件...')).toBeInTheDocument();
  });
  it('renders Smart library metadata while retaining exact field/block drag behavior and readonly state', () => {
    const view = render(<SmartComponentLibrary />);
    expect(screen.getByTestId('drag-smart-component:input')).toHaveTextContent('Single-line text input');
    const field = fixture.drags.get('smart-component:input');
    expect(field.data.componentType).toBe('input'); expect(field.data.dragBehavior).toBe('add-to-fields');
    expect(field.data.component.props).toEqual({ name: 'input', ...ALL_COMPONENT_CONFIGS.find(config => config.type === 'input')!.defaultProps });
    expect(fixture.drags.get('smart-component:button').data.dragBehavior).toBe('create-block');
    fireEvent.change(screen.getByTestId('library-search'), { target: { value: 'single-line' } });
    expect(screen.queryByTestId('drag-smart-component:button')).not.toBeInTheDocument();
    view.rerender(<SmartComponentLibrary readonly />);
    expect(fixture.drags.get('smart-component:input').disabled).toBe(true);
  });
  it('resolves property option labels and descriptions but emits the original option value', () => {
    const config = ALL_COMPONENT_CONFIGS.find(config => config.type === 'input')!;
    const property = config.propertySchema.find(property => property.key === 'size')!;
    const onChange = vi.fn(); render(<PropertyInput property={property} value="medium" onChange={onChange} />);
    expect(screen.getByRole('option', { name: 'Large' })).toHaveValue('large');
    fireEvent.change(screen.getByLabelText('Size'), { target: { value: 'large' } });
    expect(onChange).toHaveBeenLastCalledWith('large');
  });
  it('resolves bindable property labels without changing static values or binding mode', () => {
    const property = ALL_COMPONENT_CONFIGS.find(config => config.type === 'div')!.propertySchema.find(property => property.key === 'zIndex')!;
    const onChange = vi.fn(); render(<BindablePropertyInput property={property} value="auto" onChange={onChange} />);
    expect(screen.getByText('Z-index')).toBeInTheDocument();
    expect(screen.getAllByText('Stacking order, such as auto, 1 or 10').length).toBeGreaterThan(0);
    fireEvent.change(screen.getByDisplayValue('auto'), { target: { value: '10' } });
    expect(onChange).toHaveBeenLastCalledWith('10');
  });
  it('uses actual registry metadata in the property editor and preserves business property writes', () => {
    const config = ALL_COMPONENT_CONFIGS.find(config => config.type === 'input')!;
    const onComponentChange = vi.fn();
    render(<PropertyEditor config={config} component={{ id: 'locale-field', type: 'input', props: { name: 'original' } }} onComponentChange={onComponentChange} />);
    expect(screen.getByRole('heading', { name: 'Input' })).toBeInTheDocument();
    expect(screen.getByText('Single-line text input with validation rules')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^Field name/), { target: { value: 'updated' } });
    expect(onComponentChange).toHaveBeenLastCalledWith('locale-field', { props: { name: 'updated' } });
    expect(fixture.save).toHaveBeenLastCalledWith('locale-field', 'name', 'updated', 'original');
  });
});
