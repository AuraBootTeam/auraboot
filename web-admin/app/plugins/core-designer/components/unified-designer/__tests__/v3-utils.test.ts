import { describe, expect, it } from 'vitest';
import type { PageSchemaV3 } from '../types';
import {
  findBlockById,
  updateBlockById,
  moveBlockBefore,
  moveBlockToParent,
} from '../utils/recursiveBlockWalker';
import { setByPath } from '../utils/dotPath';
import {
  canMoveExistingBlockBeforeTarget,
  canMoveExistingBlockToParent,
} from '../dnd/moveBlockGuards';
import { migrateDashboardResourceToV3, migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { createDefaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import { createModelFieldBlock } from '../registry/createBlockTemplate';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';

describe('Recursive PageSchema V3 utilities', () => {
  const schema: PageSchemaV3 = {
    schemaVersion: 3,
    kind: 'composite',
    id: 'customer_workspace',
    blocks: [
      {
        id: 'form_1',
        blockType: 'form',
        blocks: [
          {
            id: 'section_basic',
            blockType: 'form-section',
            blocks: [
              { id: 'field_name', blockType: 'field', field: 'name', layout: { span: 6 } },
              { id: 'field_phone', blockType: 'field', field: 'phone', layout: { span: 6 } },
            ],
          },
        ],
      },
    ],
  };

  it('finds nested blocks with a stable path', () => {
    const found = findBlockById(schema.blocks, 'field_phone');

    expect(found?.block.field).toBe('phone');
    expect(found?.path.map((item) => item.id)).toEqual([
      'form_1',
      'section_basic',
      'field_phone',
    ]);
  });

  it('updates nested blocks immutably', () => {
    const next = updateBlockById(schema.blocks, 'field_name', (block) => ({
      ...block,
      layout: { ...block.layout, span: 12 },
      props: { component: 'textarea' },
    }));

    expect(findBlockById(next, 'field_name')?.block.layout?.span).toBe(12);
    expect(findBlockById(next, 'field_name')?.block.props?.component).toBe('textarea');
    expect(findBlockById(schema.blocks, 'field_name')?.block.layout?.span).toBe(6);
  });

  it('moves a block before a sibling in the same parent', () => {
    const next = moveBlockBefore(schema.blocks, 'field_phone', 'field_name');
    const section = findBlockById(next, 'section_basic')?.block;

    expect(section?.blocks?.map((block) => block.id)).toEqual(['field_phone', 'field_name']);
  });

  it('moves a block before a target in another compatible container', () => {
    const crossContainerSchema: PageSchemaV3 = {
      ...schema,
      blocks: [
        {
          id: 'form_1',
          blockType: 'form',
          blocks: [
            {
              id: 'section_basic',
              blockType: 'form-section',
              blocks: [
                { id: 'field_name', blockType: 'field', field: 'name' },
                { id: 'field_phone', blockType: 'field', field: 'phone' },
              ],
            },
            {
              id: 'section_secondary',
              blockType: 'form-section',
              blocks: [
                { id: 'field_email', blockType: 'field', field: 'email' },
                { id: 'field_status', blockType: 'field', field: 'status' },
              ],
            },
          ],
        },
      ],
    };

    const next = moveBlockBefore(crossContainerSchema.blocks, 'field_phone', 'field_email');
    const sourceSection = findBlockById(next, 'section_basic')?.block;
    const targetSection = findBlockById(next, 'section_secondary')?.block;

    expect(sourceSection?.blocks?.map((block) => block.id)).toEqual(['field_name']);
    expect(targetSection?.blocks?.map((block) => block.id)).toEqual([
      'field_phone',
      'field_email',
      'field_status',
    ]);
    expect(findBlockById(crossContainerSchema.blocks, 'section_basic')?.block.blocks?.map((block) => block.id)).toEqual([
      'field_name',
      'field_phone',
    ]);
  });

  it('moves a block into another compatible container as the last child', () => {
    const crossContainerSchema: PageSchemaV3 = {
      ...schema,
      blocks: [
        {
          id: 'form_1',
          blockType: 'form',
          blocks: [
            {
              id: 'section_basic',
              blockType: 'form-section',
              blocks: [
                { id: 'field_name', blockType: 'field', field: 'name' },
                { id: 'field_phone', blockType: 'field', field: 'phone' },
              ],
            },
            {
              id: 'section_secondary',
              blockType: 'form-section',
              blocks: [
                { id: 'field_email', blockType: 'field', field: 'email' },
                { id: 'field_status', blockType: 'field', field: 'status' },
              ],
            },
          ],
        },
      ],
    };

    const next = moveBlockToParent(crossContainerSchema.blocks, 'field_phone', 'section_secondary');
    const sourceSection = findBlockById(next, 'section_basic')?.block;
    const targetSection = findBlockById(next, 'section_secondary')?.block;

    expect(sourceSection?.blocks?.map((block) => block.id)).toEqual(['field_name']);
    expect(targetSection?.blocks?.map((block) => block.id)).toEqual([
      'field_email',
      'field_status',
      'field_phone',
    ]);
    expect(findBlockById(crossContainerSchema.blocks, 'section_basic')?.block.blocks?.map((block) => block.id)).toEqual([
      'field_name',
      'field_phone',
    ]);
  });

  it('moves a non-field block subtree between compatible containers without losing children', () => {
    const crossContainerSchema: PageSchemaV3 = {
      ...schema,
      blocks: [
        {
          id: 'form_1',
          blockType: 'form',
          blocks: [
            {
              id: 'section_basic',
              blockType: 'form-section',
              blocks: [
                {
                  id: 'sub_table_orders',
                  blockType: 'sub-table',
                  blocks: [
                    { id: 'column_item', blockType: 'column', field: 'item' },
                    { id: 'action_add', blockType: 'action', actionType: 'create' },
                  ],
                },
              ],
            },
            {
              id: 'section_secondary',
              blockType: 'form-section',
              blocks: [
                {
                  id: 'sub_table_history',
                  blockType: 'sub-table',
                  blocks: [{ id: 'column_status', blockType: 'column', field: 'status' }],
                },
              ],
            },
            {
              id: 'section_empty',
              blockType: 'form-section',
              blocks: [],
            },
          ],
        },
      ],
    };

    const beforeTarget = moveBlockBefore(
      crossContainerSchema.blocks,
      'sub_table_orders',
      'sub_table_history',
    );
    const sourceAfterBefore = findBlockById(beforeTarget, 'section_basic')?.block;
    const targetAfterBefore = findBlockById(beforeTarget, 'section_secondary')?.block;
    const movedBefore = findBlockById(beforeTarget, 'sub_table_orders')?.block;

    expect(sourceAfterBefore?.blocks?.map((block) => block.id)).toEqual([]);
    expect(targetAfterBefore?.blocks?.map((block) => block.id)).toEqual([
      'sub_table_orders',
      'sub_table_history',
    ]);
    expect(movedBefore?.blocks?.map((block) => block.id)).toEqual(['column_item', 'action_add']);

    const insideEmpty = moveBlockToParent(
      crossContainerSchema.blocks,
      'sub_table_orders',
      'section_empty',
    );
    const targetAfterInside = findBlockById(insideEmpty, 'section_empty')?.block;
    const movedInside = findBlockById(insideEmpty, 'sub_table_orders')?.block;

    expect(targetAfterInside?.blocks?.map((block) => block.id)).toEqual(['sub_table_orders']);
    expect(movedInside?.blocks?.map((block) => block.id)).toEqual(['column_item', 'action_add']);
    expect(
      findBlockById(crossContainerSchema.blocks, 'section_basic')?.block.blocks?.map(
        (block) => block.id,
      ),
    ).toEqual(['sub_table_orders']);
  });

  it('moves repeater and subform subtrees between compatible containers without losing children', () => {
    const crossContainerSchema: PageSchemaV3 = {
      ...schema,
      blocks: [
        {
          id: 'form_1',
          blockType: 'form',
          blocks: [
            {
              id: 'section_basic',
              blockType: 'form-section',
              blocks: [
                {
                  id: 'repeater_contacts',
                  blockType: 'repeater',
                  blocks: [{ id: 'field_contact_name', blockType: 'field', field: 'contact_name' }],
                },
                {
                  id: 'subform_team',
                  blockType: 'subform',
                  blocks: [
                    {
                      id: 'subform_section_team',
                      blockType: 'form-section',
                      blocks: [{ id: 'field_member_name', blockType: 'field', field: 'member_name' }],
                    },
                  ],
                },
              ],
            },
            {
              id: 'section_secondary',
              blockType: 'form-section',
              blocks: [
                {
                  id: 'repeater_history',
                  blockType: 'repeater',
                  blocks: [{ id: 'field_history_note', blockType: 'field', field: 'history_note' }],
                },
              ],
            },
            {
              id: 'section_empty',
              blockType: 'form-section',
              blocks: [],
            },
          ],
        },
      ],
    };

    const repeaterBeforeTarget = moveBlockBefore(
      crossContainerSchema.blocks,
      'repeater_contacts',
      'repeater_history',
    );
    const targetAfterRepeaterMove = findBlockById(repeaterBeforeTarget, 'section_secondary')?.block;
    const movedRepeater = findBlockById(repeaterBeforeTarget, 'repeater_contacts')?.block;

    expect(targetAfterRepeaterMove?.blocks?.map((block) => block.id)).toEqual([
      'repeater_contacts',
      'repeater_history',
    ]);
    expect(movedRepeater?.blocks?.map((block) => block.id)).toEqual(['field_contact_name']);

    const subformInsideEmpty = moveBlockToParent(
      crossContainerSchema.blocks,
      'subform_team',
      'section_empty',
    );
    const targetAfterSubformMove = findBlockById(subformInsideEmpty, 'section_empty')?.block;
    const movedSubform = findBlockById(subformInsideEmpty, 'subform_team')?.block;
    const movedSubformSection = findBlockById(subformInsideEmpty, 'subform_section_team')?.block;

    expect(targetAfterSubformMove?.blocks?.map((block) => block.id)).toEqual(['subform_team']);
    expect(movedSubform?.blocks?.map((block) => block.id)).toEqual(['subform_section_team']);
    expect(movedSubformSection?.blocks?.map((block) => block.id)).toEqual(['field_member_name']);
    expect(
      findBlockById(crossContainerSchema.blocks, 'section_empty')?.block.blocks?.map(
        (block) => block.id,
      ),
    ).toEqual([]);
  });

  it('moves action-bar subtrees between form and tab containers without losing actions', () => {
    const crossContainerSchema: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'form',
      id: 'form_with_action_bar_moves',
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [
            {
              id: 'tabs_holder',
              blockType: 'tabs',
              blocks: [
                {
                  id: 'tab_source',
                  blockType: 'tab',
                  blocks: [
                    {
                      id: 'action_bar_move_candidate',
                      blockType: 'action-bar',
                      region: 'toolbar',
                      blocks: [
                        { id: 'candidate_action_submit', blockType: 'action', actionType: 'submit' },
                        { id: 'candidate_action_refresh', blockType: 'action', actionType: 'refresh' },
                      ],
                    },
                  ],
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              blocks: [{ id: 'field_target', blockType: 'field', field: 'name' }],
            },
          ],
        },
      ],
    };

    const beforeTarget = moveBlockBefore(
      crossContainerSchema.blocks,
      'action_bar_move_candidate',
      'section_target',
    );
    const formAfterBefore = findBlockById(beforeTarget, 'form_root')?.block;
    const tabAfterBefore = findBlockById(beforeTarget, 'tab_source')?.block;
    const movedBefore = findBlockById(beforeTarget, 'action_bar_move_candidate')?.block;

    expect(formAfterBefore?.blocks?.map((block) => block.id)).toEqual([
      'tabs_holder',
      'action_bar_move_candidate',
      'section_target',
    ]);
    expect(tabAfterBefore?.blocks?.map((block) => block.id)).toEqual([]);
    expect(movedBefore?.blocks?.map((block) => block.id)).toEqual([
      'candidate_action_submit',
      'candidate_action_refresh',
    ]);

    const insideTabSchema: PageSchemaV3 = {
      ...crossContainerSchema,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [
            {
              id: 'action_bar_move_candidate',
              blockType: 'action-bar',
              region: 'toolbar',
              blocks: [
                { id: 'candidate_action_submit', blockType: 'action', actionType: 'submit' },
                { id: 'candidate_action_refresh', blockType: 'action', actionType: 'refresh' },
              ],
            },
            {
              id: 'tabs_holder',
              blockType: 'tabs',
              blocks: [{ id: 'tab_empty', blockType: 'tab', blocks: [] }],
            },
          ],
        },
      ],
    };

    const insideTab = moveBlockToParent(
      insideTabSchema.blocks,
      'action_bar_move_candidate',
      'tab_empty',
    );
    const formAfterInside = findBlockById(insideTab, 'form_root')?.block;
    const tabAfterInside = findBlockById(insideTab, 'tab_empty')?.block;
    const movedInside = findBlockById(insideTab, 'action_bar_move_candidate')?.block;

    expect(formAfterInside?.blocks?.map((block) => block.id)).toEqual(['tabs_holder']);
    expect(tabAfterInside?.blocks?.map((block) => block.id)).toEqual([
      'action_bar_move_candidate',
    ]);
    expect(movedInside?.blocks?.map((block) => block.id)).toEqual([
      'candidate_action_submit',
      'candidate_action_refresh',
    ]);
  });

  it('moves list block subtrees between list and tab containers without losing children', () => {
    const crossContainerSchema: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'list_with_table_and_filter_moves',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            {
              id: 'tabs_holder',
              blockType: 'tabs',
              blocks: [
                {
                  id: 'tab_source',
                  blockType: 'tab',
                  blocks: [
                    {
                      id: 'table_move_candidate',
                      blockType: 'table',
                      blocks: [
                        { id: 'candidate_col_name', blockType: 'column', field: 'name' },
                        { id: 'candidate_action_view', blockType: 'action', actionType: 'view' },
                      ],
                    },
                    {
                      id: 'filter_bar_move_candidate',
                      blockType: 'filter-bar',
                      blocks: [
                        { id: 'candidate_filter_status', blockType: 'filter-field', field: 'status' },
                      ],
                    },
                  ],
                },
              ],
            },
            {
              id: 'action_bar_target',
              blockType: 'action-bar',
              blocks: [{ id: 'target_action_create', blockType: 'action', actionType: 'create' }],
            },
            {
              id: 'table_target',
              blockType: 'table',
              blocks: [{ id: 'target_col_title', blockType: 'column', field: 'title' }],
            },
          ],
        },
      ],
    };

    const tableBeforeTarget = moveBlockBefore(
      crossContainerSchema.blocks,
      'table_move_candidate',
      'table_target',
    );
    const listAfterTableMove = findBlockById(tableBeforeTarget, 'list_root')?.block;
    const tabAfterTableMove = findBlockById(tableBeforeTarget, 'tab_source')?.block;
    const movedTable = findBlockById(tableBeforeTarget, 'table_move_candidate')?.block;

    expect(listAfterTableMove?.blocks?.map((block) => block.id)).toEqual([
      'tabs_holder',
      'action_bar_target',
      'table_move_candidate',
      'table_target',
    ]);
    expect(tabAfterTableMove?.blocks?.map((block) => block.id)).toEqual([
      'filter_bar_move_candidate',
    ]);
    expect(movedTable?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_name',
      'candidate_action_view',
    ]);

    const insideTabSchema: PageSchemaV3 = {
      ...crossContainerSchema,
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            {
              id: 'filter_bar_move_candidate',
              blockType: 'filter-bar',
              blocks: [
                { id: 'candidate_filter_status', blockType: 'filter-field', field: 'status' },
              ],
            },
            {
              id: 'tabs_holder',
              blockType: 'tabs',
              blocks: [{ id: 'tab_empty', blockType: 'tab', blocks: [] }],
            },
          ],
        },
      ],
    };

    const filterInsideTab = moveBlockToParent(
      insideTabSchema.blocks,
      'filter_bar_move_candidate',
      'tab_empty',
    );
    const listAfterFilterMove = findBlockById(filterInsideTab, 'list_root')?.block;
    const tabAfterFilterMove = findBlockById(filterInsideTab, 'tab_empty')?.block;
    const movedFilterBar = findBlockById(filterInsideTab, 'filter_bar_move_candidate')?.block;

    expect(listAfterFilterMove?.blocks?.map((block) => block.id)).toEqual(['tabs_holder']);
    expect(tabAfterFilterMove?.blocks?.map((block) => block.id)).toEqual([
      'filter_bar_move_candidate',
    ]);
    expect(movedFilterBar?.blocks?.map((block) => block.id)).toEqual([
      'candidate_filter_status',
    ]);

    const registry = createDefaultBlockRegistryV3();
    expect(registry.canContain('list', 'table')).toBe(true);
    expect(registry.canContain('list', 'filter-bar')).toBe(true);
    expect(registry.canContain('tab', 'table')).toBe(true);
    expect(registry.canContain('tab', 'filter-bar')).toBe(true);
    expect(registry.canContain('table', 'column')).toBe(true);
    expect(registry.canContain('filter-bar', 'filter-field')).toBe(true);
    expect(registry.canContain('filter-bar', 'table')).toBe(false);
  });

  it('sets nested dot-path values without mutating the source object', () => {
    const source = { props: { label: 'Name' }, layout: { span: 6 } };
    const next = setByPath(source, 'props.required', true);

    expect(next).toEqual({ props: { label: 'Name', required: true }, layout: { span: 6 } });
    expect(source).toEqual({ props: { label: 'Name' }, layout: { span: 6 } });
  });
});

