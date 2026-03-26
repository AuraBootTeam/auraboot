import React from 'react';
import type {
  TabContainerConfig,
  HierarchySelection,
} from '~/studio/domain/schema/layout-hierarchy';

interface HierarchyOutlineProps {
  hierarchy: TabContainerConfig;
  selection: HierarchySelection;
  onSelectTab: (tabId: string) => void;
  onSelectFloor: (tabId: string, floorId: string) => void;
  onSelectBlock: (tabId: string, floorId: string, blockId: string) => void;
  onSelectField: (tabId: string, floorId: string, blockId: string, fieldId: string) => void;
}

/**
 * Hierarchy Outline - tree view of the layout hierarchy.
 * Shown in the left panel's outline tab when hierarchy mode is active.
 */
export const HierarchyOutline: React.FC<HierarchyOutlineProps> = ({
  hierarchy,
  selection,
  onSelectTab,
  onSelectFloor,
  onSelectBlock,
  onSelectField,
}) => {
  return (
    <div className="p-3">
      <div className="mb-2 text-xs font-medium tracking-wide text-gray-500 uppercase">页面结构</div>
      <div className="space-y-0.5">
        {hierarchy.tabs.map((tab) => (
          <div key={tab.id}>
            {/* Tab node */}
            <button
              onClick={() => onSelectTab(tab.id)}
              className={`flex w-full items-center gap-1.5 rounded px-2 py-1 text-left text-sm ${
                selection.tabId === tab.id && !selection.floorId
                  ? 'bg-blue-50 text-blue-700'
                  : 'text-gray-700 hover:bg-gray-100'
              }`}
            >
              <svg
                className="h-3.5 w-3.5 text-gray-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"
                />
              </svg>
              {tab.label}
            </button>

            {/* Floors */}
            <div className="ml-4">
              {tab.floors.map((floor) => (
                <div key={floor.id}>
                  <button
                    onClick={() => onSelectFloor(tab.id, floor.id)}
                    className={`flex w-full items-center gap-1.5 rounded px-2 py-1 text-left text-xs ${
                      selection.floorId === floor.id && !selection.blockId
                        ? 'bg-purple-50 text-purple-700'
                        : 'text-gray-600 hover:bg-gray-100'
                    }`}
                  >
                    <svg
                      className="h-3 w-3 text-gray-400"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
                      />
                    </svg>
                    {floor.title || '未命名楼层'}
                  </button>

                  {/* Blocks */}
                  <div className="ml-4">
                    {floor.blocks.map((block) => (
                      <div key={block.id}>
                        <button
                          onClick={() => onSelectBlock(tab.id, floor.id, block.id)}
                          className={`flex w-full items-center gap-1.5 rounded px-2 py-0.5 text-left text-xs ${
                            selection.blockId === block.id && !selection.fieldId
                              ? 'bg-indigo-50 text-indigo-700'
                              : 'text-gray-500 hover:bg-gray-100'
                          }`}
                        >
                          <span className="h-2 w-2 rounded-sm border border-gray-300" />
                          {block.title || `区块 (${block.layout.columns || 2}列)`}
                        </button>

                        {/* Fields */}
                        <div className="ml-4">
                          {block.fields.map((field) => (
                            <button
                              key={field.id}
                              onClick={() => onSelectField(tab.id, floor.id, block.id, field.id)}
                              className={`flex w-full items-center gap-1 rounded px-2 py-0.5 text-left text-xs ${
                                selection.fieldId === field.id
                                  ? 'bg-blue-50 text-blue-600'
                                  : 'text-gray-400 hover:bg-gray-50 hover:text-gray-600'
                              }`}
                            >
                              <span className="h-1.5 w-1.5 rounded-full bg-gray-300" />
                              {field.label || field.fieldCode}
                            </button>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
