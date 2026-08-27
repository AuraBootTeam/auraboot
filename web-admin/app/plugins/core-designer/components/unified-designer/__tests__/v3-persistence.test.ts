import { describe, expect, it, vi } from 'vitest';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { loadPageSchemaV3, savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';
import type { PageSchemaV3 } from '../types';

/** A list-kind editor tree whose serialization covers the three main flat containers. */
const listDocumentV3: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'list',
  id: 'customer_list',
  pageKey: 'customer_list',
  modelCode: 'customer',
  title: { en: 'Customer List', 'zh-CN': '客户列表' },
  blocks: [
    {
      id: 'list_root',
      blockType: 'list',
      dataSource: { model: 'customer' },
      blocks: [
        {
          id: 'list_filters',
          blockType: 'filter-bar',
          region: 'filters',
          blocks: [{ id: 'filter_status', blockType: 'filter-field', field: 'status' }],
        },
        {
          id: 'list_toolbar',
          blockType: 'action-bar',
          region: 'toolbar',
          blocks: [
            { id: 'action_create', blockType: 'action', actionType: 'create', props: { label: 'Create' } },
          ],
        },
        {
          id: 'table_customers',
          blockType: 'table',
          blocks: [{ id: 'column_name', blockType: 'column', field: 'name' }],
        },
      ],
    },
  ],
};

describe('PageSchema V3 validation', () => {
  it('rejects duplicate block ids and invalid parent-child relationships', () => {
    const result = validatePageSchemaV3({
      ...samplePageSchemaV3,
      blocks: [
        {
          id: 'dashboard_sales',
          blockType: 'dashboard',
          blocks: [
            { id: 'dup', blockType: 'widget', widgetType: 'number-card' },
            { id: 'dup', blockType: 'field', field: 'name' },
          ],
        },
      ],
    });

    expect(result.valid).toBe(false);
    expect(result.errors.map((error) => error.code)).toContain('duplicate_block_id');
    expect(result.errors.map((error) => error.code)).toContain('invalid_child_block');
  });

  it('accepts the composite fixture as a valid Recursive PageSchema V3 document', () => {
    expect(validatePageSchemaV3(samplePageSchemaV3)).toEqual({ valid: true, errors: [] });
  });

  it('accepts row action blocks attached to table blocks', () => {
    const result = validatePageSchemaV3({
      schemaVersion: 3,
      kind: 'list',
      id: 'customer_list',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          blocks: [
            {
              id: 'table_customers',
              blockType: 'table',
              blocks: [
                { id: 'column_name', blockType: 'column', field: 'name' },
                {
                  id: 'action_open_row',
                  blockType: 'action',
                  region: 'row-actions',
                  actionType: 'command',
                },
              ],
            },
          ],
        },
      ],
    });

    expect(result).toEqual({ valid: true, errors: [] });
  });
});

