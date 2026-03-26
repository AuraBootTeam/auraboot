import { useState, useEffect } from 'react';
import {
  ComputerDesktopIcon,
  SignalIcon,
  ExclamationTriangleIcon,
  ChartBarIcon,
  ArrowPathIcon,
  EyeIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';
import { Link } from 'react-router';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';

interface DeviceStats {
  total: number;
  online: number;
  offline: number;
  inactive: number;
  warning: number;
}

interface DeviceMonitorData {
  id: string;
  deviceName: string;
  deviceCode: string;
  status: 'online' | 'offline' | 'inactive';
  storeName: string;
  lastOnlineTime: string;
  systemInfo: {
    cpuUsage: number;
    memoryUsage: number;
    diskUsage: number;
    temperature: number;
  };
  alerts: {
    level: 'warning' | 'error';
    message: string;
    time: string;
  }[];
}

interface ChartData {
  time: string;
  online: number;
  offline: number;
}

const DeviceMonitor = () => {
  const { showErrorToast } = useToast();
  const [stats, setStats] = useState<DeviceStats>({
    total: 0,
    online: 0,
    offline: 0,
    inactive: 0,
    warning: 0,
  });
  const [devices, setDevices] = useState<DeviceMonitorData[]>([]);
  const [chartData, setChartData] = useState<ChartData[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedTimeRange, setSelectedTimeRange] = useState('24h');

  useEffect(() => {
    fetchMonitorData();
    fetchChartData();

    // 设置自动刷新
    const interval = setInterval(() => {
      fetchMonitorData();
    }, 30000); // 30秒刷新一次

    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    fetchChartData();
  }, [selectedTimeRange]);

  const fetchMonitorData = async () => {
    try {
      if (!refreshing) setLoading(true);

      const [statsResult, devicesResult] = await Promise.all([
        get<DeviceStats>('/device/stats'),
        get<DeviceMonitorData[]>('/device/monitor'),
      ]);

      if (ResultHelper.isSuccess(statsResult) && statsResult.data) {
        setStats(statsResult.data);
      }

      if (ResultHelper.isSuccess(devicesResult) && devicesResult.data) {
        setDevices(devicesResult.data);
      }
    } catch (error) {
      console.error('Error fetching monitor data:', error);
      showErrorToast('获取监控数据失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const fetchChartData = async () => {
    try {
      const result = await get<ChartData[]>('/device/chart', {
        params: { range: selectedTimeRange },
      });

      if (ResultHelper.isSuccess(result) && result.data) {
        setChartData(result.data);
      }
    } catch (error) {
      console.error('Error fetching chart data:', error);
    }
  };

  const handleRefresh = () => {
    setRefreshing(true);
    fetchMonitorData();
    fetchChartData();
  };

  const getStatusBadge = (status: string) => {
    const statusConfig = {
      ONLINE: { bg: 'bg-green-100', text: 'text-green-800', label: '在线' },
      OFFLINE: { bg: 'bg-red-100', text: 'text-red-800', label: '离线' },
      inactive: { bg: 'bg-gray-100', text: 'text-gray-800', label: '未激活' },
    };

    const config = statusConfig[status as keyof typeof statusConfig] || statusConfig.inactive;

    return (
      <span
        className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${config.bg} ${config.text}`}
      >
        {config.label}
      </span>
    );
  };

  const getUsageColor = (usage: number) => {
    if (usage < 50) return 'text-green-600';
    if (usage < 80) return 'text-yellow-600';
    return 'text-red-600';
  };

  const getUsageBarColor = (usage: number) => {
    if (usage < 50) return 'bg-green-500';
    if (usage < 80) return 'bg-yellow-500';
    return 'bg-red-500';
  };

  const getAlertIcon = (level: string) => {
    return level === 'error' ? (
      <ExclamationTriangleIcon className="h-4 w-4 text-red-500" />
    ) : (
      <ExclamationTriangleIcon className="h-4 w-4 text-yellow-500" />
    );
  };

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 grid grid-cols-1 gap-4 md:grid-cols-4">
            {[...Array(4)].map((_, i) => (
              <div key={i} className="h-24 rounded bg-gray-200"></div>
            ))}
          </div>
          <div className="mb-8 h-64 rounded bg-gray-200"></div>
          <div className="h-96 rounded bg-gray-200"></div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面头部 */}
      <div className="mb-8 sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">设备监控面板</h1>
          <p className="mt-2 text-sm text-gray-700">实时监控设备状态和系统性能</p>
        </div>
        <div className="mt-4 sm:mt-0">
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            className="inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50 disabled:opacity-50"
          >
            <ArrowPathIcon className={`mr-2 h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />
            刷新数据
          </button>
        </div>
      </div>

      {/* 统计卡片 */}
      <div className="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        <div className="overflow-hidden rounded-lg bg-white shadow">
          <div className="p-5">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <ComputerDesktopIcon className="h-6 w-6 text-gray-400" />
              </div>
              <div className="ml-5 w-0 flex-1">
                <dl>
                  <dt className="truncate text-sm font-medium text-gray-500">设备总数</dt>
                  <dd className="text-lg font-medium text-gray-900">{stats.total}</dd>
                </dl>
              </div>
            </div>
          </div>
        </div>

        <div className="overflow-hidden rounded-lg bg-white shadow">
          <div className="p-5">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <SignalIcon className="h-6 w-6 text-green-400" />
              </div>
              <div className="ml-5 w-0 flex-1">
                <dl>
                  <dt className="truncate text-sm font-medium text-gray-500">在线设备</dt>
                  <dd className="text-lg font-medium text-green-600">{stats.online}</dd>
                </dl>
              </div>
            </div>
          </div>
        </div>

        <div className="overflow-hidden rounded-lg bg-white shadow">
          <div className="p-5">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <ExclamationTriangleIcon className="h-6 w-6 text-red-400" />
              </div>
              <div className="ml-5 w-0 flex-1">
                <dl>
                  <dt className="truncate text-sm font-medium text-gray-500">离线设备</dt>
                  <dd className="text-lg font-medium text-red-600">{stats.offline}</dd>
                </dl>
              </div>
            </div>
          </div>
        </div>

        <div className="overflow-hidden rounded-lg bg-white shadow">
          <div className="p-5">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <ExclamationTriangleIcon className="h-6 w-6 text-yellow-400" />
              </div>
              <div className="ml-5 w-0 flex-1">
                <dl>
                  <dt className="truncate text-sm font-medium text-gray-500">告警设备</dt>
                  <dd className="text-lg font-medium text-yellow-600">{stats.warning}</dd>
                </dl>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* 趋势图表 */}
      <div className="mb-8 rounded-lg bg-white shadow">
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-medium text-gray-900">设备状态趋势</h3>
            <div className="flex space-x-2">
              {['1h', '6h', '24h', '7d'].map((range) => (
                <button
                  key={range}
                  onClick={() => setSelectedTimeRange(range)}
                  className={`rounded-md px-3 py-1 text-sm ${
                    selectedTimeRange === range
                      ? 'bg-indigo-100 text-indigo-700'
                      : 'text-gray-500 hover:text-gray-700'
                  }`}
                >
                  {range}
                </button>
              ))}
            </div>
          </div>
        </div>
        <div className="p-6">
          {chartData.length > 0 ? (
            <div className="h-64">
              {/* 这里可以集成图表库如 Chart.js 或 Recharts */}
              <div className="flex h-full items-center justify-center text-gray-500">
                <ChartBarIcon className="mr-2 h-8 w-8" />
                图表组件待集成 (建议使用 Recharts)
              </div>
            </div>
          ) : (
            <div className="flex h-64 items-center justify-center text-gray-500">
              <ChartBarIcon className="mr-2 h-8 w-8" />
              暂无图表数据
            </div>
          )}
        </div>
      </div>

      {/* 设备详细监控 */}
      <div className="rounded-lg bg-white shadow">
        <div className="border-b border-gray-200 px-6 py-4">
          <h3 className="text-lg font-medium text-gray-900">设备详细监控</h3>
        </div>
        <div className="overflow-hidden">
          {devices.length === 0 ? (
            <div className="py-12 text-center">
              <ComputerDesktopIcon className="mx-auto h-12 w-12 text-gray-400" />
              <h3 className="mt-2 text-sm font-medium text-gray-900">暂无设备数据</h3>
              <p className="mt-1 text-sm text-gray-500">请检查设备连接状态</p>
            </div>
          ) : (
            <div className="divide-y divide-gray-200">
              {devices.map((device) => (
                <div key={device.id} className="p-6">
                  <div className="mb-4 flex items-center justify-between">
                    <div className="flex items-center">
                      <div>
                        <h4 className="text-lg font-medium text-gray-900">{device.deviceName}</h4>
                        <p className="text-sm text-gray-500">
                          {device.deviceCode} · {device.storeName}
                        </p>
                      </div>
                      <div className="ml-4">{getStatusBadge(device.status)}</div>
                    </div>
                    <div className="flex items-center space-x-2">
                      <Link
                        to={`/device/${device.id}`}
                        className="inline-flex items-center rounded-md border border-gray-300 bg-white px-3 py-1 text-sm leading-4 font-medium text-gray-700 shadow-sm hover:bg-gray-50"
                      >
                        <EyeIcon className="mr-1 h-4 w-4" />
                        查看详情
                      </Link>
                    </div>
                  </div>

                  {device.status === 'online' && (
                    <div className="mb-4 grid grid-cols-1 gap-4 md:grid-cols-4">
                      <div>
                        <div className="mb-1 flex justify-between text-sm">
                          <span className="text-gray-500">CPU</span>
                          <span
                            className={`font-medium ${getUsageColor(device.systemInfo.cpuUsage)}`}
                          >
                            {device.systemInfo.cpuUsage}%
                          </span>
                        </div>
                        <div className="h-2 w-full rounded-full bg-gray-200">
                          <div
                            className={`h-2 rounded-full ${getUsageBarColor(device.systemInfo.cpuUsage)}`}
                            style={{ width: `${device.systemInfo.cpuUsage}%` }}
                          ></div>
                        </div>
                      </div>

                      <div>
                        <div className="mb-1 flex justify-between text-sm">
                          <span className="text-gray-500">内存</span>
                          <span
                            className={`font-medium ${getUsageColor(device.systemInfo.memoryUsage)}`}
                          >
                            {device.systemInfo.memoryUsage}%
                          </span>
                        </div>
                        <div className="h-2 w-full rounded-full bg-gray-200">
                          <div
                            className={`h-2 rounded-full ${getUsageBarColor(device.systemInfo.memoryUsage)}`}
                            style={{ width: `${device.systemInfo.memoryUsage}%` }}
                          ></div>
                        </div>
                      </div>

                      <div>
                        <div className="mb-1 flex justify-between text-sm">
                          <span className="text-gray-500">磁盘</span>
                          <span
                            className={`font-medium ${getUsageColor(device.systemInfo.diskUsage)}`}
                          >
                            {device.systemInfo.diskUsage}%
                          </span>
                        </div>
                        <div className="h-2 w-full rounded-full bg-gray-200">
                          <div
                            className={`h-2 rounded-full ${getUsageBarColor(device.systemInfo.diskUsage)}`}
                            style={{ width: `${device.systemInfo.diskUsage}%` }}
                          ></div>
                        </div>
                      </div>

                      <div>
                        <div className="mb-1 flex justify-between text-sm">
                          <span className="text-gray-500">温度</span>
                          <span
                            className={`font-medium ${getUsageColor(device.systemInfo.temperature > 70 ? 80 : 30)}`}
                          >
                            {device.systemInfo.temperature}°C
                          </span>
                        </div>
                      </div>
                    </div>
                  )}

                  {device.alerts && device.alerts.length > 0 && (
                    <div className="mt-4">
                      <h5 className="mb-2 text-sm font-medium text-gray-900">告警信息</h5>
                      <div className="space-y-2">
                        {device.alerts.slice(0, 3).map((alert, index) => (
                          <div key={index} className="flex items-center text-sm">
                            {getAlertIcon(alert.level)}
                            <span className="ml-2 text-gray-600">{alert.message}</span>
                            <span className="ml-auto text-gray-400">
                              {new Date(alert.time).toLocaleTimeString()}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="mt-4 text-xs text-gray-500">
                    最后在线时间:{' '}
                    {device.lastOnlineTime
                      ? new Date(device.lastOnlineTime).toLocaleString()
                      : '从未在线'}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default DeviceMonitor;
