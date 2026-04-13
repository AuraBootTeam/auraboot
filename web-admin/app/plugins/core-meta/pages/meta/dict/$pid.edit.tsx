/**
 * Dictionary Edit Page
 *
 * Form for editing an existing dictionary
 */

import React, { useState, useCallback } from 'react';
import { useNavigate, useParams, useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { dictService } from '~/shared/services/dictService';
import { useToastContext } from '~/contexts/ToastContext';
import type { DictDTO, DictUpdateRequest } from '~/types/dict';

/**
 * Loader function
 */
export const loader = async ({ params, request }: LoaderFunctionArgs) => {
  const { pid } = params;

  if (!pid) {
    throw new Response('Dictionary PID is required', { status: 400 });
  }

  try {
    const dict = await dictService.findByPid(pid, request);
    return { dict };
  } catch (error) {
    console.error('Failed to load dictionary:', error);
    throw new Response('Dictionary not found', { status: 404 });
  }
};

export default function DictEditPage() {
  const navigate = useNavigate();
  const { pid } = useParams();
  const { dict: initialDict } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [loading, setLoading] = useState(false);
  const [formData, setFormData] = useState<DictUpdateRequest>({
    name: initialDict.name,
    description: initialDict.description || '',
  });

  /**
   * Handle form field change
   */
  const handleFieldChange = useCallback((field: keyof DictUpdateRequest, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Handle submit
   */
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      if (!formData.name) {
        showErrorToast('请填写字典名称');
        return;
      }

      setLoading(true);
      try {
        await dictService.update(pid!, formData);
        showSuccessToast('更新字典成功');
        navigate(`/meta/dict/${pid}`);
      } catch (error: any) {
        console.error('Failed to update dictionary:', error);
        showErrorToast(error.message || '更新字典失败');
      } finally {
        setLoading(false);
      }
    },
    [pid, formData, navigate, showSuccessToast, showErrorToast],
  );

  /**
   * Handle cancel
   */
  const handleCancel = useCallback(() => {
    navigate(`/meta/dict/${pid}`);
  }, [pid, navigate]);

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">编辑字典</h1>
        <p className="mt-1 text-sm text-gray-500">
          字典编码: <span className="font-mono text-blue-600">{initialDict.code}</span>
        </p>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="rounded-lg bg-white p-6 shadow">
        <div className="space-y-6">
          {/* Basic Info */}
          <div>
            <h2 className="mb-4 text-lg font-medium text-gray-900">基本信息</h2>
            <div className="grid grid-cols-1 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">字典编码</label>
                <input
                  type="text"
                  value={initialDict.code}
                  disabled
                  className="w-full cursor-not-allowed rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-500"
                />
                <p className="mt-1 text-xs text-gray-500">字典编码不可修改</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  字典名称 <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => handleFieldChange('name', e.target.value)}
                  placeholder="例如: 用户状态"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  required
                />
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">字典类型</label>
                <input
                  type="text"
                  value={initialDict.dictType}
                  disabled
                  className="w-full cursor-not-allowed rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-500"
                />
                <p className="mt-1 text-xs text-gray-500">字典类型不可修改</p>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
                <textarea
                  value={formData.description}
                  onChange={(e) => handleFieldChange('description', e.target.value)}
                  placeholder="字典的用途和说明"
                  rows={3}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
            </div>
          </div>

          {/* Note */}
          <div className="rounded-md border border-blue-200 bg-blue-50 p-4">
            <p className="text-sm text-blue-800">
              <strong>注意：</strong>
              字典项的管理请在字典详情页面进行。此页面仅用于修改字典的基本信息。
            </p>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 border-t pt-4">
            <button
              type="button"
              onClick={handleCancel}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
              disabled={loading}
            >
              取消
            </button>
            <button
              type="submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
              disabled={loading}
            >
              {loading ? '保存中...' : '保存'}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
