import React, { useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import type { BlockDefinitionV3, DslBlockV3, ModelFieldDefinition, PageSchemaV3 } from '../types';
import { PALETTE_BLOCK_TYPE_MIME, writeModelFieldPayload } from '../utils/dragPayload';

interface ResourcePanelProps {
  document: PageSchemaV3;
  selectedBlockId: string | null;
  selectedBlock: DslBlockV3 | null;
  blockDefinitions: BlockDefinitionV3[];
  selectedModelCode: string | null;
  modelFields: ModelFieldDefinition[];
  canAddBlock: (blockType: string) => boolean;
  canAddModelField: (field: ModelFieldDefinition) => boolean;
  isModelFieldUsed: (field: ModelFieldDefinition) => boolean;
  onSelect: (blockId: string) => void;
  onAddBlock: (blockType: string) => void;
  onAddModelField: (field: ModelFieldDefinition) => void;
  onPaletteDragStart: (blockType: string) => void;
  onPaletteDragEnd: () => void;
  onModelFieldDragStart: (field: ModelFieldDefinition) => void;
  onModelFieldDragEnd: () => void;
}

type ResourcePanelTab = 'outline' | 'blocks' | 'fields';

export function ResourcePanel({
  document,
  selectedBlockId,
  selectedBlock,
  blockDefinitions,
  selectedModelCode,
  modelFields,
  canAddBlock,
  canAddModelField,
  isModelFieldUsed,
  onSelect,
  onAddBlock,
  onAddModelField,
  onPaletteDragStart,
  onPaletteDragEnd,
  onModelFieldDragStart,
  onModelFieldDragEnd,
}: ResourcePanelProps) {
  const [activeTab, setActiveTab] = useState<ResourcePanelTab>('outline');

  return (
    <aside
      className="flex max-h-[220px] w-full shrink-0 flex-col border-b border-slate-200 bg-white xl:max-h-none xl:w-[260px] xl:border-b-0 xl:border-r"
      data-testid="unified-resource-panel"
    >
      <div className="grid grid-cols-3 border-b border-slate-200 text-xs">
        <ResourcePanelTabButton
          active={activeTab === 'outline'}
          testId="resource-tab-outline"
          onClick={() => setActiveTab('outline')}
        >
          Outline
        </ResourcePanelTabButton>
        <ResourcePanelTabButton
          active={activeTab === 'blocks'}
          testId="resource-tab-blocks"
          onClick={() => setActiveTab('blocks')}
        >
          Blocks
        </ResourcePanelTabButton>
        <ResourcePanelTabButton
          active={activeTab === 'fields'}
          testId="resource-tab-fields"
          onClick={() => setActiveTab('fields')}
          isLast
        >
          Fields
        </ResourcePanelTabButton>
      </div>
      <div className="flex-1 overflow-auto p-3">
        {activeTab === 'outline' ? (
          <>
            <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">
              Page tree
            </div>
            <OutlineList
              blocks={document.blocks}
              selectedBlockId={selectedBlockId}
              onSelect={onSelect}
            />
          </>
        ) : null}
        {activeTab === 'blocks' ? (
          <BlockPalette
            blockDefinitions={blockDefinitions}
            selectedBlock={selectedBlock}
            canAddBlock={canAddBlock}
            onAddBlock={onAddBlock}
            onPaletteDragStart={onPaletteDragStart}
            onPaletteDragEnd={onPaletteDragEnd}
          />
        ) : null}
        {activeTab === 'fields' ? (
          <FieldPalette
            selectedModelCode={selectedModelCode}
            modelFields={modelFields}
            canAddField={canAddBlock('field')}
            canAddModelField={canAddModelField}
            isModelFieldUsed={isModelFieldUsed}
            onAddField={() => onAddBlock('field')}
            onAddModelField={onAddModelField}
            onModelFieldDragStart={onModelFieldDragStart}
            onModelFieldDragEnd={onModelFieldDragEnd}
          />
        ) : null}
      </div>
    </aside>
  );
}

function ResourcePanelTabButton({
  active,
  testId,
  onClick,
  children,
  isLast = false,
}: {
  active: boolean;
  testId: string;
  onClick: () => void;
  children: React.ReactNode;
  isLast?: boolean;
}) {
  return (
    <button
      type="button"
      data-testid={testId}
      onClick={onClick}
      className={`${isLast ? '' : 'border-r border-slate-200'} px-3 py-2 ${
        active ? 'font-medium text-blue-700' : 'text-slate-500 hover:bg-slate-50'
      }`}
    >
      {children}
    </button>
  );
}

function BlockPalette({
  blockDefinitions,
  selectedBlock,
  canAddBlock,
  onAddBlock,
  onPaletteDragStart,
  onPaletteDragEnd,
}: {
  blockDefinitions: BlockDefinitionV3[];
  selectedBlock: DslBlockV3 | null;
  canAddBlock: (blockType: string) => boolean;
  onAddBlock: (blockType: string) => void;
  onPaletteDragStart: (blockType: string) => void;
  onPaletteDragEnd: () => void;
}) {
  const groupedDefinitions = useMemo(() => groupDefinitions(blockDefinitions), [blockDefinitions]);

  return (
    <div className="space-y-4" data-testid="block-palette">
      <div>
        <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">Target</div>
        <div className="mt-1 truncate rounded-md border border-slate-200 bg-slate-50 px-2 py-1.5 text-xs font-medium text-slate-600">
          {selectedBlock ? getBlockLabel(selectedBlock) : 'Page root'}
        </div>
      </div>
      {groupedDefinitions.map(([category, definitions]) => (
        <div key={category} className="space-y-1.5">
          <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            {category}
          </div>
          {definitions.map((definition) => {
            const enabled = canAddBlock(definition.blockType);
            return (
              <button
                key={definition.blockType}
                type="button"
                data-testid={`palette-add-${definition.blockType}`}
                disabled={!enabled}
                draggable={enabled}
                onPointerDown={() => {
                  if (enabled) onPaletteDragStart(definition.blockType);
                }}
                onPointerUp={onPaletteDragEnd}
                onMouseDown={() => {
                  if (enabled) onPaletteDragStart(definition.blockType);
                }}
                onMouseUp={onPaletteDragEnd}
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = 'copy';
                  event.dataTransfer.setData(PALETTE_BLOCK_TYPE_MIME, definition.blockType);
                  event.dataTransfer.setData('text/plain', `palette:${definition.blockType}`);
                  onPaletteDragStart(definition.blockType);
                }}
                onDragEnd={onPaletteDragEnd}
                onClick={() => onAddBlock(definition.blockType)}
                className={`flex w-full items-center justify-between rounded-md border px-2 py-2 text-left text-xs ${
                  enabled
                    ? 'border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50'
                    : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
                }`}
              >
                <span>
                  <span className="block font-medium">{localizedToString(definition.label)}</span>
                  <span className="font-mono text-[10px]">{definition.blockType}</span>
                </span>
                <Plus className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            );
          })}
        </div>
      ))}
    </div>
  );
}

