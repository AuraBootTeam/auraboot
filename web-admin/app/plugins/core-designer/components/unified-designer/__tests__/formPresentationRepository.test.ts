import { describe, expect, it, vi } from 'vitest';
import { migratePageSchemaV2ToV3 } from '../migration/migrateToV3';
import { savePageSchemaV3 } from '../persistence/pageSchemaV3Repository';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';

describe('form presentation property persistence', () => {
  it('registers orthogonal page properties in the shared inspector schema', () => {
    expect(defaultInspectorSchemaRegistry.getFields('form').map(field => field.key)).toEqual(expect.arrayContaining(['extension.formPresentation.width', 'extension.formPresentation.columns', 'extension.formPresentation.actions']));
  });
  it('saves presentation beside unrelated extension data and reopens it with v4 blocks', async () => {
    const extension = { presentation: 'workspace', attachmentFields: ['files'], formPresentation: { width: 'comfortable', columns: 1, actions: 'inline' } };
    const document = migratePageSchemaV2ToV3({ schemaVersion: 4, id: 'delivery_form', kind: 'form', modelCode: 'delivery', extension, blocks: [{ id: 'basics', blockType: 'form-section', fields: [{ field: 'name', component: 'SmartInput' }] }] });
    const updatePage = vi.fn().mockResolvedValue({ code: '0' });
    const result = await savePageSchemaV3({ document, source: { type: 'page', pid: 'page-1' }, api: { getPageByPid: vi.fn(), getPageByPageKey: vi.fn(), createPage: vi.fn(), updatePage } });
    expect(result.ok).toBe(true);
    const request = updatePage.mock.calls[0][1];
    expect(request.schemaVersion).toBe(4);
    expect(request.extension).toMatchObject(extension);
    const reopened = migratePageSchemaV2ToV3({ ...request, id: 'delivery_form' });
    expect(reopened.extension?.formPresentation).toEqual(extension.formPresentation);
    expect(reopened.blocks[0].blocks?.[0].blocks?.[0].props?.component).toBe('SmartInput');
  });
  it('blocks invalid authoring settings before sending an API request', async () => {
    const document = migratePageSchemaV2ToV3({ schemaVersion: 4, id: 'delivery_form', kind: 'form', extension: { formPresentation: { columns: 2.5 } }, blocks: [{ id: 'basics', blockType: 'form-section', fields: [] }] });
    const updatePage = vi.fn();
    const result = await savePageSchemaV3({ document, source: { type: 'page', pid: 'p' }, api: { getPageByPid: vi.fn(), getPageByPageKey: vi.fn(), createPage: vi.fn(), updatePage } });
    expect(result.ok).toBe(false);
    expect(updatePage).not.toHaveBeenCalled();
  });
});
