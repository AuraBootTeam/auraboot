import { get, post, put, del } from '~/shared/services/http-client';
import type { FilterPreset } from '~/plugins/core-designer/components/studio/workbench/panels/filters/types';

/**
 * Filter Preset Service.
 * Provides API calls to backend filter preset endpoints.
 *
 * @since 3.4.0
 */
export class FilterPresetApiService {
  /**
   * List presets for a page.
   * GET /api/meta/filter-presets?pageCode=xxx
   */
  async listByPageCode(pageCode: string): Promise<FilterPreset[]> {
    const result = await get<FilterPreset[]>('/api/meta/filter-presets', {
      params: { pageCode },
    });
    return result?.data ?? [];
  }

  /**
   * Create a new preset.
   * POST /api/meta/filter-presets
   */
  async create(request: {
    pageCode: string;
    modelCode: string;
    name: string;
    conditions: string;
    logic: string;
    isDefault: boolean;
    scope: string;
  }): Promise<FilterPreset> {
    const result = await post<FilterPreset>('/api/meta/filter-presets', request);
    return (
      result?.data ?? {
        name: '',
        conditions: [],
        logic: 'and',
        isDefault: false,
        scope: 'personal',
      }
    );
  }

  /**
   * Update a preset.
   * PUT /api/meta/filter-presets/{id}
   */
  async update(
    id: number,
    request: {
      pageCode: string;
      modelCode: string;
      name: string;
      conditions: string;
      logic: string;
      isDefault: boolean;
      scope: string;
    },
  ): Promise<FilterPreset> {
    const result = await put<FilterPreset>(`/api/meta/filter-presets/${id}`, request);
    return (
      result?.data ?? {
        name: '',
        conditions: [],
        logic: 'and',
        isDefault: false,
        scope: 'personal',
      }
    );
  }

  /**
   * Delete a preset.
   * DELETE /api/meta/filter-presets/{id}
   */
  async delete(id: number): Promise<void> {
    await del(`/api/meta/filter-presets/${id}`);
  }

  /**
   * Set a preset as default.
   * PUT /api/meta/filter-presets/{id}/default
   */
  async setDefault(id: number): Promise<void> {
    await put(`/api/meta/filter-presets/${id}/default`, {});
  }
}

export const filterPresetService = new FilterPresetApiService();
