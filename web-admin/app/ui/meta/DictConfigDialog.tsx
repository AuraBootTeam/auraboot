/**
 * Dictionary Configuration Dialog
 * Configure dictionary binding for model fields
 * Supports selecting existing dictionaries or creating new ones
 */

import React, { useState, useEffect, useCallback } from 'react';
import type { ModelFieldBinding } from '~/types/model';
import type { DictDTO } from '~/types/dict';
import { dictService } from '~/shared/services/dictService';
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
          className="rounded-card bg-panel relative flex max-h-[90vh] w-full max-w-4xl flex-col shadow-xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="border-border border-b px-6 py-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-text text-xl font-semibold">配置字段字典</h2>
                <p className="text-text-2 mt-1 text-sm">
                  字段:{' '}
                  <span className="text-accent font-mono">{field.code || field.fieldCode}</span>
                  {field.dictCode && (
                    <span className="ml-2">
                      · 当前绑定:{' '}
                      <span className="text-status-green font-mono">{field.dictCode}</span>
                    </span>
                  )}
                </p>
              </div>
              <button
                onClick={handleClose}
                disabled={saving || creating}
                className="text-text-3 hover:text-text-2 disabled:opacity-50"
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
            <div className="border-border mt-4 flex border-b">
              <button
                onClick={() => setActiveTab('select')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'select'
                    ? 'text-accent border-accent'
                    : 'text-text-2 hover:text-text-2 border-transparent'
                }`}
              >
                选择已有字典
              </button>
              <button
                onClick={() => setActiveTab('create')}
                className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                  activeTab === 'create'
                    ? 'text-accent border-accent'
                    : 'text-text-2 hover:text-text-2 border-transparent'
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
                    className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                  />
                </div>

                {/* Dictionary List */}
                {loading ? (
                  <div className="flex items-center justify-center py-12">
                    <div className="rounded-pill border-accent h-8 w-8 animate-spin border-b-2"></div>
                  </div>
                ) : dicts.length === 0 ? (
                  <div className="text-text-2 py-12 text-center">
                    <p>未找到字典</p>
                    <p className="mt-2 text-sm">请尝试创建新字典</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {dicts.map((dict) => (
                      <div
                        key={dict.pid}
                        onClick={() => handleDictSelect(dict)}
                        className={`rounded-control cursor-pointer border p-4 transition-colors ${
                          selectedDict?.pid === dict.pid
                            ? 'bg-accent-weak border-blue-200'
                            : 'border-border hover:bg-subtle'
                        }`}
                      >
                        <div className="flex items-start justify-between">
                          <div className="flex-1">
                            <div className="flex items-center gap-2">
                              <span className="text-text text-sm font-medium">{dict.name}</span>
                              <span className="text-text-2 font-mono text-xs">({dict.code})</span>
                              <span className="bg-subtle text-text inline-flex rounded px-2 py-0.5 text-xs font-medium">
                                {dict.dictType}
                              </span>
                              {dict.status === 'published' && (
                                <span className="inline-flex rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
                                  已发布
                                </span>
                              )}
                            </div>
                            {dict.remark && (
                              <p className="text-text-2 mt-1 text-xs">{dict.remark}</p>
                            )}
                          </div>
                          {selectedDict?.pid === dict.pid && (
                            <svg
                              className="text-accent h-5 w-5"
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
                    <label className="text-text-2 mb-1 block text-sm font-medium">
                      字典编码 <span className="text-status-red">*</span>
                    </label>
                    <input
                      type="text"
                      value={dictForm.code}
                      onChange={(e) => setDictForm({ ...dictForm, code: e.target.value })}
                      className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                      placeholder="dict_field_name"
                    />
                  </div>
                  <div>
                    <label className="text-text-2 mb-1 block text-sm font-medium">
                      字典名称 <span className="text-status-red">*</span>
                    </label>
                    <input
                      type="text"
                      value={dictForm.name}
                      onChange={(e) => setDictForm({ ...dictForm, name: e.target.value })}
                      className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                      placeholder="字段名称字典"
                    />
                  </div>
                </div>

                <div>
                  <label className="text-text-2 mb-1 block text-sm font-medium">字典类型</label>
                  <select
                    value={dictForm.dictType}
                    onChange={(e) => setDictForm({ ...dictForm, dictType: e.target.value as any })}
                    className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                  >
                    <option value="simple">简单字典</option>
                    <option value="tree">树形字典</option>
                    <option value="cascade">级联字典</option>
                  </select>
                </div>

                <div>
                  <label className="text-text-2 mb-1 block text-sm font-medium">备注</label>
                  <textarea
                    value={dictForm.remark}
                    onChange={(e) => setDictForm({ ...dictForm, remark: e.target.value })}
                    rows={2}
                    className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                    placeholder="字典说明..."
                  />
                </div>

                {/* Dictionary Items */}
                <div>
                  <div className="mb-2 flex items-center justify-between">
                    <label className="text-text-2 block text-sm font-medium">
                      字典项 <span className="text-status-red">*</span>
                    </label>
                    <button
                      onClick={handleAddItem}
                      className="text-accent text-sm hover:text-blue-800"
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
                          className="rounded-control border-border-strong focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
                        />
                        <input
                          type="text"
                          value={item.label}
                          onChange={(e) => handleItemChange(index, 'label', e.target.value)}
                          placeholder="标签"
                          className="rounded-control border-border-strong focus-visible:shadow-focus flex-1 border px-3 py-2 focus:outline-none"
                        />
                        <input
                          type="number"
                          value={item.sortOrder}
                          onChange={(e) =>
                            handleItemChange(index, 'sortOrder', parseInt(e.target.value) || 0)
                          }
                          placeholder="排序"
                          className="rounded-control border-border-strong focus-visible:shadow-focus w-20 border px-3 py-2 focus:outline-none"
                        />
                        <button
                          onClick={() => handleRemoveItem(index)}
                          className="text-status-red px-3 py-2 hover:text-red-800"
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
          <div className="border-border flex justify-between border-t px-6 py-4">
            <div>
              {field.dictCode && activeTab === 'select' && (
                <button
                  onClick={handleUnbindDict}
                  disabled={saving || creating}
                  className="rounded-control border-status-red hover:bg-status-red-bg border px-4 py-2 text-red-700 disabled:opacity-50"
                >
                  解绑字典
                </button>
              )}
            </div>
            <div className="flex gap-3">
              <button
                onClick={handleClose}
                disabled={saving || creating}
                className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2 disabled:opacity-50"
              >
                取消
              </button>
              {activeTab === 'select' ? (
                <button
                  onClick={handleBindDict}
                  disabled={saving || !selectedDict}
                  className="rounded-control bg-accent hover:bg-accent-hover flex items-center px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {saving && (
                    <div className="rounded-pill mr-2 h-4 w-4 animate-spin border-b-2 border-white"></div>
                  )}
                  {field.dictCode ? '更换字典' : '绑定字典'}
                </button>
              ) : (
                <button
                  onClick={handleCreateDict}
                  disabled={creating}
                  className="rounded-control flex items-center bg-green-600 px-4 py-2 text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {creating && (
                    <div className="rounded-pill mr-2 h-4 w-4 animate-spin border-b-2 border-white"></div>
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
