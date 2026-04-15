/**
 * PageManagerService unit tests
 *
 * Tests for the getPage method returning {meta, schema} pair.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PageManagerService } from '../PageManagerService';
import * as pageApi from '../pageApi';

// Minimal DTO that satisfies toPageMeta + toPageSchema
const listPageDto = {
  pid: 'pid-001',
  pageKey: 'order_list',
  name: 'order_list',
  kind: 'list',
  schemaVersion: 2,
  blocks: [{ id: 'tbl', blockType: 'table' }],
  layout: { type: 'stack' },
  title: { 'zh-CN': '订单列表', 'en-US': 'Order List' },
  status: 'draft' as const,
  isPublished: false,
  createdAt: '2026-04-15T00:00:00Z',
  updatedAt: '2026-04-15T00:00:00Z',
  extension: { viewModelCode: 'order' },
};

// Reset singleton between tests
function freshService(): PageManagerService {
  // Access private static field to reset singleton
  (PageManagerService as any).instance = undefined;
  return PageManagerService.getInstance();
}

describe('PageManagerService.getPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    (PageManagerService as any).instance = undefined;
  });

  it('returns {meta, schema} pair when backend returns V2 list page', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: listPageDto as any,
    });

    const service = PageManagerService.getInstance();
    const result = await service.getPage('pid-001');

    expect(result).not.toBeNull();
    // meta checks
    expect(result!.meta.id).toBe('pid-001');
    expect(result!.meta.kind).toBe('list');
    expect(result!.meta.pageKey).toBe('order_list');
    expect(result!.meta.viewModelCode).toBe('order');
    // schema checks
    expect(result!.schema.schemaVersion).toBe(2);
    expect(result!.schema.kind).toBe('list');
    expect(result!.schema.blocks).toHaveLength(1);
    expect(result!.schema.layout).toEqual({ type: 'stack' });
    expect(result!.schema.modelCode).toBe('order');
  });

  it('returns {meta, schema} pair for form kind', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: { ...listPageDto, kind: 'form', blocks: [] } as any,
    });

    const service = PageManagerService.getInstance();
    const result = await service.getPage('pid-002');

    expect(result).not.toBeNull();
    expect(result!.meta.kind).toBe('form');
    expect(result!.schema.kind).toBe('form');
  });

  it('returns null when backend returns null data', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: null as any,
    });

    const service = PageManagerService.getInstance();
    const result = await service.getPage('pid-missing');

    expect(result).toBeNull();
  });

  it('returns null when backend returns error code', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: 404,
      desc: 'Not found',
      data: undefined as any,
    });

    const service = PageManagerService.getInstance();
    const result = await service.getPage('pid-404');

    expect(result).toBeNull();
  });

  it('throws when DTO has unsupported kind (dashboard)', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: { ...listPageDto, kind: 'dashboard' } as any,
    });

    const service = PageManagerService.getInstance();
    await expect(service.getPage('pid-dashboard')).rejects.toThrow(/dashboard|kind/i);
  });

  it('throws when DTO blocks is missing', async () => {
    vi.spyOn(pageApi, 'getPageByPid').mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: { ...listPageDto, blocks: undefined } as any,
    });

    const service = PageManagerService.getInstance();
    await expect(service.getPage('pid-no-blocks')).rejects.toThrow(/blocks/i);
  });
});
