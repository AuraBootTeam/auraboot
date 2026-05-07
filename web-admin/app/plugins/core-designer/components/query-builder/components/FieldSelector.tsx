/**
 * FieldSelector — Chip-toggle field picker. Hover shows data type.
 */

import { useState, useEffect, useCallback } from 'react';
import { queryBuilderService, type FieldInfo } from '../services/queryBuilderService';
import { ResultHelper } from '~/utils/type';

interface FieldSelectorProps {
  modelCode: string;
  selectedFields: string[];
  onChange: (fields: string[]) => void;
  onFieldsLoaded?: (fields: FieldInfo[]) => void;
}

const COLLAPSE_AT = 12;

export const FieldSelector: React.FC<FieldSelectorProps> = ({
  modelCode,
  selectedFields,
  onChange,
  onFieldsLoaded,
}) => {
  const [fields, setFields] = useState<FieldInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState(false);

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
      /* ignore */
    } finally {
      setLoading(false);
    }
  }, [modelCode, onFieldsLoaded]);

  useEffect(() => {
    loadFields();
  }, [loadFields]);

  const toggle = (code: string) => {
    onChange(
      selectedFields.includes(code) ? selectedFields.filter((f) => f !== code) : [...selectedFields, code],
    );
  };

  const toggleAll = () => {
    onChange(selectedFields.length === fields.length ? [] : fields.map((f) => f.code));
  };

  const visible = expanded || fields.length <= COLLAPSE_AT ? fields : fields.slice(0, COLLAPSE_AT);
  const hiddenCount = fields.length - visible.length;

  return (
    <section data-testid="qb-step-fields" className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-blue-50 text-xs font-semibold text-blue-700 ring-1 ring-blue-200">
            1
          </span>
          <h3 className="text-sm font-semibold text-slate-700">Fields</h3>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500">
            {selectedFields.length} / {fields.length} selected
          </span>
          {fields.length > 0 && (
            <button type="button" onClick={toggleAll} className="text-xs font-medium text-blue-600 hover:text-blue-700">
              {selectedFields.length === fields.length ? 'Clear' : 'Select all'}
            </button>
          )}
        </div>
      </header>
      {loading && <div className="text-xs text-slate-400">Loading fields…</div>}
      {!loading && fields.length === 0 && <div className="text-xs text-slate-400">No fields available</div>}
      {!loading && fields.length > 0 && (
        <>
          <div className="flex flex-wrap gap-2">
            {visible.map((f) => {
              const active = selectedFields.includes(f.code);
              return (
                <button
                  key={f.code}
                  type="button"
                  onClick={() => toggle(f.code)}
                  data-testid={`qb-field-${f.code}`}
                  title={`${f.code} · ${f.dataType}`}
                  className={`group inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                    active
                      ? 'border-blue-500 bg-blue-50 text-blue-700'
                      : 'border-slate-200 bg-white text-slate-700 hover:border-blue-400'
                  }`}
                >
                  <span>{f.name || f.code}</span>
                  <span
                    className={`text-[10px] uppercase tracking-wide ${
                      active ? 'text-blue-400' : 'text-slate-400'
                    }`}
                  >
                    {f.dataType}
                  </span>
                </button>
              );
            })}
          </div>
          {hiddenCount > 0 && (
            <button
              type="button"
              onClick={() => setExpanded(true)}
              className="mt-3 text-xs font-medium text-blue-600 hover:text-blue-700"
            >
              + Show {hiddenCount} more fields
            </button>
          )}
        </>
      )}
    </section>
  );
};
