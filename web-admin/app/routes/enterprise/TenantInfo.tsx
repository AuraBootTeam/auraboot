import { useState, useEffect } from 'react';
import { Link } from 'react-router';
import {
  BuildingOfficeIcon,
  PencilIcon,
  GlobeAltIcon,
  PhoneIcon,
  EnvelopeIcon,
} from '@heroicons/react/24/outline';
import { fetchResult } from '~/services/http-client';
import { useTheme } from '~/contexts/ThemeContext';
import { useFormSubmit } from '~/hooks/useFormSubmit';
import { getIndustryLabel, type TenantInfo } from '~/hooks/useTenantForm';

export default function TenantInfo() {
  const [tenant, setTenant] = useState<TenantInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const { isDark } = useTheme();
  const { handleSubmitResult } = useFormSubmit();

  const fetchTenantInfo = async () => {
    setLoading(true);
    try {
      const result = await fetchResult<TenantInfo>('/api/tenant/info', {
        method: 'get',
      });

      handleSubmitResult(result, {
        showToast: false,
        onSuccess: (data: TenantInfo) => {
          setTenant(data);
        },
        onError: (error) => {
          console.error('获取租户信息失败:', error);
        },
      });
    } catch (error) {
      console.error('获取租户信息失败:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTenantInfo();
  }, []);

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'active':
        return 'text-green-600 bg-green-100';
      case 'inactive':
        return 'text-yellow-600 bg-yellow-100';
      case 'suspended':
        return 'text-red-600 bg-red-100';
      default:
        return 'text-gray-600 bg-gray-100';
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'active':
        return '正常';
      case 'inactive':
        return '停用';
      case 'suspended':
        return '暂停';
      default:
        return '未知';
    }
  };

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-blue-500 dark:border-blue-400"></div>
      </div>
    );
  }

  if (!tenant) {
    return (
      <div className="py-12 text-center">
        <p className="text-gray-500 dark:text-gray-400">未找到租户信息</p>
      </div>
    );
  }

  return (
    <div className="p-6">
      {/* 页面标题 */}
      <div className="mb-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center">
            <BuildingOfficeIcon className="mr-3 h-8 w-8 text-blue-600 dark:text-blue-400" />
            <div>
              <h1 className="text-2xl font-bold text-gray-900 dark:text-white">企业信息</h1>
              <p className="text-gray-600 dark:text-gray-300">管理您的企业基本信息</p>
            </div>
          </div>
          <Link
            to={`/enterprise/info/edit`}
            className="inline-flex items-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none dark:bg-blue-500 dark:hover:bg-blue-600 dark:focus:ring-offset-gray-800"
          >
            <PencilIcon className="mr-2 h-4 w-4" />
            编辑信息
          </Link>
        </div>
      </div>

      {/* 企业信息卡片 */}
      <div className="overflow-hidden rounded-lg bg-white shadow-lg dark:bg-gray-800">
        {/* 头部信息 */}
        <div className="bg-gradient-to-r from-blue-50 to-indigo-50 px-6 py-8 dark:from-gray-700 dark:to-gray-600">
          <div className="flex items-center">
            {tenant.logo && (
              <img
                src={tenant.logo}
                alt="企业Logo"
                className="mr-6 h-20 w-20 rounded-lg object-cover"
              />
            )}
            <div className="flex-1">
              <h2 className="mb-2 text-2xl font-bold text-gray-900 dark:text-white">
                {tenant.displayName || tenant.name}
              </h2>
              <div className="flex items-center space-x-4">
                <span
                  className={`inline-flex items-center rounded-full px-3 py-1 text-sm font-medium ${getStatusColor(
                    tenant.status,
                  )}`}
                >
                  {getStatusText(tenant.status)}
                </span>
                {tenant.industry && (
                  <span className="text-gray-600 dark:text-gray-300">
                    {getIndustryLabel(tenant.industry)}
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* 详细信息 */}
        <div className="px-6 py-6">
          <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
            {/* 基本信息 */}
            <div className="space-y-4">
              <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">基本信息</h3>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  企业名称
                </label>
                <p className="text-gray-900 dark:text-gray-100">{tenant.name}</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  显示名称
                </label>
                <p className="text-gray-900 dark:text-gray-100">{tenant.displayName || '-'}</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  所属行业
                </label>
                <p className="text-gray-900 dark:text-gray-100">
                  {getIndustryLabel(tenant.industry || '') || '-'}
                </p>
              </div>
            </div>

            {/* 联系信息 */}
            <div className="space-y-4">
              <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">联系信息</h3>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  <EnvelopeIcon className="mr-1 inline h-4 w-4" />
                  联系邮箱
                </label>
                <p className="text-gray-900 dark:text-gray-100">{tenant.contactEmail || '-'}</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  <PhoneIcon className="mr-1 inline h-4 w-4" />
                  联系电话
                </label>
                <p className="text-gray-900 dark:text-gray-100">{tenant.contactPhone || '-'}</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  <GlobeAltIcon className="mr-1 inline h-4 w-4" />
                  官方网站
                </label>
                <p className="text-gray-900 dark:text-gray-100">
                  {tenant.website ? (
                    <a
                      href={tenant.website}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-blue-600 hover:text-blue-800 dark:text-blue-400 dark:hover:text-blue-300"
                    >
                      {tenant.website}
                    </a>
                  ) : (
                    '-'
                  )}
                </p>
              </div>
            </div>
          </div>

          {/* 企业描述 */}
          {tenant.description && (
            <div className="mt-6">
              <h3 className="mb-4 text-lg font-semibold text-gray-900 dark:text-white">企业描述</h3>
              <p className="leading-relaxed text-gray-700 dark:text-gray-300">
                {tenant.description}
              </p>
            </div>
          )}

          {/* 时间信息 */}
          <div className="mt-6 border-t border-gray-200 pt-6 dark:border-gray-600">
            <div className="grid grid-cols-1 gap-4 text-sm text-gray-500 md:grid-cols-2 dark:text-gray-400">
              <div>
                <span className="font-medium">创建时间：</span>
                {new Date(tenant.createdAt).toLocaleString()}
              </div>
              <div>
                <span className="font-medium">更新时间：</span>
                {new Date(tenant.updatedAt).toLocaleString()}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
