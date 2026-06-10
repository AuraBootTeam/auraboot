import { describe, expect, it } from 'vitest';
import { validateStructure } from '../DslValidator';

describe('DslValidator runtime contract compatibility', () => {
  it('accepts runtime-supported action objects, localized tag labels, and registered renderer keys', () => {
    const messages = validateStructure({
      kind: 'list',
      version: '1.0.0',
      id: 'meta_models_admin',
      title: {
        'zh-CN': '模型',
        'en-US': 'Models',
      },
      layout: {
        type: 'stack',
      },
      blocks: [
        {
          id: 'model_toolbar',
          blockType: 'form-buttons',
          buttons: [
            {
              code: 'create',
              label: {
                'zh-CN': '新建模型',
                'en-US': 'New Model',
              },
              action: {
                type: 'navigate',
                to: '/meta/models/new',
              },
            },
          ],
        },
        {
          id: 'model_table',
          blockType: 'table',
          table: {
            rowKey: 'pid',
            dataSource: 'modelList',
            columns: [
              {
                field: 'code',
                cellRenderer: 'meta_model_code',
              },
              {
                field: 'status',
                valueType: 'tag',
                tagMap: {
                  draft: {
                    label: {
                      'zh-CN': '草稿',
                      'en-US': 'Draft',
                    },
                    color: 'gray',
                  },
                },
              },
              {
                field: 'pid',
                cellRenderer: 'meta_model_actions',
              },
            ],
          },
        },
      ],
    });

    expect(messages).toEqual([]);
  });

  it('accepts runtime-supported inline block dataSource configs and lightweight table blocks', () => {
    const messages = validateStructure({
      kind: 'list',
      version: '1.0.0',
      id: 'dashboard_like_list',
      title: 'Dashboard Like List',
      layout: {
        type: 'grid',
        cols: 12,
      },
      blocks: [
        {
          id: 'status_tabs',
          blockType: 'tabs',
          tabs: [
            {
              key: 'all',
              label: 'All',
            },
            {
              key: 'draft',
              label: 'Draft',
              filter: {
                field: 'status',
                operator: 'EQ',
                value: 'draft',
              },
            },
          ],
        },
        {
          id: 'recent_errors',
          blockType: 'table',
          dataSource: {
            type: 'api',
            endpoint: '/api/datasource/list',
            params: {
              datasourceId: 'nq:recent_errors',
            },
          },
          table: {
            columns: [
              {
                field: 'message',
              },
            ],
          },
        },
      ],
    });

    expect(messages).toEqual([]);
  });

  it('accepts DecisionOps typed custom blocks for DSL-hosted rollout workbench pages', () => {
    const messages = validateStructure({
      kind: 'list',
      version: '1.0.0',
      id: 'decisionops_rollouts',
      title: 'Decision Rollouts',
      layout: {
        type: 'stack',
      },
      blocks: [
        {
          id: 'rollout_monitor',
          blockType: 'custom',
          component: 'DecisionRolloutMonitorBlock',
          props: {
            initialDecisionCode: 'complaint_sla_deadline',
          },
        },
      ],
    });

    expect(messages).toEqual([]);
  });

  it('accepts DecisionOps typed custom blocks for DSL-hosted field impact pages', () => {
    const messages = validateStructure({
      kind: 'list',
      version: '1.0.0',
      id: 'decisionops_model_fields_impact_list',
      title: 'Field Impact',
      layout: {
        type: 'stack',
      },
      extension: {
        customOnly: true,
        skipFieldMeta: true,
      },
      blocks: [
        {
          id: 'decision_field_impact',
          blockType: 'custom',
          component: 'DecisionFieldImpactBlock',
          props: {
            initialFieldRef: 'record.data.priority',
            initialCurrentDataType: 'string',
          },
        },
      ],
    });

    expect(messages).toEqual([]);
  });

  it('accepts API-backed detail pages with record endpoint templates', () => {
    const messages = validateStructure({
      kind: 'detail',
      version: '1.0.0',
      id: 'decisionops_event_policies_detail',
      title: {
        'zh-CN': 'Event Policy 详情',
        'en-US': 'Event Policy Detail',
      },
      layout: {
        type: 'stack',
      },
      extension: {
        dataSource: {
          type: 'api',
          endpoint: '/api/event-policy/definitions',
          detailEndpoint: '/api/event-policy/definitions/{recordId}',
          method: 'get',
        },
        skipFieldMeta: true,
        showEdit: false,
      },
      blocks: [
        {
          id: 'event_policy_definition_overview',
          blockType: 'form-section',
          title: {
            'zh-CN': '策略概览',
            'en-US': 'Policy Overview',
          },
          fields: [
            {
              field: 'policyName',
              label: {
                'zh-CN': '策略',
                'en-US': 'Policy',
              },
            },
            {
              field: 'policyCode',
              label: {
                'zh-CN': '策略编码',
                'en-US': 'Policy Code',
              },
            },
            {
              field: 'enabled',
              label: {
                'zh-CN': '启用',
                'en-US': 'Enabled',
              },
              component: 'switch',
            },
          ],
        },
      ],
    });

    expect(messages).toEqual([]);
  });
});
