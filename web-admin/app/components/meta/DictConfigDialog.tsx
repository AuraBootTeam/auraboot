/**
 * Dictionary Configuration Dialog
 * Configure dictionary binding for model fields
 * Supports selecting existing dictionaries or creating new ones
 */

import React, { useState, useEffect, useCallback } from 'react';
import type { ModelFieldBinding } from '~/types/model';
import type { DictDTO } from '~/types/dict';
import { dictService } from '~/services/dictService';
import { useToastContext } from '~/contexts/ToastContext';
import { confirmDialog } from '~/utils/confirmDialog';

interface DictConfigDialogProps {
  field: ModelFieldBinding;
  modelPid: string;
  onSave: (dictCode: string | null) => Promise<void>;
  onClose: () => void;
}

type TabMode = 'select' | 'create';

interface DictFormData {
  code: string;
  name: string;
  dictType: 'simple' | 'tree' | 'cascade';
  remark?: string;
  items: DictItemFormData[];
}

interface DictItemFormData {
  value: string;
  label: string;
  sortOrder: number;
}

export function DictConfigDialog({
  field,
  modelPid: _modelPid,
  onSave,
  onClose,
}: DictConfigDialogProps) {
  const { showSuccessToast, showErrorToast } = useToastContext();

  // Tab state
  const [activeTab, setActiveTab] = useState<TabMode>('select');

  // Select existing dictionary state
  const [dicts, setDicts] = useState<DictDTO[]>([]);
  const [selectedDict, setSelectedDict] = useState<DictDTO | null>(null);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [loading, setLoading] = useState(false);

  // Create new dictionary state
  const [dictForm, setDictForm] = useState<DictFormData>({
    code: `dict_${field.code || field.fieldCode}`,
    name: `${field.fieldName || field.code || field.fieldCode}字典`,
    dictType: 'simple',
    remark: '',
    items: [{ value: '', label: '', sortOrder: 1 }],
  });
  const [creating, setCreating] = useState(false);

  // Saving state
  const [saving, setSaving] = useState(false);

  // Load dictionaries on mount
  useEffect(() => {
    if (activeTab === 'select') {
      loadDictionaries();
    }
  }, [activeTab]);

  // Debounced search
  useEffect(() => {
    if (activeTab === 'select') {
      const timer = setTimeout(() => {
        loadDictionaries();
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [searchKeyword, activeTab]);

  const loadDictionaries = async () => {
    setLoading(true);
    try {
      const result = await dictService.query({
        pageNum: 1,
        pageSize: 50,
        name: searchKeyword || undefined,
        status: 'published',
      });
      setDicts(result.records || []);

      // Pre-select current bound dictionary
      if (field.dictCode) {
        const current = result.records?.find((d) => d.code === field.dictCode);
        if (current) {
          setSelectedDict(current);
        }
      }
    } catch (error) {
      console.error('Failed to load dictionaries:', error);
      showErrorToast('加载字典列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDictSelect = useCallback((dict: DictDTO) => {
    setSelectedDict(dict);
  }, []);

  const handleAddItem = () => {
    setDictForm((prev) => ({
      ...prev,
      items: [...prev.items, { value: '', label: '', sortOrder: prev.items.length + 1 }],
    }));
  };

  const handleRemoveItem = (index: number) => {
    setDictForm((prev) => ({
      ...prev,
      items: prev.items.filter((_, i) => i !== index),
    }));
  };

  const handleItemChange = (
    index: number,
    field: keyof DictItemFormData,
    value: string | number,
  ) => {
    setDictForm((prev) => ({
      ...prev,
      items: prev.items.map((item, i) => (i === index ? { ...item, [field]: value } : item)),
    }));
  };

  const validateDictForm = (): boolean => {
    if (!dictForm.code.trim()) {
      showErrorToast('请输入字典编码');
      return false;
    }
    if (!dictForm.name.trim()) {
      showErrorToast('请输入字典名称');
      return false;
    }
    if (dictForm.items.length === 0) {
      showErrorToast('请至少添加一个字典项');
      return false;
    }
    for (let i = 0; i < dictForm.items.length; i++) {
      const item = dictForm.items[i];
      if (!item.value.trim() || !item.label.trim()) {
        showErrorToast(`请完善第 ${i + 1} 个字典项的值和标签`);
        return false;
      }
    }
    return true;
  };

  const handleCreateDict = async () => {
    if (!validateDictForm()) return;

    setCreating(true);
    try {
      // Create dictionary
      const newDict = await dictService.create({
        code: dictForm.code,
        name: dictForm.name,
        dictType: dictForm.dictType,
        description: dictForm.remark,
        sourceType: 'static', // Static dictionary with items
        items: dictForm.items.map((item) => ({
          value: item.value,
          label: item.label,
          sortOrder: item.sortOrder,
        })),
      });

      // Publish dictionary
      await dictService.publish(newDict.pid);

      showSuccessToast('字典创建成功');

      // Bind the new dictionary
      await onSave(newDict.code);
      onClose();
    } catch (error: any) {
      console.error('Failed to create dictionary:', error);
      showErrorToast(error.message || '创建字典失败');
    } finally {
      setCreating(false);
    }
  };

  const handleBindDict = async () => {
    if (!selectedDict) {
      showErrorToast('请选择一个字典');
      return;
    }

    setSaving(true);
    try {
      await onSave(selectedDict.code);
      onClose();
    } catch (error: any) {
      console.error('Failed to bind dictionary:', error);
      showErrorToast(error.message || '绑定字典失败');
    } finally {
      setSaving(false);
    }
  };

  const handleUnbindDict = async () => {
    if (!field.dictCode) {
      showErrorToast('当前字段未绑定字典');
      return;
    }

    const confirmed = await confirmDialog({ content: '确定要解绑当前字典吗？' });
    if (!confirmed) return;

    setSaving(true);
    try {
      await onSave(null);
      onClose();
    } catch (error: any) {
      console.error('Failed to unbind dictionary:', error);
      showErrorToast(error.message || '解绑字典失败');
    } finally {
      setSaving(false);
    }
  };

  const handleClose = () => {
    if (!saving && !creating) {
      onClose();
    }
  };

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* Overlay */}
      <div
        className="bg-opacity-50 fixed inset-0 bg-black transition-opacity"
        onClick={handleClose}
      />

      {/* Dialog */}
      <div className="flex min-h-full items-center justify-center p-4">
        <div
          className="relative flex max-h-[90vh] w-full max-w-4xl flex-col rounded-lg bg-white shadow-xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-b border-gray-200 px-6 py-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-xl font-semibold text-gray-900">配置字段字典</h2>
                <p className="mt-1 text-sm text-gray-500">
                  字段:{' '}
                  <span className="font-mono text-blue-600">{field.code || field.fieldCode}</span>
                  {field.dictCode && (
                    <span className="ml-2">
                      · 当前绑定: <span className="font-mono text-green-600">{field.dictCode}</span>
                    </span>
                  )}
                </p>
              </div>
              <button
                onClick={handleClose}
                disabled={saving || creating}
                className="text-gray-400 hover:text-gray-600 disabled:opacity-50"
              >
                <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>

            {/* Tabs */}
            <div className="mt-4 flex border-b border-gray-200">
              <button
                onClick={() => setActiveTab('select')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'select'
                    ? 'border-blue-500 text-blue-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700'
                }`}
              >
                选择已有字典
              </button>
              <button
                onClick={() => setActiveTab('create')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'create'
                    ? 'border-blue-500 text-blue-600'
                    : 'border-transparent text-gray-500 hover:text-gray-700'
                }`}
              >
                创建新字典
              </button>
            </div>
          </div>

          {/* Content */}
          <div className="flex-1 overflow-y-auto p-6">
            {activeTab === 'select' ? (
              <div className="space-y-4">
                {/* Search */}
                <div>
                  <input
                    type="text"
                    value={searchKeyword}
                    onChange={(e) => setSearchKeyword(e.target.value)}
                    placeholder="搜索字典名称..."
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>

                {/* Dictionary List */}
                {loading ? (
                  <div className="flex items-center justify-center py-12">
                    <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
                  </div>
                ) : dicts.length === 0 ? (
                  <div className="py-12 text-center text-gray-500">
                    <p>未找到字典</p>
                    <p className="mt-2 text-sm">请尝试创建新字典</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {dicts.map((dict) => (
                      <div
                        key={dict.pid}
                        onClick={() => handleDictSelect(dict)}
                        className={`cursor-pointer rounded-md border p-4 transition-colors ${
                          selectedDict?.pid === dict.pid
                            ? 'border-blue-200 bg-blue-50'
                            : 'border-gray-200 hover:bg-gray-50'
                        }`}
                      >
                        <div className="flex items-start justify-between">
                          <div className="flex-1">
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-medium text-gray-900">{dict.name}</span>
                              <span className="font-mono text-xs text-gray-500">({dict.code})</span>
                              <span className="inline-flex rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-800">
                                {dict.dictType}
                              </span>
                              {dict.status === 'published' && (
                                <span className="inline-flex rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
                                  已发布
                                </span>
                              )}
                            </div>
                            {dict.remark && (
                              <p className="mt-1 text-xs text-gray-500">{dict.remark}</p>
                            )}
                          </div>
                          {selectedDict?.pid === dict.pid && (
                            <svg
                              className="h-5 w-5 text-blue-600"
                              fill="currentColor"
                              viewBox="0 0 20 20"
                            >
                              <path
                                fillRule="evenodd"
                                d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                                clipRule="evenodd"
                              />
                            </svg>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div className="space-y-4">
                {/* Dictionary Basic Info */}
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      字典编码 <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={dictForm.code}
                      onChange={(e) => setDictForm({ ...dictForm, code: e.target.value })}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      placeholder="dict_field_name"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">
                      字典名称 <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={dictForm.name}
                      onChange={(e) => setDictForm({ ...dictForm, name: e.target.value })}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                      placeholder="字段名称字典"
                    />
                  </div>
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">字典类型</label>
                  <select
                    value={dictForm.dictType}
                    onChange={(e) => setDictForm({ ...dictForm, dictType: e.target.value as any })}
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  >
                    <option value="simple">简单字典</option>
                    <option value="tree">树形字典</option>
                    <option value="cascade">级联字典</option>
                  </select>
                </div>

                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">备注</label>
                  <textarea
                    value={dictForm.remark}
                    onChange={(e) => setDictForm({ ...dictForm, remark: e.target.value })}
                    rows={2}
                    className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    placeholder="字典说明..."
                  />
                </div>

                {/* Dictionary Items */}
                <div>
                  <div className="mb-2 flex items-center justify-between">
                    <label className="block text-sm font-medium text-gray-700">
                      字典项 <span className="text-red-500">*</span>
                    </label>
                    <button
                      onClick={handleAddItem}
                      className="text-sm text-blue-600 hover:text-blue-800"
                    >
                      + 添加项
                    </button>
                  </div>
                  <div className="space-y-2">
                    {dictForm.items.map((item, index) => (
                      <div key={index} className="flex gap-2">
                        <input
                          type="text"
                          value={item.value}
                          onChange={(e) => handleItemChange(index, 'value', e.target.value)}
                          placeholder="值"
                          className="flex-1 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                        <input
                          type="text"
                          value={item.label}
                          onChange={(e) => handleItemChange(index, 'label', e.target.value)}
                          placeholder="标签"
                          className="flex-1 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                        <input
                          type="number"
                          value={item.sortOrder}
                          onChange={(e) =>
                            handleItemChange(index, 'sortOrder', parseInt(e.target.value) || 0)
                          }
                          placeholder="排序"
                          className="w-20 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                        <button
                          onClick={() => handleRemoveItem(index)}
                          className="px-3 py-2 text-red-600 hover:text-red-800"
                        >
                          <svg
                            className="h-5 w-5"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                            />
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="flex justify-between border-t border-gray-200 px-6 py-4">
            <div>
              {field.dictCode && activeTab === 'select' && (
                <button
                  onClick={handleUnbindDict}
                  disabled={saving || creating}
                  className="rounded-md border border-red-300 px-4 py-2 text-red-700 hover:bg-red-50 disabled:opacity-50"
                >
                  解绑字典
                </button>
              )}
            </div>
            <div className="flex gap-3">
              <button
                onClick={handleClose}
                disabled={saving || creating}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                取消
              </button>
              {activeTab === 'select' ? (
                <button
                  onClick={handleBindDict}
                  disabled={saving || !selectedDict}
                  className="flex items-center rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {saving && (
                    <div className="mr-2 h-4 w-4 animate-spin rounded-full border-b-2 border-white"></div>
                  )}
                  {field.dictCode ? '更换字典' : '绑定字典'}
                </button>
              ) : (
                <button
                  onClick={handleCreateDict}
                  disabled={creating}
                  className="flex items-center rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {creating && (
                    <div className="mr-2 h-4 w-4 animate-spin rounded-full border-b-2 border-white"></div>
                  )}
                  创建并绑定
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
