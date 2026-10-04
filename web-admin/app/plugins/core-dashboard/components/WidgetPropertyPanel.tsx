/**
 * Widget Property Panel
 * Panel for editing selected widget properties
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import { LocalizedTextInput } from '~/shared/designer';
import { widgetText, type WidgetTextKey } from '../widgets/widgetText';
import { useDashboardStore } from '../store/useDashboardStore';
import { widgetRegistry } from '../widgets/widgetRegistry';
import type {
  PropertySchema,
  WidgetConfig,
  LinkageConfig as LinkageConfigType,
  StyleSettings,
} from '../types';
import { IconPicker } from '~/plugins/core-designer/components/studio/workbench/panels/property-editors/IconPicker';
import { DataSourceConfig } from './DataSourceConfig';
import { LinkageConfig } from './LinkageConfig';
import { StyleConfig } from './StyleConfig';
import { RefreshConfig } from './RefreshConfig';
import { DrilldownConfig } from './DrilldownConfig';
import type { ChartDataSource, DrillDownConfig } from '~/framework/smart/types/chart';

interface WidgetPropertyPanelProps {
  className?: string;
}

/**
 * Property field renderer
 */
const PropertyField: React.FC<{
  schema: PropertySchema;
  value: unknown;
  onChange: (value: unknown) => void;
}> = ({ schema, value, onChange }) => {
  const { locale, t } = useI18n();
  const l = (key: WidgetTextKey) => getLocalizedText(widgetText(key), locale, t);
  const fieldTestId = `widget-prop-${schema.key.replace(/[^a-zA-Z0-9_-]/g, '-')}`;

  const renderField = () => {
    switch (schema.type) {
      case 'localizedText': {
        const text = typeof value === 'string' ? value
          : value && typeof value === 'object' && !Array.isArray(value)
            && Object.values(value).every((entry) => typeof entry === 'string')
            ? value as Record<string, string> : undefined;
        return <div data-testid={fieldTestId}><LocalizedTextInput value={text} onChange={onChange}
          placeholder={getLocalizedText(schema.placeholder, locale, t)} testId={fieldTestId} /></div>;
      }

      case 'text':
        return (
          <input
            data-testid={fieldTestId}
            type="text"
            value={(value as string) || ''}
            onChange={(e) => onChange(e.target.value)}
            placeholder={getLocalizedText(schema.placeholder, locale, t)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        );

      case 'number':
        return (
          <input
            data-testid={fieldTestId}
            type="number"
            value={(value as number) || 0}
            onChange={(e) => onChange(Number(e.target.value))}
            placeholder={getLocalizedText(schema.placeholder, locale, t)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        );

      case 'boolean':
        return (
          <label className="flex cursor-pointer items-center gap-2">
            <input
              data-testid={fieldTestId}
              type="checkbox"
              checked={Boolean(value)}
              onChange={(e) => onChange(e.target.checked)}
              className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="text-sm text-gray-700">{getLocalizedText(schema.label, locale, t)}</span>
          </label>
        );

      case 'select':
        return (
          <select
            data-testid={fieldTestId}
            value={(value as string) || (schema.defaultValue as string) || ''}
            onChange={(e) => onChange(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            <option value="">{l('panel.select')}</option>
            {schema.options?.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {getLocalizedText(opt.label, locale, t)}
              </option>
            ))}
          </select>
        );

      case 'icon':
        return (
          <div data-testid={fieldTestId}>
            <IconPicker
              value={typeof value === 'string' ? value : ''}
              onChange={(next) => onChange(next)}
              className="w-full"
            />
          </div>
        );

      case 'model':
        // TODO: Implement model selector
        return (
          <input
            data-testid={fieldTestId}
            type="text"
            value={(value as string) || ''}
            onChange={(e) => onChange(e.target.value)}
            placeholder={l('panel.modelCode')}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        );

      case 'namedQuery':
        // TODO: Implement named query selector
        return (
          <input
            data-testid={fieldTestId}
            type="text"
            value={(value as string) || ''}
            onChange={(e) => onChange(e.target.value)}
            placeholder={l('panel.queryCode')}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        );

      case 'json':
        return (
          <textarea
            data-testid={fieldTestId}
            value={typeof value === 'string' ? value : JSON.stringify(value, null, 2)}
            onChange={(e) => {
              try {
                onChange(JSON.parse(e.target.value));
              } catch {
                // Keep raw string if not valid JSON
                onChange(e.target.value);
              }
            }}
            className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            rows={4}
          />
        );

      default:
        return (
          <input
            data-testid={fieldTestId}
            type="text"
            value={String(value || '')}
            onChange={(e) => onChange(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        );
    }
  };

  if (schema.type === 'boolean') {
    return <div className="mb-4">{renderField()}</div>;
  }

  return (
    <div className="mb-4">
      <label className="mb-1 block text-sm font-medium text-gray-700">
        {getLocalizedText(schema.label, locale, t)}
        {schema.required && <span className="ml-1 text-red-500">*</span>}
      </label>
      {renderField()}
      {schema.description && <p className="mt-1 text-xs text-gray-500">{getLocalizedText(schema.description, locale, t)}</p>}
    </div>
  );
};

/**
 * Get nested value from object using dot notation
 */
function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce((acc: unknown, part) => {
    if (acc && typeof acc === 'object') {
      return (acc as Record<string, unknown>)[part];
    }
    return undefined;
  }, obj);
}

/**
 * Set nested value in object using dot notation
 */
function setNestedValue(
  obj: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const parts = path.split('.');
  const result = { ...obj };
  let current: Record<string, unknown> = result;

  for (let i = 0; i < parts.length - 1; i++) {
    const part = parts[i];
    if (!current[part] || typeof current[part] !== 'object') {
      current[part] = {};
    }
    current[part] = { ...(current[part] as Record<string, unknown>) };
    current = current[part] as Record<string, unknown>;
  }

  current[parts[parts.length - 1]] = value;
  return result;
}

export const WidgetPropertyPanel: React.FC<WidgetPropertyPanelProps> = ({ className = '' }) => {
  const { locale, t } = useI18n();
  const l = (key: WidgetTextKey) => getLocalizedText(widgetText(key), locale, t);
  const { selectedWidgetId, getWidgetById, updateWidgetConfig, deleteWidget, duplicateWidget } =
    useDashboardStore();

  const widget = selectedWidgetId ? getWidgetById(selectedWidgetId) : undefined;
  const widgetDef = widget ? widgetRegistry.get(widget.type) : undefined;

  if (!widget || !widgetDef) {
    return (
      <div
        data-testid="widget-property-panel"
        className={`w-72 border-l border-gray-200 bg-white ${className}`}
      >
        <div className="border-b border-gray-200 p-4">
          <h2 className="text-sm font-semibold text-gray-700">{l('panel.properties')}</h2>
        </div>
        <div className="p-4 text-center text-sm text-gray-500">{l('panel.selectWidget')}</div>
      </div>
    );
  }

  const handlePropertyChange = (key: string, value: unknown) => {
    const updatedConfig = setNestedValue(
      widget.config as unknown as Record<string, unknown>,
      key,
      value,
    );
    updateWidgetConfig(widget.id, updatedConfig as unknown as Partial<WidgetConfig>);
  };

  const handleDelete = () => {
    if (confirm(l('panel.confirmDelete'))) {
      deleteWidget(widget.id);
    }
  };

  const handleDuplicate = () => {
    duplicateWidget(widget.id);
  };

  // Check if a property should be visible based on dependsOn
  const isPropertyVisible = (schema: PropertySchema): boolean => {
    if (!schema.dependsOn) return true;
    const dependValue = getNestedValue(
      widget.config as unknown as Record<string, unknown>,
      schema.dependsOn.field,
    );
    if (Array.isArray(schema.dependsOn.value)) {
      return schema.dependsOn.value.includes(dependValue);
    }
    return dependValue === schema.dependsOn.value;
  };

  return (
    <div
      data-testid="widget-property-panel"
      className={`flex w-72 flex-col border-l border-gray-200 bg-white ${className}`}
    >
      <div className="border-b border-gray-200 p-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold text-gray-700">{getLocalizedText(widgetDef.label, locale, t)}</h2>
          <div className="flex items-center gap-1">
            <button
              onClick={handleDuplicate}
              className="rounded p-1.5 text-gray-500 hover:bg-blue-50 hover:text-blue-600"
              title={l('panel.duplicate')}
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                />
              </svg>
            </button>
            <button
              onClick={handleDelete}
              className="rounded p-1.5 text-gray-500 hover:bg-red-50 hover:text-red-600"
              title={l('panel.delete')}
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                />
              </svg>
            </button>
          </div>
        </div>
        {widgetDef.description && (
          <p className="mt-1 text-xs text-gray-500">{getLocalizedText(widgetDef.description, locale, t)}</p>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {widgetDef.configSchema?.filter(isPropertyVisible).map((schema) => (
          <PropertyField
            key={schema.key}
            schema={schema}
            value={getNestedValue(widget.config as unknown as Record<string, unknown>, schema.key)}
            onChange={(value) => handlePropertyChange(schema.key, value)}
          />
        ))}

        {/* Data Source Configuration */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.dataSource')}
          </h3>
          <DataSourceConfig
            value={
              (widget.config.dataSource as ChartDataSource) || {
                type: 'aggregate',
                metrics: [{ field: 'id', aggregation: 'count' }],
              }
            }
            onChange={(dataSource) => handlePropertyChange('dataSource', dataSource)}
          />
        </div>

        {/* Style Configuration */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.style')}
          </h3>
          <StyleConfig
            value={(widget.config.style as StyleSettings) || {}}
            onChange={(style) => handlePropertyChange('style', style)}
          />
        </div>

        {/* Linkage Configuration */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.linkage')}
          </h3>
          <LinkageConfig
            value={(widget.config.linkage as LinkageConfigType) || { enabled: false }}
            onChange={(linkage) => handlePropertyChange('linkage', linkage)}
          />
        </div>

        {/* Drilldown Configuration */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.drilldown')}
          </h3>
          <DrilldownConfig
            value={
              (widget.config.drillDown as DrillDownConfig) || { enabled: false, action: 'filter' }
            }
            onChange={(drillDown) => handlePropertyChange('drillDown', drillDown)}
          />
        </div>

        {/* Refresh Configuration */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.refresh')}
          </h3>
          <RefreshConfig
            value={widget.config.refreshInterval || 0}
            onChange={(interval) => handlePropertyChange('refreshInterval', interval)}
          />
        </div>

        {/* Position section */}
        <div className="mt-6 border-t border-gray-200 pt-4">
          <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
            {l('panel.positionSize')}
          </h3>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1 block text-xs text-gray-600">X</label>
              <input
                type="number"
                value={widget.x}
                readOnly
                className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1 text-sm"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-600">Y</label>
              <input
                type="number"
                value={widget.y}
                readOnly
                className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1 text-sm"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-600">{l('panel.width')}</label>
              <input
                type="number"
                value={widget.w}
                readOnly
                className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1 text-sm"
              />
            </div>
            <div>
              <label className="mb-1 block text-xs text-gray-600">{l('panel.height')}</label>
              <input
                type="number"
                value={widget.h}
                readOnly
                className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1 text-sm"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default WidgetPropertyPanel;
