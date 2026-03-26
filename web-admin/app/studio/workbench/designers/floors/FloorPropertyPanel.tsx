/**
 * Floor Property Panel
 *
 * Right panel for editing properties of the selected floor or component.
 * - Floor selected: title, collapsible, defaultCollapsed, visible(SpEL), layout
 * - Component selected: type, config, span, visible
 */

import React from 'react';
import type { DslFloor, DslComponent } from '~/studio/domain/dsl/types';

export interface FloorPropertyPanelProps {
  selectedFloor: DslFloor | null;
  selectedComponent: DslComponent | null;
  selectedFloorId: string | null;
  onFloorChange: (floorId: string, updates: Partial<DslFloor>) => void;
  onComponentChange: (floorId: string, componentId: string, updates: Partial<DslComponent>) => void;
  onConvertToTabs: (floorId: string) => void;
  onConvertToNormal: (floorId: string) => void;
  readOnly?: boolean;
}

export const FloorPropertyPanel: React.FC<FloorPropertyPanelProps> = ({
  selectedFloor,
  selectedComponent,
  selectedFloorId,
  onFloorChange,
  onComponentChange,
  onConvertToTabs,
  onConvertToNormal,
  readOnly,
}) => {
  // If a component is selected, show component properties
  if (selectedComponent && selectedFloorId) {
    return (
      <ComponentProperties
        component={selectedComponent}
        floorId={selectedFloorId}
        onChange={onComponentChange}
        readOnly={readOnly}
      />
    );
  }

  // If a floor is selected, show floor properties
  if (selectedFloor) {
    return (
      <FloorProperties
        floor={selectedFloor}
        onChange={onFloorChange}
        onConvertToTabs={onConvertToTabs}
        onConvertToNormal={onConvertToNormal}
        readOnly={readOnly}
      />
    );
  }

  // Nothing selected
  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-gray-200 px-4 py-3">
        <h3 className="text-sm font-medium text-gray-900">Properties</h3>
      </div>
      <div className="flex flex-1 items-center justify-center p-6">
        <div className="text-center text-gray-400">
          <svg
            className="mx-auto mb-2 h-10 w-10 text-gray-300"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <p className="text-sm">Select a floor or component</p>
          <p className="mt-1 text-xs">to edit properties</p>
        </div>
      </div>
    </div>
  );
};

/**
 * Floor properties editor
 */
interface FloorPropertiesProps {
  floor: DslFloor;
  onChange: (floorId: string, updates: Partial<DslFloor>) => void;
  onConvertToTabs: (floorId: string) => void;
  onConvertToNormal: (floorId: string) => void;
  readOnly?: boolean;
}

