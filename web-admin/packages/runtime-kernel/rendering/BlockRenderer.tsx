/**
 * BlockRenderer - Profile-aware block dispatcher (kernel)
 *
 * Resolves block renderers from the current RenderProfile context, then falls
 * back to the host-injected global block resolver (the admin `BlockRegistry`
 * registers itself via `setBlockResolver` at boot — DESIGNER-001). The resolver
 * is the single source of truth for blockType → component mapping; profiles
 * override specific entries at runtime. `custom` blocks render through the
 * host-injected `CustomBlockComponent`. Each block is wrapped in
 * BlockErrorBoundary for crash isolation.
 *
 * The dispatch logic is product-agnostic; the concrete registry + custom loader
 * are injected by the host (see ./blockResolver) so the kernel stays admin-free.
 */

import React, { Suspense, useMemo } from 'react';
import { useProfileSafe } from '../profiles/ProfileContext';
import type { BlockRendererProps } from '../profiles/types';
import { BlockErrorBoundary } from './BlockErrorBoundary';
import { getBlockResolver, getCustomBlockComponent } from './blockResolver';

export type { BlockRendererProps };

export const BlockRenderer: React.FC<BlockRendererProps> = ({
  block,
  runtime,
  areaId: _areaId,
}) => {
  const context = runtime.getContext();
  const profile = useProfileSafe();

  // Conditional rendering via visibleWhen expression
  const evaluator = runtime.getEvaluator();
  const visible = useMemo(() => {
    if (!block.visibleWhen) return true;
    return evaluator.evaluateCondition(block.visibleWhen, context);
  }, [block.visibleWhen, context, evaluator]);

  if (!visible) return null;

  const blockType = block.blockType;

  // Guard: reject renamed blockType aliases with a clear error so developers
  // notice immediately instead of getting a silent fallback or mystery render.
  // Renamed 2026-03-30 as part of page-type-unification (V2 flat format).
  const DEPRECATED_BLOCK_TYPE_ALIASES: Record<string, string> = {
    'data-table': 'table',
    'filter-form': 'filters',
    'list-tabs': 'tabs',
    'toolbar-buttons': 'toolbar',
    action: 'custom',
  };
  if (blockType in DEPRECATED_BLOCK_TYPE_ALIASES) {
    throw new Error(
      `[BlockRenderer] blockType "${blockType}" was renamed to "${DEPRECATED_BLOCK_TYPE_ALIASES[blockType]}" since 2026-03-30. ` +
        `Update your DSL JSON to use the new name.`,
    );
  }

  // Resolve renderer: profile-aware → BlockRegistry
  const resolveRenderer = (): React.ComponentType<any> | null => {
    // 1. Profile override wins (per-route or per-tenant customization)
    if (profile) {
      const Renderer = profile.blockRenderers.get(blockType);
      if (Renderer) return Renderer;
    }

    // 2. Host-injected global block resolver (DESIGNER-001 single source of truth)
    const spec = getBlockResolver()?.get(blockType);
    if (spec) return spec.component;

    // 3. `custom` is handled below; structural types (`monthly-grid`) are
    //    handled by enclosing page renderers (detail / list).
    return null;
  };

  const Renderer = resolveRenderer();

  // Custom block rendering
  if (blockType === 'custom') {
    if (!block.component) {
      return (
        <BlockErrorBoundary blockType={blockType} blockId={block.id}>
          <div className="border-status-red bg-status-red-bg rounded border p-4">
            <p className="text-red-800">Custom block missing component</p>
          </div>
        </BlockErrorBoundary>
      );
    }

    const CustomBlock = getCustomBlockComponent();
    return (
      <BlockErrorBoundary blockType={blockType} blockId={block.id}>
        <div className={`block-${blockType} ${block.className || ''}`}>
          {CustomBlock ? (
            <Suspense fallback={<div className="bg-muted h-24 animate-pulse rounded" />}>
              <CustomBlock componentName={block.component} props={{ block, runtime }} />
            </Suspense>
          ) : (
            <div className="border-status-red bg-status-red-bg rounded border p-4">
              <p className="text-red-800">Custom block loader not registered</p>
            </div>
          )}
        </div>
      </BlockErrorBoundary>
    );
  }

  // Block types handled by enclosing page renderers (not BlockRenderer)
  if (!Renderer) {
    if (blockType === 'monthly-grid') {
      return null;
    }

    // Unknown block type — visible warning, NEVER silent null. Missing
    // registrations must surface immediately in dev (memory:
    // feedback_g1_init_registry_bootstrap).
    console.warn(`[BlockRenderer] Unknown block type: ${blockType}`);
    return (
      <div className="bg-status-amber-bg rounded border border-yellow-300 p-4">
        <p className="text-yellow-800">Unknown block type: {blockType}</p>
      </div>
    );
  }

  return (
    <BlockErrorBoundary blockType={blockType} blockId={block.id}>
      <Suspense fallback={<div className="bg-muted h-24 animate-pulse rounded" />}>
        <div className={`block-${blockType} ${block.className || ''}`}>
          <Renderer block={block} runtime={runtime} />
        </div>
      </Suspense>
    </BlockErrorBoundary>
  );
};

export default BlockRenderer;
