/**
 * Unit tests for expression-completion.ts
 *
 * Covers:
 * - $ input triggers 5 top-level dollar variables
 * - $user. expands static child properties
 * - $form. expands dynamic fields from context
 * - $record. expands dynamic fields from context
 * - $state. and $page. expand static children
 * - No context still shows static variables
 * - Existing form./context. completion still works
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DOLLAR_VARIABLES } from '~/shared/designer/expression/context-variables';
import type { ExpressionContext, FieldMeta } from '../../types';

// --- Monaco mock ---

function createMockMonaco() {
  const providers: any[] = [];
  return {
    languages: {
      CompletionItemKind: {
        Variable: 5,
        Property: 9,
        Field: 4,
        Function: 2,
        Keyword: 13,
        Snippet: 14,
      },
      CompletionItemInsertTextRule: {
        InsertAsSnippet: 4,
      },
      registerCompletionItemProvider: vi.fn((_langId: string, provider: any) => {
        providers.push(provider);
        return { dispose: vi.fn() };
      }),
    },
    _getLatestProvider: () => providers[providers.length - 1],
  };
}

function createMockModel(lineContent: string) {
  return {
    getLineContent: () => lineContent,
    getWordUntilPosition: (pos: any) => {
      // Simple word extraction: find word boundary before cursor
      const before = lineContent.substring(0, pos.column - 1);
      const wordMatch = before.match(/(\w*)$/);
      const word = wordMatch ? wordMatch[1] : '';
      return {
        word,
        startColumn: pos.column - word.length,
        endColumn: pos.column,
      };
    },
  };
}

function createPosition(column: number) {
  return { lineNumber: 1, column };
}

// Helper to call the provider with a given line content + cursor position
async function getCompletions(
  monaco: any,
  lineContent: string,
  column?: number,
  context?: ExpressionContext,
) {
  // Dynamically import to pick up the mock
  const { registerExpressionCompletion } = await import('../expression-completion');
  registerExpressionCompletion(monaco as any, context);

  const provider = monaco._getLatestProvider();
  const model = createMockModel(lineContent);
  const position = createPosition(column ?? lineContent.length + 1);
  const result = provider.provideCompletionItems(model, position);
  return result.suggestions;
}

describe('expression-completion: $ variable auto-complete', () => {
  let monaco: ReturnType<typeof createMockMonaco>;

  beforeEach(() => {
    monaco = createMockMonaco();
  });

  it('should include $ in triggerCharacters', async () => {
    const { registerExpressionCompletion } = await import('../expression-completion');
    registerExpressionCompletion(monaco as any);

    const call = monaco.languages.registerCompletionItemProvider.mock.calls[0];
    const provider = call[1];
    expect(provider.triggerCharacters).toContain('$');
  });

  it('should show 5 top-level $ variables when $ is typed inside {{ }}', async () => {
    const suggestions = await getCompletions(monaco, '{{ $');
    const dollarVars = suggestions.filter((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label.startsWith('$');
    });
    expect(dollarVars).toHaveLength(5);

    const labels = dollarVars.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('$user');
    expect(labels).toContain('$form');
    expect(labels).toContain('$state');
    expect(labels).toContain('$record');
    expect(labels).toContain('$page');
  });

  it('should show $ variables when partial name is typed (e.g. $us)', async () => {
    const suggestions = await getCompletions(monaco, '{{ $us');
    const dollarVars = suggestions.filter((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label.startsWith('$');
    });
    // All 5 are returned; Monaco client-side filters by prefix
    expect(dollarVars).toHaveLength(5);
  });

  it('should expand $user. to static child properties', async () => {
    const suggestions = await getCompletions(monaco, '{{ $user.');
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('id');
    expect(labels).toContain('name');
    expect(labels).toContain('email');
    expect(labels).toContain('roles');
    expect(suggestions).toHaveLength(4);
  });

  it('should expand $state. to static child properties', async () => {
    const suggestions = await getCompletions(monaco, '{{ $state.');
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('filters');
    expect(labels).toContain('selectedIds');
    expect(suggestions).toHaveLength(2);
  });

  it('should expand $page. to static child properties', async () => {
    const suggestions = await getCompletions(monaco, '{{ $page.');
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('kind');
    expect(labels).toContain('modelCode');
    expect(labels).toContain('pageKey');
    expect(suggestions).toHaveLength(3);
  });

  it('should expand $form. with dynamic fields from context', async () => {
    const context: ExpressionContext = {
      form: {
        order_name: {
          code: 'order_name',
          displayName: 'Order Name',
          dataType: 'STRING',
        },
        order_amount: {
          code: 'order_amount',
          displayName: 'Order Amount',
          dataType: 'DECIMAL',
        },
      },
    };
    const suggestions = await getCompletions(monaco, '{{ $form.', undefined, context);
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    // $form has no static children, only dynamic from context
    expect(labels).toContain('order_name');
    expect(labels).toContain('order_amount');
    expect(suggestions).toHaveLength(2);
  });

  it('should expand $record. with dynamic fields from context', async () => {
    const context: ExpressionContext = {
      form: {
        product_code: {
          code: 'product_code',
          displayName: 'Product Code',
          dataType: 'STRING',
        },
      },
    };
    const suggestions = await getCompletions(monaco, '{{ $record.', undefined, context);
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('product_code');
    expect(suggestions).toHaveLength(1);
  });

  it('should show empty children for $form. and $record. without context', async () => {
    const suggestions = await getCompletions(monaco, '{{ $form.');
    expect(suggestions).toHaveLength(0);
  });

  it('should still show static $ variables without any context', async () => {
    const suggestions = await getCompletions(monaco, '{{ $');
    const dollarVars = suggestions.filter((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label.startsWith('$');
    });
    expect(dollarVars).toHaveLength(5);
  });

  it('should not show $ variables outside {{ }} expression', async () => {
    const suggestions = await getCompletions(monaco, '$');
    // Outside {{ }}, should suggest the {{ }} wrapper snippet, not $ variables
    const dollarVars = suggestions.filter((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label.startsWith('$');
    });
    expect(dollarVars).toHaveLength(0);
  });

  it('should include description and type in $ variable suggestions', async () => {
    const suggestions = await getCompletions(monaco, '{{ $');
    const userVar = suggestions.find((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label === '$user';
    });
    expect(userVar).toBeDefined();
    expect(userVar.detail).toBe('Current authenticated user');
    expect(userVar.insertText).toBe('$user');
  });

  it('should include type info in child property suggestions', async () => {
    const suggestions = await getCompletions(monaco, '{{ $user.');
    const emailProp = suggestions.find((s: any) => {
      const label = typeof s.label === 'string' ? s.label : s.label.label;
      return label === 'email';
    });
    expect(emailProp).toBeDefined();
    expect(emailProp.detail).toBe('string');
  });
});

describe('expression-completion: existing form./context. completion', () => {
  let monaco: ReturnType<typeof createMockMonaco>;

  beforeEach(() => {
    monaco = createMockMonaco();
  });

  it('should still complete form. properties (non-dollar prefix)', async () => {
    const context: ExpressionContext = {
      form: {
        field_a: {
          code: 'field_a',
          displayName: 'Field A',
          dataType: 'STRING',
        },
      },
    };
    const suggestions = await getCompletions(monaco, '{{ form.', undefined, context);
    const labels = suggestions.map((s: any) =>
      typeof s.label === 'string' ? s.label : s.label.label,
    );
    expect(labels).toContain('field_a');
  });
});

describe('DOLLAR_VARIABLES definition', () => {
  it('should define exactly 5 variables', () => {
    expect(DOLLAR_VARIABLES).toHaveLength(5);
  });

  it('should have expected variable names', () => {
    const names = DOLLAR_VARIABLES.map((v) => v.name);
    expect(names).toEqual(['$user', '$form', '$state', '$record', '$page']);
  });

  it('should have $user with 4 children', () => {
    const user = DOLLAR_VARIABLES.find((v) => v.name === '$user');
    expect(user?.children).toHaveLength(4);
  });

  it('should have $form and $record with empty children (dynamic)', () => {
    const form = DOLLAR_VARIABLES.find((v) => v.name === '$form');
    const record = DOLLAR_VARIABLES.find((v) => v.name === '$record');
    expect(form?.children).toHaveLength(0);
    expect(record?.children).toHaveLength(0);
  });
});
