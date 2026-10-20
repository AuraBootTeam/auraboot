import React from 'react';
import { afterEach, describe, it, expect, vi } from 'vitest';
import { render, fireEvent, cleanup, screen, within, act } from '@testing-library/react';
import { I18nProvider } from '~/contexts/I18nContext';
import { WidgetPropertyPanel } from '../WidgetPropertyPanel';
import { useDashboardStore } from '../../store/useDashboardStore';
import { widgetRegistry } from '../../widgets/widgetRegistry';
import { dashboardPropertySchemas } from '../dashboardPropertySchemas';

// Complex editors have their own tests; this suite exercises the real basic schema/store seam.
vi.mock('../DataSourceConfig',()=>({DataSourceConfig:()=>null}));
vi.mock('../DrilldownConfig',()=>({DrilldownConfig:()=>null}));
afterEach(cleanup);
it('keeps every registered widget schema and its custom-control declarations reachable',()=>{
  expect(widgetRegistry.getAll().length).toBeGreaterThan(20);
  for(const definition of widgetRegistry.getAll()) {
    const schema=dashboardPropertySchemas(definition.configSchema??[],k=>k,'en-US');
    const keys=schema.map(field=>field.key);
    expect(new Set(keys).size,definition.type).toBe(keys.length);
    for(const original of definition.configSchema??[]) {
      if(!original.key.startsWith('dataSource.')) expect(keys,definition.type).toContain(original.key);
    }
    expect(schema.find(field=>field.key==='dataSource')?.editor).toBe('dashboard.data-source');
  }
});
describe('dashboard schema document properties',()=>{
  it('changes a zero numeric value, nested toggles and defaults through real undo/redo',()=>{
    const store=useDashboardStore.getState(); store.createDashboard('Schema board');
    const id=store.addWidget({type:'smart-number-card',componentType:'smart-number-card',props:{},x:0,y:0,w:3,h:2,config:{title:'Revenue',precision:0,dataSource:{type:'static'}}});
    useDashboardStore.getState().selectWidget(id);
    render(<I18nProvider initialLocale="en-US" initialData={{fixture:'loaded'}}><WidgetPropertyPanel /></I18nProvider>);
    expect(within(screen.getByTestId('schema-config-field-refreshInterval')).getByRole('spinbutton')).toHaveValue(0);
    const radius=within(screen.getByTestId('schema-config-field-style.borderRadius')).getByRole('spinbutton');
    fireEvent.change(radius,{target:{value:'12'}});
    const widget=()=>useDashboardStore.getState().widgets.find(widget=>widget.id===id)!;
    expect(widget().config.style?.borderRadius).toBe(12);
    expect(widget().config.precision).toBe(0);
    act(()=>useDashboardStore.getState().undo()); expect(widget().config.style).toBeUndefined();
    act(()=>useDashboardStore.getState().redo()); expect(widget().config.style?.borderRadius).toBe(12);
    expect(screen.getByTestId('schema-config-field-style.legendPosition')).toBeInTheDocument();
    fireEvent.click(within(screen.getByTestId('schema-config-field-style.showLegend')).getByRole('switch'));
    expect(widget().config.style?.showLegend).toBe(false);
    expect(screen.queryByTestId('schema-config-field-style.legendPosition')).not.toBeInTheDocument();
    fireEvent.change(within(screen.getByTestId('schema-config-field-style.borderRadius')).getByRole('spinbutton'),{target:{value:'99'}});
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(widget().config.style?.borderRadius).toBe(12);
  });
});
