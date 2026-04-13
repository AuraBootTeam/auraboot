/**
 * CompositePageContent — Runtime renderer for kind='composite' pages
 *
 * Iterates the blocks array from the schema and delegates each block
 * to the registered block renderer from the current profile.
 *
 * Uses LayoutRenderer for grid/stack layout support.
 * Each block is responsible for its own data fetching via its renderer.
 */

import React, { Suspense, useCallback } from 'react';
import { useProfile } from '~/framework/meta/profiles/ProfileContext';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { LayoutRenderer } from '~/framework/meta/rendering/layout';
import type { PageContentProps } from '~/framework/meta/profiles/types';

export function CompositePageContent({ schema }: PageContentProps) {
  const profile = useProfile();

  const blocks: any[] = schema?.blocks ?? [];

  const renderBlock = useCallback((block: any) => {
    const blockType: string = block.blockType ?? block.type ?? '';
    const BlockRenderer = profile.blockRenderers.get(blockType);

    if (!BlockRenderer) {
      return (
        <div
          style={{
            padding: '12px 16px',
            background: '#fffbe6',
            border: '1px solid #ffe58f',
            borderRadius: 4,
            color: '#614700',
          }}
          data-testid={`composite-unknown-block-${blockType}`}
        >
          <strong>Unknown block type: &quot;{blockType}&quot;</strong>
          <p style={{ margin: '4px 0 0' }}>
            No renderer registered for blockType &quot;{blockType}&quot; in profile &quot;
            {profile.name}&quot;.
          </p>
        </div>
      );
    }

    return (
      <Suspense fallback={<LoadingSpinner />}>
        <BlockRenderer block={block} runtime={schema} />
      </Suspense>
    );
  }, [profile, schema]);

  if (blocks.length === 0) {
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: 200,
          color: '#8c8c8c',
        }}
        data-testid="composite-page-empty"
      >
        No blocks configured for this page.
      </div>
    );
  }

  // Resolve title from schema — may be a string or LocalizedText object
  const rawTitle = schema?.title;
  const resolvedTitle: string | undefined = (() => {
    if (!rawTitle) return undefined;
    if (typeof rawTitle === 'string') return rawTitle;
    if (typeof rawTitle === 'object') {
      // LocalizedText: { "zh-CN": "...", "en-US": "..." } — pick first available
      const values = Object.values(rawTitle) as string[];
      return values[0] || undefined;
    }
    return undefined;
  })();

  return (
    <div data-testid="composite-page-content">
      {resolvedTitle && (
        <h1
          data-testid="composite-page-title"
          style={{ fontSize: 24, fontWeight: 600, margin: '0 0 8px 0', color: '#1f2937' }}
        >
          {resolvedTitle}
        </h1>
      )}
      <LayoutRenderer
        layout={schema.layout}
        blocks={blocks}
        renderBlock={renderBlock}
      />
    </div>
  );
}

export default CompositePageContent;
