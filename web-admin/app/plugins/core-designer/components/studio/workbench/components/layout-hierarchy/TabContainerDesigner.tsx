import React, { useState } from 'react';
import type { TabContainerConfig, FieldCellConfig } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import type { HierarchySelection } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import { FloorSectionDesigner } from './FloorSectionDesigner';

interface TabContainerDesignerProps {
  hierarchy: TabContainerConfig;
  selection: HierarchySelection;
  onSelectTab: (tabId: string) => void;
  onSelectFloor: (tabId: string, floorId: string) => void;
  onSelectBlock: (tabId: string, floorId: string, blockId: string) => void;
  onSelectField: (tabId: string, floorId: string, blockId: string, fieldId: string) => void;
  onAddTab: (label: string) => void;
  onRemoveTab: (tabId: string) => void;
  onAddFloor: (tabId: string, title: string) => void;
  onRemoveFloor: (tabId: string, floorId: string) => void;
  onToggleFloorCollapse: (tabId: string, floorId: string) => void;
  onAddBlock: (tabId: string, floorId: string) => void;
  onRemoveBlock: (tabId: string, floorId: string, blockId: string) => void;
  onRemoveField: (tabId: string, floorId: string, blockId: string, fieldId: string) => void;
  onUpdateField: (
    tabId: string,
    floorId: string,
    blockId: string,
    fieldId: string,
    updates: Partial<FieldCellConfig>,
  ) => void;
}

/**
 * Tab Container Designer - top-level hierarchy component.
 * Renders tab navigation and the active tab's floor sections.
 */
export const TabContainerDesigner: React.FC<TabContainerDesignerProps> = ({
  hierarchy,
  selection,
  onSelectTab,
  onSelectFloor,
  onSelectBlock,
  onSelectField,
  onAddTab,
  onRemoveTab,
  onAddFloor,
  onRemoveFloor,
  onToggleFloorCollapse,
  onAddBlock,
  onRemoveBlock,
  onRemoveField,
  onUpdateField,
}) => {
  const [activeTabId, setActiveTabId] = useState(hierarchy.activeTab || hierarchy.tabs[0]?.id);
  const [showAddInput, setShowAddInput] = useState(false);
  const [newTabLabel, setNewTabLabel] = useState('');

  const activeTab = hierarchy.tabs.find((t) => t.id === activeTabId) || hierarchy.tabs[0];

  const handleAddTab = () => {
    if (newTabLabel.trim()) {
      onAddTab(newTabLabel.trim());
      setNewTabLabel('');
      setShowAddInput(false);
    }
  };

  const handleTabClick = (tabId: string) => {
    setActiveTabId(tabId);
    onSelectTab(tabId);
  };

  return (
    <div className="flex h-full flex-col">
      {/* Tab bar */}
      <div className="flex items-center border-b border-gray-200 bg-gray-50 px-2">
        <div className="scrollbar-thin flex items-center overflow-x-auto">
          {hierarchy.tabs.map((tab) => (
            <div key={tab.id} className="group/tab flex items-center">
              <button
                onClick={() => handleTabClick(tab.id)}
                className={`relative px-4 py-2.5 text-sm font-medium whitespace-nowrap transition-colors ${
                  activeTabId === tab.id
                    ? '-mb-px border-b-2 border-blue-600 text-blue-600'
                    : 'text-gray-500 hover:text-gray-700'
                } `}
              >
                {tab.icon && <span className="mr-1.5">{tab.icon}</span>}
                {tab.label}
              </button>
              {hierarchy.tabs.length > 1 && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onRemoveTab(tab.id);
                  }}
                  className="p-0.5 text-gray-300 opacity-0 transition-opacity group-hover/tab:opacity-100 hover:text-red-500"
                  title="删除标签"
                >
                  <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
          ))}
        </div>

        {/* Add tab button */}
        {showAddInput ? (
          <div className="ml-2 flex items-center gap-1">
            <input
              type="text"
              value={newTabLabel}
              onChange={(e) => setNewTabLabel(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAddTab();
                if (e.key === 'Escape') setShowAddInput(false);
              }}
              placeholder="标签名"
              className="w-24 rounded border border-gray-300 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-400 focus:outline-none"
              autoFocus
            />
            <button onClick={handleAddTab} className="p-1 text-blue-500 hover:text-blue-700">
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M5 13l4 4L19 7"
                />
              </svg>
            </button>
          </div>
        ) : (
          <button
            onClick={() => setShowAddInput(true)}
            className="ml-2 rounded p-1.5 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
            title="添加标签"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
          </button>
        )}
      </div>

      {/* Active tab content (floors) */}
      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        {activeTab &&
          activeTab.floors.map((floor) => (
            <FloorSectionDesigner
              key={floor.id}
              floor={floor}
              tabId={activeTab.id}
              selectedBlockId={
                selection.tabId === activeTab.id && selection.floorId === floor.id
                  ? selection.blockId
                  : undefined
              }
              selectedFieldId={
                selection.tabId === activeTab.id && selection.floorId === floor.id
                  ? selection.fieldId
                  : undefined
              }
              selected={
                selection.tabId === activeTab.id &&
                selection.floorId === floor.id &&
                !selection.blockId
              }
              onSelectFloor={() => onSelectFloor(activeTab.id, floor.id)}
              onSelectBlock={(blockId) => onSelectBlock(activeTab.id, floor.id, blockId)}
              onSelectField={(blockId, fieldId) =>
                onSelectField(activeTab.id, floor.id, blockId, fieldId)
              }
              onRemoveFloor={() => onRemoveFloor(activeTab.id, floor.id)}
              onAddBlock={() => onAddBlock(activeTab.id, floor.id)}
              onRemoveBlock={(blockId) => onRemoveBlock(activeTab.id, floor.id, blockId)}
              onRemoveField={(blockId, fieldId) =>
                onRemoveField(activeTab.id, floor.id, blockId, fieldId)
              }
              onUpdateField={(blockId, fieldId, updates) =>
                onUpdateField(activeTab.id, floor.id, blockId, fieldId, updates)
              }
              onToggleCollapse={() => onToggleFloorCollapse(activeTab.id, floor.id)}
              canRemove={activeTab.floors.length > 1}
            />
          ))}

        {/* Add floor button */}
        {activeTab && <AddFloorButton onAdd={(title) => onAddFloor(activeTab.id, title)} />}
      </div>
    </div>
  );
};

const AddFloorButton: React.FC<{ onAdd: (title: string) => void }> = ({ onAdd }) => {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState('');

  const handleSubmit = () => {
    if (title.trim()) {
      onAdd(title.trim());
      setTitle('');
      setEditing(false);
    }
  };

  if (editing) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-dashed border-gray-300 px-4 py-2">
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleSubmit();
            if (e.key === 'Escape') setEditing(false);
          }}
          placeholder="楼层标题"
          className="flex-1 rounded border border-gray-300 px-2 py-1 text-sm focus:ring-1 focus:ring-purple-400 focus:outline-none"
          autoFocus
        />
        <button
          onClick={handleSubmit}
          className="rounded bg-purple-500 px-2 py-1 text-xs text-white hover:bg-purple-600"
        >
          确定
        </button>
        <button
          onClick={() => setEditing(false)}
          className="px-2 py-1 text-xs text-gray-500 hover:text-gray-700"
        >
          取消
        </button>
      </div>
    );
  }

  return (
    <button
      onClick={() => setEditing(true)}
      className="w-full rounded-lg border-2 border-dashed border-gray-200 py-3 text-sm text-gray-400 transition-colors hover:border-purple-300 hover:text-purple-500"
    >
      + 添加楼层
    </button>
  );
};