function FieldPalette({
  selectedModelCode,
  modelFields,
  canAddField,
  canAddModelField,
  isModelFieldUsed,
  onAddField,
  onAddModelField,
  onModelFieldDragStart,
  onModelFieldDragEnd,
}: {
  selectedModelCode: string | null;
  modelFields: ModelFieldDefinition[];
  canAddField: boolean;
  canAddModelField: (field: ModelFieldDefinition) => boolean;
  isModelFieldUsed: (field: ModelFieldDefinition) => boolean;
  onAddField: () => void;
  onAddModelField: (field: ModelFieldDefinition) => void;
  onModelFieldDragStart: (field: ModelFieldDefinition) => void;
  onModelFieldDragEnd: () => void;
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const normalizedSearchQuery = searchQuery.trim().toLowerCase();
  const filteredModelFields = normalizedSearchQuery
    ? modelFields.filter((field) => {
        const label = localizedToString(field.label).toLowerCase();
        const type = field.type?.toLowerCase() ?? '';
        return (
          label.includes(normalizedSearchQuery) ||
          field.code.toLowerCase().includes(normalizedSearchQuery) ||
          type.includes(normalizedSearchQuery)
        );
      })
    : modelFields;

  return (
    <div className="space-y-3" data-testid="field-palette">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">Fields</div>
      {modelFields.length ? (
        <div className="space-y-1.5">
          <div className="truncate rounded-md border border-slate-200 bg-slate-50 px-2 py-1.5 text-xs font-medium text-slate-600">
            Model: {selectedModelCode}
          </div>
          <input
            type="search"
            data-testid="field-palette-search"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            placeholder="Search fields"
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-xs text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
          {filteredModelFields.map((field) => {
            const enabled = canAddModelField(field);
            const used = isModelFieldUsed(field);
            return (
              <button
                key={`${field.modelCode}.${field.code}`}
                type="button"
                data-testid={`model-field-${field.code}`}
                data-used={used ? 'true' : 'false'}
                disabled={!enabled}
                draggable={enabled}
                onPointerDown={() => {
                  if (enabled) onModelFieldDragStart(field);
                }}
                onPointerUp={onModelFieldDragEnd}
                onMouseDown={() => {
                  if (enabled) onModelFieldDragStart(field);
                }}
                onMouseUp={onModelFieldDragEnd}
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = 'copy';
                  writeModelFieldPayload(event.dataTransfer, field);
                  onModelFieldDragStart(field);
                }}
                onDragEnd={onModelFieldDragEnd}
                onClick={() => onAddModelField(field)}
                className={`flex w-full items-center justify-between rounded-md border px-2 py-2 text-left text-xs ${
                  enabled
                    ? 'border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50'
                    : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
                }`}
              >
                <span className="min-w-0">
                  <span className="block font-medium">{localizedToString(field.label)}</span>
                  <span className="mt-1 flex flex-wrap items-center gap-1">
                    <span className="font-mono text-[10px]">{field.code}</span>
                    {field.type ? (
                      <span
                        data-testid={`model-field-type-${field.code}`}
                        className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium uppercase text-slate-500"
                      >
                        {field.type}
                      </span>
                    ) : null}
                  </span>
                </span>
                {used ? (
                  <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-400">
                    Added
                  </span>
                ) : (
                  <Plus className="h-3.5 w-3.5" aria-hidden="true" />
                )}
              </button>
            );
          })}
          {!filteredModelFields.length ? (
            <div className="rounded-md border border-dashed border-slate-200 px-2 py-3 text-center text-xs text-slate-400">
              No fields match
            </div>
          ) : null}
        </div>
      ) : null}
      <button
        type="button"
        data-testid="field-palette-add-field"
        disabled={!canAddField}
        onClick={onAddField}
        className={`flex w-full items-center justify-between rounded-md border px-2 py-2 text-xs ${
          canAddField
            ? 'border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50'
            : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
        }`}
      >
        <span className="font-medium">Field</span>
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}

