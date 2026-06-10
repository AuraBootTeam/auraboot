/**
 * Unit tests for fieldLibraryService
 * Validates URL construction, payload forwarding, and response handling.
 * Uses ResultHelper.isSuccess (code==='0') and result.desc.
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

import {
  searchFields,
  getFieldRecommendations,
  getUnusedFields,
  getSystemFields,
  getFieldUsage,
  getBindingConfigurations,
  refreshFieldUsageCache,
  analyzeFieldImpact,
  validateFieldModification,
  validateFieldDeletion,
  createField,
  checkFieldCodeUnique,
} from '../fieldLibraryService';

function ok<T>(data: T) {
  return { code: '0', desc: '', data };
}

function fail(desc = 'Server error') {
  return { code: '1', desc, data: null };
}

describe('fieldLibraryService', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
  });

  // ── searchFields ──────────────────────────────────────────────────────────────

  describe('searchFields', () => {
    it('POSTs to /api/meta/field-library/search with request body', async () => {
      const searchResult = { fields: [], total: 0 };
      postMock.mockResolvedValue(ok(searchResult));

      const req = { keyword: 'price', dataType: 'number' };
      const result = await searchFields(req as any);

      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/field-library/search',
        req,
        undefined,
        undefined,
      );
      expect(result).toEqual(searchResult);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Search error'));

      await expect(searchFields({} as any)).rejects.toThrow('Search error');
    });
  });

  // ── getFieldRecommendations ────────────────────────────────────────────────────

  describe('getFieldRecommendations', () => {
    it('GETs /api/meta/field-library/recommendations with modelPid param', async () => {
      const recs = [{ fieldPid: 'f1', score: 0.9 }];
      getMock.mockResolvedValue(ok(recs));

      const result = await getFieldRecommendations('model-1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/field-library/recommendations',
        { modelPid: 'model-1' },
        undefined,
        undefined,
      );
      expect(result).toEqual(recs);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Not found'));

      await expect(getFieldRecommendations('m1')).rejects.toThrow('Not found');
    });
  });

  // ── getUnusedFields ───────────────────────────────────────────────────────────

  describe('getUnusedFields', () => {
    it('GETs /api/meta/field-library/unused', async () => {
      const fields = [{ pid: 'f1', code: 'old_field' }];
      getMock.mockResolvedValue(ok(fields));

      const result = await getUnusedFields();

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/field-library/unused',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(fields);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('DB error'));

      await expect(getUnusedFields()).rejects.toThrow('DB error');
    });
  });

  // ── getSystemFields ───────────────────────────────────────────────────────────

  describe('getSystemFields', () => {
    it('GETs /api/meta/field-library/system', async () => {
      const fields = [{ pid: 'sys1', code: 'created_at' }];
      getMock.mockResolvedValue(ok(fields));

      const result = await getSystemFields();

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/field-library/system',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(fields);
    });
  });

  // ── getFieldUsage ─────────────────────────────────────────────────────────────

  describe('getFieldUsage', () => {
    it('GETs /api/meta/fields/:pid/usage', async () => {
      const usage = { fieldPid: 'f1', modelCount: 3, bindingCount: 5 };
      getMock.mockResolvedValue(ok(usage));

      const result = await getFieldUsage('f1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/usage',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(usage);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Field not found'));

      await expect(getFieldUsage('x')).rejects.toThrow('Field not found');
    });
  });

  // ── getBindingConfigurations ───────────────────────────────────────────────────

  describe('getBindingConfigurations', () => {
    it('GETs /api/meta/fields/:pid/usage/bindings', async () => {
      const bindings = [{ bindingPid: 'b1', modelCode: 'order' }];
      getMock.mockResolvedValue(ok(bindings));

      const result = await getBindingConfigurations('f1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/usage/bindings',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(bindings);
    });
  });

  // ── refreshFieldUsageCache ─────────────────────────────────────────────────────

  describe('refreshFieldUsageCache', () => {
    it('POSTs to /api/meta/fields/:pid/usage/refresh', async () => {
      postMock.mockResolvedValue(ok(null));

      await expect(refreshFieldUsageCache('f1')).resolves.toBeUndefined();
      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/usage/refresh',
        {},
        undefined,
        undefined,
      );
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Cache error'));

      await expect(refreshFieldUsageCache('f1')).rejects.toThrow('Cache error');
    });
  });

  // ── analyzeFieldImpact ─────────────────────────────────────────────────────────

  describe('analyzeFieldImpact', () => {
    it('GETs /api/meta/fields/:pid/impact', async () => {
      const impact = { fieldPid: 'f1', affectedModels: [], affectedPages: [] };
      getMock.mockResolvedValue(ok(impact));

      const result = await analyzeFieldImpact('f1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/impact',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toEqual(impact);
    });

    it('throws on failure', async () => {
      getMock.mockResolvedValue(fail('Analysis failed'));

      await expect(analyzeFieldImpact('f1')).rejects.toThrow('Analysis failed');
    });
  });

  // ── validateFieldModification ──────────────────────────────────────────────────

  describe('validateFieldModification', () => {
    it('POSTs modifications to /api/meta/fields/:pid/impact/validate', async () => {
      const validationResult = { valid: true, issues: [] };
      postMock.mockResolvedValue(ok(validationResult));

      const result = await validateFieldModification('f1', { dataType: 'text' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/impact/validate',
        { dataType: 'text' },
        undefined,
        undefined,
      );
      expect(result.valid).toBe(true);
    });

    it('returns issues when validation finds problems', async () => {
      const validationResult = { valid: false, issues: ['Breaking change: type mismatch'] };
      postMock.mockResolvedValue(ok(validationResult));

      const result = await validateFieldModification('f1', { dataType: 'number' });

      expect(result.valid).toBe(false);
      expect(result.issues).toHaveLength(1);
    });
  });

  // ── validateFieldDeletion ──────────────────────────────────────────────────────

  describe('validateFieldDeletion', () => {
    it('GETs /api/meta/fields/:pid/impact/validate-deletion', async () => {
      const deletionResult = { canDelete: true, blockingReasons: [] };
      getMock.mockResolvedValue(ok(deletionResult));

      const result = await validateFieldDeletion('f1');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/f1/impact/validate-deletion',
        undefined,
        undefined,
        undefined,
      );
      expect(result.canDelete).toBe(true);
    });

    it('returns blocking reasons when deletion is not safe', async () => {
      const deletionResult = {
        canDelete: false,
        blockingReasons: ['Used in 3 DSL pages'],
      };
      getMock.mockResolvedValue(ok(deletionResult));

      const result = await validateFieldDeletion('f1');

      expect(result.canDelete).toBe(false);
      expect(result.blockingReasons).toHaveLength(1);
    });
  });

  // ── createField ───────────────────────────────────────────────────────────────

  describe('createField', () => {
    it('POSTs to /api/meta/fields with field definition', async () => {
      const field = { pid: 'new-f', code: 'price', dataType: 'number' };
      postMock.mockResolvedValue(ok(field));

      const result = await createField({ code: 'price', dataType: 'number' });

      expect(postMock).toHaveBeenCalledWith(
        '/api/meta/fields',
        { code: 'price', dataType: 'number' },
        undefined,
        undefined,
      );
      expect(result).toEqual(field);
    });

    it('throws on failure', async () => {
      postMock.mockResolvedValue(fail('Code conflict'));

      await expect(createField({ code: 'dup', dataType: 'text' })).rejects.toThrow('Code conflict');
    });
  });

  // ── checkFieldCodeUnique ───────────────────────────────────────────────────────

  describe('checkFieldCodeUnique', () => {
    it('GETs /api/meta/fields/key/:code/unique without excludePid', async () => {
      getMock.mockResolvedValue(ok(true));

      const result = await checkFieldCodeUnique('price');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/key/price/unique',
        undefined,
        undefined,
        undefined,
      );
      expect(result).toBe(true);
    });

    it('appends excludePid as query param when provided', async () => {
      getMock.mockResolvedValue(ok(true));

      await checkFieldCodeUnique('price', 'old-pid');

      expect(getMock).toHaveBeenCalledWith(
        '/api/meta/fields/key/price/unique?excludePid=old-pid',
        undefined,
        undefined,
        undefined,
      );
    });

    it('returns false when code already exists', async () => {
      getMock.mockResolvedValue(ok(false));

      const result = await checkFieldCodeUnique('price');

      expect(result).toBe(false);
    });
  });
});
