/**
 * Unit tests for templateService
 * Validates URL construction, payload forwarding, and response handling.
 *
 * templateService uses `~/shared/services/http-client` (alias import).
 * We mock under that alias path via vi.mock.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
  put: vi.fn(),
  del: vi.fn(),
}));

import { templateService } from '../templateService';

/** Build a success result as templateService expects: { code, desc, data } where ResultHelper.isSuccess checks code==='0' */
const ok = <T>(data: T) => ({ code: '0', desc: 'OK', data });
const fail = (desc: string) => ({ code: '500', desc, data: null });

describe('templateService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
  });

  // ── generateCrudTemplate ────────────────────────────────────────────────────

  describe('generateCrudTemplate', () => {
    it('posts to /api/templates/crud/generate with modelCode + config and returns data', async () => {
      const expected = { taskId: 'task-1', status: 'pending' };
      postMock.mockResolvedValue(ok(expected));

      const config = { includeList: true, includeForm: true, includeDetail: true };
      const result = await templateService.generateCrudTemplate('order', config);

      expect(postMock).toHaveBeenCalledWith(
        '/api/templates/crud/generate',
        { modelCode: 'order', config },
        undefined,
        undefined,
      );
      expect(result).toEqual(expected);
    });

    it('throws when response indicates failure', async () => {
      postMock.mockResolvedValue(fail('Service unavailable'));

      await expect(templateService.generateCrudTemplate('order', {})).rejects.toThrow('Service unavailable');
    });

    it('throws default message when desc is empty', async () => {
      postMock.mockResolvedValue({ code: '500', desc: '', data: null });

      await expect(templateService.generateCrudTemplate('order', {})).rejects.toThrow('Failed to generate CRUD template');
    });
  });

  // ── getGenerationResult ─────────────────────────────────────────────────────

  describe('getGenerationResult', () => {
    it('GETs /api/templates/crud/tasks/:taskId', async () => {
      const expected = { taskId: 'task-1', status: 'completed' };
      getMock.mockResolvedValue(ok(expected));

      const result = await templateService.getGenerationResult('task-1');

      expect(getMock).toHaveBeenCalledWith('/api/templates/crud/tasks/task-1', undefined, undefined, undefined);
      expect(result).toEqual(expected);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(templateService.getGenerationResult('bad-id')).rejects.toThrow('Not found');
    });
  });

  // ── getAvailableTemplates ───────────────────────────────────────────────────

  describe('getAvailableTemplates', () => {
    it('GETs /api/templates/available', async () => {
      const templates = [{ id: 't1', name: 'CRUD Template' }];
      getMock.mockResolvedValue(ok(templates));

      const result = await templateService.getAvailableTemplates();

      expect(getMock).toHaveBeenCalledWith('/api/templates/available', undefined, undefined, undefined);
      expect(result).toEqual(templates);
    });
  });

  // ── previewTemplate ─────────────────────────────────────────────────────────

  describe('previewTemplate', () => {
    it('GETs /api/templates/:templateId/preview with modelCode param', async () => {
      const preview = { html: '<div>Preview</div>', metadata: {} };
      getMock.mockResolvedValue(ok(preview));

      const result = await templateService.previewTemplate('order', 'tmpl-1');

      expect(getMock).toHaveBeenCalledWith('/api/templates/tmpl-1/preview', { modelCode: 'order' }, undefined, undefined);
      expect(result).toEqual(preview);
    });
  });

  // ── generatePageDsl ─────────────────────────────────────────────────────────

  describe('generatePageDsl', () => {
    it('posts to /api/templates/dsl/generate with options', async () => {
      const dslResult = { listDsl: { pageKey: 'order_list', kind: 'list' }, formDsl: null, detailDsl: null };
      postMock.mockResolvedValue(ok(dslResult));

      const options = {
        modelCode: 'order',
        modelName: 'Order',
        fields: [],
        includeList: true,
        includeForm: false,
        includeDetail: false,
      };
      const result = await templateService.generatePageDsl(options);

      expect(postMock).toHaveBeenCalledWith('/api/templates/dsl/generate', options, undefined, undefined);
      expect(result).toEqual(dslResult);
    });
  });

  // ── generateMenuConfig ──────────────────────────────────────────────────────

  describe('generateMenuConfig', () => {
    it('posts to /api/templates/menu/generate', async () => {
      const menuConfig = { menuId: 'order-menu', path: '/order' };
      postMock.mockResolvedValue(ok(menuConfig));

      const options = { modelCode: 'order', modelName: 'Order', icon: 'cart' };
      const result = await templateService.generateMenuConfig(options);

      expect(postMock).toHaveBeenCalledWith('/api/templates/menu/generate', options, undefined, undefined);
      expect(result).toEqual(menuConfig);
    });
  });

  // ── generatePermissionMapping ───────────────────────────────────────────────

  describe('generatePermissionMapping', () => {
    it('posts to /api/templates/permission/generate', async () => {
      const mapping = { order_read: ['admin', 'viewer'] };
      postMock.mockResolvedValue(ok(mapping));

      const options = { modelCode: 'order', permissions: ['read', 'create'] };
      const result = await templateService.generatePermissionMapping(options);

      expect(postMock).toHaveBeenCalledWith('/api/templates/permission/generate', options, undefined, undefined);
      expect(result).toEqual(mapping);
    });
  });

  // ── verifyRuntimeLoop ───────────────────────────────────────────────────────

  describe('verifyRuntimeLoop', () => {
    it('returns a successful mock result without hitting backend', async () => {
      const model = { code: 'order', displayName: 'Order', id: 1 } as any;

      // verifyRuntimeLoop is a TODO stub that never calls http-client
      const result = await templateService.verifyRuntimeLoop(model, []);

      expect(postMock).not.toHaveBeenCalled();
      expect(getMock).not.toHaveBeenCalled();

      expect(result.success).toBe(true);
      expect(result.generatedPages.list).toBe('order_list');
      expect(result.generatedPages.form).toBe('order_form');
      expect(result.generatedPages.detail).toBe('order_detail');
      expect(result.menuPath).toBe('/p/order');
      expect(result.permissions).toContain('order:read');
    });
  });

  // ── testPageAccess ──────────────────────────────────────────────────────────

  describe('testPageAccess', () => {
    it('GETs /api/templates/page/test with modelCode + pageType', async () => {
      const accessResult = { accessible: true, url: '/p/order' };
      getMock.mockResolvedValue(ok(accessResult));

      const result = await templateService.testPageAccess('order', 'list');

      expect(getMock).toHaveBeenCalledWith('/api/templates/page/test', { modelCode: 'order', pageType: 'list' }, undefined, undefined);
      expect(result).toEqual(accessResult);
    });
  });

  // ── verifyFieldConfig ───────────────────────────────────────────────────────

  describe('verifyFieldConfig', () => {
    it('GETs /api/templates/field/verify with modelCode + fieldCode', async () => {
      const verifyResult = { applied: true, config: { required: true } };
      getMock.mockResolvedValue(ok(verifyResult));

      const result = await templateService.verifyFieldConfig('order', 'status');

      expect(getMock).toHaveBeenCalledWith('/api/templates/field/verify', { modelCode: 'order', fieldCode: 'status' }, undefined, undefined);
      expect(result).toEqual(verifyResult);
    });
  });

  // ── verifyDictDisplay ───────────────────────────────────────────────────────

  describe('verifyDictDisplay', () => {
    it('GETs /api/templates/dict/verify with three params', async () => {
      const verifyResult = { displayed: true, dictItems: [{ value: 'ACTIVE', label: 'Active' }] };
      getMock.mockResolvedValue(ok(verifyResult));

      const result = await templateService.verifyDictDisplay('order', 'status', 'ORDER_STATUS');

      expect(getMock).toHaveBeenCalledWith(
        '/api/templates/dict/verify',
        { modelCode: 'order', fieldCode: 'status', dictCode: 'ORDER_STATUS' },
        undefined,
        undefined,
      );
      expect(result).toEqual(verifyResult);
    });
  });

  // ── verifyPermissionControl ─────────────────────────────────────────────────

  describe('verifyPermissionControl', () => {
    it('GETs /api/templates/permission/verify', async () => {
      const verifyResult = { controlled: true, hasPermission: true };
      getMock.mockResolvedValue(ok(verifyResult));

      const result = await templateService.verifyPermissionControl('order', 'order:read');

      expect(getMock).toHaveBeenCalledWith(
        '/api/templates/permission/verify',
        { modelCode: 'order', permission: 'order:read' },
        undefined,
        undefined,
      );
      expect(result).toEqual(verifyResult);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Permission check failed'));

      await expect(templateService.verifyPermissionControl('order', 'order:read')).rejects.toThrow('Permission check failed');
    });
  });
});
