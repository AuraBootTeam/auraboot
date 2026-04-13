import { get, post } from '~/shared/services/http-client';
import type {
  MetaFieldDTO,
  FieldSearchRequest,
  FieldRecommendation,
} from '~/plugins/core-designer/components/studio/workbench/panels/fields/types';

/**
 * Field Library Service.
 * Provides API calls to backend field library and model field endpoints.
 *
 * @since 3.1.0
 */
export class FieldLibraryService {
  /**
   * List all fields grouped by semantic type.
   * GET /api/meta/field-library
   */
  async listBySemanticType(): Promise<Record<string, MetaFieldDTO[]>> {
    const result = await get<Record<string, MetaFieldDTO[]>>('/api/meta/field-library');
    return result?.data ?? {};
  }

  /**
   * Search fields with advanced filters.
   * POST /api/meta/field-library/search
   */
  async search(request: FieldSearchRequest): Promise<{ records: MetaFieldDTO[]; total: number }> {
    const result = await post<{ records: MetaFieldDTO[]; total: number }>(
      '/api/meta/field-library/search',
      request,
    );
    return result?.data ?? { records: [], total: 0 };
  }

  /**
   * Get field recommendations for a model.
   * GET /api/meta/field-library/recommendations?modelPid=xxx
   */
  async getRecommendations(
    modelPid: string,
    semanticType?: string,
  ): Promise<FieldRecommendation[]> {
    const params: Record<string, string> = { modelPid };
    if (semanticType) params.semanticType = semanticType;
    const result = await get<FieldRecommendation[]>(
      '/api/meta/field-library/recommendations',
      params,
    );
    return result?.data ?? [];
  }

  /**
   * Get model-bound fields.
   * GET /api/meta/models/{modelPid}/fields
   */
  async getModelFields(modelPid: string): Promise<MetaFieldDTO[]> {
    const result = await get<MetaFieldDTO[]>(`/api/meta/models/${modelPid}/fields`);
    return result?.data ?? [];
  }

  /**
   * Get system fields.
   * GET /api/meta/field-library/system-fields
   */
  async getSystemFields(): Promise<MetaFieldDTO[]> {
    const result = await get<MetaFieldDTO[]>('/api/meta/field-library/system-fields');
    return result?.data ?? [];
  }

  /**
   * Get common business fields.
   * GET /api/meta/field-library/common-fields
   */
  async getCommonFields(): Promise<MetaFieldDTO[]> {
    const result = await get<MetaFieldDTO[]>('/api/meta/field-library/common-fields');
    return result?.data ?? [];
  }
}

export const fieldLibraryService = new FieldLibraryService();
