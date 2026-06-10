import { describe, expect, it } from 'vitest';
import {
  buildListReferenceDisplayCacheKey,
  collectListReferenceDisplayConfigs,
  resolveTableBlockRowActions,
  resolveListMiscBlocksPosition,
  shouldSkipListData,
  shouldSkipModelFieldMeta,
} from '../ListPageContent';

describe('collectListReferenceDisplayConfigs', () => {
  it('uses model field refTarget metadata to resolve reference display labels', () => {
    const configs = collectListReferenceDisplayConfigs(
      [
        {
          field: 'bom_task_project_id',
          label: 'Project',
        },
      ],
      new Map([
        [
          'bom_task_project_id',
          {
            code: 'bom_task_project_id',
            dataType: 'reference',
            extension: {
              refTarget: {
                modelCode: 'bom_project',
                displayField: 'bom_project_name',
              },
            },
          },
        ],
      ]),
    );

    expect(configs).toEqual([
      {
        field: 'bom_task_project_id',
        modelCode: 'bom_project',
        valueField: 'pid',
        displayField: 'bom_project_name',
        displayKey: 'bom_task_project_id_display',
      },
    ]);
    expect(buildListReferenceDisplayCacheKey(configs[0])).toBe(
      'bom_task_project_id|bom_project|pid|bom_project_name',
    );
  });
});

describe('resolveTableBlockRowActions', () => {
  it('keeps nested table.rowActions executable for API-backed DSL pages', () => {
    expect(
      resolveTableBlockRowActions({
        blockType: 'table',
        rowActions: [{ code: 'detail' }],
        table: {
          rowActions: [
            { code: 'test', action: { type: 'flow' } },
            { code: 'delete', confirm: 'confirm.delete' },
          ],
        },
      }).map((action) => action.code),
    ).toEqual(['detail', 'test', 'delete']);
  });

  it('deduplicates table.rowActions already present at block level', () => {
    expect(
      resolveTableBlockRowActions({
        blockType: 'table',
        rowActions: [{ code: 'detail', label: 'Block Detail' }],
        table: {
          rowActions: [
            { code: 'detail', label: 'Nested Detail' },
            { code: 'console' },
          ],
        },
      }),
    ).toEqual([
      { code: 'detail', label: 'Block Detail' },
      { code: 'console' },
    ]);
  });
});

describe('shouldSkipListData', () => {
  it('skips dynamic list loading when the page opts in explicitly', () => {
    expect(
      shouldSkipListData({
        extension: { skipListData: true },
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('treats custom-only list pages as runtime block hosts', () => {
    expect(
      shouldSkipListData({
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('keeps normal table-backed list pages on dynamic data loading', () => {
    expect(
      shouldSkipListData({
        blocks: [
          {
            id: 'table',
            blockType: 'table',
            table: { columns: [{ field: 'name' }] },
          },
          {
            id: 'summary',
            blockType: 'custom',
            component: 'SummaryBlock',
          },
        ],
      }),
    ).toBe(false);
  });
});

describe('shouldSkipModelFieldMeta', () => {
  it('skips dynamic field metadata for custom-only host pages', () => {
    expect(
      shouldSkipModelFieldMeta({
        blocks: [
          {
            id: 'rollout_monitor',
            blockType: 'custom',
            component: 'DecisionRolloutMonitorBlock',
          },
        ],
      }),
    ).toBe(true);
  });

  it('lets API-backed DSL pages opt out of dynamic model field metadata', () => {
    expect(
      shouldSkipModelFieldMeta({
        dataSource: { type: 'api', endpoint: '/api/decision/rollouts' },
        extension: { skipFieldMeta: true },
        blocks: [
          {
            id: 'rollout_policy_table',
            blockType: 'table',
            table: { columns: [{ field: 'decisionCode' }] },
          },
        ],
      }),
    ).toBe(true);
  });

  it('keeps normal table-backed list pages on field metadata loading', () => {
    expect(
      shouldSkipModelFieldMeta({
        blocks: [
          {
            id: 'table',
            blockType: 'table',
            table: { columns: [{ field: 'name' }] },
          },
        ],
      }),
    ).toBe(false);
  });
});

describe('resolveListMiscBlocksPosition', () => {
  it('defaults misc blocks after the table', () => {
    expect(resolveListMiscBlocksPosition({ extension: {} })).toBe('afterTable');
  });

  it('allows DSL pages to place custom blocks before the table', () => {
    expect(resolveListMiscBlocksPosition({ extension: { miscBlocksPosition: 'beforeTable' } })).toBe(
      'beforeTable',
    );
  });
});
