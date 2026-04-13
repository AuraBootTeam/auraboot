import React from 'react';
import type {
  FloorConfig,
  BlockConfig,
  FieldCellConfig,
} from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import { BlockRegionDesigner } from './BlockRegionDesigner';

interface FloorSectionDesignerProps {
  floor: FloorConfig;
  tabId: string;
  selectedBlockId?: string;
  selectedFieldId?: string;
  onSelectFloor: () => void;
  onSelectBlock: (blockId: string) => void;
  onSelectField: (blockId: string, fieldId: string) => void;
  onRemoveFloor: () => void;
  onAddBlock: () => void;
  onRemoveBlock: (blockId: string) => void;
  onRemoveField: (blockId: string, fieldId: string) => void;
  onUpdateField: (blockId: string, fieldId: string, updates: Partial<FieldCellConfig>) => void;
  onToggleCollapse: () => void;
  selected: boolean;
  canRemove: boolean;
}

/**
 * Floor Section Designer - renders a collapsible floor section
 * containing blocks. Each floor is a logical grouping.
 */
export const FloorSectionDesigner: React.FC<FloorSectionDesignerProps> = ({
  floor,
  tabId,
  selectedBlockId,
  selectedFieldId,
  onSelectFloor,
  onSelectBlock,
  onSelectField,
  onRemoveFloor,
  onAddBlock,
  onRemoveBlock,
  onRemoveField,
  onUpdateField,
  onToggleCollapse,
  selected,
  canRemove,
}) => {
  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onSelectFloor();
      }}
      className={`rounded-lg border transition-all ${
        selected ? 'border-purple-400 shadow-sm' : 'border-gray-200 hover:border-purple-200'
      } `}
    >
      {/* Floor header */}
      <div className="flex items-center justify-between rounded-t-lg border-b border-gray-200 bg-gray-50 px-4 py-2.5">
        <div className="flex items-center gap-2">
          {floor.collapsible && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onToggleCollapse();
              }}
              className="rounded p-0.5 text-gray-400 hover:text-gray-600"
            >
              <svg
                className={`h-4 w-4 transition-transform ${floor.collapsed ? '' : 'rotate-90'}`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </button>
          )}
          <h4 className="text-sm font-medium text-gray-700">{floor.title || '未命名楼层'}</h4>
          {floor.description && <span className="text-xs text-gray-400">{floor.description}</span>}
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onAddBlock();
            }}
            className="rounded p-1 text-gray-400 hover:bg-purple-50 hover:text-purple-600"
            title="添加区块"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
          </button>
          {canRemove && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onRemoveFloor();
              }}
              className="rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-500"
              title="删除楼层"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                />
              </svg>
            </button>
          )}
        </div>
      </div>

      {/* Floor content (blocks) */}
      {!floor.collapsed && (
        <div className="space-y-3 p-4">
          {floor.blocks.map((block) => (
            <BlockRegionDesigner
              key={block.id}
              block={block}
              tabId={tabId}
              floorId={floor.id}
              selectedFieldId={selectedBlockId === block.id ? selectedFieldId : undefined}
              selected={selectedBlockId === block.id}
              onSelectBlock={() => onSelectBlock(block.id)}
              onSelectField={(fieldId) => onSelectField(block.id, fieldId)}
              onRemoveBlock={() => onRemoveBlock(block.id)}
              onRemoveField={(fieldId) => onRemoveField(block.id, fieldId)}
              onUpdateField={(fieldId, updates) => onUpdateField(block.id, fieldId, updates)}
            />
          ))}

          {floor.blocks.length === 0 && (
            <div className="rounded-lg border-2 border-dashed border-gray-200 py-8 text-center">
              <p className="text-sm text-gray-400">点击上方 "+" 添加区块</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