function OutlineList({
  blocks,
  selectedBlockId,
  onSelect,
  depth = 0,
}: {
  blocks: DslBlockV3[];
  selectedBlockId: string | null;
  onSelect: (blockId: string) => void;
  depth?: number;
}) {
  return (
    <ul className="space-y-1">
      {blocks.map((block) => (
        <li key={block.id}>
          <button
            type="button"
            data-testid={`outline-item-${block.id}`}
            onClick={() => onSelect(block.id)}
            className={`flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left text-xs ${
              selectedBlockId === block.id
                ? 'bg-blue-50 font-semibold text-blue-700'
                : 'text-slate-600 hover:bg-slate-50'
            }`}
            style={{ paddingLeft: `${8 + depth * 14}px` }}
          >
            <span className="truncate">{getBlockLabel(block)}</span>
            <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-400">
              {block.blockType}
            </span>
          </button>
          {block.blocks?.length ? (
            <OutlineList
              blocks={block.blocks}
              selectedBlockId={selectedBlockId}
              onSelect={onSelect}
              depth={depth + 1}
            />
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function getBlockLabel(block: DslBlockV3): string {
  if (typeof block.title === 'string') return block.title;
  if (block.title?.en) return block.title.en;
  if (block.title?.['zh-CN']) return block.title['zh-CN'];
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function localizedToString(value: BlockDefinitionV3['label']): string {
  if (typeof value === 'string') return value;
  return value.en || value['zh-CN'] || Object.values(value)[0] || '';
}

function groupDefinitions(definitions: BlockDefinitionV3[]) {
  const groups = new Map<string, BlockDefinitionV3[]>();
  definitions.forEach((definition) => {
    const current = groups.get(definition.category) ?? [];
    current.push(definition);
    groups.set(definition.category, current);
  });
  return Array.from(groups.entries());
}