describe('PageSchema V3 repository', () => {
  it('loads V3 documents from backend page DTOs', async () => {
    const api = {
      getPageByPid: vi.fn().mockResolvedValue({
        code: '0',
        data: {
          pid: 'page_1',
          pageKey: 'customer_workspace',
          name: 'customer_workspace',
          title: 'Customer Workspace',
          kind: 'composite',
          schemaVersion: 3,
          blocks: samplePageSchemaV3.blocks,
          layout: samplePageSchemaV3.layout,
          modelCode: 'customer',
          extension: { source: 'test' },
        },
      }),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn(),
    };

    const loaded = await loadPageSchemaV3({ pageId: 'page_1', api });

    expect(api.getPageByPid).toHaveBeenCalledWith('page_1');
    expect(loaded.document).toMatchObject({
      schemaVersion: 3,
      id: 'customer_workspace',
      pageKey: 'customer_workspace',
      kind: 'composite',
      modelCode: 'customer',
    });
    expect(loaded.source).toEqual({ type: 'page', pid: 'page_1', pageKey: 'customer_workspace' });
  });

  it('loads recursive kind-root rows under a flat label as editor trees (rollback restoration)', async () => {
    const recursiveBlocks = [
      {
        id: 'list_customer_workspace',
        blockType: 'list',
        title: 'Saved V3 List',
        blocks: [
          {
            id: 'table_customer_workspace',
            blockType: 'table',
            blocks: [{ id: 'column_name', blockType: 'column', field: 'name' }],
          },
        ],
      },
    ];
    const api = {
      getPageByPid: vi.fn().mockResolvedValue({
        code: '0',
        data: {
          pid: 'page_1',
          pageKey: 'customer_workspace',
          name: 'customer_workspace',
          title: 'Customer Workspace',
          kind: 'list',
          schemaVersion: 2,
          blocks: recursiveBlocks,
          modelCode: 'customer',
        },
      }),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn(),
    };

    // A top-level kind root is an unambiguous tree signature (the flat dialect
    // never places kind containers at the top level), so the row loads as the
    // editor tree — version rollback can legitimately produce this shape.
    const loaded = await loadPageSchemaV3({ pageId: 'page_1', api });
    expect(loaded.document.schemaVersion).toBe(3);
    expect(loaded.document.blocks).toEqual(recursiveBlocks);
  });

  it('rejects stored pages with unknown schemaVersion labels', async () => {
    const api = {
      getPageByPid: vi.fn().mockResolvedValue({
        code: '0',
        data: {
          pid: 'page_1',
          pageKey: 'customer_workspace',
          kind: 'list',
          schemaVersion: 5,
          blocks: [],
        },
      }),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn(),
    };

    await expect(loadPageSchemaV3({ pageId: 'page_1', api })).rejects.toThrow(
      /unsupported schemaVersion 5/,
    );
  });

  it('saves list documents to existing backend pages as flat v4 blocks', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn().mockResolvedValue({ code: '0', data: { pid: 'page_1' } }),
      createPage: vi.fn(),
    };

    const result = await savePageSchemaV3({
      document: listDocumentV3,
      source: { type: 'page', pid: 'page_1', pageKey: 'customer_list' },
      api,
    });

    expect(result.ok).toBe(true);
    expect(api.updatePage).toHaveBeenCalledWith(
      'page_1',
      expect.objectContaining({
        schemaVersion: 4,
        kind: 'list',
        pageKey: 'customer_list',
      }),
    );
    const request = api.updatePage.mock.calls[0][1];
    // The tree's filter-bar/action-bar/table containers are serialized back to
    // the flat runtime dialect — the DynamicPageRenderer never sees tree blocks.
    expect(request.blocks).toEqual([
      expect.objectContaining({ blockType: 'filters' }),
      expect.objectContaining({ blockType: 'toolbar' }),
      expect.objectContaining({ blockType: 'table' }),
    ]);
    // The editor's synthetic kind-root id rides in the page extension so a
    // reload rebuilds the identical root (outline / audit paths stay stable).
    expect(request.extension).toMatchObject({ designerRootId: 'list_root' });
  });

  it('refuses to persist composite documents that have no flat v4 representation', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn(),
    };

    const result = await savePageSchemaV3({
      document: samplePageSchemaV3,
      source: { type: 'page', pid: 'page_1', pageKey: 'customer_workspace' },
      api,
    });

    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/flat v4 runtime dialect/);
    expect(api.updatePage).not.toHaveBeenCalled();
  });

  it('does not save invalid V3 documents', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn(),
    };

    const result = await savePageSchemaV3({
      document: { ...samplePageSchemaV3, blocks: [] },
      source: { type: 'page', pid: 'page_1' },
      api,
    });

    expect(result.ok).toBe(false);
    expect(result.validation?.errors[0]?.code).toBe('empty_blocks');
    expect(api.updatePage).not.toHaveBeenCalled();
  });

  it('creates new pages with flat v4 blocks', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn().mockResolvedValue({ code: '0', data: { pid: 'page_2' } }),
    };

    const result = await savePageSchemaV3({
      document: listDocumentV3,
      source: { type: 'local', pageKey: 'customer_list' },
      api,
    });

    expect(result.ok).toBe(true);
    expect(api.createPage).toHaveBeenCalledWith(
      expect.objectContaining({
        schemaVersion: 4,
        kind: 'list',
      }),
    );
  });
});
