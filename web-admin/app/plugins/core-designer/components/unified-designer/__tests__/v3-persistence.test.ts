import { describe, expect, it, vi } from 'vitest';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { loadPageSchemaV3, savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';

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

  it('does not remigrate recursive V3 blocks when an older backend returns a stale schemaVersion', async () => {
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

    const loaded = await loadPageSchemaV3({ pageId: 'page_1', api });

    expect(loaded.document.schemaVersion).toBe(3);
    expect(loaded.document.blocks).toEqual(recursiveBlocks);
    expect(loaded.document.blocks[0]?.title).toBe('Saved V3 List');
  });

  it('saves valid V3 documents back to existing backend pages', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn().mockResolvedValue({ code: '0', data: { pid: 'page_1' } }),
      createPage: vi.fn(),
    };

    const result = await savePageSchemaV3({
      document: samplePageSchemaV3,
      source: { type: 'page', pid: 'page_1', pageKey: 'customer_workspace' },
      api,
    });

    expect(result.ok).toBe(true);
    expect(api.updatePage).toHaveBeenCalledWith(
      'page_1',
      expect.objectContaining({
        schemaVersion: 3,
        kind: 'composite',
        blocks: samplePageSchemaV3.blocks,
        layout: samplePageSchemaV3.layout,
        pageKey: 'customer_workspace',
      }),
    );
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

  it('creates new pages with schemaVersion 3', async () => {
    const api = {
      getPageByPid: vi.fn(),
      getPageByPageKey: vi.fn(),
      updatePage: vi.fn(),
      createPage: vi.fn().mockResolvedValue({ code: '0', data: { pid: 'page_2' } }),
    };

    const result = await savePageSchemaV3({
      document: samplePageSchemaV3,
      source: { type: 'local', pageKey: 'customer_workspace' },
      api,
    });

    expect(result.ok).toBe(true);
    expect(api.createPage).toHaveBeenCalledWith(
      expect.objectContaining({
        schemaVersion: 3,
        blocks: samplePageSchemaV3.blocks,
      }),
    );
  });
});
