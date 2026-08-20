import { describe, expect, it } from 'vitest';
import type { AuthoringSyntheticPreview } from '~/framework/meta/authoring/types';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { PageSchemaV3 } from '../types';
import {
  applySyntheticPreviewToDocument,
  createSyntheticPreviewRuntimeServices,
} from '../preview/syntheticPreview';

const preview: AuthoringSyntheticPreview = {
  mode: 'SYNTHETIC',
  pagePid: 'page-1',
  source: 'GENERATED_IN_MEMORY',
  isolatedFromTenantData: true,
  persisted: false,
  exportAllowed: false,
  businessActionsAllowed: false,
  fixtureRevision: 7,
  formValues: { name: 'Sample name 01', pid: 'synthetic-001' },
  records: [
    { name: 'Sample name 01', pid: 'synthetic-001' },
    { name: 'Sample name 02', pid: 'synthetic-002' },
  ],
  widgets: {
    revenue: {
      source: 'GENERATED_IN_MEMORY',
      value: '128',
      series: [{ label: 'Sample A', value: 24 }],
    },
  },
};

describe('synthetic preview boundary', () => {
  it('replaces embedded rows and widgets with the generated fixture', () => {
    const document: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'composite',
      id: 'page',
      blocks: [
        {
          id: 'table',
          blockType: 'table',
          props: { rows: [{ pid: 'real-record', secret: 'REAL-TENANT-SECRET' }] },
          blocks: [{ id: 'name', blockType: 'column', field: 'name' }],
        },
        { id: 'revenue', blockType: 'widget', widgetType: 'number-card' },
      ],
    };

    const synthetic = applySyntheticPreviewToDocument(document, preview);

    expect(synthetic.blocks[0].props?.rows).toEqual(preview.records);
    expect(synthetic.blocks[1].props).toMatchObject({
      value: '128',
      source: 'GENERATED_IN_MEMORY',
    });
    expect(JSON.stringify(synthetic)).not.toContain('REAL-TENANT-SECRET');
    expect(JSON.stringify(synthetic)).not.toContain('real-record');
  });

  it('only serves fixture data and rejects every business action', async () => {
    const services = createSyntheticPreviewRuntimeServices(preview);
    const widget = { id: 'revenue', blockType: 'widget' } as const;
    const picker = { id: 'picker', blockType: 'field' } as const;

    await expect(services.loadWidgetData?.(widget)).resolves.toMatchObject({
      source: 'GENERATED_IN_MEMORY',
      value: '128',
    });
    await expect(
      services.loadPickerOptions?.(picker, {
        source: 'unified-designer-runtime-preview',
        pageId: 'page',
        pageKind: 'form',
        schemaVersion: 3,
        blockId: 'picker',
        blockType: 'field',
        blockPath: ['picker'],
        pickerSearch: '02',
      }),
    ).resolves.toEqual([
      {
        label: 'Sample name 02',
        value: 'synthetic-002',
        record: preview.records[1],
      },
    ]);
    await expect(services.executeAction?.({ id: 'save', blockType: 'action' })).rejects.toMatchObject(
      {
        code: 'SYNTHETIC_PREVIEW_ACTION_DISABLED',
        kind: 'permission',
      },
    );
  });

  it('keeps the isolation contract bilingual', () => {
    expect(resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.title, 'zh-CN')).toBe(
      '合成数据预览',
    );
    expect(resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.isolated, 'en-US')).toBe(
      'No tenant business records queried',
    );
  });
});
