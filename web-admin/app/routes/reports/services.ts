import { ResultHelper } from '~/utils/type';
import { fetchResult } from '~/shared/services/http-client';
import type {
  OverviewSummaryRequest,
  DeviceTrendRequest,
  ExportConfig,
} from '~/routes/reports/overview/types';

// API 基础路径
const API_BASE = '/api/reports';

/**
 * 获取报表配置
 */
export async function getReportConfig(token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/config`, {
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
}

/**
 * 获取概览统计数据
 */
export async function getOverviewSummary(request: OverviewSummaryRequest, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/overview/summary`, {
      method: 'post',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取概览统计数据失败');
    }
  } catch (error) {
    console.error('获取概览统计数据失败:', error);
    return ResultHelper.error('network_error', '获取概览统计数据失败');
  }
}

/**
 * 获取设备趋势数据
 */
export async function getDeviceTrend(request: DeviceTrendRequest, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/overview/device-trend`, {
      method: 'post',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取设备趋势数据失败');
    }
  } catch (error) {
    console.error('获取设备趋势数据失败:', error);
    return ResultHelper.error('network_error', '获取设备趋势数据失败');
  }
}

/**
 * 获取门店分布数据
 */
export async function getStoreDistribution(request?: any, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/overview/store-distribution`, {
      method: 'get',
      params: request,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取门店分布数据失败');
    }
  } catch (error) {
    console.error('获取门店分布数据失败:', error);
    return ResultHelper.error('network_error', '获取门店分布数据失败');
  }
}

/**
 * 刷新缓存
 */
export async function refreshCache(token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/cache/refresh`, {
      method: 'post',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(null);
    } else {
      return ResultHelper.error(result.code, result.desc || '刷新缓存失败');
    }
  } catch (error) {
    console.error('刷新缓存失败:', error);
    return ResultHelper.error('network_error', '刷新缓存失败');
  }
}

/**
 * 创建导出任务
 */
export async function createExportTask(config: ExportConfig, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/export`, {
      method: 'post',
      params: config,
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '创建导出任务失败');
    }
  } catch (error) {
    console.error('创建导出任务失败:', error);
    return ResultHelper.error('network_error', '创建导出任务失败');
  }
}

/**
 * 检查导出任务状态
 */
export async function checkExportStatus(taskId: string, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/export/${taskId}/status`, {
      method: 'get',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '检查导出状态失败');
    }
  } catch (error) {
    console.error('检查导出状态失败:', error);
    return ResultHelper.error('network_error', '检查导出状态失败');
  }
}

/**
 * 获取导出文件下载链接
 */
export async function getExportDownload(taskId: string, token?: string) {
  try {
    const result = await fetchResult(`${API_BASE}/export/${taskId}/download`, {
      method: 'get',
      token: token ?? undefined,
    });
    if (ResultHelper.isSuccess(result)) {
      return ResultHelper.success(result.data);
    } else {
      return ResultHelper.error(result.code, result.desc || '获取下载链接失败');
    }
  } catch (error) {
    console.error('获取下载链接失败:', error);
    return ResultHelper.error('network_error', '获取下载链接失败');
  }
}
