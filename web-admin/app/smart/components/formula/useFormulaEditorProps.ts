/**
 * useFormulaEditorProps - Syntax bridge hook between FormulaEditor and runtime expressions.
 *
 * FormulaEditor uses a user-friendly syntax with `#fieldCode` for field references
 * and `#FUNCTION()` for function calls. The runtime expression engine uses `${form.fieldCode}`
 * and `${system.xxx}` patterns instead.
 *
 * This hook provides bidirectional conversion so that:
 * - Values stored in runtime syntax are displayed in FormulaEditor syntax
 * - User edits in FormulaEditor syntax are converted back to runtime syntax on change
 * - Model fields from the meta API are formatted for FormulaEditor's autocomplete
 *
 * Conversion rules:
 *   `${form.fieldCode}`     <-> `#fieldCode`
 *   `${system.currentUser}` <-> `#currentUser`
 *   `${system.now}`         <-> `#NOW()`
 *   Other `${expr}` patterns pass through as-is
 *   Text outside `${}` passes through as-is
 */

import { useCallback, useMemo } from 'react';
import type { FormulaFunction } from './FormulaEditor';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface UseFormulaEditorPropsOptions {
  /** Current expression value in runtime syntax (${form.xxx}) */
  value: string;
  /** Callback with converted runtime syntax value */
  onChange: (runtimeValue: string) => void;
  /** Model fields from meta API */
  modelFields?: Array<{ code: string; name: string; dataType?: string }>;
  /** Model code for function context */
  modelCode?: string;
}

export interface UseFormulaEditorPropsReturn {
  /** Value converted to FormulaEditor syntax (#fieldCode) */
  editorValue: string;
  /** onChange that converts back to runtime syntax */
  handleChange: (formulaValue: string) => void;
  /** Fields formatted for FormulaEditor */
  fields: Array<{ code: string; name: string }>;
  /** Optional function fetcher */
  fetchFunctions?: () => Promise<FormulaFunction[]>;
}

// ---------------------------------------------------------------------------
// System variable mappings
// ---------------------------------------------------------------------------

/**
 * Mapping from `system.<key>` to the FormulaEditor token.
 * The keys are the system variable names used in `${system.<key>}`.
 * The values are the FormulaEditor representations.
 */
const SYSTEM_TO_EDITOR: Record<string, string> = {
  now: '#NOW()',
  currentUser: '#currentUser',
  currentDate: '#CURRENT_DATE()',
  currentTime: '#CURRENT_DATETIME()',
};

/**
 * Reverse mapping: FormulaEditor token -> runtime `${system.<key>}` expression.
 * Built from SYSTEM_TO_EDITOR so the two stay in sync.
 */
const EDITOR_TO_SYSTEM: Record<string, string> = {};
for (const [sysKey, editorToken] of Object.entries(SYSTEM_TO_EDITOR)) {
  EDITOR_TO_SYSTEM[editorToken] = `\${system.${sysKey}}`;
}

// ---------------------------------------------------------------------------
// Conversion helpers
// ---------------------------------------------------------------------------

/**
 * Convert a runtime expression string to FormulaEditor display syntax.
 *
 * Handles three categories inside `${...}` blocks:
 *   1. `form.<fieldCode>` -> `#fieldCode`
 *   2. `system.<name>`    -> mapped token (e.g. `#NOW()`, `#currentUser`)
 *   3. Anything else      -> kept as `${expr}` (pass-through)
 *
 * Text outside `${...}` is left untouched.
 *
 * @param runtime - The expression in runtime syntax
 * @returns The expression in FormulaEditor syntax
 */
export function runtimeToEditor(runtime: string): string {
  if (!runtime) return '';

  return runtime.replace(/\$\{([^}]+)\}/g, (_match, expr: string) => {
    const trimmed = expr.trim();

    // form.fieldCode -> #fieldCode
    if (trimmed.startsWith('form.')) {
      const fieldCode = trimmed.slice('form.'.length);
      return `#${fieldCode}`;
    }

    // system.<name> -> mapped token
    if (trimmed.startsWith('system.')) {
      const sysKey = trimmed.slice('system.'.length);
      if (SYSTEM_TO_EDITOR[sysKey]) {
        return SYSTEM_TO_EDITOR[sysKey];
      }
      // Unknown system variable: fall back to #sysKey
      return `#${sysKey}`;
    }

    // Pass-through: keep the original ${expr}
    return `\${${expr}}`;
  });
}

