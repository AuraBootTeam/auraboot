/**
 * Layout Preset Applicator.
 *
 * Applies layout presets to hierarchy configurations,
 * updating block columns and field spans.
 *
 * @since 3.2.0
 */

import type {
  TabContainerConfig,
  BlockConfig,
  FieldCellConfig,
} from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import type { LayoutPreset } from '~/plugins/core-designer/components/studio/domain/schema/layout-presets';
import type { ResolvedField } from '~/plugins/core-designer/components/studio/domain/viewmodel/types';
import { DATA_TYPE_COMPONENT_MAP } from '~/plugins/core-designer/components/studio/workbench/panels/fields/types';

/**
 * Apply a layout preset to an existing hierarchy.
 * Updates all blocks' column count and recalculates field spans.
 */
export function applyLayoutPreset(
  hierarchy: TabContainerConfig,
  preset: LayoutPreset,
): TabContainerConfig {
  const columns = preset.formLayout.columns;

  return {
    ...hierarchy,
    tabs: hierarchy.tabs.map((tab) => ({
      ...tab,
      floors: tab.floors.map((floor) => ({
        ...floor,
        blocks: floor.blocks.map((block) => applyPresetToBlock(block, columns)),
      })),
    })),
  };
}

/**
 * Apply column count to a single block and recalculate field spans.
 */
function applyPresetToBlock(block: BlockConfig, columns: number): BlockConfig {
  return {
    ...block,
    layout: {
      ...block.layout,
      columns,
      gap: block.layout.gap ?? 16,
    },
    fields: block.fields.map((field) => ({
      ...field,
      span: calculateFieldSpan(field, columns),
    })),
  };
}

/**
 * Calculate the span for a field based on its component type and available columns.
 * Long fields (textarea, json) take full width; others take 1 column.
 */
function calculateFieldSpan(field: FieldCellConfig, columns: number): number {
  const wideTypes = new Set(['textarea', 'rich-text', 'json-editor', 'code-editor']);
  if (wideTypes.has(field.componentType)) {
    return columns; // Full width
  }
  return 1; // Single column
}

/**
 * Generate an initial hierarchy from resolved fields and a layout preset.
 * Creates a single-tab, single-floor, single-block structure.
 */
export function generateInitialHierarchy(
  fields: ResolvedField[],
  preset: LayoutPreset,
): TabContainerConfig {
  const columns = preset.formLayout.columns;

  const fieldCells: FieldCellConfig[] = fields
    .filter((f) => f.visible !== false)
    .map((field, index) => {
      const componentType = resolveComponentType(field);
      const wideTypes = new Set(['textarea', 'rich-text', 'json-editor', 'code-editor']);
      const span = wideTypes.has(componentType) ? columns : 1;

      return {
        id: `field-${index}`,
        fieldCode: field.code,
        componentType,
        label: field.displayName || field.code,
        span,
        props: buildFieldProps(field, componentType),
      };
    });

  return {
    type: 'tab-container',
    tabs: [
      {
        id: 'tab-main',
        code: 'main',
        label: 'Main',
        floors: [
          {
            id: 'floor-main',
            code: 'main',
            title: 'Fields',
            collapsible: false,
            blocks: [
              {
                id: 'block-main',
                code: 'main',
                layout: {
                  type: 'grid',
                  columns,
                  gap: preset.formLayout.fieldSpacing,
                },
                fields: fieldCells,
              },
            ],
          },
        ],
      },
    ],
  };
}

/**
 * Resolve the Smart Component type from a ResolvedField.
 */
function resolveComponentType(field: ResolvedField): string {
  if (field.uiHint?.componentType) {
    return field.uiHint.componentType as string;
  }
  const mapping = DATA_TYPE_COMPONENT_MAP[field.dataType?.toUpperCase() || 'string'];
  return mapping?.type || 'input';
}

/**
 * Build default props for a field component.
 */
function buildFieldProps(field: ResolvedField, componentType: string): Record<string, any> {
  const props: Record<string, any> = {};

  if (field.required) props.required = true;
  if (field.editable === false) props.disabled = true;

  const mapping = DATA_TYPE_COMPONENT_MAP[field.dataType?.toUpperCase() || 'string'];
  if (mapping?.defaultProps) {
    Object.assign(props, mapping.defaultProps);
  }

  return props;
}
