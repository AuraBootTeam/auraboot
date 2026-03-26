import { useState, useEffect } from 'react';
import { useLoaderData, useNavigate, Form, useSubmit } from 'react-router';
import { useToastContext } from '~/contexts/ToastContext';
import type { LoaderFunctionArgs, ActionFunctionArgs } from 'react-router';
import {
  getStoreList,
  createStore,
  updateStore,
  deleteStore,
  batchDeleteStores,
  checkStoreCodeUnique,
  type Store,
  type StoreCreateRequest,
  type StoreUpdateRequest,
  type PaginationResult,
} from '~/services/store';

// Loader函数 - 获取Store列表数据
export const loader = async ({ request }: LoaderFunctionArgs) => {
  const url = new URL(request.url);
  const pageNum = parseInt(url.searchParams.get('pageNum') || '1');
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10');
  const keyword = url.searchParams.get('keyword') || '';

  try {
    const storeList = await getStoreList(request, { pageNum, pageSize, keyword });
    return { storeList, pageNum, pageSize, keyword };
  } catch (error) {
    console.error('获取Store列表失败:', error);
    return {
      storeList: { records: [], total: 0, totalPages: 0, pageNum: 1, pageSize: 10 },
      pageNum,
      pageSize,
      keyword,
    };
  }
};

// Action函数 - 处理表单提交
export const action = async ({ request }: ActionFunctionArgs) => {
  const formData = await request.formData();
  const actionType = formData.get('actionType') as string;

  try {
    switch (actionType) {
      case 'create': {
        const storeData: StoreCreateRequest = {
          name: formData.get('name') as string,
          code: formData.get('code') as string,
          type: formData.get('type') as string,
          addressId: (formData.get('addressId') as string) || undefined,
          status: formData.get('status') as string,
          openDate: (formData.get('openDate') as string) || undefined,
          closeDate: (formData.get('closeDate') as string) || undefined,
        };
        await createStore(request, storeData);
        return { success: true, message: 'Store创建成功' };
      }

      case 'update': {
        const pid = formData.get('pid') as string;
        const storeData: StoreUpdateRequest = {
          name: formData.get('name') as string,
          code: formData.get('code') as string,
          type: formData.get('type') as string,
          addressId: (formData.get('addressId') as string) || undefined,
          status: formData.get('status') as string,
          openDate: (formData.get('openDate') as string) || undefined,
          closeDate: (formData.get('closeDate') as string) || undefined,
        };
        await updateStore(request, pid, storeData);
        return { success: true, message: 'Store更新成功' };
      }

      case 'delete': {
        const pid = formData.get('pid') as string;
        await deleteStore(request, pid);
        return { success: true, message: 'Store删除成功' };
      }

      case 'batchDelete': {
        const pids = formData.get('pids') as string;
        await batchDeleteStores(request, pids.split(','));
        return { success: true, message: '批量删除成功' };
      }

      default:
        return { success: false, message: '未知操作' };
    }
  } catch (error) {
    console.error('操作失败:', error);
    return { success: false, message: error instanceof Error ? error.message : '操作失败' };
  }
};

