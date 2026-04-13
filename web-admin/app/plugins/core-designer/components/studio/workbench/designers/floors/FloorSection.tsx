/**
 * Floor Section
 *
 * Individual floor card rendering within the FloorCanvas.
 * Header: title input + collapse toggle + type badge (Normal/Tabs) + delete
 * Body: sortable grid of components (or tabs if TabsFloor)
 * Supports droppable zone for library component drops.
 */

import React, { useState, useCallback } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { SortableContext, rectSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { DslFloor, DslComponent } from '~/plugins/core-designer/components/studio/domain/dsl/types';

export interface FloorSectionProps {
  floor: DslFloor;
  index: number;
  isSelected: boolean;
  selectedComponentId: string | null;
  onSelect: () => void;
  onSelectComponent: (floorId: string, componentId: string) => void;
  onUpdateFloor: (id: string, updates: Partial<DslFloor>) => void;
  onRemoveFloor: (id: string) => void;
  onRemoveComponent: (floorId: string, componentId: string) => void;
  readOnly?: boolean;
}

/**
 * Component type display info
 */
const COMPONENT_TYPE_ICONS: Record<string, { icon: string; label: string }> = {
  'detail-section': { icon: '📄', label: 'Detail Section' },
  'sub-table': { icon: '📊', label: 'Sub Table' },
  'stat-card': { icon: '📈', label: 'Stat Card' },
  timeline: { icon: '🕐', label: 'Timeline' },
  'action-buttons': { icon: '🔘', label: 'Action Buttons' },
  'welcome-banner': { icon: '🏠', label: 'Welcome Banner' },
  'quick-links': { icon: '🔗', label: 'Quick Links' },
  'stat-cards': { icon: '📊', label: 'Stat Cards' },
  'recent-list': { icon: '📋', label: 'Recent List' },
  'chart-card': { icon: '📉', label: 'Chart Card' },
  container: { icon: '📦', label: 'Container' },
};

function getComponentInfo(type: string) {
  return COMPONENT_TYPE_ICONS[type] || { icon: '📦', label: type };
}

export const FloorSection: React.FC<FloorSectionProps> = ({
  floor,
  index,
  isSelected,
  selectedComponentId,
  onSelect,
  onSelectComponent,
  onUpdateFloor,
  onRemoveFloor,
  onRemoveComponent,
  readOnly,
}) => {
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [isEditingTitle, setIsEditingTitle] = useState(false);
  const [editTitle, setEditTitle] = useState(floor.title || '');

  const isTabsFloor = floor.type === 'TabsFloor';
  const components = floor.components || [];
  const tabs = floor.tabs || [];

  // Droppable zone for library component drops
  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `floor-drop:${floor.id}`,
    data: { type: 'floor-drop', floorId: floor.id },
  });

  const handleTitleCommit = useCallback(() => {
    setIsEditingTitle(false);
    if (editTitle.trim() !== (floor.title || '')) {
      onUpdateFloor(floor.id, { title: editTitle.trim() || undefined });
    }
  }, [editTitle, floor.id, floor.title, onUpdateFloor]);

  const handleTitleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter') {
        handleTitleCommit();
      } else if (e.key === 'Escape') {
        setEditTitle(floor.title || '');
        setIsEditingTitle(false);
      }
    },
    [handleTitleCommit, floor.title],
  );

  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onSelect();
      }}
      data-testid={`floor-section-${index}`}
      className={`rounded-lg border-2 bg-white transition-all ${
        isSelected ? 'border-blue-300 shadow-sm' : 'border-gray-200 hover:border-gray-300'
      }`}
    >
      {/* Header */}
      <div className="border-b border-gray-100 px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex min-w-0 flex-1 items-center gap-2">
            {/* Collapse toggle */}
            <button
              onClick={(e) => {
                e.stopPropagation();
                setIsCollapsed(!isCollapsed);
              }}
              className="flex h-5 w-5 items-center justify-center text-gray-400 transition-colors hover:text-gray-600"
            >
              <svg
                className={`h-3.5 w-3.5 transition-transform ${isCollapsed ? '' : 'rotate-90'}`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </button>

            {/* Floor index badge */}
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-400">
              F{index + 1}
            </span>

            {/* Title - editable on double click */}
            {isEditingTitle && !readOnly ? (
              <input
                type="text"
                value={editTitle}
                onChange={(e) => setEditTitle(e.target.value)}
                onBlur={handleTitleCommit}
                onKeyDown={handleTitleKeyDown}
                autoFocus
                className="flex-1 border-b border-blue-400 bg-transparent px-1 text-sm font-medium text-gray-900 outline-none"
              />
            ) : (
              <span
                onDoubleClick={() => {
                  if (!readOnly) {
                    setEditTitle(floor.title || '');
                    setIsEditingTitle(true);
                  }
                }}
                className="cursor-default truncate text-sm font-medium text-gray-900"
              >
                {floor.title || `Floor ${index + 1}`}
              </span>
            )}

            {/* Type badge */}
            {isTabsFloor && (
              <span className="rounded bg-purple-50 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">
                Tabs
              </span>
            )}
          </div>

          <div className="flex items-center gap-1">
            {/* Component count */}
            <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-400">
              {isTabsFloor ? `${tabs.length} tabs` : `${components.length} components`}
            </span>

            {/* Delete button */}
            {!readOnly && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onRemoveFloor(floor.id);
                }}
                className="flex h-6 w-6 items-center justify-center text-gray-400 transition-colors hover:text-red-500"
                title="Delete floor"
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
            )}
          </div>
        </div>
      </div>

      {/* Body - collapsible */}
      {!isCollapsed && (
        <div
          ref={setDropRef}
          className={`p-4 transition-colors ${isOver ? 'border-blue-200 bg-blue-50/50' : ''}`}
        >
          {isTabsFloor ? (
            // Tabs floor content
            <TabsFloorContent tabs={tabs} selectedComponentId={selectedComponentId} />
          ) : components.length === 0 ? (
            // Empty floor placeholder — also droppable
            <div
              className={`rounded-lg border-2 border-dashed p-8 text-center transition-colors ${
                isOver ? 'border-blue-400 bg-blue-50' : 'border-gray-200 hover:border-gray-300'
              }`}
            >
              <div className="text-gray-400">
                <svg
                  className="mx-auto mb-2 h-10 w-10"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={1.5}
                    d="M12 6v6m0 0v6m0-6h6m-6 0H6"
                  />
                </svg>
                <p className="text-sm">
                  {isOver ? 'Drop component here' : 'Drag component from library or click to add'}
                </p>
              </div>
            </div>
          ) : (
            // Component grid with sortable context
            <SortableContext
              items={components.map((c) => c.id!).filter(Boolean)}
              strategy={rectSortingStrategy}
            >
              <div className="grid grid-cols-2 gap-3">
                {components.map((comp) => (
                  <SortableComponentCard
                    key={comp.id}
                    component={comp}
                    floorId={floor.id}
                    isSelected={selectedComponentId === comp.id}
                    onSelect={() => {
                      if (comp.id) onSelectComponent(floor.id, comp.id);
                    }}
                    onRemove={() => {
                      if (comp.id) onRemoveComponent(floor.id, comp.id);
                    }}
                    readOnly={readOnly}
                  />
                ))}
              </div>
            </SortableContext>
          )}
        </div>
      )}
    </div>
  );
};

