/**
 * Expression Language Definition for Monaco Editor
 *
 * Defines syntax highlighting rules for the unified DSL.
 * Syntax: {{ expression }}
 */

import type { Monaco } from '@monaco-editor/react';

export const EXPRESSION_LANGUAGE_ID = 'aura-expression';

/**
 * Built-in functions available in expressions
 */
export const BUILTIN_FUNCTIONS = [
  // String functions
  { name: 'concat', description: 'Concatenate strings', signature: 'concat(...strings)' },
  { name: 'upper', description: 'Convert to uppercase', signature: 'upper(str)' },
  { name: 'lower', description: 'Convert to lowercase', signature: 'lower(str)' },
  { name: 'trim', description: 'Remove whitespace', signature: 'trim(str)' },
  { name: 'length', description: 'Get string length', signature: 'length(str)' },

  // Math functions
  { name: 'add', description: 'Add two numbers', signature: 'add(a, b)' },
  { name: 'subtract', description: 'Subtract two numbers', signature: 'subtract(a, b)' },
  { name: 'multiply', description: 'Multiply two numbers', signature: 'multiply(a, b)' },
  { name: 'divide', description: 'Divide two numbers', signature: 'divide(a, b)' },
  { name: 'max', description: 'Get maximum value', signature: 'max(...numbers)' },
  { name: 'min', description: 'Get minimum value', signature: 'min(...numbers)' },
  { name: 'round', description: 'Round a number', signature: 'round(num)' },

  // Logic functions
  {
    name: 'if',
    description: 'Conditional expression',
    signature: 'if(condition, trueValue, falseValue)',
  },
  { name: 'and', description: 'Logical AND', signature: 'and(...values)' },
  { name: 'or', description: 'Logical OR', signature: 'or(...values)' },
  { name: 'not', description: 'Logical NOT', signature: 'not(value)' },

  // Array functions
  { name: 'join', description: 'Join array elements', signature: 'join(arr, separator)' },
  { name: 'split', description: 'Split string to array', signature: 'split(str, separator)' },
  {
    name: 'includes',
    description: 'Check if array includes value',
    signature: 'includes(arr, value)',
  },

  // Date functions
  { name: 'now', description: 'Get current date', signature: 'now()' },
  { name: 'formatDate', description: 'Format date', signature: 'formatDate(date, format)' },
];

/**
 * Keywords in expression language
 */
export const KEYWORDS = ['true', 'false', 'null', 'undefined'];

/**
 * Register expression language with Monaco
 */
export function registerExpressionLanguage(monaco: Monaco): void {
  // Check if language already registered
  const languages = monaco.languages.getLanguages();
  if (languages.some((lang: { id: string }) => lang.id === EXPRESSION_LANGUAGE_ID)) {
    return;
  }

  // Register language
  monaco.languages.register({
    id: EXPRESSION_LANGUAGE_ID,
    extensions: ['.expr'],
    aliases: ['AuraExpression', 'expression'],
  });

  // Set language configuration
  monaco.languages.setLanguageConfiguration(EXPRESSION_LANGUAGE_ID, {
    comments: {
      lineComment: '//',
    },
    brackets: [
      ['{', '}'],
      ['[', ']'],
      ['(', ')'],
    ],
    autoClosingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '(', close: ')' },
      { open: "'", close: "'", notIn: ['string', 'comment'] },
      { open: '"', close: '"', notIn: ['string'] },
    ],
    surroundingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '(', close: ')' },
      { open: "'", close: "'" },
      { open: '"', close: '"' },
    ],
  });

  // Set tokenizer for syntax highlighting
  monaco.languages.setMonarchTokensProvider(EXPRESSION_LANGUAGE_ID, {
    defaultToken: '',
    tokenPostfix: '.expr',

    keywords: KEYWORDS,
    functions: BUILTIN_FUNCTIONS.map((f) => f.name),

    operators: [
      '=',
      '>',
      '<',
      '!',
      '~',
      '?',
      ':',
      '==',
      '<=',
      '>=',
      '!=',
      '&&',
      '||',
      '++',
      '--',
      '+',
      '-',
      '*',
      '/',
      '&',
      '|',
      '^',
      '%',
      '<<',
      '>>',
      '>>>',
      '+=',
      '-=',
      '*=',
      '/=',
      '&=',
      '|=',
      '^=',
      '%=',
      '<<=',
      '>>=',
      '>>>=',
    ],

    symbols: /[=><!~?:&|+\-*\/\^%]+/,
    escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,

    tokenizer: {
      root: [
        // Expression wrapper {{ }}
        [/\{\{/, 'delimiter.expression', '@expression'],

        // Plain text outside {{ }}
        [/./, 'string'],
      ],

      expression: [
        // Closing }}
        [/\}\}/, 'delimiter.expression', '@pop'],

        // Whitespace
        { include: '@whitespace' },

        // Numbers
        [/\d*\.\d+([eE][\-+]?\d+)?/, 'number.float'],
        [/0[xX][0-9a-fA-F]+/, 'number.hex'],
        [/\d+/, 'number'],

        // Identifiers and keywords
        [
          /[a-zA-Z_$][\w$]*/,
          {
            cases: {
              '@keywords': 'keyword',
              '@functions': 'function',
              '@default': 'identifier',
            },
          },
        ],

        // Strings
        [/"([^"\\]|\\.)*$/, 'string.invalid'],
        [/'([^'\\]|\\.)*$/, 'string.invalid'],
        [/"/, 'string', '@string_double'],
        [/'/, 'string', '@string_single'],

        // Delimiters and operators
        [/[{}()\[\]]/, '@brackets'],
        [/[<>](?!@symbols)/, '@brackets'],
        [
          /@symbols/,
          {
            cases: {
              '@operators': 'operator',
              '@default': '',
            },
          },
        ],

        // Delimiter: after number because of .\d floats
        [/[;,.]/, 'delimiter'],
      ],

      whitespace: [[/[ \t\r\n]+/, 'white']],

      string_double: [
        [/[^\\"]+/, 'string'],
        [/@escapes/, 'string.escape'],
        [/\\./, 'string.escape.invalid'],
        [/"/, 'string', '@pop'],
      ],

      string_single: [
        [/[^\\']+/, 'string'],
        [/@escapes/, 'string.escape'],
        [/\\./, 'string.escape.invalid'],
        [/'/, 'string', '@pop'],
      ],
    },
  });

  // Define custom theme colors
  monaco.editor.defineTheme('expression-theme', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'delimiter.expression', foreground: '6366F1', fontStyle: 'bold' },
      { token: 'function', foreground: '8B5CF6' },
      { token: 'keyword', foreground: '0EA5E9' },
      { token: 'identifier', foreground: '374151' },
      { token: 'number', foreground: 'd97706' },
      { token: 'string', foreground: '059669' },
      { token: 'operator', foreground: 'dc2626' },
    ],
    colors: {
      'editor.background': '#FFFFFF',
      'editor.foreground': '#374151',
    },
  });
}
