/**
 * Field选择器组件
 *
 * 用于从可用Field列表中选择字段并添加到Model
 *
 * 功能特性:
 * - 显示可用Field列表
 * - 搜索和过滤Field
 * - 多选Field
 * - 确认选择
 */

import React, { useState, useCallback, useEffect } from 'react';
import type { MetaFieldDTO } from '~/types/field';

/**
 * Field选择器Props
 */
interface FieldSelectorProps {
  /** 是否显示选择器 */
  visible: boolean;
  /** 已选择的Field编码列表 */
  selectedFieldCodes?: string[];
  /** 关闭回调 */
  onClose: () => void;
  /** 确认选择回调 */
  onConfirm: (selectedFields: MetaFieldDTO[]) => void;
}

/**
 * Field选择器组件
 */
export function FieldSelector({
  visible,
  selectedFieldCodes = [],
  onClose,
  onConfirm,
}: FieldSelectorProps) {
  // 可用Field列表
  const [availableFields, setAvailableFields] = useState<MetaFieldDTO[]>([]);

  // 搜索关键词
  const [searchKeyword, setSearchKeyword] = useState('');

  // 当前选中的Field
  const [selectedFields, setSelectedFields] = useState<Set<string>>(new Set(selectedFieldCodes));

  // 加载状态
  const [loading, setLoading] = useState(false);

  /**
   * 加载可用Field列表
   */
  useEffect(() => {
    if (visible) {
      loadAvailableFields();
    }
  }, [visible]);

  /**
   * 加载可用Field
   */
  const loadAvailableFields = useCallback(async () => {
    setLoading(true);
    try {
      // TODO: 调用API加载可用Field列表
      // const fields = await fieldService.findAvailableFields();
      // setAvailableFields(fields);

      // 模拟数据
      const mockFields: MetaFieldDTO[] = [
        {
          id: 1,
          pid: 'field_001',
          code: 'user_name',
          name: '用户名',
          dataType: 'string',
          required: true,
          description: '用户登录名',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 2,
          pid: 'field_002',
          code: 'email',
          name: '邮箱',
          dataType: 'string',
          required: true,
          description: '用户邮箱地址',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 3,
          pid: 'field_003',
          code: 'phone',
          name: '手机号',
          dataType: 'string',
          required: false,
          description: '用户手机号码',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 4,
          pid: 'field_004',
          code: 'age',
          name: '年龄',
          dataType: 'integer',
          required: false,
          description: '用户年龄',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 5,
          pid: 'field_005',
          code: 'status',
          name: '状态',
          dataType: 'string',
          required: true,
          description: '用户状态',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ];
      setAvailableFields(mockFields);
    } catch (error) {
      console.error('Failed to load available fields:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  /**
   * 过滤Field列表
   */
  const filteredFields = availableFields.filter((field) => {
    if (!searchKeyword) return true;

    const keyword = searchKeyword.toLowerCase();
    return (
      field.code.toLowerCase().includes(keyword) ||
      field.name.toLowerCase().includes(keyword) ||
      (field.description && field.description.toLowerCase().includes(keyword))
    );
  });

  /**
   * 切换Field选择状态
   */
  const toggleFieldSelection = useCallback((fieldCode: string) => {
    setSelectedFields((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(fieldCode)) {
        newSet.delete(fieldCode);
      } else {
        newSet.add(fieldCode);
      }
      return newSet;
    });
  }, []);

  /**
   * 全选/取消全选
   */
  const toggleSelectAll = useCallback(() => {
    if (selectedFields.size === filteredFields.length) {
      setSelectedFields(new Set());
    } else {
      setSelectedFields(new Set(filteredFields.map((f) => f.code)));
    }
  }, [filteredFields, selectedFields]);

  /**
   * 确认选择
   */
  const handleConfirm = useCallback(() => {
    const selected = availableFields.filter((f) => selectedFields.has(f.code));
    onConfirm(selected);
    onClose();
  }, [availableFields, selectedFields, onConfirm, onClose]);

  /**
   * 取消选择
   */
  const handleCancel = useCallback(() => {
    setSelectedFields(new Set(selectedFieldCodes));
    setSearchKeyword('');
    onClose();
  }, [selectedFieldCodes, onClose]);

  if (!visible) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={handleCancel} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="rounded-card bg-panel relative flex max-h-[80vh] w-full max-w-4xl flex-col shadow-xl">
          {/* 标题栏 */}
          <div className="border-border border-b px-6 py-4">
            <h2 className="text-text text-lg font-semibold">选择字段</h2>
            <p className="text-text-2 mt-1 text-sm">从可用字段列表中选择要添加到模型的字段</p>
          </div>

          {/* 搜索栏 */}
          <div className="border-border border-b px-6 py-4">
            <div className="flex gap-4">
              <div className="flex-1">
                <input
                  type="text"
                  value={searchKeyword}
                  onChange={(e) => setSearchKeyword(e.target.value)}
                  placeholder="搜索字段编码、名称或描述..."
                  className="rounded-control border-border-strong focus-visible:shadow-focus w-full border px-3 py-2 focus:outline-none"
                />
              </div>
              <button
                onClick={toggleSelectAll}
                className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
              >
                {selectedFields.size === filteredFields.length ? '取消全选' : '全选'}
              </button>
            </div>
            <div className="text-text-2 mt-2 text-sm">已选择 {selectedFields.size} 个字段</div>
          </div>

          {/* Field列表 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {loading ? (
              <div className="py-12 text-center">
                <div className="rounded-pill border-accent inline-block h-8 w-8 animate-spin border-b-2"></div>
                <p className="text-text-2 mt-2 text-sm">加载中...</p>
              </div>
            ) : filteredFields.length === 0 ? (
              <div className="py-12 text-center">
                <p className="text-text-2">没有找到匹配的字段</p>
              </div>
            ) : (
              <div className="space-y-2">
                {filteredFields.map((field) => (
                  <div
                    key={field.code}
                    onClick={() => toggleFieldSelection(field.code)}
                    className={`rounded-card cursor-pointer border p-4 transition-colors ${
                      selectedFields.has(field.code)
                        ? 'bg-accent-weak border-accent'
                        : 'border-border hover:border-border-strong hover:bg-subtle'
                    }`}
                  >
                    <div className="flex items-start gap-3">
                      {/* 复选框 */}
                      <div className="mt-1 flex-shrink-0">
                        <input
                          type="checkbox"
                          checked={selectedFields.has(field.code)}
                          onChange={() => {}}
                          className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded focus:outline-none"
                        />
                      </div>

                      {/* Field信息 */}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <h3 className="text-text text-sm font-medium">{field.name}</h3>
                          <span className="text-text-2 font-mono text-xs">({field.code})</span>
                          {field.required && (
                            <span className="inline-flex rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">
                              必填
                            </span>
                          )}
                        </div>
                        <p className="text-text-2 mt-1 text-sm">{field.description || '无描述'}</p>
                        <div className="mt-2 flex gap-2">
                          <span className="bg-subtle text-text inline-flex rounded px-2 py-0.5 text-xs font-medium">
                            {field.dataType}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* 底部按钮 */}
          <div className="border-border flex justify-end gap-3 border-t px-6 py-4">
            <button
              onClick={handleCancel}
              className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
            >
              取消
            </button>
            <button
              onClick={handleConfirm}
              disabled={selectedFields.size === 0}
              className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              确定 ({selectedFields.size})
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
