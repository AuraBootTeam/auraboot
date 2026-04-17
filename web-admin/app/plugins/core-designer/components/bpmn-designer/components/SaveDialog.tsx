/**
 * BPMN流程定义保存对话框
 */

import React, { useState, useEffect } from 'react';

export interface ProcessMetadata {
  id?: string;
  name: string;
  key: string;
  version?: number;
  versionName?: string; // 语义化版本号，如 1.0.0
  description?: string;
  category?: string;
}

interface SaveDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (metadata: ProcessMetadata) => Promise<void>;
  initialData: {
    id?: string;
    name: string;
    key: string;
    version?: number;
    versionName?: string;
    description?: string;
    category?: string;
  };
  isNew: boolean;
}

interface ValidationErrors {
  name?: string;
  key?: string;
  versionName?: string;
  description?: string;
  category?: string;
}

export function SaveDialog({ isOpen, onClose, onSave, initialData, isNew }: SaveDialogProps) {
  const [name, setName] = useState(initialData.name);
  const [key, setKey] = useState(initialData.key);
  const [versionName, setVersionName] = useState(initialData.versionName || '1.0.0');
  const [description, setDescription] = useState(initialData.description || '');
  const [category, setCategory] = useState(initialData.category || '');
  const [errors, setErrors] = useState<ValidationErrors>({});
  const [isSaving, setIsSaving] = useState(false);

  // 更新表单数据当initialData变化时
  useEffect(() => {
    setName(initialData.name);
    setKey(initialData.key);
    setVersionName(initialData.versionName || '1.0.0');
    setDescription(initialData.description || '');
    setCategory(initialData.category || '');
    setErrors({});
  }, [initialData, isOpen]);

  // 验证表单
  const validateForm = (): boolean => {
    const newErrors: ValidationErrors = {};

    // 验证名称
    if (!name.trim()) {
      newErrors.name = '流程名称不能为空';
    } else if (name.length > 100) {
      newErrors.name = '流程名称不能超过100个字符';
    }

    // 验证标识
    if (!key.trim()) {
      newErrors.key = '流程标识不能为空';
    } else if (!/^[a-zA-Z0-9_-]+$/.test(key)) {
      newErrors.key = '流程标识只能包含字母、数字、下划线和连字符';
    }

    // 验证语义化版本号
    if (versionName.trim()) {
      // 支持格式: x.y.z 或 x.y 或 x
      if (!/^\d+(\.\d+){0,2}$/.test(versionName.trim())) {
        newErrors.versionName = '版本号格式不正确，应为 x.y.z 格式（如 1.0.0）';
      }
    }

    // 验证描述
    if (description && description.length > 500) {
      newErrors.description = '描述不能超过500个字符';
    }

    // 验证分类
    if (category && category.length > 50) {
      newErrors.category = '分类不能超过50个字符';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 处理提交
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) {
      return;
    }

    setIsSaving(true);
    try {
      const metadata: ProcessMetadata = {
        id: initialData.id,
        name: name.trim(),
        key: key.trim(),
        version: isNew ? 1 : (initialData.version || 1) + 1,
        versionName: versionName.trim() || undefined,
        description: description.trim() || undefined,
        category: category.trim() || undefined,
      };

      await onSave(metadata);
    } catch (error) {
      console.error('保存失败:', error);
      // 错误由父组件处理
    } finally {
      setIsSaving(false);
    }
  };

  // 处理ESC键关闭
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  // Only close when the click target is the backdrop itself, not a descendant.
  // stopPropagation on the inner panel already blocks bubbling, but this guard
  // makes the intent explicit and survives future refactors that might
  // accidentally drop the inner handler.
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div
      className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black"
      onClick={handleBackdropClick}
      data-testid="bpmn-save-dialog-backdrop"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="bpmn-save-dialog-title"
        className="mx-4 w-full max-w-lg rounded-lg bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        data-testid="bpmn-save-dialog-panel"
      >
        <div className="mb-6 flex items-center justify-between">
          <h2 id="bpmn-save-dialog-title" className="text-xl font-semibold text-gray-900">保存流程定义</h2>
          <button
            onClick={onClose}
            className="text-gray-400 transition-colors hover:text-gray-600"
            type="button"
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

        <form onSubmit={handleSubmit}>
          {/* 流程名称 */}
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">
              流程名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.name ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder="例如: 员工请假审批流程"
              disabled={isSaving}
            />
            {errors.name && <p className="mt-1 text-sm text-red-600">{errors.name}</p>}
          </div>

          {/* 流程标识 */}
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">
              流程标识 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              className={`w-full rounded-md border px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.key ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder="例如: leave_approval_process"
              disabled={isSaving}
            />
            {errors.key && <p className="mt-1 text-sm text-red-600">{errors.key}</p>}
          </div>

          {/* 版本号（数字） */}
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">版本号（数字）</label>
            <div className="flex items-center">
              <input
                type="text"
                value={isNew ? 1 : (initialData.version || 1) + 1}
                readOnly
                className="w-full rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-600"
              />
              <span className="ml-2 text-sm whitespace-nowrap text-gray-500">(自动递增)</span>
            </div>
          </div>

          {/* 语义化版本号 */}
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">语义化版本号</label>
            <input
              type="text"
              value={versionName}
              onChange={(e) => setVersionName(e.target.value)}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.versionName ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder="例如: 1.0.0"
              disabled={isSaving}
            />
            {errors.versionName && (
              <p className="mt-1 text-sm text-red-600">{errors.versionName}</p>
            )}
            <p className="mt-1 text-xs text-gray-500">格式: x.y.z（如 1.0.0 或 2.1.3）</p>
          </div>

          {/* 描述 */}
          <div className="mb-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">描述</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.description ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder="流程的详细描述..."
              disabled={isSaving}
            />
            {errors.description && (
              <p className="mt-1 text-sm text-red-600">{errors.description}</p>
            )}
          </div>

          {/* 分类 */}
          <div className="mb-6">
            <label className="mb-1 block text-sm font-medium text-gray-700">分类</label>
            <input
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.category ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder="例如: 人事管理"
              disabled={isSaving}
            />
            {errors.category && <p className="mt-1 text-sm text-red-600">{errors.category}</p>}
          </div>

          {/* 按钮 */}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
              disabled={isSaving}
            >
              取消
            </button>
            <button
              type="submit"
              data-testid="bpmn-save-dialog-submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={isSaving}
            >
              {isSaving ? '保存中...' : '确定'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
