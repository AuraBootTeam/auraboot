/**
 * JsonEditor Component
 *
 * JSON editor with syntax highlighting and validation.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import type { BaseEditorProps, JsonValidationResult, JsonSchema } from './types';

interface JsonEditorProps extends BaseEditorProps<unknown> {
  /** JSON schema for validation */
  schema?: JsonSchema;
  /** Height in pixels */
  height?: number;
  /** Read only mode */
  readOnly?: boolean;
  /** Show line numbers */
  showLineNumbers?: boolean;
  /** Format on blur */
  formatOnBlur?: boolean;
  /** Tab size */
  tabSize?: number;
}

/**
 * Validate JSON against schema (simplified)
 */
function validateJson(value: unknown, schema?: JsonSchema): JsonValidationResult {
  if (!schema) {
    return { valid: true };
  }

  // Type validation
  if (schema.type) {
    const actualType = Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value;
    if (schema.type !== actualType) {
      return {
        valid: false,
        error: `期望类型 "${schema.type}"，实际类型 "${actualType}"`,
      };
    }
  }

  // Object validation
  if (schema.type === 'object' && typeof value === 'object' && value !== null) {
    const obj = value as Record<string, unknown>;

    // Required properties
    if (schema.required) {
      for (const prop of schema.required) {
        if (!(prop in obj)) {
          return {
            valid: false,
            error: `缺少必需属性 "${prop}"`,
          };
        }
      }
    }

    // Property validation
    if (schema.properties) {
      for (const [key, propSchema] of Object.entries(schema.properties)) {
        if (key in obj) {
          const result = validateJson(obj[key], propSchema);
          if (!result.valid) {
            return {
              valid: false,
              error: `属性 "${key}": ${result.error}`,
            };
          }
        }
      }
    }
  }

  // Array validation
  if (schema.type === 'array' && Array.isArray(value)) {
    if (schema.items) {
      for (let i = 0; i < value.length; i++) {
        const result = validateJson(value[i], schema.items);
        if (!result.valid) {
          return {
            valid: false,
            error: `索引 ${i}: ${result.error}`,
          };
        }
      }
    }
  }

  // Enum validation
  if (schema.enum && !schema.enum.includes(value)) {
    return {
      valid: false,
      error: `值必须是以下之一: ${schema.enum.map((v) => JSON.stringify(v)).join(', ')}`,
    };
  }

  return { valid: true };
}

/**
 * Syntax highlight JSON
 */
function highlightJson(json: string): React.ReactNode[] {
  const tokens: React.ReactNode[] = [];
  let i = 0;
  let key = 0;

  const addToken = (text: string, className: string) => {
    tokens.push(
      <span key={key++} className={className}>
        {text}
      </span>,
    );
  };

  while (i < json.length) {
    // Skip whitespace
    if (/\s/.test(json[i])) {
      let whitespace = '';
      while (i < json.length && /\s/.test(json[i])) {
        whitespace += json[i++];
      }
      tokens.push(<span key={key++}>{whitespace}</span>);
      continue;
    }

    // String
    if (json[i] === '"') {
      let str = '"';
      i++;
      while (i < json.length && json[i] !== '"') {
        if (json[i] === '\\' && i + 1 < json.length) {
          str += json[i++];
        }
        str += json[i++];
      }
      str += '"';
      i++;

      // Check if it's a key (followed by :)
      let j = i;
      while (j < json.length && /\s/.test(json[j])) j++;
      if (json[j] === ':') {
        addToken(str, 'text-blue-600');
      } else {
        addToken(str, 'text-green-600');
      }
      continue;
    }

    // Number
    if (/[-\d]/.test(json[i])) {
      let num = '';
      while (i < json.length && /[-\d.eE+]/.test(json[i])) {
        num += json[i++];
      }
      addToken(num, 'text-orange-600');
      continue;
    }

    // Boolean / null
    if (json.slice(i, i + 4) === 'true') {
      addToken('true', 'text-purple-600');
      i += 4;
      continue;
    }
    if (json.slice(i, i + 5) === 'false') {
      addToken('false', 'text-purple-600');
      i += 5;
      continue;
    }
    if (json.slice(i, i + 4) === 'null') {
      addToken('null', 'text-gray-500');
      i += 4;
      continue;
    }

    // Punctuation
    if (/[{}\[\]:,]/.test(json[i])) {
      addToken(json[i], 'text-gray-600');
      i++;
      continue;
    }

    // Unknown
    tokens.push(<span key={key++}>{json[i++]}</span>);
  }

  return tokens;
}

/**
 * JsonEditor Component
 */
