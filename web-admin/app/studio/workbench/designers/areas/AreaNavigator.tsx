/**
 * Area Navigator
 *
 * Left panel component for navigating between areas (filters/toolbar/main).
 */

import React from 'react';
import type { AreaName, DslArea } from '~/studio/domain/dsl/types';

export interface AreaNavigatorProps {
  areas: AreaName[];
  areaConfig: Record<AreaName, { title: string; description: string }>;
  selectedArea: AreaName;
  onSelect: (areaName: AreaName) => void;
  dslAreas: Record<string, DslArea>;
}

/**
 * Area icons by name
 */
const AREA_ICONS: Record<AreaName, string> = {
  filters: '🔍',
  toolbar: '🔧',
  main: '📋',
};

export const AreaNavigator: React.FC<AreaNavigatorProps> = ({
  areas,
  areaConfig,
  selectedArea,
  onSelect,
  dslAreas,
}) => {
  return (
    <div className="p-3">
      <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase">区域</h3>
      <div className="space-y-1">
        {areas.map((areaName) => {
          const config = areaConfig[areaName];
          const blocks = dslAreas[areaName]?.blocks || [];
          const isSelected = selectedArea === areaName;

          return (
            <button
              key={areaName}
              onClick={() => onSelect(areaName)}
              className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors ${
                isSelected
                  ? 'bg-blue-50 text-blue-700 ring-1 ring-blue-200'
                  : 'text-gray-700 hover:bg-gray-50'
              }`}
            >
              <span className="text-lg">{AREA_ICONS[areaName]}</span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{config.title}</span>
                  {blocks.length > 0 && (
                    <span
                      className={`rounded-full px-1.5 py-0.5 text-xs ${
                        isSelected ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-500'
                      }`}
                    >
                      {blocks.length}
                    </span>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default AreaNavigator;
