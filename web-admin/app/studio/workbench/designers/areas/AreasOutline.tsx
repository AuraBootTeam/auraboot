/**
 * Areas Outline
 *
 * Tree view of DSL structure for AreasDesigner.
 * Shows areas, blocks, and their fields/columns.
 */

import React, { useState, useMemo } from 'react';
import type { DslV4Schema, DslBlock, AreaName } from '~/studio/domain/dsl/types';
import { parseFieldShorthand, parseColumnShorthand } from '~/studio/domain/dsl/types';

export interface AreasOutlineProps {
  dsl: DslV4Schema;
  selectedBlockId: string | null;
  onBlockClick: (blockId: string) => void;
}

/**
 * Area display configuration
 */
const AREA_INFO: Record<AreaName, { icon: string; name: string }> = {
  filters: { icon: '🔍', name: 'Filters' },
  toolbar: { icon: '🔧', name: 'Toolbar' },
  main: { icon: '📋', name: 'Main Content' },
};

/**
 * Block type icons
 */
const BLOCK_TYPE_ICONS: Record<string, string> = {
  'filter-form': '🔍',
  'data-table': '📊',
  'toolbar-buttons': '🔘',
  'form-section': '📝',
  'form-buttons': '✅',
  'detail-section': '📄',
  'stat-card': '📈',
  'chart-card': '📉',
  text: '📃',
  'selection-info': '☑️',
};

/**
 * Single block item in the outline
 */
interface OutlineBlockItemProps {
  block: DslBlock;
  isSelected: boolean;
  onClick: () => void;
}

const OutlineBlockItem: React.FC<OutlineBlockItemProps> = ({ block, isSelected, onClick }) => {
  const [isExpanded, setIsExpanded] = useState(true);

  const icon = BLOCK_TYPE_ICONS[block.blockType] || '📦';

  // Get fields or columns based on block type
  const items = useMemo(() => {
    if (block.fields && block.fields.length > 0) {
      return block.fields.map((f) => {
        const parsed = parseFieldShorthand(f);
        return { type: 'field', code: parsed.field };
      });
    }
    if (block.columns && block.columns.length > 0) {
      return block.columns.map((c) => {
        const parsed = parseColumnShorthand(c);
        return { type: 'column', code: parsed.field };
      });
    }
    if (block.buttons && block.buttons.length > 0) {
      return block.buttons.map((b) => ({
        type: 'button',
        code: typeof b === 'string' ? b : b.action,
      }));
    }
    return [];
  }, [block.fields, block.columns, block.buttons]);

  const hasChildren = items.length > 0;

  return (
    <div className="ml-2">
      <div
        onClick={(e) => {
          e.stopPropagation();
          onClick();
        }}
        className={`flex cursor-pointer items-center gap-1.5 rounded px-2 py-1 transition-colors ${
          isSelected ? 'bg-blue-100 text-blue-800' : 'text-gray-700 hover:bg-gray-100'
        }`}
      >
        {/* Expand/collapse toggle */}
        {hasChildren ? (
          <button
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded(!isExpanded);
            }}
            className="flex h-4 w-4 items-center justify-center text-gray-400 hover:text-gray-600"
          >
            <svg
              className={`h-3 w-3 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        ) : (
          <span className="w-4" />
        )}

        <span className="text-sm">{icon}</span>
        <span className="flex-1 truncate text-xs font-medium">
          {block.title || block.blockType}
        </span>
        <span className="text-[10px] text-gray-400">{block.id.slice(0, 8)}</span>
      </div>

      {/* Children */}
      {hasChildren && isExpanded && (
        <div className="mt-0.5 ml-4 space-y-0.5 border-l border-gray-200 pl-2">
          {items.map((item, index) => (
            <div
              key={`${item.type}-${item.code}-${index}`}
              className="flex items-center gap-1.5 px-2 py-0.5 text-xs text-gray-500"
            >
              <span className="flex h-3 w-3 items-center justify-center text-[10px]">
                {item.type === 'field' && '📝'}
                {item.type === 'column' && '📊'}
                {item.type === 'button' && '🔘'}
              </span>
              <span className="truncate">{item.code}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * Single area section in the outline
 */
interface OutlineAreaProps {
  areaName: AreaName;
  blocks: DslBlock[];
  selectedBlockId: string | null;
  onBlockClick: (blockId: string) => void;
}

const OutlineArea: React.FC<OutlineAreaProps> = ({
  areaName,
  blocks,
  selectedBlockId,
  onBlockClick,
}) => {
  const [isExpanded, setIsExpanded] = useState(true);
  const info = AREA_INFO[areaName];

  return (
    <div className="mb-2">
      {/* Area header */}
      <div
        onClick={() => setIsExpanded(!isExpanded)}
        className="flex cursor-pointer items-center gap-1.5 rounded bg-gray-50 px-2 py-1.5 transition-colors hover:bg-gray-100"
      >
        <button className="flex h-4 w-4 items-center justify-center text-gray-400">
          <svg
            className={`h-3 w-3 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
        </button>
        <span className="text-sm">{info.icon}</span>
        <span className="text-xs font-semibold tracking-wide text-gray-700 uppercase">
          {info.name}
        </span>
        <span className="text-[10px] text-gray-400">({blocks.length})</span>
      </div>

      {/* Blocks */}
      {isExpanded && blocks.length > 0 && (
        <div className="mt-1 space-y-0.5">
          {blocks.map((block) => (
            <OutlineBlockItem
              key={block.id}
              block={block}
              isSelected={selectedBlockId === block.id}
              onClick={() => onBlockClick(block.id)}
            />
          ))}
        </div>
      )}

      {isExpanded && blocks.length === 0 && (
        <div className="mt-1 ml-6 text-[10px] text-gray-400 italic">No blocks</div>
      )}
    </div>
  );
};

export const AreasOutline: React.FC<AreasOutlineProps> = ({
  dsl,
  selectedBlockId,
  onBlockClick,
}) => {
  const areas = dsl.areas || {};

  // Determine visible areas based on page kind
  const visibleAreas: AreaName[] = useMemo(() => {
    switch (dsl.kind) {
      case 'list':
        return ['filters', 'toolbar', 'main'];
      case 'form':
        return ['main'];
      default:
        return ['main'];
    }
  }, [dsl.kind]);

  return (
    <div className="h-full overflow-auto p-3">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-xs font-semibold tracking-wider text-gray-500 uppercase">
          Page Structure
        </h3>
        <span className="text-[10px] text-gray-400">{dsl.kind}</span>
      </div>

      {/* Page info */}
      <div className="mb-3 rounded bg-blue-50 px-2 py-1.5 text-xs">
        <div className="truncate font-medium text-blue-800">{dsl.id}</div>
        {dsl.modelCode && (
          <div className="truncate text-[10px] text-blue-600">Model: {dsl.modelCode}</div>
        )}
      </div>

      {/* Areas */}
      <div className="space-y-1">
        {visibleAreas.map((areaName) => (
          <OutlineArea
            key={areaName}
            areaName={areaName}
            blocks={areas[areaName]?.blocks || []}
            selectedBlockId={selectedBlockId}
            onBlockClick={onBlockClick}
          />
        ))}
      </div>
    </div>
  );
};

export default AreasOutline;