describe('Unified designer existing-block move guards', () => {
  const registry = createDefaultBlockRegistryV3();
  const formSchemaWithCrossKindBlocks: PageSchemaV3 = {
    schemaVersion: 3,
    kind: 'form',
    id: 'form_with_invalid_detail_section',
    blocks: [
      {
        id: 'form_root',
        blockType: 'form',
        blocks: [
          {
            id: 'tabs_root',
            blockType: 'tabs',
            blocks: [
              {
                id: 'tab_main',
                blockType: 'tab',
                blocks: [
                  { id: 'section_main', blockType: 'form-section' },
                  { id: 'detail_section_from_detail', blockType: 'detail-section' },
                ],
              },
            ],
          },
          {
            id: 'section_target',
            blockType: 'form-section',
            blocks: [{ id: 'field_target', blockType: 'field', field: 'name' }],
          },
        ],
      },
    ],
  };

  it('rejects cross-kind block moves even when the candidate parent type can contain the block', () => {
    expect(
      canMoveExistingBlockBeforeTarget({
        blocks: formSchemaWithCrossKindBlocks.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'detail_section_from_detail',
        targetBlockId: 'section_main',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockToParent({
        blocks: formSchemaWithCrossKindBlocks.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'detail_section_from_detail',
        parentBlockId: 'tab_main',
      }),
    ).toBe(false);
  });

  it('rejects leaf-parent, self, and descendant moves without mutating the tree', () => {
    expect(
      canMoveExistingBlockToParent({
        blocks: formSchemaWithCrossKindBlocks.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'section_main',
        parentBlockId: 'field_target',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockBeforeTarget({
        blocks: formSchemaWithCrossKindBlocks.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'section_target',
        targetBlockId: 'field_target',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockToParent({
        blocks: formSchemaWithCrossKindBlocks.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'section_target',
        parentBlockId: 'section_target',
      }),
    ).toBe(false);
  });

  it('rejects kind-allowed children when the target parent cannot contain them', () => {
    const schemaWithSubTableColumn: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'form',
      id: 'form_with_column_guard',
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              blocks: [
                {
                  id: 'sub_table_source',
                  blockType: 'sub-table',
                  blocks: [{ id: 'column_move_candidate', blockType: 'column', field: 'name' }],
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              blocks: [{ id: 'field_target', blockType: 'field', field: 'name' }],
            },
          ],
        },
      ],
    };

    expect(
      canMoveExistingBlockBeforeTarget({
        blocks: schemaWithSubTableColumn.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'column_move_candidate',
        targetBlockId: 'field_target',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockToParent({
        blocks: schemaWithSubTableColumn.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'column_move_candidate',
        parentBlockId: 'section_target',
      }),
    ).toBe(false);
  });

  it('rejects cross-kind workflow block moves beyond the detail-section regression case', () => {
    const listSchemaWithWorkflowBlock: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'list_with_invalid_workflow_block',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            {
              id: 'tabs_root',
              blockType: 'tabs',
              blocks: [
                {
                  id: 'tab_main',
                  blockType: 'tab',
                  blocks: [
                    { id: 'table_main', blockType: 'table' },
                    { id: 'bpm_panel_from_detail', blockType: 'bpm-panel' },
                  ],
                },
              ],
            },
          ],
        },
      ],
    };

    expect(
      canMoveExistingBlockBeforeTarget({
        blocks: listSchemaWithWorkflowBlock.blocks,
        kind: 'list',
        blockRegistry: registry,
        movingBlockId: 'bpm_panel_from_detail',
        targetBlockId: 'table_main',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockToParent({
        blocks: listSchemaWithWorkflowBlock.blocks,
        kind: 'list',
        blockRegistry: registry,
        movingBlockId: 'bpm_panel_from_detail',
        parentBlockId: 'tab_main',
      }),
    ).toBe(false);
  });

  it('does not resolve a compatible empty container drop as a sibling-before move', () => {
    const schemaWithEmptyTarget: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'form',
      id: 'form_with_sub_table_move',
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              blocks: [{ id: 'sub_table_orders', blockType: 'sub-table' }],
            },
            {
              id: 'section_empty',
              blockType: 'form-section',
              blocks: [],
            },
          ],
        },
      ],
    };

    expect(
      canMoveExistingBlockBeforeTarget({
        blocks: schemaWithEmptyTarget.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'sub_table_orders',
        targetBlockId: 'section_empty',
      }),
    ).toBe(false);
    expect(
      canMoveExistingBlockToParent({
        blocks: schemaWithEmptyTarget.blocks,
        kind: 'form',
        blockRegistry: registry,
        movingBlockId: 'sub_table_orders',
        parentBlockId: 'section_empty',
      }),
    ).toBe(true);
  });
});

