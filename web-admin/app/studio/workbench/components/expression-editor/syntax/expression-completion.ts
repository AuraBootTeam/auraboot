/**
 * Expression Completion Provider for Monaco Editor
 *
 * Provides auto-completion for variables, functions, and properties.
 */

import type { Monaco } from '@monaco-editor/react';
import type { languages, IRange } from 'monaco-editor';
import { EXPRESSION_LANGUAGE_ID, BUILTIN_FUNCTIONS } from './expression-language';
import type { ExpressionContext, FieldMeta } from '../types';

// Store for completion provider disposal
let completionProviderDisposable: { dispose: () => void } | null = null;

/**
 * Register completion provider for expression language
 */
export function registerExpressionCompletion(monaco: Monaco, context?: ExpressionContext): void {
  // Dispose previous provider if exists
  if (completionProviderDisposable) {
    completionProviderDisposable.dispose();
  }

  // Register new completion provider
  completionProviderDisposable = monaco.languages.registerCompletionItemProvider(
    EXPRESSION_LANGUAGE_ID,
    {
      triggerCharacters: ['.', '(', '{', ' '],

      provideCompletionItems: (model: any, position: any) => {
        const word = model.getWordUntilPosition(position);
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn,
        };

        const lineContent = model.getLineContent(position.lineNumber);
        const beforeCursor = lineContent.substring(0, position.column - 1);

        // Check if we're inside an expression {{ }}
        const lastOpenBrace = beforeCursor.lastIndexOf('{{');
        const lastCloseBrace = beforeCursor.lastIndexOf('}}');
        const insideExpression = lastOpenBrace > lastCloseBrace;

        if (!insideExpression) {
          // Suggest {{ }} wrapper
          return {
            suggestions: [
              {
                label: '{{ expression }}',
                kind: monaco.languages.CompletionItemKind.Snippet,
                insertText: '{{ ${1:expression} }}',
                insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
                documentation: 'Insert expression wrapper',
                range,
              },
            ],
          };
        }

        const suggestions: languages.CompletionItem[] = [];

        // Check for property access (e.g., form.)
        const propertyMatch = beforeCursor.match(/(\w+)\.\s*$/);
        if (propertyMatch) {
          const objectName = propertyMatch[1];

          // Suggest form fields
          if (objectName === 'form' && context?.form) {
            Object.entries(context.form).forEach(([key, field]) => {
              suggestions.push(createFieldSuggestion(monaco, key, field, range));
            });
          }

          // Suggest context properties
          if (objectName === 'context' && context?.context) {
            Object.keys(context.context).forEach((key) => {
              suggestions.push({
                label: key,
                kind: monaco.languages.CompletionItemKind.Property,
                insertText: key,
                documentation: `Context property: ${key}`,
                range,
              });
            });
          }

          return { suggestions };
        }

        // Add built-in function suggestions
        BUILTIN_FUNCTIONS.forEach((func) => {
          suggestions.push(createFunctionSuggestion(monaco, func, range));
        });

        // Add custom function suggestions
        if (context?.functions) {
          context.functions.forEach((func) => {
            suggestions.push(createFunctionSuggestion(monaco, func, range));
          });
        }

        // Add form variable suggestion
        if (context?.form) {
          suggestions.push({
            label: 'form',
            kind: monaco.languages.CompletionItemKind.Variable,
            insertText: 'form.',
            documentation: 'Access form field values',
            range,
          });

          // Add direct field suggestions (form.fieldName shorthand)
          Object.entries(context.form).forEach(([key, field]) => {
            suggestions.push({
              label: `form.${key}`,
              kind: monaco.languages.CompletionItemKind.Field,
              insertText: `form.${key}`,
              documentation: field.description || `${field.displayName} (${field.dataType})`,
              detail: field.dataType,
              range,
            });
          });
        }

        // Add context variable suggestion
        if (context?.context) {
          suggestions.push({
            label: 'context',
            kind: monaco.languages.CompletionItemKind.Variable,
            insertText: 'context.',
            documentation: 'Access context variables (user, tenant, etc.)',
            range,
          });
        }

        // Add keyword suggestions
        ['true', 'false', 'null'].forEach((kw) => {
          suggestions.push({
            label: kw,
            kind: monaco.languages.CompletionItemKind.Keyword,
            insertText: kw,
            range,
          });
        });

        // Add operator snippets
        suggestions.push(
          {
            label: 'if-else',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: 'if(${1:condition}, ${2:trueValue}, ${3:falseValue})',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            documentation: 'Conditional expression',
            range,
          },
          {
            label: 'ternary',
            kind: monaco.languages.CompletionItemKind.Snippet,
            insertText: '${1:condition} ? ${2:trueValue} : ${3:falseValue}',
            insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
            documentation: 'Ternary operator',
            range,
          },
        );

        return { suggestions };
      },
    },
  );
}

/**
 * Create completion item for a field
 */
function createFieldSuggestion(
  monaco: Monaco,
  key: string,
  field: FieldMeta,
  range: IRange,
): languages.CompletionItem {
  const typeIcon = getTypeIcon(field.dataType);
  return {
    label: {
      label: key,
      detail: ` ${field.dataType}`,
      description: field.displayName,
    },
    kind: monaco.languages.CompletionItemKind.Field,
    insertText: key,
    documentation: {
      value: `**${field.displayName}**\n\nType: \`${field.dataType}\`${
        field.description ? `\n\n${field.description}` : ''
      }${field.required ? '\n\n*Required*' : ''}`,
    },
    detail: `${typeIcon} ${field.dataType}`,
    range,
  };
}

/**
 * Create completion item for a function
 */
function createFunctionSuggestion(
  monaco: Monaco,
  func: { name: string; description: string; signature: string },
  range: IRange,
): languages.CompletionItem {
  // Extract parameters from signature
  const paramMatch = func.signature.match(/\(([^)]*)\)/);
  const params = paramMatch ? paramMatch[1] : '';
  const hasParams = params.length > 0;

  // Create snippet with parameter placeholders
  let insertText = func.name;
  if (hasParams) {
    const paramList = params.split(',').map((p, i) => `\${${i + 1}:${p.trim()}}`);
    insertText = `${func.name}(${paramList.join(', ')})`;
  } else {
    insertText = `${func.name}()`;
  }

  return {
    label: {
      label: func.name,
      detail: `(${params})`,
    },
    kind: monaco.languages.CompletionItemKind.Function,
    insertText,
    insertTextRules: hasParams
      ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
      : undefined,
    documentation: {
      value: `**${func.name}**\n\n${func.description}\n\n\`\`\`\n${func.signature}\n\`\`\``,
    },
    detail: func.signature,
    range,
  };
}

/**
 * Get icon for data type
 */
function getTypeIcon(dataType: string): string {
  const icons: Record<string, string> = {
    STRING: 'Aa',
    TEXT: 'Tx',
    INTEGER: '#',
    DECIMAL: '#.#',
    BOOLEAN: '?',
    DATE: 'D',
    DATETIME: 'DT',
    ENUM: 'E',
    REFERENCE: 'R',
    JSON: '{}',
  };
  return icons[dataType] || '?';
}