/**
 * Sortable component card within a floor
 */
interface SortableComponentCardProps {
  component: DslComponent;
  floorId: string;
  isSelected: boolean;
  onSelect: () => void;
  onRemove: () => void;
  readOnly?: boolean;
}

const SortableComponentCard: React.FC<SortableComponentCardProps> = ({
  component,
  floorId,
  isSelected,
  onSelect,
  onRemove,
  readOnly,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: component.id!,
    disabled: readOnly,
    data: { type: 'component', floorId, componentId: component.id },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: transition || 'transform 200ms ease',
  };

  const info = getComponentInfo(component.type);

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      onClick={(e) => {
        e.stopPropagation();
        if (!isDragging) onSelect();
      }}
      className={`flex cursor-pointer items-center gap-2 rounded-lg border p-3 transition-all ${
        isDragging
          ? 'z-50 scale-[1.02] opacity-60 shadow-lg ring-2 ring-blue-400'
          : isSelected
            ? 'border-blue-400 bg-blue-50 shadow-sm'
            : 'border-gray-200 bg-gray-50 hover:border-gray-300 hover:bg-white'
      }`}
    >
      <span className="text-lg">{info.icon}</span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-gray-900">{info.label}</div>
        <div className="truncate text-[10px] text-gray-400">{component.id || component.type}</div>
      </div>
      {!readOnly && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="flex h-5 w-5 items-center justify-center text-gray-300 transition-colors hover:text-red-400"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      )}
    </div>
  );
};

/**
 * Tabs floor content display
 */
interface TabsFloorContentProps {
  tabs: Array<{ key: string; label: string; icon?: string; content: DslComponent }>;
  selectedComponentId: string | null;
}

const TabsFloorContent: React.FC<TabsFloorContentProps> = ({ tabs }) => {
  const [activeTabKey, setActiveTabKey] = useState(tabs[0]?.key || '');

  if (tabs.length === 0) {
    return (
      <div className="rounded-lg border-2 border-dashed border-gray-200 p-6 text-center">
        <p className="text-sm text-gray-400">No tabs</p>
      </div>
    );
  }

  return (
    <div>
      {/* Tab bar */}
      <div className="mb-3 flex border-b border-gray-200">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={(e) => {
              e.stopPropagation();
              setActiveTabKey(tab.key);
            }}
            className={`px-3 py-2 text-xs font-medium transition-colors ${
              activeTabKey === tab.key
                ? 'border-b-2 border-blue-600 text-blue-600'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            {tab.icon && <span className="mr-1">{tab.icon}</span>}
            {tab.label}
          </button>
        ))}
      </div>

      {/* Active tab content */}
      <div className="min-h-[60px] rounded-lg bg-gray-50 p-4">
        {tabs
          .filter((t) => t.key === activeTabKey)
          .map((tab) => {
            const info = getComponentInfo(tab.content.type);
            return (
              <div key={tab.key} className="flex items-center gap-2 text-sm text-gray-500">
                <span>{info.icon}</span>
                <span>{info.label}</span>
                <span className="text-[10px] text-gray-400">({tab.content.type})</span>
              </div>
            );
          })}
      </div>
    </div>
  );
};

export default FloorSection;
