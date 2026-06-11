import type { BlockDefinitionV3, PageSchemaV3Kind } from '../types';

/**
 * Pluggable custom-block registry for the Unified Designer.
 *
 * The built-in block palette (`createDefaultBlockRegistryV3`) and the per-kind
 * policy (`kindPolicy`) are otherwise closed sets. This module is the single
 * extension point that lets enterprise/overlay plugins contribute their own
 * designer blocks at boot — mirroring the runtime `app/ui/schema-renderer`
 * BlockRegistry global and the InspectorSchemaRegistry global, so a plugin
 * never has to fork the core factory (§7 Designer-kernel-first: extend, don't
 * reinvent).
 *
 * Register at module/boot time (e.g. from a plugin's `setup()`); registration
 * is keyed by `blockType` and idempotent. A registered block:
 *   1. appears in the designer palette + gets an inspector (via the def);
 *   2. becomes a valid child of any `allowedParents` (so it is droppable);
 *   3. is offered for the `allowedKinds` page kinds (default: all concrete kinds).
 */
export interface CustomDesignerBlockOptions {
  /**
   * Existing parent blockTypes whose `allowedChildren` should include this block,
   * so it can be dropped/nested there. Omit for a block only used at the page root
   * of a `composite` page.
   */
  allowedParents?: string[];
  /**
   * Page kinds whose palette offers this block. Omit or empty = all concrete kinds
   * (form/list/detail/dashboard); `composite` already allows everything.
   */
  allowedKinds?: PageSchemaV3Kind[];
}

interface CustomDesignerBlockEntry {
  definition: BlockDefinitionV3;
  options: CustomDesignerBlockOptions;
}

const registry = new Map<string, CustomDesignerBlockEntry>();

export function registerCustomDesignerBlock(
  definition: BlockDefinitionV3,
  options: CustomDesignerBlockOptions = {},
): void {
  registry.set(definition.blockType, { definition, options });
}

export function getCustomDesignerBlockDefinitions(): BlockDefinitionV3[] {
  return Array.from(registry.values(), (entry) => entry.definition);
}

export function getCustomDesignerBlockEntries(): CustomDesignerBlockEntry[] {
  return Array.from(registry.values());
}

/** Whether a plugin-contributed block opted into the given page kind. */
export function isCustomBlockAllowedForKind(kind: PageSchemaV3Kind, blockType: string): boolean {
  const entry = registry.get(blockType);
  if (!entry) return false;
  const kinds = entry.options.allowedKinds;
  return !kinds || kinds.length === 0 || kinds.includes(kind);
}

/** Test-only: reset registrations between specs (module state is process-global). */
export function clearCustomDesignerBlocks(): void {
  registry.clear();
}
