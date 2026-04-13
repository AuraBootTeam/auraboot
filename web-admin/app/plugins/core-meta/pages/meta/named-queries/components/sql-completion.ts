/**
 * SQL auto-completion provider for Monaco Editor.
 * Provides SQL keyword + dynamic table name suggestions.
 */

import type { Monaco } from '@monaco-editor/react';

const SQL_KEYWORDS = [
  'select',
  'from',
  'where',
  'and',
  'OR',
  'not',
  'IN',
  'between',
  'like',
  'ilike',
  'IS',
  'null',
  'AS',
  'ON',
  'join',
  'left',
  'right',
  'inner',
  'outer',
  'cross',
  'full',
  'group',
  'BY',
  'order',
  'asc',
  'desc',
  'having',
  'limit',
  'offset',
  'union',
  'all',
  'distinct',
  'case',
  'when',
  'then',
  'else',
  'end',
  'exists',
  'count',
  'sum',
  'avg',
  'min',
  'max',
  'coalesce',
  'cast',
  'true',
  'false',
];

let disposable: { dispose: () => void } | null = null;

export function registerSqlCompletion(monaco: Monaco, tableNames: string[]): void {
  if (disposable) {
    disposable.dispose();
  }

  disposable = monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: [' ', '.', '_'],

    provideCompletionItems: (model: any, position: any) => {
      const word = model.getWordUntilPosition(position);
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };

      const suggestions: any[] = [];

      // SQL keywords
      SQL_KEYWORDS.forEach((kw) => {
        suggestions.push({
          label: kw,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: kw,
          range,
          sortText: '1_' + kw,
        });
      });

      // Table names
      tableNames.forEach((table) => {
        suggestions.push({
          label: table,
          kind: monaco.languages.CompletionItemKind.Struct,
          insertText: table,
          detail: 'Table',
          range,
          sortText: '0_' + table,
        });
      });

      // Common SQL snippets
      suggestions.push(
        {
          label: 'SELECT ... FROM',
          kind: monaco.languages.CompletionItemKind.Snippet,
          insertText: 'SELECT ${1:*} FROM ${2:table_name}',
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          detail: 'SELECT query template',
          range,
          sortText: '2_select',
        },
        {
          label: 'LEFT JOIN ... ON',
          kind: monaco.languages.CompletionItemKind.Snippet,
          insertText: 'LEFT JOIN ${1:table} ON ${2:condition}',
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          detail: 'LEFT JOIN template',
          range,
          sortText: '2_join',
        },
      );

      return { suggestions };
    },
  });
}

export function disposeSqlCompletion(): void {
  if (disposable) {
    disposable.dispose();
    disposable = null;
  }
}
