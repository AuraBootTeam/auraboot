/**
 * Form Section Preview
 *
 * Preview component for form-section and detail-section blocks.
 * Supports drag-and-drop reordering of fields within the block.
 */

import React from 'react';
import { SortableContext, rectSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  parseFieldShorthand,
  resolveLocalizedText,
  type DslBlock,
  type DslFieldRef,
} from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useI18n } from '~/contexts/I18nContext';

export interface FormSectionPreviewProps {
  block: DslBlock;
  selectedFieldIndex?: number;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}

export const FormSectionPreview: React.FC<FormSectionPreviewProps> = ({
  block,
  selectedFieldIndex,
  onFieldReorder: _onFieldReorder,
  onFieldSelect,
  readonly,
}) => {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const fields = block.fields || [];
  const title = resolveLocalizedText(block.title, locale);
  const collapsible = block.collapsible;
  const isDetailSection = block.blockType === 'detail-section';
  const columnsCount = (block.props as any)?.columns || 2;

  // Generate sortable IDs for fields
  const fieldIds = fields.slice(0, 8).map((fieldRef, index) => {
    const field = parseFieldShorthand(fieldRef);
    return `${block.id}:field:${field.field || index}`;
  });

  return (
    /* NOTE: outer wrapper must NOT stopPropagation — SortableBlock relies on
       bubbling clicks to fire its onSelect handler. Field-level chip clicks
       call e.stopPropagation themselves in SortableFieldItem to avoid double
       firing. */
    <div className="bg-white">
      {/* Section header */}
      {title && (
        <div className="flex items-center justify-between border-b border-gray-100 px-4 py-2.5">
          <div className="flex items-center gap-2">
            {collapsible && (
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
                  d="M19 9l-7 7-7-7"
                />
              </svg>
            )}
            <h4 className="text-sm font-medium text-gray-900">{title}</h4>
          </div>
          {isDetailSection && (
            <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-400">
              {l('只读', 'Read only')}
            </span>
          )}
        </div>
      )}

      {/* Fields grid with drag-and-drop */}
      <div className="p-4">
        {fields.length === 0 ? (
          <div className="rounded border border-dashed border-gray-200 py-6 text-center text-sm text-gray-400">
            {l('点击右侧面板添加字段', 'Add fields from the right panel')}
          </div>
        ) : (
          <SortableContext items={fieldIds} strategy={rectSortingStrategy}>
            <div
              className="grid gap-4"
              style={{ gridTemplateColumns: `repeat(${columnsCount}, 1fr)` }}
            >
              {fields.slice(0, 8).map((fieldRef, index) => {
                const field = parseFieldShorthand(fieldRef);
                const fieldId = `${block.id}:field:${field.field || index}`;

                return (
                  <SortableFieldItem
                    key={fieldId}
                    id={fieldId}
                    fieldName={
                      resolveLocalizedText(field.label, locale) ||
                      field.field ||
                      l(`字段${index + 1}`, `Field ${index + 1}`)
                    }
                    placeholder={resolveLocalizedText(field.placeholder, locale) || undefined}
                    required={field.required}
                    isDetailSection={isDetailSection}
                    isSelected={selectedFieldIndex === index}
                    span={field.span}
                    columnsCount={columnsCount}
                    disabled={readonly}
                    onSelect={() => onFieldSelect?.(block.id, index, fieldRef)}
                  />
                );
              })}
            </div>
          </SortableContext>
        )}
        {fields.length > 8 && (
          <div className="mt-3 text-center text-xs text-gray-400">
            {l(`+${fields.length - 8} 更多字段`, `+${fields.length - 8} more fields`)}
          </div>
        )}
      </div>
    </div>
  );
};

/**
 * Sortable field item component
 */
interface SortableFieldItemProps {
  id: string;
  fieldName: string;
  placeholder?: string;
  required?: boolean;
  isDetailSection?: boolean;
  isSelected?: boolean;
  span?: number;
  columnsCount?: number;
  disabled?: boolean;
  onSelect?: () => void;
}

const SortableFieldItem: React.FC<SortableFieldItemProps> = ({
  id,
  fieldName,
  placeholder,
  required,
  isDetailSection,
  isSelected,
  span = 1,
  columnsCount = 2,
  disabled,
  onSelect,
}) => {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled,
    data: { type: 'block-field', fieldName, sortableId: id },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: transition || 'transform 200ms ease, box-shadow 200ms ease',
    gridColumn: `span ${Math.min(span, columnsCount)}`,
  };

  // Handle click for selection (separate from drag)
  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!isDragging && onSelect) {
      onSelect();
    }
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      onClick={handleClick}
      className={`group/field relative transition-all duration-200 ${
        !disabled ? 'cursor-pointer' : ''
      } ${isSelected ? 'rounded-lg bg-blue-50/50 ring-2 ring-blue-500' : ''} ${
        isDragging
          ? 'z-50 scale-105 rounded-lg bg-white opacity-70 shadow-lg ring-2 ring-blue-400'
          : ''
      }`}
      {...attributes}
      {...listeners}
    >
      {/* Drag indicator on hover */}
      {!disabled && (
        <div
          className={`pointer-events-none absolute top-1/2 -left-5 -translate-y-1/2 rounded p-1 transition-all duration-200 ${
            isDragging
              ? 'bg-blue-100 text-blue-500 opacity-100'
              : 'text-gray-400 opacity-0 group-hover/field:opacity-100'
          }`}
        >
          <svg className="h-3 w-3" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="9" cy="7" r="1.5" />
            <circle cx="15" cy="7" r="1.5" />
            <circle cx="9" cy="12" r="1.5" />
            <circle cx="15" cy="12" r="1.5" />
            <circle cx="9" cy="17" r="1.5" />
            <circle cx="15" cy="17" r="1.5" />
          </svg>
        </div>
      )}
      {/* Hover highlight */}
      {!disabled && !isDragging && (
        <div className="pointer-events-none absolute inset-0 rounded border border-transparent transition-colors group-hover/field:border-blue-200" />
      )}

      <label className="mb-1 block text-xs text-gray-500">
        {fieldName}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {isDetailSection ? (
        <div className="text-sm text-gray-900">--</div>
      ) : (
        <div
          className={`flex h-8 items-center rounded border bg-gray-50 px-2 text-sm text-gray-400 ${
            isDragging ? 'border-blue-300 bg-blue-50' : 'border-gray-200'
          }`}
        >
          {placeholder || l('请输入...', 'Enter...')}
        </div>
      )}
    </div>
  );
};

export default FormSectionPreview;
