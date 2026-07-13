/**
 * Tests for the code-snippet block: template interpolation + copy-to-clipboard.
 *
 * Test plan:
 *   interpolateSnippet (pure)
 *     happy:          {{field}} replaced from record
 *     happy-dotted:   {{owner.name}} walks nested paths
 *     happy-builtin:  {{$origin}} resolved from builtins, record cannot shadow it
 *     edge-missing:   unknown field → '' (never leaks the raw {{token}})
 *     edge-null:      null / undefined values → ''
 *     edge-empty:     empty template → ''
 *     corner-repeat:  same placeholder repeated → replaced every time
 *
 *   firstRow (pure)
 *     array / { records } / { list } / single object / nullish
 *
 *   CodeSnippetBlockRenderer (render)
 *     renders interpolated snippet from the page record
 *     renders interpolated snippet from the first dataSource row
 *     copy button writes the interpolated snippet to the clipboard + shows feedback
 *     copy failure surfaces an error label (no silent success)
 *     missing record fields render blank, not the raw token
 *     empty template → empty state
 *     dataSource loading / error states
 */

import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import {
  CodeSnippetBlockRenderer,
  firstRow,
  interpolateSnippet,
} from '../CodeSnippetBlockRenderer';

// ---------------------------------------------------------------------------
// Helper: minimal SchemaRuntime stub (mirrors TraceGraphBlockRenderer.test.tsx)
// ---------------------------------------------------------------------------

function makeRuntime(
  overrides: {
    record?: Record<string, unknown>;
    dsData?: unknown;
    loading?: boolean;
    error?: Error | null;
  } = {},
): SchemaRuntime {
  const { record, dsData, loading = false, error = null } = overrides;

  const stub = {
    getContext: () => ({
      locale: 'en-US',
      t: (k: string) => k,
      state: {},
      form: {},
      global: {},
      record,
    }),
    getDataSourceManager: () => ({
      getData: (_id: string) => dsData ?? null,
      getState: (_id: string) => ({ data: dsData ?? null, loading, error }),
      subscribe: vi.fn(() => () => {}),
      reload: vi.fn(),
    }),
    getStateManager: () => ({
      getStore: () => ({ subscribe: vi.fn(() => () => {}) }),
      updateState: vi.fn(),
    }),
    getScopeId: () => 'scope-1',
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (tpl: string) => tpl,
      evaluateObject: (o: unknown) => o,
    }),
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
  };
  return stub as unknown as SchemaRuntime;
}

const EMBED_TEMPLATE =
  '<div id="auraboot-form"></div>\n<script src="{{$origin}}/api/crm/forms/{{pid}}/sdk.js"></script>';

const baseBlock: BlockConfig = {
  id: 'snip1',
  blockType: 'code-snippet',
  template: EMBED_TEMPLATE,
} as any;

// ---------------------------------------------------------------------------
// interpolateSnippet — pure function
// ---------------------------------------------------------------------------

describe('interpolateSnippet', () => {
  it('happy: replaces {{field}} from the record', () => {
    expect(interpolateSnippet('id={{pid}}', { pid: 'FORM-1' })).toBe('id=FORM-1');
  });

  it('happy-dotted: walks nested paths', () => {
    expect(
      interpolateSnippet('owner={{owner.name}}', { owner: { name: 'Ada' } }),
    ).toBe('owner=Ada');
  });

  it('happy-builtin: resolves {{$origin}} from builtins and record cannot shadow it', () => {
    const out = interpolateSnippet(
      '{{$origin}}/x/{{pid}}',
      { pid: 'p1', $origin: 'evil' },
      { $origin: 'https://app.example.com' },
    );
    expect(out).toBe('https://app.example.com/x/p1');
  });

  it('edge-missing: unknown field becomes empty string, never the raw token', () => {
    const out = interpolateSnippet('a={{nope}}b', { pid: 'p1' });
    expect(out).toBe('a=b');
    expect(out).not.toContain('{{');
  });

  it('edge-null: null and undefined values become empty strings', () => {
    expect(interpolateSnippet('[{{a}}][{{b}}]', { a: null, b: undefined })).toBe('[][]');
  });

  it('edge-empty: empty template returns empty string', () => {
    expect(interpolateSnippet('', { pid: 'p1' })).toBe('');
  });

  it('edge-no-record: undefined record still strips placeholders', () => {
    expect(interpolateSnippet('x={{pid}}', undefined)).toBe('x=');
  });

  it('corner-repeat: repeated placeholder is replaced every time', () => {
    expect(interpolateSnippet('{{pid}}-{{pid}}', { pid: '7' })).toBe('7-7');
  });

  it('corner-nonstring: numeric and boolean values are stringified', () => {
    expect(interpolateSnippet('{{n}}/{{b}}', { n: 42, b: false })).toBe('42/false');
  });
});

// ---------------------------------------------------------------------------
// firstRow — pure function
// ---------------------------------------------------------------------------

