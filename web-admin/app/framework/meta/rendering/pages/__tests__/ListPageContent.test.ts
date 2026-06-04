import { describe, expect, it } from 'vitest';
import {
  buildListReferenceDisplayCacheKey,
  collectListReferenceDisplayConfigs,
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
