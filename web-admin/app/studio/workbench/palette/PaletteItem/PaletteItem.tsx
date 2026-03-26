import React from 'react';
import { usePaletteItem } from '~/studio/workbench/palette/PaletteItem/usePaletteItem';
import type { PaletteItemProps } from '~/studio/workbench/palette/PaletteItem/types';

/**
 * 调色板组件项
 * 可拖拽的组件项，用于从调色板拖拽到画布
 */
export const PaletteItem: React.FC<PaletteItemProps> = ({ type, name, icon }) => {
  const { attributes, listeners, setNodeRef, style, isDragging, isClient } = usePaletteItem({
    type,
    name,
    icon,
  });

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      className={`flex cursor-grab items-center space-x-3 rounded-lg border border-gray-200 bg-white p-3 transition-all duration-200 hover:bg-blue-50 active:cursor-grabbing ${
        isDragging
          ? 'scale-105 border-blue-400 bg-blue-50 opacity-50 shadow-lg'
          : 'hover:border-blue-300 hover:shadow-md'
      }`}
      suppressHydrationWarning={true}
    >
      <span className="text-lg select-none">{icon}</span>
      <span className="text-sm font-medium text-gray-700 select-none">{name}</span>
    </div>
  );
};
