import type { DslBlockV3, PageSchemaV3 } from '../types';

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
  /** Page title applied along with the blocks. */
  title?: PageSchemaV3['title'];
  /** Build a fresh block tree. Called on apply — must return new objects. */
  build: () => DslBlockV3[];
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

/** Test-only: reset registrations between specs (module state is process-global). */
export function clearPageTemplates(): void {
  registry.clear();
}
