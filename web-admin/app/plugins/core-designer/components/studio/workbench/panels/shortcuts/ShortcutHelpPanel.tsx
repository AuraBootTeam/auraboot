/**
 * ShortcutHelpPanel Component
 *
 * Displays all available keyboard shortcuts with search and filtering.
 *
 * @since 3.2.0
 */

import React, { useState, useMemo, useCallback, useEffect } from 'react';
import {
  CATEGORIES,
  SHORTCUTS,
  getShortcutsByCategory,
  searchShortcuts,
  formatKeyCombo,
} from './shortcuts';
import type { ShortcutCategory, ShortcutDefinition } from './types';

interface ShortcutHelpPanelProps {
  /** Whether panel is visible */
  isOpen: boolean;
  /** Close handler */
  onClose: () => void;
  /** Modal mode (overlay) or inline */
  mode?: 'modal' | 'inline';
}

/**
 * ShortcutHelpPanel Component
 */
export const ShortcutHelpPanel: React.FC<ShortcutHelpPanelProps> = ({
  isOpen,
  onClose,
  mode = 'modal',
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<ShortcutCategory | 'all'>('all');

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Filter shortcuts
  const filteredShortcuts = useMemo(() => {
    let shortcuts = searchQuery
      ? searchShortcuts(searchQuery)
      : getShortcutsByCategory(selectedCategory);

    // Group by category
    const grouped = new Map<ShortcutCategory, ShortcutDefinition[]>();
    shortcuts.forEach((shortcut) => {
      const list = grouped.get(shortcut.category) || [];
      list.push(shortcut);
      grouped.set(shortcut.category, list);
    });

    return grouped;
  }, [searchQuery, selectedCategory]);

  // Handle search
  const handleSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(e.target.value);
    // Reset category filter when searching
    if (e.target.value) {
      setSelectedCategory('all');
    }
  }, []);

  // Handle category click
  const handleCategoryClick = useCallback((category: ShortcutCategory | 'all') => {
    setSelectedCategory(category);
    setSearchQuery('');
  }, []);

  if (!isOpen) return null;

  const panelContent = (
    <div className="flex h-full max-h-[80vh] flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
        <h2 className="text-lg font-semibold text-gray-900">键盘快捷键</h2>
        <button
          type="button"
          onClick={onClose}
          className="rounded p-1 text-gray-400 hover:text-gray-600"
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      </div>

      {/* Search */}
      <div className="border-b border-gray-100 px-4 py-3">
        <div className="relative">
          <svg
            className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
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
            value={searchQuery}
            onChange={handleSearchChange}
            placeholder="搜索快捷键..."
            className="w-full rounded-md border border-gray-200 py-2 pr-3 pl-9 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            autoFocus
          />
        </div>
      </div>

      {/* Categories */}
      <div className="flex flex-wrap gap-1 border-b border-gray-100 px-4 py-2">
        <button
          type="button"
          onClick={() => handleCategoryClick('all')}
          className={`rounded-md px-2.5 py-1 text-xs transition-colors ${
            selectedCategory === 'all' && !searchQuery
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          全部
        </button>
        {CATEGORIES.sort((a, b) => a.order - b.order).map((category) => (
          <button
            key={category.id}
            type="button"
            onClick={() => handleCategoryClick(category.id)}
            className={`flex items-center gap-1 rounded-md px-2.5 py-1 text-xs transition-colors ${
              selectedCategory === category.id && !searchQuery
                ? 'bg-blue-100 text-blue-700'
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}
          >
            <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d={category.icon}
              />
            </svg>
            {category.name}
          </button>
        ))}
      </div>

      {/* Shortcuts list */}
      <div className="flex-1 overflow-auto px-4 py-3">
        {filteredShortcuts.size === 0 ? (
          <div className="py-8 text-center text-gray-500">没有找到匹配的快捷键</div>
        ) : (
          Array.from(filteredShortcuts.entries())
            .sort(([catA], [catB]) => {
              const orderA = CATEGORIES.find((c) => c.id === catA)?.order || 99;
              const orderB = CATEGORIES.find((c) => c.id === catB)?.order || 99;
              return orderA - orderB;
            })
            .map(([category, shortcuts]) => {
              const categoryInfo = CATEGORIES.find((c) => c.id === category);
              return (
                <div key={category} className="mb-6 last:mb-0">
                  {/* Category header */}
                  <div className="mb-2 flex items-center gap-2">
                    <svg
                      className="h-4 w-4 text-gray-400"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d={categoryInfo?.icon || ''}
                      />
                    </svg>
                    <span className="text-sm font-medium text-gray-700">
                      {categoryInfo?.name || category}
                    </span>
                  </div>

                  {/* Shortcuts */}
                  <div className="space-y-1">
                    {shortcuts.map((shortcut) => (
                      <ShortcutItem key={shortcut.id} shortcut={shortcut} />
                    ))}
                  </div>
                </div>
              );
            })
        )}
      </div>

      {/* Footer */}
      <div className="border-t border-gray-100 px-4 py-2 text-center text-xs text-gray-400">
        按 <kbd className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-600">Esc</kbd> 关闭
      </div>
    </div>
  );

  if (mode === 'inline') {
    return (
      <div className="rounded-lg border border-gray-200 bg-white shadow-sm">{panelContent}</div>
    );
  }

  // Modal mode
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />

      {/* Panel */}
      <div className="relative mx-4 w-full max-w-2xl rounded-lg bg-white shadow-xl">
        {panelContent}
      </div>
    </div>
  );
};

/**
 * Single shortcut item
 */
interface ShortcutItemProps {
  shortcut: ShortcutDefinition;
}

const ShortcutItem: React.FC<ShortcutItemProps> = ({ shortcut }) => {
  const keyCombo = formatKeyCombo(shortcut.keys);

  return (
    <div className="flex items-center justify-between rounded-md px-3 py-2 hover:bg-gray-50">
      <div className="min-w-0 flex-1">
        <div className="text-sm text-gray-900">{shortcut.label}</div>
        {shortcut.description && (
          <div className="truncate text-xs text-gray-500">{shortcut.description}</div>
        )}
      </div>
      <div className="ml-4 flex-shrink-0">
        <KeyCombo combo={keyCombo} />
      </div>
    </div>
  );
};

/**
 * Key combination display
 */
interface KeyComboProps {
  combo: string;
}

const KeyCombo: React.FC<KeyComboProps> = ({ combo }) => {
  // Split by " / " for alternatives
  const alternatives = combo.split(' / ');

  return (
    <div className="flex items-center gap-1">
      {alternatives.map((alt, altIndex) => (
        <React.Fragment key={altIndex}>
          {altIndex > 0 && <span className="mx-1 text-xs text-gray-400">或</span>}
          <div className="flex items-center gap-0.5">
            {alt
              .split(/(?=[A-Z+⌘⇧⌥⌫␣])/)
              .filter(Boolean)
              .map((key, keyIndex) => {
                // Don't split on + in key combinations
                const keys = alt.includes('+') ? alt.split('+') : [alt];
                if (keyIndex >= keys.length) return null;

                return (
                  <kbd
                    key={keyIndex}
                    className="rounded border border-gray-200 bg-gray-100 px-1.5 py-0.5 font-mono text-xs text-gray-700 shadow-sm"
                  >
                    {keys[keyIndex]}
                  </kbd>
                );
              })}
          </div>
        </React.Fragment>
      ))}
    </div>
  );
};

export default ShortcutHelpPanel;
