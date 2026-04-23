import { describe, it, expect } from 'vitest';
import { createDslSchemaPayload, toPageMeta, toPageSchema } from '../converters';

const baseDto: any = {
  pid: 'p1',
  pageKey: 'order_list',
  name: 'order_list',
  kind: 'list',
  schemaVersion: 2,
  blocks: [{ id: 'tbl', blockType: 'table' }],
  layout: { type: 'stack' },
  title: { 'zh-CN': '订单', 'en-US': 'Orders' },
  status: 'published',
  createdAt: '2026-04-15T00:00:00Z',
  updatedAt: '2026-04-15T00:00:00Z',
  extension: { viewModelCode: 'order' },
};

describe('toPageSchema', () => {
  it('passes blocks/layout through for list kind', () => {
    const schema = toPageSchema(baseDto);
    expect(schema.schemaVersion).toBe(2);
    expect(schema.kind).toBe('list');
    expect(schema.blocks).toHaveLength(1);
    expect(schema.blocks[0].blockType).toBe('table');
    expect(schema.layout).toEqual({ type: 'stack' });
    expect(schema.modelCode).toBe('order');
  });

  it('passes through for form kind', () => {
    const schema = toPageSchema({ ...baseDto, kind: 'form' });
    expect(schema.kind).toBe('form');
  });

  it('passes through for detail kind', () => {
    const schema = toPageSchema({ ...baseDto, kind: 'detail' });
    expect(schema.kind).toBe('detail');
  });

  it('rejects dashboard kind', () => {
    expect(() => toPageSchema({ ...baseDto, kind: 'dashboard' })).toThrow(/dashboard|kind/i);
  });

  it('rejects home kind', () => {
    expect(() => toPageSchema({ ...baseDto, kind: 'home' })).toThrow();
  });

  it('rejects composite kind', () => {
    expect(() => toPageSchema({ ...baseDto, kind: 'composite' })).toThrow();
  });

  it('throws when blocks missing', () => {
    expect(() => toPageSchema({ ...baseDto, blocks: undefined })).toThrow(/blocks/);
  });

  it('defaults layout to stack when missing', () => {
    const schema = toPageSchema({ ...baseDto, layout: undefined });
    expect(schema.layout).toEqual({ type: 'stack' });
  });
});

describe('toPageMeta', () => {
  it('returns meta without dslSchema field', () => {
    const meta = toPageMeta(baseDto) as any;
    expect(meta).not.toHaveProperty('dslSchema');
  });

  it('exposes kind instead of mode', () => {
    const meta = toPageMeta(baseDto) as any;
    expect(meta.kind).toBe('list');
    expect(meta).not.toHaveProperty('mode');
  });

  it('componentCount derives from blocks length', () => {
    const meta = toPageMeta({ ...baseDto, blocks: [1, 2, 3] as any });
    expect(meta.componentCount).toBe(3);
  });
});

describe('createDslSchemaPayload', () => {
  it('preserves structured title objects in update payload', () => {
    const payload = createDslSchemaPayload(
      {
        schemaVersion: 2,
        kind: 'detail',
        id: 'p1',
        pageKey: 'wd_leave_request_detail',
        title: { 'zh-CN': '请假申请详情', en: 'Leave Request Detail' },
        layout: { type: 'stack' },
        blocks: [],
      } as any,
      0,
    );

    expect(payload.title).toEqual({
      'zh-CN': '请假申请详情',
      en: 'Leave Request Detail',
    });
  });

  it('includes pageKey in update payload so page-level edits persist', () => {
    const payload = createDslSchemaPayload(
      {
        schemaVersion: 2,
        kind: 'detail',
        id: 'p1',
        pageKey: 'wd_leave_request_detail_v2',
        title: '请假申请详情',
        layout: { type: 'stack' },
        blocks: [],
      } as any,
      0,
    );

    expect(payload.pageKey).toBe('wd_leave_request_detail_v2');
  });
});
