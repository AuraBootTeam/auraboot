import { describe, expect, it } from 'vitest';
import {
  buildDetailRecordEndpoint,
  collectDetailDictCodes,
  enrichDetailField,
  extractBlockDataRows,
  getByDataPath,
  injectDetailRecordValueIntoCustomBlock,
  mergeDetailDisplayFields,
  resolveActiveDetailTab,
  resolveDetailFieldComponent,
  resolveDetailRecordEndpoint,
  resolveHiddenSystemTabKeys,
  resolveSubTableDataSourceConfig,
  resolveVisibleDetailTabs,
  shouldRenderDefaultDetailEditAction,
  unwrapDetailRecord,
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

  it('prefers recordPid placeholders for API data source detail endpoint templates', () => {
    expect(
      buildDetailRecordEndpoint('decision_rollout_policy', '01KTPDRQ6TD9JAXRCPZ2KY3ZS7', {
        dataSource: {
          type: 'api',
          detailEndpoint: '/api/decision/rollouts/{recordPid}',
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

describe('mergeDetailDisplayFields', () => {
  it('adds reference display fields returned by the list endpoint without overwriting raw values', () => {
    expect(
      mergeDetailDisplayFields(
        {
          pid: 'lead-1',
          crm_lead_assigned_to: 'user-pid-1',
          crm_lead_company: 'ACME',
        },
        {
          pid: 'lead-1',
          crm_lead_assigned_to: 'user-pid-1',
          crm_lead_assigned_to_display: 'e2e-operator',
          crm_lead_company: 'ACME from list',
        },
      ),
    ).toEqual({
      pid: 'lead-1',
      crm_lead_assigned_to: 'user-pid-1',
      crm_lead_assigned_to_display: 'e2e-operator',
      crm_lead_company: 'ACME',
    });
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

describe('injectDetailRecordValueIntoCustomBlock', () => {
  it('passes a detail record valueField into custom block props', () => {
    const block = {
      id: 'sla_rule_binding',
      blockType: 'custom',
      component: 'DecisionRuleBindingBlock',
      props: {
        valueField: 'rule_binding',
        variant: 'summary',
      },
    } as any;
    const ruleBinding = {
      type: 'jsonb',
      value:
        '{"bindingKind":"DECISION_REF","decisionBinding":{"decisionCode":"complaint_sla_deadline","inputMappings":[{"input":"priority","source":{"kind":"FIELD","scope":"record","path":"data.priority"}}],"outputMappings":[],"versionPolicy":"LATEST_PUBLISHED","fallbackPolicy":{"mode":"FAIL_CLOSED"},"traceMode":"SAMPLED","enabled":true},"enabled":true}',
      null: false,
    };

    const injected = injectDetailRecordValueIntoCustomBlock(block, {
      pid: 'record-1',
      rule_binding: ruleBinding,
    });

    expect((injected as any).props.value).toBe(ruleBinding);
    expect((injected as any).props.valueField).toBe('rule_binding');
  });

  it('does not overwrite an explicit custom block value', () => {
    const explicit = { bindingKind: 'DECISION_REF' };
    const block = {
      id: 'sla_rule_binding',
      blockType: 'custom',
      component: 'DecisionRuleBindingBlock',
      props: {
        valueField: 'rule_binding',
        value: explicit,
      },
    } as any;

    const injected = injectDetailRecordValueIntoCustomBlock(block, {
      rule_binding: { bindingKind: 'OTHER' },
    });

    expect((injected as any).props.value).toBe(explicit);
  });
});

describe('resolveHiddenSystemTabKeys', () => {
  it('normalizes hidden system tab keys from array and comma-separated config', () => {
    expect([
      ...resolveHiddenSystemTabKeys({
        extension: { hiddenSystemTabs: ['__approval_comments__', ' __field_history__ '] },
      }),
    ]).toEqual(['__approval_comments__', '__field_history__']);

    expect([
      ...resolveHiddenSystemTabKeys({
        extension: { hideSystemTabs: '__approval_comments__, __field_history__' },
      }),
    ]).toEqual(['__approval_comments__', '__field_history__']);
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

describe('resolveVisibleDetailTabs', () => {
  const tabs = [
    { key: 'overview', label: 'Overview', blocks: [] },
    { key: '__comments__', label: 'Comments', system: true, blocks: [] },
    { key: '__approval_comments__', label: 'Approval Comments', system: true, blocks: [] },
    { key: '__field_history__', label: 'Field History', system: true, blocks: [] },
  ] as any;

  it('hides all system tabs while creating a new record', () => {
    expect(
      resolveVisibleDetailTabs(tabs, undefined, { extension: {} }).map((tab) => tab.key),
    ).toEqual(['overview']);
  });

  it('hides only configured system tabs for an existing record', () => {
    expect(
      resolveVisibleDetailTabs(tabs, '01KTEST', {
        extension: { hiddenSystemTabs: ['__approval_comments__', '__field_history__'] },
      }).map((tab) => tab.key),
    ).toEqual(['overview', '__comments__']);
  });
});

describe('getByDataPath', () => {
  it('reads nested values by dot-path', () => {
    expect(getByDataPath({ a: { b: { c: 7 } } }, 'a.b.c')).toBe(7);
    expect(getByDataPath({ version: { versionNo: 2 } }, 'version')).toEqual({ versionNo: 2 });
  });
  it('returns the object itself when path is empty, undefined for missing segments', () => {
    const obj = { x: 1 };
    expect(getByDataPath(obj, '')).toBe(obj);
    expect(getByDataPath(obj, undefined)).toBe(obj);
    expect(getByDataPath(obj, 'y.z')).toBeUndefined();
    expect(getByDataPath(null, 'a')).toBeNull();
  });
});

describe('resolveDetailRecordEndpoint', () => {
  it('falls back to the dynamic-model convention endpoint when no extension.dataSource', () => {
    expect(resolveDetailRecordEndpoint({}, 'showcase_all_fields', '900202')).toEqual({
      endpoint: '/api/dynamic/showcase_all_fields/900202',
      method: 'get',
    });
  });
  it('prefers schema.modelCode over tableName for the fallback endpoint', () => {
    expect(resolveDetailRecordEndpoint({ modelCode: 'crm_activity' }, 'crm-my-tasks', '5')).toEqual(
      { endpoint: '/api/dynamic/crm_activity/5', method: 'get' },
    );
  });
  it('uses a custom api dataSource endpoint, replacing the {id} placeholder', () => {
    expect(
      resolveDetailRecordEndpoint(
        {
          extension: {
            dataSource: { type: 'api', method: 'get', endpoint: '/api/billing/plans/{id}' },
          },
        },
        'billing_plan_catalog_detail',
        '900202',
      ),
    ).toEqual({ endpoint: '/api/billing/plans/900202', method: 'get' });
  });
  it('uses recordPid placeholders for custom api dataSource endpoints', () => {
    expect(
      resolveDetailRecordEndpoint(
        {
          extension: {
            dataSource: { type: 'api', endpoint: '/api/billing/plans/{recordPid}' },
          },
        },
        'billing_plan_catalog_detail',
        '900202',
      ),
    ).toEqual({ endpoint: '/api/billing/plans/900202', method: 'get' });

    expect(
      resolveDetailRecordEndpoint(
        {
          extension: {
            dataSource: { type: 'api', endpoint: '/api/billing/plans/${recordPid}' },
          },
        },
        'billing_plan_catalog_detail',
        'abc/def',
      ),
    ).toEqual({ endpoint: '/api/billing/plans/abc%2Fdef', method: 'get' });
  });
  it('appends /{recordPid} when the api endpoint has no placeholder, and honors post', () => {
    expect(
      resolveDetailRecordEndpoint(
        {
          extension: {
            dataSource: { type: 'api', method: 'POST', endpoint: '/api/billing/plans/' },
          },
        },
        't',
        '900202',
      ),
    ).toEqual({ endpoint: '/api/billing/plans/900202', method: 'post' });
  });
  it('url-encodes the record id', () => {
    expect(
      resolveDetailRecordEndpoint(
        { extension: { dataSource: { type: 'api', endpoint: '/api/x/{id}' } } },
        't',
        'a b',
      ).endpoint,
    ).toBe('/api/x/a%20b');
  });
});

describe('unwrapDetailRecord', () => {
  it('returns the recordPath-selected object as the master record', () => {
    expect(
      unwrapDetailRecord({ version: { versionNo: 2 }, priceComponents: [] }, 'version'),
    ).toEqual({
      versionNo: 2,
    });
  });
  it('returns the raw payload when no recordPath', () => {
    expect(unwrapDetailRecord({ a: 1 })).toEqual({ a: 1 });
  });
  it('returns {} for null payload or non-object recordPath target', () => {
    expect(unwrapDetailRecord(null, 'version')).toEqual({});
    expect(unwrapDetailRecord({ version: 'oops' }, 'version')).toEqual({});
  });
});

describe('extractBlockDataRows', () => {
  it('extracts a nested array for a block dataPath', () => {
    const raw = { version: {}, priceComponents: [{ id: 1 }, { id: 2 }] };
    expect(extractBlockDataRows(raw, 'priceComponents')).toEqual([{ id: 1 }, { id: 2 }]);
  });
  it('returns [] when the path is missing, not an array, or dataPath absent', () => {
    expect(extractBlockDataRows({ a: 1 }, 'priceComponents')).toEqual([]);
    expect(extractBlockDataRows({ priceComponents: 'x' }, 'priceComponents')).toEqual([]);
    expect(extractBlockDataRows({ a: [] }, undefined)).toEqual([]);
    expect(extractBlockDataRows(null, 'a')).toEqual([]);
  });
});
