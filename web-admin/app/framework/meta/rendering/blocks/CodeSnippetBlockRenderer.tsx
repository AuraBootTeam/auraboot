/**
 * CodeSnippetBlockRenderer - Read-only code/embed snippet block with copy-to-clipboard.
 *
 * Renders a `template` string whose `{{path}}` placeholders are interpolated from the
 * current record (detail page context) or, when `dataSource` is set, from the first row
 * of that data source. Used by "embed centre" style pages that must hand the user a
 * per-record script snippet.
 *
 * DSL config:
 * {
 *   "blockType": "code-snippet",
 *   "title": "Embed code",                            // optional, localizable
 *   "template": "<script src=\"{{$origin}}/api/x/{{pid}}/sdk.js\"></script>",
 *   "dataSource": "<id>",                             // optional; omit to read the page record
 *   "language": "html",                               // optional label shown in the header
 *   "description": "Paste this into your page"        // optional, localizable
 * }
 *
 * Placeholders:
 *   {{field}}        - record field (dotted paths supported: {{owner.name}})
 *   {{$origin}}      - window.location.origin  (embed snippets need an absolute host)
 *   {{$host}}        - window.location.host
 * Unknown / null / undefined fields interpolate to an empty string (never the raw token) —
 * a leaked `{{token}}` in customer-facing embed code is worse than a blank.
 */

import React, { useCallback, useMemo, useState } from 'react';
import { Check, Copy } from 'lucide-react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readDataSourceState, useDataSourceSubscription } from './workbenchBlockUtils';

export interface CodeSnippetBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const PLACEHOLDER = /\{\{\s*([\w$.]+)\s*\}\}/g;

/** Built-in tokens resolved from the browser location (SSR-safe). */
export function browserTokens(): Record<string, string> {
  if (typeof window === 'undefined' || !window.location) {
    return { $origin: '', $host: '' };
  }
  return { $origin: window.location.origin, $host: window.location.host };
}

/** Walk a dotted path (`owner.name`) on a record. Returns undefined when unresolvable. */
function readPath(source: Record<string, unknown> | undefined, path: string): unknown {
  if (!source) return undefined;
  return path.split('.').reduce<unknown>((acc, segment) => {
    if (acc && typeof acc === 'object') {
      return (acc as Record<string, unknown>)[segment];
    }
    return undefined;
  }, source);
}

/**
 * Interpolate `{{path}}` placeholders from `record`, falling back to `builtins`.
 * Missing / null / undefined values become '' so no raw token ever reaches the clipboard.
 */
export function interpolateSnippet(
  template: string,
  record: Record<string, unknown> | undefined,
  builtins: Record<string, string> = {},
): string {
  if (!template) return '';
  return template.replace(PLACEHOLDER, (_match, path: string) => {
    if (path in builtins) return builtins[path];
    const value = readPath(record, path);
    if (value === undefined || value === null) return '';
    return String(value);
  });
}

/** First row of a data-source payload (array / { records } / { list } / single object). */
export function firstRow(data: unknown): Record<string, unknown> | undefined {
  if (Array.isArray(data)) {
    return (data[0] as Record<string, unknown>) ?? undefined;
  }
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>;
    if (Array.isArray(d.records)) return (d.records[0] as Record<string, unknown>) ?? undefined;
    if (Array.isArray(d.list)) return (d.list[0] as Record<string, unknown>) ?? undefined;
    return d;
  }
  return undefined;
}

type CopyState = 'idle' | 'copied' | 'error';