/**
 * Convert a FormulaEditor syntax string back to runtime expression syntax.
 *
 * Processing order (to avoid partial matches):
 *   1. Known system tokens with parentheses: `#NOW()`, `#CURRENT_DATE()`, etc.
 *   2. `#fieldCode` references (word characters only) -> `${form.fieldCode}`
 *   3. Text outside `#` references passes through as-is.
 *
 * @param editor - The expression in FormulaEditor syntax
 * @returns The expression in runtime syntax
 */
export function editorToRuntime(editor: string): string {
  if (!editor) return '';

  let result = editor;

  // Step 1: Replace known system function tokens first (e.g. #NOW(), #CURRENT_DATE())
  // Sort by length descending so longer tokens match before shorter ones.
  const sortedTokens = Object.keys(EDITOR_TO_SYSTEM).sort((a, b) => b.length - a.length);
  for (const token of sortedTokens) {
    // Only replace tokens that include parentheses (function-style)
    if (token.includes('(')) {
      result = replaceAll(result, token, EDITOR_TO_SYSTEM[token]);
    }
  }

  // Step 2: Replace #identifier tokens that were not consumed by step 1.
  // Match `#` followed by a valid identifier (letters, digits, underscore, dot).
  // Use a regex that will NOT match already-converted ${...} blocks.
  result = result.replace(/#([a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)*)/g, (_match, fieldCode: string) => {
    // Check if this is a known system variable (non-function tokens like #currentUser)
    const editorToken = `#${fieldCode}`;
    if (EDITOR_TO_SYSTEM[editorToken]) {
      return EDITOR_TO_SYSTEM[editorToken];
    }

    // Default: treat as a form field reference
    return `\${form.${fieldCode}}`;
  });

  return result;
}

/**
 * Replace all occurrences of a literal string (no regex special chars).
 */
function replaceAll(source: string, search: string, replacement: string): string {
  if (!search) return source;
  // Escape regex special characters in search string
  const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return source.replace(new RegExp(escaped, 'g'), replacement);
}

// ---------------------------------------------------------------------------
// Built-in formula functions
// ---------------------------------------------------------------------------

/**
 * Default set of formula functions available in the FormulaEditor.
 * These correspond to the functions registered in ExpressionParser.
 */
