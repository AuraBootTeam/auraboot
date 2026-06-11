import { describe, expect, it } from 'vitest';
import {
  buildListReferenceDisplayCacheKey,
  collectListReferenceDisplayConfigs,
  resolveFieldMetaDisplayName,
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
                modelCode: 'req_requirement_set_pcba_bom',
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
        modelCode: 'req_requirement_set_pcba_bom',
        valueField: 'pid',
        displayField: 'bom_project_name',
        displayKey: 'bom_task_project_id_display',
      },
    ]);
    expect(buildListReferenceDisplayCacheKey(configs[0])).toBe(
      'bom_task_project_id|req_requirement_set_pcba_bom|pid|bom_project_name',
    );
  });
});

describe('resolveFieldMetaDisplayName', () => {
  const map = new Map<string, any>([
    ['crm_crq_code', { code: 'crm_crq_code', displayName: 'RFQ编号' }],
    ['crm_crq_qty', { code: 'crm_crq_qty', extension: { displayName: '预估数量' } }],
    ['crm_crq_raw', { code: 'crm_crq_raw', displayName: 'crm_crq_raw' }],
    ['crm_crq_blank', { code: 'crm_crq_blank', displayName: '   ' }],
  ]);

  it('prefers the field displayName from field-meta', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_code', map)).toBe('RFQ编号');
  });

  it('falls back to extension.displayName', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_qty', map)).toBe('预估数量');
  });

  it('returns undefined when displayName is missing, blank, or just the raw code', () => {
    expect(resolveFieldMetaDisplayName('crm_crq_raw', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('crm_crq_blank', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('unknown_field', map)).toBeUndefined();
    expect(resolveFieldMetaDisplayName('crm_crq_code', undefined)).toBeUndefined();
  });
});
