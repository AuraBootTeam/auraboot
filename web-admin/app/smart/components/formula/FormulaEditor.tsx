// web-admin/app/smart/components/formula/FormulaEditor.tsx
/**
 * FormulaEditor Component
 *
 * A formula expression editor with function picker and preview.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { cn } from '~/utils/cn';

/**
 * Formula function info
 */
export interface FormulaFunction {
  name: string;
  description: string;
  category: string;
  example: string;
  parameterTypes: string[];
}

/**
 * Props for FormulaEditor component
 */
export interface FormulaEditorProps {
  /** Current expression */
  value?: string;
  /** On change callback */
  onChange?: (value: string) => void;
  /** Available fields for autocomplete */
  fields?: { code: string; name: string }[];
  /** Fetch functions from API */
  fetchFunctions?: () => Promise<FormulaFunction[]>;
  /** Preview expression result */
  previewExpression?: (expression: string, context: Record<string, unknown>) => Promise<unknown>;
  /** Preview context (field values) */
  previewContext?: Record<string, unknown>;
  /** Placeholder text */
  placeholder?: string;
  /** Whether editor is disabled */
  disabled?: boolean;
  /** Error message */
  error?: string;
  /** Custom class name */
  className?: string;
}

/**
 * FormulaEditor - Expression editor with function picker
 */
export const FormulaEditor: React.FC<FormulaEditorProps> = ({
  value = '',
  onChange,
  fields = [],
  fetchFunctions,
  previewExpression,
  previewContext = {},
  placeholder = 'Enter formula expression...',
  disabled = false,
  error,
  className,
}) => {
  const [functions, setFunctions] = useState<FormulaFunction[]>([]);
  const [showFunctionPicker, setShowFunctionPicker] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [previewResult, setPreviewResult] = useState<unknown>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Load functions
  useEffect(() => {
    if (fetchFunctions) {
      fetchFunctions().then(setFunctions).catch(console.error);
    }
  }, [fetchFunctions]);

  // Get unique categories
  const categories = [...new Set(functions.map((f) => f.category))];

  // Filter functions by category
  const filteredFunctions = selectedCategory
    ? functions.filter((f) => f.category === selectedCategory)
    : functions;

  // Insert function at cursor
  const insertFunction = (func: FormulaFunction) => {
    const insertion = `#${func.name}()`;
    onChange?.((value || '') + insertion);
    setShowFunctionPicker(false);
  };

  // Insert field reference
  const insertField = (fieldCode: string) => {
    const insertion = `#${fieldCode}`;
    onChange?.((value || '') + insertion);
  };

  // Preview the expression
  const handlePreview = useCallback(async () => {
    if (!previewExpression || !value) return;

    setLoading(true);
    setPreviewError(null);

    try {
      const result = await previewExpression(value, previewContext);
      setPreviewResult(result);
    } catch (err) {
      setPreviewError(err instanceof Error ? err.message : 'Preview failed');
      setPreviewResult(null);
    } finally {
      setLoading(false);
    }
  }, [previewExpression, value, previewContext]);

  return (
    <div className={cn('space-y-2', className)}>
      {/* Editor toolbar */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="rounded border px-2 py-1 text-xs hover:bg-gray-50"
          onClick={() => setShowFunctionPicker(!showFunctionPicker)}
          disabled={disabled}
        >
          fx Functions
        </button>

        {fields.length > 0 && (
          <select
            className="rounded border px-2 py-1 text-xs"
            onChange={(e) => e.target.value && insertField(e.target.value)}
            disabled={disabled}
            value=""
          >
            <option value="">Insert field...</option>
            {fields.map((f) => (
              <option key={f.code} value={f.code}>
                {f.name} ({f.code})
              </option>
            ))}
          </select>
        )}

        {previewExpression && (
          <button
            type="button"
            className="rounded border bg-blue-50 px-2 py-1 text-xs text-blue-600 hover:bg-blue-100"
            onClick={handlePreview}
            disabled={disabled || loading}
          >
            {loading ? 'Previewing...' : 'Preview'}
          </button>
        )}
      </div>

      {/* Function picker */}
      {showFunctionPicker && (
        <div className="max-h-60 overflow-y-auto rounded-md border bg-gray-50 p-2">
          {/* Category tabs */}
          <div className="mb-2 flex flex-wrap gap-1">
            <button
              className={cn(
                'rounded px-2 py-1 text-xs',
                !selectedCategory ? 'bg-blue-500 text-white' : 'border bg-white',
              )}
              onClick={() => setSelectedCategory(null)}
            >
              All
            </button>
            {categories.map((cat) => (
              <button
                key={cat}
                className={cn(
                  'rounded px-2 py-1 text-xs capitalize',
                  selectedCategory === cat ? 'bg-blue-500 text-white' : 'border bg-white',
                )}
                onClick={() => setSelectedCategory(cat)}
              >
                {cat}
              </button>
            ))}
          </div>

          {/* Function list */}
          <div className="space-y-1">
            {filteredFunctions.map((func) => (
              <button
                key={func.name}
                className="w-full rounded p-2 text-left text-xs hover:bg-white"
                onClick={() => insertFunction(func)}
              >
                <div className="font-mono font-medium">#{func.name}()</div>
                <div className="text-gray-500">{func.description}</div>
                {func.example && <div className="font-mono text-gray-400">{func.example}</div>}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Expression input */}
      <textarea
        className={cn(
          'w-full rounded-md border px-3 py-2 font-mono text-sm',
          'focus:ring-2 focus:ring-blue-500 focus:outline-none',
          disabled && 'cursor-not-allowed bg-gray-100',
          error && 'border-red-500',
        )}
        value={value}
        onChange={(e) => onChange?.(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        rows={3}
      />

      {/* Error */}
      {error && <p className="text-sm text-red-500">{error}</p>}

      {/* Preview result */}
      {previewResult !== null && (
        <div className="rounded border border-green-200 bg-green-50 p-2 text-sm">
          <span className="font-medium text-green-700">Result: </span>
          <span className="font-mono">{JSON.stringify(previewResult)}</span>
        </div>
      )}

      {previewError && (
        <div className="rounded border border-red-200 bg-red-50 p-2 text-sm text-red-600">
          {previewError}
        </div>
      )}

      {/* Help text */}
      <p className="text-xs text-gray-400">
        Use #FUNCTION() for functions and #fieldCode for field references. Example: #IF(#amount{' '}
        {'>'} 100, 'High', 'Low')
      </p>
    </div>
  );
};

export default FormulaEditor;