describe('Recursive PageSchema V3 migration', () => {
  it('migrates a form page into a root form block with section field blocks', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'form',
      id: 'customer_form',
      modelCode: 'customer',
      layout: { type: 'grid', cols: 12 },
      blocks: [
        {
          id: 'basic',
          blockType: 'form-section',
          title: 'Basic',
          fields: ['name|required|span:6', { field: 'phone', span: 6 }],
        },
        {
          id: 'buttons',
          blockType: 'form-buttons',
          actions: ['submit'],
        },
      ],
    } as any);

    const form = next.blocks[0];
    const section = findBlockById(next.blocks, 'basic')?.block;
    const nameField = findBlockById(next.blocks, 'basic_name')?.block;
    const submit = findBlockById(next.blocks, 'buttons_submit')?.block;

    expect(next.schemaVersion).toBe(3);
    expect(form.blockType).toBe('form');
    expect(section?.blockType).toBe('form-section');
    expect(nameField).toMatchObject({
      blockType: 'field',
      field: 'name',
      layout: { span: 6 },
      props: { required: true },
    });
    expect(submit).toMatchObject({ blockType: 'action', actionType: 'submit' });
  });

  it('migrates a list page into filters, toolbar, and table child blocks', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'list',
      id: 'customer_list',
      modelCode: 'customer',
      layout: { type: 'grid', cols: 12 },
      blocks: [
        { id: 'filters', blockType: 'filters', fields: ['status'] },
        { id: 'toolbar', blockType: 'toolbar', actions: ['create'] },
        { id: 'table', blockType: 'table', columns: ['title|width:220', { field: 'status' }] },
      ],
    } as any);

    expect(next.blocks[0].blockType).toBe('list');
    expect(findBlockById(next.blocks, 'filters')?.block.region).toBe('filters');
    expect(findBlockById(next.blocks, 'filters_status')?.block.blockType).toBe('filter-field');
    expect(findBlockById(next.blocks, 'table_title')?.block).toMatchObject({
      blockType: 'column',
      field: 'title',
      layout: { width: 220 },
    });
    expect(findBlockById(next.blocks, 'toolbar_create')?.block.actionType).toBe('create');
  });

  it('migrates a dashboard resource into dashboard and widget blocks', () => {
    const next = migrateDashboardResourceToV3({
      id: 'sales',
      title: 'Sales Dashboard',
      layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
      widgets: [
        {
          id: 'revenue',
          type: 'smart-number-card',
          x: 0,
          y: 0,
          w: 3,
          h: 2,
          config: { title: 'Revenue' },
        },
      ],
    } as any);

    expect(next.kind).toBe('dashboard');
    expect(next.blocks[0]).toMatchObject({
      id: 'dashboard_sales',
      blockType: 'dashboard',
      layout: { type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
    });
    expect(findBlockById(next.blocks, 'revenue')?.block).toMatchObject({
      blockType: 'widget',
      widgetType: 'smart-number-card',
      layout: { x: 0, y: 0, w: 3, h: 2 },
      props: { title: 'Revenue' },
    });
  });

  it('normalizes numeric dashboard database ids to stable string schema ids', () => {
    const next = migrateDashboardResourceToV3({
      id: 1001,
      pid: 'dash_pid',
      code: 'ops_dashboard',
      title: 'Ops Dashboard',
      widgets: [
        {
          id: 'open_incidents',
          type: 'table',
          x: 3,
          y: 2,
          w: 6,
          h: 4,
          config: { title: 'Open Incidents' },
        },
      ],
    });

    expect(next.id).toBe('ops_dashboard');
    expect(next.blocks[0].id).toBe('dashboard_ops_dashboard');
    expect(validatePageSchemaV3(next)).toEqual({ valid: true, errors: [] });
  });

  it('generates unique widget ids for dashboard widgets without legacy ids', () => {
    const next = migrateDashboardResourceToV3({
      code: 'system_overview',
      title: 'System Overview',
      widgets: [
        { type: 'number-card', config: { title: 'Models' } },
        { type: 'number-card', config: { title: 'Pages' } },
        { type: 'number-card', config: { title: 'Plugins' } },
      ],
    });

    const widgetIds = next.blocks[0].blocks?.map((block) => block.id);

    expect(widgetIds).toEqual([
      'dashboard_system_overview_widget_number_card_models_1',
      'dashboard_system_overview_widget_number_card_pages_2',
      'dashboard_system_overview_widget_number_card_plugins_3',
    ]);
    expect(new Set(widgetIds).size).toBe(3);
    expect(validatePageSchemaV3(next)).toEqual({ valid: true, errors: [] });
  });

  it('migrates detail pages and action shorthand layout without leaking metadata props', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'detail',
      id: 'customer_detail',
      modelCode: 'customer',
      blocks: [
        {
          id: 'summary',
          blockType: 'detail-section',
          title: 'Summary',
          fields: ['name|span:6', { field: 'phone', span: 6, label: 'Phone' }],
        },
        {
          id: 'header_actions',
          blockType: 'toolbar',
          actions: [
            {
              id: 'open_customer',
              type: 'navigate',
              label: 'Open',
              span: 3,
              target: '/customers/:id',
            },
          ],
        },
      ],
    } as any);

    expect(next.kind).toBe('detail');
    expect(findBlockById(next.blocks, 'summary_name')?.block).toMatchObject({
      blockType: 'field',
      field: 'name',
      layout: { span: 6 },
    });
    expect(findBlockById(next.blocks, 'open_customer')?.block).toMatchObject({
      blockType: 'action',
      actionType: 'navigate',
      layout: { span: 3 },
      props: { label: 'Open', target: '/customers/:id' },
    });
    expect(findBlockById(next.blocks, 'open_customer')?.block.props).not.toHaveProperty('type');
    expect(findBlockById(next.blocks, 'open_customer')?.block.props).not.toHaveProperty('id');
    expect(findBlockById(next.blocks, 'open_customer')?.block.props).not.toHaveProperty('span');
  });

  it('migrates legacy tabs and readonly form sections in detail pages', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'detail',
      id: 'showcase_detail',
      modelCode: 'showcase_all_fields',
      blocks: [
        {
          id: 'sc_detail_tabs',
          blockType: 'tabs',
          tabs: [
            {
              key: 'overview',
              label: { en: 'Overview' },
              blocks: [
                {
                  id: 'section_basic',
                  blockType: 'form-section',
                  fields: [{ field: 'sc_name', colSpan: 6, readOnly: true }],
                },
              ],
            },
          ],
        },
      ],
    } as any);

    expect(findBlockById(next.blocks, 'sc_detail_tabs')?.block.blockType).toBe('tabs');
    expect(findBlockById(next.blocks, 'sc_detail_tabs_overview')?.block).toMatchObject({
      blockType: 'tab',
      title: { en: 'Overview' },
    });
    expect(findBlockById(next.blocks, 'section_basic')?.block.blockType).toBe('detail-section');
    expect(findBlockById(next.blocks, 'section_basic_sc_name')?.block).toMatchObject({
      blockType: 'field',
      field: 'sc_name',
      layout: { span: 6 },
      props: { readOnly: true },
    });
    expect(validatePageSchemaV3(next)).toEqual({ valid: true, errors: [] });
  });

  it('migrates legacy list tabs and form-buttons as V3 toolbar actions', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'list',
      id: 'department_list',
      modelCode: 'org_department',
      blocks: [
        {
          id: 'block_dept_tabs',
          blockType: 'tabs',
          tabs: [{ key: 'active', label: { en: 'Active' }, filter: { field: 'status' } }],
        },
        {
          id: 'block_dept_toolbar',
          blockType: 'form-buttons',
          buttons: [
            { code: 'create', action: { type: 'navigate', to: 'department_form' } },
            { code: 'import', commandCode: 'org:import_departments' },
          ],
        },
        { id: 'block_dept_table', blockType: 'table', columns: ['name'] },
      ],
    } as any);

    expect(findBlockById(next.blocks, 'block_dept_toolbar')?.block).toMatchObject({
      blockType: 'action-bar',
      region: 'toolbar',
    });
    expect(findBlockById(next.blocks, 'block_dept_toolbar_create')?.block).toMatchObject({
      blockType: 'action',
      actionType: 'navigate',
    });
    expect(findBlockById(next.blocks, 'block_dept_toolbar_import')?.block).toMatchObject({
      blockType: 'action',
      actionType: 'command',
    });
    expect(validatePageSchemaV3(next)).toEqual({ valid: true, errors: [] });
  });

  it('deduplicates legacy action ids when several custom actions have no explicit code', () => {
    const next = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'form',
      id: 'leave_form',
      blocks: [
        {
          id: 'buttons',
          blockType: 'form-buttons',
          actions: [
            { label: 'Draft' },
            { label: 'Submit' },
            { label: 'Cancel' },
          ],
        },
      ],
    } as any);

    expect(findBlockById(next.blocks, 'buttons')?.block.blocks?.map((block) => block.id)).toEqual([
      'buttons_custom',
      'buttons_custom_2',
      'buttons_custom_3',
    ]);
    expect(validatePageSchemaV3(next)).toEqual({ valid: true, errors: [] });
  });

  it('accepts legacy workflow helper blocks and form sub-tables', () => {
    const detail = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'detail',
      id: 'leave_detail',
      blocks: [
        {
          id: 'tabs',
          blockType: 'tabs',
          tabs: [
            { key: 'workflow', blocks: [{ id: 'workflow_diagram', blockType: 'bpm-panel' }] },
            { key: 'activity', blocks: [{ id: 'activity', blockType: 'activity-timeline' }] },
            { key: 'history', blocks: [{ id: 'history', blockType: 'field-history' }] },
          ],
        },
      ],
    } as any);
    const form = migratePageSchemaV2ToV3({
      schemaVersion: 2,
      kind: 'form',
      id: 'data_permission_form',
      blocks: [{ id: 'role-bindings', blockType: 'sub-table', columns: ['role_code'] }],
    } as any);

    expect(validatePageSchemaV3(detail)).toEqual({ valid: true, errors: [] });
    expect(validatePageSchemaV3(form)).toEqual({ valid: true, errors: [] });
  });
});

