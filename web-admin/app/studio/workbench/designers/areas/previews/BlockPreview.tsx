/**
 * Block Preview
 *
 * Renders a preview of a DSL block based on its type.
 * Supports field drop when dragging fields from the field library.
 */

import React from 'react';
import type { DslBlock, DslFieldRef } from '~/studio/domain/dsl/types';
import { FilterFormPreview } from './FilterFormPreview';
import { DataTablePreview } from './DataTablePreview';
import { FormSectionPreview } from './FormSectionPreview';
import { ToolbarPreview } from './ToolbarPreview';
import { useBlockDropZone } from '../hooks/useBlockDropZone';

/**
 * Selected field info structure
 */
interface SelectedFieldInfo {
  blockId: string;
  fieldIndex: number;
  fieldRef: DslFieldRef;
}

export interface BlockPreviewProps {
  block: DslBlock;
  isSelected: boolean;
  selectedFieldInfo?: SelectedFieldInfo | null;
  onClick: () => void;
  onDelete: () => void;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}

export const BlockPreview: React.FC<BlockPreviewProps> = ({
  block,
  isSelected,
  selectedFieldInfo,
  onClick,
  onDelete,
  onFieldReorder,
  onFieldSelect,
  readonly,
}) => {
  const { setNodeRef, showDropIndicator, dropLabel, canAcceptFields } = useBlockDropZone({
    block,
    disabled: readonly,
  });

  return (
    <div
      ref={setNodeRef}
      className={`group relative rounded-lg border-2 transition-all ${
        showDropIndicator
          ? 'border-green-500 bg-green-50/50 ring-2 ring-green-200'
          : isSelected
            ? 'border-blue-500 ring-2 ring-blue-100'
            : 'border-gray-200 hover:border-blue-300'
      }`}
    >
      {/* Block content based on type */}
      <BlockContent
        block={block}
        selectedFieldInfo={selectedFieldInfo}
        onFieldReorder={onFieldReorder}
        onFieldSelect={onFieldSelect}
        readonly={readonly}
      />

      {/* Field drop indicator overlay */}
      {showDropIndicator && (
        <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-lg bg-green-50/80">
          <div className="flex items-center gap-2 font-medium text-green-700">
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 6v6m0 0v6m0-6h6m-6 0H6"
              />
            </svg>
            <span>{dropLabel}</span>
          </div>
        </div>
      )}

      {/* Selection overlay with actions */}
      {isSelected && !readonly && (
        <div className="absolute top-2 right-2 flex items-center gap-1">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDelete();
            }}
            className="rounded bg-red-500 p-1.5 text-white transition-colors hover:bg-red-600"
            title="删除"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>
      )}

      {/* Block type badge */}
      <div className="absolute bottom-2 left-2 flex items-center gap-2">
        <span className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10px] text-gray-400">
          {block.blockType}
        </span>
        {canAcceptFields && !showDropIndicator && (
          <span className="text-[10px] text-gray-400 opacity-0 transition-opacity group-hover:opacity-100">
            拖拽字段到此处
          </span>
        )}
      </div>
    </div>
  );
};

/**
 * Render block content based on type
 */
const BlockContent: React.FC<{
  block: DslBlock;
  selectedFieldInfo?: SelectedFieldInfo | null;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}> = ({ block, selectedFieldInfo, onFieldReorder, onFieldSelect, readonly }) => {
  switch (block.blockType) {
    case 'filters':
      return (
        <FilterFormPreview
          block={block}
          selectedFieldIndex={selectedFieldInfo?.fieldIndex}
          onFieldReorder={onFieldReorder}
          onFieldSelect={onFieldSelect}
          readonly={readonly}
        />
      );

    case 'table':
      return <DataTablePreview block={block} />;

    case 'form-section':
    case 'detail-section':
      return (
        <FormSectionPreview
          block={block}
          selectedFieldIndex={selectedFieldInfo?.fieldIndex}
          onFieldReorder={onFieldReorder}
          onFieldSelect={onFieldSelect}
          readonly={readonly}
        />
      );

    case 'toolbar':
    case 'form-buttons':
      return <ToolbarPreview block={block} />;

    case 'selection-info':
      return <SelectionInfoPreview block={block} />;

    case 'stat-card':
      return <StatCardPreview block={block} />;

    case 'chart-card':
      return <ChartCardPreview block={block} />;

    case 'text':
      return <TextPreview block={block} />;

    default:
      return <GenericPreview block={block} />;
  }
};

/**
 * Selection info preview
 */
const SelectionInfoPreview: React.FC<{ block: DslBlock }> = ({ block }) => {
  return (
    <div className="bg-blue-50 p-3">
      <div className="flex items-center gap-2 text-blue-700">
        <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
        <span className="text-sm">已选择 0 项</span>
        <button className="ml-2 text-xs text-blue-600 hover:underline">清除</button>
      </div>
    </div>
  );
};

/**
 * Stat card preview
 */
const StatCardPreview: React.FC<{ block: DslBlock }> = ({ block }) => {
  return (
    <div className="bg-gradient-to-br from-blue-50 to-white p-4">
      <div className="mb-1 text-xs text-gray-500">{block.title || '统计指标'}</div>
      <div className="text-2xl font-semibold text-gray-900">--</div>
      <div className="mt-1 text-xs text-green-600">+0%</div>
    </div>
  );
};

/**
 * Chart card preview
 */
const ChartCardPreview: React.FC<{ block: DslBlock }> = ({ block }) => {
  return (
    <div className="p-4">
      <div className="mb-3 text-sm font-medium text-gray-900">{block.title || '图表'}</div>
      <div className="flex h-24 items-center justify-center rounded bg-gray-100">
        <svg
          className="h-8 w-8 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
          />
        </svg>
      </div>
    </div>
  );
};

/**
 * Text preview
 */
const TextPreview: React.FC<{ block: DslBlock }> = ({ block }) => {
  return (
    <div className="p-4">
      <div className="text-sm text-gray-600">{(block.props as any)?.content || '文本内容...'}</div>
    </div>
  );
};

/**
 * Generic fallback preview
 */
const GenericPreview: React.FC<{ block: DslBlock }> = ({ block }) => {
  return (
    <div className="bg-gray-50 p-4">
      <div className="flex items-center gap-2 text-gray-500">
        <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"
          />
        </svg>
        <span className="text-sm">{block.blockType}</span>
      </div>
    </div>
  );
};

export default BlockPreview;
