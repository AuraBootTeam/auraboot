/**
 * Regression spec for the unified-designer overhaul (PR feat/unified-designer-overhaul).
 *
 * Guards the three headline fixes on a real form-kind page:
 *  - canvas band shows the localized page-kind label (表单), not the old
 *    hardcoded "Composite canvas"
 *  - the Blocks palette collapses to the page kind: a form page exposes form
 *    blocks only (no List/Detail/Dashboard), and never the bare placeholder
 *    leaf blocks (field/column/filter-field)
 *  - dragging a model field from the Fields library binds it as a real field
 *    block via @dnd-kit (the drag layer that unit tests mock out)
 *
 * The form page is discovered from /api/pages so the spec is portable across
 * seeds. Default UI locale is zh-CN.
 *
 * Dimensions: D1 (auth/session), D6 (designer canvas), D9 (regression guard)
 */

import { test, expect } from '../../fixtures';
import type { Locator, Page } from '@playwright/test';
import { uniqueId } from '../helpers';

type TestBlock = { id: string; blocks?: TestBlock[] };
type CreatedDesignerPage = { pageKey: string; pid: string };
type AdvancedContainerBlockType = 'repeater' | 'subform';
type ListBlockMoveType = 'table' | 'filter-bar';

async function createFormPage(page: Page): Promise<string> {
  const id = uniqueId('udw_kind');
  const pageKey = `udw_kind_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW kind ${id}`,
      pageKey,
      title: `UDW kind ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_main',
              blockType: 'form-section',
              title: 'Main section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_seed_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Seed name', component: 'input' },
                },
                {
                  id: 'field_seed_page_key',
                  blockType: 'field',
                  field: 'page_key',
                  layout: { span: 6 },
                  props: { label: 'Seed page key', component: 'input' },
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-kind-and-binding' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  return pageKey;
}

async function createCrossContainerFormPage(
  page: Page,
  options: { emptyTarget?: boolean } = {},
): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_cross_container');
  const pageKey = `udw_cross_container_${id}`;
  const targetBlocks = options.emptyTarget
    ? []
    : [
        {
          id: 'field_target_email',
          blockType: 'field',
          field: 'description',
          layout: { span: 6 },
          props: { label: 'Target email', component: 'textarea' },
        },
        {
          id: 'field_target_status',
          blockType: 'field',
          field: 'status',
          layout: { span: 6 },
          props: { label: 'Target status', component: 'select' },
        },
      ];
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW cross container ${id}`,
      pageKey,
      title: `UDW cross container ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_source_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Source name', component: 'input' },
                },
                {
                  id: 'field_move_candidate',
                  blockType: 'field',
                  field: 'page_key',
                  layout: { span: 6 },
                  props: { label: 'Move candidate', component: 'input' },
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: targetBlocks,
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-cross-container-move' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createCrossContainerSubTableFormPage(
  page: Page,
  options: { emptyTarget?: boolean } = {},
): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_sub_table_move');
  const pageKey = `udw_sub_table_move_${id}`;
  const targetBlocks = options.emptyTarget
    ? []
    : [
        {
          id: 'sub_table_target',
          blockType: 'sub-table',
          title: 'Target items',
          layout: { span: 12 },
          blocks: [
            {
              id: 'target_col_status',
              blockType: 'column',
              field: 'status',
              props: { label: 'Target status' },
            },
          ],
        },
      ];
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW sub table move ${id}`,
      pageKey,
      title: `UDW sub table move ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_source_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Source name', component: 'input' },
                },
                {
                  id: 'sub_table_move_candidate',
                  blockType: 'sub-table',
                  title: 'Move candidate items',
                  layout: { span: 12 },
                  blocks: [
                    {
                      id: 'candidate_col_title',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Candidate title' },
                    },
                    {
                      id: 'candidate_action_add',
                      blockType: 'action',
                      actionType: 'create',
                      props: { label: 'Add item' },
                    },
                  ],
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: targetBlocks,
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-sub-table-cross-container-move' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createCrossContainerAdvancedContainerFormPage(
  page: Page,
  blockType: AdvancedContainerBlockType,
  options: { emptyTarget?: boolean } = {},
): Promise<CreatedDesignerPage> {
  const id = uniqueId(`udw_${blockType}_move`);
  const pageKey = `udw_${blockType}_move_${id}`;
  const candidateId = `${blockType}_move_candidate`;
  const targetId = `${blockType}_target`;
  const targetBlocks = options.emptyTarget
    ? []
    : [
        {
          id: targetId,
          blockType,
          title: `Target ${blockType}`,
          layout: { span: 12 },
          blocks: createAdvancedContainerChildren(blockType, 'target'),
        },
      ];

  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW ${blockType} move ${id}`,
      pageKey,
      title: `UDW ${blockType} move ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_source_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Source name', component: 'input' },
                },
                {
                  id: candidateId,
                  blockType,
                  title: `Move candidate ${blockType}`,
                  layout: { span: 12 },
                  blocks: createAdvancedContainerChildren(blockType, 'candidate'),
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: targetBlocks,
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: `unified-designer-${blockType}-cross-container-move` },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

function createAdvancedContainerChildren(blockType: AdvancedContainerBlockType, prefix: 'candidate' | 'target') {
  if (blockType === 'repeater') {
    return [
      {
        id: `${prefix}_field_name`,
        blockType: 'field',
        field: 'name',
        layout: { span: 6 },
        props: { label: `${prefix} name`, component: 'input' },
      },
    ];
  }

  return [
    {
      id: `${prefix}_section_details`,
      blockType: 'form-section',
      title: `${prefix} details`,
      layout: { span: 12 },
      blocks: [
        {
          id: `${prefix}_field_name`,
          blockType: 'field',
          field: 'name',
          layout: { span: 6 },
          props: { label: `${prefix} name`, component: 'input' },
        },
      ],
    },
  ];
}

function expectAdvancedContainerChildren(
  savedBlocks: TestBlock[],
  blockType: AdvancedContainerBlockType,
  movedBlockId: string,
) {
  const movedBlock = findBlock(savedBlocks, movedBlockId);
  if (blockType === 'repeater') {
    expect(movedBlock?.blocks?.map((block) => block.id)).toEqual(['candidate_field_name']);
    return;
  }

  expect(movedBlock?.blocks?.map((block) => block.id)).toEqual(['candidate_section_details']);
  const movedSection = findBlock(savedBlocks, 'candidate_section_details');
  expect(movedSection?.blocks?.map((block) => block.id)).toEqual(['candidate_field_name']);
}

async function createCrossContainerActionBarFormPage(
  page: Page,
  options: { source: 'tab' | 'form' },
): Promise<CreatedDesignerPage> {
  const id = uniqueId(`udw_action_bar_${options.source}_move`);
  const pageKey = `udw_action_bar_${options.source}_move_${id}`;
  const rootBlocks =
    options.source === 'tab'
      ? [
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [
              {
                id: 'tab_source',
                blockType: 'tab',
                title: 'Source tab',
                blocks: [
                  {
                    id: 'action_bar_move_candidate',
                    blockType: 'action-bar',
                    title: 'Move candidate actions',
                    region: 'toolbar',
                    layout: { span: 12 },
                    blocks: createActionBarChildren('candidate'),
                  },
                ],
              },
            ],
          },
          {
            id: 'section_target',
            blockType: 'form-section',
            title: 'Target section',
            layout: { span: 12 },
            blocks: [
              {
                id: 'field_target_name',
                blockType: 'field',
                field: 'name',
                layout: { span: 6 },
                props: { label: 'Target name', component: 'input' },
              },
            ],
          },
        ]
      : [
          {
            id: 'action_bar_move_candidate',
            blockType: 'action-bar',
            title: 'Move candidate actions',
            region: 'toolbar',
            layout: { span: 12 },
            blocks: createActionBarChildren('candidate'),
          },
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [{ id: 'tab_empty', blockType: 'tab', title: 'Empty target tab', blocks: [] }],
          },
        ];

  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW action bar move ${id}`,
      pageKey,
      title: `UDW action bar move ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: rootBlocks,
        },
      ],
      extension: {
        e2e: true,
        scenario: `unified-designer-action-bar-${options.source}-cross-container-move`,
      },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

function createActionBarChildren(prefix: 'candidate') {
  return [
    {
      id: `${prefix}_action_submit`,
      blockType: 'action',
      actionType: 'submit',
      props: { label: 'Submit candidate' },
    },
    {
      id: `${prefix}_action_refresh`,
      blockType: 'action',
      actionType: 'refresh',
      props: { label: 'Refresh candidate' },
    },
  ];
}

function expectActionBarChildren(savedBlocks: TestBlock[], movedBlockId: string) {
  const movedBlock = findBlock(savedBlocks, movedBlockId);
  expect(movedBlock?.blocks?.map((block) => block.id)).toEqual([
    'candidate_action_submit',
    'candidate_action_refresh',
  ]);
}

async function createCrossContainerFormSectionPage(
  page: Page,
  options: { source: 'tab' | 'form' },
): Promise<CreatedDesignerPage> {
  const id = uniqueId(`udw_form_section_${options.source}_move`);
  const pageKey = `udw_form_section_${options.source}_move_${id}`;
  const rootBlocks =
    options.source === 'tab'
      ? [
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [
              {
                id: 'tab_source',
                blockType: 'tab',
                title: 'Source tab',
                blocks: [createFormSectionMoveCandidate()],
              },
            ],
          },
          {
            id: 'section_target',
            blockType: 'form-section',
            title: 'Target section',
            layout: { span: 12 },
            blocks: [
              {
                id: 'target_field_title',
                blockType: 'field',
                field: 'title',
                layout: { span: 6 },
                props: { label: 'Target title', component: 'input' },
              },
            ],
          },
        ]
      : [
          createFormSectionMoveCandidate(),
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [{ id: 'tab_empty', blockType: 'tab', title: 'Empty target tab', blocks: [] }],
          },
        ];

  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW form-section move ${id}`,
      pageKey,
      title: `UDW form-section move ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: rootBlocks,
        },
      ],
      extension: {
        e2e: true,
        scenario: `unified-designer-form-section-${options.source}-cross-container-move`,
      },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

function createFormSectionMoveCandidate() {
  return {
    id: 'form_section_move_candidate',
    blockType: 'form-section',
    title: 'Move candidate section',
    layout: { span: 12 },
    blocks: [
      {
        id: 'candidate_field_name',
        blockType: 'field',
        field: 'name',
        layout: { span: 6 },
        props: { label: 'Candidate name', component: 'input' },
      },
    ],
  };
}

function expectFormSectionChildren(savedBlocks: TestBlock[], movedBlockId: string) {
  const movedBlock = findBlock(savedBlocks, movedBlockId);
  expect(movedBlock?.blocks?.map((block) => block.id)).toEqual(['candidate_field_name']);
}

async function createCrossContainerListBlockPage(
  page: Page,
  blockType: ListBlockMoveType,
  options: { source: 'tab' | 'list' },
): Promise<CreatedDesignerPage> {
  const id = uniqueId(`udw_${blockType.replace('-', '_')}_${options.source}_move`);
  const pageKey = `udw_${blockType.replace('-', '_')}_${options.source}_move_${id}`;
  const candidateBlock = createListMoveCandidateBlock(blockType);
  const rootBlocks =
    options.source === 'tab'
      ? [
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [
              {
                id: 'tab_source',
                blockType: 'tab',
                title: 'Source tab',
                blocks: [candidateBlock],
              },
            ],
          },
          {
            id: 'action_bar_target',
            blockType: 'action-bar',
            title: 'Target actions',
            region: 'toolbar',
            layout: { span: 12 },
            blocks: [
              {
                id: 'target_action_create',
                blockType: 'action',
                actionType: 'create',
                props: { label: 'Create target' },
              },
            ],
          },
          {
            id: 'table_target',
            blockType: 'table',
            title: 'Target table',
            layout: { span: 12 },
            blocks: [
              {
                id: 'target_col_title',
                blockType: 'column',
                field: 'title',
                props: { label: 'Target title' },
              },
            ],
          },
        ]
      : [
          candidateBlock,
          {
            id: 'tabs_holder',
            blockType: 'tabs',
            title: 'Tabs holder',
            layout: { span: 12 },
            blocks: [{ id: 'tab_empty', blockType: 'tab', title: 'Empty target tab', blocks: [] }],
          },
        ];

  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW ${blockType} move ${id}`,
      pageKey,
      title: `UDW ${blockType} move ${id}`,
      kind: 'list',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          title: 'List root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: rootBlocks,
        },
      ],
      extension: {
        e2e: true,
        scenario: `unified-designer-${blockType}-${options.source}-cross-container-move`,
      },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

