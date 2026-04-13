/**
 * WidgetPalette — Left panel tab showing draggable UI widget types
 *
 * Fixed list of 11 widget types grouped by category (Input / Display).
 * Each item is draggable (useDraggable from @dnd-kit/core) with id `widget:{component}`.
 * Clicking also calls onAddWidget as fallback.
 *
 * @since 4.0.0
 */

import React, { useState } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { WidgetRegistry } from '~/plugins/core-designer/components/studio/registry';
import type { WidgetDefinition } from '~/plugins/core-designer/components/studio/registry';

export interface WidgetPaletteProps {
  onAddWidget: (component: string) => void;
  readonly?: boolean;
}

// ─── Category display config ─────────────────────────────────────────────────

const CATEGORY_CONFIG: Record<string, { label: string; color: string; badgeBg: string; badgeText: string }> = {
  input: {
    label: 'Input',
    color: 'text-indigo-600',
    badgeBg: 'bg-indigo-100',
    badgeText: 'text-indigo-700',
  },
  selection: {
    label: 'Selection',
    color: 'text-violet-600',
    badgeBg: 'bg-violet-100',
    badgeText: 'text-violet-700',
  },
  advanced: {
    label: 'Advanced',
    color: 'text-rose-600',
    badgeBg: 'bg-rose-100',
    badgeText: 'text-rose-700',
  },
};

const DEFAULT_CATEGORY_CONFIG = {
  label: 'Other',
  color: 'text-gray-600',
  badgeBg: 'bg-gray-100',
  badgeText: 'text-gray-700',
};

// ─── DraggableWidgetItem ─────────────────────────────────────────────────────

interface DraggableWidgetItemProps {
  widget: WidgetDefinition;
  onAddWidget: (component: string) => void;
  disabled?: boolean;
}

const DraggableWidgetItem: React.FC<DraggableWidgetItemProps> = ({ widget, onAddWidget, disabled }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `widget:${widget.component}`,
    disabled,
    data: { component: widget.component },
  });

  const catConfig = CATEGORY_CONFIG[widget.category] ?? DEFAULT_CATEGORY_CONFIG;

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      onClick={() => !disabled && onAddWidget(widget.component)}
      title={`Click or drag to add ${widget.name}`}
      className={`flex cursor-pointer items-start gap-2 rounded-md border px-2 py-1.5 transition-all select-none ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-blue-50/30 hover:shadow-sm active:scale-[0.98]'
      }`}
      data-testid={`widget-palette-item-${widget.component}`}
    >
      {/* Icon badge */}
      <span
        className={`mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded text-xs font-bold ${catConfig.badgeBg} ${catConfig.badgeText}`}
      >
        {widget.icon}
      </span>

      {/* Name + description */}
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium text-gray-800">{widget.name}</div>
        <div className="truncate text-[10px] leading-tight text-gray-400">{widget.description}</div>
      </div>
    </div>
  );
};

// ─── WidgetPalette ───────────────────────────────────────────────────────────

export const WidgetPalette: React.FC<WidgetPaletteProps> = ({ onAddWidget, readonly }) => {
  const [search, setSearch] = useState('');

  // Pull all registered widgets from the singleton registry
  const allWidgets = WidgetRegistry.getAll();

  const filtered = allWidgets.filter(
    (w) =>
      !search.trim() ||
      w.name.toLowerCase().includes(search.toLowerCase()) ||
      w.component.toLowerCase().includes(search.toLowerCase()),
  );

  // Derive ordered category list from the filtered widgets (preserves insertion order)
  const categoryOrder = Array.from(new Set(filtered.map((w) => w.category)));

  const filteredByCategory = categoryOrder.reduce<Record<string, WidgetDefinition[]>>(
    (acc, cat) => {
      acc[cat] = filtered.filter((w) => w.category === cat);
      return acc;
    },
    {},
  );

  const hasResults = filtered.length > 0;

  return (
    <div className="flex h-full flex-col" data-testid="widget-palette">
      {/* Search */}
      <div className="border-b border-gray-100 px-3 py-2">
        <div className="relative">
          <svg
            className="absolute top-1/2 left-2 h-3 w-3 -translate-y-1/2 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search widgets..."
            className="w-full rounded border border-gray-200 bg-gray-50 py-1 pr-2 pl-6 text-xs placeholder-gray-400 focus:border-blue-400 focus:bg-white focus:outline-none"
            data-testid="widget-palette-search"
          />
        </div>
      </div>

      {/* Widget list grouped by category */}
      <div className="flex-1 overflow-y-auto px-3 py-2">
        {!hasResults ? (
          <div className="py-6 text-center text-xs text-gray-400">No widgets found</div>
        ) : (
          <div className="space-y-3">
            {categoryOrder.map((cat) => {
              const items = filteredByCategory[cat];
              if (!items || items.length === 0) return null;
              const catConfig = CATEGORY_CONFIG[cat] ?? DEFAULT_CATEGORY_CONFIG;
              return (
                <div key={cat}>
                  <div
                    className={`mb-1 text-[10px] font-semibold uppercase tracking-wider ${catConfig.color}`}
                  >
                    {catConfig.label}
                  </div>
                  <div className="space-y-1">
                    {items.map((widget) => (
                      <DraggableWidgetItem
                        key={widget.component}
                        widget={widget}
                        onAddWidget={onAddWidget}
                        disabled={readonly}
                      />
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="border-t border-gray-100 px-3 py-1.5 text-[10px] text-gray-400">
        {allWidgets.length} widget types — click or drag to add
      </div>
    </div>
  );
};

export default WidgetPalette;
