import React, { useState } from 'react';
import type { FloorConfig } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import { BlockRegionRuntime } from './BlockRegionRuntime';

interface FloorSectionRuntimeProps {
  floor: FloorConfig;
  data?: Record<string, any>;
}

/**
 * Floor Section Runtime - renders a collapsible floor section in runtime mode.
 */
export const FloorSectionRuntime: React.FC<FloorSectionRuntimeProps> = ({ floor, data }) => {
  const [collapsed, setCollapsed] = useState(floor.collapsed ?? false);

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      {/* Floor header */}
      {(floor.title || floor.collapsible) && (
        <div
          className={`flex items-center justify-between bg-gray-50 px-4 py-2.5 ${
            floor.collapsible ? 'cursor-pointer hover:bg-gray-100' : ''
          }`}
          onClick={() => {
            if (floor.collapsible) setCollapsed(!collapsed);
          }}
        >
          <div className="flex items-center gap-2">
            {floor.collapsible && (
              <svg
                className={`h-4 w-4 text-gray-400 transition-transform ${collapsed ? '' : 'rotate-90'}`}
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
            )}
            <h4 className="text-sm font-medium text-gray-700">{floor.title}</h4>
            {floor.description && (
              <span className="text-xs text-gray-400">{floor.description}</span>
            )}
          </div>
        </div>
      )}

      {/* Floor content */}
      {!collapsed && (
        <div className="space-y-4 p-4">
          {floor.blocks.map((block) => (
            <BlockRegionRuntime key={block.id} block={block} data={data} />
          ))}
        </div>
      )}
    </div>
  );
};
