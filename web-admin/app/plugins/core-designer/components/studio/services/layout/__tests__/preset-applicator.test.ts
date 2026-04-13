import { describe, it, expect } from 'vitest';
import { applyLayoutPreset, generateInitialHierarchy } from '../preset-applicator';
import { FORM_LAYOUT_PRESETS, getPresetByCode } from '~/plugins/core-designer/components/studio/domain/schema/layout-presets';
import type { TabContainerConfig } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';
import type { LayoutPreset } from '~/plugins/core-designer/components/studio/domain/schema/layout-presets';
import type { ResolvedField } from '~/plugins/core-designer/components/studio/domain/viewmodel/types';

const twoColumnPreset = getPresetByCode('two-column')!;
const threeColumnPreset = getPresetByCode('three-column')!;
const singleColumnPreset = getPresetByCode('single-column')!;

function createHierarchy(
  fields: Array<{ id: string; fieldCode: string; componentType: string; span?: number }>,
): TabContainerConfig {
  return {
    type: 'tab-container',
    tabs: [
      {
        id: 'tab-1',
        code: 'main',
        label: 'Main',
        floors: [
          {
            id: 'floor-1',
            code: 'basic',
            blocks: [
              {
                id: 'block-1',
                code: 'fields',
                layout: { type: 'grid', columns: 2, gap: 16 },
                fields: fields.map((f) => ({
                  id: f.id,
                  fieldCode: f.fieldCode,
                  componentType: f.componentType,
                  span: f.span ?? 1,
                  props: {},
                })),
              },
            ],
          },
        ],
      },
    ],
  };
}

function createResolvedField(overrides: Partial<ResolvedField> = {}): ResolvedField {
  return {
    code: 'field1',
    displayName: 'Field 1',
    dataType: 'string',
    sourceType: 'field_binding',
    ...overrides,
  };
}

describe('applyLayoutPreset', () => {
  it('should update block columns to match preset', () => {
    const hierarchy = createHierarchy([{ id: 'f1', fieldCode: 'name', componentType: 'input' }]);

    const result = applyLayoutPreset(hierarchy, threeColumnPreset);

    expect(result.tabs[0].floors[0].blocks[0].layout.columns).toBe(3);
  });

  it('should preserve existing block gap if set', () => {
    const hierarchy = createHierarchy([]);
    hierarchy.tabs[0].floors[0].blocks[0].layout.gap = 24;

    const result = applyLayoutPreset(hierarchy, twoColumnPreset);

    expect(result.tabs[0].floors[0].blocks[0].layout.gap).toBe(24);
  });

  it('should default gap to 16 if not set', () => {
    const hierarchy = createHierarchy([]);
    delete hierarchy.tabs[0].floors[0].blocks[0].layout.gap;

    const result = applyLayoutPreset(hierarchy, twoColumnPreset);

    expect(result.tabs[0].floors[0].blocks[0].layout.gap).toBe(16);
  });

  it('should set textarea fields to full width (columns)', () => {
    const hierarchy = createHierarchy([
      { id: 'f1', fieldCode: 'name', componentType: 'input', span: 1 },
      { id: 'f2', fieldCode: 'desc', componentType: 'textarea', span: 1 },
    ]);

    const result = applyLayoutPreset(hierarchy, threeColumnPreset);
    const fields = result.tabs[0].floors[0].blocks[0].fields;

    expect(fields[0].span).toBe(1);
    expect(fields[1].span).toBe(3); // textarea gets full width
  });

  it('should set rich-text and json-editor to full width', () => {
    const hierarchy = createHierarchy([
      { id: 'f1', fieldCode: 'content', componentType: 'rich-text', span: 1 },
      { id: 'f2', fieldCode: 'config', componentType: 'json-editor', span: 1 },
      { id: 'f3', fieldCode: 'code', componentType: 'code-editor', span: 1 },
    ]);

    const result = applyLayoutPreset(hierarchy, twoColumnPreset);
    const fields = result.tabs[0].floors[0].blocks[0].fields;

    expect(fields[0].span).toBe(2);
    expect(fields[1].span).toBe(2);
    expect(fields[2].span).toBe(2);
  });

  it('should apply to all tabs and floors', () => {
    const hierarchy: TabContainerConfig = {
      type: 'tab-container',
      tabs: [
        {
          id: 'tab-1',
          code: 'tab1',
          label: 'Tab 1',
          floors: [
            {
              id: 'floor-1',
              code: 'f1',
              blocks: [
                {
                  id: 'block-1',
                  code: 'b1',
                  layout: { type: 'grid', columns: 2, gap: 16 },
                  fields: [
                    { id: 'f1', fieldCode: 'name', componentType: 'input', span: 1, props: {} },
                  ],
                },
              ],
            },
          ],
        },
        {
          id: 'tab-2',
          code: 'tab2',
          label: 'Tab 2',
          floors: [
            {
              id: 'floor-2',
              code: 'f2',
              blocks: [
                {
                  id: 'block-2',
                  code: 'b2',
                  layout: { type: 'grid', columns: 2, gap: 16 },
                  fields: [
                    { id: 'f2', fieldCode: 'email', componentType: 'input', span: 1, props: {} },
                  ],
                },
              ],
            },
          ],
        },
      ],
    };

    const result = applyLayoutPreset(hierarchy, threeColumnPreset);

    expect(result.tabs[0].floors[0].blocks[0].layout.columns).toBe(3);
    expect(result.tabs[1].floors[0].blocks[0].layout.columns).toBe(3);
  });

  it('should not mutate the original hierarchy', () => {
    const hierarchy = createHierarchy([{ id: 'f1', fieldCode: 'name', componentType: 'input' }]);
    const originalColumns = hierarchy.tabs[0].floors[0].blocks[0].layout.columns;

    applyLayoutPreset(hierarchy, threeColumnPreset);

    expect(hierarchy.tabs[0].floors[0].blocks[0].layout.columns).toBe(originalColumns);
  });
});

