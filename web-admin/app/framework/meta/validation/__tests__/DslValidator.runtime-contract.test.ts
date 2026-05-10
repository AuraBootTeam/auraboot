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
});
