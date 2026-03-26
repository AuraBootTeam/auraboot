import React from 'react';
import type { BlockConfig, FieldCellConfig } from '~/studio/domain/schema/layout-hierarchy';
import { FieldCellDesigner } from './FieldCellDesigner';

interface BlockRegionDesignerProps {
  block: BlockConfig;
  tabId: string;
  floorId: string;
  selectedFieldId?: string;
  onSelectBlock: () => void;
  onSelectField: (fieldId: string) => void;
  onRemoveBlock: () => void;
  onRemoveField: (fieldId: string) => void;
  onUpdateField: (fieldId: string, updates: Partial<FieldCellConfig>) => void;
  onAddField?: () => void;
  selected: boolean;
}

/**
 * Block Region Designer - renders a block containing field cells.
 * Supports grid/flex layout modes for arranging fields.
 */
export const BlockRegionDesigner: React.FC<BlockRegionDesignerProps> = ({
  block,
  selectedFieldId,
  onSelectBlock,
  onSelectField,
  onRemoveBlock,
  onRemoveField,
  onUpdateField,
  onAddField,
  selected,
}) => {
  const gridStyle: React.CSSProperties =
    block.layout.type === 'grid'
      ? {
          display: 'grid',
          gridTemplateColumns: `repeat(${block.layout.columns || 2}, 1fr)`,
          gap: `${block.layout.gap || 16}px`,
        }
      : {
          display: 'flex',
          flexDirection: block.layout.direction || 'row',
          gap: `${block.layout.gap || 16}px`,
          flexWrap: 'wrap',
        };

  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onSelectBlock();
      }}
      className={`group/block relative rounded-lg border p-3 transition-all ${
        selected
          ? 'border-indigo-400 bg-indigo-50/30'
          : 'border-gray-200 bg-white hover:border-indigo-200'
      } `}
    >
      {/* Block header */}
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {block.title && <span className="text-xs font-medium text-gray-600">{block.title}</span>}
          <span className="text-xs text-gray-400">
            {block.layout.type === 'grid' ? `${block.layout.columns || 2}列` : block.layout.type}
          </span>
        </div>
        <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover/block:opacity-100">
          {onAddField && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onAddField();
              }}
              className="rounded p-1 text-gray-400 hover:text-indigo-600"
              title="添加字段"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 4v16m8-8H4"
                />
              </svg>
            </button>
          )}
          <button
            onClick={(e) => {
              e.stopPropagation();
              onRemoveBlock();
            }}
            className="rounded p-1 text-gray-400 hover:text-red-500"
            title="删除区块"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Field grid */}
      {block.fields.length > 0 ? (
        <div style={gridStyle}>
          {block.fields.map((field) => (
            <FieldCellDesigner
              key={field.id}
              field={field}
              selected={selectedFieldId === field.id}
              onSelect={() => onSelectField(field.id)}
              onRemove={() => onRemoveField(field.id)}
              onUpdate={(updates) => onUpdateField(field.id, updates)}
            />
          ))}
        </div>
      ) : (
        <div className="rounded-md border-2 border-dashed border-gray-200 py-6 text-center">
          <p className="text-xs text-gray-400">从字段库拖入字段</p>
        </div>
      )}
    </div>
  );
};