describe('generateInitialHierarchy', () => {
  it('should create a valid tab-container structure', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', displayName: 'Name', dataType: 'string' }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);

    expect(result.type).toBe('tab-container');
    expect(result.tabs).toHaveLength(1);
    expect(result.tabs[0].floors).toHaveLength(1);
    expect(result.tabs[0].floors[0].blocks).toHaveLength(1);
  });

  it('should create field cells from resolved fields', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', displayName: 'Name', dataType: 'string' }),
      createResolvedField({ code: 'email', displayName: 'Email', dataType: 'email' }),
      createResolvedField({ code: 'age', displayName: 'Age', dataType: 'integer' }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells).toHaveLength(3);
    expect(fieldCells[0].fieldCode).toBe('name');
    expect(fieldCells[0].label).toBe('Name');
    expect(fieldCells[1].fieldCode).toBe('email');
    expect(fieldCells[2].fieldCode).toBe('age');
  });

  it('should set columns from preset', () => {
    const fields: ResolvedField[] = [createResolvedField({ code: 'name', dataType: 'string' })];

    const result = generateInitialHierarchy(fields, threeColumnPreset);
    const block = result.tabs[0].floors[0].blocks[0];

    expect(block.layout.columns).toBe(3);
  });

  it('should set gap from preset fieldSpacing', () => {
    const fields: ResolvedField[] = [createResolvedField({ code: 'name', dataType: 'string' })];

    const result = generateInitialHierarchy(fields, threeColumnPreset);
    const block = result.tabs[0].floors[0].blocks[0];

    expect(block.layout.gap).toBe(12); // three-column preset has fieldSpacing: 12
  });

  it('should filter out invisible fields', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', visible: true }),
      createResolvedField({ code: 'hidden', visible: false }),
      createResolvedField({ code: 'email' }), // visible defaults to undefined (included)
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells).toHaveLength(2);
    expect(fieldCells.map((f) => f.fieldCode)).toEqual(['name', 'email']);
  });

  it('should resolve component type from dataType', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', dataType: 'string' }),
      createResolvedField({ code: 'desc', dataType: 'text' }),
      createResolvedField({ code: 'active', dataType: 'boolean' }),
      createResolvedField({ code: 'created', dataType: 'date' }),
      createResolvedField({ code: 'role', dataType: 'enum' }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].componentType).toBe('input');
    expect(fieldCells[1].componentType).toBe('textarea');
    expect(fieldCells[2].componentType).toBe('checkbox');
    expect(fieldCells[3].componentType).toBe('date-picker');
    expect(fieldCells[4].componentType).toBe('select');
  });

  it('should use uiHint.componentType if provided', () => {
    const fields: ResolvedField[] = [
      createResolvedField({
        code: 'name',
        dataType: 'string',
        uiHint: { componentType: 'custom-widget' },
      }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].componentType).toBe('custom-widget');
  });

  it('should set TEXT type fields (textarea) to full width', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', dataType: 'string' }),
      createResolvedField({ code: 'desc', dataType: 'text' }),
    ];

    const result = generateInitialHierarchy(fields, threeColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].span).toBe(1);
    expect(fieldCells[1].span).toBe(3); // textarea gets full columns
  });

  it('should set required prop when field is required', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'name', dataType: 'string', required: true }),
      createResolvedField({ code: 'desc', dataType: 'string', required: false }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].props.required).toBe(true);
    expect(fieldCells[1].props.required).toBeUndefined();
  });

  it('should set disabled prop when field is not editable', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'id', dataType: 'string', editable: false }),
      createResolvedField({ code: 'name', dataType: 'string', editable: true }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].props.disabled).toBe(true);
    expect(fieldCells[1].props.disabled).toBeUndefined();
  });

  it('should handle empty fields array', () => {
    const result = generateInitialHierarchy([], twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells).toHaveLength(0);
  });

  it('should default to input component for unknown data types', () => {
    const fields: ResolvedField[] = [
      createResolvedField({ code: 'custom', dataType: 'unknown_type' }),
    ];

    const result = generateInitialHierarchy(fields, twoColumnPreset);
    const fieldCells = result.tabs[0].floors[0].blocks[0].fields;

    expect(fieldCells[0].componentType).toBe('input');
  });
});
