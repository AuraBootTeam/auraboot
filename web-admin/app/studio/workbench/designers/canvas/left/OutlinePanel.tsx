/**
 * OutlinePanel — Left panel tab showing the current block tree
 *
 * Displays a flat list of canvas blocks. Clicking an item selects it.
 * Selected item highlighted with purple background.
 *
 * @since 4.0.0
 */

import React from 'react';
import type { CanvasBlock } from '~/studio/domain/canvas/types';

export interface OutlinePanelProps {
  blocks: CanvasBlock[];
  selectedBlockId: string | null;
  onSelectBlock: (id: string) => void;
}

// ─── Block type icons ─────────────────────────────────────────────────────────

const BLOCK_TYPE_ICONS: Record<string, string> = {
  table: '☰',
  'sub-table': '⊞',
  chart: '📊',
  'stat-card': '🔢',
  tabs: '⬚',
  divider: '―',
  'form-section': '📝',
  'rich-text': 'T',
  toolbar: '🔘',
  filters: '🔍',
  custom: '⚙',
};

function getBlockIcon(blockType: string): string {
  return BLOCK_TYPE_ICONS[blockType] ?? '□';
}

function getBlockLabel(blockType: string): string {
  // Convert kebab-case to Title Case
  return blockType
    .split('-')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

// ─── OutlinePanel ─────────────────────────────────────────────────────────────

export const OutlinePanel: React.FC<OutlinePanelProps> = ({
  blocks,
  selectedBlockId,
  onSelectBlock,
}) => {
  return (
    <div className="flex h-full flex-col" data-testid="outline-panel">
      {/* Block list */}
      <div className="flex-1 overflow-y-auto px-2 py-2">
        {blocks.length === 0 ? (
          <div className="py-8 text-center text-xs text-gray-400">
            <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-gray-100 text-lg">
              □
            </div>
            <p>No blocks yet</p>
            <p className="mt-1 text-[10px] text-gray-300">Add blocks from Components tab</p>
          </div>
        ) : (
          <div className="space-y-0.5">
            {blocks.map((block, index) => {
              const isSelected = block.id === selectedBlockId;
              return (
                <button
                  key={block.id}
                  onClick={() => onSelectBlock(block.id)}
                  className={`flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left transition-colors ${
                    isSelected
                      ? 'bg-purple-100 text-purple-800'
                      : 'text-gray-700 hover:bg-gray-100'
                  }`}
                  data-testid={`outline-item-${block.id}`}
                  title={`${getBlockLabel(block.blockType)} — ${block.id}`}
                >
                  {/* Index badge */}
                  <span
                    className={`flex h-4 w-4 flex-shrink-0 items-center justify-center rounded text-[9px] font-semibold ${
                      isSelected ? 'bg-purple-200 text-purple-700' : 'bg-gray-200 text-gray-500'
                    }`}
                  >
                    {index + 1}
                  </span>

                  {/* Block type icon */}
                  <span className="flex-shrink-0 text-sm leading-none">
                    {getBlockIcon(block.blockType)}
                  </span>

                  {/* Block type label */}
                  <span className="min-w-0 flex-1 truncate text-xs font-medium">
                    {getBlockLabel(block.blockType)}
                  </span>

                  {/* Block id (truncated) */}
                  <span
                    className={`flex-shrink-0 font-mono text-[9px] ${
                      isSelected ? 'text-purple-500' : 'text-gray-400'
                    }`}
                  >
                    {block.id.slice(-6)}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="border-t border-gray-100 px-3 py-1.5 text-[10px] text-gray-400">
        {blocks.length} {blocks.length === 1 ? 'block' : 'blocks'}
        {selectedBlockId && ' — 1 selected'}
      </div>
    </div>
  );
};

export default OutlinePanel;