const BUILTIN_FUNCTIONS: FormulaFunction[] = [
  // String functions
  {
    name: 'concat',
    description: 'Concatenate multiple values into one string',
    category: 'string',
    example: '#CONCAT(#firstName, " ", #lastName)',
    parameterTypes: ['...any'],
  },
  {
    name: 'upper',
    description: 'Convert text to uppercase',
    category: 'string',
    example: '#UPPER(#name)',
    parameterTypes: ['string'],
  },
  {
    name: 'lower',
    description: 'Convert text to lowercase',
    category: 'string',
    example: '#LOWER(#name)',
    parameterTypes: ['string'],
  },
  {
    name: 'trim',
    description: 'Remove leading and trailing whitespace',
    category: 'string',
    example: '#TRIM(#name)',
    parameterTypes: ['string'],
  },
  {
    name: 'length',
    description: 'Get the length of a string',
    category: 'string',
    example: '#LENGTH(#name)',
    parameterTypes: ['string'],
  },

  // Math functions
  {
    name: 'add',
    description: 'Add two numbers',
    category: 'math',
    example: '#ADD(#price, #tax)',
    parameterTypes: ['number', 'number'],
  },
  {
    name: 'subtract',
    description: 'Subtract second number from first',
    category: 'math',
    example: '#SUBTRACT(#total, #discount)',
    parameterTypes: ['number', 'number'],
  },
  {
    name: 'multiply',
    description: 'Multiply two numbers',
    category: 'math',
    example: '#MULTIPLY(#price, #quantity)',
    parameterTypes: ['number', 'number'],
  },
  {
    name: 'divide',
    description: 'Divide first number by second',
    category: 'math',
    example: '#DIVIDE(#total, #count)',
    parameterTypes: ['number', 'number'],
  },
  {
    name: 'max',
    description: 'Return the maximum value',
    category: 'math',
    example: '#MAX(#a, #b, #c)',
    parameterTypes: ['...number'],
  },
  {
    name: 'min',
    description: 'Return the minimum value',
    category: 'math',
    example: '#MIN(#a, #b, #c)',
    parameterTypes: ['...number'],
  },
  {
    name: 'round',
    description: 'Round a number to the nearest integer',
    category: 'math',
    example: '#ROUND(#price)',
    parameterTypes: ['number'],
  },

  // Logic functions
  {
    name: 'IF',
    description: 'Return one value if condition is true, another if false',
    category: 'logic',
    example: '#IF(#amount > 100, "High", "Low")',
    parameterTypes: ['boolean', 'any', 'any'],
  },
  {
    name: 'and',
    description: 'Return true if all arguments are truthy',
    category: 'logic',
    example: '#AND(#isActive, #isVerified)',
    parameterTypes: ['...any'],
  },
  {
    name: 'OR',
    description: 'Return true if any argument is truthy',
    category: 'logic',
    example: '#OR(#isAdmin, #isManager)',
    parameterTypes: ['...any'],
  },
  {
    name: 'not',
    description: 'Negate a boolean value',
    category: 'logic',
    example: '#NOT(#isDisabled)',
    parameterTypes: ['any'],
  },

  // Date functions
  {
    name: 'now',
    description: 'Return the current date and time',
    category: 'date',
    example: '#NOW()',
    parameterTypes: [],
  },
  {
    name: 'format_date',
    description: 'Format a date value',
    category: 'date',
    example: '#FORMAT_DATE(#createdAt, "YYYY-MM-DD")',
    parameterTypes: ['date', 'string'],
  },

  // Array functions
  {
    name: 'join',
    description: 'Join array elements into a string',
    category: 'array',
    example: '#JOIN(#tags, ", ")',
    parameterTypes: ['array', 'string'],
  },
  {
    name: 'split',
    description: 'Split a string into an array',
    category: 'array',
    example: '#SPLIT(#csv, ",")',
    parameterTypes: ['string', 'string'],
  },
  {
    name: 'includes',
    description: 'Check if array contains a value',
    category: 'array',
    example: '#INCLUDES(#roles, "admin")',
    parameterTypes: ['array', 'any'],
  },
];

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * Hook that bridges FormulaEditor's `#field` / `#FUNCTION()` syntax with the
 * runtime expression engine's `${form.field}` / `${system.xxx}` syntax.
 *
 * @example
 * ```tsx
 * const { editorValue, handleChange, fields, fetchFunctions } = useFormulaEditorProps({
 *   value: expression,          // e.g. "${form.price} * ${form.quantity}"
 *   onChange: setExpression,     // receives runtime syntax back
 *   modelFields: model?.fields, // from meta model API
 *   modelCode: 'order',
 * });
 *
 * return (
 *   <FormulaEditor
 *     value={editorValue}       // e.g. "#price * #quantity"
 *     onChange={handleChange}
 *     fields={fields}
 *     fetchFunctions={fetchFunctions}
 *   />
 * );
 * ```
 */
export function useFormulaEditorProps(
  options: UseFormulaEditorPropsOptions,
): UseFormulaEditorPropsReturn {
  const { value, onChange, modelFields, modelCode: _modelCode } = options;

  // Convert runtime syntax -> FormulaEditor syntax for display
  const editorValue = useMemo(() => runtimeToEditor(value), [value]);

  // Convert FormulaEditor syntax -> runtime syntax on user edits
  const handleChange = useCallback(
    (formulaValue: string) => {
      onChange(editorToRuntime(formulaValue));
    },
    [onChange],
  );

  // Format model fields for FormulaEditor autocomplete
  const fields = useMemo<Array<{ code: string; name: string }>>(
    () =>
      (modelFields ?? []).map((f) => ({
        code: f.code,
        name: f.name,
      })),
    [modelFields],
  );

  // Provide built-in functions via a stable fetcher reference
  const fetchFunctions = useCallback(async (): Promise<FormulaFunction[]> => {
    return BUILTIN_FUNCTIONS;
  }, []);

  return {
    editorValue,
    handleChange,
    fields,
    fetchFunctions,
  };
}

export default useFormulaEditorProps;
