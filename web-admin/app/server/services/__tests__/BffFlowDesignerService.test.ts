/**
 * Unit tests for BffFlowDesignerService
 * Mocks flowDesignerApiClient and logger; verifies request validation,
 * default-value injection, and response status mapping.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

// ── Hoist mocks before any imports that pull the real modules ──────────────

const {
  saveFlowMock,
  updateFlowMock,
  getFlowMock,
  getFlowListMock,
  deleteFlowMock,
  publishFlowMock,
  duplicateFlowMock,
} = vi.hoisted(() => ({
  saveFlowMock: vi.fn(),
  updateFlowMock: vi.fn(),
  getFlowMock: vi.fn(),
  getFlowListMock: vi.fn(),
  deleteFlowMock: vi.fn(),
  publishFlowMock: vi.fn(),
  duplicateFlowMock: vi.fn(),
}));

vi.mock('~/server/clients/FlowDesignerApiClient', () => ({
  flowDesignerApiClient: {
    saveFlow: saveFlowMock,
    updateFlow: updateFlowMock,
    getFlow: getFlowMock,
    getFlowList: getFlowListMock,
    deleteFlow: deleteFlowMock,
    publishFlow: publishFlowMock,
    duplicateFlow: duplicateFlowMock,
  },
}));

vi.mock('~/server/utils/logger', () => ({
  default: {
    info: vi.fn(),
    error: vi.fn(),
    warn: vi.fn(),
  },
}));

import { BffFlowDesignerService } from '../BffFlowDesignerService';

// ── Tiny helpers to build express-like req/res mocks ──────────────────────

function mockRes() {
  const res: any = {
    statusCode: 200,
    body: undefined as any,
  };
  res.status = vi.fn().mockImplementation((code: number) => {
    res.statusCode = code;
    return res;
  });
  res.json = vi.fn().mockImplementation((data: any) => {
    res.body = data;
    return res;
  });
  return res;
}

function mockReq(opts: { body?: any; params?: any; query?: any } = {}) {
  return {
    body: opts.body ?? {},
    params: opts.params ?? {},
    query: opts.query ?? {},
  } as any;
}

function validFlowBody(overrides: Record<string, unknown> = {}) {
  return {
    name: 'My Flow',
    nodes: [],
    edges: [],
    status: 'draft',
    layoutMode: 'free',
    gridConfig: { columns: 3, rowGap: 20, columnGap: 20 },
    ...overrides,
  };
}

describe('BffFlowDesignerService', () => {
  let service: BffFlowDesignerService;

  beforeEach(() => {
    service = new BffFlowDesignerService();
    vi.clearAllMocks();
  });

  // ── saveFlow ──────────────────────────────────────────────────────────────

  describe('saveFlow', () => {
    it('returns 400 when name is missing', async () => {
      const req = mockReq({ body: { ...validFlowBody(), name: '' } });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.success).toBe(false);
      expect(res.body.message).toMatch(/name/i);
    });

    it('returns 400 when name is whitespace-only', async () => {
      const req = mockReq({ body: { ...validFlowBody(), name: '   ' } });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 400 when nodes is not an array', async () => {
      const req = mockReq({ body: { ...validFlowBody(), nodes: 'bad' } });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/nodes/i);
    });

    it('returns 400 when edges is not an array', async () => {
      const req = mockReq({ body: { ...validFlowBody(), edges: null } });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/edges/i);
    });

    it('returns 400 when status is invalid', async () => {
      const req = mockReq({ body: { ...validFlowBody(), status: 'archived' } });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/status/i);
    });

    it('calls saveFlow on client and returns 201 on success', async () => {
      saveFlowMock.mockResolvedValue({ success: true, data: { id: '123' } });
      const req = mockReq({ body: validFlowBody() });
      const res = mockRes();
      await service.saveFlow(req, res);

      expect(saveFlowMock).toHaveBeenCalledOnce();
      const savedData = saveFlowMock.mock.calls[0][0];
      // name should be trimmed
      expect(savedData.name).toBe('My Flow');
      // default layoutMode should be set
      expect(savedData.layoutMode).toBe('free');
      // default gridConfig should be set
      expect(savedData.gridConfig).toEqual({ columns: 3, rowGap: 20, columnGap: 20 });
      // updatedAt should be set
      expect(typeof savedData.updatedAt).toBe('string');

      expect(res.status).toHaveBeenCalledWith(201);
      expect(res.body.success).toBe(true);
    });

    it('returns 400 when client returns success:false', async () => {
      saveFlowMock.mockResolvedValue({ success: false, message: 'duplicate' });
      const req = mockReq({ body: validFlowBody() });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 500 on unexpected client error', async () => {
      saveFlowMock.mockRejectedValue(new Error('network error'));
      const req = mockReq({ body: validFlowBody() });
      const res = mockRes();
      await service.saveFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
      expect(res.body.success).toBe(false);
    });

    it('trims name and sets default description when empty', async () => {
      saveFlowMock.mockResolvedValue({ success: true, data: {} });
      const req = mockReq({ body: { ...validFlowBody(), name: '  Test  ', description: '' } });
      const res = mockRes();
      await service.saveFlow(req, res);
      const arg = saveFlowMock.mock.calls[0][0];
      expect(arg.name).toBe('Test');
      expect(arg.description).toBe('');
    });
  });

  // ── updateFlow ────────────────────────────────────────────────────────────

  describe('updateFlow', () => {
    it('returns 400 when flowId is missing', async () => {
      const req = mockReq({ params: {}, body: {} });
      const res = mockRes();
      await service.updateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/flow id/i);
    });

    it('calls updateFlow on client with id from params and returns 200', async () => {
      updateFlowMock.mockResolvedValue({ success: true, data: { id: 'abc' } });
      const req = mockReq({ params: { id: 'abc' }, body: { name: 'Updated' } });
      const res = mockRes();
      await service.updateFlow(req, res);

      expect(updateFlowMock).toHaveBeenCalledOnce();
      const [id, data] = updateFlowMock.mock.calls[0];
      expect(id).toBe('abc');
      expect(typeof data.updatedAt).toBe('string');

      expect(res.status).toHaveBeenCalledWith(200);
    });

    it('returns 400 when client returns success:false', async () => {
      updateFlowMock.mockResolvedValue({ success: false, message: 'not found' });
      const req = mockReq({ params: { id: 'abc' }, body: {} });
      const res = mockRes();
      await service.updateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 500 on exception', async () => {
      updateFlowMock.mockRejectedValue(new Error('DB error'));
      const req = mockReq({ params: { id: 'abc' }, body: {} });
      const res = mockRes();
      await service.updateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
    });
  });

  // ── getFlow ───────────────────────────────────────────────────────────────

  describe('getFlow', () => {
    it('returns 400 when flowId is missing', async () => {
      const req = mockReq({ params: {} });
      const res = mockRes();
      await service.getFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 200 when client succeeds', async () => {
      getFlowMock.mockResolvedValue({ success: true, data: { id: 'xyz', name: 'Test' } });
      const req = mockReq({ params: { id: 'xyz' } });
      const res = mockRes();
      await service.getFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(200);
      expect(res.body.success).toBe(true);
    });

    it('returns 404 when client returns "Flow not found" message', async () => {
      getFlowMock.mockResolvedValue({ success: false, message: 'Flow not found' });
      const req = mockReq({ params: { id: 'xyz' } });
      const res = mockRes();
      await service.getFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(404);
    });

    it('returns 400 for other failure messages', async () => {
      getFlowMock.mockResolvedValue({ success: false, message: 'Unauthorized' });
      const req = mockReq({ params: { id: 'xyz' } });
      const res = mockRes();
      await service.getFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 500 on exception', async () => {
      getFlowMock.mockRejectedValue(new Error('timeout'));
      const req = mockReq({ params: { id: 'xyz' } });
      const res = mockRes();
      await service.getFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
    });
  });

  // ── getFlowList ───────────────────────────────────────────────────────────

  describe('getFlowList', () => {
    it('returns 400 for negative page', async () => {
      const req = mockReq({ query: { page: '-1' } });
      const res = mockRes();
      await service.getFlowList(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/page/i);
    });

    it('returns 400 for size > 100', async () => {
      const req = mockReq({ query: { size: '200' } });
      const res = mockRes();
      await service.getFlowList(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/size/i);
    });

    it('returns 400 for invalid status', async () => {
      const req = mockReq({ query: { status: 'unknown' } });
      const res = mockRes();
      await service.getFlowList(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('calls getFlowList and returns 200 on success', async () => {
      getFlowListMock.mockResolvedValue({ success: true, data: { records: [], total: 0 } });
      const req = mockReq({ query: { page: '1', size: '10', name: 'test', status: 'draft' } });
      const res = mockRes();
      await service.getFlowList(req, res);

      expect(getFlowListMock).toHaveBeenCalledOnce();
      const query = getFlowListMock.mock.calls[0][0];
      expect(query.page).toBe(1);
      expect(query.size).toBe(10);
      expect(query.name).toBe('test');
      expect(query.status).toBe('draft');

      expect(res.status).toHaveBeenCalledWith(200);
    });

    it('returns 500 on exception', async () => {
      getFlowListMock.mockRejectedValue(new Error('network'));
      const req = mockReq({ query: {} });
      const res = mockRes();
      await service.getFlowList(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
    });
  });

  // ── deleteFlow ────────────────────────────────────────────────────────────

  describe('deleteFlow', () => {
    it('returns 400 when flowId is missing', async () => {
      const req = mockReq({ params: {} });
      const res = mockRes();
      await service.deleteFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('calls deleteFlow and returns 200 on success', async () => {
      deleteFlowMock.mockResolvedValue({ success: true });
      const req = mockReq({ params: { id: 'abc' } });
      const res = mockRes();
      await service.deleteFlow(req, res);
      expect(deleteFlowMock).toHaveBeenCalledWith('abc');
      expect(res.status).toHaveBeenCalledWith(200);
    });

    it('returns 500 on exception', async () => {
      deleteFlowMock.mockRejectedValue(new Error('db'));
      const req = mockReq({ params: { id: 'abc' } });
      const res = mockRes();
      await service.deleteFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
    });
  });

  // ── publishFlow ───────────────────────────────────────────────────────────

  describe('publishFlow', () => {
    it('returns 400 when flowId is missing', async () => {
      const req = mockReq({ params: {} });
      const res = mockRes();
      await service.publishFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('calls publishFlow and returns 200 on success', async () => {
      publishFlowMock.mockResolvedValue({ success: true, data: { status: 'published' } });
      const req = mockReq({ params: { id: 'abc' } });
      const res = mockRes();
      await service.publishFlow(req, res);
      expect(publishFlowMock).toHaveBeenCalledWith('abc');
      expect(res.status).toHaveBeenCalledWith(200);
    });
  });

  // ── duplicateFlow ─────────────────────────────────────────────────────────

  describe('duplicateFlow', () => {
    it('returns 400 when flowId is missing', async () => {
      const req = mockReq({ params: {}, body: { name: 'Copy' } });
      const res = mockRes();
      await service.duplicateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
    });

    it('returns 400 when new name is empty', async () => {
      const req = mockReq({ params: { id: 'abc' }, body: { name: '   ' } });
      const res = mockRes();
      await service.duplicateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(400);
      expect(res.body.message).toMatch(/name/i);
    });

    it('calls duplicateFlow with trimmed name and returns 201', async () => {
      duplicateFlowMock.mockResolvedValue({ success: true, data: { id: 'new-id' } });
      const req = mockReq({ params: { id: 'abc' }, body: { name: '  Copy  ' } });
      const res = mockRes();
      await service.duplicateFlow(req, res);
      expect(duplicateFlowMock).toHaveBeenCalledWith('abc', 'Copy');
      expect(res.status).toHaveBeenCalledWith(201);
    });

    it('returns 500 on exception', async () => {
      duplicateFlowMock.mockRejectedValue(new Error('error'));
      const req = mockReq({ params: { id: 'abc' }, body: { name: 'Copy' } });
      const res = mockRes();
      await service.duplicateFlow(req, res);
      expect(res.status).toHaveBeenCalledWith(500);
    });
  });
});
