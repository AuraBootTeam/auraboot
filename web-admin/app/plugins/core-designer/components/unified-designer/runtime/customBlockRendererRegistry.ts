import type React from 'react';
import type { DslBlockV3 } from '../types';

/**
 * Pluggable runtime/preview renderer registry for Unified Designer custom blocks.
 *
 * `customBlockRegistry` lets a plugin contribute a block *definition* (palette
 * entry + inspector + droppability). This module is the matching extension point
 * for the block's *runtime React renderer*: by default `RecursiveBlockRenderer`
 * falls a custom blockType through to the generic `RuntimeContainer` (a titled
 * box with children), which is fine for layout/data blocks but cannot show a
 * live widget (e.g. a scannability score that updates as props change).
 *
 * Register a renderer at module/boot time (e.g. from a plugin's block-setup
 * module, alongside `registerCustomDesignerBlock`); registration is keyed by
 * `blockType` and idempotent (last writer wins). A registered renderer fully
 * replaces the generic container for that blockType in the runtime preview —
 * mirroring the runtime `app/ui/schema-renderer` BlockRegistry global and the
 * `customBlockRegistry` definition global (§7 Designer-kernel-first: extend the
 * kernel through a global, don't fork the renderer switch).
 *
 * Renderers are pure presentational components driven by `block.props`; they do
 * not receive runtime data services (custom blocks that need server data should
 * use a built-in helper block). This keeps them safe to render in jsdom tests.
 */
export interface CustomBlockRendererProps {
  block: DslBlockV3;
}

const registry = new Map<string, React.ComponentType<CustomBlockRendererProps>>();

export function registerCustomBlockRenderer(
  blockType: string,
  component: React.ComponentType<CustomBlockRendererProps>,
): void {
  registry.set(blockType, component);
}

export function getCustomBlockRenderer(
  blockType: string,
): React.ComponentType<CustomBlockRendererProps> | undefined {
  return registry.get(blockType);
}

/** Test-only: reset registrations between specs (module state is process-global). */
export function clearCustomBlockRenderers(): void {
  registry.clear();
}
