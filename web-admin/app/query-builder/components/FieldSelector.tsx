/**
 * FieldSelector — Select fields from a model to include in query
 */

import { useState, useEffect, useCallback } from 'react';
import { queryBuilderService, type FieldInfo } from '../services/queryBuilderService';
import { ResultHelper } from '~/utils/type';

interface FieldSelectorProps {
  modelCode: string;
  selectedFields: string[];
  onChange: (fields: string[]) => void;
  /** Expose loaded fields to parent */
  onFieldsLoaded?: (fields: FieldInfo[]) => void;
}

export const FieldSelector: React.FC<FieldSelectorProps> = ({
  modelCode,
  selectedFields,
  onChange,
  onFieldsLoaded,
}) => {
  const [fields, setFields] = useState<FieldInfo[]>([]);
  const [loading, setLoading] = useState(false);

  const loadFields = useCallback(async () => {
    if (!modelCode) return;
    setLoading(true);
    try {
      const resp = await queryBuilderService.getFields(modelCode);
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setFields(resp.data);
        onFieldsLoaded?.(resp.data);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [modelCode, onFieldsLoaded]);

  useEffect(() => {
    loadFields();
  }, [loadFields]);

  const toggleField = (code: string) => {
    if (selectedFields.includes(code)) {
      onChange(selectedFields.filter((f) => f !== code));
    } else {
      onChange([...selectedFields, code]);
    }
  };

  const toggleAll = () => {
    if (selectedFields.length === fields.length) {
      onChange([]);
    } else {
      onChange(fields.map((f) => f.code));
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-gray-700">Fields</h3>
        <button
          type="button"
          onClick={toggleAll}
          className="text-xs text-blue-600 hover:text-blue-800"
        >
          {selectedFields.length === fields.length ? 'Deselect All' : 'Select All'}
        </button>
      </div>
      <div className="max-h-48 overflow-y-auto rounded-md border border-gray-200">
        {loading && <div className="px-3 py-2 text-sm text-gray-500">Loading...</div>}
        {fields.map((f) => (
          <label
            key={f.code}
            className="flex cursor-pointer items-center gap-2 px-3 py-1.5 hover:bg-gray-50"
          >
            <input
              type="checkbox"
              checked={selectedFields.includes(f.code)}
              onChange={() => toggleField(f.code)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              data-testid={`qb-field-${f.code}`}
            />
            <span className="text-sm text-gray-700">{f.name || f.code}</span>
            <span className="ml-auto text-xs text-gray-400">{f.dataType}</span>
          </label>
        ))}
      </div>
    </div>
  );
};
