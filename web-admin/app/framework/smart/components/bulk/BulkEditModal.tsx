/**
 * BulkEditModal Component
 *
 * Modal dialog for bulk editing selected records.
 * Allows selecting a field and setting a new value for all selected records.
 */

import React, { useState, useCallback } from 'react';
import { dynamicService } from '~/services/dynamicService';
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

  const handleSubmit = useCallback(async () => {
    if (!selectedField || selectedIds.length === 0) return;

    setUpdating(true);
    setError(null);

    try {
      // Parse value based on field type
      let parsedValue: unknown = newValue;
      if (selectedFieldDef) {
        const dt = selectedFieldDef.dataType.toUpperCase();
        if (['number', 'integer', 'decimal', 'float', 'double'].includes(dt)) {
          parsedValue = Number(newValue);
          if (isNaN(parsedValue as number)) {
            setError('Invalid number value');
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
      setError(err instanceof Error ? err.message : 'Bulk update failed');
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
      <div className="fixed inset-0 z-50 bg-black/50" onClick={handleClose} />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-md rounded-xl bg-white shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-b border-gray-200 px-6 py-4">
            <h2 className="text-lg font-semibold text-gray-900">Bulk Edit</h2>
            <p className="mt-1 text-sm text-gray-500">
              Update {selectedIds.length} selected records
            </p>
          </div>

          {/* Body */}
          <div className="space-y-4 px-6 py-4">
            {/* Field selector */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                Field to update
              </label>
              <select
                value={selectedField}
                onChange={(e) => {
                  setSelectedField(e.target.value);
                  setNewValue('');
                }}
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                <option value="">Select field...</option>
                {editableFields.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name} ({f.dataType})
                  </option>
                ))}
              </select>
            </div>

            {/* Value input */}
            {selectedField && (
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">New value</label>
                {selectedFieldDef?.dataType.toLowerCase() === 'boolean' ? (
                  <select
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  >
                    <option value="">Select...</option>
                    <option value="true">True</option>
                    <option value="false">False</option>
                  </select>
                ) : (
                  <input
                    type={
                      ['number', 'integer', 'decimal', 'float', 'double'].includes(
                        (selectedFieldDef?.dataType || '').toUpperCase(),
                      )
                        ? 'number'
                        : 'text'
                    }
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    placeholder="Enter new value..."
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                )}
              </div>
            )}

            {/* Error */}
            {error && (
              <div className="rounded-md border border-red-200 bg-red-50 p-3">
                <p className="text-sm text-red-600">{error}</p>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            <button
              type="button"
              onClick={handleClose}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!selectedField || updating}
              className={cn(
                'rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white',
                'hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              {updating ? (
                <span className="flex items-center gap-2">
                  <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                  Updating...
                </span>
              ) : (
                `Update ${selectedIds.length} Records`
              )}
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default BulkEditModal;
