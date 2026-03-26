/**
 * Floor Outline
 *
 * Tree outline view for the FloorsDesigner.
 * Shows floor hierarchy with components and tabs.
 */

import React, { useState } from 'react';
import type { DslV4Schema, DslFloor, DslComponent } from '~/studio/domain/dsl/types';

export interface FloorOutlineProps {
  dsl: DslV4Schema;
  selectedFloorId: string | null;
  selectedComponentId: string | null;
  onFloorClick: (floorId: string) => void;
  onComponentClick: (floorId: string, componentId: string) => void;
}

/**
 * Component type icons
 */
const COMPONENT_ICONS: Record<string, string> = {
  'detail-section': '📄',
  'sub-table': '📊',
  'stat-card': '📈',
  timeline: '🕐',
  'action-buttons': '🔘',
  'welcome-banner': '🏠',
  'quick-links': '🔗',
  'stat-cards': '📊',
  'recent-list': '📋',
  'chart-card': '📉',
  container: '📦',
};

function getComponentIcon(type: string): string {
  return COMPONENT_ICONS[type] || '📦';
}

export const FloorOutline: React.FC<FloorOutlineProps> = ({
  dsl,
  selectedFloorId,
  selectedComponentId,
  onFloorClick,
  onComponentClick,
}) => {
  const floors = dsl.floors || [];

  return (
    <div className="h-full overflow-auto p-3">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-xs font-semibold tracking-wider text-gray-500 uppercase">
          Page Structure
        </h3>
        <span className="text-[10px] text-gray-400">{dsl.kind}</span>
      </div>

      {/* Page info */}
      <div className="mb-3 rounded bg-blue-50 px-2 py-1.5 text-xs">
        <div className="truncate font-medium text-blue-800">{dsl.id}</div>
        {dsl.modelCode && (
          <div className="truncate text-[10px] text-blue-600">Model: {dsl.modelCode}</div>
        )}
      </div>

      {/* Floors tree */}
      <div className="space-y-1">
        {floors.length === 0 ? (
          <div className="px-2 text-[10px] text-gray-400 italic">No floors</div>
        ) : (
          floors.map((floor, index) => (
            <OutlineFloorItem
              key={floor.id}
              floor={floor}
              index={index}
              isSelected={selectedFloorId === floor.id}
              selectedComponentId={selectedComponentId}
              onFloorClick={() => onFloorClick(floor.id)}
              onComponentClick={(componentId) => onComponentClick(floor.id, componentId)}
            />
          ))
        )}
      </div>
    </div>
  );
};

/**
 * Single floor item in the outline
 */
interface OutlineFloorItemProps {
  floor: DslFloor;
  index: number;
  isSelected: boolean;
  selectedComponentId: string | null;
  onFloorClick: () => void;
  onComponentClick: (componentId: string) => void;
}

const OutlineFloorItem: React.FC<OutlineFloorItemProps> = ({
  floor,
  index,
  isSelected,
  selectedComponentId,
  onFloorClick,
  onComponentClick,
}) => {
  const [isExpanded, setIsExpanded] = useState(true);
  const isTabsFloor = floor.type === 'TabsFloor';
  const components = floor.components || [];
  const tabs = floor.tabs || [];
  const hasChildren = isTabsFloor ? tabs.length > 0 : components.length > 0;

  return (
    <div className="mb-1">
      {/* Floor header */}
      <div
        onClick={(e) => {
          e.stopPropagation();
          onFloorClick();
        }}
        className={`flex cursor-pointer items-center gap-1.5 rounded px-2 py-1.5 transition-colors ${
          isSelected ? 'bg-blue-100 text-blue-800' : 'text-gray-700 hover:bg-gray-100'
        }`}
      >
        {/* Expand/collapse toggle */}
        {hasChildren ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded(!isExpanded);
            }}
            className="flex h-4 w-4 items-center justify-center text-gray-400 hover:text-gray-600"
          >
            <svg
              className={`h-3 w-3 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        ) : (
          <span className="w-4" />
        )}

        <span className="text-sm">{isTabsFloor ? '📑' : '🏗️'}</span>
        <span className="flex-1 truncate text-xs font-medium">
          {floor.title || `Floor ${index + 1}`}
        </span>
        {isTabsFloor && (
          <span className="rounded bg-purple-50 px-1 text-[9px] text-purple-600">Tabs</span>
        )}
        <span className="text-[10px] text-gray-400">{floor.id.slice(0, 8)}</span>
      </div>

      {/* Children */}
      {hasChildren && isExpanded && (
        <div className="mt-0.5 ml-4 space-y-0.5 border-l border-gray-200 pl-2">
          {isTabsFloor
            ? // Tab items
              tabs.map((tab) => (
                <div
                  key={tab.key}
                  className="flex items-center gap-1.5 px-2 py-0.5 text-xs text-gray-500"
                >
                  <span className="flex h-3 w-3 items-center justify-center text-[10px]">📑</span>
                  <span className="truncate">{tab.label}</span>
                  <span className="text-[9px] text-gray-400">({tab.key})</span>
                </div>
              ))
            : // Component items
              components.map((comp) => (
                <OutlineComponentItem
                  key={comp.id || comp.type}
                  component={comp}
                  floorId={floor.id}
                  isSelected={selectedComponentId === comp.id}
                  onClick={() => comp.id && onComponentClick(comp.id)}
                />
              ))}
        </div>
      )}
    </div>
  );
};

/**
 * Single component item in the outline
 */
interface OutlineComponentItemProps {
  component: DslComponent;
  floorId: string;
  isSelected: boolean;
  onClick: () => void;
}

const OutlineComponentItem: React.FC<OutlineComponentItemProps> = ({
  component,
  isSelected,
  onClick,
}) => {
  const icon = getComponentIcon(component.type);

  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className={`flex cursor-pointer items-center gap-1.5 rounded px-2 py-0.5 transition-colors ${
        isSelected ? 'bg-blue-50 text-blue-700' : 'text-gray-500 hover:bg-gray-50'
      }`}
    >
      <span className="flex h-3 w-3 items-center justify-center text-[10px]">{icon}</span>
      <span className="flex-1 truncate text-xs">{component.type}</span>
      <span className="text-[9px] text-gray-400">{(component.id || '').slice(0, 6)}</span>
    </div>
  );
};

export default FloorOutline;
