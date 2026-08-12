import type { DslBlockV3, PageSchemaV3 } from '../types';
import { collectBlockIds, createUniqueBlockId, toStableBlockId } from '../utils/blockIds';

/**
 * Pluggable page-template registry for the Unified Designer.
 *
 * A "scenario template" is a named starting point that fills an empty (or
 * to-be-replaced) page with a ready-made block tree — e.g. AuraQR's 巡检 / 报修 /
 * 资产 / 菜单 scan-landing presets. This is the matching extension point to the
 * block / renderer / inspector globals (§7 extend-the-kernel): plugins register
 * templates at boot, the workbench offers them in a picker and applies the chosen
 * one to the current document.
 *
 * `build()` MUST return a fresh block tree on every call (templates are applied
 * repeatedly and the result is then mutated in place by the designer).
 */
export interface PageTemplate {
  /** Stable id used as the picker value. */
  id: string;
  /** Human label (zh-CN). */
  label: string;
  /** Optional grouping hint for the picker. */
  category?: string;
  /** Immutable template revision stamped into non-privileged lineage metadata. */
  version?: string;
  /** Concrete page kinds this template can safely populate. */
  kinds?: PageSchemaV3['kind'][];
  /** Page title applied along with the blocks. */
  title?: PageSchemaV3['title'];
  /** Build a fresh block tree. Called on apply — must return new objects. */
  build: () => DslBlockV3[];
}

export interface PageTemplateLineage {
  templateId: string;
  templateVersion: string;
  sourceBlockId: string;
}

const registry = new Map<string, PageTemplate>();

export function registerPageTemplate(template: PageTemplate): void {
  registry.set(template.id, template);
}

export function getPageTemplates(): PageTemplate[] {
  return Array.from(registry.values());
}

export function getPageTemplate(id: string | null | undefined): PageTemplate | undefined {
  if (!id) return undefined;
  return registry.get(id);
}

/**
 * Materialize one template application with fresh block ids and immutable source lineage.
 * Template ids are never reused as live block ids, even when the same template is applied twice.
 */
export function instantiatePageTemplate(
  template: PageTemplate,
  existingBlocks: DslBlockV3[] = [],
): DslBlockV3[] {
  const usedIds = collectBlockIds(existingBlocks);
  const version = template.version?.trim() || '1';
  const visit = (block: DslBlockV3): DslBlockV3 => {
    const sourceBlockId = block.id || 'block';
    const baseId = toStableBlockId(template.id, sourceBlockId) || 'template_block';
    const id = createUniqueBlockId(baseId, usedIds);
    usedIds.add(id);
    const lineage: PageTemplateLineage = {
      templateId: template.id,
      templateVersion: version,
      sourceBlockId,
    };
    return {
      ...block,
      id,
      extension: {
        ...(block.extension ?? {}),
        authoringTemplateLineage: lineage,
      },
      blocks: block.blocks?.map(visit),
    };
  };
  return template.build().map(visit);
}

/** Test-only: reset registrations between specs (module state is process-global). */
export function clearPageTemplates(): void {
  registry.clear();
}
