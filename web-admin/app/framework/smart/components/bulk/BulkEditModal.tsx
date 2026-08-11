/**
 * BulkEditModal Component
 *
 * Modal dialog for bulk editing selected records.
 * Allows selecting a field and setting a new value for all selected records.
 */

import React, { useState, useCallback } from 'react';
import { dynamicService } from '~/shared/services/dynamicService';
import { cn } from '~/utils/cn';

export interface BulkEditField {
  code: string;
  name: string;
  dataType: string;
}

export interface BulkEditModalProps {
  /** Whether the modal is open */
  open: boolean;
  /** Callback to close the modal */
  onClose: () => void;
  /** Model code for the data source */
  modelCode: string;
  /** IDs of records to edit */
  selectedIds: string[];
  /** Available fields for editing */
  fields: BulkEditField[];
  /** Active UI locale used for readable fallbacks when common keys are absent. */
  locale?: string;
  /** Shared translator. */
  t?: (key: string) => string;
  /** Callback after successful update */
  onUpdateComplete?: () => void;
}

/**
 * BulkEditModal - Modal for bulk editing records
 */
export const BulkEditModal: React.FC<BulkEditModalProps> = ({
  open,
  onClose,
  modelCode,
  selectedIds,
  fields,
  locale = 'zh-CN',
  t,
  onUpdateComplete,
}) => {
  const [selectedField, setSelectedField] = useState('');
  const [newValue, setNewValue] = useState('');
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const editableFields = fields.filter(
    (f) =>
      ![
        'id',
        'pid',
        'tenant_id',
        'created_at',
        'created_by',
        'updated_at',
        'updated_by',
        'deleted_flag',
      ].includes(f.code),
  );

  const selectedFieldDef = editableFields.find((f) => f.code === selectedField);
  const isZh = locale.toLowerCase().startsWith('zh');
  const tr = useCallback(
    (key: string, zh: string, en: string) => {
      const translated = t?.(key);
      return translated && translated !== key ? translated : isZh ? zh : en;
    },
    [isZh, t],
  );

  const handleSubmit = useCallback(async () => {
    if (!selectedField || selectedIds.length === 0) return;

    setUpdating(true);
    setError(null);

    try {
      // Parse value based on field type
      let parsedValue: unknown = newValue;
      if (selectedFieldDef) {
        const dt = selectedFieldDef.dataType.toLowerCase();
        if (['number', 'integer', 'decimal', 'float', 'double'].includes(dt)) {
          parsedValue = Number(newValue);
          if (isNaN(parsedValue as number)) {
            setError(tr('list.bulk.invalidNumber', '请输入有效数字', 'Enter a valid number'));
            setUpdating(false);
            return;
          }
        } else if (['boolean'].includes(dt)) {
          parsedValue = newValue === 'true';
        }
      }

      const updates = selectedIds.map((id) => ({
        id,
        data: { [selectedField]: parsedValue },
      }));

      await dynamicService.batchUpdate(modelCode, updates);
      onUpdateComplete?.();
      onClose();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : tr('list.bulk.updateFailed', '批量更新失败', 'Bulk update failed'),
      );
    } finally {
      setUpdating(false);
    }
  }, [
    selectedField,
    newValue,
    selectedIds,
    modelCode,
    selectedFieldDef,
    onUpdateComplete,
    onClose,
    tr,
  ]);

  const handleClose = useCallback(() => {
    setSelectedField('');
    setNewValue('');
    setError(null);
    onClose();
  }, [onClose]);

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-50 bg-black/45 backdrop-blur-[1px]" onClick={handleClose} />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="bulk-edit-title"
          data-testid="bulk-edit-dialog"
          className="rounded-card bg-panel border-border w-full max-w-lg border shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-border border-b px-6 py-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 id="bulk-edit-title" className="text-text text-lg font-semibold">
                  {tr('list.bulk.editTitle', '批量编辑', 'Bulk edit')}
                </h2>
                <p className="text-text-2 mt-1 text-sm">
                  {isZh
                    ? `将同一字段更新到已选择的 ${selectedIds.length} 条记录`
                    : `Update one field across ${selectedIds.length} selected records`}
                </p>
              </div>
              <span className="bg-accent-weak text-accent rounded-pill px-3 py-1 text-sm font-semibold">
                {selectedIds.length}
              </span>
            </div>
            <p className="text-text-3 mt-3 text-xs">
              {tr(
                'list.bulk.commandHint',
                '生命周期字段不会出现在这里；阶段和状态请使用受控批量动作。',
                'Lifecycle fields stay command-controlled and are not editable here.',
              )}
            </p>
          </div>

          {/* Body */}
          <div className="space-y-4 px-6 py-4">
            {/* Field selector */}
            <div>
              <label
                htmlFor="bulk-edit-field"
                className="text-text-2 mb-1.5 block text-sm font-medium"
              >
                {tr('list.bulk.field', '要更新的字段', 'Field to update')}
              </label>
              <select
                id="bulk-edit-field"
                data-testid="bulk-edit-field"
                value={selectedField}
                onChange={(e) => {
                  setSelectedField(e.target.value);
                  setNewValue('');
                }}
                className="rounded-control border-border-strong bg-panel text-text focus:ring-accent w-full border px-3 py-2.5 text-sm focus:ring-2 focus:outline-none"
              >
                <option value="">
                  {tr('list.bulk.selectField', '选择字段…', 'Select a field…')}
                </option>
                {editableFields.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name}
                  </option>
                ))}
              </select>
            </div>

            {/* Value input */}
            {selectedField && (
              <div>
                <label
                  htmlFor="bulk-edit-value"
                  className="text-text-2 mb-1.5 block text-sm font-medium"
                >
                  {tr('list.bulk.newValue', '新值', 'New value')}
                </label>
                {selectedFieldDef?.dataType.toLowerCase() === 'boolean' ? (
                  <select
                    id="bulk-edit-value"
                    data-testid="bulk-edit-value"
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    className="rounded-control border-border-strong bg-panel text-text focus:ring-accent w-full border px-3 py-2.5 text-sm focus:ring-2 focus:outline-none"
                  >
                    <option value="">{tr('list.bulk.selectValue', '选择…', 'Select…')}</option>
                    <option value="true">{tr('common.yes', '是', 'Yes')}</option>
                    <option value="false">{tr('common.no', '否', 'No')}</option>
                  </select>
                ) : (
                  <input
                    id="bulk-edit-value"
                    data-testid="bulk-edit-value"
                    type={
                      ['number', 'integer', 'decimal', 'float', 'double'].includes(
                        (selectedFieldDef?.dataType || '').toLowerCase(),
                      )
                        ? 'number'
                        : selectedFieldDef?.dataType.toLowerCase() === 'date'
                          ? 'date'
                          : ['datetime', 'datetime-local', 'timestamp'].includes(
                                (selectedFieldDef?.dataType || '').toLowerCase(),
                              )
                            ? 'datetime-local'
                            : 'text'
                    }
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    placeholder={tr('list.bulk.enterValue', '输入新值…', 'Enter a new value…')}
                    className="rounded-control border-border-strong bg-panel text-text focus:ring-accent w-full border px-3 py-2.5 text-sm focus:ring-2 focus:outline-none"
                  />
                )}
              </div>
            )}

            {/* Error */}
            {error && (
              <div
                className="rounded-control bg-status-red-bg border border-red-200 p-3"
                role="alert"
              >
                <p className="text-status-red text-sm">{error}</p>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="border-border bg-subtle flex justify-end gap-3 border-t px-6 py-4">
            <button
              type="button"
              onClick={handleClose}
              className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover border px-4 py-2 text-sm font-medium"
            >
              {tr('common.cancel', '取消', 'Cancel')}
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!selectedField || newValue === '' || updating}
              className={cn(
                'rounded-control bg-accent px-4 py-2 text-sm font-medium text-white',
                'hover:bg-accent-hover focus:ring-accent focus:ring-2 focus:outline-none',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              {updating ? (
                <span className="flex items-center gap-2">
                  <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                  {tr('list.bulk.updating', '正在更新…', 'Updating…')}
                </span>
              ) : isZh ? (
                `更新 ${selectedIds.length} 条记录`
              ) : (
                `Update ${selectedIds.length} records`
              )}
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default BulkEditModal;
