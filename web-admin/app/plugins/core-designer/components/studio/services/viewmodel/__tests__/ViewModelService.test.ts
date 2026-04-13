import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ViewModelService } from '../ViewModelService';
import type {
  ResolvedField,
  ViewModelSummary,
  ViewModelValidationResult,
} from '~/plugins/core-designer/components/studio/domain/viewmodel/types';

// Mock the http-client module
vi.mock('~/services/http-client', () => ({
  get: vi.fn(),
  post: vi.fn(),
}));

import { get, post } from '~/services/http-client';

const mockGet = vi.mocked(get);
const mockPost = vi.mocked(post);

describe('ViewModelService', () => {
  let service: ViewModelService;

  beforeEach(() => {
    vi.clearAllMocks();
    service = new ViewModelService();
  });

  describe('getResolvedFields', () => {
    it('should call GET /api/meta/view-models/{code}/resolved-fields', async () => {
      const mockFields: ResolvedField[] = [
        { code: 'name', displayName: 'Name', dataType: 'string', sourceType: 'field_binding' },
        { code: 'email', displayName: 'Email', dataType: 'email', sourceType: 'field_binding' },
      ];
      mockGet.mockResolvedValueOnce({ data: mockFields } as any);

      const result = await service.getResolvedFields('my-view-model');

      expect(mockGet).toHaveBeenCalledWith('/api/meta/view-models/my-view-model/resolved-fields');
      expect(result).toEqual(mockFields);
    });

    it('should return empty array when response is null', async () => {
      mockGet.mockResolvedValueOnce(null as any);

      const result = await service.getResolvedFields('not-found');

      expect(result).toEqual([]);
    });

    it('should return empty array when data is null', async () => {
      mockGet.mockResolvedValueOnce({ data: null } as any);

      const result = await service.getResolvedFields('empty');

      expect(result).toEqual([]);
    });
  });

  describe('getSummary', () => {
    it('should call GET /api/meta/view-models/{code}/summary', async () => {
      const mockSummary: ViewModelSummary = {
        code: 'my-view',
        displayName: 'My View',
        mode: 'inherit',
        fieldCount: 5,
        status: 'published',
      };
      mockGet.mockResolvedValueOnce({ data: mockSummary } as any);

      const result = await service.getSummary('my-view');

      expect(mockGet).toHaveBeenCalledWith('/api/meta/view-models/my-view/summary');
      expect(result).toEqual(mockSummary);
    });

    it('should return null when response is null', async () => {
      mockGet.mockResolvedValueOnce(null as any);

      const result = await service.getSummary('not-found');

      expect(result).toBeNull();
    });
  });

  describe('validate', () => {
    it('should call POST /api/meta/view-models/{code}/validate', async () => {
      const mockResult: ViewModelValidationResult = {
        valid: true,
        errors: [],
        warnings: [],
      };
      mockPost.mockResolvedValueOnce({ data: mockResult } as any);

      const result = await service.validate('my-view');

      expect(mockPost).toHaveBeenCalledWith('/api/meta/view-models/my-view/validate', {});
      expect(result).toEqual(mockResult);
    });

    it('should return default failure when response is null', async () => {
      mockPost.mockResolvedValueOnce(null as any);

      const result = await service.validate('broken');

      expect(result).toEqual({
        valid: false,
        errors: ['Request failed'],
        warnings: [],
      });
    });

    it('should return validation errors from backend', async () => {
      const mockResult: ViewModelValidationResult = {
        valid: false,
        errors: ['Base entity not found: customer'],
        warnings: ['Virtual field has no expression'],
      };
      mockPost.mockResolvedValueOnce({ data: mockResult } as any);

      const result = await service.validate('invalid-view');

      expect(result.valid).toBe(false);
      expect(result.errors).toContain('Base entity not found: customer');
      expect(result.warnings).toContain('Virtual field has no expression');
    });
  });

  describe('listViewModels', () => {
    it('should call GET /api/meta/models with VIEW type filter', async () => {
      const mockModels = [
        { pid: 'p1', code: 'view1', displayName: 'View 1', modelType: 'view' },
        { pid: 'p2', code: 'view2', displayName: 'View 2', modelType: 'view' },
      ];
      mockGet.mockResolvedValueOnce({
        data: { data: mockModels, total: 2, page: 1, size: 20 },
      } as any);

      const result = await service.listViewModels();

      expect(mockGet).toHaveBeenCalledWith('/api/meta/models', {
        modelType: 'view',
        currentOnly: 'true',
      });
      expect(result).toEqual(mockModels);
    });

    it('should return empty array when response has no data', async () => {
      mockGet.mockResolvedValueOnce({ data: null } as any);

      const result = await service.listViewModels();

      expect(result).toEqual([]);
    });

    it('should return empty array when data.data is null', async () => {
      mockGet.mockResolvedValueOnce({ data: { data: null } } as any);

      const result = await service.listViewModels();

      expect(result).toEqual([]);
    });

    it('should return empty array when response is null', async () => {
      mockGet.mockResolvedValueOnce(null as any);

      const result = await service.listViewModels();

      expect(result).toEqual([]);
    });
  });
});
