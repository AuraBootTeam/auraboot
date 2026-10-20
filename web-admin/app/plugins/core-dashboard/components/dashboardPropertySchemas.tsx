import React from 'react';
import type { PropertySchema } from '../types';
import type { ExtendedPropertySchema as Schema } from '~/shared/designer/SchemaBlockConfigPanel';
import { propertyEditorRegistry, type RegisteredPropertyEditorProps } from '~/shared/designer/PropertyEditorRegistry';
import { getLocalizedText } from '~/utils/i18n';
import { DataSourceConfig } from './DataSourceConfig';
import { DrilldownConfig } from './DrilldownConfig';
import type { ChartDataSource, DrillDownConfig } from '~/framework/smart/types/chart';

function DataSourceEditor({ adapter }: RegisteredPropertyEditorProps) {
  return <fieldset disabled={adapter.disabled} className="min-w-0">
    <DataSourceConfig value={(adapter.value as ChartDataSource) ?? {type:'aggregate',metrics:[{field:'id',aggregation:'count'}]}}
      onChange={value => adapter.setValue(value)} />
  </fieldset>;
}
function DrilldownEditor({ adapter }: RegisteredPropertyEditorProps) {
  return <fieldset disabled={adapter.disabled} className="min-w-0">
    <DrilldownConfig value={(adapter.value as DrillDownConfig) ?? {enabled:false,action:'filter'}}
      onChange={value => adapter.setValue(value)} />
  </fieldset>;
}
const disposeDataSource = propertyEditorRegistry.register('dashboard.data-source', DataSourceEditor);
const disposeDrilldown = propertyEditorRegistry.register('dashboard.drilldown', DrilldownEditor);
import.meta.hot?.dispose(()=>{disposeDataSource();disposeDrilldown();});

export function dashboardPropertySchemas(config: PropertySchema[], label: (key: 'panel.dataSource' | 'panel.style' | 'panel.linkage' | 'panel.drilldown' | 'panel.refresh') => string, locale: string): Schema<string>[] {
  // The same schema declares every basic field. Complex controls are named extensions.
  return [
    ...config.filter(schema => !schema.key.startsWith('dataSource.')).map(schema => ({...schema,label:getLocalizedText(schema.label,locale),group:schema.group ? getLocalizedText(schema.group,locale) : undefined,options:schema.options?.map(option=>({...option,label:getLocalizedText(option.label,locale)})),testId:`widget-prop-${schema.key.replace(/[^a-zA-Z0-9_-]/g,'-')}`})) as Schema<string>[],
    {key:'dataSource',label:label('panel.dataSource'),group:label('panel.dataSource'),type:'text',editor:'dashboard.data-source'},
    ...styleSchemas(label('panel.style'), locale),
    ...linkageSchemas(label('panel.linkage'), locale),
    {key:'drillDown',label:label('panel.drilldown'),group:label('panel.drilldown'),type:'text',editor:'dashboard.drilldown'},
    {key:'refreshInterval',label:label('panel.refresh'),type:'number',min:0,defaultValue:0},
  ];
}

function localized(zh: string, en: string, locale: string): string {
  return getLocalizedText({zh,en}, locale);
}
function styleSchemas(group: string, locale: string): Schema<string>[] {
  const field = (key: string, zh: string, en: string, type: Schema['type'], extra: Partial<Schema<string>> = {}): Schema<string> => ({key:`style.${key}`,label:localized(zh,en,locale),type,group,...extra});
  return [
    field('colorTheme','配色主题','Color theme','select',{defaultValue:'default',options:[['default','默认','Default'],['vintage','复古','Vintage'],['dark','暗色','Dark'],['westeros','西部','Westeros'],['macarons','马卡龙','Macarons']].map(([value,zh,en])=>({value,label:localized(zh,en,locale)}))}),
    field('showTitle','显示标题','Show title','boolean',{defaultValue:true}),
    field('showLegend','显示图例','Show legend','boolean',{defaultValue:true}),
    field('legendPosition','图例位置','Legend position','select',{defaultValue:'bottom',dependsOn:{field:'style.showLegend',value:true},options:[['top','上方','Top'],['bottom','下方','Bottom'],['left','左侧','Left'],['right','右侧','Right']].map(([value,zh,en])=>({value,label:localized(zh,en,locale)}))}),
    field('showLabel','显示标签','Show label','boolean',{defaultValue:false}),
    field('labelPosition','标签位置','Label position','select',{defaultValue:'outside',dependsOn:{field:'style.showLabel',value:true},options:[['inside','内部','Inside'],['outside','外部','Outside'],['top','上方','Top']].map(([value,zh,en])=>({value,label:localized(zh,en,locale)}))}),
    field('borderRadius','圆角','Border radius','number',{min:0,max:24,defaultValue:8}),
    field('backgroundColor','背景色','Background color','text',{defaultValue:'#ffffff',editor:'designer.color'}),
  ];
}
function linkageSchemas(group: string, locale: string): Schema<string>[] {
  return [
    {key:'linkage.enabled',label:localized('启用联动','Enable linkage',locale),group,type:'boolean',defaultValue:false},
    ...[
      {key:'linkage.groupId',label:localized('联动分组','Linkage group',locale),type:'text',defaultValue:'default'},
      {key:'linkage.emitFilter',label:localized('发送筛选条件','Emit filter',locale),type:'boolean',defaultValue:true},
      {key:'linkage.receiveFilter',label:localized('接收筛选条件','Receive filter',locale),type:'boolean',defaultValue:true},
    ].map(schema=>({...schema,group,dependsOn:{field:'linkage.enabled',value:true}})) as Schema<string>[],
  ];
}