const FloorProperties: React.FC<FloorPropertiesProps> = ({
  floor,
  onChange,
  onConvertToTabs,
  onConvertToNormal,
  readOnly,
}) => {
  const isTabsFloor = floor.type === 'TabsFloor';

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-gray-200 px-4 py-3">
        <h3 className="text-sm font-medium text-gray-900">Floor Properties</h3>
        <p className="mt-0.5 text-xs text-gray-500">
          {isTabsFloor ? 'TabsFloor' : 'Normal'} - {floor.id.slice(0, 12)}
        </p>
      </div>

      <div className="flex-1 space-y-4 overflow-auto p-4">
        {/* Title */}
        <PropertyField label="Title">
          <input
            type="text"
            value={floor.title || ''}
            onChange={(e) => onChange(floor.id, { title: e.target.value || undefined })}
            placeholder="Floor title"
            disabled={readOnly}
            className="w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-400"
          />
        </PropertyField>

        {/* Collapsible */}
        <PropertyField label="Collapsible">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={floor.collapsible || false}
              onChange={(e) => onChange(floor.id, { collapsible: e.target.checked || undefined })}
              disabled={readOnly}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="text-sm text-gray-700">Allow collapsing this floor</span>
          </label>
        </PropertyField>

        {/* Default Collapsed - only when collapsible */}
        {floor.collapsible && (
          <PropertyField label="Default Collapsed">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={floor.defaultCollapsed || false}
                onChange={(e) =>
                  onChange(floor.id, { defaultCollapsed: e.target.checked || undefined })
                }
                disabled={readOnly}
                className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700">Start collapsed</span>
            </label>
          </PropertyField>
        )}

        {/* Visible (SpEL) */}
        <PropertyField label="Visible">
          <input
            type="text"
            value={floor.visible || ''}
            onChange={(e) => onChange(floor.id, { visible: e.target.value || undefined })}
            placeholder="SpEL expression (empty = always)"
            disabled={readOnly}
            className="w-full rounded-md border border-gray-200 px-3 py-1.5 font-mono text-sm text-xs focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-400"
          />
        </PropertyField>

        {/* Floor type conversion */}
        <PropertyField label="Floor Type">
          {isTabsFloor ? (
            <button
              onClick={() => onConvertToNormal(floor.id)}
              disabled={readOnly}
              className="w-full rounded-md border border-orange-200 bg-orange-50 px-3 py-2 text-sm text-orange-700 transition-colors hover:bg-orange-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Convert to Normal Floor
            </button>
          ) : (
            <button
              onClick={() => onConvertToTabs(floor.id)}
              disabled={readOnly}
              className="w-full rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm text-purple-700 transition-colors hover:bg-purple-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Convert to Tabs Floor
            </button>
          )}
        </PropertyField>

        {/* ID (read-only) */}
        <PropertyField label="ID">
          <div className="rounded-md border border-gray-100 bg-gray-50 px-3 py-1.5 font-mono text-xs text-gray-400">
            {floor.id}
          </div>
        </PropertyField>
      </div>
    </div>
  );
};

/**
 * Component properties editor
 */
interface ComponentPropertiesProps {
  component: DslComponent;
  floorId: string;
  onChange: (floorId: string, componentId: string, updates: Partial<DslComponent>) => void;
  readOnly?: boolean;
}

/** Helper to get/set nested config property */
function getConfig(component: DslComponent, key: string): unknown {
  return (component.config as Record<string, unknown> | undefined)?.[key];
}