describe('Inspector schema registry', () => {
  it('returns registered block inspector schemas and default fallback fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const actionSchema = registry.getFields('action');
    const commandActionSchema = registry.getFieldsForBlock({
      id: 'command_action',
      blockType: 'action',
      actionType: 'command',
    });
    const navigateActionSchema = registry.getFieldsForBlock({
      id: 'navigate_action',
      blockType: 'action',
      actionType: 'navigate',
    });
    const unknownSchema = registry.getFields('unknown-block');

    expect(actionSchema.map((field) => field.key)).toContain('actionType');
    expect(commandActionSchema.map((field) => field.key)).toContain('props.command');
    expect(commandActionSchema.map((field) => field.key)).toContain('props.permissionCode');
    expect(commandActionSchema.map((field) => field.key)).not.toContain('props.to');
    expect(navigateActionSchema.map((field) => field.key)).toContain('props.to');
    expect(navigateActionSchema.map((field) => field.key)).toContain('props.permissionCode');
    expect(navigateActionSchema.map((field) => field.key)).not.toContain('props.command');
    expect(commandActionSchema.find((field) => field.key === 'props.payload')?.type).toBe('json');
    expect(navigateActionSchema.find((field) => field.key === 'props.params')?.type).toBe('json');
    expect(unknownSchema.map((field) => field.key)).toEqual(['title', 'layout.span', 'region']);
  });

  it('uses model field selectors for field-like blocks', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const formFieldSchema = registry.getFields('field');
    const filterFieldSchema = registry.getFields('filter-field');
    const columnSchema = registry.getFields('column');

    expect(formFieldSchema.find((field) => field.key === 'field')?.type).toBe('field-select');
    expect(filterFieldSchema.find((field) => field.key === 'field')?.type).toBe('field-select');
    expect(columnSchema.find((field) => field.key === 'field')?.type).toBe('field-select');
    expect(formFieldSchema.map((field) => field.key)).toContain('props.dataType');
    expect(formFieldSchema.map((field) => field.key)).toContain('props.dictCode');
    expect(filterFieldSchema.map((field) => field.key)).toContain('props.operator');
    expect(columnSchema.map((field) => field.key)).toContain('props.dataType');
    expect(columnSchema.map((field) => field.key)).toContain('props.dictCode');
  });

  it('exposes complex form field settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const formFieldSchema = registry.getFields('field');
    const sectionSchema = registry.getFields('form-section');

    expect(formFieldSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'props.placeholder',
        'props.helpText',
        'props.readOnly',
        'props.options',
        'props.visibleWhen',
        'props.validationRules',
        'layout.span',
      ]),
    );
    expect(formFieldSchema.find((field) => field.key === 'props.options')?.type).toBe('json');
    expect(formFieldSchema.find((field) => field.key === 'props.visibleWhen')?.type).toBe('json');
    expect(formFieldSchema.find((field) => field.key === 'props.validationRules')?.type).toBe(
      'json',
    );
    expect(sectionSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining(['props.description', 'props.collapsible', 'props.visibleWhen']),
    );
    expect(sectionSchema.find((field) => field.key === 'props.visibleWhen')?.type).toBe('json');
  });

  it('exposes props.aiLocked as a boolean inspector field on form fields (D5 AI lock)', () => {
    const registry = createDefaultInspectorSchemaRegistry();
    const formFieldSchema = registry.getFields('field');
    const aiLocked = formFieldSchema.find((field) => field.key === 'props.aiLocked');
    expect(aiLocked).toBeDefined();
    expect(aiLocked?.type).toBe('boolean');
  });

  it('returns structured inspector schemas for page containers and dashboard layout', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const formSchema = registry.getFields('form');
    const listSchema = registry.getFields('list');
    const detailSchema = registry.getFields('detail');
    const dashboardSchema = registry.getFields('dashboard');

    expect(formSchema.map((field) => field.key)).toEqual([
      'title',
      'dataSource.model',
      'layout.span',
    ]);
    expect(listSchema.map((field) => field.key)).toContain('dataSource.model');
    expect(listSchema.map((field) => field.key)).toContain('props.selectionMode');
    expect(detailSchema.map((field) => field.key)).toContain('dataSource.model');
    expect(dashboardSchema.map((field) => field.key)).toEqual([
      'title',
      'layout.span',
      'layout.cols',
      'layout.rowHeight',
      'layout.gap',
    ]);
  });

  it('exposes common dashboard widget settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const widgetSchema = registry.getFields('widget');
    const widgetTypeField = widgetSchema.find((field) => field.key === 'widgetType');

    expect(widgetSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'props.title',
        'props.subtitle',
        'widgetType',
        'dataSource.type',
        'dataSource.model',
        'dataSource.metric',
        'dataSource.query',
        'dataSource.queryCode',
        'dataSource.parameters',
        'dataSource.page',
        'dataSource.size',
        'props.value',
        'props.format',
        'props.emptyText',
        'props.errorText',
        'props.drillDownTo',
        'props.thresholds',
        'props.series',
        'props.columns',
        'props.rows',
        'props.markdown',
        'props.refreshInterval',
        'layout.x',
        'layout.y',
        'layout.w',
        'layout.h',
      ]),
    );
    expect(widgetTypeField?.type).toBe('select');
    expect(widgetTypeField?.options?.map((option) => option.value)).toEqual(
      expect.arrayContaining(['number-card', 'bar-chart', 'line-chart', 'table', 'markdown']),
    );
    expect(widgetSchema.find((field) => field.key === 'dataSource.type')?.type).toBe('select');
    expect(widgetSchema.find((field) => field.key === 'dataSource.query')?.type).toBe('json');
    expect(widgetSchema.find((field) => field.key === 'dataSource.parameters')?.type).toBe('json');
    expect(widgetSchema.find((field) => field.key === 'props.thresholds')?.type).toBe('json');
    expect(widgetSchema.find((field) => field.key === 'props.series')?.type).toBe('json');
    expect(widgetSchema.find((field) => field.key === 'props.columns')?.type).toBe('json');
    expect(widgetSchema.find((field) => field.key === 'props.rows')?.type).toBe('json');
  });

  it('exposes helper block settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const aiSchema = registry.getFields('ai-fill-banner');
    const bpmSchema = registry.getFields('bpm-panel');
    const timelineSchema = registry.getFields('activity-timeline');
    const historySchema = registry.getFields('field-history');

    expect(aiSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'props.description',
        'props.suggestedFields',
        'props.feedback',
        'props.emptyText',
        'props.permissionCode',
        'dataSource.type',
        'dataSource.executionMode',
        'dataSource.query',
        'dataSource.queryCode',
        'dataSource.parameters',
      ]),
    );
    expect(aiSchema.find((field) => field.key === 'props.suggestedFields')?.type).toBe('json');
    expect(aiSchema.find((field) => field.key === 'dataSource.type')?.type).toBe('select');
    expect(aiSchema.find((field) => field.key === 'dataSource.query')?.type).toBe('json');
    expect(bpmSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'props.status',
        'props.assignee',
        'props.dueAt',
        'props.actions',
        'props.emptyText',
        'props.permissionCode',
        'dataSource.type',
        'dataSource.executionMode',
        'dataSource.queryCode',
      ]),
    );
    expect(bpmSchema.find((field) => field.key === 'props.status')?.type).toBe('select');
    expect(bpmSchema.find((field) => field.key === 'props.actions')?.type).toBe('json');
    expect(bpmSchema.find((field) => field.key === 'dataSource.parameters')?.type).toBe('json');
    expect(timelineSchema.find((field) => field.key === 'props.items')?.type).toBe('json');
    expect(timelineSchema.find((field) => field.key === 'props.permissionCode')?.type).toBe('text');
    expect(timelineSchema.find((field) => field.key === 'dataSource.queryCode')?.type).toBe('text');
    expect(historySchema.find((field) => field.key === 'props.entries')?.type).toBe('json');
    expect(historySchema.find((field) => field.key === 'props.permissionCode')?.type).toBe('text');
    expect(historySchema.find((field) => field.key === 'dataSource.executionMode')?.type).toBe(
      'select',
    );
  });

  it('exposes sub-table authoring settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const subTableSchema = registry.getFields('sub-table');

    expect(subTableSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'title',
        'dataSource.model',
        'dataSource.parentField',
        'dataSource.childField',
        'props.rows',
        'layout.span',
      ]),
    );
    expect(subTableSchema.find((field) => field.key === 'props.rows')?.type).toBe('json');
  });

  it('exposes repeater authoring settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const repeaterSchema = registry.getFields('repeater');

    expect(repeaterSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining(['title', 'props.rows', 'layout.span']),
    );
    expect(repeaterSchema.find((field) => field.key === 'props.rows')?.type).toBe('json');
  });

  it('exposes subform authoring settings as structured inspector fields', () => {
    const registry = createDefaultInspectorSchemaRegistry();

    const subformSchema = registry.getFields('subform');

    expect(subformSchema.map((field) => field.key)).toEqual(
      expect.arrayContaining(['title', 'props.rows', 'layout.span']),
    );
    expect(subformSchema.find((field) => field.key === 'props.rows')?.type).toBe('json');
  });

  it('attaches inspector schemas and containment rules to default block definitions', () => {
    const registry = createDefaultBlockRegistryV3();

    expect(registry.canContain('form-section', 'field')).toBe(true);
    expect(registry.canContain('form-section', 'form-section')).toBe(true);
    expect(registry.canContain('form-section', 'sub-table')).toBe(true);
    expect(registry.canContain('form-section', 'repeater')).toBe(true);
    expect(registry.canContain('form-section', 'subform')).toBe(true);
    expect(registry.canContain('form', 'action-bar')).toBe(true);
    expect(registry.canContain('tab', 'action-bar')).toBe(true);
    expect(registry.canContain('action-bar', 'action')).toBe(true);
    expect(registry.canContain('form-section', 'action-bar')).toBe(false);
    expect(registry.canContain('sub-table', 'column')).toBe(true);
    expect(registry.canContain('sub-table', 'action')).toBe(true);
    expect(registry.canContain('repeater', 'field')).toBe(true);
    expect(registry.canContain('repeater', 'column')).toBe(false);
    expect(registry.canContain('subform', 'form-section')).toBe(true);
    expect(registry.canContain('subform', 'field')).toBe(true);
    expect(registry.canContain('subform', 'column')).toBe(false);
    expect(registry.canContain('dashboard', 'widget')).toBe(true);
    expect(registry.canContain('dashboard', 'field')).toBe(false);
    expect(registry.get('widget')?.layoutCapability).toBe('dashboard-widget');
    expect(registry.get('action')?.inspector?.tabs[0]?.groups[0]?.fields.map((field) => field.key)).toContain(
      'actionType',
    );
  });
});

