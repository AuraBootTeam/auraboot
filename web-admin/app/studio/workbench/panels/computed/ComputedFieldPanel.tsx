/**
 * Computed Field Panel Component
 *
 * Main panel for managing computed fields.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import { ComputedFieldList } from './ComputedFieldList';
import { ComputedFieldEditor } from './ComputedFieldEditor';
import { COMPUTED_TYPES, type ComputedFieldDefinition, type ComputedFieldType } from './types';
import { confirmDialog } from '~/utils/confirmDialog';

interface ComputedFieldPanelProps {
  /** List of computed fields */
  fields: ComputedFieldDefinition[];
  /** Available fields for expression */
  availableFields?: Array<{ path: string; label: string; type: string }>;
  /** On fields change */
  onFieldsChange?: (fields: ComputedFieldDefinition[]) => void;
  /** Whether panel is visible */
  isVisible?: boolean;
}

type ViewMode = 'list' | 'editor';

/**
 * Computed Field Panel Component
 */
export const ComputedFieldPanel: React.FC<ComputedFieldPanelProps> = ({
  fields,
  availableFields = [],
  onFieldsChange,
  isVisible = true,
}) => {
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [editingField, setEditingField] = useState<ComputedFieldDefinition | undefined>();
  const [selectedCode, setSelectedCode] = useState<string>();
  const [filter, setFilter] = useState<ComputedFieldType | 'all'>('all');

  // Filter fields
  const filteredFields = filter === 'all' ? fields : fields.filter((f) => f.virtualType === filter);

  // Handle create new
  const handleCreate = useCallback(() => {
    setEditingField(undefined);
    setViewMode('editor');
  }, []);

  // Handle edit
  const handleEdit = useCallback((field: ComputedFieldDefinition) => {
    setEditingField(field);
    setViewMode('editor');
  }, []);

  // Handle delete
  const handleDelete = useCallback(
    async (field: ComputedFieldDefinition) => {
      if (
        await confirmDialog({
          content: `确定要删除计算字段 "${field.label}" 吗？`,
          variant: 'danger',
        })
      ) {
        const newFields = fields.filter((f) => f.code !== field.code);
        onFieldsChange?.(newFields);
      }
    },
    [fields, onFieldsChange],
  );

  // Handle toggle enabled
  const handleToggleEnabled = useCallback(
    (field: ComputedFieldDefinition) => {
      const newFields = fields.map((f) =>
        f.code === field.code ? { ...f, enabled: !f.enabled } : f,
      );
      onFieldsChange?.(newFields);
    },
    [fields, onFieldsChange],
  );

  // Handle save
  const handleSave = useCallback(
    (field: ComputedFieldDefinition) => {
      let newFields: ComputedFieldDefinition[];

      if (editingField) {
        // Update existing
        newFields = fields.map((f) => (f.code === field.code ? field : f));
      } else {
        // Add new
        newFields = [...fields, field];
      }

      onFieldsChange?.(newFields);
      setViewMode('list');
      setEditingField(undefined);
    },
    [fields, editingField, onFieldsChange],
  );

  // Handle cancel
  const handleCancel = useCallback(() => {
    setViewMode('list');
    setEditingField(undefined);
  }, []);

  if (!isVisible) return null;

  // Editor view
  if (viewMode === 'editor') {
    return (
      <ComputedFieldEditor
        field={editingField}
        availableFields={availableFields}
        onSave={handleSave}
        onCancel={handleCancel}
      />
    );
  }

  // List view
  return (
    <div className="flex h-full flex-col bg-white">
      {/* Header */}
      <div className="flex-shrink-0 border-b border-gray-200 px-4 py-3">
        <div className="mb-2 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-900">计算字段</h3>
          <button
            type="button"
            onClick={handleCreate}
            className="flex items-center gap-1 rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 4v16m8-8H4"
              />
            </svg>
            新建
          </button>
        </div>

        {/* Stats */}
        <div className="flex items-center gap-2 text-xs text-gray-500">
          <span>共 {fields.length} 个字段</span>
          <span className="text-gray-300">|</span>
          <span className="text-green-600">
            {fields.filter((f) => f.enabled !== false).length} 启用
          </span>
          <span className="text-gray-300">|</span>
          <span className="text-gray-400">
            {fields.filter((f) => f.enabled === false).length} 禁用
          </span>
        </div>
      </div>

      {/* Filter */}
      <div className="flex-shrink-0 border-b border-gray-100 bg-gray-50 px-4 py-2">
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => setFilter('all')}
            className={`rounded px-2 py-1 text-xs ${
              filter === 'all' ? 'bg-gray-200 text-gray-700' : 'text-gray-500 hover:bg-gray-100'
            }`}
          >
            全部
          </button>
          {COMPUTED_TYPES.map((type) => (
            <button
              key={type.type}
              type="button"
              onClick={() => setFilter(type.type)}
              className={`rounded px-2 py-1 text-xs ${
                filter === type.type
                  ? 'bg-gray-200 text-gray-700'
                  : 'text-gray-500 hover:bg-gray-100'
              }`}
            >
              {type.icon} {type.label}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        <ComputedFieldList
          fields={filteredFields}
          selectedCode={selectedCode}
          onSelect={(field) => setSelectedCode(field.code)}
          onEdit={handleEdit}
          onDelete={handleDelete}
          onToggleEnabled={handleToggleEnabled}
        />
      </div>

      {/* Footer */}
      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-50 px-4 py-2">
        <div className="flex items-center justify-between text-xs text-gray-500">
          <span>计算字段会在后端执行，支持 SpEL/MVEL 表达式</span>
          <button
            type="button"
            className="text-blue-600 hover:underline"
            onClick={() => window.open('/docs/computed-fields', '_blank')}
          >
            查看文档
          </button>
        </div>
      </div>
    </div>
  );
};

export default ComputedFieldPanel;
