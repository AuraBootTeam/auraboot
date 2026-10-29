import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import {
  enrichDetailRecordDisplayFields,
  hasCompleteDetailDisplayFields,
  loadDetailRecordRefreshSnapshot,
} from '../detailPage/detailRecordRequests';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
const transport = vi.mocked(fetchResult);
const fields = new Map([
  ['name', { code: 'name', dataType: 'string' }],
  ['owner', { code: 'owner', dataType: 'reference', refTarget: { targetEntity: 'people' } }],
  ['scope', { code: 'scope', dataType: 'reference' }],
]);
const schema = {
  blocks: [
    { blockType: 'toolbar', buttons: [] },
    {
      blockType: 'detail-section',
      fields: [{ field: 'name' }, { field: 'owner' }, { field: 'scope' }],
    },
    { blockType: 'rich-text', content: 'Read only.' },
  ],
};
const record = {
  pid: 'public',
  row_version: 7,
  name: 'Example',
  owner: 'p1',
  owner_display: 'Alice',
  scope: 'p2',
  scope_display: 'Team',
};
beforeEach(() => transport.mockReset());
describe('detail master display completeness', () => {
  it('uses complete master labels without a second permission-scanned list read', async () => {
    expect(
      await enrichDetailRecordDisplayFields('example', 'public', record, 'token', fields, schema),
    ).toBe(record);
    expect(transport).not.toHaveBeenCalled();
  });
  it.each([undefined, new Map()])('retains fallback when metadata is unknown: %s', async (meta) => {
    transport.mockResolvedValue({
      code: '0',
      data: { records: [{ owner_display: 'From list' }] },
    } as any);
    expect(
      (await enrichDetailRecordDisplayFields('example', 'public', record, 'token', meta, schema))
        .owner_display,
    ).toBe('From list');
    expect(transport).toHaveBeenCalledTimes(1);
  });
  it.each([undefined, null, '', '   '])(
    'retains fallback if any populated reference lacks a display: %s',
    async (display) => {
      transport.mockResolvedValue({
        code: '0',
        data: { records: [{ scope_display: 'Fresh team' }] },
      } as any);
      const result = await enrichDetailRecordDisplayFields(
        'example',
        'public',
        { ...record, scope_display: display },
        'token',
        fields,
        schema,
      );
      expect(result.scope_display).toBe('Fresh team');
      expect(transport).toHaveBeenCalledWith(
        '/api/dynamic/example/list',
        expect.objectContaining({
          params: expect.objectContaining({
            filters: JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: 'public' }]),
          }),
        }),
      );
    },
  );
  it('does not invent a hidden raw reference from field metadata', async () => {
    const { scope, scope_display, ...visible } = record;
    expect(
      await enrichDetailRecordDisplayFields('example', 'public', visible, 'token', fields, schema),
    ).toBe(visible);
    expect(transport).not.toHaveBeenCalled();
    expect(visible).not.toHaveProperty('scope');
  });
  it('does not accept inherited display values or an empty reference set', () => {
    const inherited = Object.assign(Object.create({ owner_display: 'Inherited' }), {
      owner: 'p1',
      name: 'X',
    });
    expect(hasCompleteDetailDisplayFields(inherited, fields, schema)).toBe(false);
    expect(hasCompleteDetailDisplayFields({ name: 'X' }, fields, schema)).toBe(false);
  });
  it.each([
    { blocks: [{ blockType: 'custom' }] },
    { blocks: [{ blockType: 'detail-section', fields: [{ field: 'unknown' }] }] },
    { ...schema, extension: { headerSubtitleFields: ['name'] } },
    {
      blocks: [
        {
          blockType: 'detail-section',
          extension: { displayVariant: 'settings-card' },
          fields: [{ field: 'owner' }],
        },
      ],
    },
    {
      blocks: [
        { blockType: 'detail-section', fields: [{ field: 'owner', component: 'custom-picker' }] },
      ],
    },
    { blocks: [{ blockType: 'detail-section', fields: [{ field: '${record.owner}' }] }] },
    { ...schema, extension: { visibleWhen: '${record.name}' } },
    { blocks: [{ blockType: 'rich-text', content: '${record.owner_display}' }] },
  ])('preserves legacy enrichment for unsupported display consumers', (page) => {
    expect(hasCompleteDetailDisplayFields(record, fields, page)).toBe(false);
  });
  it.each([
    { dataType: 'string', extension: { renderComponent: 'userselect' } },
    { dataType: 'string', extension: { renderComponent: 'organizationselect' } },
    { dataType: 'string', dictCode: 'choices' },
    { dataType: 'string', extension: { refTarget: { targetEntity: 'people' } } },
  ])('requires labels for declared picker/dictionary/reference consumers', (meta) => {
    const map = new Map(fields);
    map.set('scope', { code: 'scope', ...meta } as any);
    expect(hasCompleteDetailDisplayFields({ ...record, scope_display: '' }, map, schema)).toBe(
      false,
    );
  });
  it.each(['userselect', 'custom-picker'])(
    'honors the actual metadata uiComponent alias: %s',
    (component) => {
      const map = new Map(fields);
      map.set('scope', {
        code: 'scope',
        dataType: 'string',
        extension: { uiComponent: component },
      } as any);
      expect(hasCompleteDetailDisplayFields({ ...record, scope_display: '' }, map, schema)).toBe(
        false,
      );
    },
  );
  it.each([
    { dataType: 'reference' },
    { refTarget: { targetEntity: 'people' } },
    { referenceModelCode: 'people' },
  ])('honors page reference metadata overrides', (override) => {
    const map = new Map(fields);
    map.set('scope', { code: 'scope', dataType: 'string' } as any);
    const page = {
      blocks: [
        {
          blockType: 'detail-section',
          fields: [{ field: 'owner' }, { field: 'scope', ...override }],
        },
      ],
    };
    expect(hasCompleteDetailDisplayFields({ ...record, scope_display: '' }, map, page)).toBe(false);
  });
  it('keeps alternate section record paths on the original enrichment path', () => {
    expect(
      hasCompleteDetailDisplayFields(record, fields, {
        blocks: [{ blockType: 'detail-section', dataPath: 'nested', fields: [{ field: 'owner' }] }],
      }),
    ).toBe(false);
  });
  it('strict refresh uses the new version and never old display labels to skip enrichment', async () => {
    transport.mockResolvedValue({
      code: '0',
      data: { records: [{ scope_display: 'New scope' }] },
    } as any);
    const next = { ...record, row_version: 8, scope: 'p3', scope_display: '' };
    const result = await loadDetailRecordRefreshSnapshot(
      async () => ({ code: '0', data: next }),
      undefined,
      'example',
      'public',
      'token',
      (model, pid, value, token) =>
        enrichDetailRecordDisplayFields(model, pid, value, token, fields, schema),
    );
    expect(result?.recordData.row_version).toBe(8);
    expect(result?.recordData.scope_display).toBe('New scope');
    expect(transport).toHaveBeenCalledTimes(1);
  });
});