const ComponentProperties: React.FC<ComponentPropertiesProps> = ({
  component,
  floorId,
  onChange,
  readOnly,
}) => {
  const updateConfig = (key: string, value: unknown) => {
    if (!component.id) return;
    const config = {
      ...((component.config as Record<string, unknown>) || {}),
      [key]: value === '' ? undefined : value,
    };
    onChange(floorId, component.id, { config } as Partial<DslComponent>);
  };

  const inputClass = `w-full px-3 py-1.5 text-sm border border-gray-200 rounded-md
    focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
    disabled:bg-gray-50 disabled:text-gray-400`;

  const showTitle = true; // all component types can have a title
  const showSpan = true;
  const showVisible = true;
  const showPageSize = component.type === 'sub-table' || component.type === 'recent-list';
  const showModelCode = component.type === 'detail-section' || component.type === 'sub-table';

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-gray-200 px-4 py-3">
        <h3 className="text-sm font-medium text-gray-900">Component Properties</h3>
        <p className="mt-0.5 text-xs text-gray-500">
          {component.type} - {(component.id || '').slice(0, 12)}
        </p>
      </div>

      <div className="flex-1 space-y-4 overflow-auto p-4">
        {/* Type (read-only) */}
        <PropertyField label="Type">
          <div className="rounded-md border border-gray-100 bg-gray-50 px-3 py-1.5 text-sm text-gray-600">
            {component.type}
          </div>
        </PropertyField>

        {/* Title */}
        {showTitle && (
          <PropertyField label="Title">
            <input
              type="text"
              value={(getConfig(component, 'title') as string) || ''}
              onChange={(e) => updateConfig('title', e.target.value)}
              placeholder="Component title"
              disabled={readOnly}
              className={inputClass}
            />
          </PropertyField>
        )}

        {/* Span (1-12) */}
        {showSpan && (
          <PropertyField label="Span (1-12)">
            <input
              type="number"
              min={1}
              max={12}
              value={(getConfig(component, 'span') as number) || ''}
              onChange={(e) =>
                updateConfig('span', e.target.value ? Number(e.target.value) : undefined)
              }
              placeholder="12"
              disabled={readOnly}
              className={inputClass}
            />
          </PropertyField>
        )}

        {/* Visible (SpEL) */}
        {showVisible && (
          <PropertyField label="Visible">
            <input
              type="text"
              value={(getConfig(component, 'visible') as string) || ''}
              onChange={(e) => updateConfig('visible', e.target.value)}
              placeholder="SpEL expression (empty = always)"
              disabled={readOnly}
              className={`${inputClass} font-mono text-xs`}
            />
          </PropertyField>
        )}

        {/* Model Code (sub-table, detail-section) */}
        {showModelCode && (
          <PropertyField label="Model Code">
            <input
              type="text"
              value={(getConfig(component, 'modelCode') as string) || ''}
              onChange={(e) => updateConfig('modelCode', e.target.value)}
              placeholder="e.g. order_line"
              disabled={readOnly}
              className={inputClass}
            />
          </PropertyField>
        )}

        {/* Page Size (sub-table, recent-list) */}
        {showPageSize && (
          <PropertyField label="Page Size">
            <input
              type="number"
              min={1}
              max={100}
              value={(getConfig(component, 'pageSize') as number) || ''}
              onChange={(e) =>
                updateConfig('pageSize', e.target.value ? Number(e.target.value) : undefined)
              }
              placeholder="10"
              disabled={readOnly}
              className={inputClass}
            />
          </PropertyField>
        )}

        {/* Data source */}
        <PropertyField label="Data Source">
          <input
            type="text"
            value={(component.dataSource as string) || ''}
            onChange={(e) => {
              if (component.id) {
                onChange(floorId, component.id, { dataSource: e.target.value || undefined });
              }
            }}
            placeholder="Data source binding"
            disabled={readOnly}
            className={inputClass}
          />
        </PropertyField>

        {/* Grid position */}
        {component.grid && (
          <>
            <PropertyField label="Column">
              <input
                type="text"
                value={component.grid.column || ''}
                onChange={(e) => {
                  if (component.id) {
                    onChange(floorId, component.id, {
                      grid: { ...component.grid!, column: e.target.value },
                    });
                  }
                }}
                placeholder="e.g. 1 / span 2"
                disabled={readOnly}
                className={`${inputClass} font-mono text-xs`}
              />
            </PropertyField>
            <PropertyField label="Row">
              <input
                type="text"
                value={String(component.grid.row || '')}
                onChange={(e) => {
                  if (component.id) {
                    onChange(floorId, component.id, {
                      grid: { ...component.grid!, row: e.target.value },
                    });
                  }
                }}
                placeholder="e.g. 1"
                disabled={readOnly}
                className={`${inputClass} font-mono text-xs`}
              />
            </PropertyField>
          </>
        )}

        {/* ID (read-only) */}
        <PropertyField label="ID">
          <div className="rounded-md border border-gray-100 bg-gray-50 px-3 py-1.5 font-mono text-xs text-gray-400">
            {component.id || '-'}
          </div>
        </PropertyField>
      </div>
    </div>
  );
};

/**
 * Reusable property field wrapper
 */
interface PropertyFieldProps {
  label: string;
  children: React.ReactNode;
}

const PropertyField: React.FC<PropertyFieldProps> = ({ label, children }) => {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-medium text-gray-500">{label}</label>
      {children}
    </div>
  );
};

export default FloorPropertyPanel;
