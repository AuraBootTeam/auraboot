import { describe, expect, it } from 'vitest';
import {
  buildDetailRecordEndpoint,
  collectDetailDictCodes,
  enrichDetailField,
  resolveDetailFieldComponent,
  resolveSubTableDataSourceConfig,
} from '../DetailPageContent';

describe('buildDetailRecordEndpoint', () => {
  it('builds the direct record endpoint used by detail pages', () => {
    expect(buildDetailRecordEndpoint('showcase_all_fields', '01KPTMPKJEAC6QHW08PE9JE62W')).toBe(
      '/api/dynamic/showcase_all_fields/01KPTMPKJEAC6QHW08PE9JE62W',
    );
  });
});

describe('resolveDetailFieldComponent', () => {
  it('maps file dataType to fileattachment for read-only detail rendering', () => {
    expect(resolveDetailFieldComponent({ dataType: 'file' })).toBe('fileattachment');
  });

  it('prefers explicit renderComponent over dataType defaults', () => {
    expect(
      resolveDetailFieldComponent({
        dataType: 'json',
        extension: { renderComponent: 'richtext' },
      }),
    ).toBe('richtext');
  });

  it('keeps existing primitive mappings for dates', () => {
    expect(resolveDetailFieldComponent({ dataType: 'date' })).toBe('date');
    expect(resolveDetailFieldComponent({ dataType: 'datetime' })).toBe('datetime');
  });

  it('maps sys_user reference metadata to userselect for read-only labels', () => {
    expect(
      resolveDetailFieldComponent({
        dataType: 'reference',
        extension: {
          refTarget: {
            targetModel: 'sys_user',
            targetField: 'username',
          },
        },
      }),
    ).toBe('userselect');
  });
});

describe('enrichDetailField', () => {
  it('uses extension dictCode, renderComponent, and displayName from field-meta', () => {
    expect(
      enrichDetailField({ field: 'sc_cascade_category', label: 'SC_CASCADE_CATEGORY' } as any, {
        code: 'sc_cascade_category',
        dataType: 'string',
        extension: {
          dictCode: 'sc_cascade_category_dict',
          renderComponent: 'cascadeselect',
          displayName: '级联分类',
        },
      }),
    ).toMatchObject({
      field: 'sc_cascade_category',
      label: '级联分类',
      dictCode: 'sc_cascade_category_dict',
      component: 'cascadeselect',
      props: {
        dictCode: 'sc_cascade_category_dict',
      },
    });
  });
});

describe('collectDetailDictCodes', () => {
  it('collects dict codes declared inside field-meta extension props', () => {
    const modelFieldMap = new Map<string, any>([
      [
        'sc_tree_node',
        {
          code: 'sc_tree_node',
          extension: { dictCode: 'sc_tree_dept_dict' },
        },
      ],
    ]);

    expect(
      collectDetailDictCodes(
        {
          blocks: [
            {
              id: 'tabs-main',
              blockType: 'tabs',
              tabs: [
                {
                  key: 'main',
                  label: 'Main',
                  blocks: [
                    {
                      id: 'tab-form-section',
                      blockType: 'form-section',
                      fields: [{ field: 'sc_tree_node' }],
                    },
                  ],
                },
              ],
            },
          ],
        },
        modelFieldMap,
      ),
    ).toEqual(['sc_tree_dept_dict']);
  });
});

describe('resolveSubTableDataSourceConfig', () => {
  it('resolves canonical block dataSource IDs from schema dataSources before rendering sub-tables', () => {
    const config = {
      id: 'subtable_ds_dataSource',
      type: 'api' as const,
      endpoint: '/api/dynamic/showcase_all_fields/list',
      params: {
        pageNum: '1',
        pageSize: '5',
      },
    };

    expect(
      resolveSubTableDataSourceConfig('subtable_ds_dataSource', {
        subtable_ds_dataSource: config,
      }),
    ).toEqual(config);
  });
});
