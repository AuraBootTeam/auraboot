// web-admin/app/smart/components/formula/FormulaEditor.tsx
/**
 * FormulaEditor Component
 *
 * A formula expression editor with function picker and preview.
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { cn } from '~/utils/cn';
import { useSmartText } from '~/utils/i18n';

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

export interface FormulaField {
  code: string;
  name: string;
  group?: string;
  /** Optional literal inserted into the editor instead of the display code. */
  insertion?: string;
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
  fields?: FormulaField[];
  /** Fetch functions from API */
  fetchFunctions?: () => Promise<FormulaFunction[]>;
  /** Preview expression result */
  previewExpression?: (expression: string, context: Record<string, unknown>) => Promise<unknown>;
  /** Preview context (field values) */
  previewContext?: Record<string, unknown>;
  /** Placeholder text */
  placeholder?: string;
  /** Accessible label for the expression textarea. */
  ariaLabel?: string;
  /** Whether editor is disabled */
  disabled?: boolean;
  /** Error message */
  error?: string;
  /** Show the generic syntax help under the editor. */
  showHelp?: boolean;
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
  placeholder,
  ariaLabel,
  disabled = false,
  error,
  showHelp = true,
  className,
}) => {
  const st = useSmartText();
  const [functions, setFunctions] = useState<FormulaFunction[]>([]);
  const [showFunctionPicker, setShowFunctionPicker] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [previewResult, setPreviewResult] = useState<unknown>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [showFieldPicker, setShowFieldPicker] = useState(false);

  // $ variable autocomplete state
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [acQuery, setAcQuery] = useState<string | null>(null); // null = hidden
  const [acIndex, setAcIndex] = useState(0);

  // Filter fields matching the $ prefix query
  const acItems = acQuery !== null
    ? fields.filter((f) => f.code.startsWith('$') && f.code.toLowerCase().includes(acQuery.toLowerCase())).slice(0, 8)
    : [];

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

  const hasScenarioFields = useMemo(
    () => fields.some((field) => !isRuntimeContextField(field)),
    [fields],
  );

  const quickFields = useMemo(() => {
    const candidates = hasScenarioFields
      ? fields.filter((field) => !isRuntimeContextField(field))
      : fields.filter(isRuntimeContextField);
    return candidates.slice(0, 6);
  }, [fields, hasScenarioFields]);

  const groupedFields = useMemo(() => {
    const groups = new Map<string, typeof fields>();
    for (const field of fields) {
      const group = field.group || st('$i18n:formula.fieldPicker.fields', '字段');
      const existing = groups.get(group) || [];
      existing.push(field);
      groups.set(group, existing);
    }
    const contextGroups = ['$record', '$task', '$process', '$sla', '$event', '$user', '$page', '$state', '$form'];
    return Array.from(groups.entries()).sort(([a], [b]) => {
      const aContext = contextGroups.indexOf(a);
      const bContext = contextGroups.indexOf(b);
      if (aContext >= 0 && bContext >= 0) return aContext - bContext;
      if (aContext >= 0) return 1;
      if (bContext >= 0) return -1;
      return a.localeCompare(b, 'zh-CN');
    });
  }, [fields, st]);

  // Insert function at cursor
  const insertFunction = (func: FormulaFunction) => {
    const insertion = `#${func.name}()`;
    onChange?.((value || '') + insertion);
    setShowFunctionPicker(false);
  };

  // Insert field reference
  const insertField = (field: FormulaField | string) => {
    const fieldCode = typeof field === 'string' ? field : field.code;
    const insertion = typeof field === 'string'
      ? (fieldCode.startsWith('$') ? fieldCode : `#${fieldCode}`)
      : field.insertion ?? (fieldCode.startsWith('$') ? fieldCode : `#${fieldCode}`);
    onChange?.((value || '') + insertion);
    setShowFieldPicker(false);
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
      setPreviewError(
        err instanceof Error
          ? err.message
          : st('$i18n:formula.previewFailed', '预览失败'),
      );
      setPreviewResult(null);
    } finally {
      setLoading(false);
    }
  }, [previewExpression, value, previewContext, st]);

  // Handle textarea input for $ autocomplete trigger
  const handleInput = useCallback((newValue: string) => {
    onChange?.(newValue);
    const textarea = textareaRef.current;
    if (!textarea) return;
    const cursorPos = textarea.selectionStart;
    const textBefore = newValue.slice(0, cursorPos);
    // Find the $ token being typed (e.g., "$user.ro" → query = "$user.ro")
    const match = textBefore.match(/(\$[\w.]*)$/);
    if (match) {
      setAcQuery(match[1]);
      setAcIndex(0);
    } else {
      setAcQuery(null);
    }
  }, [onChange]);

  // Handle keyboard navigation in autocomplete
  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (acQuery === null || acItems.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setAcIndex((prev) => (prev + 1) % acItems.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setAcIndex((prev) => (prev - 1 + acItems.length) % acItems.length);
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      const selected = acItems[acIndex];
      if (selected) {
        const textarea = textareaRef.current;
        if (!textarea) return;
        const cursorPos = textarea.selectionStart;
        const textBefore = (value || '').slice(0, cursorPos);
        const textAfter = (value || '').slice(cursorPos);
        const match = textBefore.match(/(\$[\w.]*)$/);
        if (match) {
          const prefix = textBefore.slice(0, textBefore.length - match[1].length);
          onChange?.(prefix + selected.code + textAfter);
        }
      }
      setAcQuery(null);
    } else if (e.key === 'Escape') {
      setAcQuery(null);
    }
  }, [acQuery, acItems, acIndex, value, onChange]);

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
          fx {st('$i18n:formula.functions', '函数')}
        </button>

        {fields.length > 0 && (
          <button
            type="button"
            className={cn(
              'rounded border px-2 py-1 text-xs hover:bg-gray-50',
              showFieldPicker && 'border-blue-300 bg-blue-50 text-blue-700',
            )}
            onClick={() => setShowFieldPicker((open) => !open)}
            disabled={disabled}
          >
            {showFieldPicker
              ? st('$i18n:formula.fieldPicker.close', '收起字段')
              : st('$i18n:formula.insertField', '插入字段')}
          </button>
        )}

        {previewExpression && (
          <button
            type="button"
            className="rounded border bg-blue-50 px-2 py-1 text-xs text-blue-600 hover:bg-blue-100"
            onClick={handlePreview}
            disabled={disabled || loading}
          >
            {loading
              ? st('$i18n:formula.previewing', '预览中...')
              : st('$i18n:formula.preview', '预览')}
          </button>
        )}
      </div>

      {showFieldPicker && fields.length > 0 && (
        <div
          className="max-h-72 overflow-y-auto rounded-md border border-gray-200 bg-white p-2 shadow-sm"
          data-testid="formula-field-picker"
        >
          <div className="mb-2 flex items-center justify-between">
            <div className="text-xs font-medium text-gray-600">
              {st('$i18n:formula.fieldPicker.title', '可用字段')}
            </div>
            <button
              type="button"
              className="rounded px-2 py-0.5 text-xs text-gray-500 hover:bg-gray-100"
              onClick={() => setShowFieldPicker(false)}
            >
              {st('$i18n:formula.fieldPicker.close', '收起字段')}
            </button>
          </div>

          {quickFields.length > 0 && (
            <div className="mb-3">
              <div className="mb-1 text-[11px] font-medium text-gray-500">
                {hasScenarioFields
                  ? st('$i18n:formula.fieldPicker.quickFields', '常用字段')
                  : st('$i18n:formula.fieldPicker.quick', '常用上下文')}
              </div>
              <div className="flex flex-wrap gap-1">
                {quickFields.map((field) => (
                  <button
                    key={`quick-${field.code}`}
                    type="button"
                    className="rounded-full border border-blue-100 bg-blue-50 px-2 py-1 text-[11px] text-blue-700 hover:bg-blue-100"
                    onClick={() => insertField(field)}
                  >
                    <span>{field.name}</span>
                    <span className="ml-1 font-mono text-blue-500">{field.code}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {groupedFields.length === 0 && quickFields.length === 0 ? (
            <div className="rounded bg-gray-50 px-3 py-2 text-xs text-gray-500">
              {st('$i18n:formula.fieldPicker.empty', '暂无可插入字段')}
            </div>
          ) : groupedFields.length > 0 ? (
            <div className="space-y-3">
              {groupedFields.map(([group, groupFields]) => (
                <div key={group}>
                  <div className="mb-1 text-[11px] font-semibold text-gray-500">{group}</div>
                  <div className="grid gap-1">
                    {groupFields.map((field) => (
                      <button
                        key={field.code}
                        type="button"
                        className="flex min-w-0 items-center justify-between gap-2 rounded px-2 py-1.5 text-left text-xs hover:bg-gray-50"
                        onClick={() => insertField(field)}
                      >
                        <span className="truncate text-gray-700">{field.name}</span>
                        <span className="shrink-0 font-mono text-gray-400">{field.code}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      )}

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
              {st('$i18n:formula.category.all', '全部')}
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

      {/* Expression input with $ autocomplete */}
      <div className="relative">
        <textarea
          ref={textareaRef}
          className={cn(
            'w-full rounded-md border px-3 py-2 font-mono text-sm',
            'focus:ring-2 focus:ring-blue-500 focus:outline-none',
            disabled && 'cursor-not-allowed bg-gray-100',
            error && 'border-red-500',
          )}
          value={value}
          onChange={(e) => handleInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={() => setTimeout(() => setAcQuery(null), 150)}
          placeholder={placeholder || st('$i18n:formula.placeholder', '请输入公式表达式...')}
          aria-label={ariaLabel}
          disabled={disabled}
          rows={3}
          data-testid="formula-editor-textarea"
        />

        {/* $ variable autocomplete dropdown */}
        {acQuery !== null && acItems.length > 0 && (
          <div
            className="absolute left-0 z-50 mt-1 max-h-48 w-72 overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg"
            data-testid="formula-autocomplete"
          >
            {acItems.map((item, i) => (
              <button
                key={item.code}
                type="button"
                className={cn(
                  'flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs',
                  i === acIndex ? 'bg-purple-50 text-purple-700' : 'text-gray-700 hover:bg-gray-50',
                )}
                onMouseDown={(e) => {
                  e.preventDefault();
                  const textarea = textareaRef.current;
                  if (!textarea) return;
                  const cursorPos = textarea.selectionStart;
                  const textBefore = (value || '').slice(0, cursorPos);
                  const textAfter = (value || '').slice(cursorPos);
                  const match = textBefore.match(/(\$[\w.]*)$/);
                  if (match) {
                    const prefix = textBefore.slice(0, textBefore.length - match[1].length);
                    onChange?.(prefix + item.code + textAfter);
                  }
                  setAcQuery(null);
                }}
                data-testid={`ac-item-${item.code}`}
              >
                <span className="font-mono font-medium">{item.code}</span>
                <span className="text-gray-400">{item.name}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Error */}
      {error && <p className="text-sm text-red-500">{error}</p>}

      {/* Preview result */}
      {previewResult !== null && (
        <div className="rounded border border-green-200 bg-green-50 p-2 text-sm">
          <span className="font-medium text-green-700">
            {st('$i18n:formula.result', '结果')}:{' '}
          </span>
          <span className="font-mono">{JSON.stringify(previewResult)}</span>
        </div>
      )}

      {previewError && (
        <div className="rounded border border-red-200 bg-red-50 p-2 text-sm text-red-600">
          {previewError}
        </div>
      )}

      {/* Help text */}
      {showHelp && (
        <p className="text-xs text-gray-400">
          {st(
            '$i18n:formula.help',
            "使用 #FUNCTION() 调用函数，使用 #fieldCode 引用字段。例如：#IF(#amount > 100, '高', '低')",
          )}
        </p>
      )}
    </div>
  );
};

function isRuntimeContextField(field: FormulaField): boolean {
  return field.code.startsWith('$');
}

export default FormulaEditor;
