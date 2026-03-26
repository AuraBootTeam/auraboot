import { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router';
import {
  ArrowLeftIcon,
  PencilIcon,
  TrashIcon,
  PlayIcon,
  StopIcon,
  ArrowPathIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';

interface DeviceDetail {
  id: string;
  pid: string;
  deviceName: string;
  deviceCode: string;
  deviceType: string;
  status: 'online' | 'offline' | 'inactive';
  storeName: string;
  storeId: string;
  ipAddress: string;
  macAddress: string;
  osVersion: string;
  appVersion: string;
  screenResolution: string;
  lastOnlineTime: string;
  activatedAt: string;
  createdAt: string;
  description?: string;
  currentPlaylist?: {
    id: string;
    name: string;
    status: string;
  };
  systemInfo: {
    cpuUsage: number;
    memoryUsage: number;
    diskUsage: number;
    temperature: number;
  };
}

const DeviceDetail = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const { deviceId } = useParams();
  const [device, setDevice] = useState<DeviceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  useEffect(() => {
    if (deviceId) {
      fetchDeviceDetail();
    }
  }, [deviceId]);

  const fetchDeviceDetail = async () => {
    try {
      setLoading(true);
      const response = await fetch(`/api/device/${deviceId}`);
      const data = await response.json();

      if (data.code === '0') {
        setDevice(data.data);
      } else {
        showErrorToast('获取设备详情失败');
      }
    } catch (error) {
      console.error('Error fetching device detail:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const handleDeviceAction = async (action: 'restart' | 'shutdown' | 'activate') => {
    try {
      setActionLoading(true);
      const response = await fetch(`/api/device/${deviceId}/actions`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ action }),
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast(
          `设备${action === 'restart' ? '重启' : action === 'shutdown' ? '关机' : '激活'}指令已发送`,
        );
        // 刷新设备信息
        setTimeout(() => {
          fetchDeviceDetail();
        }, 2000);
      } else {
        showErrorToast(data.message || '操作失败');
      }
    } catch (error) {
      console.error('Error performing device action:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setActionLoading(false);
    }
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
        className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${config.bg} ${config.text}`}
      >
        {config.label}
      </span>
    );
  };

  const getUsageColor = (usage: number) => {
    if (usage < 50) return 'bg-green-500';
    if (usage < 80) return 'bg-yellow-500';
    return 'bg-red-500';
  };

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 h-4 w-1/2 rounded bg-gray-200"></div>
          <div className="space-y-4">
            <div className="h-32 rounded bg-gray-200"></div>
            <div className="h-32 rounded bg-gray-200"></div>
          </div>
        </div>
      </div>
    );
  }

  if (!device) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="py-12 text-center">
          <h3 className="text-lg font-medium text-gray-900">设备不存在</h3>
          <p className="mt-2 text-sm text-gray-500">请检查设备ID是否正确</p>
          <Link
            to="/device"
            className="mt-4 inline-flex items-center rounded-md border border-transparent bg-indigo-100 px-4 py-2 text-sm font-medium text-indigo-600 hover:bg-indigo-200"
          >
            返回设备列表
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面头部 */}
      <div className="mb-8">
        <div className="flex items-center justify-between">
          <div className="flex items-center">
            <Link to="/device" className="mr-4 p-2 text-gray-400 hover:text-gray-600">
              <ArrowLeftIcon className="h-5 w-5" />
            </Link>
            <div>
              <h1 className="text-2xl font-semibold text-gray-900">{device.deviceName}</h1>
              <p className="text-sm text-gray-500">设备编码: {device.deviceCode}</p>
            </div>
          </div>
          <div className="flex items-center space-x-3">
            {getStatusBadge(device.status)}
            <Link
              to={`/device/${device.pid}/edit`}
              className="inline-flex items-center rounded-md border border-gray-300 bg-white px-3 py-2 text-sm leading-4 font-medium text-gray-700 shadow-sm hover:bg-gray-50"
            >
              <PencilIcon className="mr-1 h-4 w-4" />
              编辑
            </Link>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* 基本信息 */}
        <div className="lg:col-span-2">
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">基本信息</h3>
            </div>
            <div className="px-6 py-4">
              <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <div>
                  <dt className="text-sm font-medium text-gray-500">设备名称</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.deviceName}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">设备类型</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.deviceType}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">所属门店</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.storeName}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">IP地址</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.ipAddress || '-'}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">MAC地址</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.macAddress || '-'}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">屏幕分辨率</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.screenResolution || '-'}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">系统版本</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.osVersion || '-'}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">应用版本</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.appVersion || '-'}</dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">激活时间</dt>
                  <dd className="mt-1 text-sm text-gray-900">
                    {device.activatedAt ? new Date(device.activatedAt).toLocaleString() : '-'}
                  </dd>
                </div>
                <div>
                  <dt className="text-sm font-medium text-gray-500">最后在线时间</dt>
                  <dd className="mt-1 text-sm text-gray-900">
                    {device.lastOnlineTime ? new Date(device.lastOnlineTime).toLocaleString() : '-'}
                  </dd>
                </div>
              </dl>
              {device.description && (
                <div className="mt-4">
                  <dt className="text-sm font-medium text-gray-500">设备描述</dt>
                  <dd className="mt-1 text-sm text-gray-900">{device.description}</dd>
                </div>
              )}
            </div>
          </div>

          {/* 当前播放列表 */}
          {device.currentPlaylist && (
            <div className="mt-6 rounded-lg bg-white shadow">
              <div className="border-b border-gray-200 px-6 py-4">
                <h3 className="text-lg font-medium text-gray-900">当前播放列表</h3>
              </div>
              <div className="px-6 py-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-gray-900">
                      {device.currentPlaylist.name}
                    </p>
                    <p className="text-sm text-gray-500">状态: {device.currentPlaylist.status}</p>
                  </div>
                  <Link
                    to={`/playlist/${device.currentPlaylist.id}`}
                    className="text-sm font-medium text-indigo-600 hover:text-indigo-900"
                  >
                    查看详情
                  </Link>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* 系统监控和操作 */}
        <div className="space-y-6">
          {/* 系统监控 */}
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">系统监控</h3>
            </div>
            <div className="space-y-4 px-6 py-4">
              <div>
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">CPU使用率</span>
                  <span className="font-medium">{device.systemInfo.cpuUsage}%</span>
                </div>
                <div className="mt-1 h-2 w-full rounded-full bg-gray-200">
                  <div
                    className={`h-2 rounded-full ${getUsageColor(device.systemInfo.cpuUsage)}`}
                    style={{ width: `${device.systemInfo.cpuUsage}%` }}
                  ></div>
                </div>
              </div>

              <div>
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">内存使用率</span>
                  <span className="font-medium">{device.systemInfo.memoryUsage}%</span>
                </div>
                <div className="mt-1 h-2 w-full rounded-full bg-gray-200">
                  <div
                    className={`h-2 rounded-full ${getUsageColor(device.systemInfo.memoryUsage)}`}
                    style={{ width: `${device.systemInfo.memoryUsage}%` }}
                  ></div>
                </div>
              </div>

              <div>
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">磁盘使用率</span>
                  <span className="font-medium">{device.systemInfo.diskUsage}%</span>
                </div>
                <div className="mt-1 h-2 w-full rounded-full bg-gray-200">
                  <div
                    className={`h-2 rounded-full ${getUsageColor(device.systemInfo.diskUsage)}`}
                    style={{ width: `${device.systemInfo.diskUsage}%` }}
                  ></div>
                </div>
              </div>

              <div>
                <div className="flex justify-between text-sm">
                  <span className="text-gray-500">设备温度</span>
                  <span className="font-medium">{device.systemInfo.temperature}°C</span>
                </div>
              </div>
            </div>
          </div>

          {/* 设备操作 */}
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">设备操作</h3>
            </div>
            <div className="space-y-3 px-6 py-4">
              {device.status === 'online' && (
                <>
                  <button
                    onClick={() => handleDeviceAction('restart')}
                    disabled={actionLoading}
                    className="flex w-full items-center justify-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50 disabled:opacity-50"
                  >
                    <ArrowPathIcon className="mr-2 h-4 w-4" />
                    重启设备
                  </button>
                  <button
                    onClick={() => handleDeviceAction('shutdown')}
                    disabled={actionLoading}
                    className="flex w-full items-center justify-center rounded-md border border-red-300 bg-white px-4 py-2 text-sm font-medium text-red-700 shadow-sm hover:bg-red-50 disabled:opacity-50"
                  >
                    <StopIcon className="mr-2 h-4 w-4" />
                    关闭设备
                  </button>
                </>
              )}

              {device.status === 'inactive' && (
                <button
                  onClick={() => handleDeviceAction('activate')}
                  disabled={actionLoading}
                  className="flex w-full items-center justify-center rounded-md border border-green-300 bg-white px-4 py-2 text-sm font-medium text-green-700 shadow-sm hover:bg-green-50 disabled:opacity-50"
                >
                  <PlayIcon className="mr-2 h-4 w-4" />
                  激活设备
                </button>
              )}

              <button
                onClick={fetchDeviceDetail}
                disabled={loading}
                className="flex w-full items-center justify-center rounded-md border border-indigo-300 bg-white px-4 py-2 text-sm font-medium text-indigo-700 shadow-sm hover:bg-indigo-50 disabled:opacity-50"
              >
                <ArrowPathIcon className="mr-2 h-4 w-4" />
                刷新信息
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default DeviceDetail;
