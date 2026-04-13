import React, { useState } from 'react';
import type { TabContainerConfig } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import { FloorSectionRuntime } from './FloorSectionRuntime';

interface TabContainerRuntimeProps {
  hierarchy: TabContainerConfig;
  data?: Record<string, any>;
}

/**
 * Tab Container Runtime - renders the full hierarchy in runtime/preview mode.
 */
export const TabContainerRuntime: React.FC<TabContainerRuntimeProps> = ({ hierarchy, data }) => {
  const [activeTabId, setActiveTabId] = useState(hierarchy.activeTab || hierarchy.tabs[0]?.id);
  const activeTab = hierarchy.tabs.find((t) => t.id === activeTabId) || hierarchy.tabs[0];

  if (hierarchy.tabs.length === 0) return null;

  return (
    <div className="flex flex-col overflow-hidden rounded-lg border border-gray-200">
      {/* Tab navigation */}
      {hierarchy.tabs.length > 1 && (
        <div className="flex border-b border-gray-200 bg-gray-50">
          {hierarchy.tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTabId(tab.id)}
              className={`px-4 py-2.5 text-sm font-medium transition-colors ${
                activeTabId === tab.id
                  ? '-mb-px border-b-2 border-blue-600 bg-white text-blue-600'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              {tab.icon && <span className="mr-1.5">{tab.icon}</span>}
              {tab.label}
            </button>
          ))}
        </div>
      )}

      {/* Active tab content */}
      {activeTab && (
        <div className="space-y-4 p-4">
          {activeTab.floors.map((floor) => (
            <FloorSectionRuntime key={floor.id} floor={floor} data={data} />
          ))}
        </div>
      )}
    </div>
  );
};