// Store管理页面组件
export default function StoreManagement() {
  const { showWarningToast } = useToastContext();
  const loaderData = useLoaderData<typeof loader>();
  const storeList = loaderData?.storeList ?? {
    records: [],
    total: 0,
    totalPages: 0,
    pageNum: 1,
    pageSize: 10,
  };
  const pageNum = loaderData?.pageNum ?? 1;
  const pageSize = loaderData?.pageSize ?? 10;
  const keyword = loaderData?.keyword ?? '';
  const navigate = useNavigate();
  const submit = useSubmit();

  const [selectedStores, setSelectedStores] = useState<string[]>([]);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [editingStore, setEditingStore] = useState<Store | null>(null);
  const [searchKeyword, setSearchKeyword] = useState(keyword);

  // 处理搜索
  const handleSearch = () => {
    const params = new URLSearchParams();
    params.set('pageNum', '1');
    params.set('pageSize', pageSize.toString());
    if (searchKeyword) {
      params.set('keyword', searchKeyword);
    }
    navigate(`?${params.toString()}`);
  };

  // 处理分页
  const handlePageChange = (newPage: number) => {
    const params = new URLSearchParams();
    params.set('pageNum', newPage.toString());
    params.set('pageSize', pageSize.toString());
    if (keyword) {
      params.set('keyword', keyword);
    }
    navigate(`?${params.toString()}`);
  };

  // 处理选择
  const handleSelectStore = (pid: string, checked: boolean) => {
    if (checked) {
      setSelectedStores([...selectedStores, pid]);
    } else {
      setSelectedStores(selectedStores.filter((id) => id !== pid));
    }
  };

  // 处理全选
  const handleSelectAll = (checked: boolean) => {
    if (checked) {
      setSelectedStores(storeList.records.map((store) => store.pid));
    } else {
      setSelectedStores([]);
    }
  };

  // 处理删除
  const handleDelete = (pid: string) => {
    if (confirm('确定要删除这个Store吗？')) {
      const formData = new FormData();
      formData.append('actionType', 'delete');
      formData.append('pid', pid);
      submit(formData, { method: 'post' });
    }
  };

  // 处理批量删除
  const handleBatchDelete = () => {
    if (selectedStores.length === 0) {
      showWarningToast('请选择要删除的Store');
      return;
    }
    if (confirm(`确定要删除选中的 ${selectedStores.length} 个Store吗？`)) {
      const formData = new FormData();
      formData.append('actionType', 'batchDelete');
      formData.append('pids', selectedStores.join(','));
      submit(formData, { method: 'post' });
    }
  };

  // 处理编辑
  const handleEdit = (store: Store) => {
    setEditingStore(store);
    setShowEditModal(true);
  };

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="mb-4 text-2xl font-bold text-gray-900">Store管理</h1>

        {/* 搜索和操作栏 */}
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <input
              type="text"
              placeholder="搜索Store..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
            />
            <button
              onClick={handleSearch}
              className="rounded-md bg-blue-500 px-4 py-2 text-white hover:bg-blue-600"
            >
              搜索
            </button>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={() => setShowCreateModal(true)}
              className="rounded-md bg-green-500 px-4 py-2 text-white hover:bg-green-600"
            >
              新建Store
            </button>
            {selectedStores.length > 0 && (
              <button
                onClick={handleBatchDelete}
                className="rounded-md bg-red-500 px-4 py-2 text-white hover:bg-red-600"
              >
                批量删除 ({selectedStores.length})
              </button>
            )}
          </div>
        </div>

        {/* Store列表表格 */}
        <div className="overflow-hidden rounded-lg bg-white shadow">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  <input
                    type="checkbox"
                    checked={
                      selectedStores.length === storeList.records.length &&
                      storeList.records.length > 0
                    }
                    onChange={(e) => handleSelectAll(e.target.checked)}
                    className="rounded border-gray-300"
                  />
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  Store名称
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  Store代码
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  类型
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  状态
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  创建时间
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  操作
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {storeList.records.map((store) => (
                <tr key={store.pid} className="hover:bg-gray-50">
                  <td className="px-6 py-4 whitespace-nowrap">
                    <input
                      type="checkbox"
                      checked={selectedStores.includes(store.pid)}
                      onChange={(e) => handleSelectStore(store.pid, e.target.checked)}
                      className="rounded border-gray-300"
                    />
                  </td>
                  <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                    {store.name}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {store.code}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {store.type}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${
                        store.status === 'active'
                          ? 'bg-green-100 text-green-800'
                          : 'bg-red-100 text-red-800'
                      }`}
                    >
                      {store.status}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {new Date(store.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-6 py-4 text-sm font-medium whitespace-nowrap">
                    <button
                      onClick={() => handleEdit(store)}
                      className="mr-4 text-indigo-600 hover:text-indigo-900"
                    >
                      编辑
                    </button>
                    <button
                      onClick={() => handleDelete(store.pid)}
                      className="text-red-600 hover:text-red-900"
                    >
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* 分页 */}
        <div className="mt-4 flex items-center justify-between">
          <div className="text-sm text-gray-700">
            显示 {(pageNum - 1) * pageSize + 1} 到 {Math.min(pageNum * pageSize, storeList.total)}{' '}
            条， 共 {storeList.total} 条记录
          </div>
          <div className="flex items-center space-x-2">
            <button
              onClick={() => handlePageChange(pageNum - 1)}
              disabled={pageNum <= 1}
              className="rounded-md border border-gray-300 px-3 py-1 disabled:cursor-not-allowed disabled:opacity-50"
            >
              上一页
            </button>
            <span className="px-3 py-1">
              第 {pageNum} 页，共 {storeList.totalPages} 页
            </span>
            <button
              onClick={() => handlePageChange(pageNum + 1)}
              disabled={pageNum >= storeList.totalPages}
              className="rounded-md border border-gray-300 px-3 py-1 disabled:cursor-not-allowed disabled:opacity-50"
            >
              下一页
            </button>
          </div>
        </div>
      </div>

      {/* 创建Store模态框 */}
      {showCreateModal && (
        <StoreModal
          title="创建Store"
          onClose={() => setShowCreateModal(false)}
          onSubmit={(data) => {
            const formData = new FormData();
            formData.append('actionType', 'create');
            Object.entries(data).forEach(([key, value]) => {
              if (value !== undefined && value !== null) {
                formData.append(key, typeof value === 'string' ? value : String(value));
              }
            });
            submit(formData, { method: 'post' });
            setShowCreateModal(false);
          }}
        />
      )}

      {/* 编辑Store模态框 */}
      {showEditModal && editingStore && (
        <StoreModal
          title="编辑Store"
          store={editingStore}
          onClose={() => {
            setShowEditModal(false);
            setEditingStore(null);
          }}
          onSubmit={(data) => {
            const formData = new FormData();
            formData.append('actionType', 'update');
            formData.append('pid', editingStore.pid);
            Object.entries(data).forEach(([key, value]) => {
              if (value !== undefined && value !== null) {
                formData.append(key, typeof value === 'string' ? value : String(value));
              }
            });
            submit(formData, { method: 'post' });
            setShowEditModal(false);
            setEditingStore(null);
          }}
        />
      )}
    </div>
  );
}

// Store表单模态框组件
interface StoreModalProps {
  title: string;
  store?: Store;
  onClose: () => void;
  onSubmit: (data: any) => void;
}

function StoreModal({ title, store, onClose, onSubmit }: StoreModalProps) {
  const [formData, setFormData] = useState({
    name: store?.name || '',
    code: store?.code || '',
    type: store?.type || 'retail',
    addressId: store?.addressId || '',
    status: store?.status || 'active',
    openDate: store?.openDate || '',
    closeDate: store?.closeDate || '',
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(formData);
  };

  const handleChange = (field: string, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
      <div className="w-full max-w-md rounded-lg bg-white p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-xl font-bold">{title}</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            ✕
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Store名称 *</label>
            <input
              type="text"
              required
              value={formData.name}
              onChange={(e) => handleChange('name', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Store代码 *</label>
            <input
              type="text"
              required
              value={formData.code}
              onChange={(e) => handleChange('code', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Store类型 *</label>
            <select
              required
              value={formData.type}
              onChange={(e) => handleChange('type', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="retail">零售店</option>
              <option value="wholesale">批发店</option>
              <option value="online">在线店</option>
              <option value="warehouse">仓库</option>
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">状态 *</label>
            <select
              required
              value={formData.status}
              onChange={(e) => handleChange('status', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="active">激活</option>
              <option value="inactive">停用</option>
              <option value="pending">待审核</option>
            </select>
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">地址ID</label>
            <input
              type="text"
              value={formData.addressId}
              onChange={(e) => handleChange('addressId', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">开业日期</label>
            <input
              type="date"
              value={formData.openDate}
              onChange={(e) => handleChange('openDate', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">关闭日期</label>
            <input
              type="date"
              value={formData.closeDate}
              onChange={(e) => handleChange('closeDate', e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          <div className="flex justify-end space-x-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            >
              取消
            </button>
            <button
              type="submit"
              className="rounded-md bg-blue-500 px-4 py-2 text-white hover:bg-blue-600"
            >
              {store ? '更新' : '创建'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
