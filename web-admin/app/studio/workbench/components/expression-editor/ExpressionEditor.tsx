/**
 * Expression Editor Component
 *
 * A Monaco-based expression editor supporting unified DSL syntax.
 * Provides syntax highlighting, auto-completion, and validation.
 *
 * @since 3.2.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import Editor, { type Monaco, type OnMount } from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import { expressionParser } from '~/studio/services/runtime/expression/expression-parser';
import type { ExpressionEditorProps, ExpressionValidationResult, ExpressionContext } from './types';
import { registerExpressionLanguage } from './syntax/expression-language';
import { registerExpressionCompletion } from './syntax/expression-completion';

// Language ID for our expression DSL
const EXPRESSION_LANGUAGE_ID = 'aura-expression';

/**
 * Expression Editor Component
 */
export const ExpressionEditor: React.FC<ExpressionEditorProps> = ({
  value,
  onChange,
  context,
  returnType,
  mode = 'inline',
  placeholder = '输入表达式，例如: {{ form.name }}',
  disabled = false,
  readOnly = false,
  height = 120,
  lineNumbers = mode === 'full',
  minimap = false,
  autoWrap = true,
  onBlur,
  onFocus,
  onValidationChange,
}) => {
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const [isEditorReady, setIsEditorReady] = useState(false);
  const [validation, setValidation] = useState<ExpressionValidationResult>({ valid: true });

  // Normalize value (ensure {{ }} wrapper if autoWrap is enabled)
  const normalizedValue = React.useMemo(() => {
    if (!value) return '';
    if (!autoWrap) return value;
    const trimmed = value.trim();
    if (trimmed && !trimmed.startsWith('{{')) {
      return `{{ ${trimmed} }}`;
    }
    return value;
  }, [value, autoWrap]);

  // Validate expression
  const validateExpression = useCallback(
    (expr: string) => {
      const result = expressionParser.validate(expr);
      const dependencies = expressionParser.extractVariables(expr);

      const validationResult: ExpressionValidationResult = {
        valid: result.valid,
        errors: result.error ? [{ message: result.error }] : undefined,
        dependencies,
      };

      setValidation(validationResult);
      onValidationChange?.(validationResult);

      // Update Monaco markers
      if (editorRef.current && monacoRef.current) {
        const model = editorRef.current.getModel();
        if (model) {
          const markers: editor.IMarkerData[] = [];
          if (!result.valid && result.error) {
            markers.push({
              severity: monacoRef.current.MarkerSeverity.Error,
              message: result.error,
              startLineNumber: 1,
              startColumn: 1,
              endLineNumber: 1,
              endColumn: model.getLineMaxColumn(1),
            });
          }
          monacoRef.current.editor.setModelMarkers(model, 'expression', markers);
        }
      }

      return validationResult;
    },
    [onValidationChange],
  );

  // Handle editor mount
  const handleEditorMount: OnMount = useCallback(
    (editor, monaco) => {
      editorRef.current = editor;
      monacoRef.current = monaco;

      // Register custom language
      registerExpressionLanguage(monaco);

      // Register completion provider
      registerExpressionCompletion(monaco, context);

      // Set model language
      const model = editor.getModel();
      if (model) {
        monaco.editor.setModelLanguage(model, EXPRESSION_LANGUAGE_ID);
      }

      // Initial validation
      if (normalizedValue) {
        validateExpression(normalizedValue);
      }

      setIsEditorReady(true);

      // Focus handling
      editor.onDidFocusEditorText(() => {
        onFocus?.();
      });

      editor.onDidBlurEditorText(() => {
        onBlur?.();
      });
    },
    [context, normalizedValue, validateExpression, onFocus, onBlur],
  );

  // Handle content change
  const handleChange = useCallback(
    (newValue: string | undefined) => {
      const val = newValue || '';
      onChange(val);
      validateExpression(val);
    },
    [onChange, validateExpression],
  );

  // Update context when it changes
  useEffect(() => {
    if (monacoRef.current && isEditorReady) {
      registerExpressionCompletion(monacoRef.current, context);
    }
  }, [context, isEditorReady]);

  // Editor options based on mode
  const editorOptions: editor.IStandaloneEditorConstructionOptions = {
    minimap: { enabled: minimap },
    lineNumbers: lineNumbers ? 'on' : 'off',
    readOnly: readOnly || disabled,
    fontSize: mode === 'inline' ? 12 : 13,
    lineHeight: mode === 'inline' ? 18 : 20,
    padding: { top: mode === 'inline' ? 4 : 8, bottom: mode === 'inline' ? 4 : 8 },
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    wrappingStrategy: 'advanced',
    automaticLayout: true,
    folding: false,
    glyphMargin: false,
    lineDecorationsWidth: 0,
    lineNumbersMinChars: 2,
    renderLineHighlight: mode === 'inline' ? 'none' : 'line',
    scrollbar: {
      vertical: mode === 'inline' ? 'hidden' : 'auto',
      horizontal: 'hidden',
      verticalScrollbarSize: 8,
    },
    overviewRulerLanes: 0,
    hideCursorInOverviewRuler: true,
    overviewRulerBorder: false,
    cursorBlinking: 'smooth',
    contextmenu: mode !== 'inline',
    quickSuggestions: true,
    suggestOnTriggerCharacters: true,
    tabCompletion: 'on',
    acceptSuggestionOnEnter: 'on',
    formatOnPaste: true,
    formatOnType: true,
  };

  // Calculate actual height
  const actualHeight = mode === 'inline' ? 32 : height;

  return (
    <div
      className={`expression-editor relative overflow-hidden rounded-md border transition-colors ${
        disabled ? 'bg-gray-100 opacity-60' : 'bg-white'
      } ${validation.valid ? 'border-gray-200 focus-within:border-blue-400' : 'border-red-300'}`}
    >
      {/* Loading placeholder */}
      {!isEditorReady && (
        <div
          className="absolute inset-0 flex items-center justify-center bg-gray-50 text-xs text-gray-400"
          style={{ height: actualHeight }}
        >
          加载编辑器...
        </div>
      )}

      {/* Monaco Editor */}
      <Editor
        height={actualHeight}
        defaultLanguage={EXPRESSION_LANGUAGE_ID}
        value={normalizedValue}
        onChange={handleChange}
        onMount={handleEditorMount}
        options={editorOptions}
        theme="vs"
        loading={null}
      />

      {/* Placeholder overlay */}
      {!normalizedValue && isEditorReady && (
        <div
          className="pointer-events-none absolute top-1/2 left-2 -translate-y-1/2 text-xs text-gray-400"
          style={{ display: mode === 'inline' ? 'block' : 'none' }}
        >
          {placeholder}
        </div>
      )}

      {/* Validation error indicator */}
      {!validation.valid && validation.errors && validation.errors.length > 0 && (
        <div
          className="absolute top-1/2 right-2 -translate-y-1/2 text-red-500"
          title={validation.errors[0].message}
        >
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
              clipRule="evenodd"
            />
          </svg>
        </div>
      )}

      {/* Expression indicator */}
      {mode === 'inline' && (
        <div className="pointer-events-none absolute top-1/2 right-8 -translate-y-1/2 font-mono text-[10px] text-gray-300">
          fx
        </div>
      )}
    </div>
  );
};

export default ExpressionEditor;
