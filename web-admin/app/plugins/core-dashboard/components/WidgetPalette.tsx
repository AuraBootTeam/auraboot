/**
 * Widget Palette
 * Component library panel for dragging widgets onto the canvas
 */

import React, { useCallback, useRef } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import { widgetRegistry } from '../widgets/widgetRegistry';
import type { WidgetDefinition, WidgetType } from '../types';

interface WidgetPaletteProps {
  onDragStart?: (event: React.DragEvent, widgetType: WidgetType) => void;
  onWidgetClick?: (widgetType: WidgetType) => void;
}

/**
 * Widget icon component
 */
const WidgetIcon: React.FC<{ icon: string; className?: string }> = ({ icon, className = '' }) => {
  // Detect emoji icons (non-ASCII first char) — render as text span
  if (icon && !/^[a-zA-Z]/.test(icon)) {
    return <span className="text-xl leading-none">{icon}</span>;
  }

  const iconMap: Record<string, React.ReactNode> = {
    NumberOutlined: (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M7 20l4-16m2 16l4-16M6 9h14M4 15h14"
        />
      </svg>
    ),
    BarChartOutlined: (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
        />
      </svg>
    ),
    LineChartOutlined: (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4v16"
        />
      </svg>
    ),
    PieChartOutlined: (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M11 3.055A9.001 9.001 0 1020.945 13H11V3.055z"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M20.488 9H15V3.512A9.025 9.025 0 0120.488 9z"
        />
      </svg>
    ),
    AreaChartOutlined: (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M3 17l4-4 4 4 4-8 4 4"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M3 17l4-4 4 4 4-8 4 4V21H3z"
          opacity={0.3}
          fill="currentColor"
        />
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 21h18" />
      </svg>
    ),
  };

  return (
    iconMap[icon] || (
      <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z"
        />
      </svg>
    )
  );
};

/**
 * Widget item component
 */
const WidgetItem: React.FC<{
  definition: WidgetDefinition;
  onDragStart?: (event: React.DragEvent) => void;
  onClick?: () => void;
}> = ({ definition, onDragStart, onClick }) => {
  const dragPreviewRef = useRef<HTMLDivElement>(null);

  const handleDragStart = useCallback(
    (e: React.DragEvent) => {
      if (dragPreviewRef.current) {
        e.dataTransfer.setDragImage(dragPreviewRef.current, 50, 20);
      }
      onDragStart?.(e);
    },
    [onDragStart],
  );

  return (
    <div
      draggable
      onDragStart={handleDragStart}
      onClick={onClick}
      className="group flex cursor-grab flex-col items-center rounded-lg border border-gray-200 bg-white p-3 transition-all hover:border-blue-400 hover:shadow-sm active:cursor-grabbing"
    >
      <div className="flex h-10 w-10 items-center justify-center text-gray-500 group-hover:text-blue-600">
        <WidgetIcon icon={definition.icon} className="h-6 w-6" />
      </div>
      <span className="mt-2 text-center text-xs font-medium text-gray-700">{definition.label}</span>
      {/* Pre-rendered drag preview — hidden offscreen to avoid Safari race condition */}
      <div
        ref={dragPreviewRef}
        className="pointer-events-none fixed -top-[1000px] -left-[1000px] rounded-lg bg-blue-500 px-3 py-2 text-sm font-medium text-white shadow-lg"
        aria-hidden="true"
      >
        {definition.label}
      </div>
    </div>
  );
};

export const WidgetPalette: React.FC<WidgetPaletteProps> = ({ onDragStart, onWidgetClick }) => {
  const { locale } = useI18n();
  const widgetsByCategory = widgetRegistry.getByCategory();

  const handleDragStart = useCallback(
    (event: React.DragEvent, widgetType: WidgetType) => {
      event.dataTransfer.setData('application/widget-type', widgetType);
      event.dataTransfer.effectAllowed = 'copy';
      onDragStart?.(event, widgetType);
    },
    [onDragStart],
  );

  return (
    <div
      data-testid="widget-palette"
      className="flex w-56 flex-col overflow-hidden border-r border-gray-200 bg-gray-50"
    >
      <div className="border-b border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-700">
          {resolveDesignerText(DESIGNER_I18N.palette.title, locale)}
        </h2>
        <p className="mt-1 text-xs text-gray-500">
          {resolveDesignerText(DESIGNER_I18N.palette.dragHint, locale)}
        </p>
      </div>

      <div className="flex-1 space-y-6 overflow-y-auto p-4">
        {Object.entries(widgetsByCategory).map(([category, widgets]) => (
          <div key={category}>
            <h3 className="mb-3 text-xs font-medium tracking-wider text-gray-500 uppercase">
              {category}
            </h3>
            <div className="grid grid-cols-2 gap-2">
              {widgets.map((widget) => (
                <WidgetItem
                  key={widget.type}
                  definition={widget}
                  onDragStart={(e) => handleDragStart(e, widget.type)}
                  onClick={() => onWidgetClick?.(widget.type)}
                />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default WidgetPalette;
