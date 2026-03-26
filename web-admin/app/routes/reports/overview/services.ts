import type { Result } from '~/utils/type';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  OverviewSummaryRequest,
  OverviewSummaryResponse,
  DeviceTrendRequest,
  DeviceTrendResponse,
  StoreDistributionResponse,
  TenantStatisticsResponse,
  ExportConfig,
  ExportTaskResponse,
  ReportConfig,
} from '~/routes/reports/overview/types';

// API基础路径
const API_BASE_PATH = '/api/reports/overview';

/**
 * 获取概览统计数据
 */
export const getOverviewSummary = async (
  request: OverviewSummaryRequest,
  token?: string,
): Promise<Result<OverviewSummaryResponse>> => {
  try {
    const result = await fetchResult<OverviewSummaryResponse>(`${API_BASE_PATH}/summary`, {
      method: 'post',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取概览统计数据失败');
    }
  } catch (error) {
    console.error('获取概览统计数据失败:', error);
    return ResultHelper.error('network_error', '获取概览统计数据失败');
  }
};

/**
 * 获取设备趋势数据
 */
export const getDeviceTrend = async (
  request: DeviceTrendRequest,
  token?: string,
): Promise<Result<DeviceTrendResponse>> => {
  try {
    const result = await fetchResult<DeviceTrendResponse>(`${API_BASE_PATH}/device-trend`, {
      method: 'post',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取设备趋势数据失败');
    }
  } catch (error) {
    console.error('获取设备趋势数据失败:', error);
    return ResultHelper.error('network_error', '获取设备趋势数据失败');
  }
};

/**
 * 获取门店分布统计
 */
export const getStoreDistribution = async (
  token?: string,
): Promise<Result<StoreDistributionResponse>> => {
  try {
    const result = await fetchResult<StoreDistributionResponse>(
      `${API_BASE_PATH}/store-distribution`,
      {
        method: 'get',
        token: token ?? undefined,
      },
    );
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取门店分布数据失败');
    }
  } catch (error) {
    console.error('获取门店分布数据失败:', error);
    return ResultHelper.error('network_error', '获取门店分布数据失败');
  }
};

/**
 * 获取租户统计数据（仅SuperAdmin）
 */
export const getTenantStatistics = async (
  token?: string,
): Promise<Result<TenantStatisticsResponse>> => {
  try {
    const result = await fetchResult<TenantStatisticsResponse>(
      `${API_BASE_PATH}/tenant-statistics`,
      {
        method: 'get',
        token: token ?? undefined,
      },
    );
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取租户统计数据失败');
    }
  } catch (error) {
    console.error('获取租户统计数据失败:', error);
    return ResultHelper.error('network_error', '获取租户统计数据失败');
  }
};

/**
 * 创建导出任务
 */
export const createExportTask = async (
  request: ExportConfig,
  token?: string,
): Promise<Result<ExportTaskResponse>> => {
  try {
    const result = await fetchResult<ExportTaskResponse>(`${API_BASE_PATH}/export`, {
      method: 'post',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '创建导出任务失败');
    }
  } catch (error) {
    console.error('创建导出任务失败:', error);
    return ResultHelper.error('network_error', '创建导出任务失败');
  }
};

/**
 * 检查导出任务状态
 */
export const checkExportStatus = async (
  taskId: string,
  token?: string,
): Promise<Result<ExportTaskResponse>> => {
  try {
    const result = await fetchResult<ExportTaskResponse>(`${API_BASE_PATH}/${taskId}/status`, {
      method: 'get',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '检查导出任务状态失败');
    }
  } catch (error) {
    console.error('检查导出任务状态失败:', error);
    return ResultHelper.error('network_error', '检查导出任务状态失败');
  }
};

/**
 * 获取导出文件下载链接
 */
export const getExportDownload = async (
  taskId: string,
  token?: string,
): Promise<Result<string>> => {
  try {
    const result = await fetchResult<string>(`${API_BASE_PATH}/${taskId}/download`, {
      method: 'get',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result) && result.data != null) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取导出文件下载链接失败');
    }
  } catch (error) {
    console.error('获取导出文件下载链接失败:', error);
    return ResultHelper.error('network_error', '获取导出文件下载链接失败');
  }
};

/**
 * 刷新缓存
 */
export const refreshCache = async (token?: string): Promise<Result<void>> => {
  try {
    const result = await fetchResult<void>(`${API_BASE_PATH}/refresh-cache`, {
      method: 'post',
      params: {},
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(undefined);
    } else {
      return ResultHelper.error(result.code, result.desc || '刷新缓存失败');
    }
  } catch (error) {
    console.error('刷新缓存失败:', error);
    return ResultHelper.error('network_error', '刷新缓存失败');
  }
};

/**
 * 获取报表配置
 */
export const getReportConfig = async (token?: string): Promise<Result<any>> => {
  try {
    const result = await fetchResult<ReportConfig>(`${API_BASE_PATH}/config`, {
      method: 'get',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取报表配置失败');
    }
  } catch (error) {
    console.error('获取报表配置失败:', error);
    return ResultHelper.error('network_error', '获取报表配置失败');
  }
};

/**
 * 并行获取概览页面所有数据
 */
export async function getOverviewData(
  timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom' = 'month',
  startDate?: string,
  endDate?: string,
): Promise<{
  summary: Result<OverviewSummaryResponse>;
  trend: Result<DeviceTrendResponse>;
  distribution: Result<StoreDistributionResponse>;
  tenantStats?: Result<TenantStatisticsResponse>;
  config: Result<any>;
}> {
  const params: OverviewSummaryRequest = {
    timeRange,
    startDate,
    endDate,
  };

  const trendRequest: DeviceTrendRequest = {
    timeRange,
    startDate,
    endDate,
    granularity: timeRange === 'today' ? 'hour' : 'day',
  };

  try {
    // 并行请求所有数据
    const [summary, trend, distribution, config] = await Promise.all([
      getOverviewSummary(params),
      getDeviceTrend(trendRequest),
      getStoreDistribution(),
      getReportConfig(),
    ]);

    // 如果是SuperAdmin，获取租户统计
    let tenantStats = undefined;
    if (config.data?.permissions?.canViewTenantStats) {
      tenantStats = await getTenantStatistics();
    }

    return {
      summary,
      trend,
      distribution,
      tenantStats,
      config,
    };
  } catch (error) {
    console.error('获取概览数据失败:', error);
    throw error;
  }
}

/**
 * 错误处理工具函数
 */
export function handleApiError(error: any): string {
  if (error instanceof Error) {
    return error.message;
  }

  if (typeof error === 'string') {
    return error;
  }

  if (error?.message) {
    return error.message;
  }

  return '未知错误，请稍后重试';
}
