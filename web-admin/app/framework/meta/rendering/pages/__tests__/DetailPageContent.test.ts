import { describe, expect, it } from 'vitest';
import {
  buildDetailRecordEndpoint,
  collectDetailDictCodes,
  enrichDetailField,
  resolveActiveDetailTab,
  resolveDetailFieldComponent,
  resolveSubTableDataSourceConfig,
  shouldRenderDefaultDetailEditAction,
} from '../DetailPageContent';

describe('buildDetailRecordEndpoint', () => {
  it('builds the direct record endpoint used by detail pages', () => {
    expect(buildDetailRecordEndpoint('showcase_all_fields', '01KPTMPKJEAC6QHW08PE9JE62W')).toBe(
      '/api/dynamic/showcase_all_fields/01KPTMPKJEAC6QHW08PE9JE62W',
    );
  });

  it('builds API data source detail endpoints from a template', () => {
    expect(
      buildDetailRecordEndpoint('decision_rollout_policy', '01KTPDRQ6TD9JAXRCPZ2KY3ZS7', {
        dataSource: {
          type: 'api',
          endpoint: '/api/decision/rollouts',
          detailEndpoint: '/api/decision/rollouts/{pid}',
        },
      } as any),
    ).toBe('/api/decision/rollouts/01KTPDRQ6TD9JAXRCPZ2KY3ZS7');
  });

  it('falls back to appending the record id to an API data source endpoint', () => {
    expect(
      buildDetailRecordEndpoint('decision_rollout_policy', 'rollout-pid', {
        dataSource: {
          type: 'api',
          endpoint: '/api/decision/rollouts/',
        },
      } as any),
    ).toBe('/api/decision/rollouts/rollout-pid');
  });
});

describe('shouldRenderDefaultDetailEditAction', () => {
  it('keeps the default edit action visible when the schema does not opt out', () => {
    expect(shouldRenderDefaultDetailEditAction({ extension: {} })).toBe(true);
    expect(shouldRenderDefaultDetailEditAction(null)).toBe(true);
  });

  it('hides the default edit action when extension.showEdit is false', () => {
    expect(shouldRenderDefaultDetailEditAction({ extension: { showEdit: false } })).toBe(false);
  });
});

describe('resolveDetailFieldComponent', () => {
  it('maps json dataType to jsonviewer for read-only detail rendering', () => {
    expect(resolveDetailFieldComponent({ dataType: 'json' })).toBe('jsonviewer');
    expect(resolveDetailFieldComponent({ dataType: 'jsonb' })).toBe('jsonviewer');
  });

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
  it('uses jsonviewer for JSON field codes even when field-meta is unavailable', () => {
    expect(
      enrichDetailField({ field: 'bom_sfp_llm_policy_json', label: 'LLM 策略 JSON' } as any),
    ).toMatchObject({
      field: 'bom_sfp_llm_policy_json',
      component: 'jsonviewer',
    });
  });

  it('uses jsonviewer for field codes that explicitly store JSON strings', () => {
    expect(
      enrichDetailField({ field: 'bom_sfp_header_rule_json', label: '表头规则 JSON' } as any, {
        code: 'bom_sfp_header_rule_json',
        dataType: 'string',
      }),
    ).toMatchObject({
      field: 'bom_sfp_header_rule_json',
      component: 'jsonviewer',
    });
  });

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

describe('resolveActiveDetailTab', () => {
  const tabs = [
    { key: 'overview', label: 'Overview', blocks: [] },
    { key: 'review', label: 'Review', blocks: [] },
  ] as any;

  it('returns only the requested active tab', () => {
    expect(resolveActiveDetailTab(tabs, 1)).toMatchObject({
      index: 1,
      tab: { key: 'review' },
    });
  });

  it('falls back to the first tab when the active index is out of range', () => {
    expect(resolveActiveDetailTab(tabs, 99)).toMatchObject({
      index: 0,
      tab: { key: 'overview' },
    });
  });

  it('returns null when no tabs are configured', () => {
    expect(resolveActiveDetailTab([], 0)).toBeNull();
  });
});
