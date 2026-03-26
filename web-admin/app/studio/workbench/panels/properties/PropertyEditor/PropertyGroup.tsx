/**
 * 属性分组组件
 * 用于组织和展示属性编辑器中的属性分组
 */

import React from 'react';

export interface PropertyGroupProps {
  id: string;
  title: string;
  icon?: string;
  expanded: boolean;
  onToggle: () => void;
  children: React.ReactNode;
  description?: string;
}

export const PropertyGroup: React.FC<PropertyGroupProps> = ({
  id,
  title,
  icon,
  expanded,
  onToggle,
  children,
  description,
}) => {
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      {/* 分组标题 */}
      <button
        onClick={onToggle}
        className="flex w-full items-center justify-between bg-gray-50 p-3 transition-colors hover:bg-gray-100"
      >
        <div className="flex items-center space-x-2">
          {icon && <span className="text-sm">{icon}</span>}
          <span className="text-sm font-medium text-gray-900">{title}</span>
        </div>
        <div className="flex items-center space-x-2">
          {description && (
            <span className="hidden text-xs text-gray-500 sm:inline">{description}</span>
          )}
          <span className="text-gray-500">{expanded ? '▼' : '▶'}</span>
        </div>
      </button>

      {/* 分组内容 */}
      {expanded && <div className="border-t border-gray-200 bg-white p-3">{children}</div>}
    </div>
  );
};