export const JsonEditor: React.FC<JsonEditorProps> = ({
  value,
  onChange,
  disabled = false,
  label,
  error,
  className = '',
  schema,
  height = 200,
  readOnly = false,
  showLineNumbers = true,
  formatOnBlur = true,
  tabSize = 2,
}) => {
  const [text, setText] = useState(() => {
    try {
      return JSON.stringify(value, null, tabSize);
    } catch {
      return '';
    }
  });
  const [parseError, setParseError] = useState<string | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [isFocused, setIsFocused] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Update text when value changes externally
  useEffect(() => {
    if (!isFocused) {
      try {
        setText(JSON.stringify(value, null, tabSize));
      } catch {
        setText('');
      }
    }
  }, [value, tabSize, isFocused]);

  // Line numbers
  const lineNumbers = useMemo(() => {
    const lines = text.split('\n').length;
    return Array.from({ length: lines }, (_, i) => i + 1);
  }, [text]);

  // Highlighted content
  const highlightedContent = useMemo(() => {
    if (isFocused) return null;
    return highlightJson(text);
  }, [text, isFocused]);

  // Handle text change
  const handleTextChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const newText = e.target.value;
      setText(newText);

      // Parse and validate
      try {
        const parsed = JSON.parse(newText);
        setParseError(null);

        // Schema validation
        const schemaResult = validateJson(parsed, schema);
        if (!schemaResult.valid) {
          setSchemaError(schemaResult.error || '');
        } else {
          setSchemaError(null);
          onChange(parsed);
        }
      } catch (err) {
        if (err instanceof SyntaxError) {
          setParseError(err.message);
        }
      }
    },
    [onChange, schema],
  );

  // Handle blur
  const handleBlur = useCallback(() => {
    setIsFocused(false);
    if (formatOnBlur && !parseError) {
      try {
        const parsed = JSON.parse(text);
        setText(JSON.stringify(parsed, null, tabSize));
      } catch {
        // Keep as is
      }
    }
  }, [formatOnBlur, parseError, text, tabSize]);

  // Handle focus
  const handleFocus = useCallback(() => {
    setIsFocused(true);
  }, []);

  // Handle tab key
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Tab') {
        e.preventDefault();
        const target = e.currentTarget;
        const start = target.selectionStart;
        const end = target.selectionEnd;

        const spaces = ' '.repeat(tabSize);
        const newText = text.slice(0, start) + spaces + text.slice(end);
        setText(newText);

        // Set cursor position
        setTimeout(() => {
          target.selectionStart = target.selectionEnd = start + tabSize;
        }, 0);
      }
    },
    [text, tabSize],
  );

  // Format button handler
  const handleFormat = useCallback(() => {
    try {
      const parsed = JSON.parse(text);
      setText(JSON.stringify(parsed, null, tabSize));
      setParseError(null);
    } catch (err) {
      if (err instanceof SyntaxError) {
        setParseError(err.message);
      }
    }
  }, [text, tabSize]);

  // Minify button handler
  const handleMinify = useCallback(() => {
    try {
      const parsed = JSON.parse(text);
      setText(JSON.stringify(parsed));
      setParseError(null);
    } catch (err) {
      if (err instanceof SyntaxError) {
        setParseError(err.message);
      }
    }
  }, [text]);

  const displayError = error || parseError || schemaError;

  return (
    <div className={`${className}`}>
      {label && (
        <div className="mb-1 flex items-center justify-between">
          <label className="text-sm font-medium text-gray-700">{label}</label>
          <div className="flex gap-1">
            <button
              type="button"
              onClick={handleFormat}
              disabled={disabled || readOnly}
              className="text-xs text-blue-600 hover:text-blue-700 disabled:text-gray-400"
            >
              格式化
            </button>
            <span className="text-gray-300">|</span>
            <button
              type="button"
              onClick={handleMinify}
              disabled={disabled || readOnly}
              className="text-xs text-blue-600 hover:text-blue-700 disabled:text-gray-400"
            >
              压缩
            </button>
          </div>
        </div>
      )}

      <div
        className={`relative overflow-hidden rounded-md border font-mono text-sm ${displayError ? 'border-red-300' : 'border-gray-200'} ${disabled ? 'bg-gray-50' : 'bg-white'} `}
        style={{ height }}
      >
        {/* Line numbers */}
        {showLineNumbers && (
          <div
            className="absolute top-0 bottom-0 left-0 w-10 overflow-hidden border-r border-gray-200 bg-gray-50 py-2 pr-2 text-right text-gray-400 select-none"
            style={{ lineHeight: '1.5rem' }}
          >
            {lineNumbers.map((num) => (
              <div key={num}>{num}</div>
            ))}
          </div>
        )}

        {/* Editor area */}
        <div className="absolute inset-0 overflow-auto" style={{ left: showLineNumbers ? 40 : 0 }}>
          {/* Syntax highlighted overlay */}
          {!isFocused && highlightedContent && (
            <pre
              className="pointer-events-none absolute inset-0 p-2 break-all whitespace-pre-wrap"
              style={{ lineHeight: '1.5rem' }}
            >
              {highlightedContent}
            </pre>
          )}

          {/* Textarea */}
          <textarea
            ref={textareaRef}
            value={text}
            onChange={handleTextChange}
            onBlur={handleBlur}
            onFocus={handleFocus}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            readOnly={readOnly}
            spellCheck={false}
            className={`h-full w-full resize-none p-2 font-mono outline-none ${isFocused ? 'text-gray-900' : 'text-transparent caret-gray-900'} ${disabled ? 'cursor-not-allowed' : ''} `}
            style={{ lineHeight: '1.5rem', background: 'transparent' }}
          />
        </div>
      </div>

      {displayError && (
        <div className="mt-1 flex items-center gap-1 text-xs text-red-500">
          <svg className="h-3 w-3" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
              clipRule="evenodd"
            />
          </svg>
          {displayError}
        </div>
      )}
    </div>
  );
};

export default JsonEditor;
