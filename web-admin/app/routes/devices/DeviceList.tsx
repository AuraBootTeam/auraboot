import { useState, useEffect } from 'react';
import { Link } from 'react-router';
import {
  PlusIcon,
  MagnifyingGlassIcon,
  FunnelIcon,
  QrCodeIcon,
  LinkIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';
import { useAuth } from '~/contexts/AuthContext';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import QrCodeScanner from '~/ui/QrCodeScanner';
import { confirmDialog } from '~/utils/confirmDialog';

interface Device {
  id: string;
  pid: string;
  deviceName: string;
  deviceCode: string;
  deviceType: string;
  status: 'online' | 'offline' | 'inactive';
  storeName: string;
  storePid?: string;
  lastOnlineTime: string;
  createdAt: string;
}

interface Store {
  pid: string;
  name: string;
  code: string;
  status: string;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

const DeviceList = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const { token } = useAuth();
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [currentPage, setCurrentPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [showQrScanner, setShowQrScanner] = useState(false);
  const [showStoreModal, setShowStoreModal] = useState(false);
  const [selectedDevice, setSelectedDevice] = useState<Device | null>(null);
  const [stores, setStores] = useState<Store[]>([]);
  const [selectedDevices, setSelectedDevices] = useState<string[]>([]);
  const [batchMode, setBatchMode] = useState(false);

  useEffect(() => {
    fetchDevices();
  }, [currentPage, searchTerm, statusFilter]);

  useEffect(() => {
    fetchStores();
  }, []);

  const fetchDevices = async () => {
    try {
      setLoading(true);
      const params = {
        page: currentPage.toString(),
        size: '10',
        ...(searchTerm && { search: searchTerm }),
        ...(statusFilter !== 'all' && { status: statusFilter }),
      };

      const result = await fetchResult<PaginationResult<Device>>('/api/device', {
        method: 'get',
        params,
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        setDevices(result.data?.records || []);
        setTotalPages(result.data?.totalPages || 1);
      } else {
        throw new Error(result.desc || 'Failed to fetch devices');
      }
    } catch (error) {
      console.error('Error fetching devices:', error);
      showErrorToast('获取设备列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchStores = async () => {
    try {
      const result = await fetchResult<PaginationResult<Store>>('/api/stores/search', {
        method: 'post',
        params: {
          page: 1,
          size: 100,
          status: 'active',
        },
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        setStores(result.data?.records || []);
      } else {
        throw new Error(result.desc || 'Failed to fetch stores');
      }
    } catch (error) {
      console.error('Error fetching stores:', error);
      showErrorToast('获取门店列表失败');
    }
  };

  const bindDeviceToStore = async (devicePid: string, storePid: string) => {
    try {
      const result = await fetchResult('/api/device/bind-store', {
        method: 'post',
        params: {
          devicePid,
          storePid,
          reason: '手动绑定设备到门店',
        },
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('设备绑定成功');
        fetchDevices();
      } else {
        throw new Error(result.desc || 'Failed to bind device to store');
      }
    } catch (error) {
      console.error('Error binding device to store:', error);
      showErrorToast('设备绑定失败');
    }
  };

  const unbindDeviceFromStore = async (devicePid: string) => {
    try {
      const result = await fetchResult('/api/device/unbind-store', {
        method: 'post',
        params: {
          devicePid,
          reason: '手动解绑设备',
        },
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('设备解绑成功');
        fetchDevices();
      } else {
        throw new Error(result.desc || 'Failed to unbind device from store');
      }
    } catch (error) {
      console.error('Error unbinding device from store:', error);
      showErrorToast('设备解绑失败');
    }
  };

  const batchBindDevicesToStore = async (devicePids: string[], storePid: string) => {
    try {
      const result = await fetchResult('/api/device/batch-bind-store', {
        method: 'post',
        params: {
          devicePids,
          storePid,
          reason: '批量绑定设备到门店',
        },
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('批量绑定成功');
        fetchDevices();
        setSelectedDevices([]);
        setBatchMode(false);
      } else {
        throw new Error(result.desc || 'Failed to batch bind devices to store');
      }
    } catch (error) {
      console.error('Error batch binding devices to store:', error);
      showErrorToast('批量绑定失败');
    }
  };

  const batchUnbindDevicesFromStore = async (devicePids: string[]) => {
    try {
      const result = await fetchResult('/api/device/batch-unbind-store', {
        method: 'post',
        params: {
          devicePids,
          reason: '批量解绑设备',
        },
        token,
      });

      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('批量解绑成功');
        fetchDevices();
        setSelectedDevices([]);
        setBatchMode(false);
      } else {
        throw new Error(result.desc || 'Failed to batch unbind devices from store');
      }
    } catch (error) {
      console.error('Error batch unbinding devices from store:', error);
      showErrorToast('批量解绑失败');
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

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setCurrentPage(1);
    fetchDevices();
  };

  const handleQrCodeScan = (result: string) => {
    setShowQrScanner(false);
    // TODO: 处理扫描结果
  };

  const handleBindDevice = (device: Device) => {
    setSelectedDevice(device);
    setShowStoreModal(true);
  };

  const handleUnbindDevice = async (device: Device) => {
    if (await confirmDialog({ content: `确定要解绑设备 "${device.deviceName}" 吗？` })) {
      await unbindDeviceFromStore(device.pid);
    }
  };

  const handleStoreSelect = async (storePid: string) => {
    if (!selectedDevice) return;

    try {
      await bindDeviceToStore(selectedDevice.pid, storePid);
      setShowStoreModal(false);
      setSelectedDevice(null);
      fetchDevices();
      // useToast already shows success feedback
    } catch (error) {
      console.error('绑定失败:', error);
    }
  };

  const handleDeviceSelect = (devicePid: string, checked: boolean) => {
    if (checked) {
      setSelectedDevices([...selectedDevices, devicePid]);
    } else {
      setSelectedDevices(selectedDevices.filter((pid) => pid !== devicePid));
    }
  };

  const handleBatchBind = async (storePid: string) => {
    if (selectedDevices.length > 0) {
      await batchBindDevicesToStore(selectedDevices, storePid);
    }
  };

  const handleBatchUnbind = async () => {
    if (
      selectedDevices.length > 0 &&
      (await confirmDialog({ content: `确定要解绑选中的 ${selectedDevices.length} 个设备吗？` }))
    ) {
      await batchUnbindDevicesFromStore(selectedDevices);
    }
  };

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面标题 */}
      <div className="sm:flex sm:items-center">
        <div className="sm:flex-auto">
          <h1 className="text-2xl leading-6 font-semibold text-gray-900">设备管理</h1>
          <p className="mt-2 text-sm text-gray-700">管理所有设备的状态、配置和监控信息。</p>
        </div>
        <div className="mt-4 sm:mt-0 sm:ml-16 sm:flex-none">
          <div className="flex space-x-3">
            {batchMode && (
              <>
                <button
                  type="button"
                  onClick={() => {
                    if (selectedDevices.length > 0) {
                      setSelectedDevice({ pid: 'batch' } as Device);
                      setShowStoreModal(true);
                    } else {
                      showErrorToast('请先选择要绑定的设备');
                    }
                  }}
                  disabled={selectedDevices.length === 0}
                  className="inline-flex items-center gap-x-1.5 rounded-md bg-green-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-green-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-green-600 disabled:cursor-not-allowed disabled:bg-gray-400"
                >
                  <LinkIcon className="-ml-0.5 h-5 w-5" aria-hidden="true" />
                  批量绑定 ({selectedDevices.length})
                </button>
                <button
                  type="button"
                  onClick={handleBatchUnbind}
                  disabled={selectedDevices.length === 0}
                  className="inline-flex items-center gap-x-1.5 rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-red-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-600 disabled:cursor-not-allowed disabled:bg-gray-400"
                >
                  <XMarkIcon className="-ml-0.5 h-5 w-5" aria-hidden="true" />
                  批量解绑 ({selectedDevices.length})
                </button>
              </>
            )}
            <button
              type="button"
              onClick={() => {
                setBatchMode(!batchMode);
                setSelectedDevices([]);
              }}
              className={`inline-flex items-center gap-x-1.5 rounded-md px-3 py-2 text-sm font-semibold shadow-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 ${
                batchMode
                  ? 'bg-gray-600 text-white hover:bg-gray-500 focus-visible:outline-gray-600'
                  : 'bg-white text-gray-900 ring-1 ring-gray-300 ring-inset hover:bg-gray-50'
              }`}
            >
              {batchMode ? '退出批量' : '批量操作'}
            </button>
            <button
              onClick={() => setShowQrScanner(true)}
              className="rounded-md bg-green-600 px-3 py-2 text-center text-sm font-semibold text-white shadow-sm hover:bg-green-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-green-600"
            >
              <QrCodeIcon className="mr-1 inline h-4 w-4" />
              扫码登录
            </button>
            <Link
              to="/device/new"
              className="block rounded-md bg-indigo-600 px-3 py-2 text-center text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
            >
              <PlusIcon className="mr-1 inline h-4 w-4" />
              添加设备
            </Link>
          </div>
        </div>
      </div>

      {/* 搜索和筛选 */}
      <div className="mt-8 flex flex-col gap-4 sm:flex-row">
        <form onSubmit={handleSearch} className="flex-1">
          <div className="relative">
            <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="block w-full rounded-md border-0 py-1.5 pr-3 pl-10 text-gray-900 ring-1 ring-gray-300 ring-inset placeholder:text-gray-400 focus:ring-2 focus:ring-indigo-600 focus:ring-inset sm:text-sm sm:leading-6"
              placeholder="搜索设备名称或设备编码..."
            />
          </div>
        </form>

        <div className="flex items-center gap-2">
          <FunnelIcon className="h-5 w-5 text-gray-400" />
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-md border-0 py-1.5 pr-8 pl-3 text-gray-900 ring-1 ring-gray-300 ring-inset focus:ring-2 focus:ring-indigo-600 focus:ring-inset sm:text-sm sm:leading-6"
          >
            <option value="all">全部状态</option>
            <option value="online">在线</option>
            <option value="offline">离线</option>
            <option value="inactive">未激活</option>
          </select>
        </div>
      </div>

      {/* 设备列表 */}
      <div className="mt-8 flow-root">
        <div className="-mx-4 -my-2 overflow-x-auto sm:-mx-6 lg:-mx-8">
          <div className="inline-block min-w-full py-2 align-middle sm:px-6 lg:px-8">
            <div className="ring-opacity-5 overflow-hidden shadow ring-1 ring-black md:rounded-lg">
              <table className="min-w-full divide-y divide-gray-300">
                <thead className="bg-gray-50">
                  <tr>
                    {batchMode && (
                      <th
                        scope="col"
                        className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                      >
                        <input
                          type="checkbox"
                          className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                          checked={devices.length > 0 && selectedDevices.length === devices.length}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedDevices(devices.map((device) => device.pid));
                            } else {
                              setSelectedDevices([]);
                            }
                          }}
                        />
                      </th>
                    )}
                    <th
                      scope="col"
                      className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                    >
                      设备信息
                    </th>
                    <th
                      scope="col"
                      className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                    >
                      设备类型
                    </th>
                    <th
                      scope="col"
                      className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                    >
                      状态
                    </th>
                    <th
                      scope="col"
                      className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                    >
                      所属门店
                    </th>
                    <th
                      scope="col"
                      className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                    >
                      最后在线时间
                    </th>
                    <th scope="col" className="relative px-6 py-3">
                      <span className="sr-only">操作</span>
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 bg-white">
                  {loading ? (
                    <tr>
                      <td
                        colSpan={batchMode ? 7 : 6}
                        className="px-6 py-4 text-center text-sm text-gray-500"
                      >
                        加载中...
                      </td>
                    </tr>
                  ) : devices.length === 0 ? (
                    <tr>
                      <td
                        colSpan={batchMode ? 7 : 6}
                        className="px-6 py-4 text-center text-sm text-gray-500"
                      >
                        暂无设备数据
                      </td>
                    </tr>
                  ) : (
                    devices.map((device) => (
                      <tr key={device.id} className="hover:bg-gray-50">
                        {batchMode && (
                          <td className="px-6 py-4 whitespace-nowrap">
                            <input
                              type="checkbox"
                              className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                              checked={selectedDevices.includes(device.pid)}
                              onChange={(e) => handleDeviceSelect(device.pid, e.target.checked)}
                            />
                          </td>
                        )}
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="flex items-center">
                            <div>
                              <div className="text-sm font-medium text-gray-900">
                                {device.deviceName}
                              </div>
                              <div className="text-sm text-gray-500">{device.deviceCode}</div>
                            </div>
                          </div>
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                          {device.deviceType}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          {getStatusBadge(device.status)}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                          {device.storeName || '未分配'}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {device.lastOnlineTime
                            ? new Date(device.lastOnlineTime).toLocaleString()
                            : '从未在线'}
                        </td>
                        <td className="px-6 py-4 text-right text-sm font-medium whitespace-nowrap">
                          <div className="flex space-x-2">
                            {!batchMode && (
                              <>
                                {device.storeName ? (
                                  <button
                                    onClick={() => handleUnbindDevice(device)}
                                    className="text-red-600 hover:text-red-900"
                                  >
                                    解绑
                                  </button>
                                ) : (
                                  <button
                                    onClick={() => handleBindDevice(device)}
                                    className="text-green-600 hover:text-green-900"
                                  >
                                    绑定门店
                                  </button>
                                )}
                              </>
                            )}
                            <Link
                              to={`/device/${device.pid}`}
                              className="text-indigo-600 hover:text-indigo-900"
                            >
                              查看详情
                            </Link>
                            <Link
                              to={`/device/${device.pid}/edit`}
                              className="text-indigo-600 hover:text-indigo-900"
                            >
                              编辑
                            </Link>
                          </div>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="mt-6 flex items-center justify-between">
          <div className="flex flex-1 justify-between sm:hidden">
            <button
              onClick={() => setCurrentPage(Math.max(1, currentPage - 1))}
              disabled={currentPage === 1}
              className="relative inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              上一页
            </button>
            <button
              onClick={() => setCurrentPage(Math.min(totalPages, currentPage + 1))}
              disabled={currentPage === totalPages}
              className="relative ml-3 inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
            >
              下一页
            </button>
          </div>
          <div className="hidden sm:flex sm:flex-1 sm:items-center sm:justify-between">
            <div>
              <p className="text-sm text-gray-700">
                第 <span className="font-medium">{currentPage}</span> 页，共{' '}
                <span className="font-medium">{totalPages}</span> 页
              </p>
            </div>
            <div>
              <nav
                className="isolate inline-flex -space-x-px rounded-md shadow-sm"
                aria-label="Pagination"
              >
                <button
                  onClick={() => setCurrentPage(Math.max(1, currentPage - 1))}
                  disabled={currentPage === 1}
                  className="relative inline-flex items-center rounded-l-md px-2 py-2 text-gray-400 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 focus:z-20 focus:outline-offset-0 disabled:opacity-50"
                >
                  上一页
                </button>
                <button
                  onClick={() => setCurrentPage(Math.min(totalPages, currentPage + 1))}
                  disabled={currentPage === totalPages}
                  className="relative inline-flex items-center rounded-r-md px-2 py-2 text-gray-400 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 focus:z-20 focus:outline-offset-0 disabled:opacity-50"
                >
                  下一页
                </button>
              </nav>
            </div>
          </div>
        </div>
      )}

      {/* 二维码扫描器 */}
      <QrCodeScanner
        isOpen={showQrScanner}
        onClose={() => setShowQrScanner(false)}
        onScan={handleQrCodeScan}
      />

      {/* 门店选择弹窗 */}
      {showStoreModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">
                {selectedDevice?.pid === 'batch' ? '批量绑定门店' : '选择门店'}
              </h3>
              <div className="max-h-60 overflow-y-auto">
                {stores.map((store) => (
                  <div
                    key={store.pid}
                    className="mb-2 cursor-pointer rounded-lg border p-3 hover:bg-gray-50"
                    onClick={() => {
                      if (selectedDevice?.pid === 'batch') {
                        handleBatchBind(store.pid);
                      } else {
                        handleStoreSelect(store.pid);
                      }
                    }}
                  >
                    <div className="font-medium text-gray-900">{store.name}</div>
                    <div className="text-sm text-gray-500">{store.code}</div>
                  </div>
                ))}
              </div>
              <div className="mt-4 flex justify-end space-x-3">
                <button
                  onClick={() => {
                    setShowStoreModal(false);
                    setSelectedDevice(null);
                  }}
                  className="rounded-md border border-gray-300 bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200"
                >
                  取消
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Success feedback handled by ToastContext */}
    </div>
  );
};

export default DeviceList;