export const CodeSnippetBlockRenderer: React.FC<CodeSnippetBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  // Plain fn (not useCallback): `t` is re-derived each render, so memoizing on it
  // would invalidate every render anyway.
  const tx = (key: string, fallback: string): string => (t(key) !== key ? t(key) : fallback);

  // Bare-path config — same pattern as StatCardBlockRenderer / CardGridBlockRenderer.
  const cfg = { ...((block as any).props || {}), ...(block as any) };
  const dataSourceId = typeof block.dataSource === 'string' ? block.dataSource : undefined;

  useDataSourceSubscription(runtime, dataSourceId);
  const dataSourceState = readDataSourceState(runtime, dataSourceId);

  const [copyState, setCopyState] = useState<CopyState>('idle');

  const template: string = typeof cfg.template === 'string' ? cfg.template : '';

  const record = useMemo<Record<string, unknown> | undefined>(() => {
    if (dataSourceId) {
      return firstRow(dataSourceState?.data);
    }
    return (context as unknown as { record?: Record<string, unknown> }).record;
  }, [context, dataSourceId, dataSourceState?.data]);

  const snippet = useMemo(
    () => interpolateSnippet(template, record, browserTokens()),
    [template, record],
  );

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(snippet);
      setCopyState('copied');
      window.setTimeout(() => setCopyState('idle'), 2000);
    } catch {
      // Clipboard is unavailable (insecure context / permission denied). Surface it
      // in-place instead of silently pretending the copy succeeded.
      setCopyState('error');
    }
  }, [snippet]);

  // ── Error state ────────────────────────────────────────────────────────────
  if (dataSourceState?.error) {
    const message =
      dataSourceState.error instanceof Error
        ? dataSourceState.error.message
        : String(dataSourceState.error);
    return (
      <div
        role="alert"
        data-testid="code-snippet-error"
        className="rounded-control bg-status-red-bg text-status-red border border-red-200 p-3 text-sm"
      >
        {message || tx('common.loadError', 'Failed to load')}
      </div>
    );
  }

  // ── Loading state ──────────────────────────────────────────────────────────
  if (dataSourceState?.loading && !dataSourceState.data) {
    return (
      <div
        data-testid="code-snippet-loading"
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
      >
        {tx('common.loading', 'Loading...')}
      </div>
    );
  }

  // ── Empty state (no template configured) ───────────────────────────────────
  if (!template) {
    return (
      <div
        data-testid="code-snippet-empty"
        className="rounded-control border-border bg-panel text-text-2 border p-3 text-sm"
      >
        {tx('common.noData', 'No data')}
      </div>
    );
  }

  const title = cfg.title ? getLocalizedText(cfg.title, locale, t) : null;
  const description = cfg.description ? getLocalizedText(cfg.description, locale, t) : null;
  const language: string | undefined =
    typeof cfg.language === 'string' ? cfg.language : undefined;

  const copyLabel =
    copyState === 'copied'
      ? tx('common.copied', 'Copied')
      : copyState === 'error'
        ? tx('common.copyFailed', 'Copy failed')
        : tx('common.copy', 'Copy');

  return (
    <div
      data-testid="code-snippet-block"
      data-block-type="code-snippet"
      className={`code-snippet-block rounded-card border-border bg-panel border ${block.className || ''}`}
    >
      <div className="border-border flex items-center justify-between gap-3 border-b px-4 py-2">
        <div className="min-w-0">
          {title && (
            <div className="text-text truncate text-sm font-medium" data-testid="code-snippet-title">
              {title}
            </div>
          )}
          {description && (
            <p className="text-text-2 mt-0.5 truncate text-xs" data-testid="code-snippet-description">
              {description}
            </p>
          )}
        </div>
        <div className="flex flex-shrink-0 items-center gap-2">
          {language && (
            <span className="rounded-pill border-border text-text-3 border px-2 py-0.5 text-xs uppercase">
              {language}
            </span>
          )}
          <button
            type="button"
            onClick={handleCopy}
            data-testid="code-snippet-copy"
            aria-label={copyLabel}
            className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover focus:ring-accent/30 inline-flex h-8 items-center gap-1.5 border px-3 text-sm font-medium focus:ring-[0_0_0_3px] focus:outline-none disabled:opacity-50"
          >
            {copyState === 'copied' ? (
              <Check className="text-status-green h-4 w-4" aria-hidden="true" />
            ) : (
              <Copy className="h-4 w-4" aria-hidden="true" />
            )}
            <span data-testid="code-snippet-copy-label">{copyLabel}</span>
          </button>
        </div>
      </div>

      <pre
        data-testid="code-snippet-code"
        className="bg-subtle text-text overflow-x-auto px-4 py-3 font-mono text-xs leading-relaxed whitespace-pre-wrap"
      >
        <code>{snippet}</code>
      </pre>
    </div>
  );
};

export default CodeSnippetBlockRenderer;