describe('firstRow', () => {
  it('unwraps a bare array', () => {
    expect(firstRow([{ pid: 'a' }, { pid: 'b' }])).toEqual({ pid: 'a' });
  });

  it('unwraps { records }', () => {
    expect(firstRow({ records: [{ pid: 'r1' }] })).toEqual({ pid: 'r1' });
  });

  it('unwraps { list }', () => {
    expect(firstRow({ list: [{ pid: 'l1' }] })).toEqual({ pid: 'l1' });
  });

  it('passes a single object through', () => {
    expect(firstRow({ pid: 'single' })).toEqual({ pid: 'single' });
  });

  it('returns undefined for nullish / empty payloads', () => {
    expect(firstRow(null)).toBeUndefined();
    expect(firstRow(undefined)).toBeUndefined();
    expect(firstRow([])).toBeUndefined();
    expect(firstRow({ records: [] })).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// CodeSnippetBlockRenderer — render + interaction
// ---------------------------------------------------------------------------

describe('CodeSnippetBlockRenderer', () => {
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
      writable: true,
    });
  });

  it('renders the snippet interpolated from the page record', () => {
    const runtime = makeRuntime({ record: { pid: 'FORM-42' } });
    render(<CodeSnippetBlockRenderer block={baseBlock} runtime={runtime} />);

    const code = screen.getByTestId('code-snippet-code');
    expect(code.textContent).toContain('/api/crm/forms/FORM-42/sdk.js');
    expect(code.textContent).toContain('<div id="auraboot-form"></div>');
    // {{$origin}} resolves against the live browser origin
    expect(code.textContent).toContain(
      `${window.location.origin}/api/crm/forms/FORM-42/sdk.js`,
    );
    expect(code.textContent).not.toContain('{{');
  });

  it('renders the snippet interpolated from the first dataSource row', () => {
    const runtime = makeRuntime({ dsData: { records: [{ pid: 'FROM-DS' }] } });
    const block = { ...baseBlock, dataSource: 'formDs' } as any;
    render(<CodeSnippetBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('code-snippet-code').textContent).toContain(
      '/api/crm/forms/FROM-DS/sdk.js',
    );
  });

  it('copy button writes the interpolated snippet to the clipboard and shows feedback', async () => {
    const runtime = makeRuntime({ record: { pid: 'FORM-42' } });
    render(<CodeSnippetBlockRenderer block={baseBlock} runtime={runtime} />);

    const btn = screen.getByTestId('code-snippet-copy');
    expect(btn).toBeInTheDocument();
    fireEvent.click(btn);

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    const copied = writeText.mock.calls[0][0] as string;
    expect(copied).toContain('/api/crm/forms/FORM-42/sdk.js');
    expect(copied).toContain('<div id="auraboot-form"></div>');
    expect(copied).not.toContain('{{');

    // The stubbed `t` echoes the key back, so the renderer falls back to its
    // English default — assert the rendered label, not the raw i18n key.
    await waitFor(() =>
      expect(screen.getByTestId('code-snippet-copy-label').textContent).toBe('Copied'),
    );
  });

  it('copy failure surfaces an error label instead of a silent success', async () => {
    writeText.mockRejectedValueOnce(new Error('denied'));
    const runtime = makeRuntime({ record: { pid: 'FORM-42' } });
    render(<CodeSnippetBlockRenderer block={baseBlock} runtime={runtime} />);

    fireEvent.click(screen.getByTestId('code-snippet-copy'));

    await waitFor(() =>
      expect(screen.getByTestId('code-snippet-copy-label').textContent).toBe('Copy failed'),
    );
  });

  it('missing record fields render blank, not the raw token', () => {
    const runtime = makeRuntime({ record: {} });
    render(<CodeSnippetBlockRenderer block={baseBlock} runtime={runtime} />);

    const text = screen.getByTestId('code-snippet-code').textContent ?? '';
    expect(text).toContain('/api/crm/forms//sdk.js');
    expect(text).not.toContain('{{pid}}');
  });

  it('renders the empty state when no template is configured', () => {
    const runtime = makeRuntime({ record: { pid: 'x' } });
    const block = { id: 'snip2', blockType: 'code-snippet' } as any;
    render(<CodeSnippetBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('code-snippet-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('code-snippet-copy')).toBeNull();
  });

  it('renders the loading state while the dataSource is loading', () => {
    const runtime = makeRuntime({ loading: true });
    const block = { ...baseBlock, dataSource: 'formDs' } as any;
    render(<CodeSnippetBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('code-snippet-loading')).toBeInTheDocument();
  });

  it('renders the error state when the dataSource fails', () => {
    const runtime = makeRuntime({ error: new Error('DS boom') });
    const block = { ...baseBlock, dataSource: 'formDs' } as any;
    render(<CodeSnippetBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('code-snippet-error')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('DS boom');
  });

  it('renders title, description and language chip when configured', () => {
    const runtime = makeRuntime({ record: { pid: 'p' } });
    const block = {
      ...baseBlock,
      title: 'Embed code',
      description: 'Paste into your site',
      language: 'html',
    } as any;
    render(<CodeSnippetBlockRenderer block={block} runtime={runtime} />);

    expect(screen.getByTestId('code-snippet-title')).toHaveTextContent('Embed code');
    expect(screen.getByTestId('code-snippet-description')).toHaveTextContent('Paste into your site');
    expect(screen.getByTestId('code-snippet-block')).toHaveTextContent('html');
  });
});
