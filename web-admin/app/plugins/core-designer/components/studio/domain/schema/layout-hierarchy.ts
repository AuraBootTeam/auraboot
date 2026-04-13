/**
 * Layout Hierarchy Types
 *
 * Four-level hierarchy: TAB → Floor → Block → Field
 * Provides structured form layouts with collapsible sections,
 * tabbed navigation, and grid-based field placement.
 *
 * @since 3.2.0
 */

/**
 * Top-level tab container configuration.
 * Renders as a tabbed interface with each tab containing floors.
 */
export interface TabContainerConfig {
  type: 'tab-container';
  tabs: TabItemConfig[];
  activeTab?: string;
  position?: 'top' | 'left' | 'bottom';
  variant?: 'default' | 'pills' | 'underline';
}

/**
 * A single tab within the container.
 */
export interface TabItemConfig {
  id: string;
  code: string;
  label: string;
  icon?: string;
  floors: FloorConfig[];
  visible?: string; // SpEL expression for conditional visibility
}

/**
 * Floor (section) within a tab.
 * Represents a logical grouping with optional collapse.
 */
export interface FloorConfig {
  id: string;
  code: string;
  title?: string;
  description?: string;
  collapsible?: boolean;
  collapsed?: boolean;
  blocks: BlockConfig[];
  visible?: string;
}

/**
 * Block (region) within a floor.
 * Contains field cells arranged in a grid/flex layout.
 */
export interface BlockConfig {
  id: string;
  code: string;
  title?: string;
  layout: BlockLayoutConfig;
  fields: FieldCellConfig[];
  visible?: string;
}

export interface BlockLayoutConfig {
  type: 'grid' | 'flex' | 'stack';
  columns?: number;
  gap?: number;
  direction?: 'row' | 'column';
}

/**
 * Field cell within a block.
 * Maps a MetaField to a Smart Component with layout props.
 */
export interface FieldCellConfig {
  id: string;
  fieldCode: string; // Associated MetaField code
  componentType: string; // Smart Component type
  label?: string; // Override display label
  span?: number; // Grid column span
  props: Record<string, any>;
  visible?: string; // Visibility expression
  readonly?: string; // Readonly expression
  required?: string; // Required expression
}

/**
 * Hierarchy selection state for the designer.
 */
export interface HierarchySelection {
  tabId?: string;
  floorId?: string;
  blockId?: string;
  fieldId?: string;
}

/**
 * Default hierarchy configuration for new pages.
 */
export const DEFAULT_HIERARCHY: TabContainerConfig = {
  type: 'tab-container',
  tabs: [
    {
      id: 'tab-default',
      code: 'main',
      label: '基本信息',
      floors: [
        {
          id: 'floor-default',
          code: 'basic',
          title: '基本信息',
          collapsible: false,
          blocks: [
            {
              id: 'block-default',
              code: 'fields',
              layout: { type: 'grid', columns: 2, gap: 16 },
              fields: [],
            },
          ],
        },
      ],
    },
  ],
  position: 'top',
  variant: 'default',
};

/**
 * Utility: generate a unique ID for hierarchy nodes.
 */
export function generateHierarchyId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}

/**
 * Utility: create default configs for each hierarchy level.
 */
export function createDefaultTab(label: string): TabItemConfig {
  const id = generateHierarchyId('tab');
  return {
    id,
    code: id,
    label,
    floors: [createDefaultFloor('默认楼层')],
  };
}

export function createDefaultFloor(title: string): FloorConfig {
  const id = generateHierarchyId('floor');
  return {
    id,
    code: id,
    title,
    collapsible: true,
    collapsed: false,
    blocks: [createDefaultBlock()],
  };
}

export function createDefaultBlock(): BlockConfig {
  const id = generateHierarchyId('block');
  return {
    id,
    code: id,
    layout: { type: 'grid', columns: 2, gap: 16 },
    fields: [],
  };
}

export function createFieldCell(
  fieldCode: string,
  componentType: string,
  props: Record<string, any> = {},
): FieldCellConfig {
  const id = generateHierarchyId('field');
  return {
    id,
    fieldCode,
    componentType,
    span: 1,
    props,
  };
}
