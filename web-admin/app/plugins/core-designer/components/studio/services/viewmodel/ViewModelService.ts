import { get, post } from '~/services/http-client';
import type {
  ResolvedField,
  ViewModelSummary,
  ViewModelValidationResult,
} from '~/plugins/core-designer/components/studio/domain/viewmodel/types';

/**
 * Model list item from backend PageResult.
 */
export interface ModelListItem {
  pid: string;
  code: string;
  displayName?: string;
  description?: string;
  modelType?: string;
  status?: string;
  extension?: Record<string, any>;
}

/**
 * Backend PageResult structure.
 */
interface PageResult<T> {
  data: T[];
  total: number;
  page: number;
  size: number;
}

/**
 * ViewModel API service.
 * Provides access to ViewModel field resolution and data query endpoints.
 *
 * @since 3.2.0
 */
export class ViewModelService {
  /**
   * Get resolved fields for a ViewModel (three-layer merged).
   * GET /api/meta/view-models/{code}/resolved-fields
   */
  async getResolvedFields(code: string): Promise<ResolvedField[]> {
    const result = await get<ResolvedField[]>(`/api/meta/view-models/${code}/resolved-fields`);
    return result?.data ?? [];
  }

  /**
   * Get ViewModel summary.
   * GET /api/meta/view-models/{code}/summary
   */
  async getSummary(code: string): Promise<ViewModelSummary | null> {
    const result = await get<ViewModelSummary>(`/api/meta/view-models/${code}/summary`);
    return result?.data ?? null;
  }

  /**
   * Validate ViewModel configuration.
   * POST /api/meta/view-models/{code}/validate
   */
  async validate(code: string): Promise<ViewModelValidationResult> {
    const result = await post<ViewModelValidationResult>(
      `/api/meta/view-models/${code}/validate`,
      {},
    );
    return result?.data ?? { valid: false, errors: ['Request failed'], warnings: [] };
  }

  /**
   * List all VIEW type models (ViewModels).
   * GET /api/meta/models?modelType=VIEW&currentOnly=true
   */
  async listViewModels(): Promise<ModelListItem[]> {
    const result = await get<PageResult<ModelListItem>>('/api/meta/models', {
      modelType: 'view',
      currentOnly: 'true',
    });
    return result?.data?.data ?? [];
  }
}

export const viewModelService = new ViewModelService();
