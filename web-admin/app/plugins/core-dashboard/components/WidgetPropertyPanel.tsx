/**
 * Widget Property Panel
 * Panel for editing selected widget properties
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { widgetText } from '../widgets/widgetText';
import { useDashboardStore } from '../store/useDashboardStore';
import { widgetRegistry } from '../widgets/widgetRegistry';
import type { WidgetConfig } from '../types';
import { dashboardPropertySchemas } from './dashboardPropertySchemas';

interface WidgetPropertyPanelProps {
  className?: string;
}

export const WidgetPropertyPanel: React.FC<WidgetPropertyPanelProps> = ({ className = '' }) => {
  const { locale, t } = useI18n();
  const l = (key: Parameters<typeof widgetText>[0]) => getLocalizedText(widgetText(key), locale, t);
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

  const handleDelete = () => {
    if (confirm(l('panel.confirmDelete'))) {
      deleteWidget(widget.id);
    }
  };

  const handleDuplicate = () => {
    duplicateWidget(widget.id);
  };

  return (
    <div
      data-testid="widget-property-panel"
      data-widget-id={widget.id}
      className={`flex w-72 shrink-0 flex-col border-l border-gray-200 bg-white ${className}`}
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
        <SchemaBlockConfigPanel key={widget.id} density="compact" pathMode="nested"
          schemas={dashboardPropertySchemas(widgetDef.configSchema ?? [], l, locale, widget.type)}
          value={widget.config as unknown as Record<string, unknown>}
          onChange={next => updateWidgetConfig(widget.id, next as Partial<WidgetConfig>)} />

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
