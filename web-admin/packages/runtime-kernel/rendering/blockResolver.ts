/**
 * Block resolution injection — keeps the kernel BlockRenderer dispatcher
 * product-agnostic.
 *
 * The dispatcher resolves a blockType in this order:
 *   1. the current RenderProfile's `blockRenderers` (profile override)
 *   2. the host-provided global block resolver (fallback registry)
 *   3. the host-provided custom-block component (for `blockType: 'custom'`)
 *
 * Steps 2 and 3 are concrete admin/product implementations (the global
 * `BlockRegistry`, the `ComponentLoader`) that must NOT be imported by the
 * kernel. The host app injects them at boot (see `initBlockRegistry` in
 * auraboot-app). This mirrors how the rest of the kernel works: the engine is
 * unified, the content is registered by the host.
 */

import type { ComponentType } from 'react';

/** A resolved block renderer entry — only `.component` is consumed here. */
export interface KernelBlockSpec {
  component: ComponentType<any>;
}

/** Global fallback registry: blockType → renderer. The admin `BlockRegistry`
 *  satisfies this structurally. */
export interface KernelBlockResolver {
  get(blockType: string): KernelBlockSpec | undefined;
}

/** Component used to render `blockType: 'custom'` blocks by name. The admin
 *  `ComponentLoader` satisfies this; injected lazily so it stays out of the
 *  entry chunk. */
export type CustomBlockComponent = ComponentType<{
  componentName: string;
  props: Record<string, unknown>;
}>;

let globalBlockResolver: KernelBlockResolver | null = null;
let customBlockComponent: CustomBlockComponent | null = null;

/** Host wires its global block registry here at boot. Idempotent. */
export function setBlockResolver(resolver: KernelBlockResolver): void {
  globalBlockResolver = resolver;
}

export function getBlockResolver(): KernelBlockResolver | null {
  return globalBlockResolver;
}

/** Host wires its custom-block loader component here at boot. Idempotent. */
export function setCustomBlockComponent(component: CustomBlockComponent): void {
  customBlockComponent = component;
}

export function getCustomBlockComponent(): CustomBlockComponent | null {
  return customBlockComponent;
}
