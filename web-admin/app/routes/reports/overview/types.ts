// 报表概览页面类型定义

// 基础统计数据
export interface StatisticData {
  label: string;
  value: number;
  trend?: {
    change: number;
    percentage: number;
    isPositive: boolean;
  };
}

// 概览摘要请求
export interface OverviewSummaryRequest {
  timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
  startDate?: string;
  endDate?: string;
}

export type TimeRange = OverviewSummaryRequest['timeRange'];

// 概览摘要响应
export interface OverviewSummaryResponse {
  deviceStats: {
    total: number;
    online: number;
    offline: number;
    fault: number;
    maintenance: number;
  };
  storeStats: {
    total: number;
    active: number;
    inactive: number;
  };
  contentStats: {
    total: number;
    published: number;
    draft: number;
    archived: number;
  };
  tenantStats?: {
    total: number;
    active: number;
    inactive: number;
  };
}

// 设备趋势请求
export interface DeviceTrendRequest {
  timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
  startDate?: string;
  endDate?: string;
  granularity?: 'hour' | 'day' | 'week' | 'month';
}

// 设备趋势数据点
export interface DeviceTrendDataPoint {
  date: string;
  total: number;
  online: number;
  offline: number;
  fault: number;
  maintenance: number;
}

// 设备趋势响应
export interface DeviceTrendResponse {
  data: DeviceTrendDataPoint[];
  summary: {
    totalChange: number;
    totalChangePercentage: number;
    onlineChange: number;
    onlineChangePercentage: number;
    offlineChange: number;
    offlineChangePercentage: number;
    faultChange: number;
    faultChangePercentage: number;
    maintenanceChange: number;
    maintenanceChangePercentage: number;
  };
}

// 门店分布数据
export interface StoreDistributionData {
  region: string;
  total: number;
  active: number;
  inactive: number;
  activeRate: number;
}

// 门店分布响应
export interface StoreDistributionResponse {
  summary: {
    totalStores: number;
    activeStores: number;
    inactiveStores: number;
    activeRate: number;
  };
  regionData: StoreDistributionData[];
}

// 租户统计响应
export interface TenantStatisticsResponse {
  summary: {
    totalTenants: number;
    activeTenants: number;
    inactiveTenants: number;
    activeRate: number;
  };
  recentActivity: {
    newTenants: number;
    activatedTenants: number;
    deactivatedTenants: number;
  };
}

// 导出任务状态
export type ExportTaskStatus = 'pending' | 'processing' | 'completed' | 'failed';

// 导出配置
export interface ExportConfig {
  type: 'overview' | 'device_trend' | 'store_distribution' | 'all';
  format: 'excel' | 'csv' | 'pdf';
  timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
  startDate?: string;
  endDate?: string;
  includeCharts?: boolean;
}

// 导出任务响应
export interface ExportTaskResponse {
  taskId: string;
  status: ExportTaskStatus;
  progress?: number;
  downloadUrl?: string;
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
}

// 自定义日期范围
export interface CustomDateRange {
  start: string;
  end: string;
  startDate?: string;
  endDate?: string;
}

// 报表配置
export interface ReportConfig {
  refreshInterval: number; // 自动刷新间隔（秒）
  defaultTimeRange: 'today' | 'week' | 'month' | 'quarter' | 'year';
  enableAutoRefresh: boolean;
  chartColors: {
    primary: string;
    success: string;
    warning: string;
    danger: string;
    info: string;
  };
}

// 页面状态
export interface ReportOverviewState {
  loading: boolean;
  refreshing: boolean;
  summary: OverviewSummaryResponse | null;
  deviceTrend: DeviceTrendResponse | null;
  storeDistribution: StoreDistributionResponse | null;
  tenantStatistics: TenantStatisticsResponse | null;
  timeRange: 'today' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
  customDateRange: CustomDateRange | null;
  config: ReportConfig | null;
  error: string | null;
}
