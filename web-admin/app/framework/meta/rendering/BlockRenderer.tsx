/**
 * BlockRenderer - Profile-aware block dispatcher
 *
 * Resolves block renderers from the current RenderProfile context.
 * Falls back to the hardcoded switch-case for backward compatibility
 * when no profile is available (e.g., route components that haven't
 * been migrated to DynamicPageRenderer yet).
 *
 * Each block is wrapped in BlockErrorBoundary for crash isolation.
 */

import React, { Suspense, useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { useProfileSafe } from '~/framework/meta/profiles/ProfileContext';
import { BlockErrorBoundary } from '~/framework/meta/rendering/BlockErrorBoundary';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';

/**
 * Fallback renderer map — used when no profile context is available.
 * Lazily initialized via dynamic import to avoid pulling block renderers
 * into the main bundle when the profile path (primary) is used.
 */
let _fallbackRenderers: Map<string, React.ComponentType<any>> | null = null;
async function loadFallbackRenderers(): Promise<Map<string, React.ComponentType<any>>> {
  if (_fallbackRenderers) return _fallbackRenderers;
  const [
    form,
    formSection,
    formButtons,
    formWizard,
    table,
    filters,
    toolbar,
    description,
    chart,
    tabs,
    approvalComments,
  ] = await Promise.all([
    import('~/framework/meta/rendering/blocks/FormBlockRenderer'),
    import('~/framework/meta/rendering/blocks/FormSectionBlockRenderer'),
    import('~/framework/meta/rendering/blocks/FormButtonsBlockRenderer'),
    import('~/framework/meta/rendering/blocks/FormWizardBlockRenderer'),
    import('~/framework/meta/rendering/blocks/TableBlockRenderer'),
    import('~/framework/meta/rendering/blocks/FiltersBlockRenderer'),
    import('~/framework/meta/rendering/blocks/ToolbarBlockRenderer'),
    import('~/framework/meta/rendering/blocks/DescriptionBlockRenderer'),
    import('~/framework/meta/rendering/blocks/ChartBlockRenderer'),
    import('~/framework/meta/rendering/blocks/TabsBlockRenderer'),
    import('~/framework/meta/rendering/blocks/ApprovalCommentsBlock'),
  ]);
  _fallbackRenderers = new Map([
    ['form', form.FormBlockRenderer],
    ['form-section', formSection.FormSectionBlockRenderer],
    ['form-buttons', formButtons.FormButtonsBlockRenderer],
    ['form-wizard', formWizard.FormWizardBlockRenderer],
    ['table', table.TableBlockRenderer],
    ['filters', filters.FiltersBlockRenderer],
    ['toolbar', toolbar.ToolbarBlockRenderer],
    ['action', toolbar.ToolbarBlockRenderer],
    ['description', description.DescriptionBlockRenderer],
    ['chart', chart.ChartBlockRenderer],
    ['tabs', tabs.TabsBlockRenderer],
    ['approval-comments', approvalComments.ApprovalCommentsBlock as any],
  ]);
  return _fallbackRenderers;
}

function getFallbackRenderers(): Map<string, React.ComponentType<any>> {
  if (!_fallbackRenderers) {
    // Trigger async load; will be available on next render
    loadFallbackRenderers();
    return new Map();
  }
  return _fallbackRenderers;
}

export interface BlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
  areaId: string;
}

export const BlockRenderer: React.FC<BlockRendererProps> = ({ block, runtime, areaId }) => {
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

  // Resolve renderer: profile-aware → fallback map
  const resolveRenderer = (): React.ComponentType<any> | null => {
    // 1. Try profile's blockRenderers
    if (profile) {
      const Renderer = profile.blockRenderers.get(blockType);
      if (Renderer) return Renderer;
    }

    // 2. Try fallback map (lazy to avoid circular dep TDZ)
    const FallbackRenderer = getFallbackRenderers().get(blockType);
    if (FallbackRenderer) return FallbackRenderer;

    // 3. Special block types handled by page renderers
    if (blockType === 'tabs' || blockType === 'sub-table' || blockType === 'monthly-grid') {
      return null;
    }

    // 4. Custom blocks
    if (blockType === 'custom') {
      return null; // handled separately below
    }

    return null;
  };

  const Renderer = resolveRenderer();

  // Custom block rendering
  if (blockType === 'custom') {
    if (!block.component) {
      return (
        <BlockErrorBoundary blockType={blockType} blockId={block.id}>
          <div className="rounded border border-red-300 bg-red-50 p-4">
            <p className="text-red-800">Custom block missing component</p>
          </div>
        </BlockErrorBoundary>
      );
    }

    return (
      <BlockErrorBoundary blockType={blockType} blockId={block.id}>
        <div className={`block-${blockType} ${block.className || ''}`}>
          <ComponentLoader componentName={block.component} props={{ block, runtime }} />
        </div>
      </BlockErrorBoundary>
    );
  }

  // Block types handled by page renderers (not BlockRenderer)
  if (!Renderer) {
    if (blockType === 'tabs' || blockType === 'sub-table' || blockType === 'monthly-grid') {
      return null;
    }

    // Unknown block type
    console.warn(`[BlockRenderer] Unknown block type: ${blockType}`);
    return (
      <div className="rounded border border-yellow-300 bg-yellow-50 p-4">
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