describe('Model field block templates', () => {
  it('preserves model field metadata in form field, filter, and column defaults', () => {
    const modelField = {
      modelCode: 'customer',
      code: 'status',
      label: 'Status',
      type: 'enum',
      component: 'select',
      dictCode: 'customer_status',
      required: true,
    };

    const formField = createModelFieldBlock(modelField, 'field', new Set());
    const filterField = createModelFieldBlock(modelField, 'filter-field', new Set());
    const column = createModelFieldBlock(modelField, 'column', new Set());

    expect(formField.props).toMatchObject({
      label: 'Status',
      component: 'select',
      dataType: 'enum',
      dictCode: 'customer_status',
      required: true,
    });
    expect(filterField.props).toMatchObject({
      label: 'Status',
      component: 'select',
      dataType: 'enum',
      dictCode: 'customer_status',
      operator: 'equals',
    });
    expect(column.props).toMatchObject({
      label: 'Status',
      dataType: 'enum',
      dictCode: 'customer_status',
    });
  });

  it('creates relation fields as model-backed picker blocks', () => {
    const modelField = {
      modelCode: 'customer',
      code: 'owner_id',
      label: 'Owner',
      type: 'relation',
      component: 'select',
      refTarget: {
        modelCode: 'user',
        valueField: 'pid',
        displayField: 'displayName',
      },
    };

    const formField = createModelFieldBlock(modelField, 'field', new Set());
    const filterField = createModelFieldBlock(modelField, 'filter-field', new Set());

    expect(formField.props).toMatchObject({
      label: 'Owner',
      component: 'picker',
      dataType: 'relation',
      pickerDataSource: 'model',
      pickerSource: 'user',
      valueField: 'pid',
      displayField: 'displayName',
      searchable: true,
      searchField: 'displayName',
      pageSize: 20,
    });
    expect(filterField.props).toMatchObject({
      label: 'Owner',
      component: 'picker',
      operator: 'equals',
      pickerDataSource: 'model',
      pickerSource: 'user',
      valueField: 'pid',
      displayField: 'displayName',
    });
  });
});
