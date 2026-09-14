/**
 * Chart Data Service
 *
 * Service for fetching chart data from the backend aggregate query API.
 * Uses the unified HTTP client for consistent request handling.
 */

import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { AggregateQueryRequest, AggregateQueryResponse } from '~/framework/smart/types/chart';

/**
 * Chart data service for aggregate queries
 */
export const chartDataService = {
  async fetchDashboardWidget(
    dashboardPid: string,
    widgetId: string,
    request: {
      usageId: string;
      linkageFilters?: AggregateQueryRequest['filters'];
      drillFilters?: AggregateQueryRequest['drillFilters'];
    },
  ): Promise<AggregateQueryResponse> {
    const result = await post<AggregateQueryResponse>(
      `/api/dashboards/${encodeURIComponent(dashboardPid)}/widgets/${encodeURIComponent(widgetId)}/data`,
      request,
    );
    if (!ResultHelper.isSuccess(result) || result.data === null) {
      throw new Error(result.desc || 'Failed to fetch saved dashboard data');
    }
    return result.data;
  },
  /**
   * Fetch chart data using aggregate query
   *
   * @param request - Aggregate query request configuration
   * @returns Promise resolving to the query response
   * @throws Error if the request fails
   *
   * @example
   * const response = await chartDataService.fetchChartData({
   *   type: 'aggregate',
   *   modelCode: 'order',
   *   dimensions: ['status'],
   *   metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
   *   groupBy: ['status'],
   * });
   */
  async fetchChartData(request: AggregateQueryRequest): Promise<AggregateQueryResponse> {
    const result = await post<AggregateQueryResponse>('/api/meta/chart-data', request);

    if (!ResultHelper.isSuccess(result) || result.data === null) {
      throw new Error(result.desc || 'Failed to fetch chart data');
    }

    return result.data;
  },
};

export default chartDataService;