function createListMoveCandidateBlock(blockType: ListBlockMoveType) {
  if (blockType === 'table') {
    return {
      id: 'table_move_candidate',
      blockType: 'table',
      title: 'Move candidate table',
      layout: { span: 12 },
      blocks: [
        {
          id: 'candidate_col_name',
          blockType: 'column',
          field: 'name',
          props: { label: 'Candidate name' },
        },
        {
          id: 'candidate_action_view',
          blockType: 'action',
          actionType: 'view',
          props: { label: 'View candidate' },
        },
      ],
    };
  }

  return {
    id: 'filter_bar_move_candidate',
    blockType: 'filter-bar',
    title: 'Move candidate filters',
    layout: { span: 12 },
    blocks: [
      {
        id: 'candidate_filter_status',
        blockType: 'filter-field',
        field: 'status',
        props: { label: 'Candidate status' },
      },
    ],
  };
}

function expectListBlockChildren(
  savedBlocks: TestBlock[],
  blockType: ListBlockMoveType,
  movedBlockId: string,
) {
  const movedBlock = findBlock(savedBlocks, movedBlockId);
  if (blockType === 'table') {
    expect(movedBlock?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_name',
      'candidate_action_view',
    ]);
    return;
  }

  expect(movedBlock?.blocks?.map((block) => block.id)).toEqual(['candidate_filter_status']);
}

