import React, { useMemo, useState } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { Plus, Sparkles } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { BlockDefinitionV3, DslBlockV3, LocalizedText, ModelFieldDefinition, PageSchemaV3 } from '../types';
import { fieldDraggableId, paletteDraggableId } from '../dnd/dndShared';
import { groupModelFields } from '../utils/fieldGrouping';

/**
 * Field-like leaf blocks are added by binding a model field from the Fields tab,
 * not as blank placeholders from the Blocks palette. They stay registered (valid
 * children / inspector targets) but are hidden from the palette.
 */
const FIELD_LIKE_BLOCK_TYPES = new Set(['field', 'column', 'filter-field']);

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
}: ResourcePanelProps) {
  const { locale } = useI18n();
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
          {resolveDesignerText(DESIGNER_I18N.unified.tabOutline, locale)}
        </ResourcePanelTabButton>
        <ResourcePanelTabButton
          active={activeTab === 'blocks'}
          testId="resource-tab-blocks"
          onClick={() => setActiveTab('blocks')}
        >
          {resolveDesignerText(DESIGNER_I18N.unified.tabBlocks, locale)}
        </ResourcePanelTabButton>
        <ResourcePanelTabButton
          active={activeTab === 'fields'}
          testId="resource-tab-fields"
          onClick={() => setActiveTab('fields')}
          isLast
        >
          {resolveDesignerText(DESIGNER_I18N.unified.tabFields, locale)}
        </ResourcePanelTabButton>
      </div>
      <div className="flex-1 overflow-auto p-3">
        {activeTab === 'outline' ? (
          <>
            <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">
              {resolveDesignerText(DESIGNER_I18N.unified.pageTree, locale)}
            </div>
            <OutlineList
              blocks={document.blocks}
              selectedBlockId={selectedBlockId}
              onSelect={onSelect}
              locale={locale}
            />
          </>
        ) : null}
        {activeTab === 'blocks' ? (
          <BlockPalette
            blockDefinitions={blockDefinitions}
            selectedBlock={selectedBlock}
            canAddBlock={canAddBlock}
            onAddBlock={onAddBlock}
            locale={locale}
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
            locale={locale}
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
      data-active={active ? 'true' : 'false'}
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
  locale,
}: {
  blockDefinitions: BlockDefinitionV3[];
  selectedBlock: DslBlockV3 | null;
  canAddBlock: (blockType: string) => boolean;
  onAddBlock: (blockType: string) => void;
  locale: string;
}) {
  const paletteDefinitions = useMemo(
    () => blockDefinitions.filter((definition) => !FIELD_LIKE_BLOCK_TYPES.has(definition.blockType)),
    [blockDefinitions],
  );
  const groupedDefinitions = useMemo(
    () => groupDefinitions(paletteDefinitions),
    [paletteDefinitions],
  );

  return (
    <div className="space-y-4" data-testid="block-palette">
      <div>
        <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.target, locale)}
        </div>
        <div className="mt-1 truncate rounded-md border border-slate-200 bg-slate-50 px-2 py-1.5 text-xs font-medium text-slate-600">
          {selectedBlock
            ? getBlockLabel(selectedBlock, locale)
            : resolveDesignerText(DESIGNER_I18N.unified.pageRoot, locale)}
        </div>
      </div>
      {groupedDefinitions.map(([category, definitions]) => (
        <div key={category} className="space-y-1.5">
          <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            {DESIGNER_I18N.unified.category[category]
              ? resolveDesignerText(DESIGNER_I18N.unified.category[category], locale)
              : category}
          </div>
          {definitions.map((definition) => (
            <PaletteBlockButton
              key={definition.blockType}
              definition={definition}
              enabled={canAddBlock(definition.blockType)}
              onAddBlock={onAddBlock}
              locale={locale}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

function PaletteBlockButton({
  definition,
  enabled,
  onAddBlock,
  locale,
}: {
  definition: BlockDefinitionV3;
  enabled: boolean;
  onAddBlock: (blockType: string) => void;
  locale: string;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: paletteDraggableId(definition.blockType),
    data: { kind: 'palette-block', blockType: definition.blockType },
    disabled: !enabled,
  });
  return (
    <button
      ref={setNodeRef}
      type="button"
      data-testid={`palette-add-${definition.blockType}`}
      disabled={!enabled}
      onClick={() => onAddBlock(definition.blockType)}
      {...attributes}
      {...listeners}
      className={`flex w-full items-center justify-between rounded-md border px-2 py-2 text-left text-xs ${
        enabled
          ? 'cursor-grab touch-none border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50 active:cursor-grabbing'
          : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
      } ${isDragging ? 'opacity-50' : ''}`}
    >
      <span>
        <span className="block font-medium">{localizedToString(definition.label, locale)}</span>
        <span className="font-mono text-[10px]">{definition.blockType}</span>
      </span>
      <Plus className="h-3.5 w-3.5" aria-hidden="true" />
    </button>
  );
}

function ModelFieldItem({
  field,
  enabled,
  used,
  onAddModelField,
  locale,
}: {
  field: ModelFieldDefinition;
  enabled: boolean;
  used: boolean;
  onAddModelField: (field: ModelFieldDefinition) => void;
  locale: string;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: fieldDraggableId(field),
    data: { kind: 'model-field', field },
    disabled: !enabled,
  });
  return (
    <button
      ref={setNodeRef}
      type="button"
      data-testid={`model-field-${field.code}`}
      data-used={used ? 'true' : 'false'}
      data-virtual={field.virtual ? 'true' : 'false'}
      disabled={!enabled}
      onDoubleClick={() => {
        if (enabled) onAddModelField(field);
      }}
      onClick={() => onAddModelField(field)}
      {...attributes}
      {...listeners}
      className={`flex w-full items-center justify-between rounded-md border px-2 py-2 text-left text-xs ${
        enabled
          ? 'cursor-grab touch-none border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50 active:cursor-grabbing'
          : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
      } ${isDragging ? 'opacity-50' : ''}`}
    >
      <span className="min-w-0">
        <span className="block truncate font-medium">{localizedToString(field.label, locale)}</span>
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
          {field.virtual ? (
            <span
              data-testid={`model-field-virtual-${field.code}`}
              className="inline-flex items-center gap-0.5 rounded bg-violet-50 px-1.5 py-0.5 text-[10px] font-medium text-violet-600"
            >
              <Sparkles className="h-2.5 w-2.5" aria-hidden="true" />
              {resolveDesignerText(DESIGNER_I18N.unified.virtual, locale)}
            </span>
          ) : null}
        </span>
      </span>
      {used ? (
        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.added, locale)}
        </span>
      ) : (
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
      )}
    </button>
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
  locale,
}: {
  selectedModelCode: string | null;
  modelFields: ModelFieldDefinition[];
  canAddField: boolean;
  canAddModelField: (field: ModelFieldDefinition) => boolean;
  isModelFieldUsed: (field: ModelFieldDefinition) => boolean;
  onAddField: () => void;
  onAddModelField: (field: ModelFieldDefinition) => void;
  locale: string;
}) {
  const [searchQuery, setSearchQuery] = useState('');
  const normalizedSearchQuery = searchQuery.trim().toLowerCase();
  const filteredModelFields = normalizedSearchQuery
    ? modelFields.filter((field) => {
        const label = localizedToString(field.label, locale).toLowerCase();
        const type = field.type?.toLowerCase() ?? '';
        return (
          label.includes(normalizedSearchQuery) ||
          field.code.toLowerCase().includes(normalizedSearchQuery) ||
          type.includes(normalizedSearchQuery)
        );
      })
    : modelFields;
  const groups = useMemo(() => groupModelFields(filteredModelFields), [filteredModelFields]);
  const virtualCount = filteredModelFields.filter((field) => field.virtual).length;

  return (
    <div className="space-y-3" data-testid="field-palette">
      <div className="flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.fields, locale)}
        </div>
        <div className="text-[10px] text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.dragOrDoubleClick, locale)}
        </div>
      </div>
      {modelFields.length ? (
        <div className="space-y-2">
          <div className="truncate rounded-md border border-slate-200 bg-slate-50 px-2 py-1.5 text-xs font-medium text-slate-600">
            {resolveDesignerText(DESIGNER_I18N.unified.model, locale)}: {selectedModelCode}
          </div>
          <input
            type="search"
            data-testid="field-palette-search"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            placeholder={resolveDesignerText(DESIGNER_I18N.unified.searchFields, locale)}
            className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-xs text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100"
          />
          {groups.map((group) => (
            <div key={group.key} className="space-y-1.5">
              <div className="px-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-400">
                {resolveDesignerText(DESIGNER_I18N.unified.fieldGroup[group.key], locale)}
                <span className="ml-1 text-slate-300">({group.fields.length})</span>
              </div>
              {group.fields.map((field) => (
                <ModelFieldItem
                  key={`${field.modelCode}.${field.code}`}
                  field={field}
                  enabled={canAddModelField(field)}
                  used={isModelFieldUsed(field)}
                  onAddModelField={onAddModelField}
                  locale={locale}
                />
              ))}
            </div>
          ))}
          {!filteredModelFields.length ? (
            <div className="rounded-md border border-dashed border-slate-200 px-2 py-3 text-center text-xs text-slate-400">
              {resolveDesignerText(DESIGNER_I18N.unified.noFieldsMatch, locale)}
            </div>
          ) : (
            <div className="flex items-center justify-between border-t border-slate-100 pt-2 text-[10px] text-slate-400">
              <span>
                {resolveDesignerText(DESIGNER_I18N.unified.fieldCount, locale, {
                  count: filteredModelFields.length,
                })}
              </span>
              {virtualCount > 0 ? (
                <span className="text-violet-400">
                  {resolveDesignerText(DESIGNER_I18N.unified.virtualCount, locale, {
                    count: virtualCount,
                  })}
                </span>
              ) : null}
            </div>
          )}
        </div>
      ) : (
        <div className="rounded-md border border-dashed border-slate-200 px-2 py-3 text-center text-xs text-slate-400">
          {resolveDesignerText(DESIGNER_I18N.unified.noModelBound, locale)}
        </div>
      )}
      <button
        type="button"
        data-testid="field-palette-add-field"
        disabled={!canAddField}
        onClick={onAddField}
        className={`flex w-full items-center justify-between rounded-md border border-dashed px-2 py-2 text-xs ${
          canAddField
            ? 'border-slate-200 bg-white text-slate-600 hover:border-blue-300 hover:bg-blue-50'
            : 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300'
        }`}
      >
        <span className="font-medium">
          {resolveDesignerText(DESIGNER_I18N.unified.customField, locale)}
        </span>
        <Plus className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}

function OutlineList({
  blocks,
  selectedBlockId,
  onSelect,
  locale,
  depth = 0,
}: {
  blocks: DslBlockV3[];
  selectedBlockId: string | null;
  onSelect: (blockId: string) => void;
  locale: string;
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
            <span className="truncate">{getBlockLabel(block, locale)}</span>
            <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-400">
              {block.blockType}
            </span>
          </button>
          {block.blocks?.length ? (
            <OutlineList
              blocks={block.blocks}
              selectedBlockId={selectedBlockId}
              onSelect={onSelect}
              locale={locale}
              depth={depth + 1}
            />
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function getBlockLabel(block: DslBlockV3, locale: string): string {
  if (block.title) {
    const resolved = localizedToString(block.title, locale);
    if (resolved) return resolved;
  }
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function localizedToString(value: LocalizedText, locale: string): string {
  if (typeof value === 'string') return value;
  return value[locale] || value['en-US'] || value['zh-CN'] || Object.values(value)[0] || '';
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
