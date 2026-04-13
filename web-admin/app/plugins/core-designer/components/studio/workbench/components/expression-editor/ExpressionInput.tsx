/**
 * Expression Input Component
 *
 * A simplified single-line expression input with expand button.
 * For quick expression entry with optional modal editor.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { expressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import type { ExpressionInputProps, ExpressionValidationResult } from './types';

/**
 * Expression Input Component
 */
export const ExpressionInput: React.FC<ExpressionInputProps> = ({
  value,
  onChange,
  context,
  placeholder = '{{ expression }}',
  disabled = false,
  className = '',
  expandable = true,
  onExpand,
}) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [isFocused, setIsFocused] = useState(false);
  const [validation, setValidation] = useState<ExpressionValidationResult>({ valid: true });

  // Validate on value change
  useEffect(() => {
    if (value) {
      const result = expressionParser.validate(value);
      setValidation({
        valid: result.valid,
        errors: result.error ? [{ message: result.error }] : undefined,
      });
    } else {
      setValidation({ valid: true });
    }
  }, [value]);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange(e.target.value);
    },
    [onChange],
  );

  const handleFocus = useCallback(() => {
    setIsFocused(true);
  }, []);

  const handleBlur = useCallback(() => {
    setIsFocused(false);
  }, []);

  const handleExpandClick = useCallback(() => {
    onExpand?.();
  }, [onExpand]);

  // Insert wrapper on first focus if empty
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === '{' && !value && inputRef.current) {
        e.preventDefault();
        onChange('{{ }}');
        // Position cursor between {{ and }}
        setTimeout(() => {
          if (inputRef.current) {
            inputRef.current.setSelectionRange(3, 3);
          }
        }, 0);
      }
    },
    [value, onChange],
  );

  return (
    <div
      className={`expression-input relative flex items-center rounded-md border transition-colors ${disabled ? 'bg-gray-100 opacity-60' : 'bg-white'} ${isFocused ? 'border-blue-400 ring-1 ring-blue-100' : 'border-gray-200'} ${!validation.valid ? 'border-red-300' : ''} ${className} `}
    >
      {/* fx indicator */}
      <div className="flex-shrink-0 rounded-l-md border-r border-gray-200 bg-blue-50 px-2 py-1.5 font-mono text-[10px] text-blue-500">
        fx
      </div>

      {/* Input field */}
      <input
        ref={inputRef}
        type="text"
        value={value}
        onChange={handleChange}
        onFocus={handleFocus}
        onBlur={handleBlur}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        className={`flex-1 border-0 bg-transparent px-2 py-1.5 font-mono text-xs placeholder-gray-400 outline-none ${disabled ? 'cursor-not-allowed' : ''} `}
      />

      {/* Validation error indicator */}
      {!validation.valid && validation.errors && (
        <div className="flex-shrink-0 px-1 text-red-500" title={validation.errors[0]?.message}>
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
              clipRule="evenodd"
            />
          </svg>
        </div>
      )}

      {/* Expand button */}
      {expandable && (
        <button
          type="button"
          onClick={handleExpandClick}
          disabled={disabled}
          className={`flex-shrink-0 border-l border-gray-200 px-2 py-1.5 text-gray-400 transition-colors hover:text-gray-600 ${disabled ? 'cursor-not-allowed' : 'hover:bg-gray-50'} `}
          title="展开编辑器"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"
            />
          </svg>
        </button>
      )}
    </div>
  );
};

export default ExpressionInput;