async function createCrossKindGuardFormPage(page: Page): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_cross_kind_guard');
  const pageKey = `udw_cross_kind_guard_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW cross kind guard ${id}`,
      pageKey,
      title: `UDW cross kind guard ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'tabs_root',
              blockType: 'tabs',
              title: 'Tabs root',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'tab_main',
                  blockType: 'tab',
                  title: 'Main tab',
                  blocks: [
                    {
                      id: 'section_main',
                      blockType: 'form-section',
                      title: 'Main section',
                      layout: { span: 12 },
                      blocks: [
                        {
                          id: 'field_inside_section',
                          blockType: 'field',
                          field: 'name',
                          layout: { span: 6 },
                          props: { label: 'Section field', component: 'input' },
                        },
                      ],
                    },
                    {
                      id: 'detail_section_from_detail',
                      blockType: 'detail-section',
                      title: 'Detail section from stale schema',
                      layout: { span: 12 },
                      blocks: [
                        {
                          id: 'field_inside_detail_section',
                          blockType: 'field',
                          field: 'description',
                          layout: { span: 12 },
                          props: { label: 'Invalid detail field', component: 'textarea' },
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-cross-kind-guard' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createIncompatibleContainerGuardFormPage(page: Page): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_incompatible_guard');
  const pageKey = `udw_incompatible_guard_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW incompatible guard ${id}`,
      pageKey,
      title: `UDW incompatible guard ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'sub_table_source',
                  blockType: 'sub-table',
                  title: 'Source sub-table',
                  layout: { span: 12 },
                  blocks: [
                    {
                      id: 'column_move_candidate',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Candidate column' },
                    },
                  ],
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_target_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Target name', component: 'input' },
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-incompatible-container-guard' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createCrossKindWorkflowGuardListPage(page: Page): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_workflow_guard');
  const pageKey = `udw_workflow_guard_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW workflow guard ${id}`,
      pageKey,
      title: `UDW workflow guard ${id}`,
      kind: 'list',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          title: 'List root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'tabs_root',
              blockType: 'tabs',
              title: 'Tabs root',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'tab_main',
                  blockType: 'tab',
                  title: 'Main tab',
                  blocks: [
                    {
                      id: 'table_main',
                      blockType: 'table',
                      title: 'Main table',
                      layout: { span: 12 },
                      blocks: [
                        {
                          id: 'column_table_name',
                          blockType: 'column',
                          field: 'name',
                          props: { label: 'Name' },
                        },
                      ],
                    },
                    {
                      id: 'bpm_panel_from_detail',
                      blockType: 'bpm-panel',
                      title: 'Workflow panel from stale schema',
                      layout: { span: 12 },
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-cross-kind-workflow-guard' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

/**
 * Open the unified designer for a page key. On a cold dev Vite the very first
 * navigation can race optimizeDeps and render a transient "Application Error";
 * reload once (Vite is warm by then) before asserting on the workbench.
 */
async function openDesigner(page: Page, pageKey: string) {
  const workbench = page.getByTestId('unified-designer-workbench');
  const attempts = 4;
  for (let i = 0; i < attempts; i++) {
    if (i === 0) {
      await page.goto(`/unified-designer?pageKey=${pageKey}`, { waitUntil: 'domcontentloaded' });
    } else {
      // Cold dev Vite re-runs optimizeDeps on the heavy designer route and can
      // render a transient "Application Error" until it settles; reload until ready.
      await page.reload({ waitUntil: 'domcontentloaded' });
    }
    try {
      await workbench.waitFor({ state: 'visible', timeout: i === attempts - 1 ? 45000 : 15000 });
      return;
    } catch {
      if (i === attempts - 1) throw new Error('unified-designer-workbench never became visible');
    }
  }
}

async function openBlocksResourceTab(page: Page) {
  const blocksTab = page.getByTestId('resource-tab-blocks');
  const firstPaletteItem = page.getByTestId('palette-add-form-section');
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await blocksTab.click();
    if (await firstPaletteItem.isVisible().catch(() => false)) return;
  }
  await expect(firstPaletteItem).toBeVisible({ timeout: 10000 });
}

test.describe('Unified designer — kind collapse, i18n, model binding', () => {
  // The designer route pulls a heavy dep graph (@dnd-kit, lucide, react-router
  // framework). On a cold dev Vite the first load triggers optimizeDeps and can
  // need a couple of reloads to settle, which exceeds the default per-test budget.
  test.describe.configure({ timeout: 120_000 });

  test('a form page collapses the palette and renders zh-CN copy', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);

    // Canvas band shows the localized form kind label, not the old Composite text.
    const band = page.getByTestId('canvas-root-drop-zone');
    await expect(band).toContainText('表单');
    await expect(band).not.toContainText('组合页面');
    await expect(band).not.toContainText('Composite');

    // zh-CN designer chrome.
    await expect(page.getByTestId('resource-tab-blocks')).toHaveText('区块');

    // Palette collapses to the form kind; other page kinds + placeholder leaves absent.
    await openBlocksResourceTab(page);
    await expect(page.getByTestId('palette-add-form-section')).toBeVisible();
    await expect(page.getByTestId('palette-add-list')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-detail')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-dashboard')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-field')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-column')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-filter-field')).toHaveCount(0);
  });

  test('dragging a model field into a section binds a field block via @dnd-kit', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);

    // Wait for the outline tree to populate before querying it.
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({
      timeout: 15000,
    });

    // Select the first form-section from the outline.
    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    await expect(sectionItem).toBeVisible();
    const sectionTestId = await sectionItem.getAttribute('data-testid');
    const sectionId = sectionTestId!.replace('outline-item-', '');
    await sectionItem.click();

    // Open the Fields library; model fields load async, so wait before deciding.
    await page.getByTestId('resource-tab-fields').click();
    await page
      .locator('[data-testid^="model-field-"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 })
      .catch(() => {});
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    await expect(fieldItem).toBeVisible();

    const beforeFields = await page.locator('[data-testid^="canvas-block-field_"]').count();

    // Real @dnd-kit pointer drag: field item -> section canvas block.
    const target = page.getByTestId(`canvas-block-${sectionId}`);
    const src = await fieldItem.boundingBox();
    const dst = await target.boundingBox();
    expect(src && dst).toBeTruthy();
    await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
    await page.mouse.down();
    await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
    await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 14 });
    await page.mouse.move(dst!.x + dst!.width / 2 + 3, dst!.y + dst!.height / 2 + 3, { steps: 4 });
    await page.mouse.up();

    await expect
      .poll(async () => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(beforeFields);
  });

  test('moves an existing field block between form-section containers and persists schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBefore(page, 'field_move_candidate', 'field_target_email');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_target_email')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as Array<{ id: string; blocks?: Array<{ id: string }> }>;
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'field_move_candidate',
      'field_target_email',
      'field_target_status',
    ]);
  });

  test('undoes and redoes a cross-container move-before before saving schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBefore(page, 'field_move_candidate', 'field_target_email');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await waitForDesignerDragToSettle(page);

    await clickDesignerToolbarButton(page, 'designer-undo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    expect(await isBeforeInDom(sourceSection, 'field_source_name', 'field_move_candidate')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await expect(page.getByTestId('designer-redo')).toBeEnabled();

    await clickDesignerToolbarButton(page, 'designer-redo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'field_move_candidate',
      'field_target_email',
      'field_target_status',
    ]);
  });

  test('moves an existing field block inside an empty form-section container and persists schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'field_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['field_move_candidate']);
  });

  test('undoes and redoes a cross-container move-inside into an empty section before saving schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'field_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await waitForDesignerDragToSettle(page);

    await clickDesignerToolbarButton(page, 'designer-undo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    expect(await isBeforeInDom(sourceSection, 'field_source_name', 'field_move_candidate')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await expect(page.getByTestId('designer-redo')).toBeEnabled();

    await clickDesignerToolbarButton(page, 'designer-redo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['field_move_candidate']);
  });

  test('moves an existing sub-table subtree before another sub-table in a different section', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerSubTableFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBeforeHeader(page, 'sub_table_move_candidate', 'sub_table_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-sub_table_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-sub_table_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-sub_table_target')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'sub_table_move_candidate', 'sub_table_target')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');
    const movedSubTable = findBlock(savedBlocks, 'sub_table_move_candidate');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'sub_table_move_candidate',
      'sub_table_target',
    ]);
    expect(movedSubTable?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_title',
      'candidate_action_add',
    ]);
  });

  test('moves an existing sub-table subtree inside an empty section and preserves children', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerSubTableFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'sub_table_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-sub_table_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-sub_table_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');
    const movedSubTable = findBlock(savedBlocks, 'sub_table_move_candidate');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['sub_table_move_candidate']);
    expect(movedSubTable?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_title',
      'candidate_action_add',
    ]);
  });

  for (const blockType of ['repeater', 'subform'] as const) {
    test(`moves an existing ${blockType} subtree before another ${blockType} in a different section`, async ({
      page,
    }) => {
      const movedBlockId = `${blockType}_move_candidate`;
      const targetBlockId = `${blockType}_target`;
      const { pageKey: formKey, pid } = await createCrossContainerAdvancedContainerFormPage(page, blockType);
      await openDesigner(page, formKey);
      await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

      await page.getByTestId('designer-mode-layout').click();
      await dragCanvasBlockBeforeHeader(page, movedBlockId, targetBlockId);

      const sourceSection = page.getByTestId('canvas-block-section_source');
      const targetSection = page.getByTestId('canvas-block-section_target');
      await expect(sourceSection.getByTestId(`canvas-block-${movedBlockId}`)).toHaveCount(0);
      await expect(targetSection.getByTestId(`canvas-block-${movedBlockId}`)).toBeVisible();
      await expect(targetSection.getByTestId(`canvas-block-${targetBlockId}`)).toBeVisible();
      expect(await isBeforeInDom(targetSection, movedBlockId, targetBlockId)).toBe(true);
      await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

      await saveDesignerPage(page, pid);

      const readback = await page.request.get(`/api/pages/key/${formKey}`);
      expect(readback.ok(), await readback.text()).toBe(true);
      const readbackBody = await readback.json();
      expect(readbackBody.code).toBe('0');
      const savedBlocks = readbackBody.data.blocks as TestBlock[];
      const savedSource = findBlock(savedBlocks, 'section_source');
      const savedTarget = findBlock(savedBlocks, 'section_target');

      expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
      expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([movedBlockId, targetBlockId]);
      expectAdvancedContainerChildren(savedBlocks, blockType, movedBlockId);
    });

    test(`moves an existing ${blockType} subtree inside an empty section and preserves children`, async ({
      page,
    }) => {
      const movedBlockId = `${blockType}_move_candidate`;
      const { pageKey: formKey, pid } = await createCrossContainerAdvancedContainerFormPage(page, blockType, {
        emptyTarget: true,
      });
      await openDesigner(page, formKey);
      await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

      await page.getByTestId('designer-mode-layout').click();
      await dragCanvasBlockInto(page, movedBlockId, 'section_target');

      const sourceSection = page.getByTestId('canvas-block-section_source');
      const targetSection = page.getByTestId('canvas-block-section_target');
      await expect(sourceSection.getByTestId(`canvas-block-${movedBlockId}`)).toHaveCount(0);
      await expect(targetSection.getByTestId(`canvas-block-${movedBlockId}`)).toBeVisible();
      await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

      await saveDesignerPage(page, pid);

      const readback = await page.request.get(`/api/pages/key/${formKey}`);
      expect(readback.ok(), await readback.text()).toBe(true);
      const readbackBody = await readback.json();
      expect(readbackBody.code).toBe('0');
      const savedBlocks = readbackBody.data.blocks as TestBlock[];
      const savedSource = findBlock(savedBlocks, 'section_source');
      const savedTarget = findBlock(savedBlocks, 'section_target');

      expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
      expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([movedBlockId]);
      expectAdvancedContainerChildren(savedBlocks, blockType, movedBlockId);
    });
  }

  test('moves an existing action-bar subtree from a tab before a form-root sibling and preserves actions', async ({
    page,
  }) => {
    const { pageKey: formKey, pid } = await createCrossContainerActionBarFormPage(page, {
      source: 'tab',
    });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBeforeHeader(page, 'action_bar_move_candidate', 'section_target');

    const formRoot = page.getByTestId('canvas-block-form_root');
    const sourceTab = page.getByTestId('canvas-block-tab_source');
    await expect(sourceTab.getByTestId('canvas-block-action_bar_move_candidate')).toHaveCount(0);
    await expect(formRoot.getByTestId('canvas-block-action_bar_move_candidate')).toBeVisible();
    await expect(formRoot.getByTestId('canvas-block-section_target')).toBeVisible();
    expect(await isBeforeInDom(formRoot, 'action_bar_move_candidate', 'section_target')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'form_root');
    const savedSourceTab = findBlock(savedBlocks, 'tab_source');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual([
      'tabs_holder',
      'action_bar_move_candidate',
      'section_target',
    ]);
    expect(savedSourceTab?.blocks?.map((block) => block.id)).toEqual([]);
    expectActionBarChildren(savedBlocks, 'action_bar_move_candidate');
  });

  test('moves an existing action-bar subtree from form root inside an empty tab and preserves actions', async ({
    page,
  }) => {
    const { pageKey: formKey, pid } = await createCrossContainerActionBarFormPage(page, {
      source: 'form',
    });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'action_bar_move_candidate', 'tab_empty');

    const targetTab = page.getByTestId('canvas-block-tab_empty');
    await expect(targetTab.getByTestId('canvas-block-action_bar_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'form_root');
    const savedTargetTab = findBlock(savedBlocks, 'tab_empty');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual(['tabs_holder']);
    expect(savedTargetTab?.blocks?.map((block) => block.id)).toEqual([
      'action_bar_move_candidate',
    ]);
    expectActionBarChildren(savedBlocks, 'action_bar_move_candidate');
  });

  test('moves an existing form-section subtree from a tab before a form-root sibling and preserves fields', async ({
    page,
  }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormSectionPage(page, {
      source: 'tab',
    });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBeforeHeader(page, 'form_section_move_candidate', 'section_target');

    const formRoot = page.getByTestId('canvas-block-form_root');
    const sourceTab = page.getByTestId('canvas-block-tab_source');
    await expect(sourceTab.getByTestId('canvas-block-form_section_move_candidate')).toHaveCount(0);
    await expect(formRoot.getByTestId('canvas-block-form_section_move_candidate')).toBeVisible();
    await expect(formRoot.getByTestId('canvas-block-section_target')).toBeVisible();
    expect(await isBeforeInDom(formRoot, 'form_section_move_candidate', 'section_target')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'form_root');
    const savedSourceTab = findBlock(savedBlocks, 'tab_source');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual([
      'tabs_holder',
      'form_section_move_candidate',
      'section_target',
    ]);
    expect(savedSourceTab?.blocks?.map((block) => block.id)).toEqual([]);
    expectFormSectionChildren(savedBlocks, 'form_section_move_candidate');
  });

  test('moves an existing form-section subtree from form root inside an empty tab and preserves fields', async ({
    page,
  }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormSectionPage(page, {
      source: 'form',
    });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'form_section_move_candidate', 'tab_empty');

    const targetTab = page.getByTestId('canvas-block-tab_empty');
    await expect(targetTab.getByTestId('canvas-block-form_section_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'form_root');
    const savedTargetTab = findBlock(savedBlocks, 'tab_empty');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual(['tabs_holder']);
    expect(savedTargetTab?.blocks?.map((block) => block.id)).toEqual([
      'form_section_move_candidate',
    ]);
    expectFormSectionChildren(savedBlocks, 'form_section_move_candidate');
  });

  test('moves an existing table subtree from a tab before another list-root table and preserves columns/actions', async ({
    page,
  }) => {
    const { pageKey: listKey, pid } = await createCrossContainerListBlockPage(page, 'table', {
      source: 'tab',
    });
    await openDesigner(page, listKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBeforeHeader(page, 'table_move_candidate', 'table_target');

    const listRoot = page.getByTestId('canvas-block-list_root');
    const sourceTab = page.getByTestId('canvas-block-tab_source');
    await expect(sourceTab.getByTestId('canvas-block-table_move_candidate')).toHaveCount(0);
    await expect(listRoot.getByTestId('canvas-block-table_move_candidate')).toBeVisible();
    await expect(listRoot.getByTestId('canvas-block-table_target')).toBeVisible();
    expect(await isBeforeInDom(listRoot, 'table_move_candidate', 'table_target')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${listKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'list_root');
    const savedSourceTab = findBlock(savedBlocks, 'tab_source');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual([
      'tabs_holder',
      'action_bar_target',
      'table_move_candidate',
      'table_target',
    ]);
    expect(savedSourceTab?.blocks?.map((block) => block.id)).toEqual([]);
    expectListBlockChildren(savedBlocks, 'table', 'table_move_candidate');
  });

  test('moves an existing filter-bar subtree from list root inside an empty tab and preserves filters', async ({
    page,
  }) => {
    const { pageKey: listKey, pid } = await createCrossContainerListBlockPage(page, 'filter-bar', {
      source: 'list',
    });
    await openDesigner(page, listKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'filter_bar_move_candidate', 'tab_empty');

    const targetTab = page.getByTestId('canvas-block-tab_empty');
    await expect(targetTab.getByTestId('canvas-block-filter_bar_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${listKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedRoot = findBlock(savedBlocks, 'list_root');
    const savedTargetTab = findBlock(savedBlocks, 'tab_empty');

    expect(savedRoot?.blocks?.map((block) => block.id)).toEqual(['tabs_holder']);
    expect(savedTargetTab?.blocks?.map((block) => block.id)).toEqual([
      'filter_bar_move_candidate',
    ]);
    expectListBlockChildren(savedBlocks, 'filter-bar', 'filter_bar_move_candidate');
  });

  test('rejects moving a cross-kind block within a form designer and keeps persisted schema unchanged', async ({ page }) => {
    const { pageKey: formKey } = await createCrossKindGuardFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    const tabsRoot = page.getByTestId('canvas-block-tabs_root');
    await expect(tabsRoot.getByTestId('canvas-block-section_main')).toBeVisible();
    await expect(tabsRoot.getByTestId('canvas-block-detail_section_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabsRoot, 'section_main', 'detail_section_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await dragCanvasBlockBefore(page, 'detail_section_from_detail', 'section_main');

    await expect(tabsRoot.getByTestId('canvas-block-section_main')).toBeVisible();
    await expect(tabsRoot.getByTestId('canvas-block-detail_section_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabsRoot, 'section_main', 'detail_section_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const tabMain = findBlock(savedBlocks, 'tab_main');
    expect(tabMain?.blocks?.map((block) => block.id)).toEqual([
      'section_main',
      'detail_section_from_detail',
    ]);
  });

  test('rejects moving a kind-allowed child into an incompatible container and keeps persisted schema unchanged', async ({
    page,
  }) => {
    const { pageKey: formKey } = await createIncompatibleContainerGuardFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    const sourceSubTable = page.getByTestId('canvas-block-sub_table_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSubTable.getByTestId('canvas-block-column_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_target_name')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await dragCanvasBlockBefore(page, 'column_move_candidate', 'field_target_name');
    await waitForDesignerDragToSettle(page);

    await expect(sourceSubTable.getByTestId('canvas-block-column_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-column_move_candidate')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSubTable = findBlock(savedBlocks, 'sub_table_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');
    expect(savedSubTable?.blocks?.map((block) => block.id)).toEqual(['column_move_candidate']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['field_target_name']);
  });

  test('rejects moving a workflow block in a list designer even when the local tab can contain it', async ({
    page,
  }) => {
    const { pageKey: listKey } = await createCrossKindWorkflowGuardListPage(page);
    await openDesigner(page, listKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    const tabMain = page.getByTestId('canvas-block-tab_main');
    await expect(tabMain.getByTestId('canvas-block-table_main')).toBeVisible();
    await expect(tabMain.getByTestId('canvas-block-bpm_panel_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabMain, 'table_main', 'bpm_panel_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await dragCanvasBlockBefore(page, 'bpm_panel_from_detail', 'table_main');
    await waitForDesignerDragToSettle(page);

    await expect(tabMain.getByTestId('canvas-block-table_main')).toBeVisible();
    await expect(tabMain.getByTestId('canvas-block-bpm_panel_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabMain, 'table_main', 'bpm_panel_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const readback = await page.request.get(`/api/pages/key/${listKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedTab = findBlock(savedBlocks, 'tab_main');
    expect(savedTab?.blocks?.map((block) => block.id)).toEqual([
      'table_main',
      'bpm_panel_from_detail',
    ]);
  });

  // Guards Playwright `.dragTo()` compatibility with @dnd-kit. The wider designer
  // E2E suite (unified-designer-workbench UDW-*) drives drags via `.dragTo()`,
  // whose single jump-move pointerWithin can miss — the workbench's
  // pointerWithin→closestCenter fallback is what keeps those green.
  test('binds a model field via Playwright .dragTo() (UDW drag-driver guard)', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({ timeout: 15000 });

    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    await expect(sectionItem).toBeVisible();
    const sectionId = (await sectionItem.getAttribute('data-testid'))!.replace('outline-item-', '');
    await sectionItem.click();

    await page.getByTestId('resource-tab-fields').click();
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    await fieldItem.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    await expect(fieldItem).toBeVisible();

    const before = await page.locator('[data-testid^="canvas-block-field_"]').count();
    await fieldItem.dragTo(page.getByTestId(`canvas-block-${sectionId}`));
    await expect
      .poll(() => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(before);
  });

  // Block deletion: a designer must let users remove blocks (golden-standard
  // delete). The top-level kind container is protected; descendants are deletable.
  test('deletes a canvas block via the delete control and persists the removal', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({ timeout: 15000 });

    // The root form container has no delete control (it defines the page kind).
    const rootItem = page.locator('button[data-testid^="outline-item-"]').first();
    const rootId = (await rootItem.getAttribute('data-testid'))!.replace('outline-item-', '');
    await expect(page.getByTestId(`block-delete-${rootId}`)).toHaveCount(0);

    // Pick a deletable descendant block that exposes a delete control.
    const deletable = page.locator('[data-testid^="block-delete-"]').first();
    await expect(deletable).toBeVisible({ timeout: 10000 });
    const deleteTestId = (await deletable.getAttribute('data-testid'))!;
    const blockId = deleteTestId.replace('block-delete-', '');
    await expect(page.getByTestId(`canvas-block-${blockId}`)).toBeVisible();

    await deletable.click();

    await expect(page.getByTestId(`canvas-block-${blockId}`)).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  });
});

async function dragCanvasBlockBefore(page: Page, sourceBlockId: string, targetBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 18 });
  await page.mouse.move(dst!.x + dst!.width / 2 + 3, dst!.y + dst!.height / 2 + 3, { steps: 4 });
  await page.mouse.up();
}

async function dragCanvasBlockBeforeHeader(page: Page, sourceBlockId: string, targetBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  const targetX = dst!.x + dst!.width / 2;
  const targetY = dst!.y + Math.min(20, Math.max(12, dst!.height * 0.18));
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(targetX, targetY, { steps: 18 });
  await page.mouse.move(targetX + 3, targetY + 3, { steps: 4 });
  await page.mouse.up();
}

async function dragCanvasBlockInto(page: Page, sourceBlockId: string, parentBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${parentBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 18 });
  await page.mouse.move(dst!.x + dst!.width / 2 + 4, dst!.y + dst!.height / 2 + 4, { steps: 4 });
  await page.mouse.up();
}

async function waitForDesignerDragToSettle(page: Page) {
  await expect(page.getByTestId('drag-overlay-ghost')).toHaveCount(0);
  await expect
    .poll(() => page.locator('[data-drop-intent]:not([data-drop-intent="none"])').count())
    .toBe(0);
}

async function clickDesignerToolbarButton(page: Page, testId: string) {
  const button = page.getByTestId(testId);
  await expect(button).toBeEnabled();
  await expect
    .poll(() => receivesPointerAtCenter(button))
    .toBe(true);
  await button.hover();
  await button.click();
}

async function receivesPointerAtCenter(locator: Locator): Promise<boolean> {
  return locator.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const target = document.elementFromPoint(
      rect.left + rect.width / 2,
      rect.top + rect.height / 2,
    );
    return target === element || element.contains(target);
  });
}

async function saveDesignerPage(page: Page, pid: string): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const saveButton = page.getByTestId('designer-save');
      await expect(saveButton).toBeEnabled();
      const saveResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes(`/api/pages/${pid}`) && response.request().method() === 'PUT',
        { timeout: 5000 },
      );
      await saveButton.click();
      const saveResponse = await saveResponsePromise;
      expect(saveResponse.ok(), await saveResponse.text()).toBe(true);
      const saveBody = await saveResponse.json();
      expect(saveBody.code).toBe('0');
      await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Designer save did not complete.');
}

async function isBeforeInDom(
  container: Locator,
  beforeBlockId: string,
  afterBlockId: string,
) {
  return container
    .evaluate((containerNode, args) => {
      const beforeNode = containerNode.querySelector(`[data-testid="${args.beforeTestId}"]`);
      const afterNode = containerNode.querySelector(`[data-testid="${args.afterTestId}"]`);
      return Boolean(
        beforeNode &&
        afterNode &&
          (beforeNode.compareDocumentPosition(afterNode) & Node.DOCUMENT_POSITION_FOLLOWING),
      );
    }, {
      beforeTestId: `canvas-block-${beforeBlockId}`,
      afterTestId: `canvas-block-${afterBlockId}`,
    });
}

function findBlock(blocks: TestBlock[], blockId: string): TestBlock | null {
  for (const block of blocks) {
    if (block.id === blockId) return block;
    const child = block.blocks ? findBlock(block.blocks, blockId) : null;
    if (child) return child;
  }
  return null;
}
