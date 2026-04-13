/**
 * BlockPalette — Left panel tab showing available block types
 *
 * Groups block types by category. Each item is draggable (useDraggable from @dnd-kit/core)
 * with id `palette:{type}`. Clicking also calls onAddBlock as fallback before DnD is wired.
 *
 * @since 4.0.0
 */

import React, { useState } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { BlockRegistry } from '~/plugins/core-designer/components/studio/registry';
import type { BlockDefinition } from '~/plugins/core-designer/components/studio/registry';

export interface BlockPaletteProps {
  onAddBlock: (blockType: string) => void;
  readonly?: boolean;
}

// ─── Category display config ─────────────────────────────────────────────────

type BlockCategory = 'data' | 'layout' | 'form' | 'display';

const CATEGORY_CONFIG: Record<BlockCategory, { label: string; color: string; badgeBg: string; badgeText: string }> = {
  data: {
    label: 'Data',
    color: 'text-purple-600',
    badgeBg: 'bg-purple-100',
    badgeText: 'text-purple-700',
  },
  layout: {
    label: 'Layout',
    color: 'text-blue-600',
    badgeBg: 'bg-blue-100',
    badgeText: 'text-blue-700',
  },
  form: {
    label: 'Form',
    color: 'text-yellow-600',
    badgeBg: 'bg-yellow-100',
    badgeText: 'text-yellow-700',
  },
  display: {
    label: 'Display',
    color: 'text-green-600',
    badgeBg: 'bg-green-100',
    badgeText: 'text-green-700',
  },
};

const CATEGORY_ORDER: BlockCategory[] = ['data', 'layout', 'form', 'display'];

// ─── DraggableBlockItem ───────────────────────────────────────────────────────

interface DraggableBlockItemProps {
  block: BlockDefinition;
  onAddBlock: (blockType: string) => void;
  disabled?: boolean;
}

const DraggableBlockItem: React.FC<DraggableBlockItemProps> = ({ block, onAddBlock, disabled }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `palette:${block.type}`,
    disabled,
    data: { blockType: block.type },
  });

  const catConfig = CATEGORY_CONFIG[block.category as BlockCategory];

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData('text/x-block-type', block.type);
        e.dataTransfer.effectAllowed = 'copy';
      }}
      onClick={() => !disabled && onAddBlock(block.type)}
      title={`Click or drag to add ${block.name}`}
      className={`flex cursor-pointer items-start gap-2 rounded-md border px-2 py-1.5 transition-all select-none ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-blue-50/30 hover:shadow-sm active:scale-[0.98]'
      }`}
      data-testid={`block-palette-item-${block.type}`}
    >
      {/* Icon badge */}
      <span
        className={`mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded text-xs font-bold ${catConfig.badgeBg} ${catConfig.badgeText}`}
      >
        {block.icon}
      </span>

      {/* Name + description */}
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium text-gray-800">{block.name}</div>
        <div className="truncate text-[10px] leading-tight text-gray-400">{block.description}</div>
      </div>
    </div>
  );
};

// ─── BlockPalette ─────────────────────────────────────────────────────────────

export const BlockPalette: React.FC<BlockPaletteProps> = ({ onAddBlock, readonly }) => {
  const [search, setSearch] = useState('');

  // Pull all registered blocks from the singleton registry
  const allBlocks = BlockRegistry.getAll();

  const filteredByCategory = CATEGORY_ORDER.reduce<Record<BlockCategory, BlockDefinition[]>>(
    (acc, cat) => {
      acc[cat] = allBlocks.filter(
        (b) =>
          b.category === cat &&
          (!search.trim() ||
            b.name.toLowerCase().includes(search.toLowerCase()) ||
            b.type.toLowerCase().includes(search.toLowerCase())),
      );
      return acc;
    },
    {} as Record<BlockCategory, BlockDefinition[]>,
  );

  const hasResults = CATEGORY_ORDER.some((cat) => filteredByCategory[cat].length > 0);

  return (
    <div className="flex h-full flex-col" data-testid="block-palette">
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
            placeholder="Search blocks..."
            className="w-full rounded border border-gray-200 bg-gray-50 py-1 pr-2 pl-6 text-xs placeholder-gray-400 focus:border-blue-400 focus:bg-white focus:outline-none"
            data-testid="block-palette-search"
          />
        </div>
      </div>

      {/* Block list grouped by category */}
      <div className="flex-1 overflow-y-auto px-3 py-2">
        {!hasResults ? (
          <div className="py-6 text-center text-xs text-gray-400">No blocks found</div>
        ) : (
          <div className="space-y-3">
            {CATEGORY_ORDER.map((cat) => {
              const items = filteredByCategory[cat];
              if (items.length === 0) return null;
              const catConfig = CATEGORY_CONFIG[cat];
              return (
                <div key={cat}>
                  <div
                    className={`mb-1 text-[10px] font-semibold uppercase tracking-wider ${catConfig.color}`}
                  >
                    {catConfig.label}
                  </div>
                  <div className="space-y-1">
                    {items.map((block) => (
                      <DraggableBlockItem
                        key={block.type}
                        block={block}
                        onAddBlock={onAddBlock}
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
        {allBlocks.length} block types — click or drag to add
      </div>
    </div>
  );
};

export default BlockPalette;
