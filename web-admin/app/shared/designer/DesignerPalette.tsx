/**
 * Unified DesignerPalette — shared palette component for BPMN, Flow, and Report designers.
 *
 * Supports:
 * - Category grouping with collapsible sections
 * - Optional search filter
 * - Drag mode (BPMN, Flow) and click mode (Report)
 * - Custom MIME type for drag data
 */

import React, { useMemo, useState } from 'react';
import { Search, ChevronDown, ChevronRight } from 'lucide-react';
import { cn } from '~/utils/cn';

// ==================== Types ====================

export interface PaletteItem {
  type: string;
  label: string;
  icon?: React.ReactNode;
  description?: string;
  category?: string;
  /** Arbitrary extra data preserved during drag/click callbacks */
  data?: unknown;
  /** Whether the item is disabled (e.g. already added) */
  disabled?: boolean;
  /** Extra status text shown below description when disabled */
  disabledText?: string;
}

export interface DesignerPaletteProps {
  items: PaletteItem[];
  title?: string;
  subtitle?: string;
  searchable?: boolean;
  draggable?: boolean;
  dragMimeType?: string;
  onItemClick?: (item: PaletteItem) => void;
  onItemDragStart?: (e: React.DragEvent, item: PaletteItem) => void;
  /** Labels for category keys. Falls back to the raw category string. */
  categoryLabels?: Record<string, string>;
  /** Icons for category headers */
  categoryIcons?: Record<string, React.ReactNode>;
  /** Ordered list of category keys. Unmatched categories appended at end. */
  categoryOrder?: string[];
  /** Empty state message */
  emptyMessage?: string;
  className?: string;
  /** data-testid for the root element */
  testId?: string;
}

// ==================== Component ====================

export function DesignerPalette({
  items,
  title,
  subtitle,
  searchable = false,
  draggable = false,
  dragMimeType = 'application/designer-item',
  onItemClick,
  onItemDragStart,
  categoryLabels = {},
  categoryIcons = {},
  categoryOrder,
  emptyMessage = 'No components available',
  className,
  testId,
}: DesignerPaletteProps) {
  const [search, setSearch] = useState('');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  // Filter items by search query
  const filtered = useMemo(() => {
    if (!search.trim()) return items;
    const q = search.toLowerCase();
    return items.filter(
      (item) => item.label.toLowerCase().includes(q) || item.description?.toLowerCase().includes(q),
    );
  }, [items, search]);

  // Group by category
  const grouped = useMemo(() => {
    const groups: Record<string, PaletteItem[]> = {};
    for (const item of filtered) {
      const cat = item.category || '__uncategorized__';
      if (!groups[cat]) groups[cat] = [];
      groups[cat].push(item);
    }
    return groups;
  }, [filtered]);

  // Determine ordered categories
  const categories = useMemo(() => {
    const allCats = Object.keys(grouped);
    if (!categoryOrder) return allCats;
    const ordered = categoryOrder.filter((c) => grouped[c]);
    const remaining = allCats.filter((c) => !categoryOrder.includes(c));
    return [...ordered, ...remaining];
  }, [grouped, categoryOrder]);

  const hasCategories =
    categories.length > 0 && !(categories.length === 1 && categories[0] === '__uncategorized__');

  const toggleCategory = (cat: string) => {
    setCollapsed((prev) => ({ ...prev, [cat]: !prev[cat] }));
  };

  const handleDragStart = (e: React.DragEvent, item: PaletteItem) => {
    if (onItemDragStart) {
      onItemDragStart(e, item);
    } else {
      e.dataTransfer.setData(dragMimeType, item.type);
      e.dataTransfer.effectAllowed = 'move';
    }
  };

  const renderItem = (item: PaletteItem) => {
    const isDisabled = !!item.disabled;
    const isDraggable = draggable && !isDisabled;
    const isClickable = !!onItemClick && !isDisabled;

    const Element = isClickable ? 'button' : 'div';

    return (
      <Element
        key={item.type}
        draggable={isDraggable}
        onDragStart={isDraggable ? (e) => handleDragStart(e as React.DragEvent, item) : undefined}
        onClick={isClickable ? () => onItemClick!(item) : undefined}
        disabled={isDisabled && isClickable ? true : undefined}
        className={cn(
          'flex w-full items-center gap-3 rounded-lg border p-3 text-left transition-colors',
          isDisabled
            ? 'cursor-not-allowed border-gray-200 bg-gray-100 text-gray-400'
            : isDraggable
              ? 'cursor-grab border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50 active:cursor-grabbing'
              : isClickable
                ? 'cursor-pointer border-gray-200 bg-white hover:border-blue-400 hover:shadow-sm'
                : 'border-gray-200 bg-gray-50',
        )}
        title={item.description}
        data-testid={testId ? `${testId}-item-${item.type}` : undefined}
      >
        {item.icon && <span className="flex-shrink-0 text-lg">{item.icon}</span>}
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-gray-900">{item.label}</div>
          {item.description && (
            <div className="mt-0.5 truncate text-xs text-gray-500">{item.description}</div>
          )}
          {isDisabled && item.disabledText && (
            <div className="mt-0.5 text-xs text-green-600">{item.disabledText}</div>
          )}
        </div>
      </Element>
    );
  };

  return (
    <div
      data-testid={testId}
      className={cn('flex flex-col overflow-hidden border-r border-gray-200 bg-white', className)}
    >
      {/* Header */}
      {(title || subtitle || searchable) && (
        <div className="space-y-3 border-b border-gray-200 p-4">
          {title && <h2 className="text-sm font-semibold text-gray-900" data-testid={testId ? `${testId}-heading` : undefined}>{title}</h2>}
          {subtitle && <p className="text-xs text-gray-500">{subtitle}</p>}
          {searchable && (
            <div className="relative">
              <Search className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search..."
                className="w-full rounded-md border border-gray-200 py-1.5 pr-3 pl-8 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
                data-testid={testId ? `${testId}-search` : undefined}
              />
            </div>
          )}
        </div>
      )}

      {/* Items */}
      <div className="flex-1 overflow-y-auto p-4">
        {categories.length === 0 && (
          <div className="py-8 text-center text-sm text-gray-500">{emptyMessage}</div>
        )}

        {categories.map((category) => {
          const catItems = grouped[category];
          if (!catItems?.length) return null;
          const isCollapsed = collapsed[category];

          if (!hasCategories) {
            // No real categories — render items flat
            return (
              <div key={category} className="space-y-2">
                {catItems.map(renderItem)}
              </div>
            );
          }

          return (
            <div key={category} className="mb-6" data-testid={testId ? `${testId}-category-${category}` : undefined}>
              <button
                type="button"
                onClick={() => toggleCategory(category)}
                className="mb-2 flex w-full items-center gap-2 text-left text-xs font-medium text-gray-500 uppercase hover:text-gray-700"
              >
                {isCollapsed ? (
                  <ChevronRight className="h-3.5 w-3.5" />
                ) : (
                  <ChevronDown className="h-3.5 w-3.5" />
                )}
                {categoryIcons[category] && (
                  <span className="flex-shrink-0">{categoryIcons[category]}</span>
                )}
                {categoryLabels[category] || category}
              </button>

              {!isCollapsed && <div className="space-y-2">{catItems.map(renderItem)}</div>}
            </div>
          );
        })}
      </div>
    </div>
  );
}
