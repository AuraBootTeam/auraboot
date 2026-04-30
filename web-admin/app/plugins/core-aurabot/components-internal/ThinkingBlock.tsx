/**
 * ThinkingBlock
 *
 * Renders the Anthropic Extended Thinking trace returned alongside an
 * assistant message. The block is collapsed by default — most users do not
 * want to read the full chain-of-thought, but power users / debug sessions
 * benefit from being able to expand it.
 *
 * Visual style intentionally mirrors {@link ToolResultCard} (gray border,
 * rounded card, chevron toggle) so the chat surface stays consistent.
 *
 * @since P0-2
 */

import { useState } from 'react';
import { ChevronDown, ChevronRight, Sparkles } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';

// ============================================================================
// Types
// ============================================================================

export interface ThinkingBlockProps {
  /** Raw chain-of-thought prose returned by Anthropic. */
  content: string;
  /**
   * Optional explicit token count for the header. When omitted the component
   * derives a rough word-count estimate so we always show *something* useful.
   */
  tokens?: number;
  /**
   * Initial collapsed state. Defaults to {@code true}: most callers want the
   * cheap-to-render version of the message and only expand on demand.
   */
  initiallyCollapsed?: boolean;
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * Cheap token estimate — Anthropic counts ~0.75 words per token in English,
 * so word-count × 1.3 is a reasonable upper bound when the server didn't
 * provide a precise count. Used purely for the header label, never for
 * billing or guard logic.
 */
function estimateTokens(content: string): number {
  if (!content) return 0;
  const words = content.trim().split(/\s+/).filter(Boolean).length;
  return Math.max(1, Math.round(words * 1.3));
}

// ============================================================================
// Component
// ============================================================================

export function ThinkingBlock({ content, tokens, initiallyCollapsed = true }: ThinkingBlockProps) {
  const [collapsed, setCollapsed] = useState(initiallyCollapsed);
  const tokenCount = tokens ?? estimateTokens(content);
  const { t } = useI18n();
  // i18n keys live in seed/i18n-base.json; the {count} placeholder is filled
  // via I18nContext.translate's {var}-style param substitution. Falls back to
  // zh-CN copy when the bundle has not loaded yet (e.g. SSR cold start).
  const labelText = t('aurabot.thinking.label', undefined, '推理过程');
  const tokensText = t('aurabot.thinking.tokens', { count: tokenCount }, `${tokenCount} tokens`);

  return (
    <div className="mb-3 flex justify-start" data-testid="thinking-block">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-gray-200 bg-gray-50 shadow-sm dark:border-gray-600 dark:bg-gray-800/50">
        <button
          type="button"
          onClick={() => setCollapsed((v) => !v)}
          aria-expanded={!collapsed}
          aria-controls="thinking-block-content"
          className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-gray-100 dark:hover:bg-gray-700/50"
          data-testid="thinking-block-toggle"
        >
          <Sparkles className="h-4 w-4 flex-shrink-0 text-purple-500" aria-hidden="true" />
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {labelText} ({tokensText})
          </span>
          <span className="ml-auto text-gray-400" aria-hidden="true">
            {collapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </span>
        </button>
        {!collapsed && (
          <div
            id="thinking-block-content"
            data-testid="thinking-block-content"
            className="border-t border-gray-200 bg-white px-3 py-2 text-xs text-gray-600 dark:border-gray-600 dark:bg-gray-900 dark:text-gray-400"
          >
            {/*
             * Minor 15: thinking content is natural language prose (sentences,
             * not code). font-sans matches the rest of the chat surface and
             * reads more naturally than font-mono. whitespace-pre-wrap is
             * preserved so the model's intentional line breaks survive.
             */}
            <pre className="whitespace-pre-wrap break-words font-sans leading-relaxed">{content}</pre>
          </div>
        )}
      </div>
    </div>
  );
}

export default ThinkingBlock;
