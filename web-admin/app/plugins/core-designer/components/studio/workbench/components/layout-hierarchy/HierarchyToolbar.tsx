import React from 'react';

interface HierarchyToolbarProps {
  isHierarchyMode: boolean;
  onEnableHierarchy: () => void;
  onDisableHierarchy: () => void;
}

/**
 * Hierarchy Toolbar - toggle between grid mode and hierarchy mode.
 * Shown in the canvas toolbar area.
 */
export const HierarchyToolbar: React.FC<HierarchyToolbarProps> = ({
  isHierarchyMode,
  onEnableHierarchy,
  onDisableHierarchy,
}) => {
  return (
    <div className="flex items-center gap-2">
      <div className="flex items-center rounded-md bg-gray-100 p-0.5">
        <button
          onClick={onDisableHierarchy}
          className={`rounded px-2.5 py-1 text-xs font-medium transition-colors ${
            !isHierarchyMode
              ? 'bg-white text-gray-700 shadow-sm'
              : 'text-gray-500 hover:text-gray-700'
          }`}
          title="网格布局"
        >
          <svg
            className="mr-1 inline-block h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"
            />
          </svg>
          网格
        </button>
        <button
          onClick={onEnableHierarchy}
          className={`rounded px-2.5 py-1 text-xs font-medium transition-colors ${
            isHierarchyMode
              ? 'bg-white text-gray-700 shadow-sm'
              : 'text-gray-500 hover:text-gray-700'
          }`}
          title="层级布局"
        >
          <svg
            className="mr-1 inline-block h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 5h16M4 10h16M4 15h10M4 20h6"
            />
          </svg>
          层级
        </button>
      </div>
    </div>
  );
};
