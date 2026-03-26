import { useState, useEffect } from 'react';
import {
  PlusIcon,
  PencilIcon,
  TrashIcon,
  UserGroupIcon,
  ComputerDesktopIcon,
  MagnifyingGlassIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';

interface DeviceGroup {
  id: string;
  name: string;
  description?: string;
  deviceCount: number;
  createdAt: string;
  updatedAt: string;
}

interface Device {
  id: string;
  deviceName: string;
  deviceCode: string;
  status: 'online' | 'offline' | 'inactive';
  storeName: string;
  groupId?: string;
}

const DeviceGroup = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const [groups, setGroups] = useState<DeviceGroup[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [selectedGroup, setSelectedGroup] = useState<DeviceGroup | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [formData, setFormData] = useState({
    name: '',
    description: '',
  });
  const [selectedDevices, setSelectedDevices] = useState<string[]>([]);

  useEffect(() => {
    fetchGroups();
    fetchDevices();
  }, []);

  const fetchGroups = async () => {
    try {
      const response = await fetch('/api/device-groups');
      const data = await response.json();

      if (data.code === '0') {
        setGroups(data.data);
      } else {
        showErrorToast('获取分组列表失败');
      }
    } catch (error) {
      console.error('Error fetching groups:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const fetchDevices = async () => {
    try {
      setLoading(true);
      const response = await fetch('/api/device?pageSize=1000');
      const data = await response.json();

      if (data.code === '0') {
        setDevices(data.data.items);
      } else {
        showErrorToast('获取设备列表失败');
      }
    } catch (error) {
      console.error('Error fetching devices:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const handleCreateGroup = async () => {
    if (!formData.name.trim()) {
      showErrorToast('请输入分组名称');
      return;
    }

    try {
      const response = await fetch('/api/device-groups', {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(formData),
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast('分组创建成功');
        setShowCreateModal(false);
        setFormData({ name: '', description: '' });
        fetchGroups();
      } else {
        showErrorToast(data.message || '创建失败');
      }
    } catch (error) {
      console.error('Error creating group:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleEditGroup = async () => {
    if (!formData.name.trim() || !selectedGroup) {
      showErrorToast('请输入分组名称');
      return;
    }

    try {
      const response = await fetch(`/api/device-groups/${selectedGroup.id}`, {
        method: 'put',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(formData),
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast('分组更新成功');
        setShowEditModal(false);
        setSelectedGroup(null);
        setFormData({ name: '', description: '' });
        fetchGroups();
      } else {
        showErrorToast(data.message || '更新失败');
      }
    } catch (error) {
      console.error('Error updating group:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleDeleteGroup = async (group: DeviceGroup) => {
    if (!confirm(`确定要删除分组 "${group.name}" 吗？`)) {
      return;
    }

    try {
      const response = await fetch(`/api/device-groups/${group.id}`, {
        method: 'delete',
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast('分组删除成功');
        fetchGroups();
        fetchDevices();
      } else {
        showErrorToast(data.message || '删除失败');
      }
    } catch (error) {
      console.error('Error deleting group:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const handleAssignDevices = async () => {
    if (!selectedGroup || selectedDevices.length === 0) {
      showErrorToast('请选择要分配的设备');
      return;
    }

    try {
      const response = await fetch(`/api/device-groups/${selectedGroup.id}/devices`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ deviceIds: selectedDevices }),
      });

      const data = await response.json();

      if (data.code === '0') {
        showSuccessToast('设备分配成功');
        setShowAssignModal(false);
        setSelectedGroup(null);
        setSelectedDevices([]);
        fetchGroups();
        fetchDevices();
      } else {
        showErrorToast(data.message || '分配失败');
      }
    } catch (error) {
      console.error('Error assigning devices:', error);
      showErrorToast('网络错误，请稍后重试');
    }
  };

  const openEditModal = (group: DeviceGroup) => {
    setSelectedGroup(group);
    setFormData({
      name: group.name,
      description: group.description || '',
    });
    setShowEditModal(true);
  };

  const openAssignModal = (group: DeviceGroup) => {
    setSelectedGroup(group);
    setSelectedDevices([]);
    setShowAssignModal(true);
  };

  const filteredGroups = groups.filter(
    (group) =>
      group.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (group.description && group.description.toLowerCase().includes(searchTerm.toLowerCase())),
  );

  const availableDevices = devices.filter((device) => !device.groupId);

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

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 h-4 w-1/2 rounded bg-gray-200"></div>
          <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
            {[...Array(6)].map((_, i) => (
              <div key={i} className="h-32 rounded bg-gray-200"></div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面头部 */}
      <div className="mb-8 sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">设备分组管理</h1>
          <p className="mt-2 text-sm text-gray-700">管理设备分组，批量操作和配置设备</p>
        </div>
        <div className="mt-4 sm:mt-0">
          <button
            onClick={() => setShowCreateModal(true)}
            className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
          >
            <PlusIcon className="mr-2 h-4 w-4" />
            新建分组
          </button>
        </div>
      </div>

      {/* 搜索栏 */}
      <div className="mb-6">
        <div className="relative">
          <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
            <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" />
          </div>
          <input
            type="text"
            placeholder="搜索分组名称或描述..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="block w-full rounded-md border border-gray-300 bg-white py-2 pr-3 pl-10 leading-5 placeholder-gray-500 focus:border-indigo-500 focus:placeholder-gray-400 focus:ring-1 focus:ring-indigo-500 focus:outline-none"
          />
        </div>
      </div>

      {/* 分组列表 */}
      {filteredGroups.length === 0 ? (
        <div className="py-12 text-center">
          <UserGroupIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-medium text-gray-900">暂无分组</h3>
          <p className="mt-1 text-sm text-gray-500">
            {searchTerm ? '没有找到匹配的分组' : '开始创建第一个设备分组'}
          </p>
          {!searchTerm && (
            <div className="mt-6">
              <button
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
              >
                <PlusIcon className="mr-2 h-4 w-4" />
                新建分组
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
          {filteredGroups.map((group) => (
            <div key={group.id} className="overflow-hidden rounded-lg bg-white shadow">
              <div className="p-6">
                <div className="flex items-center justify-between">
                  <div className="flex items-center">
                    <UserGroupIcon className="h-8 w-8 text-indigo-600" />
                    <div className="ml-3">
                      <h3 className="text-lg font-medium text-gray-900">{group.name}</h3>
                      <p className="text-sm text-gray-500">{group.deviceCount} 台设备</p>
                    </div>
                  </div>
                  <div className="flex items-center space-x-2">
                    <button
                      onClick={() => openEditModal(group)}
                      className="p-2 text-gray-400 hover:text-gray-600"
                    >
                      <PencilIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDeleteGroup(group)}
                      className="p-2 text-gray-400 hover:text-red-600"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </div>
                </div>

                {group.description && (
                  <p className="mt-3 text-sm text-gray-600">{group.description}</p>
                )}

                <div className="mt-4 flex items-center justify-between">
                  <span className="text-xs text-gray-500">
                    创建于 {new Date(group.createdAt).toLocaleDateString()}
                  </span>
                  <button
                    onClick={() => openAssignModal(group)}
                    className="inline-flex items-center rounded border border-transparent bg-indigo-100 px-3 py-1 text-xs font-medium text-indigo-700 hover:bg-indigo-200"
                  >
                    <ComputerDesktopIcon className="mr-1 h-3 w-3" />
                    分配设备
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 创建分组模态框 */}
      {showCreateModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">新建设备分组</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700">分组名称 *</label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入分组名称"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">分组描述</label>
                  <textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    rows={3}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入分组描述（可选）"
                  />
                </div>
              </div>
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  onClick={() => {
                    setShowCreateModal(false);
                    setFormData({ name: '', description: '' });
                  }}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={handleCreateGroup}
                  className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
                >
                  创建
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 编辑分组模态框 */}
      {showEditModal && selectedGroup && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">编辑设备分组</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700">分组名称 *</label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入分组名称"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700">分组描述</label>
                  <textarea
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                    rows={3}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="请输入分组描述（可选）"
                  />
                </div>
              </div>
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  onClick={() => {
                    setShowEditModal(false);
                    setSelectedGroup(null);
                    setFormData({ name: '', description: '' });
                  }}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={handleEditGroup}
                  className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
                >
                  保存
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 分配设备模态框 */}
      {showAssignModal && selectedGroup && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-10 mx-auto w-2/3 max-w-4xl rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">
                为分组 "{selectedGroup.name}" 分配设备
              </h3>

              {availableDevices.length === 0 ? (
                <div className="py-8 text-center">
                  <ComputerDesktopIcon className="mx-auto h-12 w-12 text-gray-400" />
                  <h3 className="mt-2 text-sm font-medium text-gray-900">暂无可分配设备</h3>
                  <p className="mt-1 text-sm text-gray-500">所有设备都已分配到其他分组</p>
                </div>
              ) : (
                <div className="max-h-96 overflow-y-auto">
                  <div className="space-y-2">
                    {availableDevices.map((device) => (
                      <div
                        key={device.id}
                        className="flex items-center rounded-lg border p-3 hover:bg-gray-50"
                      >
                        <input
                          type="checkbox"
                          checked={selectedDevices.includes(device.id)}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedDevices([...selectedDevices, device.id]);
                            } else {
                              setSelectedDevices(selectedDevices.filter((id) => id !== device.id));
                            }
                          }}
                          className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                        />
                        <div className="ml-3 flex-1">
                          <div className="flex items-center justify-between">
                            <div>
                              <p className="text-sm font-medium text-gray-900">
                                {device.deviceName}
                              </p>
                              <p className="text-sm text-gray-500">{device.deviceCode}</p>
                            </div>
                            <div className="flex items-center space-x-2">
                              {getStatusBadge(device.status)}
                              <span className="text-sm text-gray-500">{device.storeName}</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="mt-6 flex items-center justify-between">
                <span className="text-sm text-gray-500">
                  已选择 {selectedDevices.length} 台设备
                </span>
                <div className="flex space-x-3">
                  <button
                    onClick={() => {
                      setShowAssignModal(false);
                      setSelectedGroup(null);
                      setSelectedDevices([]);
                    }}
                    className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleAssignDevices}
                    disabled={selectedDevices.length === 0}
                    className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    分配设备
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DeviceGroup;
