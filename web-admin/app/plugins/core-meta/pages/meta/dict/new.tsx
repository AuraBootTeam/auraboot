/**
 * Dictionary Create Page
 *
 * Form for creating a new dictionary
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { dictService } from '~/services/dictService';
import { useToastContext } from '~/contexts/ToastContext';
import type { DictCreateRequest, DictItemData } from '~/types/dict';

export default function DictCreatePage() {
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [loading, setLoading] = useState(false);
  const [formData, setFormData] = useState<DictCreateRequest>({
    code: '',
    name: '',
    dictType: 'simple',
    sourceType: 'static',
    description: '',
    items: [],
  });

  const [items, setItems] = useState<DictItemData[]>([]);
  const [newItem, setNewItem] = useState<DictItemData>({
    value: '',
    label: '',
    order: 0,
  });

  /**
   * Handle form field change
   */
  const handleFieldChange = useCallback((field: keyof DictCreateRequest, value: any) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  }, []);

  /**
   * Handle add item
   */
  const handleAddItem = useCallback(() => {
    if (!newItem.value || !newItem.label) {
      showErrorToast('请填写字典项的值和标签');
      return;
    }

    setItems((prev) => [...prev, { ...newItem, order: prev.length }]);
    setNewItem({ value: '', label: '', order: 0 });
  }, [newItem, showErrorToast]);

  /**
   * Handle remove item
   */
  const handleRemoveItem = useCallback((index: number) => {
    setItems((prev) => prev.filter((_, i) => i !== index));
  }, []);

  /**
   * Handle submit
   */
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      if (!formData.code || !formData.name) {
        showErrorToast('请填写必填字段');
        return;
      }

      setLoading(true);
      try {
        const request: DictCreateRequest = {
          ...formData,
          // 将前端的 items 转换为后端期望的格式
          items:
            items.length > 0
              ? items.map((item) => ({
                  value: item.value,
                  label: item.label,
                  sortOrder: item.order || 0,
                  parentValue: item.parentValue,
                  disabled: item.disabled || false,
                  extension: item.extension,
                }))
              : undefined,
        };

        const result = await dictService.create(request);
        showSuccessToast('创建字典成功');
        navigate(`/meta/dict/${result.pid}`);
      } catch (error: any) {
        console.error('Failed to create dictionary:', error);
        showErrorToast(error.message || '创建字典失败');
      } finally {
        setLoading(false);
      }
    },
    [formData, items, navigate, showSuccessToast, showErrorToast],
  );

  /**
   * Handle cancel
   */
  const handleCancel = useCallback(() => {
    navigate('/meta/dict');
  }, [navigate]);

  return (
    <div className="mx-auto max-w-4xl p-6">
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">创建字典</h1>
        <p className="mt-1 text-sm text-gray-500">创建新的字典定义</p>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit} className="rounded-lg bg-white p-6 shadow">
        <div className="space-y-6">
          {/* Basic Info */}
          <div>
            <h2 className="mb-4 text-lg font-medium text-gray-900">基本信息</h2>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  字典编码 <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={formData.code}
                  onChange={(e) => handleFieldChange('code', e.target.value)}
                  placeholder="例如: user_status"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  required
                />
                <p className="mt-1 text-xs text-gray-500">使用小写字母、数字和下划线</p>
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
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  字典类型 <span className="text-red-500">*</span>
                </label>
                <select
                  value={formData.dictType}
                  onChange={(e) => handleFieldChange('dictType', e.target.value as any)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  required
                >
                  <option value="simple">简单字典</option>
                  <option value="tree">树形字典</option>
                </select>
                <p className="mt-1 text-xs text-gray-500">
                  {formData.dictType === 'simple' && '键值对形式的简单字典'}
                  {formData.dictType === 'tree' && '支持父子关系的树形结构'}
                </p>
              </div>

              <div className="md:col-span-2">
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

          {/* Dictionary Items */}
          <div>
            <h2 className="mb-4 text-lg font-medium text-gray-900">字典项</h2>

            {/* Add Item Form */}
            <div className="mb-4 rounded-md bg-gray-50 p-4">
              <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">值</label>
                  <input
                    type="text"
                    value={newItem.value}
                    onChange={(e) => setNewItem((prev) => ({ ...prev, value: e.target.value }))}
                    placeholder="例如: active"
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">标签</label>
                  <input
                    type="text"
                    value={newItem.label}
                    onChange={(e) => setNewItem((prev) => ({ ...prev, label: e.target.value }))}
                    placeholder="例如: 激活"
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>
                <div className="flex items-end">
                  <button
                    type="button"
                    onClick={handleAddItem}
                    className="w-full rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
                  >
                    添加
                  </button>
                </div>
              </div>
            </div>

            {/* Items List */}
            {items.length > 0 ? (
              <div className="overflow-hidden rounded-md border border-gray-200">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                        值
                      </th>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                        标签
                      </th>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                        顺序
                      </th>
                      <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">
                        操作
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {items.map((item, index) => (
                      <tr key={index}>
                        <td className="px-4 py-3 font-mono text-sm text-gray-900">{item.value}</td>
                        <td className="px-4 py-3 text-sm text-gray-900">{item.label}</td>
                        <td className="px-4 py-3 text-sm text-gray-500">{index}</td>
                        <td className="px-4 py-3 text-sm">
                          <button
                            type="button"
                            onClick={() => handleRemoveItem(index)}
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
            ) : (
              <div className="rounded-md border border-gray-200 py-8 text-center text-gray-500">
                暂无字典项，请添加
              </div>
            )}
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
              {loading ? '创建中...' : '创建字典'}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
